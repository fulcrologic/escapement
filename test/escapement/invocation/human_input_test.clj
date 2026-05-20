(ns escapement.invocation.human-input-test
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :refer [final script state transition]]
    [escapement.chart.helpers :as h]
    [escapement.engine.testing :as dct]
    [escapement.invocation.human-input :as hi]
    [fulcro-spec.core :refer [=> assertions specification]]))

(defrecord StubRenderer [scripted call-log]
  hi/HumanRenderer
  (prompt-text [_ opts]
    (swap! call-log conj [:text opts])
    (:text @scripted))
  (prompt-select [_ opts]
    (swap! call-log conj [:select opts])
    (:select @scripted))
  (prompt-multi [_ opts]
    (swap! call-log conj [:multi opts])
    (:multi @scripted))
  (prompt-confirm [_ opts]
    (swap! call-log conj [:confirm opts])
    (:confirm @scripted))
  (start-progress [_ _] (atom {}))
  (update-progress [_ _ _ _] nil)
  (end-progress [_ _] nil)
  (custom-render [_ f env data]
    (swap! call-log conj [:custom])
    (f env data)))

(defn- stub [answers]
  (->StubRenderer (atom answers) (atom [])))

(defn- new-env-with-renderer [chart renderer]
  (let [processor (hi/new-processor {:renderer renderer})]
    (-> (dct/new-testing-env {:statechart chart} processor)
      (dct/start!))))

(defn- await-config!
  [t state-kw max-ms]
  (let [deadline (+ (System/currentTimeMillis) max-ms)]
    (loop []
      (dct/drain! t)
      (cond
        (dct/in? t state-kw) t
        (>= (System/currentTimeMillis) deadline) t
        :else (do (Thread/sleep 25) (recur))))))

(defn- ask-chart
  "Build a 3-state chart that invokes :human-input in :ask, captures the answer
  into :captured on :human.answer, and ends in :done. On :error.human.* or
  :human.cancelled it transitions to :errored."
  [params-fn]
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :ask}
      (state {:id :ask}
        (h/human-input {:id "ask" :params-fn params-fn})
        (transition {:event :human.answer :target :done}
          (script {:expr (fn [_ data]
                           [(ops/assign :captured
                              (get-in data [:_event :data :answer]))])}))
        (transition {:event :human.cancelled :target :errored})
        (transition {:event :error.human.* :target :errored}))
      (final {:id :done})
      (final {:id :errored}))))

(specification ":text kind round-trips answer to :human.answer"
  (let [r (stub {:text "alice"})
        c (ask-chart (fn [_ _] {:kind :text :prompt "name?"}))
        t (new-env-with-renderer c r)
        t (await-config! t :done 2000)]
    (assertions
      "chart reached :done"
      (dct/in? t :done) => true
      "answer landed in data model"
      (:captured (dct/data t)) => "alice"
      "renderer saw exactly one :text call"
      (mapv first @(:call-log r)) => [:text])))

(specification ":select kind"
  (let [r (stub {:select :b})
        c (ask-chart (fn [_ _] {:kind    :select :prompt "pick"
                                :options [{:label "A" :value :a}
                                          {:label "B" :value :b}]}))
        t (await-config! (new-env-with-renderer c r) :done 2000)]
    (assertions
      (dct/in? t :done) => true
      (:captured (dct/data t)) => :b)))

(specification ":multi-select kind"
  (let [r (stub {:multi [:x :z]})
        c (ask-chart (fn [_ _] {:kind    :multi-select :prompt "any"
                                :options [{:label "X" :value :x}
                                          {:label "Y" :value :y}
                                          {:label "Z" :value :z}]}))
        t (await-config! (new-env-with-renderer c r) :done 2000)]
    (assertions
      (dct/in? t :done) => true
      (:captured (dct/data t)) => [:x :z])))

(specification ":confirm kind"
  (let [r (stub {:confirm true})
        c (ask-chart (fn [_ _] {:kind :confirm :prompt "ok?"}))
        t (await-config! (new-env-with-renderer c r) :done 2000)]
    (assertions
      (dct/in? t :done) => true
      (:captured (dct/data t)) => true)))

(specification "schema validation routes to :error.human.invalid-answer"
  ;; Stub returns a number when schema demands a non-empty string.
  (let [r (stub {:text ""})
        c (ask-chart (fn [_ _] {:kind          :text
                                :prompt        "name?"
                                :answer-schema [:string {:min 1}]}))
        t (await-config! (new-env-with-renderer c r) :errored 2000)]
    (assertions
      "validation failure posts :error.human.invalid-answer → :errored"
      (dct/in? t :errored) => true)))

(specification ":custom kind"
  (let [r (stub {})
        c (ask-chart (fn [_ _] {:kind   :custom
                                :render (fn [_ _] 42)}))
        t (await-config! (new-env-with-renderer c r) :done 2000)]
    (assertions
      (dct/in? t :done) => true
      (:captured (dct/data t)) => 42)))

(specification "custom :on-answer-event is honored"
  (let [r (stub {:text "hi"})
        c (chart/statechart
            {:initial :run}
            (state {:id :run :initial :ask}
              (state {:id :ask}
                (h/human-input
                  {:id        "ask"
                   :params-fn (fn [_ _]
                                {:kind            :text :prompt "?"
                                 :on-answer-event :greet})})
                (transition {:event :greet :target :done}))
              (final {:id :done})))
        t (await-config! (new-env-with-renderer c r) :done 2000)]
    (assertions
      (dct/in? t :done) => true)))
