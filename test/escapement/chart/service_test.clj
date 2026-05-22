(ns escapement.chart.service-test
  "Happy-path coverage for the region-tool service. Comprehensive case
  coverage is the bdd-tester's job (see plan §Verification 1-9); this file
  proves the API works end-to-end so subsequent agents have something to
  build on."
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final on-entry on-exit
                                                  parallel state
                                                  transition]]
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
;; Tiny mock LLMBackend (same shape as the llm-conversation tests).
;; ---------------------------------------------------------------------------

(defrecord MockBackend [responses call-log]
  llm/LLMBackend
  (send-turn [_ request]
    (p/do!
      (swap! call-log conj request)
      (let [r (ts/pop-first! responses)]
        (when (nil? r)
          (throw (ex-info "Mock backend out of canned responses" {:n-calls (count @call-log)})))
        r))))

(defn- mock-backend [responses]
  (->MockBackend (ts/queue responses) (atom [])))

(defn- end-turn-response [text]
  {:stop-reason :end_turn
   :content     [{:type :text :text (or text "done")}]
   :usage       {:input-tokens 1 :output-tokens 1}
   :model       "mock"})

(defn- tool-use-response
  [tool-uses]
  {:stop-reason :tool_use
   :content     (mapv (fn [{:keys [id name input]}]
                        {:type :tool_use :id id :name name :input input})
                  tool-uses)
   :usage       {:input-tokens 1 :output-tokens 1}
   :model       "mock"})

(defn- new-llm-test-env [{:keys [statechart backend]}]
  (let [processor (llmc/new-processor {:backend       backend
                                       :tool-registry (tp/new-registry)
                                       :transcript-fn (fn [_] nil)})]
    (-> (dct/new-testing-env {:statechart statechart} processor)
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

;; ---------------------------------------------------------------------------
;; #1 — happy path: request/reply correlation through a consumer LLM
;; ---------------------------------------------------------------------------

(specification "region-tool happy path: consumer LLM calls a region tool, gets the reply"
  (let [backend    (mock-backend
                     [(tool-use-response
                        [{:id "u1" :name "region__repl_eval" :input {:expr "(+ 1 2)"}}])
                      (end-turn-response "got 3")])
        chart      (chart/statechart
                     {:initial :run}
                     (state {:id :run :initial :work}
                       (parallel {:id :work}
                         ;; Consumer region.
                         (state {:id :consumer :initial :running}
                           (state {:id :running}
                             (h/llm-conversation
                               {:id        "coder"
                                :params-fn (fn [_ _]
                                             {:chart-tools          [{:owner :repl-A}]
                                              :initial-user-message "go"})})
                             (transition {:event :llm.idle :target :consumer-done}))
                           (final {:id :consumer-done}))
                         ;; Service region.
                         (state {:id :repl-A :initial :idle}
                           (on-entry {}
                             (service/register-tool!
                               {:tool         :repl/eval
                                :description  "Evaluate a Clojure form."
                                :input-schema [:map [:expr :string]]}))
                           (on-exit {} (service/unregister-tool! :repl/eval))
                           (state {:id :idle}
                             (h/handle-tool
                               :repl/eval
                               (fn [_env req]
                                 {:result   (str "evaluated " (get-in req [:data :expr]))
                                  :is-error false})))))
                       (transition {:event :done.state.work :target :finished})
                       (final {:id :finished})))
        t          (new-llm-test-env {:statechart chart :backend backend})
        t          (await-config! t :consumer-done 5000)
        ;; The second turn's messages should carry the tool_result with our reply.
        second-req (-> backend :call-log deref second)]
    (assertions
      "consumer region reached :consumer-done"
      (dct/in? t :consumer-done) => true
      "backend was called exactly twice (initial tool_use turn + post-reply turn)"
      (count @(:call-log backend)) => 2
      "second request's user message includes the region-tool reply"
      (->> (:messages second-req)
        (mapcat :content)
        (some (fn [b]
                (and (= :tool_result (:type b))
                  (.contains (str (:content b)) "evaluated (+ 1 2)")))))
      => true)))

;; ---------------------------------------------------------------------------
;; #2 — timeout: a service region with no handler in this state times out
;; ---------------------------------------------------------------------------

(specification "missing-handler timeout: LLM gets is-error tool_result"
  (let [backend    (mock-backend
                     [(tool-use-response
                        [{:id    "u1" :name "region__repl_eval"
                          :input {:expr "(+ 1 2)" :timeout-ms 200}}])
                      (end-turn-response "ok then")])
        chart      (chart/statechart
                     {:initial :run}
                     (state {:id :run :initial :work}
                       (parallel {:id :work}
                         (state {:id :consumer :initial :running}
                           (state {:id :running}
                             (h/llm-conversation
                               {:id        "coder"
                                :params-fn (fn [_ _]
                                             {:chart-tools          [{:owner :repl-A}]
                                              :initial-user-message "go"})})
                             (transition {:event :llm.idle :target :consumer-done}))
                           (final {:id :consumer-done}))
                         (state {:id :repl-A :initial :no-handler}
                           (on-entry {}
                             (service/register-tool!
                               {:tool         :repl/eval
                                :description  "Evaluate a Clojure form."
                                :input-schema [:map [:expr :string]]}))
                           ;; Substate exists, but installs no handler for :repl/eval.
                           (state {:id :no-handler})))
                       (transition {:event :done.state.work :target :finished})
                       (final {:id :finished})))
        t          (new-llm-test-env {:statechart chart :backend backend})
        t          (await-config! t :consumer-done 5000)
        second-req (-> backend :call-log deref second)]
    (assertions
      "consumer region reached :consumer-done"
      (dct/in? t :consumer-done) => true
      "second request carries an is-error tool_result mentioning 'timed out'"
      (->> (:messages second-req)
        (mapcat :content)
        (some (fn [b]
                (and (= :tool_result (:type b))
                  (true? (:is-error b))
                  (.contains (str (:content b)) "timed out")))))
      => true)))

;; ---------------------------------------------------------------------------
;; #3 — owner-tag routing: two siblings register the same tool, only one runs
;; ---------------------------------------------------------------------------

(defn- owner-routing-chart [a-calls b-calls]
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :work}
      (parallel {:id :work}
        (state {:id :consumer :initial :running}
          (state {:id :running}
            (h/llm-conversation
              {:id        "coder"
               :params-fn (fn [_ _]
                            {:chart-tools          [{:owner :repl-A}]
                             :initial-user-message "go"})})
            (transition {:event :llm.idle :target :consumer-done}))
          (final {:id :consumer-done}))
        (state {:id :repl-A :initial :idle-a}
          (on-entry {}
            (service/register-tool!
              {:tool         :repl/eval
               :description  "A's repl."
               :input-schema [:map [:expr :string]]}))
          (state {:id :idle-a}
            (h/handle-tool
              :repl/eval
              (fn [_env _req]
                (swap! a-calls inc)
                {:result "A handled" :is-error false}))))
        (state {:id :repl-B :initial :idle-b}
          (on-entry {}
            (service/register-tool!
              {:tool         :repl/eval
               :description  "B's repl."
               :input-schema [:map [:expr :string]]}))
          (state {:id :idle-b}
            (h/handle-tool
              :repl/eval
              (fn [_env _req]
                (swap! b-calls inc)
                {:result "B handled" :is-error false})))))
      (transition {:event :done.state.work :target :finished})
      (final {:id :finished}))))

(specification "owner-tag routing: consumer's :chart-tools selects ONE owner"
  (let [a-calls (atom 0)
        b-calls (atom 0)
        backend (mock-backend
                  [(tool-use-response
                     [{:id "u1" :name "region__repl_eval" :input {:expr "1"}}])
                   (end-turn-response "ok")])
        chart   (owner-routing-chart a-calls b-calls)
        t       (new-llm-test-env {:statechart chart :backend backend})
        t       (await-config! t :consumer-done 5000)]
    (assertions
      "A's handler ran exactly once"
      @a-calls => 1
      "B's handler did NOT run"
      @b-calls => 0
      "consumer region reached :consumer-done"
      (dct/in? t :consumer-done) => true)))

;; ---------------------------------------------------------------------------
;; #4 — palette-snapshot collision: two owners aliased to the same prefix
;; ---------------------------------------------------------------------------

(specification "palette collision: two owners with the same LLM-facing name throw at snapshot"
  ;; Both regions register :repl/eval and the consumer pulls both with NO
  ;; aliasing — the snapshot can't disambiguate "region__repl_eval". We
  ;; assert this against the private `region-tool-palette` directly so the
  ;; test doesn't have to swallow the throw from a worker thread.
  (let [registry-snapshot [{:tool :repl/eval :owner :repl-A :description "A" :input-schema [:map]}
                           {:tool :repl/eval :owner :repl-B :description "B" :input-schema [:map]}]]
    (assertions
      "the snapshot raises an :region-palette-collision ex-info"
      (try
        (#'llmc/region-tool-palette registry-snapshot
          [{:owner :repl-A} {:owner :repl-B}]
          30000)
        :no-throw
        (catch Exception e
          (:reason (ex-data e))))
      => :region-palette-collision)))

;; ---------------------------------------------------------------------------
;; #5 — same-microstep register-then-invoke is safe (phase ordering)
;; ---------------------------------------------------------------------------

(specification "phase ordering: an on-entry register-tool! is visible to a sibling invocation"
  ;; Both regions enter :work in the same microstep. The consumer's
  ;; llm-conversation invocation starts in phase 4 (start-invocations);
  ;; the service region's on-entry register-tool! runs in phase 3
  ;; (enter-states). The palette snapshot taken when start-invocations
  ;; runs MUST already include the registered tool.
  (let [backend    (mock-backend
                     [(tool-use-response
                        [{:id "u1" :name "region__repl_eval" :input {:expr "(+ 1 2)"}}])
                      (end-turn-response "ok")])
        chart      (chart/statechart
                     {:initial :run}
                     (state {:id :run :initial :work}
                       (parallel {:id :work}
                         (state {:id :consumer}
                           (h/llm-conversation
                             {:id        "coder"
                              :params-fn (fn [_ _]
                                           {:chart-tools          [{:owner :repl-A}]
                                            :initial-user-message "go"})}))
                         (state {:id :repl-A :initial :idle}
                           (on-entry {}
                             (service/register-tool!
                               {:tool         :repl/eval
                                :description  "Evaluate a Clojure form."
                                :input-schema [:map [:expr :string]]}))
                           (state {:id :idle}
                             (h/handle-tool
                               :repl/eval
                               (fn [_env req]
                                 {:result   (str "ok " (get-in req [:data :expr]))
                                  :is-error false})))))))
        t          (new-llm-test-env {:statechart chart :backend backend})
        _          (await-config! t nil 3000)
        ;; Force a final drain.
        _          (dct/drain! t)
        _          (Thread/sleep 250)
        _          (dct/drain! t)
        second-req (-> backend :call-log deref second)]
    (assertions
      "backend got at least 2 turns (proves the tool was in the palette)"
      (>= (count @(:call-log backend)) 2) => true
      "second request's tool_result includes the handler's reply"
      (->> (:messages second-req)
        (mapcat :content)
        (some (fn [b] (and (= :tool_result (:type b))
                        (.contains (str (:content b)) "ok (+ 1 2)")))))
      => true)))

;; ---------------------------------------------------------------------------
;; Direct-API unit tests for the registry helpers — fast, no charts.
;; ---------------------------------------------------------------------------

(defn- env-with-registry []
  {:com.fulcrologic.statecharts/event-queue nil
   ::service/registry                       (atom {})})

(specification "service/entries flattens the registry to a vector of entries"
  (let [env (env-with-registry)
        reg (::service/registry env)]
    (swap! reg assoc :foo/eval {:repl-A {:owner :repl-A :description "A" :input-schema [:map]}
                                :repl-B {:owner :repl-B :description "B" :input-schema [:map]}})
    (let [es (service/entries env)
          ts (set (mapv (juxt :tool :owner) es))]
      (assertions
        "two entries emerge, one per owner"
        (count es) => 2
        ts => #{[:foo/eval :repl-A] [:foo/eval :repl-B]}))))

(specification "service/prune-owners! removes only the named owners"
  (let [env (env-with-registry)
        reg (::service/registry env)]
    (swap! reg assoc :foo/eval {:repl-A {:owner :repl-A :description "A" :input-schema [:map]}
                                :repl-B {:owner :repl-B :description "B" :input-schema [:map]}})
    (service/prune-owners! env [:repl-A])
    (assertions
      "only :repl-B remains"
      (set (keys (:foo/eval @reg))) => #{:repl-B}))
  (let [env (env-with-registry)
        reg (::service/registry env)]
    (swap! reg assoc :foo/eval {:repl-A {:owner :repl-A :description "A" :input-schema [:map]}})
    (service/prune-owners! env [:repl-A])
    (assertions
      "pruning the last owner also removes the empty tool-kw entry"
      (contains? @reg :foo/eval) => false)))

(specification "assoc-implicit-timeout merges :timeout-ms into an open map schema"
  (assertions
    "appends :timeout-ms as optional"
    (#'llmc/assoc-implicit-timeout [:map [:expr :string]])
    => [:map [:expr :string] [:timeout-ms {:optional true} [:int {:min 1}]]]
    "accepts a schema with a properties map and preserves it"
    (#'llmc/assoc-implicit-timeout [:map {:description "p"} [:expr :string]])
    => [:map {:description "p"} [:expr :string]
        [:timeout-ms {:optional true} [:int {:min 1}]]]
    "closed maps are rejected with a clear ex-info"
    (try (#'llmc/assoc-implicit-timeout [:map {:closed true} [:expr :string]])
         :no-throw
         (catch Exception e (:reason (ex-data e))))
    => :closed-region-tool-schema))
