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
           (let [k (keyword (subs a 2))]
             (cond
               (contains? bool-flags k)
               (recur (rest args) pos (assoc opts k true))

               (contains? multi-flags k)
               (if-let [v (second args)]
                 (recur (drop 2 args) pos (update opts k (fnil conj []) v))
                 (die! (str "Flag " a " requires a value")))

               :else
               (if-let [v (second args)]
                 (recur (drop 2 args) pos (assoc opts k v))
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
  "Resolve TUI on/off from flags + chart metadata + tty.

   --no-tui wins outright. --debug forces on (and errors with no
   TTY). Otherwise `^{:interactive? true}` on the chart var defaults to on;
   else off. `--debug` and `--no-tui` together is rejected."
  [opts chart-meta]
  (let [no-tui?      (boolean (:no-tui opts))
        debug?       (boolean (:debug opts))
        _            (when (and no-tui? debug?)
                       (die! "--debug requires the TUI; do not combine with --no-tui."))
        interactive? (boolean (:interactive? chart-meta))
        want?        (cond
                       no-tui? false
                       debug? true
                       :else interactive?)]
    (cond
      no-tui?
      false

      (and want? (not (tui/interactive-terminal?)))
      (die! (str "Interactive chart requires a TTY for the TUI.\n"
              "Run from a real terminal, or pass --no-tui (the chart's\n"
              ":human-input invocations will read from stdin).")
        1)

      :else want?)))

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
        (parse-args args #{:resume :trace :no-tui :debug :dump-d2} #{:param :tools-ns})
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
        _                      (when (needs-llm? opts)
                                 (die! (str "Error: no LLM backend configured.\n"
                                         "Options:\n"
                                         "  1. Set ANTHROPIC_API_KEY (Anthropic API)\n"
                                         "  2. Set ZAI_API_KEY (z.ai Anthropic-compatible endpoint)\n"
                                         "  3. Set OLLAMA_API_KEY (Ollama Cloud)\n"
                                         "  4. Set OPENCODE_GO_API_KEY (OpenCode Go)\n"
                                         "  5. Pass --backend codex  (ChatGPT Plus/Pro subscription; run 'escapement login codex' first)\n"
                                         "See: escapement info   (or:  Guide.adoc, \"LLM backends\")")
                                   1))
        backend-info           (make-backend opts)
        backend                (:backend backend-info)
        backend-default-models (:default-models backend-info)
        ;; Resolve the subjective ratings table + fail-closed flag ONCE
        ;; from the merged `.escapement.edn` at startup, then inject them
        ;; as explicit values into the invocation context (same code path
        ;; the lib facade — Step 4 — feeds from injected `:config`). The
        ;; processor no longer relies on the Step-1 disk-resolving 2-arg
        ;; `satisfies-policy?` seam.
        run-cfg                (config/load-config)
        catalog-ratings        (ratings/ratings run-cfg)
        eligibility-strict?    (boolean
                                 (or (:llm/eligibility-strict? run-cfg)
                                   (get-in run-cfg [:llm :eligibility-strict?])))
        ;; Load the chart FIRST. Its require-graph may include namespaces
        ;; whose top-level forms call
        ;; `(tp/register! escapement.tools.builtin/default-registry ...)`.
        ;; Those side-effects mutate the singleton registry atom and are then
        ;; visible to `runner/run!` below.
        [chart chart-meta] (runner/load-chart-with-meta chart-sym)
        use-tui?               (decide-tui opts chart-meta)
        ;; When the TUI is active it owns the terminal (alt-screen ANSI on
        ;; *err*). The interactive logging default is DEBUG, which would
        ;; otherwise scribble over the modal/scrollback ("flicker + messages").
        ;; Redirect those logs to a file so the screen stays clean but the
        ;; verbose trail is still captured for debugging.
        log-file               (when use-tui?
                                 (route-logs-to-file! (str session-dir "/escapement.log")))
        debug?                 (boolean (:debug opts))
        debug-cfg              (when debug? (config/load-config))
        debug-controller       (when debug?
                                 (dbg/new-controller
                                   {:initial-pause? (boolean
                                                      (get-in debug-cfg
                                                        [:debug :auto-pause?]
                                                        true))}))
        session-short          (apply str (take 8 session))
        tui-handle             (when use-tui?
                                 (tui/start! (cond-> {:chart-sym     chart-sym
                                                      :session-short session-short}
                                               debug? (assoc :debug? true
                                                             :debug-controller debug-controller
                                                             :debug-config debug-cfg))))
        human-renderer         (cond
                                 tui-handle (tui/->renderer tui-handle)
                                 (:interactive? chart-meta) (human-input/stdin-renderer)
                                 ;; Charts that don't declare :interactive? still get
                                 ;; a stdin renderer for any human-input states they
                                 ;; happen to invoke — fail-soft rather than silently
                                 ;; hang on a missing renderer.
                                 :else (human-input/stdin-renderer))
        tool-registry          (when backend
                                 (require 'escapement.tools.builtin)
                                 (let [reg-var (resolve 'escapement.tools.builtin/default-registry)
                                       _       (assert reg-var "escapement.tools.builtin/default-registry not found")
                                       reg     (deref reg-var)]
                                   (require-tools-nses! all-tools-ns reg)
                                   ;; R3: builtin path-taking tools resolve RELATIVE paths
                                   ;; against the session work-dir. `tp/dispatch` reads
                                   ;; `:escapement/base-dir` off the registry's metadata
                                   ;; when no explicit base-dir arg is given.
                                   (when session-dir
                                     (alter-meta! reg assoc :escapement/base-dir session-dir))
                                   reg))
        api-server-port        (when-let [s (:api-server opts)]
                                 (let [n (try (Long/parseLong s) (catch Throwable _ nil))]
                                   (when-not (and n (pos? n))
                                     (die! (str "--api-server must be a positive port (got: " s ")") 2))
                                   n))
        ;; Shared control handle: created here (before the env exists) and given
        ;; to BOTH the api-server ctx and `run!`'s on-env-ready (which fills it
        ;; with the live env/queue/controller). This is the seam by which the
        ;; server's live resolvers/mutations reach the running chart under bb.
        control-handle         (when api-server-port (ctrl-handle/new-handle))
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
                                   (binding [*out* *err*]
                                     (println (str "[cli] EQL API on http://localhost:" api-server-port "/api"))
                                     (println (str "[cli] UI     on http://localhost:" api-server-port "/")))
                                   (start! {:port              api-server-port
                                            :work-dir          work-dir
                                            :active-session-id session
                                            :chart             chart
                                            :controller        debug-controller
                                            :live              control-handle})))
        exit-code
        (try
          (let [session-kw         (keyword "session" session)
                parse-pos-int      (fn [k]
                                     (when-let [s (get opts k)]
                                       (let [n (try (Long/parseLong s) (catch Throwable _ nil))]
                                         (when (or (nil? n) (not (pos? n)))
                                           (die! (str "--" (name k) " must be a positive integer (got: " s ")")
                                             2))
                                         n)))
                max-frozen-cycles  (parse-pos-int :max-frozen-cycles)
                quiescent-sleep-ms (parse-pos-int :quiescent-sleep-ms)
                summary            (runner/run!
                                     (cond-> {:chart                  chart
                                              :session-id             session-kw
                                              :transcript-path        transcript
                                              :checkpoint-dir         checkpoint-dir
                                              :session-dir            session-dir
                                              :backend                backend
                                              :backend-default-models backend-default-models
                                              :catalog-ratings        catalog-ratings
                                              :eligibility-strict?    eligibility-strict?
                                              :tool-registry          tool-registry
                                              :human-renderer         human-renderer
                                              :initial-data           initial-data
                                              :resume?                (boolean (:resume opts))
                                              :trace?                 (boolean (:trace opts))
                                              :multi-session?         (boolean (:multi-session? chart-meta))
                                              :prelude-events         prelude-events
                                              :transcript-tap         (when tui-handle
                                                                        (fn [ev] (tui/event! tui-handle ev)))
                                              :debug-controller       debug-controller
                                              :human-input-active?    (when tui-handle
                                                                        (fn [] (tui/human-input-active? tui-handle)))
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
                                                                          ;; can reach the running env + queue.
                                                                          (when control-handle
                                                                            (ctrl-handle/fill! control-handle
                                                                              {:env        env
                                                                               :session-id session-kw
                                                                               :queue      (::sc/event-queue env)
                                                                               :controller debug-controller}))))}
                                       max-frozen-cycles (assoc :max-frozen-cycles max-frozen-cycles)
                                       quiescent-sleep-ms (assoc :quiescent-sleep-ms quiescent-sleep-ms)))]
            ;; In debug mode, hold the TUI open after the chart finishes so the
            ;; user can keep browsing the inspector (artifacts, history, viz).
            ;; Ctrl-C from the input thread breaks await-quit!.
            (when (and tui-handle debug?)
              (tui/await-quit! tui-handle))
            (when tui-handle (tui/stop! tui-handle))
            (println "session         " session)
            (println "transcript      " transcript)
            (println "checkpoint-dir  " checkpoint-dir)
            (when log-file (println "log             " log-file))
            (println "final-config    " (:final-config summary))
            0)
          (catch Throwable t
            (when tui-handle (tui/stop! tui-handle))
            (binding [*out* *err*]
              (println "[cli] chart run failed:" (.getMessage t)))
            1)
          (finally
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
  --no-tui                      Force-disable the TUI (overrides ^:interactive?).
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
