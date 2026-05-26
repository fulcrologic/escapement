(ns escapement.chart.repl-service-test
  "Smoke tests for `escapement.chart.repl-service`. The discovery path is
  exercised via a pure parser test (no shell-out); the region composition
  test wires a mock backend + the in-process `:repl/eval` builtin and
  asserts the LLM gets the eval result back.

  Live nREPL tests would require a running external process; we skip
  cleanly when the relevant tools aren't available."
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final parallel state transition]]
    [escapement.chart.helpers :as h]
    [escapement.chart.repl-service :as repl-service]
    [escapement.engine.testing :as dct]
    [escapement.invocation.llm-conversation :as llmc]
    [escapement.llm.protocol :as llm]
    [escapement.test-support :as ts]
    [escapement.tools.builtin :as builtin]
    [fulcro-spec.core :refer [=> assertions specification]]
    [com.fulcrologic.statecharts.promise :as p]))

;; ---------------------------------------------------------------------------
;; Discovery parser — pure, no shell-out
;; ---------------------------------------------------------------------------

(specification "parse-discover-output filters shadow and matches project-dir"
  (let [out (str "  localhost:50643 (clj) - " (System/getProperty "user.dir") "\n"
              "  localhost:50644 (shadow) - " (System/getProperty "user.dir") "\n"
              "  localhost:50645 (clj) - /some/other/project\n")]
    (assertions
      "picks the clj port matching user.dir"
      (repl-service/parse-discover-output out (System/getProperty "user.dir")) => 50643
      "returns nil when no project matches"
      (repl-service/parse-discover-output out "/nowhere") => nil)))

;; ---------------------------------------------------------------------------
;; Region composition — mock LLM dispatches :repl/eval via the service
;; ---------------------------------------------------------------------------

(defrecord MockBackend [responses call-log]
  llm/LLMBackend
  (send-turn [_ request]
    (p/do!
      (swap! call-log conj request)
      (let [r (ts/pop-first! responses)]
        (when (nil? r)
          (throw (ex-info "Mock backend out of canned responses" {})))
        r))))

(defn- mock-backend [responses]
  (->MockBackend (ts/queue responses) (atom [])))

(defn- end-turn-response [text]
  {:stop-reason :end_turn
   :content     [{:type :text :text (or text "done")}]
   :usage       {:input-tokens 1 :output-tokens 1}
   :model       "mock"})

(defn- tool-use-response [tool-uses]
  {:stop-reason :tool_use
   :content     (mapv (fn [{:keys [id name input]}]
                        {:type :tool_use :id id :name name :input input})
                  tool-uses)
   :usage       {:input-tokens 1 :output-tokens 1}
   :model       "mock"})

(defn- new-env [{:keys [statechart backend tool-registry]}]
  (let [processor (llmc/new-processor {:backend       backend
                                       :tool-registry tool-registry
                                       :transcript-fn (fn [_] nil)})]
    (-> (dct/new-testing-env {:statechart    statechart
                              :tool-registry tool-registry}
          processor)
      (dct/start!))))

(defn- await-state!
  [t state-kw max-ms]
  (let [deadline (+ (System/currentTimeMillis) max-ms)]
    (loop []
      (dct/drain! t)
      (cond
        (dct/in? t state-kw) t
        (>= (System/currentTimeMillis) deadline) t
        :else (do (Thread/sleep 25) (recur))))))

(specification "repl-service-region: consumer LLM evaluates a form via the service"
  ;; The helper's default `:eval-fn` delegates to a `:repl/eval` builtin
  ;; tool that this project doesn't ship. Pass an inline `:eval-fn` so the
  ;; smoke test exercises the region wiring (palette → request → handler →
  ;; reply) without depending on a builtin that may or may not exist.
  (let [backend    (mock-backend
                     [(tool-use-response
                        [{:id "u1" :name "region__repl_eval" :input {:expr "(+ 2 3)"}}])
                      (end-turn-response "ok")])
        registry   (builtin/new-builtin-registry)
        eval-fn    (fn [_env {:keys [data]}]
                     ;; Trivial inline eval: just enough to prove the wire path.
                     (let [v (try (load-string (str (:expr data)))
                                  (catch Throwable t (str "ERR: " (.getMessage t))))]
                       {:result (str v) :is-error false}))
        chart      (chart/statechart
                     {:initial :run}
                     (state {:id :run :initial :work}
                       (parallel {:id :work}
                         (state {:id :consumer :initial :running}
                           (state {:id :running}
                             (h/llm-conversation
                               {:id          "coder"
                                :chart-tools [{:owner :repl-mgr}]
                                :message     "go"})
                             (transition {:event :llm.idle :target :consumer-done}))
                           (final {:id :consumer-done}))
                         (repl-service/repl-service-region {:id      :repl-mgr
                                                            :eval-fn eval-fn}))))
        t          (new-env {:statechart chart :backend backend :tool-registry registry})
        t          (await-state! t :consumer-done 5000)
        second-req (-> backend :call-log deref second)]
    (assertions
      "consumer region reached :consumer-done"
      (dct/in? t :consumer-done) => true
      "second request includes a tool_result with the eval value"
      (->> (:messages second-req)
        (mapcat :content)
        (some (fn [b]
                (and (= :tool_result (:type b))
                  (.contains (str (:content b)) "5")))))
      => true)))
