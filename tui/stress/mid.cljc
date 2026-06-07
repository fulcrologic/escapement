(ns stress.mid
  "TUI render stress — MID tier (moderate concurrency + structure).

   Exercises the renderer with a handful of things happening at once:
     * FOUR LLM conversations streaming CONCURRENTLY inside a parallel region
       (the inspector should show four live invocation rows; the live-token
       panel should split/aggregate four streams; tokens/sec aggregates).
     * Each worker is steered into a SECOND turn (multi-turn) via h/tell-llm,
       so each invocation's transcript grows past one reply.
     * A handful of small artifacts captured as the streams finish, plus two
       deterministic structured artifacts written up-front (so the artifacts
       view has mixed content even if the model misbehaves).

   This is the 'normal busy session' case: enough concurrency and structure to
   surface layout/clipping issues, but bounded and time-boxable.

   MODEL DEPENDENCY: the four streams are real LLM invocations, so a backend is
   required. A tiny local model is fine. With no API key set, run on ollama:

     OLLAMA_API_KEY=dummy OLLAMA_NUM_PARALLEL=4 bb -m escapement.cli run \\
       stress.mid/agent \\
       --backend ollama --api-base-url http://localhost:11434/v1 \\
       --model gemma3:1b --max-tokens 2048 --overrun-retries 2 \\
       --overrun-temp-bump 0.3

   Set OLLAMA_NUM_PARALLEL>=4 so the four workers genuinely stream at once.

   The deterministic artifacts and the chart's choreography do NOT depend on the
   model; only the streamed token content does."
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements
     :refer [final on-entry parallel script state transition]]
    [escapement.chart.helpers :as h]))

(def ^:private worker-system
  (str "You are a terse worker. When asked, write ONE short sentence (under 90 "
    "characters) on the given topic, then call `event__stress_mid_step` exactly "
    "once with `{\"line\":\"<your sentence>\"}` and end your turn. When asked to "
    "continue, write ONE more short sentence and call the tool again. Never call "
    "any other tool."))

(def ^:private topics
  (array-map
    :alpha "the colour blue"
    :beta  "a quiet morning"
    :gamma "a busy harbour"
    :delta "an old lighthouse"))

(defn- worker-region
  "Build one parallel region that runs a 2-turn LLM conversation on `topic`,
   captures its output, then finalises. `rid` is the region id (also the
   invokeid + artifact base name)."
  [rid topic]
  (let [conv-id  (name rid)
        working  (keyword (str conv-id "-working"))
        done     (keyword (str conv-id "-done"))
        step-ev  (keyword "stress-mid" (str conv-id "-step"))]
    (state {:id rid :initial working}
      (state {:id working}
        (h/llm-conversation
          {:id             conv-id
           :system         worker-system
           :allowed-events [{:event       step-ev
                             :data-schema [:map [:line :string]]}]
           :max-turns      4
           :budget-ms      90000
           :message        (str "Topic: " topic ". Write your first sentence.")})

        ;; First turn: bump a per-region counter, steer into a second turn.
        (transition {:event step-ev :type :internal
                     :cond  (fn [_env data]
                              (zero? (get-in data [:counts rid] 0)))}
          (script {:expr (fn [_env data]
                           [(ops/assign :counts
                              (assoc (or (:counts data) {}) rid 1))])})
          (h/tell-llm {:expr (fn [_env _data]
                               "Good. Now continue with one more sentence.")}))

        ;; Second turn: capture and finalise this region.
        (transition {:event step-ev :target done
                     :cond  (fn [_env data]
                              (pos? (get-in data [:counts rid] 0)))}
          (h/capture-llm-output {:as (str conv-id ".md")}))

        ;; Safety net: if the model emits :llm.idle without a second tool call,
        ;; capture and finish anyway so the region cannot wedge.
        (transition {:event :llm.idle :target done
                     :cond  (fn [_env data]
                              (pos? (get-in data [:counts rid] 0)))}
          (h/capture-llm-output {:as (str conv-id ".md")})))

      (final {:id done}))))

(def ^:private seed-artifacts-script
  "Write two deterministic, structured artifacts so the artifacts view always
   has mixed content regardless of model behaviour."
  (script
    {:expr
     (fn [env _data]
       (let [sdir (:escapement/session-dir env)
             tfn  (:escapement/transcript-fn env)]
         (when sdir
           (let [adir (str sdir "/artifacts")]
             (.mkdirs (java.io.File. adir))
             (let [files {"manifest.md"
                          (str "# Mid-tier session manifest\n\n"
                            "Four concurrent workers, two turns each.\n\n"
                            "| region | topic |\n|---|---|\n"
                            (apply str
                              (for [[k v] topics]
                                (str "| " (name k) " | " v " |\n"))))
                          "plan.json"
                          (str "{\n  \"regions\": ["
                            (clojure.string/join ", "
                              (map #(str "\"" (name %) "\"") (keys topics)))
                            "],\n  \"turns\": 2\n}\n")}]
               (doseq [[fname content] files]
                 (spit (str adir "/" fname) content)
                 (when tfn
                   (try (tfn {:event :artifact/captured
                              :data  {:name fname :bytes (count content)}})
                        (catch Throwable _ nil))))))))
       nil)}))

(def agent
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :work}

      (on-entry {} seed-artifacts-script)

      (parallel {:id :work}
        (worker-region :alpha (:alpha topics))
        (worker-region :beta  (:beta topics))
        (worker-region :gamma (:gamma topics))
        (worker-region :delta (:delta topics)))

      ;; When all four regions finalise, the parallel raises done.state.work.
      (transition {:event :done.state.work :target :finished})

      (final {:id :finished}))))
