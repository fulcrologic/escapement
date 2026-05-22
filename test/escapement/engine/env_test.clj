(ns escapement.engine.env-test
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final state transition]]
    [com.fulcrologic.statecharts.invocation.multiplex-options :as mo]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.engine.env :as env]
    [fulcro-spec.core :refer [=> assertions specification]])
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
    (let [w0   (sp/start! processor e ::two-state-chart {::sc/session-id sid})
          _    (sp/save-working-memory! store e sid w0)
          cfg0 (::sc/configuration w0)
          w1   (sp/process-event! processor e w0 {:name :go :data {}})
          _    (sp/save-working-memory! store e sid w1)
          cfg1 (::sc/configuration w1)]
      (assertions
        "starts in :idle"
        (contains? cfg0 :idle) => true
        "transitions to :done on :go"
        (contains? cfg1 :done) => true
        "leaves :idle"
        (contains? cfg1 :idle) => false))))

(specification "engine.env — invocation processors include multiplex and statechart-as-invokable"
  (let [e     (env/new-env {:checkpoint-dir (tmp-dir)})
        procs (::sc/invocation-processors e)]
    (assertions
      "the multiplex processor is registered"
      (boolean (some #(sp/supports-invocation-type? % mo/type) procs))
      => true
      "the statechart-as-invokable processor is registered (required by multiplex)"
      (boolean (some #(sp/supports-invocation-type? % ::sc/chart) procs))
      => true)))
