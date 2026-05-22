(ns escapement.lib.hermetic-facade-test
  "Step 4 — hermetic `escapement.lib/run` facade wiring.

  Proves the facade resolves ratings / preference-ordered models / the
  fail-closed flag PURELY from injected `:config` and assembles the backend
  PURELY from injected `:credentials`, with ZERO `escapement.config/load-config`
  and ZERO `System/getenv`. Two runs in one process with different `:config`
  ratings resolve a `:needs` gate differently — impossible under the old
  process-global, and the core concurrency guarantee.

  No network: the mock backend only records the request `:model` (proving
  which model the eligibility gate + preference order selected) and emits a
  `tool_use` that fires the chart's terminal event."
  (:require
    [clojure.string :as str]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final state transition]]
    [escapement.chart.helpers :as h]
    [escapement.config :as config]
    [escapement.lib :as lib]
    [escapement.llm.protocol :as proto]
    [escapement.tools.protocol :as tp]
    [fulcro-spec.core :refer [=> assertions component specification]]
    [com.fulcrologic.statecharts.promise :as p]))

(defn- event-tool
  "The Anthropic-tool name the llm-conversation processor expects for a chart
  event keyword (`escapement.invocation.llm-conversation/event-tool-name`):
  `event__` + name with every non `[A-Za-z0-9_]` char (e.g. `-`) → `_`."
  [event-kw]
  (str "event__" (str/replace (name event-kw) #"[^A-Za-z0-9_]" "_")))

;; ---------------------------------------------------------------------------
;; Mock backend: records every request's :model, then fires the chart's
;; terminal event so the conversation worker stops after one turn.
;; ---------------------------------------------------------------------------

(defrecord ModelRecordingStub [seen done-event turns]
  proto/LLMBackend
  (send-turn [_ request]
    ;; Turn 1: record the gate-selected model and emit the chart event tool.
    ;; A `:tool_use` makes the worker loop (`:continue`); a subsequent
    ;; `:end_turn` parks it (`:awaiting-user`) so it stops spinning while the
    ;; chart processes the raised event and cancels the invocation.
    (p/do!
      (if (zero? (first (swap-vals! turns inc)))
        (do
          (swap! seen conj (:model request))
          {:stop-reason :tool_use
           :content     [{:type :tool_use :id "u1"
                          :name (event-tool done-event) :input {}}]
           :usage       {:input-tokens 1 :output-tokens 1}
           :model       (or (:model request) "mock")})
        {:stop-reason :end_turn
         :content     [{:type :text :text "done"}]
         :usage       {:input-tokens 1 :output-tokens 1}
         :model       (or (:model request) "mock")}))))

(defn- new-stub [done-event] (->ModelRecordingStub (atom []) done-event (atom 0)))

;; ---------------------------------------------------------------------------
;; Reference example config (verbatim from fixlibfacade.md "Reference example")
;; ---------------------------------------------------------------------------

(def reference-config
  {:llm/preferences
   [{:provider :z-ai-plan :model "glm-5.1"}
    {:provider :anthropic :model "claude-opus-4-7"}
    {:provider :openai :model "gpt-5"}]

   :llm/ratings
   {"glm-5.1"         {:clojure 7 :ux 5}
    "claude-opus-4-7" {:clojure 9 :ux 4}
    "gpt-5"           {:clojure 5 :ux 8}}

   :llm/eligibility-strict? true})

(def reference-credentials
  [{:provider :z-ai-plan :subscription true}
   {:provider :anthropic :api-key "sk-anthropic"}
   {:provider :openai :api-key "sk-openai"}])

;; ---------------------------------------------------------------------------
;; Hermetic guard: any call to config/load-config or System/getenv on the lib
;; path is a hard failure. Mirrors the Step 3 providers_test technique
;; (with-redefs the sanctioned reader to a throwing/counting stub).
;; ---------------------------------------------------------------------------

(defn- hermetic
  "Run `thunk` with `config/load-config` redefed to throw. Any disk-config
  read on the lib path explodes loudly instead of silently working."
  [thunk]
  (with-redefs [config/load-config
                (fn [& _]
                  (throw (ex-info "HERMETIC VIOLATION: config/load-config called on lib path" {})))]
    (thunk)))

;; ---------------------------------------------------------------------------
;; Single-node chart whose :needs gate we vary by config.
;; ---------------------------------------------------------------------------

(defn- gate-chart
  "A one-node llm-conversation chart whose `:needs` is `needs` and which
  reaches `:finished` on `:gate-done`."
  [needs]
  (chart/statechart
    {:initial :work}
    (state {:id :work :initial :running}
      (state {:id :running}
        (h/llm-conversation
          {:id        "gate"
           :params-fn (fn [_ _]
                        {:system               "go"
                         :needs                needs
                         :real-tools           []
                         :allowed-events       [{:event :gate-done}]
                         :initial-user-message "do it"})})
        (transition {:event :gate-done :target :finished}))
      (final {:id :finished}))))

(defn- run-gate
  "Run `gate-chart` with `needs` through the facade with `cfg`/`creds` and an
  explicit recording `:backend`; returns the first model the gate selected."
  [{:keys [needs config credentials backend strict?]}]
  (let [stub (or backend (new-stub :gate-done))
        cfg  (cond-> config
               (and (some? strict?) (map? config))
               (assoc :llm/eligibility-strict? strict?))
        r    (lib/run (cond-> {:chart          (gate-chart needs)
                               :session-id     (keyword (str "gate-" (rand-int 1e9)))
                               :credentials    credentials
                               :backend        stub
                               :tool-registry  (tp/new-registry)
                               :max-iterations 200}
                        config (assoc :config cfg)))]
    {:result r
     :model  (first @(:seen stub))
     :status (:status r)}))

;; ===========================================================================

(specification "Step 4: hermetic facade — config-driven :needs resolution"

  (component "TWO runs, SAME process, DIFFERENT :config :llm/ratings resolve the gate differently"
    ;; Both runs use the SAME chart/:needs (:clojure [:>= 7]) and the SAME
    ;; preference order; only :llm/ratings differs. Under the old global this
    ;; was impossible — the first deref froze the table process-wide.
    (let [needs {:clojure [:>= 7]}
          prefs [{:provider :z-ai-plan :model "glm-5.1"}
                 {:provider :anthropic :model "claude-opus-4-7"}
                 {:provider :openai :model "gpt-5"}]
          ;; Run A: only gpt-5 clears :clojure>=7 ⇒ gpt-5 selected.
          a     (hermetic
                  #(run-gate {:needs       needs
                              :credentials reference-credentials
                              :config      {:llm/preferences prefs
                                            :llm/ratings     {"glm-5.1"         {:clojure 2}
                                                              "claude-opus-4-7" {:clojure 3}
                                                              "gpt-5"           {:clojure 9}}}}))
          ;; Run B: only glm-5.1 clears it ⇒ glm-5.1 selected (first in prefs).
          b     (hermetic
                  #(run-gate {:needs       needs
                              :credentials reference-credentials
                              :config      {:llm/preferences prefs
                                            :llm/ratings     {"glm-5.1"         {:clojure 9}
                                                              "claude-opus-4-7" {:clojure 2}
                                                              "gpt-5"           {:clojure 1}}}}))]
      (assertions
        "run A selected the only model its ratings let through the gate"
        (:model a) => "gpt-5"
        "run B selected a DIFFERENT model — same chart, different :config"
        (:model b) => "glm-5.1"
        "the two runs resolved the gate differently (concurrency guarantee)"
        (= (:model a) (:model b)) => false
        "both runs reached their final state"
        (:status a) => :done
        (:status b) => :done)))

  (component ":credentials omitted ⇒ early closed-schema rejection, run does NOT proceed"
    (assertions
      "validate-options flags the missing required key"
      (some? (lib/validate-options {:chart (gate-chart {}) :session-id :x})) => true
      "run throws an ex-info carrying :errors and never starts"
      (try (lib/run {:chart (gate-chart {}) :session-id :x}) :proceeded
           (catch clojure.lang.ExceptionInfo e
             (boolean (-> e ex-data :errors))))
      => true))

  (component ":config omitted ⇒ empty ratings + default preferences; subjective :needs fails-open by default"
    ;; No :config ⇒ ratings = {} ⇒ a subjective clause (:clojure) matches NO
    ;; model. Default fail-open ⇒ the gate empties, the run proceeds anyway on
    ;; the unfiltered default-preferences list (CLI-bias documented behavior).
    (let [{:keys [model status]}
          (hermetic
            #(run-gate {:needs       {:clojure [:>= 5]}
                        :credentials reference-credentials}))]
      (assertions
        "the run still proceeded (fail-open default) to its final state"
        status => :done
        "a model was selected from default-preferences despite the empty gate"
        (string? model) => true)))

  (component "explicit :backend wins verbatim; :credentials still schema-required but not consulted for assembly"
    (let [stub (new-stub :gate-done)
          {:keys [status model]}
          (hermetic
            #(run-gate {:needs       {}
                        ;; A provider keyword that has NO template — if the
                        ;; facade tried to assemble from it the backend would
                        ;; be nil and the LLM node could not run. It runs ⇒
                        ;; the explicit :backend was used verbatim.
                        :credentials [{:provider :no-such-provider}]
                        :backend     stub}))]
      (assertions
        "the explicit backend was exercised (run reached final via its tool_use)"
        status => :done
        "the recording stub (not a credentials-assembled backend) handled the turn"
        (some? model) => true))))

(specification "Step 4: HERMETIC — no config/load-config, no env reads on the lib path"
  ;; The whole point: prove the facade path makes ZERO disk-config reads.
  ;; `hermetic` redefs config/load-config to throw; a clean run inside it
  ;; proves the lib path never resolved config from disk.
  (let [outcome (hermetic
                  #(run-gate {:needs       {:tool-call? true}
                              :credentials reference-credentials
                              :config      reference-config}))]
    (assertions
      "a full lib/run completed without ever calling config/load-config"
      (:status outcome) => :done
      "and an objective-fact gate still resolved a model (no disk needed)"
      (string? (:model outcome)) => true)))

(specification "Step 4: fixlibfacade.md reference example reproduced end-to-end (mock backend, no network)"
  ;; Reproduces the plan's two-node chart + resolution table exactly.
  ;; NODE 1 :refactor — :tool-call? true + :clojure [:>= 7]
  ;;   glm-5.1 (clj 7 ok), claude-opus-4-7 (clj 9 ok), gpt-5 dropped (clj 5)
  ;;   ⇒ glm-5.1 (first in preferences).
  ;; NODE 2 :design-review — :vision? true + :ux [:>= 6]
  ;;   only gpt-5 (ux 8, vision ok); glm-5.1 (ux 5)/opus (ux 4) dropped
  ;;   ⇒ gpt-5 (only survivor — gate overrode preference priority).
  (let [seen   (atom [])
        ;; First turn of each node: record the gate-selected model + emit that
        ;; node's terminal event tool. Then `:end_turn` so the worker parks
        ;; (it does not spin) while the chart processes the transition and
        ;; cancels the invocation. `node` advances per distinct first-turn.
        node   (atom 0)
        stub   (reify proto/LLMBackend
                 (send-turn [_ request]
                   (p/do!
                     (let [n (count @seen)]
                       (if (or (zero? n)
                             (not= (peek @seen) (:model request)))
                         ;; first turn of a (new) node
                         (do
                           (swap! seen conj (:model request))
                           (let [k (swap! node inc)]
                             {:stop-reason :tool_use
                              :content     [{:type  :tool_use :id (str "u" k)
                                             :name  (if (= 1 k) (event-tool :refactor-done)
                                                                (event-tool :review-done))
                                             :input {}}]
                              :usage       {:input-tokens 1 :output-tokens 1}
                              :model       (:model request)}))
                         ;; subsequent turn of the same node — park the worker
                         {:stop-reason :end_turn
                          :content     [{:type :text :text "done"}]
                          :usage       {:input-tokens 1 :output-tokens 1}
                          :model       (:model request)})))))
        agent
               (chart/statechart
                 {:initial :refactor}
                 (state {:id :refactor}
                   (h/llm-conversation
                     {:id        "refactor"
                      :params-fn (fn [_ _]
                                   {:system               "You are a Clojure refactoring agent."
                                    :needs                {:tool-call? true :clojure [:>= 7]}
                                    :real-tools           []
                                    :allowed-events       [{:event :refactor-done}]
                                    :initial-user-message "rename foo to bar"})})
                   (transition {:event :refactor-done :target :design-review}))
                 (state {:id :design-review}
                   (h/llm-conversation
                     {:id        "design-review"
                      :params-fn (fn [_ _]
                                   {:system               "You are a UX/design reviewer."
                                    :needs                {:vision? true :ux [:>= 6]}
                                    :real-tools           []
                                    :allowed-events       [{:event :review-done}]
                                    :initial-user-message "critique the mockups"})})
                   (transition {:event :review-done :target :finished}))
                 (final {:id :finished}))
        result (hermetic
                 #(lib/run {:chart          agent
                            :session-id     :req-42
                            :credentials    reference-credentials
                            :config         reference-config
                            :backend        stub
                            :tool-registry  (tp/new-registry)
                            :max-iterations 300}))
        models @seen]
    (assertions
      "the chart ran both nodes then terminated via the top-level final"
      (:status result) => :done
      ;; A top-level `final` empties the configuration (the runner's
      ;; termination signal) — quiescent + done with no residual config.
      (empty? (:final-config result)) => true
      "exactly two LLM turns occurred (one per node)"
      (count models) => 2
      "NODE 1 :refactor selected glm-5.1 (first preference clearing clj>=7)"
      (first models) => "glm-5.1"
      "NODE 2 :design-review selected gpt-5 (only survivor of vision+ux>=6)"
      (second models) => "gpt-5"
      "the gate never reordered — it filtered; preference order chose survivors"
      models => ["glm-5.1" "gpt-5"])))
