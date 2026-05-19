(ns escapement.chart.deferred-reply-test
  "Tests for the deferred-reply primitive: event-tool entries with
  `:awaits` defer their `tool_result` until the chart fires a matching
  reply event via `escapement.chart.helpers/complete-call` (alias for
  `escapement.chart.deferred-reply/complete-call`)."
  (:require
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :refer [state transition final script on-entry]]
   [com.fulcrologic.statecharts.environment :as env-ns]
   [com.fulcrologic.statecharts.protocols :as sp]
   [escapement.chart.deferred-reply :as deferred-reply]
   [escapement.chart.helpers :as h]
   [escapement.engine.testing :as dct]
   [escapement.invocation.llm-conversation :as llmc]
   [escapement.test-support-llm :refer [mock-backend end-turn-response tool-use-response]]
   [escapement.tools.protocol :as tp]
   [fulcro-spec.core :refer [specification assertions =>]]))

(defn- new-llm-test-env [{:keys [statechart backend tool-registry]}]
  (let [processor (llmc/new-processor {:backend       backend
                                       :tool-registry (or tool-registry (tp/new-registry))})]
    (-> (dct/new-testing-env {:statechart statechart} processor)
        (dct/start!))))

(defn- await-config! [t state-kw max-ms]
  (let [deadline (+ (System/currentTimeMillis) max-ms)]
    (loop []
      (dct/drain! t)
      (cond
        (dct/in? t state-kw) t
        (>= (System/currentTimeMillis) deadline) t
        :else (do (Thread/sleep 25) (recur))))))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- text-blocks
  "All `:text` content from user messages on a request (the worker collapses
   each tool_result into a `:tool_result` content block, but plain text user
   messages — like `tell-llm` deliveries — appear as `:text`)."
  [request]
  (->> (:messages request)
       (filter #(= :user (:role %)))
       (mapcat :content)))

(defn- tool-result-block
  "Find the tool_result block in `request` for the given `tool-use-id`."
  [request tool-use-id]
  (->> (text-blocks request)
       (some (fn [b] (when (and (= :tool_result (:type b))
                                (= tool-use-id (:tool_use_id b))) b)))))

;; ---------------------------------------------------------------------------
;; Specification 1: happy path — :awaits defers until matching reply fires
;; ---------------------------------------------------------------------------

(specification ":awaits event-tool defers tool_result until a matching reply event fires"
  ;; Flow: LLM calls event__ask_peer; chart fires :ask-peer; a transition
  ;; on :ask-peer immediately fires :peer/yes (script); transition on
  ;; :peer/yes runs (h/complete-call); worker sees the reply, completes the
  ;; tool_use, and the next LLM turn carries the tool_result.
               (let [backend (mock-backend
                              [(tool-use-response [{:id "u1" :name "event__ask_peer"
                                                    :input {:question "ready?"}}])
                               (end-turn-response "done")])
                     chart   (chart/statechart
                              {:initial :wrap}
                              (state {:id :wrap :initial :running}
                                     (state {:id :running}
                                            (h/llm-conversation
                                             {:id        "main"
                                              :params-fn (fn [_ _]
                                                           {:allowed-events
                                                            [{:event       :ask-peer
                                                              :data-schema [:map [:question :string]]
                                                              :awaits      {:on         #{:peer/yes :peer/no}
                                                                            :timeout-ms 2000}}]
                                                            :initial-user-message "begin"})})
                                            (transition {:event :ask-peer :type :internal}
                                                        (script
                                                         {:expr (fn [env _]
                                                                  (sp/send! (::sc/event-queue env) env
                                                                            {:target            (env-ns/session-id env)
                                                                             :source-session-id (env-ns/session-id env)
                                                                             :event             :peer/yes
                                                                             :data              {:verdict "approved"}})
                                                                  nil)}))
                                            (transition {:event :peer/yes :type :internal}
                                                        (h/complete-call))
                                            (transition {:event :llm.idle :target :done}))
                                     (final {:id :done})))
                     t       (new-llm-test-env {:statechart chart :backend backend})
                     t       (await-config! t :done 5000)
                     calls   @(:call-log backend)
                     turn-2  (second calls)
                     tr      (tool-result-block turn-2 "u1")]
                 (assertions
                  "chart reached :done"
                  (dct/in? t :done) => true
                  "backend received exactly 2 turns"
                  (count calls) => 2
                  "turn 2 user content carries the tool_result for u1"
                  (some? tr) => true
                  "tool_result is NOT an error (no :error-events declared)"
                  (boolean (:is-error tr)) => false
                  "tool_result content is pr-str of the reply event's :data"
                  (:content tr) => "{:verdict \"approved\"}")))

;; ---------------------------------------------------------------------------
;; Specification 2: :error-events flips is-error on the tool_result
;; ---------------------------------------------------------------------------

(specification ":error-events flips is-error on the tool_result"
  ;; Same shape as #1, but the chart fires :peer/no instead, and the awaits
  ;; declaration marks :peer/no as an error event.
               (let [backend (mock-backend
                              [(tool-use-response [{:id "u1" :name "event__ask_peer"
                                                    :input {:question "ready?"}}])
                               (end-turn-response "done")])
                     chart   (chart/statechart
                              {:initial :wrap}
                              (state {:id :wrap :initial :running}
                                     (state {:id :running}
                                            (h/llm-conversation
                                             {:id        "main"
                                              :params-fn (fn [_ _]
                                                           {:allowed-events
                                                            [{:event       :ask-peer
                                                              :data-schema [:map [:question :string]]
                                                              :awaits      {:on           #{:peer/yes :peer/no}
                                                                            :error-events #{:peer/no}
                                                                            :timeout-ms   2000}}]
                                                            :initial-user-message "begin"})})
                                            (transition {:event :ask-peer :type :internal}
                                                        (script
                                                         {:expr (fn [env _]
                                                                  (sp/send! (::sc/event-queue env) env
                                                                            {:target            (env-ns/session-id env)
                                                                             :source-session-id (env-ns/session-id env)
                                                                             :event             :peer/no
                                                                             :data              {:reason "not ready"}})
                                                                  nil)}))
                                            (transition {:event :peer/no :type :internal}
                                                        (h/complete-call))
                                            (transition {:event :llm.idle :target :done}))
                                     (final {:id :done})))
                     t       (new-llm-test-env {:statechart chart :backend backend})
                     t       (await-config! t :done 5000)
                     calls   @(:call-log backend)
                     tr      (tool-result-block (second calls) "u1")]
                 (assertions
                  "tool_result is flagged as an error"
                  (boolean (:is-error tr)) => true
                  "tool_result content carries the reply event's :data"
                  (:content tr) => "{:reason \"not ready\"}")))

;; ---------------------------------------------------------------------------
;; Specification 3: timeout produces is-error tool_result
;; ---------------------------------------------------------------------------

(specification ":awaits timeout produces an is-error tool_result"
  ;; No transition fires a reply, so the worker waits out the 200ms timeout.
               (let [backend (mock-backend
                              [(tool-use-response [{:id "u1" :name "event__ask_peer"
                                                    :input {:question "?"}}])
                               (end-turn-response "ok")])
                     chart   (chart/statechart
                              {:initial :wrap}
                              (state {:id :wrap :initial :running}
                                     (state {:id :running}
                                            (h/llm-conversation
                                             {:id        "main"
                                              :params-fn (fn [_ _]
                                                           {:allowed-events
                                                            [{:event       :ask-peer
                                                              :data-schema [:map [:question :string]]
                                                              :awaits      {:on         #{:peer/yes}
                                                                            :timeout-ms 200}}]
                                                            :initial-user-message "go"})})
                                            (transition {:event :llm.idle :target :done}))
                                     (final {:id :done})))
                     t       (new-llm-test-env {:statechart chart :backend backend})
                     t       (await-config! t :done 5000)
                     calls   @(:call-log backend)
                     tr      (tool-result-block (second calls) "u1")]
                 (assertions
                  "tool_result is an error"
                  (boolean (:is-error tr)) => true
                  "tool_result message mentions the timeout"
                  (boolean (re-find #"timed out after 200ms" (or (:content tr) ""))) => true)))

;; ---------------------------------------------------------------------------
;; Specification 4: in-flight slot is cleared after a reply
;; ---------------------------------------------------------------------------

(specification "peer-RPC slot is cleared once a reply lands"
  ;; After a happy-path RPC, ::in-flight should be empty so a future
  ;; mis-correlated reply can't sneak in.
               (let [backend (mock-backend
                              [(tool-use-response [{:id "u1" :name "event__ask_peer"
                                                    :input {:question "?"}}])
                               (end-turn-response "ok")])
                     chart   (chart/statechart
                              {:initial :wrap}
                              (state {:id :wrap :initial :running}
                                     (state {:id :running}
                                            (h/llm-conversation
                                             {:id        "main"
                                              :params-fn (fn [_ _]
                                                           {:allowed-events
                                                            [{:event       :ask-peer
                                                              :data-schema [:map [:question :string]]
                                                              :awaits      {:on         #{:peer/yes}
                                                                            :timeout-ms 2000}}]
                                                            :initial-user-message "go"})})
                                            (transition {:event :ask-peer :type :internal}
                                                        (script
                                                         {:expr (fn [env _]
                                                                  (sp/send! (::sc/event-queue env) env
                                                                            {:target            (env-ns/session-id env)
                                                                             :source-session-id (env-ns/session-id env)
                                                                             :event             :peer/yes
                                                                             :data              {:ok true}})
                                                                  nil)}))
                                            (transition {:event :peer/yes :type :internal}
                                                        (h/complete-call))
                                            (transition {:event :llm.idle :target :done}))
                                     (final {:id :done})))
                     t       (new-llm-test-env {:statechart chart :backend backend})
                     t       (await-config! t :done 5000)
                     atm     (deferred-reply/in-flight (:env t))]
                 (assertions
                  "in-flight atom exists on env"
                  (some? atm) => true
                  "after RPC completes, in-flight is empty"
                  @atm => {})))

;; ---------------------------------------------------------------------------
;; Direct unit tests on pop-slot! (the private impl) — exercising cleanup
;; invariants without going through chart machinery. Uses `#'` to bypass
;; the privacy guard; these tests are exercising an implementation detail.
;; ---------------------------------------------------------------------------

(def ^:private pop-slot! #'escapement.chart.deferred-reply/pop-slot!)

(defn- env-with-in-flight
  "Helper: build a minimal env-like map wrapping an in-flight atom seeded
   with the given map. Mirrors the shape escapement.engine.env/new-env
   produces, but doesn't drag in the full env (we only need the atom)."
  [in-flight-map]
  {:escapement.chart.deferred-reply/in-flight (atom in-flight-map)})

(specification "pop-slot! returns nil and leaves the atom alone when no slot matches"
               (let [env (env-with-in-flight {:peer/yes [{:reply-id "tr_keep" :reply-to "x"}]})
                     before @(deferred-reply/in-flight env)
                     popped (pop-slot! env :peer/no)
                     after @(deferred-reply/in-flight env)]
                 (assertions
                  "returns nil"
                  popped => nil
                  "atom unchanged"
                  after => before)))

(specification "pop-slot! removes the popped reply-id from EVERY :on key"
  ;; A request with :on #{:tester/passed :tester/failed} populates BOTH keys
  ;; with the same reply-id. Popping for :tester/passed must also clear
  ;; the stale :tester/failed entry, so a future failed reply can't
  ;; accidentally complete a different already-answered request.
               (let [slot {:reply-id            "tr_abc"
                           :reply-to            "experimenter"
                           :requesting-event-kw :new-version
                           :on                  #{:tester/passed :tester/failed}}
                     env  (env-with-in-flight
                           {:tester/passed [slot]
                            :tester/failed [slot]})
                     popped (pop-slot! env :tester/passed)
                     after  @(deferred-reply/in-flight env)]
                 (assertions
                  "returns the popped slot"
                  (:reply-id popped) => "tr_abc"
                  "ALL keys for that reply-id are cleared"
                  after => {})))

(specification "pop-slot! falls back to the :escapement.tool/any wildcard"
  ;; When the worker's :awaits :on set was empty, the slot was registered
  ;; under :escapement.tool/any. pop-slot! must consult that fallback so
  ;; any event-kw can answer.
               (let [slot {:reply-id "tr_xyz" :reply-to "main"}
                     env  (env-with-in-flight
                           {:escapement.tool/any [slot]})
                     popped (pop-slot! env :anything-at-all)
                     after  @(deferred-reply/in-flight env)]
                 (assertions
                  "the wildcard slot is popped"
                  (:reply-id popped) => "tr_xyz"
                  "wildcard key is cleared"
                  after => {})))

(specification "pop-slot! pops FIFO when multiple slots share an event-kw"
  ;; Two concurrent requests both await :peer/yes. Popping pops the older one.
               (let [slot-a {:reply-id "tr_a" :reply-to "first"}
                     slot-b {:reply-id "tr_b" :reply-to "second"}
                     env    (env-with-in-flight {:peer/yes [slot-a slot-b]})
                     first-popped (pop-slot! env :peer/yes)
                     after-first  @(deferred-reply/in-flight env)
                     second-popped (pop-slot! env :peer/yes)]
                 (assertions
                  "first pop returns slot-a (FIFO)"
                  (:reply-id first-popped) => "tr_a"
                  "slot-b survives"
                  (get after-first :peer/yes) => [slot-b]
                  "second pop returns slot-b"
                  (:reply-id second-popped) => "tr_b")))

;; ---------------------------------------------------------------------------
;; complete-call with no in-flight slot is a silent no-op
;; ---------------------------------------------------------------------------

(specification "complete-call with no in-flight slot does not fire any reply event"
  ;; If the chart fires a transition with (h/complete-call) for an event
  ;; that has no matching in-flight slot (e.g. the worker timed out and
  ;; cleaned its slots, or the chart-author wired complete-call to an
  ;; event that isn't actually a deferred-reply trigger), the helper
  ;; must NOT post a spurious :escapement.tool/reply that could
  ;; mis-complete some future call. We assert no extra backend turn
  ;; runs and no tool_result with junk content makes it into the worker.
               (let [backend (mock-backend [(end-turn-response "all good")])
                     chart   (chart/statechart
                              {:initial :wrap}
                              (state {:id :wrap :initial :running}
                                     (state {:id :running}
                                            (h/llm-conversation
                                             {:id        "main"
                                              :params-fn (fn [_ _]
                                                           {:initial-user-message "go"})})
                                            ;; Self-fire :stray/event right at start and have
                                            ;; complete-call try to answer it. There's no
                                            ;; in-flight slot — should be a quiet no-op.
                                            (on-entry {}
                                                      (script
                                                       {:expr (fn [env _]
                                                                (sp/send! (::sc/event-queue env) env
                                                                          {:target            (env-ns/session-id env)
                                                                           :source-session-id (env-ns/session-id env)
                                                                           :event             :stray/event
                                                                           :data              {:noise true}})
                                                                nil)}))
                                            (transition {:event :stray/event :type :internal}
                                                        (h/complete-call))
                                            (transition {:event :llm.idle :target :done}))
                                     (final {:id :done})))
                     t       (new-llm-test-env {:statechart chart :backend backend})
                     t       (await-config! t :done 3000)]
                 (assertions
                  "chart reached :done normally"
                  (dct/in? t :done) => true
                  "backend was called exactly once (no extra turn from a spurious reply)"
                  (count @(:call-log backend)) => 1
                  "in-flight stayed empty throughout"
                  @(deferred-reply/in-flight (:env t)) => {})))
