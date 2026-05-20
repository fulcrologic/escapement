(ns escapement.examples.scan-test
  (:require
    [clojure.string :as str]
    [escapement.engine.testing :as dct]
    [escapement.examples.scan :as scan]
    [escapement.invocation.llm-conversation :as llmc]
    [escapement.llm.protocol :as llm]
    [escapement.test-support :as ts]
    [escapement.tools.builtin :as builtin]
    [fulcro-spec.core :refer [=> assertions specification]]))

(defrecord MockBackend [responses call-log]
  llm/LLMBackend
  (send-turn [_ request]
    (swap! call-log conj request)
    (or (ts/pop-first! responses)
      (throw (ex-info "mock out of canned responses" {})))))

(defn mock-backend [responses]
  (->MockBackend (ts/queue responses) (atom [])))

(defn- tool-use [tool-uses]
  {:stop-reason :tool_use
   :content     (mapv (fn [{:keys [id name input]}]
                        {:type :tool_use :id id :name name :input input})
                  tool-uses)
   :usage       {} :model "mock"})

(defn- end-turn []
  {:stop-reason :end_turn :content [{:type :text :text "ok"}] :usage {} :model "mock"})

(defn- await-config! [t state-kw max-ms]
  (let [deadline (+ (System/currentTimeMillis) max-ms)]
    (loop []
      (dct/drain! t)
      (cond
        (dct/in? t state-kw) t
        (>= (System/currentTimeMillis) deadline) t
        :else (do (Thread/sleep 25) (recur))))))

(specification "scan chart: fan-out of :found-bug then :scan-complete reaches :finished"
  (let [backend (mock-backend
                  [(tool-use [{:id    "f1" :name "event__found_bug"
                               :input {:file "a.clj" :line 10 :summary "thing 1"}}
                              {:id    "f2" :name "event__found_bug"
                               :input {:file "b.clj" :line 20 :summary "thing 2"}}])
                   (tool-use [{:id    "c" :name "event__scan_complete"
                               :input {:total_findings 2}}])
                   (end-turn)])
        proc    (llmc/new-processor {:backend backend :tool-registry (builtin/new-builtin-registry)})
        t       (-> (dct/new-testing-env {:statechart scan/agent} proc)
                  (dct/start!))
        t       (await-config! t :finished 4000)
        d       (dct/data t)]
    (assertions
      "chart reached :finished"
      (dct/in? t :finished) => true
      "two findings were accumulated in order"
      (mapv :file (:findings d)) => ["a.clj" "b.clj"]
      "total-findings populated"
      (:total-findings d) => 2)))

(defn- write-tmp! [content]
  (let [f (java.io.File/createTempFile "scan-test" ".txt")]
    (spit f content)
    (.deleteOnExit f)
    (.getAbsolutePath f)))

(specification "scan chart: LLM uses real fs_read first; chart never sees :fs/read events"
  (let [path        (write-tmp! "interesting marker XYZZY")
        backend     (mock-backend
                      [(tool-use [{:id "r1" :name "fs_read" :input {:path path}}])
                       (tool-use [{:id    "f1" :name "event__found_bug"
                                   :input {:file path :line 1 :summary "saw marker"}}])
                       (tool-use [{:id    "c1" :name "event__scan_complete"
                                   :input {:total_findings 1}}])
                       (end-turn)])
        proc        (llmc/new-processor {:backend backend :tool-registry (builtin/new-builtin-registry)})
        t           (-> (dct/new-testing-env {:statechart scan/agent} proc)
                      (dct/start!))
        t           (await-config! t :finished 4000)
        d           (dct/data t)
        second-msgs (-> backend :call-log deref second :messages)]
    (assertions
      "chart reached :finished"
      (dct/in? t :finished) => true
      "exactly one finding"
      (count (:findings d)) => 1
      "second turn carries a tool_result echoing the file contents"
      (some (fn [m]
              (some (fn [b]
                      (and (= :tool_result (:type b))
                        (str/includes? (or (:content b) "") "XYZZY")))
                (:content m)))
        second-msgs) => true)))
