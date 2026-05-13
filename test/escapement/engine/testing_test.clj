(ns escapement.engine.testing-test
  (:require
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :refer [state transition final]]
   [escapement.engine.testing :as dct]
   [fulcro-spec.core :refer [specification assertions =>]]))

(def two-state-chart
  (chart/statechart {:initial :work}
                    (state {:id :work :initial :idle}
                           (state {:id :idle}
                                  (transition {:event :go :target :done}))
                           (final {:id :done}))))

(specification "engine.testing harness"
               (let [t (-> (dct/new-testing-env {:statechart two-state-chart})
                           (dct/start!))]
                 (assertions
                  "starts in :idle"
                  (dct/in? t :idle) => true
                  "not yet in :done"
                  (dct/in? t :done) => false)
                 (let [t' (dct/run-events! t :go)]
                   (assertions
                    "after :go, reaches :done"
                    (dct/in? t' :done) => true
                    "after :go, leaves :idle"
                    (dct/in? t' :idle) => false))))
