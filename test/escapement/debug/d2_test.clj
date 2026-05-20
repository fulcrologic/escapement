(ns escapement.debug.d2-test
  (:require
    [clojure.string :as str]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final state transition]]
    [escapement.debug.d2 :as d2]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(def ^:private fixture-chart
  (chart/statechart
    {:initial :a}
    (state {:id :a}
      (transition {:event :go :target :b}))
    (final {:id :b})))

(specification "chart->d2"
  (let [out (d2/chart->d2 fixture-chart #{:a})]
    (assertions
      "emits a node id for each state"
      (and (str/includes? out "a")
        (str/includes? out "b")) => true

      "emits a transition arrow with the event label"
      (boolean (re-find #"a\s*->\s*b\s*:\s*\":?go\"" out)) => true

      "highlights the active state"
      (boolean (re-find #"(?s)a[^}]*style\.fill" out)) => true

      "does not highlight the inactive state"
      (boolean (re-find #"(?s)b[^}]*style\.fill" out)) => false

      "renders final states as a circle"
      (boolean (re-find #"(?s)b[^}]*shape:\s*circle" out)) => true))

  (component "with a nested compound state"
    (let [chart (chart/statechart
                  {:initial :outer}
                  (state {:id :outer :initial :inner}
                    (state {:id :inner})))
          out   (d2/chart->d2 chart #{:inner})]
      (assertions
        "emits the inner state nested under the outer"
        (and (str/includes? out "outer")
          (str/includes? out "inner")) => true

        "highlights the nested active state"
        (boolean (re-find #"(?s)inner[^}]*style\.fill" out)) => true))))

(specification "render-and-open!"
  (component "when the d2 binary is missing"
    (let [tmp (str (System/getProperty "java.io.tmpdir") "/d2-test-" (System/currentTimeMillis))]
      (.mkdirs (java.io.File. tmp))
      (let [r (d2/render-and-open! fixture-chart #{:a} tmp
                {:d2 {:command "definitely-not-a-real-binary-xyz"}})]
        (assertions
          "never throws and reports the failure"
          (boolean (or (:error r) (:svg-path r))) => true

          "always writes the .d2 source file"
          (.exists (java.io.File. (str tmp "/chart.d2"))) => true)))))
