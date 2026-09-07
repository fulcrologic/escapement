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
    [com.fulcrologic.statecharts :as-alias sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final invoke on-entry script state transition]]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.engine.queue :as queue]
    [escapement.engine.store :as store]
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

(def resume-signal-chart
  "Idle chart that self-heals on the `:escapement/resumed` signal, then accepts a host-supplied
   `:custom/ping` resume-event. On-entry bumps prove which states were (re-)entered."
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :idle}
      (state {:id :idle}
        (on-entry {} (script {:expr (fn [_ _] (bump! :idle) nil)}))
        (transition {:event :escapement/resumed :target :healed}))
      (state {:id :healed}
        (on-entry {} (script {:expr (fn [_ _] (bump! :healed) nil)}))
        (transition {:event :custom/ping :target :done}))
      (final {:id :done}
        (on-entry {} (script {:expr (fn [_ _] (bump! :done) nil)}))))))

(specification "runner resume: the :escapement/resumed signal + host resume-events drive self-heal"
  (let [dir         (tmp-dir "runner-resume-signal-")
        chk         (str dir "/chk")
        transcript1 (str dir "/run1.jsonl")
        transcript2 (str dir "/run2.jsonl")
        sid         :resume-signal/session
        entries     (atom {})]
    (binding [*entries* entries]
      ;; Run 1: parks in :idle awaiting an external event.
      (runner/run! {:chart resume-signal-chart :session-id sid
                    :transcript-path transcript1 :checkpoint-dir chk
                    :quiescent-sleep-ms 5}))
    (reset! entries {})
    (binding [*entries* entries]
      ;; Run 2 (resume): built-in :escapement/resumed fires FIRST (idle → healed),
      ;; then the host resume-event :custom/ping (healed → done).
      (runner/run! {:chart resume-signal-chart :session-id sid
                    :transcript-path transcript2 :checkpoint-dir chk
                    :resume? true :quiescent-sleep-ms 5
                    :resume-events [{:event :custom/ping}]}))
    (assertions
      "the :escapement/resumed signal drove the opt-in self-heal transition (idle → healed)"
      (get @entries :healed) => 1
      "the host-supplied resume-event was delivered AFTER the signal (healed → done)"
      (get @entries :done) => 1
      "resume did NOT replay :idle's on-entry (no blanket on-entry replay)"
      (get @entries :idle) => nil)))

(specification "runner resume: a past-due event persisted in the queue fires on resume"
  ;; Phase 2 durability: an event still in the queue at process-exit time (e.g. a
  ;; delayed timer whose delivery-time has since passed) is restored from the
  ;; checkpoint's queue snapshot and delivered on resume — advancing the chart with
  ;; NO on-entry replay needed. We model "its time has passed" by persisting an
  ;; immediate (past-due) :go into the checkpoint queue between the two runs.
  (let [dir         (tmp-dir "runner-resume-queue-")
        chk         (str dir "/chk")
        transcript1 (str dir "/run1.jsonl")
        transcript2 (str dir "/run2.jsonl")
        sid         :resume-queue/session
        entries     (atom {})]
    (binding [*entries* entries]
      ;; Run 1: reaches :a awaiting :go, no events → quiescent.
      (runner/run! {:chart two-step-chart :session-id sid
                    :transcript-path transcript1 :checkpoint-dir chk
                    :quiescent-sleep-ms 5}))
    ;; Between runs: persist a past-due :go into the checkpoint's queue snapshot,
    ;; keeping the checkpointed working memory intact. This is exactly what a
    ;; surviving delayed timer looks like after enough downtime.
    (let [s     (store/new-store chk)
          wmem  (sp/get-working-memory s {} sid)
          queue (queue/new-queue)]
      (sp/send! queue {} {:event :go :target sid})            ; no :delay ⇒ past-due
      (sp/save-working-memory! s {::sc/event-queue queue} sid wmem))
    (reset! entries {})
    (binding [*entries* entries]
      ;; Run 2 (resume): rehydrates the queue, delivers :go, advances :a → :b.
      (runner/run! {:chart two-step-chart :session-id sid
                    :transcript-path transcript2 :checkpoint-dir chk
                    :resume? true :quiescent-sleep-ms 5}))
    (assertions
      "resume delivered the restored :go event, entering :b"
      (get @entries :b) => 1
      "resume did NOT replay :a's on-entry (state was restored, not re-run)"
      (get @entries :a) => nil
      "the resume transcript reports the restored pending-event count"
      (let [rows (with-open [r (io/reader transcript2)] (doall (line-seq r)))]
        (boolean (some #(re-find #"\"restored-events\":1" %) rows))) => true)))

;; ---------------------------------------------------------------------------
;; The orphaned-invocation guard
;; ---------------------------------------------------------------------------

(def orphan-child-chart
  "Invoked child. Parks forever (its only transition awaits an event nobody sends)
   so the parent's `<invoke>` is genuinely long-lived during run 1."
  (chart/statechart {:initial :busy}
    (state {:id :busy}
      (on-entry {} (script {:expr (fn [_ _] (bump! :child) nil)}))
      (transition {:event :never :target :child-done}))
    (final {:id :child-done})))

(def orphan-parent-chart
  "Parks in :working with a live child-chart `<invoke>`. On resume the library does
   NOT restart that invocation (invocations start only on state ENTRY, and restoring
   a checkpoint enters nothing), so :working is parked with a dead `<invoke>`."
  (chart/statechart {:initial :working}
    (state {:id :working}
      (invoke {:id :job :type :com.fulcrologic.statecharts/chart :src :orphan/child})
      (transition {:event :finish :target :parent-done}))
    (final {:id :parent-done})))

(specification "runner resume: a parked state whose <invoke> is dead reports :orphaned-invocation, not a false :done"
  (let [dir         (tmp-dir "runner-resume-orphan-")
        chk         (str dir "/chk")
        transcript1 (str dir "/run1.jsonl")
        transcript2 (str dir "/run2.jsonl")
        sid         :resume-orphan/session
        entries     (atom {})
        base        {:chart              orphan-parent-chart
                     :session-id         sid
                     :checkpoint-dir     chk
                     :quiescent-sleep-ms 5
                     :max-frozen-cycles  20
                     ;; `run!` takes no `:invocation-processors`, so the child is
                     ;; registered through the env-ready hook and invoked by the
                     ;; built-in chart-as-invokable processor.
                     :on-env-ready       (fn [env]
                                           (sp/register-statechart!
                                             (::sc/statechart-registry env) :orphan/child orphan-child-chart))}
        r1          (binding [*entries* entries]
                      (runner/run! (assoc base :transcript-path transcript1)))
        after-1     @entries
        _           (reset! entries {})
        r2          (binding [*entries* entries]
                      (runner/run! (assoc base :transcript-path transcript2 :resume? true)))]
    (assertions
      "run 1 started the child invocation"
      (get after-1 :child) => 1
      "run 1 parked in :working (the invoking state)"
      (:final-config r1) => [:working]
      "resume did NOT restart the invocation"
      (get @entries :child) => nil
      "resume still restores the invoking configuration"
      (:final-config r2) => [:working]
      "resume does NOT report a false :done"
      (:status r2) => :orphaned-invocation
      "the transcript records the reason and the offending state"
      (let [rows (with-open [r (io/reader transcript2)] (doall (line-seq r)))
            row  (first (filter #(re-find #"orphaned-invocation" %) rows))]
        (boolean (and row (re-find #"runner/error" row) (re-find #"working" row)))) => true)))

(specification "runner resume: the guard does NOT fire for a state parked with no <invoke>"
  ;; Regression fence for over-triggering: parking in a non-final state simply
  ;; awaiting an external event is legitimate and must still report :done.
  (let [dir         (tmp-dir "runner-resume-no-orphan-")
        chk         (str dir "/chk")
        transcript1 (str dir "/run1.jsonl")
        transcript2 (str dir "/run2.jsonl")
        sid         :resume-no-orphan/session
        entries     (atom {})
        base        {:chart two-step-chart :session-id sid :checkpoint-dir chk
                     :quiescent-sleep-ms 5}
        r1          (binding [*entries* entries]
                      (runner/run! (assoc base :transcript-path transcript1)))
        r2          (binding [*entries* entries]
                      (runner/run! (assoc base :transcript-path transcript2 :resume? true)))]
    (assertions
      "fresh run parked awaiting :go and reported :done"
      (:status r1) => :done
      "resume into the same invoke-less parked state still reports :done"
      (:status r2) => :done
      "and it really is parked in a non-final state"
      (boolean (some #{:a} (:final-config r2))) => true
      "no :orphaned-invocation error was emitted"
      (let [rows (with-open [r (io/reader transcript2)] (doall (line-seq r)))]
        (boolean (some #(re-find #"orphaned-invocation" %) rows))) => false)))
