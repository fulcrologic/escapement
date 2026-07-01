(ns escapement.examples.heartbeat-test
  (:require
    [escapement.engine.testing :as dct]
    [escapement.examples.heartbeat :as hb]
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "heartbeat demo — the poll/tick loop increments a persisted counter"
  (let [t (dct/start! (dct/new-testing-env {:statechart hb/agent}))]
    (assertions
      "starts idle in :polling (the tick timer armed on entry)"
      (dct/in? t :polling) => true
      "no ticks yet"
      (:ticks (dct/data t)) => nil)
    (dct/run-events! t :poll/tick)
    (assertions
      "a tick increments :ticks and loops straight back to :polling (re-arming)"
      (:ticks (dct/data t)) => 1
      (dct/in? t :polling) => true)
    (dct/run-events! t :poll/tick)
    (dct/run-events! t :poll/tick)
    (assertions
      "the counter accumulates across ticks — the data-model continuity resume relies on"
      (:ticks (dct/data t)) => 3
      "and the chart settles back in :polling after each (no runaway eventless loop)"
      (dct/in? t :polling) => true)))
