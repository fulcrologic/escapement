(ns escapement.cli
  "Babashka/JVM entry point for the agent.

  Subcommands:

    run <chart-sym>   — Load and execute a chart, writing a JSONL transcript and checkpoints.
      Flags:
        --input <edn-file>      Initial data (EDN map).
        --session <id>          Session id; default a random UUID.
        --work-dir <path>       Parent dir for per-session output; default .escapement
        --transcript <path>     Transcript path; default <work-dir>/<session>/transcript.jsonl
        --checkpoint-dir <dir>  Checkpoint dir; default <work-dir>/<session>/checkpoints
        --resume                Resume from saved working memory.
        --backend (claude-p|api)  LLM backend (optional; only needed for LLM charts).
        --model <name>          Model name.
        --api-base-url <url>    API base URL.
        --api-key-env <name>    Env-var name holding the API key.
        --tools-ns <sym>        Qualified symbol of a registration fn called with the
                                builtin registry atom. The fn can register any number
                                of additional tools (or compose other registration
                                fns), so one --tools-ns is enough per run.
                                e.g. --tools-ns my.app.tools/register-tools!
        --trace                 Emit per-tick transcript events.

    info              — Print version + environment info.

  Exit codes: 0 success, 1 chart error, 2 usage error."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [escapement.runner :as runner]))

(def ^:const version "0.1.0")

(defn- die!
  ([msg] (die! msg 2))
  ([msg code]
   (binding [*out* *err*]
     (println msg))
   (System/exit code)))

(defn- parse-args
  "Split positional args from --flag [value] options into `{:positional [...] :opts {...}}`.

   Boolean flags can be declared in `bool-flags`; everything else takes a value."
  [args bool-flags]
  (loop [args args
         pos  []
         opts {}]
    (if (empty? args)
      {:positional pos :opts opts}
      (let [a (first args)]
        (cond
          (str/starts-with? a "--")
          (let [k (keyword (subs a 2))]
            (if (contains? bool-flags k)
              (recur (rest args) pos (assoc opts k true))
              (if-let [v (second args)]
                (recur (drop 2 args) pos (assoc opts k v))
                (die! (str "Flag " a " requires a value")))))
          :else
          (recur (rest args) (conj pos a) opts))))))

(defn- read-edn-file [path]
  (with-open [r (java.io.PushbackReader. (io/reader path))]
    (edn/read r)))

(defn- autodetect-api-opts
  "Inspect environment variables and return a map suitable for the `:api`
   backend constructor, or nil if no key is available.

   ANTHROPIC_API_KEY takes precedence over ZAI_API_KEY (Anthropic is canonical;
   z.ai is a compat endpoint)."
  []
  (let [anthropic (System/getenv "ANTHROPIC_API_KEY")
        zai       (System/getenv "ZAI_API_KEY")]
    (cond
      (and anthropic (not (str/blank? anthropic)))
      {:source        "ANTHROPIC_API_KEY"
       :api-key       anthropic
       :base-url      "https://api.anthropic.com"
       :default-model "claude-sonnet-4-6"
       :auth-mode     :x-api-key}

      (and zai (not (str/blank? zai)))
      {:source        "ZAI_API_KEY"
       :api-key       zai
       :base-url      "https://api.z.ai/api/anthropic"
       :default-model "glm-4.6"
       :auth-mode     :bearer}

      :else nil)))

(defn- build-api-backend [opts]
  (require 'escapement.llm.api)
  (let [ctor (resolve 'escapement.llm.api/new-backend)]
    (assert ctor "escapement.llm.api/new-backend not found")
    (ctor opts)))

(defn- make-backend
  "Construct an LLM backend.

   If `--backend` is explicitly provided, honor it. Otherwise auto-detect from
   environment variables (ANTHROPIC_API_KEY or ZAI_API_KEY) and construct an
   `:api` backend. If neither is set and no `--backend` was given, return nil
   (charts that don't need an LLM are still fine)."
  [{:keys [backend model api-base-url api-key-env]}]
  (if backend
    (case backend
      "claude-p"
      (do (require 'escapement.llm.claude-p)
          (let [ctor (resolve 'escapement.llm.claude-p/new-backend)]
            (assert ctor "escapement.llm.claude-p/new-backend not found")
            (ctor (cond-> {} model (assoc :model model)))))

      "api"
      (build-api-backend (cond-> {}
                           model        (assoc :model model)
                           api-base-url (assoc :base-url api-base-url)
                           api-key-env  (assoc :api-key (System/getenv api-key-env))))

      (die! (str "Unknown backend: " backend)))
    ;; No --backend: try env auto-detect.
    (when-let [auto (autodetect-api-opts)]
      (binding [*out* *err*]
        (println (str "[cli] auto-detected LLM backend from " (:source auto)
                      " (" (:base-url auto) ", model " (:default-model auto) ")")))
      (build-api-backend (-> auto
                             (dissoc :source)
                             (cond-> model (assoc :default-model model)))))))

(defn- needs-llm?
  "Heuristic: does this chart require an LLM backend? We treat any chart loaded
   from the conventional `*.charts.*` namespace as potentially LLM-using; the
   safer signal is the absence of env keys AND no --backend flag — at that
   point we surface the actionable error before the engine reports a cryptic
   `:type :llm-conversation` message."
  [opts]
  (and (nil? (:backend opts))
       (nil? (autodetect-api-opts))))

(defn- cmd-info [_args]
  (println "escapement" version)
  (println "java" (System/getProperty "java.version"))
  (println "os"   (System/getProperty "os.name") (System/getProperty "os.version"))
  (when-let [bb (System/getProperty "babashka.version")]
    (println "babashka" bb))
  (println "cwd" (System/getProperty "user.dir"))
  (System/exit 0))

(defn- cmd-run [args]
  (let [{:keys [positional opts]}
        (parse-args args #{:resume :trace})
        chart-arg (first positional)
        _         (when-not chart-arg
                    (die! "Usage: run <chart-sym> [flags]"))
        chart-sym (symbol chart-arg)
        _         (when-not (qualified-symbol? chart-sym)
                    (die! (str "Chart symbol must be qualified, got: " chart-arg)))
        session   (or (:session opts) (str (java.util.UUID/randomUUID)))
        work-dir  (or (:work-dir opts) ".escapement")
        session-dir (str work-dir "/" session)
        transcript (or (:transcript opts) (str session-dir "/transcript.jsonl"))
        checkpoint-dir (or (:checkpoint-dir opts) (str session-dir "/checkpoints"))
        _         (.mkdirs (io/file session-dir))
        initial-data (when-let [p (:input opts)] (read-edn-file p))
        _         (when (needs-llm? opts)
                    (die! (str "Error: no LLM backend configured.\n"
                               "Set ANTHROPIC_API_KEY or ZAI_API_KEY, or pass --backend explicitly.\n"
                               "See: escapement info --backends   (or:  Guide.adoc, \"LLM backends\")")
                          1))
        backend (make-backend opts)
        ;; Load the chart FIRST. Its require-graph may include namespaces
        ;; whose top-level forms call
        ;; `(tp/register! escapement.tools.builtin/default-registry ...)`.
        ;; Those side-effects mutate the singleton registry atom and are then
        ;; visible to `runner/run!` below.
        chart   (runner/load-chart chart-sym)
        tool-registry (when backend
                        (require 'escapement.tools.builtin)
                        (let [reg-var (resolve 'escapement.tools.builtin/default-registry)
                              _       (assert reg-var "escapement.tools.builtin/default-registry not found")
                              reg     (deref reg-var)]
                          ;; --tools-ns is an explicit hook for cases where the
                          ;; chart can't transitively require the tools, or you
                          ;; want declarative wiring at the entry point.
                          (when-let [sym-str (:tools-ns opts)]
                            (let [sym (try (symbol sym-str)
                                           (catch Throwable _
                                             (die! (str "Invalid --tools-ns symbol: " sym-str))))
                                  _   (when-not (qualified-symbol? sym)
                                        (die! (str "--tools-ns must be qualified (namespace/name), got: " sym-str)))
                                  ns-sym (symbol (namespace sym))]
                              (try (require ns-sym)
                                   (catch Throwable t
                                     (die! (str "Could not require --tools-ns namespace "
                                                ns-sym ": " (.getMessage t)) 1)))
                              (if-let [v (resolve sym)]
                                ((deref v) reg)
                                (die! (str "Could not resolve --tools-ns: " sym) 1))))
                          reg))]
    (try
      (let [summary (runner/run! {:chart           chart
                                  :session-id      (keyword "session" session)
                                  :transcript-path transcript
                                  :checkpoint-dir  checkpoint-dir
                                  :backend         backend
                                  :tool-registry   tool-registry
                                  :initial-data    initial-data
                                  :resume?         (boolean (:resume opts))
                                  :trace?          (boolean (:trace opts))})]
        (println "session         " session)
        (println "transcript      " transcript)
        (println "checkpoint-dir  " checkpoint-dir)
        (println "final-config    " (:final-config summary))
        (System/exit 0))
      (catch Throwable t
        (binding [*out* *err*]
          (println "[cli] chart run failed:" (.getMessage t)))
        (System/exit 1)))))

(defn -main [& args]
  (let [[sub & rest-args] args]
    (case sub
      "info" (cmd-info rest-args)
      "run"  (cmd-run rest-args)
      nil    (die! "Usage: bb -m escapement.cli (run <chart-sym>|info)")
      (die! (str "Unknown subcommand: " sub)))))
