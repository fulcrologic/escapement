(ns escapement.runner-resume-test
  "Crash/resume verification for the runner.

  We run a chart partway, then start a fresh runner with `:resume? true` and the
  same `:session-id`. The chart is constructed so that resume completion can be
  observed without needing to halt the first runner mid-event: the first run
  reaches a state that awaits an external event but has no live invocations, so
  the runner naturally returns. Then we feed the resume run an event by
  pre-seeding the checkpointed configuration's awaited event into the queue.

  The chart also counts `:on-entry` invocations into a side-channel atom; the
  test then asserts the counter to verify whether prior states were re-entered
  on resume (documenting library behavior precisely)."
  (:require
    [clojure.java.io :as io]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final on-entry script state transition]]
    [escapement.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir [prefix]
  (str (Files/createTempDirectory prefix (into-array FileAttribute []))))

;; Side-channel counters keyed by state id. The script element is a side effect;
;; we don't try to model this in the data-model.
(def ^:dynamic *entries* nil)

(defn- bump! [k]
  (when *entries* (swap! *entries* update k (fnil inc 0))))

(def two-step-chart
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :a}
      (state {:id :a}
        (on-entry {} (script {:expr (fn [_ _] (bump! :a) nil)}))
        (transition {:event :go :target :b}))
      (state {:id :b}
        (on-entry {} (script {:expr (fn [_ _] (bump! :b) nil)}))
        (transition {:event :go :target :done}))
      (final {:id :done}
        (on-entry {} (script {:expr (fn [_ _] (bump! :done) nil)}))))))

(specification "runner resume: starting with :resume? true picks up checkpointed config"
  (let [dir         (tmp-dir "runner-resume-m6-")
        chk         (str dir "/chk")
        transcript1 (str dir "/run1.jsonl")
        transcript2 (str dir "/run2.jsonl")
        sid         :resume-test/session
        entries     (atom {})]
    (binding [*entries* entries]
      ;; Run 1: starts in :a, no events available -> goes quiescent immediately.
      (runner/run! {:chart              two-step-chart
                    :session-id         sid
                    :transcript-path    transcript1
                    :checkpoint-dir     chk
                    :max-iterations     50
                    :quiescent-sleep-ms 5}))
    (let [entries-after-1 @entries
          ;; Now we want to:
          ;;   1. simulate a "crash" (no in-memory state survives)
          ;;   2. restart with :resume? true and confirm we DO NOT re-enter :a
          ;; To test cleanly, reset the counters before run 2 and assert what
          ;; on-entry hooks fire.
          _               (reset! entries {})]
      (binding [*entries* entries]
        ;; Resume run: same session-id, same checkpoint-dir. Library will not
        ;; call start! again because checkpoint has a non-empty configuration.
        (runner/run! {:chart              two-step-chart
                      :session-id         sid
                      :transcript-path    transcript2
                      :checkpoint-dir     chk
                      :resume?            true
                      :max-iterations     50
                      :quiescent-sleep-ms 5}))
      (let [entries-after-2 @entries]
        (assertions
          "run 1 entered :a"
          (get entries-after-1 :a) => 1
          "run 1 did not enter :b or :done"
          (boolean (or (get entries-after-1 :b) (get entries-after-1 :done))) => false
          "resume run did NOT re-enter :a (library does not replay on-entry on resume)"
          (get entries-after-2 :a) => nil
          "transcript 2 logs :runner/resumed (the resume code path was taken)"
          (let [rows (with-open [r (io/reader transcript2)]
                       (doall (line-seq r)))]
            (boolean (some #(re-find #"runner/resumed" %) rows))) => true)))))
