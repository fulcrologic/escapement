(ns escapement.cli
  "Babashka/JVM entry point for the agent.

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
        --backend (api|codex|openai)  LLM backend (optional; only needed for LLM charts).
        --model <name>          Model name.
        --api-base-url <url>    API base URL.
        --api-key-env <name>    Env-var name holding the API key.
        --tools-ns <sym>        Qualified symbol of a registration fn called with the
                                builtin registry atom. The fn can register any number
                                of additional tools (or compose other registration
                                fns), so one --tools-ns is enough per run.
                                e.g. --tools-ns my.app.tools/register-tools!
        --trace                 Emit per-tick transcript events.
        --tui                   Force-enable the persistent TUI display.
        --no-tui                Force-disable the TUI (overrides --tui and
                                ^{:interactive? true} chart metadata).
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
   [escapement.debug.controller :as dbg]
   [escapement.invocation.human-input :as human-input]
   [escapement.runner :as runner]
   [escapement.transcript :as transcript]
   [escapement.tui :as tui]))

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
                         (symbol? parsed)         v
                         :else                    parsed)]
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

(defn- autodetect-api-opts
  "Inspect environment variables and return a map suitable for the `:api`
   backend constructor, or nil if no key is available.

   ANTHROPIC_API_KEY takes precedence over ZAI_API_KEY (Anthropic is canonical;
   z.ai is a compat endpoint)."
  []
  (let [anthropic  (System/getenv "ANTHROPIC_API_KEY")
        zai        (System/getenv "ZAI_API_KEY")
        openai     (System/getenv "OPENAI_API_KEY")
        openrouter (System/getenv "OPENROUTER_API_KEY")]
    (cond
      (and anthropic (not (str/blank? anthropic)))
      {:source        "ANTHROPIC_API_KEY"
       :backend-kind  :api
       :api-key       anthropic
       :base-url      "https://api.anthropic.com"
       :default-model "claude-sonnet-4-6"
       :auth-mode     :x-api-key}

      (and zai (not (str/blank? zai)))
      {:source        "ZAI_API_KEY"
       :backend-kind  :api
       :api-key       zai
       :base-url      "https://api.z.ai/api/anthropic"
       :default-model "glm-4.6"
       :auth-mode     :bearer}

      (and openai (not (str/blank? openai)))
      {:source        "OPENAI_API_KEY"
       :backend-kind  :openai
       :api-key       openai
       :base-url      "https://api.openai.com/v1"
       :default-model (or (System/getenv "OPENAI_MODEL") "gpt-4o-mini")}

      (and openrouter (not (str/blank? openrouter)))
      ;; OpenRouter is OpenAI-shaped; route it through the openai backend.
      ;; Default to a free/cheap model unless OPENROUTER_MODEL overrides.
      {:source        "OPENROUTER_API_KEY"
       :backend-kind  :openai
       :api-key       openrouter
       :base-url      "https://openrouter.ai/api/v1"
       :default-model (or (System/getenv "OPENROUTER_MODEL") "openai/gpt-4o-mini")}

      :else nil)))

(defn- build-api-backend [opts]
  (require 'escapement.llm.api)
  (let [ctor (resolve 'escapement.llm.api/new-backend)]
    (assert ctor "escapement.llm.api/new-backend not found")
    (ctor opts)))

(defn- build-openai-backend [opts]
  (require 'escapement.llm.openai)
  (let [ctor (resolve 'escapement.llm.openai/new-backend)]
    (assert ctor "escapement.llm.openai/new-backend not found")
    (ctor opts)))

(defn- build-codex-backend [opts]
  (require 'escapement.llm.openai-codex)
  (let [ctor (resolve 'escapement.llm.openai-codex/new-backend)]
    (assert ctor "escapement.llm.openai-codex/new-backend not found")
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
      "api"
      (build-api-backend (cond-> {}
                           model        (assoc :model model)
                           api-base-url (assoc :base-url api-base-url)
                           api-key-env  (assoc :api-key (System/getenv api-key-env))))

      "openai"
      (build-openai-backend (cond-> {:base-url      (or api-base-url "https://api.openai.com/v1")
                                     :default-model (or model "gpt-4o-mini")}
                              api-key-env (assoc :api-key (System/getenv api-key-env))
                              (not api-key-env) (assoc :api-key (System/getenv "OPENAI_API_KEY"))))

      "codex"
      (build-codex-backend (cond-> {}
                             model (assoc :default-model model)))

      (die! (str "Unknown backend: " backend)))
    ;; No --backend: try env auto-detect first, then codex OAuth token.
    (if-let [auto (autodetect-api-opts)]
      (do
        (binding [*out* *err*]
          (println (str "[cli] auto-detected LLM backend from " (:source auto)
                        " (" (:base-url auto) ", model " (:default-model auto) ")")))
        (let [kind (:backend-kind auto)
              opts (-> auto
                       (dissoc :source :backend-kind)
                       (cond-> model (assoc :default-model model)))]
          (case kind
            :openai (build-openai-backend opts)
            (build-api-backend opts))))
      ;; Check for saved codex OAuth credentials.
      (when (try
              (require 'escapement.llm.openai-codex.auth)
              (let [load-auth! (resolve 'escapement.llm.openai-codex.auth/load-auth!)]
                (some? (load-auth!)))
              (catch Throwable _ false))
        (binding [*out* *err*]
          (println "[cli] auto-detected LLM backend: codex (saved OAuth token)"))
        (build-codex-backend (cond-> {} model (assoc :default-model model)))))))

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
    (let [load!   (resolve 'escapement.llm.openai-codex.auth/load-auth!)
          auth    (load!)]
      (when auth
        (str "present  account=" (:account-id auth)
             "  expires=" (java.util.Date. ^long (:expires-at auth 0)))))
    (catch Throwable _ nil)))

(defn- needs-llm?
  "Heuristic: does this chart require an LLM backend? We treat any chart loaded
   from the conventional `*.charts.*` namespace as potentially LLM-using; the
   safer signal is the absence of env keys AND no --backend flag — at that
   point we surface the actionable error before the engine reports a cryptic
   `:type :llm-conversation` message."
  [opts]
  (and (nil? (:backend opts))
       (nil? (autodetect-api-opts))
       (nil? (codex-auth-info))))

(defn- cmd-info [_args]
  (println "escapement" version)
  (println "java" (System/getProperty "java.version"))
  (println "os"   (System/getProperty "os.name") (System/getProperty "os.version"))
  (when-let [bb (System/getProperty "babashka.version")]
    (println "babashka" bb))
  (println "cwd" (System/getProperty "user.dir"))
  (println)
  (println "LLM backends:")
  (let [anthropic  (System/getenv "ANTHROPIC_API_KEY")
        zai        (System/getenv "ZAI_API_KEY")
        openai     (System/getenv "OPENAI_API_KEY")
        openrouter (System/getenv "OPENROUTER_API_KEY")]
    (println "  ANTHROPIC_API_KEY : " (if (seq anthropic) "set" "not set"))
    (println "  ZAI_API_KEY       : " (if (seq zai) "set" "not set"))
    (println "  OPENAI_API_KEY    : " (if (seq openai) "set" "not set"))
    (println "  OPENROUTER_API_KEY: " (if (seq openrouter) "set" "not set")))
  (let [codex-info (codex-auth-info)
        auth-file  (codex-auth-file)]
    (println "  codex OAuth       : " (or codex-info "not logged in"))
    (when auth-file
      (println "  codex auth file   : " auth-file)))
  (System/exit 0))

(defn- decide-tui
  "Resolve TUI on/off from flags + chart metadata + tty.

   --no-tui wins outright. --debug or --tui forces on (and errors with no
   TTY). Otherwise `^{:interactive? true}` on the chart var defaults to on;
   else off. `--debug` and `--no-tui` together is rejected."
  [opts chart-meta]
  (let [no-tui?  (boolean (:no-tui opts))
        tui?     (boolean (:tui opts))
        debug?   (boolean (:debug opts))
        _        (when (and no-tui? debug?)
                   (die! "--debug requires the TUI; do not combine with --no-tui."))
        interactive? (boolean (:interactive? chart-meta))
        want?    (cond
                   no-tui? false
                   debug?  true
                   tui?    true
                   :else   interactive?)]
    (cond
      no-tui?
      false

      (and want? (not (tui/interactive-terminal?)))
      (die! (str "Interactive chart requires a TTY for the TUI.\n"
                 "Run from a real terminal, or pass --no-tui (the chart's\n"
                 ":human-input invocations will read from stdin).")
            1)

      :else want?)))

(defn- cmd-run [args]
  (let [{:keys [positional opts]}
        (parse-args args #{:resume :trace :tui :no-tui :debug} #{:param})
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
        initial-data (let [base (when-let [p (:input opts)] (read-edn-file p))]
                       (merge-params base (:param opts)))
        _         (when (needs-llm? opts)
                    (die! (str "Error: no LLM backend configured.\n"
                               "Options:\n"
                               "  1. Set ANTHROPIC_API_KEY (Anthropic API)\n"
                               "  2. Set ZAI_API_KEY (z.ai Anthropic-compatible endpoint)\n"
                               "  3. Pass --backend codex  (ChatGPT Plus/Pro subscription; run 'escapement login codex' first)\n"
                               "See: escapement info   (or:  Guide.adoc, \"LLM backends\")")
                          1))
        backend (make-backend opts)
        ;; Load the chart FIRST. Its require-graph may include namespaces
        ;; whose top-level forms call
        ;; `(tp/register! escapement.tools.builtin/default-registry ...)`.
        ;; Those side-effects mutate the singleton registry atom and are then
        ;; visible to `runner/run!` below.
        [chart chart-meta] (runner/load-chart-with-meta chart-sym)
        use-tui?       (decide-tui opts chart-meta)
        debug?         (boolean (:debug opts))
        debug-cfg      (when debug? (config/load-config))
        debug-controller (when debug?
                           (dbg/new-controller
                            {:initial-pause? (boolean
                                              (get-in debug-cfg
                                                      [:debug :auto-pause?]
                                                      true))}))
        session-short  (apply str (take 8 session))
        tui-handle     (when use-tui?
                         (tui/start! (cond-> {:chart-sym     chart-sym
                                              :session-short session-short}
                                       debug? (assoc :debug?           true
                                                     :debug-controller debug-controller
                                                     :debug-config     debug-cfg))))
        human-renderer (cond
                         tui-handle              (tui/->renderer tui-handle)
                         (:interactive? chart-meta) (human-input/stdin-renderer)
                         ;; Charts that don't declare :interactive? still get
                         ;; a stdin renderer for any human-input states they
                         ;; happen to invoke — fail-soft rather than silently
                         ;; hang on a missing renderer.
                         :else (human-input/stdin-renderer))
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
      (let [session-kw (keyword "session" session)
            summary    (runner/run!
                        {:chart           chart
                         :session-id      session-kw
                         :transcript-path transcript
                         :checkpoint-dir  checkpoint-dir
                         :session-dir     session-dir
                         :backend         backend
                         :tool-registry   tool-registry
                         :human-renderer  human-renderer
                         :initial-data    initial-data
                         :resume?         (boolean (:resume opts))
                         :trace?          (boolean (:trace opts))
                         :transcript-tap  (when tui-handle
                                            (fn [ev] (tui/event! tui-handle ev)))
                         :debug-controller debug-controller
                         :human-input-active? (when tui-handle
                                                (fn [] (tui/human-input-active? tui-handle)))
                         :on-env-ready    (when tui-handle
                                            (fn [env]
                                              (tui/attach-session!
                                               tui-handle
                                               session-kw
                                               (::sc/event-queue env))
                                              (tui/attach-env! tui-handle env chart)))})]
        ;; In debug mode, hold the TUI open after the chart finishes so the
        ;; user can keep browsing the inspector (artifacts, history, viz).
        ;; Ctrl-C from the input thread breaks await-quit!.
        (when (and tui-handle debug?)
          (tui/await-quit! tui-handle))
        (when tui-handle (tui/stop! tui-handle))
        (println "session         " session)
        (println "transcript      " transcript)
        (println "checkpoint-dir  " checkpoint-dir)
        (println "final-config    " (:final-config summary))
        (System/exit 0))
      (catch Throwable t
        (when tui-handle (tui/stop! tui-handle))
        (binding [*out* *err*]
          (println "[cli] chart run failed:" (.getMessage t)))
        (System/exit 1)))))

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

(defn -main [& args]
  (let [[sub & rest-args] args]
    (case sub
      "info"   (cmd-info rest-args)
      "run"    (cmd-run rest-args)
      "login"  (cmd-login rest-args)
      "logout" (cmd-logout rest-args)
      nil      (die! "Usage: bb -m escapement.cli (run <chart-sym>|info|login codex|logout codex)")
      (die! (str "Unknown subcommand: " sub)))))
