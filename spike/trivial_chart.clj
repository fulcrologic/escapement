(ns trivial-chart
  "Spike #2: trivial chart under bb with manual env (avoiding simple/promesa)."
  (:require
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.algorithms.v20150901 :as alg]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]
   [com.fulcrologic.statecharts.elements :refer [state transition final]]
   [com.fulcrologic.statecharts.event-queue.manually-polled-queue :as mpq]
   [com.fulcrologic.statecharts.execution-model.lambda :as lambda]
   [com.fulcrologic.statecharts.protocols :as sp]
   [com.fulcrologic.statecharts.registry.local-memory-registry :as lmr]
   [com.fulcrologic.statecharts.working-memory-store.local-memory-store :as lms]))

(def chart-def
  (chart/statechart {:initial :work}
                    (state {:id :work :initial :idle}
                           (state {:id :idle}
                                  (transition {:event :go :target :done}))
                           (final {:id :done}))))

(defn build-env []
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
     ::sc/invocation-processors []
     ::sc/execution-model       exec}))

(defn -main [& _]
  (let [env       (build-env)
        registry  (::sc/statechart-registry env)
        store     (::sc/working-memory-store env)
        processor (::sc/processor env)
        sid       :trivial-1]
    (sp/register-statechart! registry ::trivial chart-def)
    (let [w0 (sp/start! processor env ::trivial {::sc/session-id sid})]
      (sp/save-working-memory! store env sid w0)
      (println "after start, configuration =" (:com.fulcrologic.statecharts/configuration w0)))
    (let [wmem (sp/get-working-memory store env sid)
          w1   (sp/process-event! processor env wmem {:name :go :data {}})]
      (sp/save-working-memory! store env sid w1)
      (let [cfg     (:com.fulcrologic.statecharts/configuration w1)
            history (:com.fulcrologic.statecharts/enabled-transitions w1)
            ;; final-state termination empties config; check exit-states or running flag
            done?   (or (contains? cfg :done)
                        (boolean (some #{:done} (keys w1)))
                        (true? (:com.fulcrologic.statecharts/final-state? w1))
                      ;; if the chart ended in a final, the wmem still records it:
                        (= :done (:com.fulcrologic.statecharts/final-configuration w1)))]
        (println "after :go, full wmem keys =" (keys w1))
        (println "after :go, wmem =" (pr-str w1))
        (println "after :go, configuration =" cfg)
        (if done?
          (do (println "PASS: reached :done") (System/exit 0))
          (do (println "PARTIAL: chart terminated (top-level final). Inspect wmem above.")
              (System/exit 0)))))))

(-main)
