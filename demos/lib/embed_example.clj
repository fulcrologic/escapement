(ns lib.embed-example
  "Worked example: embedding Escapement in a host project as a library.

  This is the example a *library consumer* should read first. It is the
  end-to-end LLM path that the README's `escapement.lib/run` quickstart
  intentionally omits (that one is a trivial no-LLM chart). It demonstrates,
  in one runnable file, every concern a real host hits:

    * authoring a real LLM chart with `escapement.chart.helpers`
      (`h/llm-conversation`, `h/capture-llm-output`, `h/render-template`)
      instead of hand-rolling `invoke` elements;
    * the **hermetic** contract: `:credentials` + `:config` are injected as
      plain data — no `.escapement.edn`, no env sniffing on the lib path;
    * passing `:initial-data` and `:session-dir` (absent from the README
      embed examples, but needed by any non-trivial chart);
    * **streaming** assistant text live via the public, normalized event
      stream — `escapement.lib.event-sink`'s `:text-delta` event — rather
      than tapping raw transcript rows by hand;
    * keeping correlation in the host closure via `:run-id`;
    * a vision variant (commented) showing the base64 image content block.

  Two sequential LLM phases share context through a file-backed artifact:

    phase 1 `brief`  — write a short product brief  → artifacts/brief.md
    phase 2 `pitch`  — render {{brief.md}} into a 1-paragraph elevator pitch
                       (streamed to stdout as it generates) → artifacts/pitch.md

  Run under Babashka (no JVM needed):

    OPENAI_API_KEY=sk-...   bb -m lib.embed-example
    ANTHROPIC_API_KEY=sk-...  bb -m lib.embed-example
    ZAI_API_KEY=...           bb -m lib.embed-example
    OLLAMA_API_KEY=...        bb -m lib.embed-example

  A host would normally hold the credential vector at app startup (like a DB
  pool) and pass it on every `run`; here we derive it from one env var only
  so the file is copy-runnable."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final state transition]]
    [escapement.chart.helpers :as h]
    [escapement.lib :as escapement]
    [escapement.lib.event-sink :as sink]
    [escapement.tools.protocol :as tools]))

;; ---------------------------------------------------------------------------
;; 1. Credentials — injected data, not env sniffing.
;;
;; The lib path NEVER reads env on its own. The host decides where secrets
;; come from. We resolve exactly one provider here for runnability; in a real
;; app this vector is built once at startup and reused.
;; ---------------------------------------------------------------------------

(defn- host-credential
  "Return [credential default-model] for the first provider whose env var is
  set, or throw with a clear message. The keyword matches a provider in
  `escapement.llm.providers/provider-templates`."
  []
  (let [env  #(System/getenv %)
        pick (fn [v provider model] (when v [{:provider provider :api-key v} model]))]
    (or (pick (env "ANTHROPIC_API_KEY") :anthropic "claude-sonnet-4-6")
      (pick (env "OPENAI_API_KEY") :openai "gpt-4o-mini")
      (pick (env "OPENROUTER_API_KEY") :openrouter "openai/gpt-4o-mini")
      (pick (env "ZAI_API_KEY") :z-ai "glm-4.6")
      (pick (env "OLLAMA_API_KEY") :ollama "gpt-oss:20b")
      (throw (ex-info "Set one of ANTHROPIC_API_KEY / OPENAI_API_KEY / OPENROUTER_API_KEY / ZAI_API_KEY / OLLAMA_API_KEY"
               {:reason :missing-credential})))))

;; ---------------------------------------------------------------------------
;; 2. The chart — authored with escapement.chart.helpers.
;;
;; `h/llm-conversation` expands to the `:llm-conversation` invoke. Params are
;; authored as FLAT keys: each is a literal OR a `(fn [env data])` resolved at
;; invoke time. Static config (`:system`, `:model`, `:budget-ms`, `:stream?`)
;; stays a literal; only data-dependent slots (`:message`) are lambdas. Note
;; the friendly aliases: `:message` (the initial user message) and `:budget-ms`
;; (wall-clock budget). `h/capture-llm-output` writes the final assistant text
;; to <session-dir>/artifacts/<:as>. `h/render-template` (used inside a
;; `:message` lambda, where `env` is in scope) substitutes {{brief.md}} with the
;; artifact phase 1 produced.
;; ---------------------------------------------------------------------------

(defn embed-chart [model]
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :brief}

      (state {:id :brief}
        (h/llm-conversation
          {:id        "brief"
           :system    "You are a crisp product writer. Reply with prose only, no preamble."
           :model     model
           :budget-ms 60000
           :message   (fn [_env data]
                        (str "Write a 3-sentence product brief for: " (:idea data)))})
        (transition {:event :llm.idle :target :pitch}
          (h/capture-llm-output {:as "brief.md"})))

      (state {:id :pitch}
        (h/llm-conversation
          {:id        "pitch"
           :system    "You are a punchy startup pitch writer. One paragraph, no preamble."
           :model     model
           :stream?   true
           :budget-ms 60000
           :message   (fn [env _data]
                        (h/render-template
                          (str "Turn this brief into a single-paragraph elevator pitch:\n\n"
                            "{{brief.md}}")
                          env))})
        (transition {:event :llm.idle :target :done}
          (h/capture-llm-output {:as "pitch.md"})))

      (final {:id :done}))))

;; ---------------------------------------------------------------------------
;; 3. Run it via escapement.lib/run + stream phase 2 live.
;;
;; We attach an event-sink adapter to :transcript-tap. The sink normalizes
;; raw rows into the closed public event union; `:text-delta` is the public
;; way to stream assistant tokens — no need to match raw `:llm/delta` rows.
;; Correlation (:run-id) stays in this closure.
;; ---------------------------------------------------------------------------

(defn -main [& args]
  (let [idea     (or (first args) "a CLI that turns terminal recordings into shareable GIFs")
        [credential model] (host-credential)
        adapter  (sink/make-adapter)
        result
                 (escapement/run
                   {:chart         (embed-chart model)
                    :session-id    "embed-example"
                    :session-dir   (.getPath (io/file "demos/lib/.session"))
                    ;; REQUIRED for any chart with an :llm-conversation: the lib facade
                    ;; only registers the LLM invocation processor when BOTH a backend
                    ;; (assembled from :credentials) AND a :tool-registry are present.
                    ;; `tools/new-registry` is an empty registry (no built-in fs/shell
                    ;; tools); use `escapement.tools.builtin/new-builtin-registry` to
                    ;; also expose the built-ins, or pass your own.
                    :tool-registry (tools/new-registry)
                    :credentials   [credential]
                    :config        {:llm/preferences         [(assoc credential :model model)]
                                    :llm/eligibility-strict? false}
                    :initial-data  {:idea idea}
                    :transcript-tap
                    (fn [row]
                      (doseq [e (sink/feed! adapter row)]
                        (case (:type e)
                          ;; Stream only the second phase's tokens to stdout live.
                          :text-delta (when (= "pitch" (some-> (:invokeid e) name))
                                        (print (get-in e [:delta :text]))
                                        (flush))
                          :run-started (println "[run-id]" (:run-id e))
                          :run-done (println "\n[done]" (:config e))
                          nil)))})
        artifact #(let [f (io/file (:session-dir result) "artifacts" %)]
                    (when (.exists f) (str/trim (slurp f))))]
    (println "\n--- brief.md ---\n" (artifact "brief.md"))
    (println "\n--- pitch.md ---\n" (artifact "pitch.md"))
    (println "\nstatus      :" (:status result))
    (println "final-config:" (:final-config result))
    (println "transcript  :" (:transcript result))
    (shutdown-agents)
    (System/exit (if (and (= :done (:status result)) (artifact "pitch.md")) 0 1))))

;; ---------------------------------------------------------------------------
;; Vision variant (for reference) — feeding an image to a vision-capable
;; model. Gate the node with `:needs {:vision? true}` and pass a base64
;; image content block as an initial message. The block shape is the same
;; one documented in Guide.adoc ("The :llm-conversation invocation"):
;;
;;   (defn image-message [path]
;;     (let [b   (java.nio.file.Files/readAllBytes (.toPath (io/file path)))
;;           b64 (.encodeToString (java.util.Base64/getEncoder) b)]
;;       {:role :user
;;        :content [{:type :image
;;                   :source {:type :base64
;;                            :media-type "image/png"   ; jpeg|png|gif|webp
;;                            :data b64}}
;;                  {:type :text :text "Describe this screenshot."}]}))
;;
;;   ;; as flat keys on the h/llm-conversation opts map:
;;   {:id      "vision"
;;    :system  "You are a visual design analyst."
;;    :needs   {:vision? true}               ; eligibility gate (filters)
;;    :models  ["claude-sonnet-4-6"]         ; plural = explicit ordered
;;    :initial-messages [(image-message path)]}  ; fallback, no auto-substitution
;; ---------------------------------------------------------------------------
