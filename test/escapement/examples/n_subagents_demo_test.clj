(ns escapement.examples.n-subagents-demo-test
  "Deterministic end-to-end test of the multiplex-based n-subagents demo.

  Exercises the full life cycle: parent registers worker-chart; multiplex
  spawns N=6 children (one per task in `default-tasks`); each worker
  upper-cases its task and calls `mux/reply`; parent accumulates results
  by `:idx`; library-emitted `done.invoke.workers` transitions the parent
  to `:finished`."
  (:require
    [escapement.engine.testing :as dct]
    [escapement.examples.n-subagents-demo :as d]
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "n-subagents-demo end-to-end via multiplex"
  (let [t (-> (dct/new-testing-env {:statechart d/agent})
            (dct/start!)
            (dct/drain-multi!))
        cfg (dct/configuration t)
        data (dct/data t)]
    (assertions
      "parent reaches :finished"
      (contains? cfg :finished) => true
      "all six tasks produced an upper-cased result, keyed by idx"
      (:results data)
      => {0 "ALPHA" 1 "BETA" 2 "GAMMA" 3 "DELTA" 4 "EPSILON" 5 "ZETA"}
      "tasks were threaded through the data model unchanged"
      (:tasks data) => d/default-tasks)))
