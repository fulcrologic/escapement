(ns escapement.examples.parallel-demo-test
  (:require
   [escapement.examples.parallel-demo :as pd]
   [escapement.engine.testing :as dct]
   [escapement.invocation.llm-conversation :as llmc]
   [escapement.llm.protocol :as llm]
   [escapement.test-support :as ts]
   [escapement.tools.protocol :as tp]
   [fulcro-spec.core :refer [specification assertions =>]]))

;; A per-invocation backend dispatcher: distinct queues are picked by sniffing
;; which tool the assistant is expected to call next. The simplest reliable
;; approach is to look at which tools are in the request (each invocation has
;; its own tools list), and use that as the dispatch key.

(defrecord RoutingMockBackend [tool-name->responses call-log]
  llm/LLMBackend
  (send-turn [_ request]
    (swap! call-log conj request)
    (let [tool-names (set (map :name (:tools request)))
          k         (some #(when (contains? tool-names %) %) (keys tool-name->responses))
          q         (get tool-name->responses k)
          r         (when q (ts/pop-first! q))]
      (or r (throw (ex-info "mock out of canned responses"
                            {:tool-names tool-names :k k}))))))

(defn- routing-backend [m]
  (->RoutingMockBackend
   (into {} (map (fn [[k vs]] [k (ts/queue vs)])) m)
   (atom [])))

(defn- tool-use [tool-uses]
  {:stop-reason :tool_use
   :content     (mapv (fn [{:keys [id name input]}]
                        {:type :tool_use :id id :name name :input input})
                      tool-uses)
   :usage       {} :model "mock"})

(defn- end-turn []
  {:stop-reason :end_turn :content [{:type :text :text "ok"}] :usage {} :model "mock"})

(defn- await-config!
  "Poll-and-drain. Interleave short sleeps with drain calls so events posted by worker
   threads while the main thread is sleeping get pumped through the chart."
  ([t state-kw max-ms] (await-config! t state-kw max-ms nil))
  ([t state-kw max-ms _backend]
   (let [deadline (+ (System/currentTimeMillis) max-ms)]
     (loop [n 0]
       (Thread/sleep 30)
       (dct/drain! t)
       (cond
         (dct/in? t state-kw) t
         (>= (System/currentTimeMillis) deadline) t
         :else (recur (inc n)))))))

(specification "parallel-demo chart: two regions each complete via distinct event-tools"
               (let [backend (routing-backend
                              {"event__translated"
                               [(tool-use [{:id "t1" :name "event__translated"
                                            :input {:text "Bonjour le monde"}}])
                                (end-turn)]
                               "event__summarized"
                               [(tool-use [{:id "s1" :name "event__summarized"
                                            :input {:text "A short summary sentence."}}])
                                (end-turn)]})
                     proc    (llmc/new-processor {:backend backend :tool-registry (tp/new-registry)})
                     t       (-> (dct/new-testing-env {:statechart pd/agent} proc)
                                 (dct/start!))
                     t       (await-config! t :finished 10000 backend)
                     d       (dct/data t)
        ;; Distinct invocation-ids appear as distinct tool-name sets per request.
                     call-tool-sets (->> @(:call-log backend)
                                         (map (fn [r] (set (map :name (:tools r)))))
                                         set)]
                 (assertions
                  "chart reached :finished"
                  (dct/in? t :finished) => true
                  "translator populated"
                  (:translation d) => "Bonjour le monde"
                  "summarizer populated"
                  (:summary d) => "A short summary sentence."
                  "two distinct tool sets were used (proving no cross-region collision)"
                  (count call-tool-sets) => 2
                  "tool names contain both event tools, across distinct invocations"
                  (boolean (and (some #(contains? % "event__translated") call-tool-sets)
                                (some #(contains? % "event__summarized") call-tool-sets))) => true)))
