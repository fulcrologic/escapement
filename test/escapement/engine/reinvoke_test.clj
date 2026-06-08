(ns escapement.engine.reinvoke-test
  "Behavior-pinning test for `escapement.engine.reinvoke` — the single seam that
   reaches into the statechart library's invocation internals. Pins, against the
   classpath RC, that the primitive starts EXACTLY the invocations of the
   invoking states present in a restored configuration (and is a no-op when
   there are none)."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final state transition]]
    [com.fulcrologic.statecharts.promise :as p]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.chart.helpers :as h]
    [escapement.engine.env :as engine-env]
    [escapement.engine.reinvoke :as reinvoke]
    [escapement.invocation.llm-conversation :as llm-conv]
    [escapement.llm.protocol :as llm]
    [escapement.test-support :as ts]
    [escapement.tools.protocol :as tp]
    [fulcro-spec.core :refer [=> assertions specification]]))

(defrecord MockBackend [responses]
  llm/LLMBackend
  (send-turn [_ _]
    (p/do! (or (ts/pop-first! responses)
             {:stop-reason :end_turn :content [{:type :text :text "ok"}]
              :usage {:input-tokens 1 :output-tokens 1} :model "mock"}))))

;; Two leaf states: `:talk` owns an llm-conversation invocation, `:idle` owns
;; none. A restored configuration may contain either/both.
(def ^:private chart-2
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :idle}
      (state {:id :idle}
        (transition {:event :go :target :talk}))
      (state {:id :talk}
        (h/llm-conversation {:id "writer" :message "hi"})
        (transition {:event :llm.idle :target :done}))
      (final {:id :done}))))

(defn- new-test-env []
  (engine-env/new-env {:checkpoint-dir "/tmp/reinvoke-pin"
                       :llm-backend    (->MockBackend (ts/queue []))
                       :tool-registry  (tp/new-registry)}))

(defn- llm-processor [env]
  (->> (::sc/invocation-processors env)
    (filter #(instance? escapement.invocation.llm_conversation.LlmConversationProcessor %))
    first))

(defn- wmem-with-config [config]
  {::sc/statechart-src ::reinvoke/chart
   ::sc/session-id     "pin-session"
   ::sc/configuration  (set config)})

(specification "reinvoke-active-invocations! pins library invocation semantics"
  (let [env  (new-test-env)
        reg  (::sc/statechart-registry env)
        _    (sp/register-statechart! reg ::reinvoke/chart chart-2)
        proc (llm-processor env)]

    (assertions
      "invoking-states-in-config returns ONLY states that own <invoke> elements"
      (reinvoke/invoking-states-in-config chart-2 (wmem-with-config #{:run :talk}))
      => #{:talk}
      "a config with no invoking state yields the empty set"
      (reinvoke/invoking-states-in-config chart-2 (wmem-with-config #{:run :idle}))
      => #{})

    ;; No-op path: no invoking state in the config ⇒ nothing started, wmem unchanged.
    (let [wmem (wmem-with-config #{:run :idle})
          out  (reinvoke/reinvoke-active-invocations! env wmem)]
      (assertions
        "no invoking state ⇒ returns the wmem unchanged (identical, no work)"
        (identical? out wmem) => true
        "no worker was started"
        (llm-conv/active-worker-count proc) => 0))

    ;; Invoking path: starts exactly one worker for :talk.
    (let [wmem (wmem-with-config #{:run :talk})
          _    (reinvoke/reinvoke-active-invocations! env wmem)]
      (assertions
        "exactly one live worker was started for the single invoking state"
        (llm-conv/active-worker-count proc) => 1))))
