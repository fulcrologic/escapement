(ns escapement.examples.hello-test
  (:require
    [escapement.engine.testing :as dct]
    [escapement.examples.hello :as hello]
    [escapement.invocation.llm-conversation :as llmc]
    [escapement.llm.protocol :as llm]
    [escapement.test-support :as ts]
    [escapement.tools.protocol :as tp]
    [fulcro-spec.core :refer [=> assertions specification]]
    [com.fulcrologic.statecharts.promise :as p]))

(defrecord MockBackend [responses call-log]
  llm/LLMBackend
  (send-turn [_ request]
    (p/do!
      (swap! call-log conj request)
      (or (ts/pop-first! responses)
        (throw (ex-info "mock out of canned responses" {}))))))

(defn mock-backend [responses]
  (->MockBackend (ts/queue responses) (atom [])))

(defn- tool-use [tool-uses]
  {:stop-reason :tool_use
   :content     (mapv (fn [{:keys [id name input]}]
                        {:type :tool_use :id id :name name :input input})
                  tool-uses)
   :usage       {} :model "mock"})

(defn- end-turn []
  {:stop-reason :end_turn
   :content     [{:type :text :text "ok"}]
   :usage       {} :model "mock"})

(defn- await-config! [t state-kw max-ms]
  (let [deadline (+ (System/currentTimeMillis) max-ms)]
    (loop []
      (dct/drain! t)
      (cond
        (dct/in? t state-kw) t
        (>= (System/currentTimeMillis) deadline) t
        :else (do (Thread/sleep 25) (recur))))))

(specification "hello chart: LLM fires :done and chart reaches :finished"
  (let [backend (mock-backend
                  [(tool-use [{:id "u1" :name "event__done" :input {:greeting "Bonjour!"}}])
                   (end-turn)])
        proc    (llmc/new-processor {:backend backend :tool-registry (tp/new-registry)})
        t       (-> (dct/new-testing-env {:statechart hello/agent} proc)
                  (dct/start!))
        t       (await-config! t :finished 3000)]
    (assertions
      "chart reached :finished"
      (dct/in? t :finished) => true
      "data-model captured the greeting"
      (:greeting (dct/data t)) => "Bonjour!"
      "backend was called at least once"
      (pos? (count @(:call-log backend))) => true)))
