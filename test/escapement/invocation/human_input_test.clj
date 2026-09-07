(ns escapement.invocation.human-input-test
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :refer [final script state transition]]
    [escapement.chart.helpers :as h]
    [escapement.engine.testing :as dct]
    [escapement.invocation.human-input :as hi]
    [fulcro-spec.core :refer [=> assertions component specification]]))

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
  :human.cancelled it transitions to :errored.

  `params` is the flat human-input opts map (e.g. `{:kind :text :prompt \"?\"}`)."
  [params]
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :ask}
      (state {:id :ask}
        (h/human-input (assoc params :id "ask"))
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
        c (ask-chart {:kind :text :prompt "name?"})
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
        c (ask-chart {:kind    :select :prompt "pick"
                      :options [{:label "A" :value :a}
                                {:label "B" :value :b}]})
        t (await-config! (new-env-with-renderer c r) :done 2000)]
    (assertions
      (dct/in? t :done) => true
      (:captured (dct/data t)) => :b)))

(specification ":multi-select kind"
  (let [r (stub {:multi [:x :z]})
        c (ask-chart {:kind    :multi-select :prompt "any"
                      :options [{:label "X" :value :x}
                                {:label "Y" :value :y}
                                {:label "Z" :value :z}]})
        t (await-config! (new-env-with-renderer c r) :done 2000)]
    (assertions
      (dct/in? t :done) => true
      (:captured (dct/data t)) => [:x :z])))

(specification ":confirm kind"
  (let [r (stub {:confirm true})
        c (ask-chart {:kind :confirm :prompt "ok?"})
        t (await-config! (new-env-with-renderer c r) :done 2000)]
    (assertions
      (dct/in? t :done) => true
      (:captured (dct/data t)) => true)))

(specification "schema validation routes to :error.human.invalid-answer"
  ;; Stub returns a number when schema demands a non-empty string.
  (let [r (stub {:text ""})
        c (ask-chart {:kind          :text
                      :prompt        "name?"
                      :answer-schema [:string {:min 1}]})
        t (await-config! (new-env-with-renderer c r) :errored 2000)]
    (assertions
      "validation failure posts :error.human.invalid-answer → :errored"
      (dct/in? t :errored) => true)))

(specification ":custom kind"
  (let [r (stub {})
        c (ask-chart {:kind   :custom
                      :render (fn [_ _] 42)})
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
                  {:id              "ask"
                   :kind            :text
                   :prompt          "?"
                   :on-answer-event :greet})
                (transition {:event :greet :target :done}))
              (final {:id :done})))
        t (await-config! (new-env-with-renderer c r) :done 2000)]
    (assertions
      (dct/in? t :done) => true)))

;; ---------------------------------------------------------------------------
;; :invokeid on every failure-path transcript event
;; ---------------------------------------------------------------------------

(defrecord ThrowingRenderer [throw-fn]
  hi/HumanRenderer
  (prompt-text [_ _] (throw (throw-fn)))
  (prompt-select [_ _] (throw (throw-fn)))
  (prompt-multi [_ _] (throw (throw-fn)))
  (prompt-confirm [_ _] (throw (throw-fn)))
  (start-progress [_ _] (atom {}))
  (update-progress [_ _ _ _] nil)
  (end-progress [_ _] nil)
  (custom-render [_ _ _ _] (throw (throw-fn))))

(defrecord BlockingRenderer []
  ;; Parks in the prompt until the worker thread is interrupted — the shape of
  ;; a real modal waiting on a human who never answers.
  hi/HumanRenderer
  (prompt-text [_ _] (Thread/sleep 60000) "never")
  (prompt-select [_ _] (Thread/sleep 60000) nil)
  (prompt-multi [_ _] (Thread/sleep 60000) nil)
  (prompt-confirm [_ _] (Thread/sleep 60000) nil)
  (start-progress [_ _] (atom {}))
  (update-progress [_ _ _ _] nil)
  (end-progress [_ _] nil)
  (custom-render [_ _ _ _] (Thread/sleep 60000) nil))

(defn- new-env-with-transcript
  "Like `new-env-with-renderer`, but also taps the processor's transcript into
   `captured` (an atom of a vector)."
  [chart renderer captured]
  (let [processor (hi/new-processor {:renderer      renderer
                                     :transcript-fn (fn [ev] (swap! captured conj ev))})]
    (-> (dct/new-testing-env {:statechart chart} processor)
      (dct/start!))))

(defn- await-event!
  "Pump the chart until a transcript event named `event-kw` shows up in
   `captured` (the worker posts from its own thread) or `max-ms` elapses.
   Returns the first such event, or nil."
  [t captured event-kw max-ms]
  (let [deadline (+ (System/currentTimeMillis) max-ms)]
    (loop []
      (dct/drain! t)
      (if-let [ev (first (filter #(= event-kw (:event %)) @captured))]
        ev
        (when (< (System/currentTimeMillis) deadline)
          (Thread/sleep 25)
          (recur))))))

(specification "every human-input failure event carries its :invokeid"
  ;; A host tap attributes an event to its invocation by :invokeid. The happy
  ;; path (:human-input/start / :human-input/answer) carried one; the four
  ;; failure events did not, so a tap filtering on :invokeid saw a prompt
  ;; open and then nothing — the cancel, the interrupt, the rejected answer
  ;; and the crash were all unattributable.

  (component "cancelled: the renderer rejects with {:reason :cancelled}"
    ;; The documented cancel contract (tui.clj / remote_renderer.clj): a
    ;; renderer that throws ex-data {:reason :cancelled} is a user cancel,
    ;; not a failure — the worker posts :human.cancelled and records
    ;; :human-input/cancelled.
    (let [captured (atom [])
          r        (->ThrowingRenderer #(ex-info "User cancelled the prompt" {:reason :cancelled}))
          c        (ask-chart {:kind :text :prompt "name?"})
          t        (new-env-with-transcript c r captured)
          ev       (await-event! t captured :human-input/cancelled 2000)]
      (assertions
        "the cancel event was recorded"
        (some? ev) => true
        "and it names the invocation it belongs to"
        (get-in ev [:data :invokeid]) => "ask"
        "the chart saw the cancel as :human.cancelled → :errored"
        (dct/in? (await-config! t :errored 2000) :errored) => true)))

  (component "interrupted: the invocation is stopped while the prompt is open"
    ;; Exiting the owning state stops the invocation, which interrupts the
    ;; parked worker thread; the InterruptedException path records
    ;; :human-input/interrupted.
    (let [captured (atom [])
          r        (->BlockingRenderer)
          c        (chart/statechart
                     {:initial :run}
                     (state {:id :run :initial :ask}
                       (state {:id :ask}
                         (h/human-input {:id "ask" :kind :text :prompt "?"})
                         (transition {:event :abort :target :aborted}))
                       (final {:id :aborted})))
          t        (new-env-with-transcript c r captured)
          _        (await-event! t captured :human-input/start 2000)
          _        (dct/run-events! t :abort)
          ev       (await-event! t captured :human-input/interrupted 2000)]
      (assertions
        "the prompt had opened before the stop"
        (some? (first (filter #(= :human-input/start (:event %)) @captured))) => true
        "the interrupt event was recorded"
        (some? ev) => true
        "and it names the invocation it belongs to"
        (get-in ev [:data :invokeid]) => "ask")))

  (component "validation-failed: the answer does not satisfy :answer-schema"
    (let [captured (atom [])
          r        (stub {:text ""})
          c        (ask-chart {:kind          :text
                               :prompt        "name?"
                               :answer-schema [:string {:min 1}]})
          t        (new-env-with-transcript c r captured)
          ev       (await-event! t captured :human-input/validation-failed 2000)]
      (assertions
        "the validation event was recorded"
        (some? ev) => true
        "it carries the humanized errors"
        (string? (get-in ev [:data :errors])) => true
        "and it names the invocation it belongs to"
        (get-in ev [:data :invokeid]) => "ask")))

  (component "error: the renderer throws"
    (let [captured (atom [])
          r        (->ThrowingRenderer #(ex-info "renderer exploded" {:code 42}))
          c        (ask-chart {:kind :text :prompt "name?"})
          t        (new-env-with-transcript c r captured)
          ev       (await-event! t captured :human-input/error 2000)]
      (assertions
        "the error event was recorded"
        (some? ev) => true
        "with the throwable's message"
        (get-in ev [:data :message]) => "renderer exploded"
        "and it names the invocation it belongs to"
        (get-in ev [:data :invokeid]) => "ask"
        "the chart saw :error.human.worker-exception → :errored"
        (dct/in? (await-config! t :errored 2000) :errored) => true)))

  (component "no failure event in any of those runs is left unattributable"
    ;; The general invariant over every human-input event, not only the four
    ;; named above.
    (let [captured (atom [])
          r        (->ThrowingRenderer #(ex-info "renderer exploded" {}))
          c        (ask-chart {:kind :text :prompt "name?"})
          t        (new-env-with-transcript c r captured)
          _        (await-event! t captured :human-input/error 2000)
          orphans  (->> @captured
                     (remove #(get-in % [:data :invokeid]))
                     (mapv :event))]
      (assertions
        "every emitted event is attributable to the invocation"
        orphans => []
        "and the run did emit something, so this is not vacuous"
        (boolean (seq @captured)) => true))))
