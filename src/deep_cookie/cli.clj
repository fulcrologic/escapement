(ns deep-cookie.cli
  "Babashka/JVM entry point for the agent.

  Subcommands:

    run <chart-sym>   — Load and execute a chart, writing a JSONL transcript and checkpoints.
      Flags:
        --input <edn-file>      Initial data (EDN map).
        --session <id>          Session id; default a random UUID.
        --work-dir <path>       Parent dir for per-session output; default .deep-cookie
        --transcript <path>     Transcript path; default <work-dir>/<session>/transcript.jsonl
        --checkpoint-dir <dir>  Checkpoint dir; default <work-dir>/<session>/checkpoints
        --resume                Resume from saved working memory.
        --backend (claude-p|api)  LLM backend (optional; only needed for LLM charts).
        --model <name>          Model name.
        --api-base-url <url>    API base URL.
        --api-key-env <name>    Env-var name holding the API key.
        --trace                 Emit per-tick transcript events.

    info              — Print version + environment info.

  Exit codes: 0 success, 1 chart error, 2 usage error."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [deep-cookie.runner :as runner]))

(def ^:const version "0.1.0-m5")

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

(defn- make-backend
  "Construct an LLM backend if requested. Returns nil if `--backend` was not set."
  [{:keys [backend model api-base-url api-key-env]}]
  (when backend
    (case backend
      "claude-p"
      (do (require 'deep-cookie.llm.claude-p)
          (let [ctor (resolve 'deep-cookie.llm.claude-p/new-backend)]
            (assert ctor "deep-cookie.llm.claude-p/new-backend not found")
            (ctor (cond-> {} model (assoc :model model)))))

      "api"
      (do (require 'deep-cookie.llm.api)
          (let [ctor (resolve 'deep-cookie.llm.api/new-backend)]
            (assert ctor "deep-cookie.llm.api/new-backend not found")
            (ctor (cond-> {}
                    model        (assoc :model model)
                    api-base-url (assoc :base-url api-base-url)
                    api-key-env  (assoc :api-key (System/getenv api-key-env))))))

      (die! (str "Unknown backend: " backend)))))

(defn- cmd-info [_args]
  (println "deep-cookie" version)
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
        work-dir  (or (:work-dir opts) ".deep-cookie")
        session-dir (str work-dir "/" session)
        transcript (or (:transcript opts) (str session-dir "/transcript.jsonl"))
        checkpoint-dir (or (:checkpoint-dir opts) (str session-dir "/checkpoints"))
        _         (.mkdirs (io/file session-dir))
        initial-data (when-let [p (:input opts)] (read-edn-file p))
        backend (make-backend opts)
        chart   (runner/load-chart chart-sym)]
    (try
      (let [summary (runner/run! {:chart           chart
                                  :session-id      (keyword "session" session)
                                  :transcript-path transcript
                                  :checkpoint-dir  checkpoint-dir
                                  :backend         backend
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
      nil    (die! "Usage: bb -m deep-cookie.cli (run <chart-sym>|info)")
      (die! (str "Unknown subcommand: " sub)))))
