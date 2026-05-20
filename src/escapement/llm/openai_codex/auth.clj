(ns escapement.llm.openai-codex.auth
  "OAuth 2.0 + PKCE authentication flow against auth.openai.com.

  Manages the ChatGPT account credential lifecycle:
    - Initiates the browser-based PKCE authorization flow
    - Exchanges authorization codes for tokens
    - Refreshes expired tokens automatically
    - Persists credentials to `~/.escapement/openai-auth.json` (mode 0600)

  Designed to be Babashka-compatible: uses only `babashka.http-client`,
  stdlib Java security/crypto classes, and `cheshire`."
  (:require
    [babashka.http-client :as http]
    [babashka.process :as process]
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn ?]])
  (:import
    (java.io BufferedReader InputStreamReader OutputStreamWriter)
    (java.net ServerSocket URLEncoder)
    (java.security MessageDigest SecureRandom)
    (java.util Base64)))

;;; ---------------------------------------------------------------------------
;;; Constants

(def AUTHORIZE-URL "https://auth.openai.com/oauth/authorize")
(def TOKEN-URL "https://auth.openai.com/oauth/token")
(def CLIENT-ID "app_EMoamEEZ73f0CkXaXp7hrann")
(def REDIRECT-URI "http://localhost:1455/auth/callback")
(def SCOPE "openid profile email offline_access")
(def CALLBACK-PORT 1455)
(def AUTH-FILE (str (System/getProperty "user.home") "/.escapement/openai-auth.json"))

;;; ---------------------------------------------------------------------------
;;; Pure crypto helpers

(defn- url-safe-b64
  "Encodes `bytes` as URL-safe base64 with no padding."
  ^String [^bytes bs]
  (-> (Base64/getUrlEncoder)
    (.withoutPadding)
    (.encodeToString bs)))

(defn- sha256
  "Returns the SHA-256 digest of the UTF-8 `s` as a byte array."
  ^bytes [^String s]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.digest md (.getBytes s "UTF-8"))))

(>defn generate-pkce
  "Generates a PKCE verifier/challenge pair.

Returns `{:verifier <url-safe-base64-string> :challenge <url-safe-base64-sha256>}`."
  []
  [=> [:map [:verifier :string] [:challenge :string]]]
  (let [buf       (byte-array 48)
        _         (.nextBytes (SecureRandom.) buf)
        verifier  (url-safe-b64 buf)
        challenge (url-safe-b64 (sha256 verifier))]
    {:verifier verifier :challenge challenge}))

(>defn generate-state
  "Generates a random 16-byte hex string for OAuth state parameter."
  []
  [=> :string]
  (let [buf (byte-array 16)]
    (.nextBytes (SecureRandom.) buf)
    (str/join (map #(format "%02x" (bit-and % 0xff)) buf))))

;;; ---------------------------------------------------------------------------
;;; URL building

(defn- url-encode
  "URL-encodes `s` using percent-encoding (spaces become %20, not +)."
  [s]
  (-> (URLEncoder/encode (str s) "UTF-8")
    (str/replace "+" "%20")))

(>defn build-authorize-url
  "Builds the OAuth authorization URL with PKCE and all required query params.

- `pkce`  — map from `generate-pkce` with `:verifier` and `:challenge`
- `state` — random state string from `generate-state`"
  [pkce state]
  [[:map [:verifier :string] [:challenge :string]] :string => :string]
  (let [params [["response_type" "code"]
                ["client_id" CLIENT-ID]
                ["redirect_uri" REDIRECT-URI]
                ["scope" SCOPE]
                ["code_challenge" (:challenge pkce)]
                ["code_challenge_method" "S256"]
                ["state" state]
                ["id_token_add_organizations" "true"]
                ["codex_cli_simplified_flow" "true"]
                ["originator" "codex_cli_rs"]]
        qs     (str/join "&" (map (fn [[k v]] (str k "=" (url-encode v))) params))]
    (str AUTHORIZE-URL "?" qs)))

;;; ---------------------------------------------------------------------------
;;; Browser launch

(>defn open-browser!
  "Opens `url` in the system default browser. Best-effort; prints a manual URL on failure."
  [url]
  [:string => :nil]
  (let [os  (str/lower-case (System/getProperty "os.name"))
        cmd (cond
              (str/includes? os "mac") ["open" url]
              (str/includes? os "win") ["cmd" "/c" "start" url]
              :else ["xdg-open" url])]
    (try
      (apply process/shell {:out :inherit :err :inherit} cmd)
      (catch Throwable _
        (binding [*out* *err*]
          (println "Open this URL in your browser:" url)))))
  nil)

;;; ---------------------------------------------------------------------------
;;; Callback HTTP server

(defn- parse-query-string
  "Parses a URL query string into a string->string map."
  [qs]
  (when (seq qs)
    (into {}
      (map (fn [part]
             (let [idx (.indexOf ^String part (int \=))]
               (if (pos? idx)
                 [(subs part 0 idx) (subs part (inc idx))]
                 [part ""])))
        (str/split qs #"&")))))

(defn- write-http-response!
  "Writes a minimal HTTP/1.1 response to `out-writer`."
  [out-writer status-line body]
  (let [body-bytes (.getBytes ^String body "UTF-8")
        n          (count body-bytes)]
    (.write ^OutputStreamWriter out-writer
      (str status-line "\r\n"
        "Content-Type: text/html\r\n"
        "Content-Length: " n "\r\n"
        "Connection: close\r\n"
        "\r\n"
        body))))

(>defn await-callback!
  "Listens on `CALLBACK-PORT` for a single OAuth callback request.

- `expected-state` — the state string sent in the authorization URL
- `timeout-ms`     — socket timeout in milliseconds (default 300000)

Returns `{:code <auth-code>}` on success or `{:error <reason-string>}` on failure."
  [expected-state timeout-ms]
  [:string :int => [:map]]
  (with-open [server (ServerSocket. CALLBACK-PORT)]
    (.setSoTimeout server timeout-ms)
    (let [socket (try (.accept server) (catch Throwable t {:error (.getMessage t)}))]
      (if (map? socket)
        socket
        (try
          (let [reader (BufferedReader. (InputStreamReader. (.getInputStream socket) "UTF-8"))
                line   (.readLine reader)
                ;; line: "GET /auth/callback?code=...&state=... HTTP/1.1"
                [_ path] (when line (re-find #"GET (\S+) HTTP" line))
                qs     (when path
                         (let [qi (.indexOf ^String path (int \?))]
                           (when (pos? qi) (subs path (inc qi)))))
                params (parse-query-string qs)
                code   (get params "code")
                state  (get params "state")
                writer (OutputStreamWriter. (.getOutputStream socket) "UTF-8")]
            (cond
              (not= state expected-state)
              (do
                (write-http-response! writer "HTTP/1.1 400 Bad Request"
                  "<html><body><h1>Error: state mismatch. Please try again.</h1></body></html>")
                (.flush writer)
                {:error "state-mismatch"})

              (nil? code)
              (do
                (write-http-response! writer "HTTP/1.1 400 Bad Request"
                  "<html><body><h1>Error: missing code parameter.</h1></body></html>")
                (.flush writer)
                {:error "missing-code"})

              :else
              (do
                (write-http-response! writer "HTTP/1.1 200 OK"
                  "<html><body><h1>Authentication successful. You can close this tab.</h1></body></html>")
                (.flush writer)
                {:code code})))
          (finally
            (try (.close socket) (catch Throwable _ nil))))))))

;;; ---------------------------------------------------------------------------
;;; Token exchange

(defn- build-form-body
  "Builds a URL-encoded form body string from a params map."
  [params]
  (str/join "&" (map (fn [[k v]] (str (name k) "=" (url-encode v))) params)))

(defn- parse-token-response
  "Parses a token endpoint JSON body into our auth map shape."
  [body]
  (let [parsed     (json/parse-string body true)
        access     (:access_token parsed)
        refresh    (:refresh_token parsed)
        expires-in (:expires_in parsed)]
    (when (or (nil? access) (nil? refresh) (not (number? expires-in)))
      (throw (ex-info "Token response missing required fields"
               {:body body :parsed parsed})))
    {:access-token  access
     :refresh-token refresh
     :expires-at    (+ (System/currentTimeMillis) (* 1000 (long expires-in)))}))

(>defn exchange-code
  "Exchanges an authorization `code` for access/refresh tokens using PKCE `verifier`.

Returns `{:access-token :refresh-token :expires-at}`.
Throws ex-info with `:status` and `:body` on non-2xx response."
  [code verifier]
  [:string :string => [:map [:access-token :string] [:refresh-token :string] [:expires-at :int]]]
  (let [form   (build-form-body {:grant_type    "authorization_code"
                                 :code          code
                                 :code_verifier verifier
                                 :client_id     CLIENT-ID
                                 :redirect_uri  REDIRECT-URI})
        resp   (http/post TOKEN-URL
                 {:headers {"Content-Type" "application/x-www-form-urlencoded"}
                  :body    form
                  :throw   false})
        status (:status resp)
        body   (:body resp)]
    (when-not (and (>= status 200) (< status 300))
      (throw (ex-info (str "Token exchange failed: HTTP " status)
               {:status status :body body})))
    (parse-token-response body)))

(>defn refresh-token!
  "Exchanges a `refresh-token` for a new access/refresh token pair.

Returns `{:access-token :refresh-token :expires-at}`.
Throws ex-info with `:status` and `:body` on non-2xx response."
  [refresh-token]
  [:string => [:map [:access-token :string] [:refresh-token :string] [:expires-at :int]]]
  (let [form   (build-form-body {:grant_type    "refresh_token"
                                 :refresh_token refresh-token
                                 :client_id     CLIENT-ID})
        resp   (http/post TOKEN-URL
                 {:headers {"Content-Type" "application/x-www-form-urlencoded"}
                  :body    form
                  :throw   false})
        status (:status resp)
        body   (:body resp)]
    (when-not (and (>= status 200) (< status 300))
      (throw (ex-info (str "Token refresh failed: HTTP " status)
               {:status status :body body})))
    (parse-token-response body)))

;;; ---------------------------------------------------------------------------
;;; JWT decoding

(>defn decode-jwt
  "Decodes the payload of a JWT token (no signature verification).

Returns the payload as a keywordized map, or nil on any parse failure."
  [token]
  [:string => (? :map)]
  (try
    (let [parts   (str/split token #"\.")
          payload (nth parts 1 nil)]
      (when payload
        (let [padded  (let [r (mod (count payload) 4)]
                        (if (zero? r) payload (str payload (subs "====" 0 (- 4 r)))))
              decoded (.decode (Base64/getUrlDecoder) ^String padded)]
          (json/parse-string (String. decoded "UTF-8") true))))
    (catch Throwable _ nil)))

(>defn extract-account-id
  "Extracts the ChatGPT account ID from the JWT `token`.

Looks for `https://api.openai.com/auth` claim nested under `chatgpt_account_id`.
Throws ex-info when the claim is absent or the token is unparseable."
  [token]
  [:string => :string]
  (let [payload    (decode-jwt token)
        auth-kw    (keyword "https://api.openai.com/auth")
        account-id (get-in payload [auth-kw :chatgpt_account_id])]
    (when (nil? account-id)
      (let [prefix (if (> (count token) 20) (subs token 0 20) token)]
        (throw (ex-info "Could not extract chatgpt-account-id from token"
                 {:token-prefix prefix}))))
    account-id))

;;; ---------------------------------------------------------------------------
;;; Credential persistence

(>defn load-auth!
  "Loads saved credentials from `AUTH-FILE`.

Returns the auth map (with keywordized keys) or nil when the file does not
exist or cannot be parsed."
  []
  [=> (? :map)]
  (let [f (io/file AUTH-FILE)]
    (when (.exists f)
      (try
        (json/parse-string (slurp f) true)
        (catch Throwable e
          (binding [*out* *err*]
            (println "WARN [openai-codex/auth] Could not parse auth file:" (.getMessage e)))
          nil)))))

(>defn save-auth!
  "Saves `auth-map` to `AUTH-FILE` as pretty-printed JSON with 0600 permissions."
  [auth-map]
  [:map => :nil]
  (io/make-parents AUTH-FILE)
  (spit AUTH-FILE (json/generate-string auth-map {:pretty true}))
  (let [f (java.io.File. AUTH-FILE)]
    (.setReadable f false false)
    (.setReadable f true true)
    (.setWritable f false false)
    (.setWritable f true true)
    (.setExecutable f false false))
  nil)

;;; ---------------------------------------------------------------------------
;;; Token freshness

(defn- ensure-valid-token!
  "Returns `auth` if the access token is fresh (>60s left), or transparently
   refreshes it, persists the new credentials, and returns the updated map."
  [auth]
  (if (> (:expires-at auth 0) (+ (System/currentTimeMillis) 60000))
    auth
    (let [new-tokens (refresh-token! (:refresh-token auth))
          account-id (extract-account-id (:access-token new-tokens))
          updated    (merge auth new-tokens {:account-id account-id})]
      (save-auth! updated)
      updated)))

;;; ---------------------------------------------------------------------------
;;; Interactive login flow

(>defn login-flow!
  "Runs the full interactive OAuth PKCE login flow in the terminal.

Opens the browser, waits for the callback, exchanges the code, persists
credentials, and returns the full auth map."
  []
  [=> :map]
  (let [pkce  (generate-pkce)
        state (generate-state)
        url   (build-authorize-url pkce state)]
    (println (str "Opening browser for ChatGPT login. If it does not open, visit:\n" url))
    (open-browser! url)
    (let [callback (await-callback! state 300000)]
      (when (:error callback)
        (throw (ex-info (str "OAuth callback failed: " (:error callback))
                 {:error (:error callback)})))
      (let [tokens     (exchange-code (:code callback) (:verifier pkce))
            account-id (extract-account-id (:access-token tokens))
            auth-map   (assoc tokens :account-id account-id)]
        (save-auth! auth-map)
        auth-map))))

(>defn get-auth!
  "Returns the current auth credentials, running the login flow if not already authenticated.

Automatically refreshes tokens if they are within 60 seconds of expiry.
Returns a map with at minimum `:access-token` and `:account-id`."
  []
  [=> [:map [:access-token :string] [:account-id :string]]]
  (let [auth (or (load-auth!) (login-flow!))]
    (select-keys (ensure-valid-token! auth) [:access-token :account-id])))
