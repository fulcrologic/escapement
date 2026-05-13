(ns custom-invocation
  "Spike #3 + #4: custom InvocationProcessor under bb, plus future-based invoke."
  (:require
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.algorithms.v20150901 :as alg]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]
   [com.fulcrologic.statecharts.elements :refer [state transition final invoke]]
   [com.fulcrologic.statecharts.event-queue.manually-polled-queue :as mpq]
   [com.fulcrologic.statecharts.execution-model.lambda :as lambda]
   [com.fulcrologic.statecharts.protocols :as sp]
   [com.fulcrologic.statecharts.registry.local-memory-registry :as lmr]
   [com.fulcrologic.statecharts.working-memory-store.local-memory-store :as lms]))

;; ---- Custom InvocationProcessor: :echo
;; Immediately sends back done.invoke.<id> with canned data.

(require '[com.fulcrologic.statecharts.environment :as env-ns])

(defrecord EchoProcessor []
  sp/InvocationProcessor
  (supports-invocation-type? [_ typ]
    (println "  [echo] supports? " typ)
    (= typ :echo))
  (start-invocation! [_ env invoke-data]
    (println "  [echo] start-invocation! data=" (pr-str invoke-data))
    (let [{:keys [invokeid params]} invoke-data
          queue (::sc/event-queue env)
          sid   (env-ns/session-id env)
          ename (keyword (str "done.invoke." (if (keyword? invokeid) (name invokeid) (str invokeid))))]
      (println "  [echo] sending event" ename "to sid" sid)
      (sp/send! queue env
                {:target            sid
                 :source-session-id sid
                 :sendid            (str sid "." invokeid)
                 :event             ename
                 :data              {:echoed params :from :echo-processor}})
      true))
  (stop-invocation! [_ _ _] nil)
  (forward-event! [_ _ _] nil))

(def echo-chart
  (chart/statechart {:initial :calling}
                    (state {:id :calling}
                           (invoke {:type :echo
                                    :idlocation [:invoke-id]
                                    :id "echo-1"
                                    :params (fn [_ _] {:hello "world"})})
                           (transition {:event :done.invoke.echo-1 :target :got-it}))
                    (state {:id :got-it}
                           (transition {:event :reset :target :calling}))))

(defn build-env [extra-processors]
  (let [dm       (wmdm/new-flat-model)
        queue    (mpq/new-queue)
        exec     (lambda/new-execution-model dm queue)
        registry (lmr/new-registry)
        store    (lms/new-store)]
    {::sc/statechart-registry   registry
     ::sc/data-model            dm
     ::sc/event-queue           queue
     ::sc/working-memory-store  store
     ::sc/processor             (alg/new-processor)
     ::sc/invocation-processors extra-processors
     ::sc/execution-model       exec}))

(defn pump!
  "Drain the manual queue, processing events for the given session id, up to n iterations."
  [env sid n]
  (let [queue     (::sc/event-queue env)
        store     (::sc/working-memory-store env)
        processor (::sc/processor env)]
    (loop [i n]
      (when (pos? i)
        (let [progressed? (atom false)]
          (sp/receive-events! queue env
                              (fn [_ event]
                                (reset! progressed? true)
                                (let [wmem  (sp/get-working-memory store env sid)
                                      wmem' (sp/process-event! processor env wmem event)]
                                  (sp/save-working-memory! store env sid wmem')))
                              {})
          (when @progressed? (recur (dec i))))))))

(defn run-echo-test []
  (println "=== Test: custom :echo InvocationProcessor ===")
  (let [env       (build-env [(->EchoProcessor)])
        registry  (::sc/statechart-registry env)
        store     (::sc/working-memory-store env)
        processor (::sc/processor env)
        sid       :echo-sess]
    (sp/register-statechart! registry ::echo echo-chart)
    (let [w0 (sp/start! processor env ::echo {::sc/session-id sid})]
      (sp/save-working-memory! store env sid w0)
      (println "after start config =" (:com.fulcrologic.statecharts/configuration w0)))
    (pump! env sid 5)
    (let [w   (sp/get-working-memory store env sid)
          cfg (:com.fulcrologic.statecharts/configuration w)]
      (println "final config =" cfg)
      (if (contains? cfg :got-it)
        (println "PASS: custom InvocationProcessor fired and event was received")
        (do (println "FAIL: did not reach :got-it") (System/exit 1))))))

;; ---- Future invocation
(defn run-future-test []
  (println)
  (println "=== Test: invocation.future under bb ===")
  (try
    (require '[com.fulcrologic.statecharts.invocation.future :as i.future])
    (let [proc-fn (resolve 'com.fulcrologic.statecharts.invocation.future/new-future-processor)
          fp      (proc-fn)
          fchart  (chart/statechart {:initial :spawning}
                                    (state {:id :spawning}
                                           (invoke {:type :future
                                                    :id "f1"
                                                    :params (fn [_ _] {:x 21})
                                                    :src (fn [{:keys [x]}]
                                                           (Thread/sleep 50)
                                                           {:doubled (* 2 x)})})
                                           (transition {:event :done.invoke.f1 :target :ok}))
                                    (state {:id :ok}))
          env     (build-env [fp])
          sid     :fut-sess]
      (sp/register-statechart! (::sc/statechart-registry env) ::fchart fchart)
      (let [w0 (sp/start! (::sc/processor env) env ::fchart {::sc/session-id sid})]
        (sp/save-working-memory! (::sc/working-memory-store env) env sid w0))
      ;; Wait for the future to settle, then pump.
      (Thread/sleep 250)
      (pump! env sid 10)
      (let [w   (sp/get-working-memory (::sc/working-memory-store env) env sid)
            cfg (:com.fulcrologic.statecharts/configuration w)]
        (println "future-chart final config =" cfg)
        (if (contains? cfg :ok)
          (println "PASS: future-based invocation completed")
          (println "FAIL: future invocation did not complete"))))
    (catch Throwable t
      (println "FAIL (future ns load/run):" (.getMessage t))
      (.printStackTrace t))))

(defn -main [& _]
  (run-echo-test)
  (run-future-test)
  (System/exit 0))

(-main)
