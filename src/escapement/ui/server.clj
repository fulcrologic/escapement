(ns escapement.ui.server
  "Read-only EQL HTTP API for the Escapement runtime — the `--api-server <port>` surface.

   `POST /api` with a transit+json body that is an EQL query; the response is the transit-encoded
   result of running that query through `escapement.ui.resolvers` over a disk read store rooted at the
   run's `--work-dir`. There are NO mutations: this is an observability surface (sessions, transcript,
   artifacts, invocation drill-in), not a control channel.

   Uses babashka's bundled `org.httpkit.server`. CORS is permissive (`*`) with an OPTIONS preflight
   branch so the browser SPA can call it from the shadow dev origin.

   The server also serves the compiled browser SPA. So a single `--api-server <port>` both hosts the
   app and answers its EQL queries on the same origin. The bundle is NOT committed to git (it would
   bloat history); it is resolved on demand, cheapest-first:

     1. classpath `public/js/main/main.js` — present in the release jar and after a local `bb build-ui`;
     2. local cache `~/.cache/escapement/ui/<version>/main.js`;
     3. fetched from the GitHub release asset for that version and verified against the SHA-256 in the
        committed `escapement-ui.edn` manifest, then cached.

   It is served content-addressed at `/js/main/<sha>.js` with an immutable cache header; `GET /`
   serves `index.html` (a committed template) with that SHA path injected, so a freshly built bundle
   never reads stale from the browser cache. Everything degrades gracefully: with no bundle and no
   network, `/` still loads and shows a notice, and the `/api` surface always works.

   The server is constructed with the active session id, so the global active-session resolver can
   report the currently-running session. Reads hit the live transcript file each call, so the active
   session is visible as it is written (snapshot-per-request; no streaming in v1)."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [cognitect.transit :as transit]
    [escapement.storage.disk-read :as disk-read]
    [escapement.ui.resolvers :as resolvers]
    [escapement.ui.ws-push :as ws-push]
    [org.httpkit.server :as http])
  (:import
    (java.io ByteArrayInputStream ByteArrayOutputStream InputStream)
    (java.net URI)
    (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
    (java.security MessageDigest)))

(def ^:private cors-headers
  {"Access-Control-Allow-Origin"  "*"
   "Access-Control-Allow-Methods" "POST, OPTIONS"
   "Access-Control-Allow-Headers" "Content-Type"})

(defn- read-transit
  "Read a transit+json EQL query from the request `body` (an InputStream)."
  [^InputStream body]
  (transit/read (transit/reader body :json)))

(defn- write-transit
  "Encode `data` as transit+json bytes."
  [data]
  (let [out (ByteArrayOutputStream.)]
    (transit/write (transit/writer out :json) data)
    (.toByteArray out)))

(defn- transit-response [status data]
  {:status  status
   :headers (assoc cors-headers "Content-Type" "application/transit+json")
   :body    (write-transit data)})

(def ^:private content-types
  {"html" "text/html; charset=utf-8"
   "js"   "text/javascript"
   "css"  "text/css"
   "json" "application/json"
   "map"  "application/json"
   "edn"  "application/edn"
   "svg"  "image/svg+xml"
   "png"  "image/png"
   "ico"  "image/x-icon"})

(defn- content-type-for
  "Guess a Content-Type header from the `path` extension; defaults to octet-stream."
  [path]
  (let [ext (str/lower-case (or (last (str/split path #"\.")) ""))]
    (get content-types ext "application/octet-stream")))

(defn- resource-path
  "Map a request `uri` to a classpath resource path under `public/`. A leading slash is stripped;
   returns nil for paths that try to escape the prefix (`..`)."
  [uri]
  (let [rel (str/replace-first (or uri "") #"^/" "")]
    (when-not (str/includes? rel "..")
      (str "public/" rel))))

(defn- static-response
  "Serve a compiled-SPA static file for a `GET uri` from the classpath `public/` tree (e.g. the dev
   `cljs-runtime/` chunks). nil when no such resource exists (caller falls through to 404)."
  [uri]
  (when-let [rpath (resource-path uri)]
    (when-let [res (io/resource rpath)]
      {:status  200
       :headers {"Content-Type" (content-type-for rpath)}
       :body    (io/input-stream res)})))

;; --------------------------------------------------------------------------
;; Browser SPA bundle: resolve classpath → cache → GitHub release (verified)
;; --------------------------------------------------------------------------

(def ^:private bundle-resource "public/js/main/main.js")
(def ^:private content-addressed-bundle
  "A `/js/main/<sha>.js` request — the cache-busting, content-addressed bundle URL."
  #"^/js/main/[0-9a-f]{6,64}\.js$")

(defn- sha256-hex
  "Lowercase hex SHA-256 of `bs`."
  [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff))
               (.digest (MessageDigest/getInstance "SHA-256") bs))))

(defn- ui-manifest
  "The committed fetch manifest `escapement-ui.edn` (`{:version :sha256 :asset}`), or nil if absent."
  []
  (some-> (io/resource "escapement-ui.edn") slurp edn/read-string))

(defn- classpath-bundle-bytes
  "Bytes of the on-classpath SPA bundle (release jar / local `bb build-ui`), or nil."
  []
  (when-let [res (io/resource bundle-resource)]
    (with-open [in (io/input-stream res)] (.readAllBytes in))))

(def ^:private ui-sha
  "SHA-256 of the bundle this server will serve, for the content-addressed URL. Hashes the actual
   on-classpath bundle when present (dev build or release jar) so the advertised SHA always matches
   the served bytes; falls back to the manifest SHA in the pure-fetch (bbin) case. Computed once per
   server process — restart `--api-server` to re-hash after a `bb watch-ui` rebuild."
  (delay
    (or (some-> (classpath-bundle-bytes) sha256-hex)
      (:sha256 (ui-manifest)))))

(defn- cache-file
  "Local cache path for the fetched bundle of `version`, under `$XDG_CACHE_HOME` (or `~/.cache`)."
  ^java.io.File [version]
  (let [home (or (System/getenv "XDG_CACHE_HOME")
               (str (System/getProperty "user.home") "/.cache"))]
    (io/file home "escapement" "ui" (str version) "main.js")))

(defn- download-bytes
  "GET `url` and return the body bytes, or throw on a non-200."
  [^String url]
  (let [resp (.send (HttpClient/newHttpClient)
               (.build (.GET (HttpRequest/newBuilder (URI. url))))
               (HttpResponse$BodyHandlers/ofByteArray))]
    (if (= 200 (.statusCode resp))
      (.body resp)
      (throw (ex-info "Escapement UI bundle download failed" {:url url :status (.statusCode resp)})))))

(defn- fetch-ui-bundle!
  "Return the cached release bundle as a File, downloading it from the GitHub release asset on first
   use and verifying it against the manifest SHA-256 before caching. nil when there is no manifest
   (nothing to fetch)."
  []
  (when-let [{:keys [version sha256]} (ui-manifest)]
    (let [f (cache-file version)]
      (when-not (.exists f)
        (let [url   (str "https://github.com/fulcrologic/escapement/releases/download/escapement-"
                      version "/main.js")
              bytes (download-bytes url)
              got   (sha256-hex bytes)]
          (when (and sha256 (not= sha256 got))
            (throw (ex-info "Escapement UI bundle checksum mismatch"
                     {:url url :expected sha256 :actual got})))
          (io/make-parents f)
          (with-open [o (io/output-stream f)] (.write o ^bytes bytes))))
      f)))

(defn- bundle-response
  "Serve the SPA bundle, resolving it classpath → cache → release-fetch. `immutable?` marks the
   content-addressed URL as cacheable forever. With no bundle and no network, returns a tiny script
   that renders a notice into `#app` so `/` is never a blank page."
  [immutable?]
  (let [stream (or (some-> (io/resource bundle-resource) io/input-stream)
                 (try (some-> (fetch-ui-bundle!) io/input-stream)
                   (catch Throwable _ nil)))]
    (if stream
      {:status  200
       :headers {"Content-Type"  "text/javascript"
                 "Cache-Control" (if immutable? "public, max-age=31536000, immutable" "no-cache")}
       :body    stream}
      {:status  200
       :headers {"Content-Type" "text/javascript" "Cache-Control" "no-store"}
       :body    (str "document.getElementById('app').innerText="
                  "'Escapement UI bundle is unavailable offline. Run `bb build-ui`, or connect once "
                  "so it can be fetched from the GitHub release.';")})))

(defn- index-response
  "Serve `index.html` (a committed template) with the content-addressed bundle URL injected in place
   of the `__MAIN_JS__` token. The HTML carries `no-cache` so a new bundle SHA is always picked up."
  []
  (when-let [res (io/resource "public/index.html")]
    (let [src (if-let [sha @ui-sha] (str "/js/main/" sha ".js") "/js/main/main.js")]
      {:status  200
       :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-cache"}
       :body    (str/replace (slurp res) "__MAIN_JS__" src)})))

(defn make-handler
  "Return a Ring handler closing over `ctx` (the Pathom env: `:escapement/store`,
   `:escapement/active-session-id`, optional `:escapement/chart`, `:escapement/controller`,
   `:escapement/live`). Handles `OPTIONS` (preflight),
   `POST /api` (run the EQL query), and `GET` for the SPA (`/` → index template, `/js/main/<sha>.js`
   or `/js/main/main.js` → the resolved bundle, other paths → classpath statics); anything unmatched
   is 404. A malformed query / resolver error is returned as a transit `{:error …}` with status 500
   rather than dropping the connection."
  [ctx]
  (let [ws-route (when-let [hub (:escapement/ws-push ctx)]
                   (ws-push/ws-handler hub (:escapement/ws-handlers ctx)))]
    (fn [{:keys [request-method uri] :as req}]
      (let [{:keys [body]} req]
        (cond
          (and ws-route (= :get request-method) (= "/ws" uri))
          (ws-route req)

          (= :options request-method)
          {:status 200 :headers cors-headers :body ""}

          (and (= :post request-method) (= "/api" uri))
      (try
        (transit-response 200 (resolvers/process ctx (read-transit body)))
        (catch Throwable t
          (transit-response 500 {:error (ex-message t)})))

      (= :get request-method)
      (cond
        (or (str/blank? uri) (= "/" uri) (= "/index.html" uri))
        (or (index-response) {:status 404 :headers cors-headers :body "Not found."})

        (or (= "/js/main/main.js" uri) (re-matches content-addressed-bundle uri))
        (bundle-response (boolean (re-matches content-addressed-bundle uri)))

        :else
        (or (static-response uri) {:status 404 :headers cors-headers :body "Not found."}))

          :else
          {:status 404 :headers cors-headers :body "Not found. POST an EQL transit query to /api."})))))

(defn start!
  "Start the read-only EQL API server. Options:

     * `:port`              — (required) TCP port to listen on.
     * `:work-dir`          — (required) sessions-root the runner writes under (its `--work-dir`).
     * `:active-session-id` — id of the session this run is producing (the active-session resolver).
     * `:chart`             — optional chart map for the chart-definition resolver.
     * `:controller`        — optional live debug controller (pause/step/continue mutations).
     * `:live`              — optional control handle (atom filled by `run!`'s on-env-ready) the live
                              resolvers deref to reach the running env/queue. Nil-tolerant.
     * `:ws-push`           — optional fan-out hub (from `escapement.ui.ws-push/new-hub`) that powers
                              the live `GET /ws` push endpoint. When present, the runner's
                              transcript-tap should call `ws-push/publish!` on this same hub (the CLI
                              composes it into `:transcript-tap`). Nil → no `/ws` route.
     * `:ws-handlers`       — optional back-channel seam map `{:control fn :answer fn}` dispatched for
                              inbound UI frames (see `ws-push/dispatch-inbound!`). `:answer` is the
                              human-input delivery hook (task 003).

   Returns a handle `{:stop <fn> :port <port> :ws-push <hub>}`; call `stop!` (or invoke `:stop`)."
  [{:keys [port work-dir active-session-id chart controller live ws-push ws-handlers]}]
  (let [ctx     {:escapement/store              (disk-read/new-store work-dir)
                 :escapement/active-session-id  active-session-id
                 :escapement/chart              chart
                 :escapement/controller         controller
                 :escapement/live               live
                 :escapement/ws-push            ws-push
                 :escapement/ws-handlers        ws-handlers}
        stop-fn (http/run-server (make-handler ctx) {:port port})]
    {:stop stop-fn :port port :ws-push ws-push}))

(defn stop!
  "Stop a server `handle` returned by `start!`."
  [handle]
  (when-let [stop-fn (:stop handle)]
    (stop-fn)))
