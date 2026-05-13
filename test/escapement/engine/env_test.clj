(ns escapement.engine.env-test
  (:require
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :refer [state transition final]]
   [com.fulcrologic.statecharts.protocols :as sp]
   [escapement.engine.env :as env]
   [fulcro-spec.core :refer [specification assertions =>]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir []
  (str (Files/createTempDirectory "env-test" (into-array FileAttribute []))))

;; Wrap final in a compound parent so a top-level final doesn't empty the configuration on entry.
(def two-state-chart
  (chart/statechart {:initial :work}
                    (state {:id :work :initial :idle}
                           (state {:id :idle}
                                  (transition {:event :go :target :done}))
                           (final {:id :done}))))

(specification "engine.env"
               (let [e         (env/new-env {:checkpoint-dir (tmp-dir)})
                     registry  (::sc/statechart-registry e)
                     store     (::sc/working-memory-store e)
                     processor (::sc/processor e)
                     sid       :env-test/sess]
                 (sp/register-statechart! registry ::two-state-chart two-state-chart)
                 (let [w0 (sp/start! processor e ::two-state-chart {::sc/session-id sid})
                       _  (sp/save-working-memory! store e sid w0)
                       cfg0 (::sc/configuration w0)
                       w1 (sp/process-event! processor e w0 {:name :go :data {}})
                       _  (sp/save-working-memory! store e sid w1)
                       cfg1 (::sc/configuration w1)]
                   (assertions
                    "starts in :idle"
                    (contains? cfg0 :idle) => true
                    "transitions to :done on :go"
                    (contains? cfg1 :done) => true
                    "leaves :idle"
                    (contains? cfg1 :idle) => false))))
