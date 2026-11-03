(ns escapement.llm.scripted-test
  "The scripted LLM backend answers AI steps from a script, end to end through
   `escapement.lib/run`: verdicts, event tools, text, real tools, failures,
   malformed answers, and a script that runs out (fails clearly, naming the step)."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.promise :as p]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :refer [script state transition]]
    [escapement.chart.helpers :as h]
    [escapement.lib :as lib]
    [escapement.llm.protocol :as llm]
    [escapement.llm.scripted :as scripted]
    [escapement.testing.tools :as pt]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(defn- save [k f]
  (script {:expr (fn [_ data] [(ops/assign k (f data))])}))

(defn- step-chart
  "One AI step `classify` with a verdict schema, an event tool and a real tool."
  []
  (chart/statechart {:initial :classify}
    (state {:id :classify}
      (h/llm-conversation
        {:id             "classify"
         :system         "triage"
         :message        "classify this"
         :real-tools     [:kb/search]
         :allowed-events [{:event :triage/spam :data-schema [:map [:reason :string]]}]
         :verdict-schema [:map [:category [:enum :billing :bug]]]})
      (transition {:event :llm.idle :target :classified}
        (save :verdict #(get-in % [:_event :data :verdict]))
        (save :text #(get-in % [:_event :data :text])))
      (transition {:event :triage/spam :target :spam}
        (save :reason #(get-in % [:_event :data :reason])))
      (transition {:event :error.llm :target :failed}
        (save :error #(get-in % [:_event :name]))))
    ;; Plain end states (not <final>), so the configuration stays inspectable.
    (state {:id :classified})
    (state {:id :spam})
    (state {:id :failed})))

(defn- run [backend & [reg]]
  (let [r (lib/run {:chart         (step-chart)
                    :session-id    (keyword (str "scripted-" (rand-int 1000000000)))
                    :credentials   []
                    :backend       backend
                    :tool-registry (or reg (pt/pretend-registry {:kb/search {:reply "3 hits"}}))})
        env (:env r)
        wm  (sp/get-working-memory (::sc/working-memory-store env) env (:session-id r))]
    (assoc r :data (sp/current-data (::sc/data-model env)
                     (assoc env ::sc/vwmem (volatile! wm) ::sc/context-element-id nil)))))

(defn- data [r] (:data r))

(specification "scripted backend: verdicts, events and text"
  (component "a verdict reply"
    (let [b (scripted/backend {:script {"classify" [{:verdict {:category :billing}}]}})
          r (run b)]
      (assertions
        "drives the chart through :llm.idle with the verdict"
        (:final-config r) => [:classified]
        (:verdict (data r)) => {:category :billing}
        "records the call under the step"
        (mapv :step (scripted/calls b)) => ["classify"]
        "leaves nothing unused"
        (scripted/remaining b) => {})))
  (component "an event-tool reply"
    (let [b (scripted/backend {:script {"classify" [{:event :triage/spam :data {:reason "promo"}}]}})
          r (run b)]
      (assertions
        "transitions as if the model chose the event"
        (:final-config r) => [:spam]
        (:reason (data r)) => "promo")))
  (component "a real tool, then a verdict"
    (let [reg (pt/pretend-registry {:kb/search {:reply "3 hits"}})
          b   (scripted/backend {:script {"classify" [{:tool :kb/search :input {:q "refund"}}
                                                       {:verdict {:category :bug}}]}})
          r   (run b reg)]
      (assertions
        "the tool is called through the registry"
        (mapv :tool (pt/calls reg)) => [:kb/search]
        "the second turn's verdict ends the step"
        (:final-config r) => [:classified]
        (:verdict (data r)) => {:category :bug}
        (count (scripted/calls b)) => 2))))

(specification "scripted backend: failures"
  (component "a categorized failure"
    (let [t0 (System/currentTimeMillis)
          b  (scripted/backend {:script {"classify" [{:fail :timeout}]}})
          r  (run b)]
      (assertions
        "reaches the step's error exit"
        (:final-config r) => [:failed]
        (:error (data r)) => :error.llm.timeout
        "retries of the same turn repeat the failure without waiting"
        (< (- (System/currentTimeMillis) t0) 5000) => true
        (scripted/divergences b) => [])))
  (component "a malformed answer to a verdict step"
    (let [b (scripted/backend {:script {"classify" [{:malformed true}]}})
          r (run b)]
      (assertions
        "fails the step through its error exit"
        (:final-config r) => [:failed]
        "and does not use up the script"
        (scripted/divergences b) => [])))
  (component "a script that runs out"
    (let [b (scripted/backend {:script {"classify" []}})
          r (run b)
          d (first (scripted/divergences b))]
      (assertions
        "fails clearly instead of hanging"
        (:final-config r) => [:failed]
        (:error (data r)) => :error.llm.backend
        "naming the step"
        (:step d) => "classify"
        (:reason d) => :exhausted)))
  (component "an event the step does not offer"
    (let [b (scripted/backend {:script {"classify" [{:event :nope/never}]}})
          r (run b)]
      (assertions
        (:final-config r) => [:failed]
        (:reason (first (scripted/divergences b))) => :not-offered))))

(specification "scripted backend: step selection"
  (let [b (scripted/backend {:script {"a" [{:text "A"}] "b" [{:text "B"}]}
                             :match  {"b" (fn [req] (= "sys-b" (:system req)))}})]
    (assertions
      "request metadata names the step"
      (:step (do (p/await! (llm/send-turn b (with-meta {:messages []} {:escapement/invokeid "a"})))
               (last (scripted/calls b)))) => "a"
      "a :match fn picks the step when there is no metadata"
      (:step (do (p/await! (llm/send-turn b {:system "sys-b" :messages []}))
               (last (scripted/calls b)))) => "b")))
