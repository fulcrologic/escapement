(ns escapement.cli
  "Babashka entry point for the agent.

  Subcommands:

    run <chart-sym>   — Load and execute a chart, writing a JSONL transcript and checkpoints.
      Flags:
        --input <edn-file>      Initial data (EDN map).
        --param key=value       One-shot initial-data entry. Repeatable. Values are
                                EDN-read: numbers/keywords/booleans/collections
                                parse natively; bare words become strings (so
                                --param name=alice does the obvious thing).
                                Merged on top of --input.
        --session <id>          Session id; default a random UUID.
        --work-dir <path>       Parent dir for per-session output; default .escapement
        --transcript <path>     Transcript path; default <work-dir>/<session>/transcript.jsonl
        --checkpoint-dir <dir>  Checkpoint dir; default <work-dir>/<session>/checkpoints
        --base-dir <path>       Dir the agent's file/shell tools operate in: `:fs/*`
                                resolve relative paths against it and `:shell/run`
                                runs there. Default <work-dir>/<session> (the session
                                dir). Set this to a repo the chart clones/operates on
                                to decouple tool I/O from the session/checkpoint dir.
        --resume                Resume from saved working memory.
        --backend (api|codex|openai|ollama|opencode-go)  LLM backend (optional; only needed for LLM charts).
        --model <name>          Model name.
        --api-base-url <url>    API base URL.
        --api-key-env <name>    Env-var name holding the API key.
        --tools-ns <sym[,sym…]> Comma-separated qualified symbols of registration
                                fns called with the builtin registry atom. Each fn
                                can register any number of additional tools.
                                Repeatable; values accumulate.
                                e.g. --tools-ns my.app.tools/register-tools!
        --source-paths <p[:p…]> Colon-separated extra classpath roots, prepended
                                via babashka.classpath/add-classpath. CLI-supplied
                                paths resolve against cwd; config-supplied paths
                                resolve against the config root.
        --deps <edn>            Inline EDN map of additional runtime deps merged on
                                top of `.escapement.edn` :deps. Same coordinate
                                shape as deps.edn's :deps map.
        --trace                 Emit per-tick transcript events.
        --log-level <lvl>       Logging verbosity: debug|info|warn|error
                                (case-insensitive). Defaults to INFO for
                                headless (--no-tui) runs so live archiving
                                is cheap; interactive runs keep the library
                                default (DEBUG). An explicit value always
                                wins.
        --no-tui                Force-disable the TUI (overrides
                                ^{:interactive? true} chart metadata).
        --dump-d2               Print the chart's d2 diagram source to stdout
                                and exit without running it. Requires no LLM
                                backend and creates no session dir. Pipe to the
                                `d2` binary to render (e.g. ... --dump-d2 | d2 -).
        --api-server <port>     Also start a read-only EQL HTTP API on <port>
                                for the duration of the run. POST a transit
                                EQL query to /api to inspect the active session
                                (and browse past sessions under --work-dir).
        --debug                 Enable debug mode: forces the TUI on (even
                                for non-interactive charts), enables the
                                inspector overlay (`?` to open), and — when
                                `:debug :auto-pause?` is true in
                                `.escapement.edn` (default true) — pauses
                                before the very first event so you can step.

    info              — Print version + environment info.

  Exit codes: 0 success, 1 chart error, 2 usage error."
  (:require
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.fulcrologic.statecharts :as sc]
   [escapement.config :as config]
   [escapement.debug.control-handle :as ctrl-handle]
   [escapement.debug.controller :as dbg]
   [escapement.debug.d2 :as d2]
   [escapement.invocation.human-input :as human-input]
   [escapement.llm.providers :as providers]
   [escapement.llm.preferences :as preferences]
   [escapement.llm.ratings :as ratings]
   [escapement.runner :as runner]
   [escapement.tui :as tui]
   [taoensso.timbre :as timbre]))

(def ^:const version "0.1.0")

(defn- die!
  ([msg] (die! msg 2))
  ([msg code]
   (binding [*out* *err*]
     (println msg))
   (System/exit code)))

(defn- parse-args
  "Split positional args from --flag [value] options into `{:positional [...] :opts {...}}`.

   Boolean flags can be declared in `bool-flags`; multi-value flags in `multi-flags`
   accumulate into a vector (later occurrences appended). Everything else is
   single-valued, last write wins."
  ([args bool-flags] (parse-args args bool-flags #{}))
  ([args bool-flags multi-flags]
   (loop [args args
          pos  []
          opts {}]
     (if (empty? args)
       {:positional pos :opts opts}
       (let [a (first args)]
         (cond
           (str/starts-with? a "--")
           ;; Support both `--flag value` (space) and `--flag=value` (inline) forms.
           (let [eq      (.indexOf ^String a (int \=))
                 inline? (>= eq 0)
                 k       (keyword (subs a 2 (if inline? eq (count a))))
                 inline-v (when inline? (subs a (inc eq)))]
             (cond
               (contains? bool-flags k)
               (recur (rest args) pos (assoc opts k true))

               (contains? multi-flags k)
               (if-let [v (or inline-v (second args))]
                 (recur (if inline? (rest args) (drop 2 args)) pos (update opts k (fnil conj []) v))
                 (die! (str "Flag " a " requires a value")))

               :else
               (if-let [v (or inline-v (second args))]
                 (recur (if inline? (rest args) (drop 2 args)) pos (assoc opts k v))
                 (die! (str "Flag " a " requires a value")))))
           :else
           (recur (rest args) (conj pos a) opts)))))))

(def ^:private log-levels
  "Accepted --log-level values mapped to timbre min-level keywords."
  {"debug" :debug "info" :info "warn" :warn "error" :error})

(defn resolve-log-level
  "Pure resolution of the effective timbre min-level for a run.

   `opts` is the parsed `--flag` map (expects optional `:log-level` string
   and boolean `:no-tui`).

   Rules:
   * An explicit `--log-level` always wins (case-insensitive; one of
     debug|info|warn|error). An unrecognized value returns
     `[:error <msg>]` so the caller can `die!` with usage exit 2.
   * No explicit value + headless (`--no-tui`) → `:info` (cheap archiving).
   * No explicit value + interactive → `nil` (preserve the library default,
     i.e. don't touch timbre's min-level).

   Returns `[:level <kw-or-nil>]` on success or `[:error <msg>]` on a bad
   explicit value."
  [opts]
  (let [raw (:log-level opts)]
    (if (some? raw)
      (if-let [lvl (get log-levels (str/lower-case (str/trim (str raw))))]
        [:level lvl]
        [:error (str "Invalid --log-level " (pr-str raw)
                     "; expected one of debug|info|warn|error")])
      (if (boolean (:no-tui opts))
        [:level :info]
        [:level nil]))))

(defn route-logs-to-file!
  "Redirect timbre output to `path` (append) and silence the console appender.

   The TUI owns the terminal (it renders an alt-screen via ANSI on `*err*`),
   so any library logging that also reaches the terminal scribbles over the
   modal/scrollback — the classic \"flickers and prints messages\" corruption.
   When the TUI is active we therefore keep verbose logs (the interactive
   default is DEBUG) but send them to `<session-dir>/escapement.log` instead
   of the screen. Returns `path` so callers can surface it in the run summary.

   Idempotent enough for one call per run; uses a plain `:fn` appender so it
   works under bb/SCI (no dependency on timbre's appender namespaces)."
  [path]
  (io/make-parents (io/file path))
  (timbre/merge-config!
   {:appenders {:println {:enabled? false}
                :file    {:enabled?  true
                          :async?    false
                          :min-level nil
                          :fn        (fn [data]
                                       (try
                                         (spit path (str (force (:output_ data)) "\n") :append true)
                                         (catch Throwable _ nil)))}}})
  path)

(defn restore-console-logging!
  "Undo `route-logs-to-file!`: re-enable the console (`:println`) appender and
   disable the file appender. Called ONLY after the TUI's alt-screen has been
   fully exited (post `tui/stop!` / OpenTUI teardown), so that:

   - while the TUI owns the terminal, no library DEBUG line can scribble over
     the alt-screen (the classic `omarflow DEBUG` scrollback wall on a fast
     finish/error — a stray log emitted DURING teardown reaches the console
     after `alt-screen-off`); and
   - once the terminal is back to the normal screen, ordinary CLI errors /
     run-summary lines print again.

   Idempotent; SCI/bb-safe (plain `:println` appender, no JVM-only deps)."
  []
  (timbre/merge-config!
   {:appenders {:println {:enabled? true}
                :file    {:enabled? false}}}))

;; ---------------------------------------------------------------------------
;; OpenTUI ONLY: global stdout/stderr capture.
;;
;; Under `--tui=opentui` the Bun sidecar is the CHILD and inherited the real
;; TTY fds at spawn (ProcessBuilder .inheritIO). The agent is the PARENT, and
;; its own *out*/*err* + System/out/System/err still point at that same TTY, so
;; ANY stray `println`/`print`/`.printStackTrace`/uncaught-thread print —
;; including ones emitted from background `future`s (e.g. the LLM http-transport
;; SSE worker) — paints directly over the sidecar's rendered frame.
;;
;; `route-logs-to-file!` only reroutes TIMBRE. Thread-local `binding` only
;; covers the current thread. So here we globally redirect BOTH the Clojure
;; dynamic vars (alter-var-root) AND the Java System streams to a file writer,
;; capturing prints from every thread. This is installed AFTER the sidecar is
;; spawned, which is safe: the child already holds its own copies of the TTY
;; fds, so re-pointing the parent's streams cannot affect the child's rendering.
;;
;; JLine must NEVER use this — it renders its alt-screen on *err* in-process.
(defn- install-opentui-stream-capture!
  "Globally redirect the agent's *out*/*err* and System/out/System/err to
   `path` (append). Returns a restore-fn (0-arity) that flushes and restores the
   original streams. SCI/bb-safe (java.io.PrintStream / FileOutputStream /
   OutputStreamWriter only)."
  [path]
  (io/make-parents (io/file path))
  (let [fos        (java.io.FileOutputStream. (io/file path) true) ; append
        ps         (java.io.PrintStream. fos true "UTF-8")          ; autoflush
        writer     (java.io.OutputStreamWriter. fos "UTF-8")
        orig-out   *out*
        orig-err   *err*
        sys-out    System/out
        sys-err    System/err]
    (System/setOut ps)
    (System/setErr ps)
    (alter-var-root #'*out* (constantly writer))
    (alter-var-root #'*err* (constantly writer))
    (fn restore! []
      (try (.flush writer) (catch Throwable _ nil))
      (try (.flush ps)     (catch Throwable _ nil))
      (alter-var-root #'*out* (constantly orig-out))
      (alter-var-root #'*err* (constantly orig-err))
      (System/setOut sys-out)
      (System/setErr sys-err))))

(defn- read-edn-file [path]
  (with-open [r (java.io.PushbackReader. (io/reader path))]
    (edn/read r)))

(defn parse-param
  "Split `\"key=value\"` on the first `=`, read the value as EDN, and return
   `[k v]`. Returns nil for malformed entries.

   Friendlier-than-strict EDN: bare words (which EDN would read as symbols)
   are returned as plain strings, so `--param name=alice` does the obvious
   thing. Numbers, booleans, keywords, collections still EDN-parse as expected.
   Quoted strings (`--param name=\\\"alice\\\"`) also work.

   Public for tests."
  [s]
  (let [i (.indexOf ^String s (int \=))]
    (when (pos? i)
      (let [k (subs s 0 i)
            v (subs s (inc i))]
        (when (seq k)
          (let [parsed (try (edn/read-string v) (catch Throwable _ ::unparseable))
                value  (cond
                         (= ::unparseable parsed) v
                         (symbol? parsed) v
                         :else parsed)]
            [(keyword k) value]))))))

(defn- merge-params
  "Given a base map and a vector of raw `--param` strings, return the merged
   initial-data map. Malformed entries call `die!`."
  [base raw-params]
  (reduce
   (fn [m s]
     (if-let [[k v] (parse-param s)]
       (assoc m k v)
       (die! (str "--param expects key=value, got: " s))))
   (or base {})
   raw-params))

;; Provider/credential matrix lives in escapement.llm.providers so the CLI's
;; auto-detection and the live e2e suite share one source of truth.
(def ^:private build-api-backend providers/build-api-backend)
(def ^:private build-openai-backend providers/build-openai-backend)
(def ^:private build-codex-backend providers/build-codex-backend)
(def ^:private build-multi-backend providers/build-multi-backend)
(def ^:private nonblank-env providers/nonblank-env)
(def ^:private build-opencode-go-backend providers/build-opencode-go-backend)
(def ^:private detect-available-credentials providers/detect-available-credentials)
(def ^:private build-credential-backend providers/build-credential-backend)

(defn- build-default-multi-backend!
  "Scan all available credentials and assemble a multi-dispatch backend. If only
   one credential is available, return that bare backend (no wrapping). Returns
   nil if nothing is available.

   Returns `{:backend B :default-models [model-id ...]}` — `:default-models`
   is the preference-ordered list used by the llm-conversation processor when
   a chart doesn't pin `:model`. Each credential contributes one entry."
  [{:keys [model]}]
  (let [creds (detect-available-credentials)]
    (cond
      (empty? creds) nil

      (= 1 (count creds))
      (let [c            (first creds)
            chosen-model (or model (:default-model c))]
        (binding [*out* *err*]
          (println (str "[cli] auto-detected LLM backend: " (name (:kind c))
                        " (" (:source c) ", model " chosen-model ")")))
        {:backend        (build-credential-backend (cond-> c model (assoc :default-model model)))
         :default-models [chosen-model]})

      :else
      (let [built           (mapv (fn [c] [c (build-credential-backend c)]) creds)
            routes          (mapv (fn [[c b]] [(:route c) b]) built)
            default-backend (second (first built))]
        (binding [*out* *err*]
          (println (str "[cli] auto-detected multi-backend dispatcher; routes by model prefix:"))
          (doseq [[c _] built]
            (println (str "[cli]   " (pr-str (:route c)) " → " (name (:kind c))
                          " (" (:source c) ", default model " (:default-model c) ")")))
          (println (str "[cli]   default backend → " (name (:kind (ffirst built))))))
        {:backend        (build-multi-backend {:routes routes :default-backend default-backend})
         :default-models (mapv (fn [[c _]] (:default-model c)) built)}))))

(defn- expand-home
  "Expand a leading `~` to the user's home directory."
  [path]
  (if (str/starts-with? path "~")
    (str (System/getProperty "user.home") (subs path 1))
    path))

(defn- read-json-store
  "Slurp + parse a JSON credential store (e.g. another tool's auth file).
   Returns the parsed map (string keys) or nil if absent/unparsable."
  [path]
  (let [f (io/file (expand-home path))]
    (when (.exists f)
      (try (json/parse-string (slurp f))
           (catch Throwable _ nil)))))

(defn- resolve-config-credentials
  "Resolve `:llm/credentials` from `run-cfg` into concrete descriptor maps for
   `providers/build-injected-credentials-backend`. Each descriptor's API key is
   taken inline (`:api-key`) or fetched from a `:llm/credential-sources` store
   via `:key-from [<source-kw> <json-path…>]`. Stores are read once and cached.
   Descriptors that need a key but can't resolve one are dropped (with a warning);
   `:codex` (OAuth file) needs no key. Returns a vector, or nil when unconfigured."
  [run-cfg]
  (when-let [descs (seq (:llm/credentials run-cfg))]
    (let [sources (:llm/credential-sources run-cfg)
          store   (memoize (fn [src-kw]
                             (some-> (get sources src-kw) read-json-store)))]
      (->> descs
        (keep
          (fn [{:keys [provider api-key key-from] :as d}]
            (let [k (or api-key
                      (when key-from
                        (let [[src-kw & path] key-from]
                          (get-in (store src-kw) (vec path)))))
                  base (-> d
                         (dissoc :key-from)
                         (cond-> k (assoc :api-key k)))]
              (cond
                (= :codex provider) base          ; OAuth file; no key needed
                (str/blank? k)
                (do (binding [*out* *err*]
                      (println (str "[cli] WARN :llm/credentials — no API key resolved for "
                                    provider " (from " (pr-str key-from) "); skipping")))
                    nil)
                :else base))))
        vec
        seq))))

(defn- make-backend
  "Construct an LLM backend.

   Returns `{:backend B :default-models [model-id ...]}` — `:default-models`
   gives the cross-credential fallback preference list when charts don't pin
   `:model`. For explicit `--backend X`, the list has at most one entry.
   Returns nil when no backend is available.

   If `--backend` is explicitly provided, honor it. Otherwise auto-detect from
   environment variables and construct a multi-dispatch backend."
  [{:keys [backend model api-base-url api-key-env]}]
  (if backend
    (case backend
      "api"
      {:backend        (build-api-backend (cond-> {}
                                            model (assoc :model model)
                                            api-base-url (assoc :base-url api-base-url)
                                            api-key-env (assoc :api-key (System/getenv api-key-env))))
       :default-models (when model [model])}

      "openai"
      (let [m (or model "gpt-4o-mini")]
        {:backend        (build-openai-backend (cond-> {:base-url      (or api-base-url "https://api.openai.com/v1")
                                                        :default-model m}
                                                 api-key-env (assoc :api-key (System/getenv api-key-env))
                                                 (not api-key-env) (assoc :api-key (System/getenv "OPENAI_API_KEY"))))
         :default-models [m]})

      "ollama"
      (let [m (or model "kimi-k2.5")]
        {:backend        (build-openai-backend (cond-> {:base-url      (or api-base-url "https://ollama.com/v1")
                                                        :default-model m}
                                                 api-key-env (assoc :api-key (System/getenv api-key-env))
                                                 (not api-key-env) (assoc :api-key (System/getenv "OLLAMA_API_KEY"))))
         :default-models [m]})

      "opencode-go"
      (let [m (or model "glm-5")]
        {:backend        (build-opencode-go-backend {:api-key  (if api-key-env
                                                                 (System/getenv api-key-env)
                                                                 (System/getenv "OPENCODE_GO_API_KEY"))
                                                     :base-url api-base-url
                                                     :model    m})
         :default-models [m]})

      "codex"
      {:backend        (build-codex-backend (cond-> {}
                                              model (assoc :default-model model)))
       :default-models (when model [model])}

      (die! (str "Unknown backend: " backend)))
    ;; No --backend: build a multi-dispatch from all available credentials.
    (build-default-multi-backend! {:model model})))

(defn- codex-auth-file
  "Returns the path to the saved OpenAI OAuth token file, or nil if the auth
   namespace is unavailable."
  []
  (try
    (require 'escapement.llm.openai-codex.auth)
    @(resolve 'escapement.llm.openai-codex.auth/AUTH-FILE)
    (catch Throwable _ nil)))

(defn- codex-auth-info
  "Returns a display string about saved codex credentials, or nil if absent."
  []
  (try
    (require 'escapement.llm.openai-codex.auth)
    (let [load! (resolve 'escapement.llm.openai-codex.auth/load-auth!)
          auth  (load!)]
      (when auth
        (str "present  account=" (:account-id auth)
             "  expires=" (java.util.Date. ^long (:expires-at auth 0)))))
    (catch Throwable _ nil)))

(defn- needs-llm?
  "Heuristic: does this chart require an LLM backend? We treat any chart loaded
   from the conventional `*.examples.*` namespace as potentially LLM-using; the
   safer signal is the absence of env keys AND no --backend flag — at that
   point we surface the actionable error before the engine reports a cryptic
   `:type :llm-conversation` message."
  [opts]
  (and (nil? (:backend opts))
       (empty? (detect-available-credentials))))

(defn- cmd-info [_args]
  (println "escapement" version)
  (println "java" (System/getProperty "java.version"))
  (println "os" (System/getProperty "os.name") (System/getProperty "os.version"))
  (when-let [bb (System/getProperty "babashka.version")]
    (println "babashka" bb))
  (println "cwd" (System/getProperty "user.dir"))
  (when-let [bb-cfg (System/getProperty "babashka.config")]
    (println "bb.edn" bb-cfg))
  (println)
  (println "Classpath (resolved, in load order):")
  ;; What `escapement run` will look in when loading a chart-sym. Comes from
  ;; the bb.edn :paths in effect (resolved absolute, so identical regardless
  ;; of where you ran from) plus any deps jars. Project-config :source-paths
  ;; below are layered on top at run time via babashka.classpath/add-classpath.
  (let [cp     (try
                 (require 'babashka.classpath)
                 ((resolve 'babashka.classpath/get-classpath))
                 (catch Throwable _ nil))
        parts  (when cp (remove str/blank? (str/split cp #":")))
        dirs   (filter #(let [f (io/file %)] (and (.exists f) (.isDirectory f))) parts)
        jars   (filter #(str/ends-with? % ".jar") parts)
        other  (remove (set (concat dirs jars)) parts)]
    (if (seq parts)
      (do
        (when (seq dirs)
          (println "  source dirs:")
          (doseq [d dirs] (println "    " d)))
        (when (seq jars)
          (println (str "  jars (" (count jars) "):"))
          (doseq [j jars] (println "    " j)))
        (when (seq other)
          (println "  other:")
          (doseq [o other] (println "    " o))))
      (println "  <babashka.classpath unavailable>")))
  (println)
  (println ".escapement.edn:")
  (let [cfg-info (try (config/load-project-config) (catch Throwable _ nil))]
    (if cfg-info
      (let [^java.io.File path (:path cfg-info)
            ^java.io.File root (:root cfg-info)
            cfg                (:config cfg-info)
            sps                (:source-paths cfg)
            deps               (:deps cfg)]
        (println "  found at:" (.getPath path))
        (println "  config-root:" (.getPath root))
        (when (seq sps)
          (println "  :source-paths (added to classpath at `run` time):")
          (doseq [p sps]
            (println "    " (.getAbsolutePath (config/resolve-path root p)))))
        (when (seq deps)
          (println (str "  :deps (added at `run` time, " (count deps) " entries):"))
          (doseq [[k v] deps]
            (println "    " (pr-str k) "=>" (pr-str v))))
        (when (:default-chart cfg)
          (println "  :default-chart:" (:default-chart cfg))))
      (println "  <none found via walk-up from cwd>")))
  (println)
  (println "LLM backends:")
  (let [anthropic  (System/getenv "ANTHROPIC_API_KEY")
        zai        (System/getenv "ZAI_API_KEY")
        openai     (System/getenv "OPENAI_API_KEY")
        openrouter (System/getenv "OPENROUTER_API_KEY")
        ollama     (System/getenv "OLLAMA_API_KEY")
        ocgo       (System/getenv "OPENCODE_GO_API_KEY")]
    (println "  ANTHROPIC_API_KEY : " (if (seq anthropic) "set" "not set"))
    (println "  ZAI_API_KEY       : " (if (seq zai) "set" "not set"))
    (println "  OPENAI_API_KEY    : " (if (seq openai) "set" "not set"))
    (println "  OPENROUTER_API_KEY: " (if (seq openrouter) "set" "not set"))
    (println "  OLLAMA_API_KEY    : " (if (seq ollama) "set" "not set"))
    (println "  OPENCODE_GO_API_KEY: " (if (seq ocgo) "set" "not set")))
  (let [codex-info (codex-auth-info)
        auth-file  (codex-auth-file)]
    (println "  codex OAuth       : " (or codex-info "not logged in"))
    (when auth-file
      (println "  codex auth file   : " auth-file)))
  (let [creds (detect-available-credentials)]
    (println)
    (cond
      (empty? creds)
      (println "Auto-detect: no LLM backend would be selected (no credentials).")

      (= 1 (count creds))
      (let [c (first creds)]
        (println (str "Auto-detect: single backend `" (name (:kind c))
                      "` (default model " (:default-model c) ").")))

      :else
      (do
        (println "Auto-detect: multi-backend dispatcher with routes:")
        (doseq [c creds]
          (println (str "  " (pr-str (:route c)) " → " (name (:kind c))
                        " (default model " (:default-model c) ")")))
        (println (str "  default backend → " (name (:kind (first creds)))
                      " (used when :model doesn't match any route)")))))
  (System/exit 0))

(defn- decide-tui
  "Resolve the TUI mode from flags + chart metadata + tty.

   Returns one of: `:jline` (the default in-process JLine TUI), `:opentui`
   (the OpenTUI Bun sidecar), or `false` (headless / no UI).

   Rules:
   * `--no-tui` wins outright → `false`.
   * `--tui=opentui` selects the sidecar (requires a real TTY). `--tui=jline`
     (or `--tui` absent) selects the JLine path. `--debug --tui=opentui`
     runs the sidecar WITH a debug controller (live pause/step/continue/arm
     over the WS back-channel + a PAUSED banner) — parity with `--debug` on
     the web path. `--debug` alone still forces the JLine TUI.
   * `--debug` forces a TUI on (JLine by default, the sidecar with
     `--tui=opentui`) and errors with no TTY.
   * else `^{:interactive? true}` on the chart var defaults to JLine; else off.
   * `--debug` + `--no-tui` is rejected.

   Both UI modes require an interactive terminal; a non-TTY with a UI requested
   errors (the JLine message is reused — it covers the headless stdin advice)."
  [opts chart-meta]
  (let [no-tui?      (boolean (:no-tui opts))
        debug?       (boolean (:debug opts))
        tui-flag     (some-> (:tui opts) str/lower-case str/trim)
        _            (when (and no-tui? debug?)
                       (die! "--debug requires the TUI; do not combine with --no-tui."))
        _            (when (and tui-flag (not (#{"jline" "opentui"} tui-flag)))
                       (die! (str "--tui must be jline or opentui (got: " (:tui opts) ")")))
        opentui?     (and (= tui-flag "opentui") (not no-tui?))
        interactive? (boolean (:interactive? chart-meta))
        want?        (cond
                       no-tui? false
                       opentui? true
                       debug? true
                       (= tui-flag "jline") true
                       :else interactive?)]
    (cond
      no-tui?
      false

      (and want? (not (tui/interactive-terminal?)))
      (die! (str "Interactive chart requires a TTY for the TUI.\n"
                 "Run from a real terminal, or pass --no-tui (the chart's\n"
                 ":human-input invocations will read from stdin).")
            1)

      (and want? opentui?) :opentui

      want? :jline

      :else false)))

(defn parse-source-paths [s] (when s (remove str/blank? (str/split s #":"))))

(defn parse-tools-ns-flag
  "`--tools-ns` accumulates a vector of raw strings (multi-flag). Each
   string may itself be comma-separated. Returns a vector of qualified
   symbols, dying on any malformed entry."
  [raw]
  (let [strs (mapcat #(str/split % #",") raw)
        strs (remove str/blank? strs)]
    (mapv (fn [s]
            (let [sym (try (symbol s) (catch Throwable _ (die! (str "Invalid --tools-ns symbol: " s))))]
              (when-not (qualified-symbol? sym)
                (die! (str "--tools-ns must be qualified (namespace/name), got: " s)))
              sym))
          strs)))

(defn parse-deps-flag [s]
  (when s
    (let [v (try (edn/read-string s)
                 (catch Throwable t
                   (die! (str "--deps must be EDN: " (.getMessage t)))))]
      (when-not (map? v) (die! "--deps must be an EDN map of {sym coord}"))
      v)))

(defn- apply-deps!
  "Call babashka.deps/add-deps with the given coordinate map. Errors are
   re-thrown wrapped so the user sees which coordinate failed."
  [deps-map]
  (when (seq deps-map)
    (try
      (require 'babashka.deps)
      ((resolve 'babashka.deps/add-deps) {:deps deps-map})
      (catch Throwable t
        (die! (str "Failed to resolve runtime :deps " (pr-str deps-map) ": "
                   (.getMessage t)) 1)))))

(defn- apply-classpath!
  "Prepend each path (a File) to the classpath via babashka.classpath."
  [paths]
  (when (seq paths)
    (require 'babashka.classpath)
    (let [add (resolve 'babashka.classpath/add-classpath)]
      (doseq [^java.io.File p paths]
        (add (.getAbsolutePath p))))))

(defn- require-tools-nses!
  "For each qualified symbol: require its namespace and, if it resolves
   to a fn, invoke it with `registry`."
  [syms registry]
  (doseq [sym syms]
    (let [ns-sym (symbol (namespace sym))]
      (try (require ns-sym)
           (catch Throwable t
             (die! (str "Could not require tools-ns " ns-sym ": " (.getMessage t)) 1)))
      (if-let [v (resolve sym)]
        (let [val (deref v)]
          (when (fn? val) (val registry)))
        (die! (str "Could not resolve tools-ns symbol: " sym) 1)))))

(defn effective-opts
  "Pure helper for layering CLI flags over project config. Returns the
   resolved subset that drives run-time wiring. CLI > config > default.

   `cli-opts`: parsed `--flag` map from the command line.
   `project-cfg`: parsed `.escapement.edn` map (may be nil).
   `config-root`: java.io.File of the dir containing `.escapement.edn` (may be nil).

   Why: kept side-effect-free so precedence rules are unit-testable."
  [cli-opts project-cfg config-root]
  (let [cli-sps   (mapv #(io/file %) (parse-source-paths (:source-paths cli-opts)))
        cfg-sps   (mapv #(config/resolve-path config-root %) (:source-paths project-cfg))
        cli-deps  (parse-deps-flag (:deps cli-opts))
        cli-tools (parse-tools-ns-flag (:tools-ns cli-opts))
        cfg-tools (or (:tools-ns project-cfg) [])
        work-dir  (cond
                    (:work-dir cli-opts) (:work-dir cli-opts)
                    (and config-root (:work-dir project-cfg))
                    (.getAbsolutePath (config/resolve-path config-root (:work-dir project-cfg)))
                    :else ".escapement")]
    {:source-paths  (vec (concat cli-sps cfg-sps))
     :deps          (merge (:deps project-cfg) cli-deps)
     :tools-ns      (vec (concat cfg-tools cli-tools))
     :work-dir      work-dir
     :default-chart (:default-chart project-cfg)}))

(defn- cmd-run [args]
  (let [{:keys [positional opts]}
        (parse-args args #{:resume :trace :no-tui :debug :dump-d2 :no-spawn
                           :keep-alive :no-keep-alive} #{:param :tools-ns})
        _                      (let [[tag v] (resolve-log-level opts)]
                                 (if (= tag :error)
                                   (die! v 2)
                                   (when v (timbre/set-min-level! v))))
        project-cfg-info       (try (config/load-project-config)
                                    (catch clojure.lang.ExceptionInfo e
                                      (die! (str (.getMessage e) "\n"
                                                 (pr-str (:errors (ex-data e)))) 2)))
        project-cfg            (:config project-cfg-info)
        config-root            (:root project-cfg-info)
        chart-arg              (first positional)
        chart-sym              (cond
                                 chart-arg
                                 (let [s (symbol chart-arg)]
                                   (when-not (qualified-symbol? s)
                                     (die! (str "Chart symbol must be qualified, got: " chart-arg)))
                                   s)
                                 (:default-chart project-cfg)
                                 (:default-chart project-cfg)
                                 :else
                                 (die! "Usage: run <chart-sym> [flags]  (or set :default-chart in .escapement.edn)"))
        eff                    (effective-opts opts project-cfg config-root)
        work-dir               (:work-dir eff)
        all-source-paths       (:source-paths eff)
        merged-deps            (:deps eff)
        all-tools-ns           (:tools-ns eff)
        prelude-events         (cond-> []
                                 project-cfg-info
                                 (conj {:event :cli/config-loaded
                                        :data  {:path (.getPath ^java.io.File (:path project-cfg-info))
                                                :keys (vec (keys project-cfg))}})
                                 (seq merged-deps)
                                 (conj {:event :cli/deps-added
                                        :data  {:coords (into {} (map (fn [[k v]] [(str k) (pr-str v)]))
                                                              merged-deps)}}))
        _                      (apply-deps! merged-deps)
        _                      (apply-classpath! all-source-paths)
        ;; --dump-d2: print the chart's d2 source and exit, before any
        ;; execution machinery (LLM backend, session dirs, TUI). Loads the
        ;; chart only — no API key required, no session dir created.
        _                      (when (:dump-d2 opts)
                                 (let [[chart _] (runner/load-chart-with-meta chart-sym)]
                                   (print (d2/chart->d2 chart nil))
                                   (flush)
                                   (System/exit 0)))
        session                (or (:session opts) (str (java.util.UUID/randomUUID)))
        session-dir            (str work-dir "/" session)
        transcript             (or (:transcript opts) (str session-dir "/transcript.jsonl"))
        checkpoint-dir         (or (:checkpoint-dir opts) (str session-dir "/checkpoints"))
        _                      (.mkdirs (io/file session-dir))
        initial-data           (let [base (when-let [p (:input opts)] (read-edn-file p))]
                                 (merge-params base (:param opts)))
        ;; Resolve the subjective ratings table + fail-closed flag ONCE
        ;; from the merged `.escapement.edn` at startup, then inject them
        ;; as explicit values into the invocation context (same code path
        ;; the lib facade — Step 4 — feeds from injected `:config`). The
        ;; processor no longer relies on the Step-1 disk-resolving 2-arg
        ;; `satisfies-policy?` seam.
        run-cfg                (config/load-config)
        catalog-ratings        (ratings/ratings run-cfg)
        ;; Falls back to the built-in `default-aliases` so a node naming a
        ;; default alias resolves even with no `:llm/aliases` configured (R7).
        llm-aliases            (preferences/aliases-from-config run-cfg)
        ;; `:llm/preferences` (a vector of alias keywords) is the default
        ;; candidate set the resolver flattens when a node names no model;
        ;; falls back to the built-in `default-preferences`.
        llm-preferences        (preferences/preferences run-cfg)
        eligibility-strict?    (boolean
                                (or (:llm/eligibility-strict? run-cfg)
                                    (get-in run-cfg [:llm :eligibility-strict?])))
        ;; Config-declared credentials (`.escapement.edn :llm/credentials`) →
        ;; a hermetic multi-dispatch backend, the SAME path the embeddable lib
        ;; uses. Active only when no explicit `--backend` flag is given. Keys
        ;; are resolved from external stores so no secrets live in the config.
        config-creds           (when-not (:backend opts)
                                 (resolve-config-credentials run-cfg))
        _                      (when (and (empty? config-creds) (needs-llm? opts))
                                 (die! (str "Error: no LLM backend configured.\n"
                                            "Options:\n"
                                            "  1. Declare :llm/credentials in .escapement.edn (see .escapement.edn.example)\n"
                                            "  2. Set ANTHROPIC_API_KEY / ZAI_API_KEY / OPENAI_API_KEY / OLLAMA_API_KEY / OPENCODE_GO_API_KEY\n"
                                            "  3. Pass --backend codex  (ChatGPT Plus/Pro subscription; run 'escapement login codex' first)\n"
                                            "See: escapement info   (or:  Guide.adoc, \"LLM backends\")")
                                       1))
        backend-info           (if (seq config-creds)
                                 {:backend        (providers/build-injected-credentials-backend
                                                    config-creds
                                                    (preferences/flatten-targets llm-preferences llm-aliases))
                                  :default-models (preferences/model-order llm-preferences llm-aliases)}
                                 (make-backend opts))
        backend                (:backend backend-info)
        backend-default-models (:default-models backend-info)
        ;; Load the chart FIRST. Its require-graph may include namespaces
        ;; whose top-level forms call
        ;; `(tp/register! escapement.tools.builtin/default-registry ...)`.
        ;; Those side-effects mutate the singleton registry atom and are then
        ;; visible to `runner/run!` below.
        [chart chart-meta] (runner/load-chart-with-meta chart-sym)
        tui-mode               (decide-tui opts chart-meta)
        jline?                 (= tui-mode :jline)
        opentui?               (= tui-mode :opentui)
        ;; #2 keep-alive / pause-on-finish. When ON, a finished/errored run
        ;; HOLDS a live frame instead of tearing the UI down (JLine already does
        ;; this via `await-quit!`; OpenTUI gets the analog below). Default rule:
        ;;   ON  for an interactive TTY run that owns a UI (jline or opentui);
        ;;   OFF for headless / non-TTY / UI-less runs (so CI + `--api-server`-
        ;;       only invocations never block on a human key).
        ;; `--keep-alive` forces it on (still gated on a UI + TTY — a headless run
        ;; has no frame to hold); `--no-keep-alive` forces it off.
        keep-alive?            (let [tty? (tui/interactive-terminal?)]
                                 (cond
                                   (:no-keep-alive opts) false
                                   (not tui-mode)        false   ; no UI → nothing to hold
                                   (not tty?)            false   ; non-TTY → never block
                                   (:keep-alive opts)    true
                                   :else                 true))
        ;; --tui=opentui: resolve the Bun sidecar entry up-front so we fail fast
        ;; (before any session machinery) if the tui/opentui/ workspace is missing.
        ;; Reached only via requiring-resolve (architecture boundary).
        sidecar-entry          (when opentui?
                                 (let [e (try ((requiring-resolve 'opentui.sidecar/sidecar-entry))
                                              (catch Throwable _ nil))]
                                   (when-not e
                                     (die! (str "--tui=opentui could not find the OpenTUI sidecar entry "
                                                "(tui/opentui/src/main.tsx). Run from the escapement repo, set "
                                                "OPENTUI_DIR=<path-to-opentui>, and `bun install` in tui/opentui/.")
                                           1))
                                   e))
        ;; When ANY TUI is active it owns the terminal. For JLine the alt-screen
        ;; ANSI is in-process; for OpenTUI the sidecar owns the tty and the agent
        ;; runs headless — in BOTH cases verbose logs must NOT reach the terminal,
        ;; so route them to a file.
        log-file               (when tui-mode
                                 (route-logs-to-file! (str session-dir "/escapement.log")))
        ;; OpenTUI ONLY: globally capture the PARENT agent's stdout/stderr (incl.
        ;; cross-thread `future`s like the LLM SSE worker) to the session log so
        ;; no stray print bleeds over the sidecar-owned TTY. Installed AFTER the
        ;; sidecar spawn point below would be ideal, but the streams are inert
        ;; until something prints; the sidecar inherited its TTY fds at spawn, so
        ;; redirecting the parent here is safe regardless of ordering. Restored on
        ;; teardown (BEFORE any post-run summary). nil (no capture) for jline.
        restore-streams!       (when opentui?
                                 (install-opentui-stream-capture!
                                  (str session-dir "/escapement.log")))
        debug?                 (boolean (:debug opts))
        debug-cfg              (when debug? (config/load-config))
        debug-controller       (when debug?
                                 (dbg/new-controller
                                  {:initial-pause? (boolean
                                                    (get-in debug-cfg
                                                            [:debug :auto-pause?]
                                                            true))}))
        session-short          (apply str (take 8 session))
        tui-handle             (when jline?
                                 (tui/start! (cond-> {:chart-sym     chart-sym
                                                      :session-short session-short}
                                               debug? (assoc :debug? true
                                                             :debug-controller debug-controller
                                                             :debug-config debug-cfg))))
        tool-registry          (when backend
                                 (require 'escapement.tools.builtin)
                                 (let [reg-var (resolve 'escapement.tools.builtin/default-registry)
                                       _       (assert reg-var "escapement.tools.builtin/default-registry not found")
                                       reg     (deref reg-var)]
                                   (require-tools-nses! all-tools-ns reg)
                                   ;; R3: builtin path-taking tools resolve RELATIVE paths
                                   ;; against this base-dir. `tp/dispatch` reads
                                   ;; `:escapement/base-dir` off the registry's metadata
                                   ;; when no explicit base-dir arg is given, and
                                   ;; `:shell/run` runs in it. Defaults to the session
                                   ;; work-dir; `--base-dir` overrides it so a chart can
                                   ;; point the agent's file/shell tools at a repo it
                                   ;; clones/operates on, decoupled from the session/
                                   ;; checkpoint dir.
                                   (when-let [base-dir (or (:base-dir opts) session-dir)]
                                     (alter-meta! reg assoc :escapement/base-dir base-dir))
                                   reg))
        explicit-api-port      (when-let [s (:api-server opts)]
                                 (let [n (try (Long/parseLong s) (catch Throwable _ nil))]
                                   (when-not (and n (pos? n))
                                     (die! (str "--api-server must be a positive port (got: " s ")") 2))
                                   n))
        ;; --tui=opentui REQUIRES the api-server + WS push (the sidecar's transport).
        ;; Reuse an explicit --api-server port if given, else auto-pick a free one.
        api-server-port        (cond
                                 explicit-api-port explicit-api-port
                                 opentui? ((requiring-resolve 'opentui.sidecar/free-port))
                                 :else nil)
        ;; Shared control handle: created here (before the env exists) and given
        ;; to BOTH the api-server ctx and `run!`'s on-env-ready (which fills it
        ;; with the live env/queue/controller). This is the seam by which the
        ;; server's live resolvers/mutations reach the running chart under bb.
        control-handle         (when api-server-port (ctrl-handle/new-handle))
        ;; Live WS push fan-out hub. Created here so it can be BOTH handed to the api-server
        ;; (powers `GET /ws`) and tee'd into the runner's transcript-tap below — a non-blocking
        ;; mirror of every transcript event to connected sidecar/browser clients. Lazy-required so a
        ;; normal run never loads the add-on; nil unless the api-server is up.
        ws-hub                 (when api-server-port
                                 (try ((requiring-resolve 'escapement.ui.ws-push/new-hub)
                                       {:chart chart})
                                      (catch Throwable _ nil)))
        ws-publish!            (when ws-hub
                                 (let [pub (requiring-resolve 'escapement.ui.ws-push/publish!)]
                                   (fn [ev] (pub ws-hub ev))))
        ;; OpenTUI human-input renderer: prompts are published to the sidecar over
        ;; the WS hub (raw `prompt`/`progress` frames via `ws-push/broadcast!`),
        ;; the worker parks on a promise, and the WS `answer` back-channel resolves
        ;; it. Constructed here so it can serve as the run's HumanRenderer.
        remote-renderer        (when (and opentui? ws-hub)
                                 (let [broadcast! (requiring-resolve 'escapement.ui.ws-push/broadcast!)
                                       ->rndr     (requiring-resolve 'escapement.ui.remote-renderer/->renderer)]
                                   (->rndr {:publish-fn (fn [msg] (broadcast! ws-hub msg))})))
        human-renderer         (cond
                                 tui-handle (tui/->renderer tui-handle)
                                 remote-renderer remote-renderer
                                 (:interactive? chart-meta) (human-input/stdin-renderer)
                                 ;; Charts that don't declare :interactive? still get
                                 ;; a stdin renderer for any human-input states they
                                 ;; happen to invoke — fail-soft rather than silently
                                 ;; hang on a missing renderer.
                                 :else (human-input/stdin-renderer))
        ;; OpenTUI WS back-channel seam: inbound `control`/`answer` frames are
        ;; dispatched here (control → debug controller / runner :ui.interrupt|:ui.quit,
        ;; answer → remote-renderer delivery registry). `on-quit` triggers teardown.
        sidecar-proc           (atom nil)
        ;; Push the live debugger snapshot (paused?/step-budget/config) to the
        ;; sidecar so its PAUSED banner + Debugger view stay current without
        ;; polling. nil unless both a debug controller and the WS hub exist.
        publish-debug!         (when (and debug-controller ws-hub)
                                 (let [pub (requiring-resolve 'escapement.ui.ws-push/publish-debug!)]
                                   (fn [snap] (pub ws-hub snap))))
        ws-handlers            (when opentui?
                                 ((requiring-resolve 'opentui.sidecar/make-ws-handlers)
                                  {:control-handle control-handle
                                   :controller     debug-controller
                                   :publish-debug! publish-debug!
                                   :on-answered    (when ws-hub
                                                     (let [clear! (requiring-resolve 'escapement.ui.ws-push/clear-pending-prompt!)]
                                                       (fn [] (clear! ws-hub))))
                                   :on-quit        (fn []
                                                     ;; On a UI quit the sidecar restores its own terminal
                                                     ;; (graceful renderer.destroy() + process.exit) and the
                                                     ;; watcher thread tears the run down when it exits. Give
                                                     ;; it a moment to do that and only force-kill as a
                                                     ;; fallback if it's still alive — an immediate SIGTERM
                                                     ;; here races the sidecar's restore and can leave the
                                                     ;; kitty keyboard protocol / alt-screen enabled.
                                                     (when-let [p @sidecar-proc]
                                                       (doto (Thread.
                                                              (fn []
                                                                (try (Thread/sleep 1500) (catch InterruptedException _ nil))
                                                                (when (.isAlive p)
                                                                  (try ((requiring-resolve 'opentui.sidecar/destroy!) p)
                                                                       (catch Throwable _ nil)))))
                                                         (.setDaemon true)
                                                         (.start))))}))
        ;; Read-only EQL API over the work-dir, scoped to this run's session,
        ;; PLUS a live control plane (pause/step/continue) when a debug
        ;; controller is active. Lazy-required so a normal run never loads Pathom.
        api-handle             (when api-server-port
                                 (let [start! (try
                                                (requiring-resolve 'escapement.ui.server/start!)
                                                (catch Throwable t
                                                  (die! (str "--api-server requires the optional web UI add-on "
                                                             "(Pathom/EQL/transit + escapement.ui.server), which is "
                                                             "not on this classpath. It ships with the bbin/JVM build; "
                                                             "library consumers must add those deps. Cause: "
                                                             (.getMessage t))
                                                        2)))]
                                   (when-not opentui?
                                     (binding [*out* *err*]
                                       (println (str "[cli] EQL API on http://localhost:" api-server-port "/api"))
                                       (println (str "[cli] UI     on http://localhost:" api-server-port "/"))))
                                   (start! {:port              api-server-port
                                            :work-dir          work-dir
                                            :active-session-id session
                                            :chart             chart
                                            :controller        debug-controller
                                            :live              control-handle
                                            :ws-push           ws-hub
                                            :ws-handlers       ws-handlers})))
        ;; --tui=opentui supervisor teardown: stop the api-server, cancel any
        ;; parked prompts, restore the terminal (the sidecar owned raw mode), and
        ;; exit. Used both when the sidecar dies abnormally (watcher thread) and
        ;; in the run's finally. Idempotent via a one-shot flag.
        torn-down?             (atom false)
        teardown-opentui!      (fn [code]
                                 (when (compare-and-set! torn-down? false true)
                                   (try ((requiring-resolve 'escapement.ui.remote-renderer/cancel-all!))
                                        (catch Throwable _ nil))
                                   (when api-handle
                                     (try ((requiring-resolve 'escapement.ui.server/stop!) api-handle)
                                          (catch Throwable _ nil)))
                                   (when-let [p @sidecar-proc]
                                     (try ((requiring-resolve 'opentui.sidecar/destroy!) p)
                                          (catch Throwable _ nil)))
                                   (try ((requiring-resolve 'opentui.sidecar/restore-terminal!))
                                        (catch Throwable _ nil))
                                   ;; Restore the parent's stdout/stderr (flushing the capture
                                   ;; file) BEFORE re-enabling the console appender and before any
                                   ;; post-run summary, so the user-facing summary reaches the now-
                                   ;; restored normal screen instead of the session log.
                                   (when restore-streams! (restore-streams!))
                                   ;; #3 parity: the sidecar owned the tty; now that it is
                                   ;; restored, re-enable the console log appender so ordinary
                                   ;; CLI errors print on the normal screen again.
                                   (when log-file (restore-console-logging!))
                                   (when code (System/exit code))))
        exit-code
        (try
          (let [session-kw         (keyword "session" session)
                ;; OpenTUI: spawn + supervise the Bun sidecar. It connects back to
                ;; the just-started api-server's WS and owns the tty; the agent runs
                ;; headless below. A watcher thread tears the run down if the sidecar
                ;; dies abnormally (restoring the terminal). --no-spawn skips the
                ;; spawn so an externally-launched UI can attach (debug aid).
                _                  (when (and opentui? (not (:no-spawn opts)))
                                     (let [spawn!  (requiring-resolve 'opentui.sidecar/spawn!)
                                           proc    (spawn! {:entry         sidecar-entry
                                                            :port          api-server-port
                                                            :session-id    session
                                                            :session-dir   session-dir
                                                            :chart-sym     chart-sym
                                                            :session-short session-short})]
                                       (reset! sidecar-proc proc)
                                       (doto (Thread.
                                              (fn []
                                                (let [code (try (.waitFor proc) (catch Throwable _ -1))]
                                                  ;; sidecar gone → end the run + restore the tty
                                                  (teardown-opentui! (if (zero? code) 0 130)))))
                                         (.setDaemon true)
                                         (.start))))
                _                  (when (and opentui? (:no-spawn opts))
                                     (binding [*out* *err*]
                                       (println (str "[cli] --tui=opentui --no-spawn: connect a sidecar to "
                                                     "ws://127.0.0.1:" api-server-port "/ws"))))
                parse-pos-int      (fn [k]
                                     (when-let [s (get opts k)]
                                       (let [n (try (Long/parseLong s) (catch Throwable _ nil))]
                                         (when (or (nil? n) (not (pos? n)))
                                           (die! (str "--" (name k) " must be a positive integer (got: " s ")")
                                                 2))
                                         n)))
                max-frozen-cycles  (parse-pos-int :max-frozen-cycles)
                quiescent-sleep-ms (parse-pos-int :quiescent-sleep-ms)
                ;; OpenTUI: the sidecar owns the terminal, so any chart `*out*`/`*err*`
                ;; prints must NOT reach it. Redirect them to the session log so they
                ;; are captured but never corrupt the UI. (JLine already routes via
                ;; route-logs-to-file! + its own alt-screen ownership.)
                ot-log-writer      (when opentui?
                                     (io/writer (io/file (str session-dir "/escapement.log")) :append true))
                run-headless       (fn [thunk]
                                     (if ot-log-writer
                                       (binding [*out* ot-log-writer *err* ot-log-writer]
                                         (try (thunk) (finally (try (.flush ot-log-writer) (catch Throwable _ nil)))))
                                       (thunk)))
                summary            (run-headless
                                    (fn [] (runner/run!
                                    (cond-> {:chart                  chart
                                             :session-id             session-kw
                                             :transcript-path        transcript
                                             :checkpoint-dir         checkpoint-dir
                                             :session-dir            session-dir
                                             :backend                backend
                                             :backend-default-models backend-default-models
                                             :catalog-ratings        catalog-ratings
                                             :llm-aliases            llm-aliases
                                             :llm-preferences        llm-preferences
                                             :eligibility-strict?    eligibility-strict?
                                             :tool-registry          tool-registry
                                             :human-renderer         human-renderer
                                             :initial-data           initial-data
                                             :resume?                (boolean (:resume opts))
                                             :trace?                 (boolean (:trace opts))
                                             :multi-session?         (boolean (:multi-session? chart-meta))
                                             :prelude-events         prelude-events
                                             ;; Tee the transcript event stream to the JLine TUI
                                             ;; (when present) AND the live WS push hub (when the
                                             ;; api-server is up). Both are non-blocking; failures
                                             ;; in one must not disturb the other or the writer.
                                             :transcript-tap         (when (or tui-handle ws-publish!)
                                                                       (fn [ev]
                                                                         (when tui-handle
                                                                           (try (tui/event! tui-handle ev) (catch Throwable _ nil)))
                                                                         (when ws-publish!
                                                                           (try (ws-publish! ev) (catch Throwable _ nil)))))
                                             :debug-controller       debug-controller
                                             :human-input-active?    (cond
                                                                       tui-handle
                                                                       (fn [] (tui/human-input-active? tui-handle))
                                                                       opentui?
                                                                       (requiring-resolve 'escapement.ui.remote-renderer/human-input-active?))
                                             :on-env-ready           (when (or tui-handle control-handle)
                                                                       (fn [env]
                                                                         (when tui-handle
                                                                           (tui/attach-session!
                                                                            tui-handle
                                                                            session-kw
                                                                            (::sc/event-queue env))
                                                                           (tui/attach-env! tui-handle env chart))
                                                                          ;; Fill the shared control handle so the
                                                                          ;; api-server's live resolvers/mutations
                                                                          ;; (and the OpenTUI WS back-channel) can
                                                                          ;; reach the running env + queue.
                                                                         (when control-handle
                                                                           (ctrl-handle/fill! control-handle
                                                                                              {:env        env
                                                                                               :session-id session-kw
                                                                                               :queue      (::sc/event-queue env)
                                                                                               :controller debug-controller}))
                                                                         ;; Publish the initial debugger snapshot so a
                                                                         ;; sidecar started with --debug (auto-paused)
                                                                         ;; shows the PAUSED banner immediately.
                                                                         (when publish-debug!
                                                                           (publish-debug!
                                                                            {:paused      (dbg/paused? debug-controller)
                                                                             :step-budget (long (or (:step-budget @debug-controller) 0))}))))}
                                      max-frozen-cycles (assoc :max-frozen-cycles max-frozen-cycles)
                                      quiescent-sleep-ms (assoc :quiescent-sleep-ms quiescent-sleep-ms)))))]
            ;; Hold the TUI open after the chart finishes so the user can keep
            ;; browsing the inspector (transcripts, artifacts, history, viz)
            ;; instead of the process exiting out from under them. Ctrl-C from
            ;; the input thread breaks await-quit!.
            ;; #2: hold the finished JLine frame open (inspector stays live)
            ;; until the user presses Ctrl-C — unless `--no-keep-alive`.
            (when (and tui-handle keep-alive?)
              (tui/await-quit! tui-handle))
            ;; #3: stop the JLine TUI (exits the alt-screen) BEFORE re-enabling
            ;; the console log appender. Logs stayed file-routed for the whole
            ;; run (incl. teardown), so no library DEBUG line can land on the
            ;; alt-screen after `alt-screen-off` — the fast-finish/error debug
            ;; scrollback wall. Only once the normal screen is restored do we
            ;; let the console appender (and the summary prints below) speak.
            (when tui-handle
              (tui/stop! tui-handle)
              (when log-file (restore-console-logging!)))
            ;; OpenTUI: the agent finished first → signal the sidecar to exit and
            ;; restore the terminal. (Its watcher thread also fires on its own exit;
            ;; teardown is idempotent.) The summary lines go to the log, not the
            ;; sidecar-owned terminal.
            (if opentui?
              (do
                (when log-file
                  (try
                    (spit log-file
                          (str "session " session "\ntranscript " transcript
                               "\nfinal-config " (pr-str (:final-config summary)) "\n")
                          :append true)
                    (catch Throwable _ nil)))
                ;; #2 OpenTUI keep-alive: the agent finished first. Instead of
                ;; tearing the sidecar down to a black frame, push a `run/finished`
                ;; control frame so the sidecar can show a "finished — press
                ;; Ctrl-C to quit" banner over its last live frame, then HOLD this
                ;; process until the sidecar exits (the user's Ctrl-C → an inbound
                ;; `ui-quit` → `on-quit` `destroy!`s it; or the WS/proc closes).
                ;; The watcher thread runs `teardown-opentui!` on that exit; we
                ;; only fall through to an explicit teardown when keep-alive is off
                ;; (`--no-keep-alive` / non-TTY) or if the proc is already gone.
                ;; Default rule mirrors `keep-alive?`: held for interactive TTY,
                ;; immediate teardown otherwise — CI never blocks.
                (if (and keep-alive? ws-hub @sidecar-proc)
                  (do
                    (try ((requiring-resolve 'escapement.ui.ws-push/broadcast!)
                          ws-hub
                          {:kind "control" :op "run-finished"
                           :final-config (pr-str (:final-config summary))})
                         (catch Throwable _ nil))
                    ;; block until the sidecar exits (Ctrl-C → ui-quit → destroy!,
                    ;; or any WS/process close); then run idempotent teardown in
                    ;; case the watcher thread hasn't yet.
                    (try (.waitFor ^Process @sidecar-proc) (catch Throwable _ nil))
                    (teardown-opentui! nil))
                  (teardown-opentui! nil)))
              (do
                (println "session         " session)
                (println "transcript      " transcript)
                (println "checkpoint-dir  " checkpoint-dir)
                (when log-file (println "log             " log-file))
                (println "final-config    " (:final-config summary))))
            0)
          (catch Throwable t
            ;; #3: tear down the UI (alt-screen exit / tty restore) FIRST, then
            ;; re-enable console logging, so the failure line lands on the normal
            ;; screen — never on the alt-screen behind a debug-scrollback wall.
            (when tui-handle (tui/stop! tui-handle))
            (when opentui? (teardown-opentui! nil))
            (when (and log-file (not opentui?)) (restore-console-logging!))
            (binding [*out* *err*]
              (println "[cli] chart run failed:" (.getMessage t)))
            1)
          (finally
            (when opentui? (teardown-opentui! nil))
            (when api-handle
              (try ((requiring-resolve 'escapement.ui.server/stop!) api-handle)
                   (catch Throwable _ nil)))))]
    (System/exit exit-code)))

(defn- cmd-login [args]
  (let [[provider & _rest] args]
    (case provider
      "codex"
      (do (require 'escapement.llm.openai-codex.cli)
          ((resolve 'escapement.llm.openai-codex.cli/login!) _rest))
      nil (die! "Usage: escapement login <provider>  (currently: codex)")
      (die! (str "Unknown login provider: " provider)))))

(defn- cmd-logout [args]
  (let [[provider & _rest] args]
    (case provider
      "codex"
      (do (require 'escapement.llm.openai-codex.cli)
          ((resolve 'escapement.llm.openai-codex.cli/logout!) _rest))
      nil (die! "Usage: escapement logout <provider>  (currently: codex)")
      (die! (str "Unknown logout provider: " provider)))))

(def ^:private help-text
  "escapement — run a statechart-driven LLM agent.

Subcommands:
  run <chart-sym>   Load and execute a chart. See flags below.
  info              Print version + environment info.
  login codex       OAuth login for the codex backend.
  logout codex      Remove saved codex credentials.
  help              Show this help.

Common `run` flags:
  --input <edn-file>            Initial data (EDN map).
  --param key=value             One-shot initial-data entry. Repeatable.
                                Values EDN-read; bare words → strings.
  --session <id>                Session id; default a random UUID.
  --work-dir <path>             Per-session output parent; default .escapement
  --transcript <path>           Transcript path.
  --checkpoint-dir <dir>        Checkpoint dir.
  --resume                      Resume from saved working memory.
  --backend (api|codex|openai|ollama|opencode-go)
                                LLM backend (only needed for LLM charts).
  --model <name>                Model name.
  --api-base-url <url>          API base URL.
  --api-key-env <name>          Env-var name holding the API key.
  --tools-ns <sym[,sym…]>       Registration fns; repeatable.
  --source-paths <p[:p…]>       Extra classpath roots.
  --deps <edn>                  Inline EDN deps merged on top of .escapement.edn.
  --trace                       Emit per-tick transcript events.
  --max-frozen-cycles <n>       Override the runner's frozen-config guard
                                (default 200 cycles × 50ms = ~10s). Raise
                                for charts whose LLM workers take longer
                                than 10s between chart-visible events
                                (e.g. multi-step REPL evals).
  --quiescent-sleep-ms <ms>     Override the runner's idle-sleep (default
                                50ms). Increases per-cycle wall-clock so
                                --max-frozen-cycles N covers more time.
  --tui (jline|opentui)         Select the terminal UI. jline (default) is the
                                in-process JLine TUI; opentui spawns the OpenTUI
                                Bun sidecar (requires a TTY + bun + tui/opentui/ built;
                                starts the api-server+WS, runs the agent headless).
  --no-spawn                    With --tui=opentui: do NOT spawn the sidecar; start
                                the server+WS and print the ws:// URL so an
                                externally-launched UI can attach (debug aid).
  --no-tui                      Force-disable the TUI (overrides ^:interactive?).
  --keep-alive / --no-keep-alive
                                Pause-on-finish: hold the finished/errored UI
                                frame open until you press Ctrl-C, instead of
                                exiting out from under you (JLine keeps the
                                inspector live; OpenTUI shows a finished banner).
                                Default ON for an interactive TTY run with a UI;
                                always OFF for headless/non-TTY/UI-less runs (so
                                CI + --api-server-only never block).
  --dump-d2                     Print the chart's d2 diagram source and exit
                                (no run, no backend). Pipe to `d2 -` to render.
  --api-server <port>           Serve a read-only EQL HTTP API on <port> during
                                the run (POST transit EQL to /api).
  --debug                       Force the TUI on, enable inspector (`?`), and
                                pause before the first event so you can step.
                                Press `c` to continue. Recommended for watching
                                a non-interactive chart.

Exit codes: 0 success, 1 chart error, 2 usage error.")

(defn- print-help! []
  (println help-text)
  (System/exit 0))

(defn -main [& args]
  (let [[sub & rest-args] args]
    (case sub
      "info" (cmd-info rest-args)
      "run" (cmd-run rest-args)
      "login" (cmd-login rest-args)
      "logout" (cmd-logout rest-args)
      "help" (print-help!)
      "--help" (print-help!)
      "-h" (print-help!)
      nil (print-help!)
      (die! (str "Unknown subcommand: " sub "\nRun `escapement help` for usage.")))))
