(ns escapement.chart.service-coverage-test
  "Comprehensive coverage gaps for region-tools (per plan §Verification):

    1. Timeout — LLM-overridden short timeout: no reply → is_error tool_result.
    2. Timeout — LLM-overridden short timeout WITH reply just inside deadline → success.
    3. Late-reply drop: handler delivers reply AFTER the worker timed out;
       the timeout tool_result still goes through, and the late reply fires
       a `:llm/region-tool-late-reply` transcript event.
    4. Snapshot semantics: tools registered AFTER start-invocation! returns
       are NOT in the conversation's palette.
    5. Service-substate routing: same `:tool` keyword, two handlers in two
       substates of one owner; the active substate picks the handler.
    6. Async slow-work pattern via `post-reply`: handler returns nil and
       another thread posts the deferred reply.
    7. Palette collision at start-invocation!: end-to-end exception when
       two undisambiguated owners collide on an LLM-facing tool name."
  (:require
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final on-entry parallel
                                                  state transition]]
    [escapement.chart.helpers :as h]
    [escapement.chart.service :as service]
    [escapement.engine.testing :as dct]
    [escapement.invocation.llm-conversation :as llmc]
    [escapement.llm.protocol :as llm]
    [escapement.test-support :as ts]
    [escapement.tools.protocol :as tp]
    [fulcro-spec.core :refer [=> assertions specification]]
    [com.fulcrologic.statecharts.promise :as p]))

;; ---------------------------------------------------------------------------
;; Mock backend + helpers (mirroring service_test.clj)
;; ---------------------------------------------------------------------------

(defrecord MockBackend [responses call-log]
  llm/LLMBackend
  (send-turn [_ request]
    (p/do!
      (swap! call-log conj request)
      (let [r (ts/pop-first! responses)]
        (when (nil? r)
          (throw (ex-info "Mock backend out of canned responses"
                   {:n-calls (count @call-log)})))
        r))))

(>defn mock-backend
  "Build a MockBackend seeded with `responses` (a vector of canned send-turn
   return maps). Used by tests to drive an LLM-conversation through a
   deterministic sequence of turns without any real network."
  [responses]
  [vector? => any?]
  (->MockBackend (ts/queue responses) (atom [])))

(>defn end-turn-response
  "Canned :end_turn response with an optional text block."
  [text]
  [[:maybe :string] => :map]
  {:stop-reason :end_turn
   :content     [{:type :text :text (or text "done")}]
   :usage       {:input-tokens 1 :output-tokens 1}
   :model       "mock"})

(>defn tool-use-response
  "Canned :tool_use response carrying the supplied tool_use blocks."
  [tool-uses]
  [vector? => :map]
  {:stop-reason :tool_use
   :content     (mapv (fn [{:keys [id name input]}]
                        {:type :tool_use :id id :name name :input input})
                  tool-uses)
   :usage       {:input-tokens 1 :output-tokens 1}
   :model       "mock"})

(defn- transcript-recorder
  "Returns `[transcript-fn events-atom]`. The fn appends every received event
   to the atom; tests can assert against the captured stream."
  []
  (let [a (atom [])]
    [(fn [ev] (swap! a conj ev)) a]))

(defn- new-llm-test-env
  [{:keys [statechart backend transcript-fn]}]
  (let [processor (llmc/new-processor {:backend       backend
                                       :tool-registry (tp/new-registry)
                                       :transcript-fn (or transcript-fn
                                                        (fn [_] nil))})]
    (-> (dct/new-testing-env {:statechart statechart} processor)
      (dct/start!))))

(defn- await-config!
  [t state-kw max-ms]
  (let [deadline (+ (System/currentTimeMillis) max-ms)]
    (loop []
      (dct/drain! t)
      (cond
        (and state-kw (dct/in? t state-kw)) t
        (>= (System/currentTimeMillis) deadline) t
        :else (do (Thread/sleep 25) (recur))))))

;; ---------------------------------------------------------------------------
;; #1 — Timeout: LLM-overridden short timeout, handler never replies
;; ---------------------------------------------------------------------------

(specification "region-tool timeout: short LLM-supplied :timeout-ms with no reply yields is-error"
  (let [backend    (mock-backend
                     [(tool-use-response
                        [{:id    "u1" :name "region__repl_eval"
                          :input {:expr "(loop)" :timeout-ms 200}}])
                      (end-turn-response "ok")])
        chart      (chart/statechart
                     {:initial :run}
                     (state {:id :run :initial :work}
                       (parallel {:id :work}
                         (state {:id :consumer :initial :running}
                           (state {:id :running}
                             (h/llm-conversation
                               {:id          "coder"
                                :chart-tools [{:owner :repl-A}]
                                :message     "go"})
                             (transition {:event :llm.idle :target :consumer-done}))
                           (final {:id :consumer-done}))
                         (state {:id :repl-A :initial :idle}
                           (on-entry {}
                             (service/register-tool!
                               {:tool         :repl/eval
                                :description  "eval"
                                :input-schema [:map [:expr :string]]}))
                           (state {:id :idle}
                             ;; Handler returns nil → never replies.
                             (h/handle-tool
                               :repl/eval
                               (fn [_env _req] nil)))))
                       (transition {:event :done.state.work :target :finished})
                       (final {:id :finished})))
        t          (new-llm-test-env {:statechart chart :backend backend})
        t          (await-config! t :consumer-done 3000)
        second-req (-> backend :call-log deref second)]
    (assertions
      "consumer region reached :consumer-done"
      (dct/in? t :consumer-done) => true
      "second request carries an is-error tool_result mentioning 'timed out after 200ms'"
      (->> (:messages second-req)
        (mapcat :content)
        (some (fn [b]
                (and (= :tool_result (:type b))
                  (true? (:is-error b))
                  (.contains (str (:content b)) "timed out after 200ms")))))
      => true)))

;; ---------------------------------------------------------------------------
;; #2 — Timeout: LLM-overridden timeout, handler replies inside the window
;; ---------------------------------------------------------------------------

(specification "region-tool timeout override: handler replies within LLM-supplied deadline"
  (let [backend    (mock-backend
                     [(tool-use-response
                        [{:id    "u1" :name "region__repl_eval"
                          :input {:expr "(+ 1 2)" :timeout-ms 1000}}])
                      (end-turn-response "ok")])
        chart      (chart/statechart
                     {:initial :run}
                     (state {:id :run :initial :work}
                       (parallel {:id :work}
                         (state {:id :consumer :initial :running}
                           (state {:id :running}
                             (h/llm-conversation
                               {:id          "coder"
                                :chart-tools [{:owner :repl-A}]
                                :message     "go"})
                             (transition {:event :llm.idle :target :consumer-done}))
                           (final {:id :consumer-done}))
                         (state {:id :repl-A :initial :idle}
                           (on-entry {}
                             (service/register-tool!
                               {:tool         :repl/eval
                                :description  "eval"
                                :input-schema [:map [:expr :string]]}))
                           (state {:id :idle}
                             (h/handle-tool
                               :repl/eval
                               (fn [_env req]
                                 ;; small sleep, well within the 1000ms budget
                                 (Thread/sleep 30)
                                 {:result   (str "ok " (get-in req [:data :expr]))
                                  :is-error false})))))
                       (transition {:event :done.state.work :target :finished})
                       (final {:id :finished})))
        t          (new-llm-test-env {:statechart chart :backend backend})
        t          (await-config! t :consumer-done 3000)
        second-req (-> backend :call-log deref second)]
    (assertions
      "consumer region reached :consumer-done"
      (dct/in? t :consumer-done) => true
      "second request carries the success tool_result with the eval text"
      (->> (:messages second-req)
        (mapcat :content)
        (some (fn [b]
                (and (= :tool_result (:type b))
                  (false? (:is-error b))
                  (.contains (str (:content b)) "ok (+ 1 2)")))))
      => true)))

;; ---------------------------------------------------------------------------
;; #3 — Late-reply drop and transcript event
;; ---------------------------------------------------------------------------

(specification "late-reply drop: a stale reply observed during a later poll fires `:llm/region-tool-late-reply`"
  ;; The late-reply transcript event is fired by `poll-reply-queue!` when
  ;; the queue yields a reply whose id doesn't match the current call's
  ;; reply-id. This requires the stale reply to land WHILE a *subsequent*
  ;; region-tool call is polling. We arrange two sequential calls; the
  ;; first times out (handler returns nil), the second succeeds — and the
  ;; first's deferred reply gets delivered during the second's poll loop.
  (let [[tfn events-a] (transcript-recorder)
        call1-reply-info (atom nil)
        backend          (mock-backend
                           [(tool-use-response
                              [{:id    "u1" :name "region__repl_eval"
                                :input {:expr "first" :timeout-ms 200}}])
                            ;; After the first call times out, the LLM
                            ;; retries with a fresh id and a generous deadline.
                            (tool-use-response
                              [{:id    "u2" :name "region__repl_eval"
                                :input {:expr "second" :timeout-ms 1500}}])
                            (end-turn-response "ok")])
        n-calls          (atom 0)
        chart            (chart/statechart
                           {:initial :run}
                           (state {:id :run :initial :work}
                             (parallel {:id :work}
                               (state {:id :consumer :initial :coder-running}
                                 (state {:id :coder-running}
                                   (h/llm-conversation
                                     {:id          "coder"
                                      :chart-tools [{:owner :repl-A}]
                                      :message     "go"})
                                   (transition {:event :llm.idle :target :consumer-done}))
                                 (final {:id :consumer-done}))
                               (state {:id :repl-A :initial :idle}
                                 (on-entry {}
                                   (service/register-tool!
                                     {:tool         :repl/eval
                                      :description  "eval"
                                      :input-schema [:map [:expr :string]]}))
                                 (state {:id :idle}
                                   (h/handle-tool
                                     :repl/eval
                                     (fn [env req]
                                       (let [n (swap! n-calls inc)]
                                         (cond
                                           (= n 1)
                                           ;; First call: capture ids; reply will be
                                           ;; posted AFTER the worker times out and
                                           ;; while a second call is polling.
                                           (do (reset! call1-reply-info
                                                 {:reply-id (:reply-id req)
                                                  :reply-to (:reply-to req)
                                                  :env      env})
                                               nil)
                                           :else
                                           ;; Second call: reply immediately AFTER
                                           ;; firing off the late reply for call 1.
                                           (let [{:keys [reply-id reply-to env]} @call1-reply-info]
                                             (when reply-id
                                               (service/post-reply env
                                                 {:reply-id reply-id
                                                  :reply-to reply-to
                                                  :result   "late-for-1"
                                                  :is-error false}))
                                             (Thread/sleep 50)
                                             {:result "ok-second" :is-error false}))))))))
                             (transition {:event :done.state.work :target :finished})
                             (final {:id :finished})))
        t                (new-llm-test-env {:statechart chart :backend backend :transcript-fn tfn})
        t                (await-config! t :consumer-done 3000)
        second-req       (-> backend :call-log deref second)
        third-req        (-> backend :call-log deref (nth 2 nil))]
    (assertions
      "consumer region reached :consumer-done"
      (dct/in? t :consumer-done) => true
      "worker emitted the timeout tool_result for the first (u1) call"
      (->> (:messages second-req)
        (mapcat :content)
        (some (fn [b]
                (and (= :tool_result (:type b))
                  (= "u1" (:tool_use_id b))
                  (true? (:is-error b))
                  (.contains (str (:content b)) "timed out after 200ms")))))
      => true
      "worker emitted a success tool_result for the second (u2) call"
      (->> (:messages third-req)
        (mapcat :content)
        (some (fn [b]
                (and (= :tool_result (:type b))
                  (= "u2" (:tool_use_id b))
                  (false? (:is-error b))
                  (.contains (str (:content b)) "ok-second")))))
      => true
      "transcript captured a `:llm/region-tool-late-reply` event for the stale id"
      (boolean (some #(= :llm/region-tool-late-reply (:event %)) @events-a))
      => true)))

;; ---------------------------------------------------------------------------
;; #4 — Snapshot semantics: post-start registration is NOT in the palette
;; ---------------------------------------------------------------------------

(specification "snapshot semantics: tools registered after start-invocation! are absent from the palette"
  ;; Plan §V6: the worker snapshots the palette at start-invocation! time;
  ;; tools added to the registry afterwards must NOT be callable.
  ;;
  ;; Setup: service region pre-registers :first on entry. Consumer pulls
  ;; owner :repl-A → palette snapshot contains exactly region__first.
  ;;
  ;; Sequence driven by the mock backend:
  ;;   1. LLM calls region__first → handler runs and, as a side effect,
  ;;      mutates the live registry atom to add :second under :repl-A.
  ;;   2. LLM calls region__second → if the worker re-read the live registry,
  ;;      this would dispatch; because it uses its snapshot, it must hit the
  ;;      "Unknown tool" branch.
  ;;   3. LLM ends the turn.
  (let [backend    (mock-backend
                     [(tool-use-response
                        [{:id "u1" :name "region__first" :input {}}])
                      (tool-use-response
                        [{:id "u2" :name "region__second" :input {}}])
                      (end-turn-response "ok")])
        chart      (chart/statechart
                     {:initial :run}
                     (state {:id :run :initial :work}
                       (parallel {:id :work}
                         (state {:id :consumer :initial :coder-running}
                           (state {:id :coder-running}
                             (h/llm-conversation
                               {:id          "coder"
                                :chart-tools [{:owner :repl-A}]
                                :message     "go"})
                             (transition {:event :llm.idle :target :consumer-done}))
                           (final {:id :consumer-done}))
                         (state {:id :repl-A :initial :idle}
                           (on-entry {}
                             (service/register-tool!
                               {:tool         :first
                                :description  "first"
                                :input-schema [:map]}))
                           (state {:id :idle}
                             (h/handle-tool
                               :first
                               (fn [env _req]
                                 ;; Late-register :second AFTER the consumer's palette
                                 ;; was snapshotted at start-invocation!.
                                 (swap! (service/registry env)
                                   assoc-in [:second :repl-A]
                                   {:owner        :repl-A
                                    :description  "second"
                                    :input-schema [:map]})
                                 {:result "first-ok" :is-error false})))))
                       (transition {:event :done.state.work :target :finished})
                       (final {:id :finished})))
        t          (new-llm-test-env {:statechart chart :backend backend})
        t          (await-config! t :consumer-done 3000)
        second-req (-> backend :call-log deref second)
        third-req  (-> backend :call-log deref (nth 2 nil))]
    (assertions
      "consumer reaches :consumer-done"
      (dct/in? t :consumer-done) => true
      "first tool call (in-palette) succeeded"
      (->> (:messages second-req)
        (mapcat :content)
        (some (fn [b]
                (and (= :tool_result (:type b))
                  (= "u1" (:tool_use_id b))
                  (false? (:is-error b))
                  (.contains (str (:content b)) "first-ok")))))
      => true
      "second tool call (late-registered) hits Unknown tool — proves the palette is a snapshot"
      (->> (:messages third-req)
        (mapcat :content)
        (some (fn [b]
                (and (= :tool_result (:type b))
                  (= "u2" (:tool_use_id b))
                  (true? (:is-error b))
                  (.contains (str (:content b)) "Unknown tool: region__second")))))
      => true)))

;; ---------------------------------------------------------------------------
;; #5 — Service-substate routing
;; ---------------------------------------------------------------------------

(defn- substate-routing-chart
  "Build a chart with a :repl-A region whose `:initial` substate is `start-substate`
   (either :idle or :repl-running). Both substates declare a `(handle :repl/eval ...)`;
   SCXML transition precedence means the active substate's handler wins."
  [start-substate eval-calls]
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :work}
      (parallel {:id :work}
        (state {:id :consumer :initial :coder-running}
          (state {:id :coder-running}
            (h/llm-conversation
              {:id          "coder"
               :chart-tools [{:owner :repl-A}]
               :message     "go"})
            (transition {:event :llm.idle :target :consumer-done}))
          (final {:id :consumer-done}))
        (state {:id :repl-A :initial start-substate}
          (on-entry {}
            (service/register-tool!
              {:tool         :repl/eval
               :description  "eval"
               :input-schema [:map [:expr :string]]}))
          ;; External-event substate flip used by Spec B below.
          ;; NB: must be `:type :internal`. SCXML §3.13 says the
          ;; LCCA for an external transition `:source :repl-A →
          ;; :target <child-of-:repl-A>` is `:repl-A`'s PARENT
          ;; (findLCCA excludes the source itself), so the
          ;; transition's exit set covers every active state under
          ;; that parent — including this parallel's sibling branch
          ;; running the consumer's llm-conversation invocation.
          ;; `:type :internal` keeps the exit set inside `:repl-A`.
          ;; See test/escapement/engine/parallel_external_transition_test.clj
          ;; for the conformance check.
          (transition {:event :repl/start :target :repl-running :type :internal})
          (state {:id :idle}
            (h/handle-tool
              :repl/eval
              (fn [_env _req]
                {:result "not running" :is-error true})))
          (state {:id :repl-running}
            (h/handle-tool
              :repl/eval
              (fn [_env req]
                (swap! eval-calls inc)
                {:result   (str "ran " (get-in req [:data :expr]))
                 :is-error false})))))
      (transition {:event :done.state.work :target :finished})
      (final {:id :finished}))))

(specification "service-substate routing: each substate's handler wins while active"
  ;; SCXML transition precedence: with both substates declaring a
  ;; `(handle :repl/eval ...)`, only the active substate's handler fires.
  ;; We prove this without race conditions by running two charts whose
  ;; only difference is the :repl-A region's `:initial` substate.
  (let [eval-calls-idle (atom 0)
        backend-idle    (mock-backend
                          [(tool-use-response
                             [{:id    "u1" :name "region__repl_eval"
                               :input {:expr "x"}}])
                           (end-turn-response "ok")])
        t-idle          (new-llm-test-env {:statechart (substate-routing-chart :idle eval-calls-idle)
                                           :backend    backend-idle})
        t-idle          (await-config! t-idle :consumer-done 3000)
        idle-second-req (-> backend-idle :call-log deref second)
        eval-calls-run  (atom 0)
        backend-run     (mock-backend
                          [(tool-use-response
                             [{:id    "u1" :name "region__repl_eval"
                               :input {:expr "x"}}])
                           (end-turn-response "ok")])
        t-run           (new-llm-test-env {:statechart (substate-routing-chart :repl-running eval-calls-run)
                                           :backend    backend-run})
        t-run           (await-config! t-run :consumer-done 3000)
        run-second-req  (-> backend-run :call-log deref second)]
    (assertions
      "[:idle initial] the :idle handler answered 'not running'"
      (boolean
        (->> (:messages idle-second-req)
          (mapcat :content)
          (some (fn [b]
                  (and (= :tool_result (:type b))
                    (true? (:is-error b))
                    (.contains (str (:content b)) "not running"))))))
      => true
      "[:idle initial] the :repl-running handler never ran"
      @eval-calls-idle => 0
      "[:repl-running initial] the :repl-running handler answered 'ran x'"
      (boolean
        (->> (:messages run-second-req)
          (mapcat :content)
          (some (fn [b]
                  (and (= :tool_result (:type b))
                    (false? (:is-error b))
                    (.contains (str (:content b)) "ran x"))))))
      => true
      "[:repl-running initial] the :idle handler never ran (its reply would be is-error)"
      (boolean
        (->> (:messages run-second-req)
          (mapcat :content)
          (some (fn [b]
                  (and (= :tool_result (:type b))
                    (.contains (str (:content b)) "not running"))))))
      => false
      "[:repl-running initial] the :repl-running handler ran exactly once"
      @eval-calls-run => 1)))

;; ---------------------------------------------------------------------------
;; #5b — Substate transition across an external event (full plan §V7 path)
;; ---------------------------------------------------------------------------

(specification "service substate transitions via an external event between LLM turns"
  ;; The plan calls for `:idle` → fire `:repl/eval`, get 'not running'; then
  ;; flip the region to `:running` and fire `:repl/eval` again, get the real
  ;; reply. We drive this by intercepting the LLM round-trip: after the
  ;; first response is consumed by the worker AND the chart has emitted the
  ;; "not running" tool_result on a subsequent backend call, the test thread
  ;; fires `:repl/start` directly. The test backend is intentionally serial
  ;; (mock-backend pops responses FIFO), so the second tool_use is only
  ;; emitted on the worker's NEXT turn — which lands after the substate flip.
  (let [eval-calls (atom 0)
        ;; A custom backend that lets the test thread gate the second response.
        gate       (atom :hold)
        responses  (atom [(tool-use-response
                            [{:id    "u1" :name "region__repl_eval"
                              :input {:expr "first"}}])
                          (tool-use-response
                            [{:id    "u2" :name "region__repl_eval"
                              :input {:expr "second"}}])
                          (end-turn-response "ok")])
        call-log   (atom [])
        backend    (reify llm/LLMBackend
                     (send-turn [_ request]
                       (p/do!
                         (swap! call-log conj request)
                         ;; The SECOND call is the one carrying u1's "not running" tool_result.
                         ;; Wait for the test to fire :repl/start before letting it through.
                         (when (= 2 (count @call-log))
                           (let [deadline (+ (System/currentTimeMillis) 2000)]
                             (loop []
                               (cond
                                 (= :open @gate) :ok
                                 (>= (System/currentTimeMillis) deadline) :timeout
                                 :else (do (Thread/sleep 10) (recur))))))
                         (let [[r & more] @responses]
                           (reset! responses (vec more))
                           (or r (throw (ex-info "out of responses" {})))))))
        chart      (substate-routing-chart :idle eval-calls)
        processor  (llmc/new-processor {:backend       backend
                                        :tool-registry (tp/new-registry)
                                        :transcript-fn (fn [_] nil)})
        t          (-> (dct/new-testing-env {:statechart chart} processor)
                     (dct/start!))
        ;; Wait until the worker has issued the request carrying u1's tool_result.
        _          (let [deadline (+ (System/currentTimeMillis) 2500)]
                     (loop []
                       (dct/drain! t)
                       (cond
                         (>= (count @call-log) 2) :ok
                         (>= (System/currentTimeMillis) deadline) :give-up
                         :else (do (Thread/sleep 10) (recur)))))
        ;; Flip the substate to :repl-running, then open the gate so the
        ;; backend returns u2 (which will land on :repl-running's handler).
        _          (dct/run-events! t :repl/start)
        _          (reset! gate :open)
        t          (await-config! t :consumer-done 3000)
        u2-req     (nth @call-log 2 nil)]
    (assertions
      "consumer reached :consumer-done"
      (dct/in? t :consumer-done) => true
      "the :repl-running handler ran exactly once (only for u2)"
      @eval-calls => 1
      "the third backend request carries the :repl-running success tool_result for u2"
      (boolean
        (->> (:messages u2-req)
          (mapcat :content)
          (some (fn [b]
                  (and (= :tool_result (:type b))
                    (= "u2" (:tool_use_id b))
                    (false? (:is-error b))
                    (.contains (str (:content b)) "ran second"))))))
      => true)))

;; ---------------------------------------------------------------------------
;; #6 — Async slow-work pattern via `post-reply`
;; ---------------------------------------------------------------------------

(specification "async slow-work: handler returns nil and posts the reply later via post-reply"
  (let [backend    (mock-backend
                     [(tool-use-response
                        [{:id    "u1" :name "region__repl_eval"
                          :input {:expr "slow" :timeout-ms 1500}}])
                      (end-turn-response "ok")])
        chart      (chart/statechart
                     {:initial :run}
                     (state {:id :run :initial :work}
                       (parallel {:id :work}
                         (state {:id :consumer :initial :running}
                           (state {:id :running}
                             (h/llm-conversation
                               {:id          "coder"
                                :chart-tools [{:owner :repl-A}]
                                :message     "go"})
                             (transition {:event :llm.idle :target :consumer-done}))
                           (final {:id :consumer-done}))
                         (state {:id :repl-A :initial :idle}
                           (on-entry {}
                             (service/register-tool!
                               {:tool         :repl/eval
                                :description  "eval"
                                :input-schema [:map [:expr :string]]}))
                           (state {:id :idle}
                             (h/handle-tool
                               :repl/eval
                               (fn [env req]
                                 ;; Kick off background work and reply later.
                                 (doto (Thread.
                                         ^Runnable
                                         (fn []
                                           (Thread/sleep 50)
                                           (service/post-reply
                                             env
                                             {:reply-id (:reply-id req)
                                              :reply-to (:reply-to req)
                                              :result   (str "async "
                                                          (get-in req [:data :expr]))
                                              :is-error false})))
                                   (.setDaemon true)
                                   (.start))
                                 nil)))))
                       (transition {:event :done.state.work :target :finished})
                       (final {:id :finished})))
        t          (new-llm-test-env {:statechart chart :backend backend})
        t          (await-config! t :consumer-done 3000)
        second-req (-> backend :call-log deref second)]
    (assertions
      "consumer reached :consumer-done"
      (dct/in? t :consumer-done) => true
      "the second turn carries the deferred async tool_result"
      (->> (:messages second-req)
        (mapcat :content)
        (some (fn [b]
                (and (= :tool_result (:type b))
                  (false? (:is-error b))
                  (.contains (str (:content b)) "async slow")))))
      => true)))

;; ---------------------------------------------------------------------------
;; #7 — Palette collision at start-invocation!: end-to-end exception
;; ---------------------------------------------------------------------------

(specification "palette collision at start-invocation!: collision error names both owners"
  ;; Two sibling service regions BOTH register :repl/eval, and the consumer
  ;; pulls BOTH with no `:as` aliasing. The palette snapshot built inside
  ;; `start-invocation!` must throw with `:region-palette-collision` —
  ;; verifying the implementer's claim that collision is detected at
  ;; palette-snapshot time, not later. The naming of both owners is
  ;; asserted on the ex-info data.
  (let [registry-snapshot [{:tool        :repl/eval :owner :repl-A
                            :description "A" :input-schema [:map]}
                           {:tool        :repl/eval :owner :repl-B
                            :description "B" :input-schema [:map]}]
        decls             [{:owner :repl-A} {:owner :repl-B}]
        thrown            (try
                            (#'llmc/region-tool-palette registry-snapshot decls 30000)
                            nil
                            (catch Exception e e))
        ed                (some-> thrown ex-data)]
    (assertions
      "a region-palette-collision is thrown"
      (:reason ed) => :region-palette-collision
      "ex-data names both colliding owners"
      (set (:owners ed)) => #{:repl-A :repl-B}
      "exception message names both owners by symbol"
      (and (.contains (.getMessage ^Exception thrown) ":repl-A")
        (.contains (.getMessage ^Exception thrown) ":repl-B"))
      => true)))
