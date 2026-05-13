(ns sanity
  "Babashka sanity script. Verifies the engine loads under bb and that a trivial chart
  can be assembled and driven end-to-end through our env (no `simple`, no `promesa`)."
  (:require
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :refer [state transition final]]
   [com.fulcrologic.statecharts.protocols :as sp]
   [escapement.engine.env :as env]))

(def chart-def
  (chart/statechart {:initial :work}
                    (state {:id :work :initial :idle}
                           (state {:id :idle}
                                  (transition {:event :go :target :done}))
                           (final {:id :done}))))

(defn -main [& _]
  (let [tmp       (str (System/getProperty "java.io.tmpdir") "/dcch-sanity-" (System/currentTimeMillis))
        e         (env/new-env {:checkpoint-dir tmp})
        registry  (::sc/statechart-registry e)
        store     (::sc/working-memory-store e)
        processor (::sc/processor e)
        sid       :sanity-1]
    (sp/register-statechart! registry ::sanity chart-def)
    (let [w0 (sp/start! processor e ::sanity {::sc/session-id sid})]
      (sp/save-working-memory! store e sid w0)
      (println "after start, config =" (::sc/configuration w0))
      (when-not (contains? (::sc/configuration w0) :idle)
        (println "FAIL: did not start in :idle") (System/exit 1)))
    (let [w0 (sp/get-working-memory store e sid)
          w1 (sp/process-event! processor e w0 {:name :go :data {}})]
      (sp/save-working-memory! store e sid w1)
      (println "after :go, config =" (::sc/configuration w1))
      (if (contains? (::sc/configuration w1) :done)
        (do (println "PASS: bb sanity — engine loads and trivial chart runs end-to-end")
            (System/exit 0))
        (do (println "FAIL: did not reach :done") (System/exit 1))))))

(-main)
