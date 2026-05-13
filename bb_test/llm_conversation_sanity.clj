(ns llm-conversation-sanity
  "bb sanity smoke for the :llm-conversation InvocationProcessor.

  Spawns a chart with a mocked LLMBackend that returns one tool_use (an event-tool)
  followed by end_turn. Verifies the chart receives the event and reaches :done."
  (:require
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :refer [state transition final]]
   [com.fulcrologic.statecharts.protocols :as sp]
   [deep-cookie.chart.helpers :as h]
   [deep-cookie.engine.env :as env]
   [deep-cookie.invocation.llm-conversation :as llmc]
   [deep-cookie.llm.protocol :as llm]
   [deep-cookie.tools.protocol :as tp]))

(defrecord MockBackend [responses]
  llm/LLMBackend
  (send-turn [_ _request]
    (let [r (first @responses)]
      (swap! responses rest)
      r)))

(def end-turn
  {:stop-reason :end_turn
   :content     [{:type :text :text "ok"}]
   :usage       {}
   :model       "mock"})

(def tool-use
  {:stop-reason :tool_use
   :content     [{:type :tool_use :id "u1" :name "event__ok" :input {:msg "hi"}}]
   :usage       {}
   :model       "mock"})

(defn pump-all! [env sid]
  (let [queue     (::sc/event-queue env)
        store     (::sc/working-memory-store env)
        processor (::sc/processor env)]
    (loop [i 30]
      (when (pos? i)
        (let [progressed? (atom false)]
          (sp/receive-events! queue env
                              (fn [_ event]
                                (reset! progressed? true)
                                (let [w  (sp/get-working-memory store env sid)
                                      w' (sp/process-event! processor env w event)]
                                  (sp/save-working-memory! store env sid w')))
                              {})
          (Thread/sleep 50)
          (when @progressed? (recur (dec i))))))))

(defn -main [& _]
  (println "=== bb sanity: :llm-conversation ===")
  (let [backend  (->MockBackend (atom [tool-use end-turn]))
        proc     (llmc/new-processor {:backend backend :tool-registry (tp/new-registry)})
        chart-id ::sanity
        scchart  (chart/statechart {:initial :wrap}
                                   (state {:id :wrap :initial :work}
                                          (state {:id :work}
                                                 (h/llm-conversation
                                                  {:id        "main"
                                                   :params-fn (fn [_ _]
                                                                {:allowed-events       [{:event :ok :data-schema [:map [:msg :string]]}]
                                                                 :initial-user-message "go"})})
                                                 (transition {:event :ok :target :done}))
                                          (final {:id :done})))
        env      (env/new-env {:checkpoint-dir       "/tmp/dcch-bb-sanity"
                               :invocation-processors [proc]})
        sid      :bb-sanity]
    (sp/register-statechart! (::sc/statechart-registry env) chart-id scchart)
    (let [w0 (sp/start! (::sc/processor env) env chart-id {::sc/session-id sid})]
      (sp/save-working-memory! (::sc/working-memory-store env) env sid w0))
    (Thread/sleep 400)
    (pump-all! env sid)
    (let [cfg (::sc/configuration (sp/get-working-memory (::sc/working-memory-store env) env sid))]
      (println "final config =" cfg)
      (if (contains? cfg :done)
        (do (println "PASS") (System/exit 0))
        (do (println "FAIL: chart did not reach :done") (System/exit 1))))))

(-main)
