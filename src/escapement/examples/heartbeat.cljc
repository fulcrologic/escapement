(ns escapement.examples.heartbeat
  "Pure-timer polling loop (NO LLM) — the canonical resume-durability demo.

  Mirrors the shape of a long-running poller (e.g. `../gitlab-solver`'s `:polling`
  state): `:polling` arms a delayed `:poll/tick` with `send-after` (on-entry send +
  on-exit cancel); each tick advances to `:ticked`, which increments a `:ticks`
  counter in the data model, prints a heartbeat line, and loops straight back to
  `:polling` to re-arm. The chart therefore runs FOREVER on its own timer, with no
  LLM and no external input — ideal for a deterministic kill/resume experiment.

  What it proves across a `kill -9` + `--resume`:

    * The delayed `:poll/tick` timer sitting in the queue at exit time is PERSISTED
      with the checkpoint and REHYDRATED on resume (escapement.engine.queue snapshot),
      so the poller keeps ticking — the runner does NOT immediately declare `:done`.
      (Before durable queues, resume re-entered `:polling` without re-running its
      on-entry, so no tick was ever armed and the run exited at once.)
    * The `:ticks` counter lives in the checkpointed data model, so the count
      CONTINUES across the restart (tick #4 follows a run that reached #3) — the
      transcript/timeline is a single navigable history over the whole life.

  RUN (headless, no backend needed — there is no LLM):

    bb -m escapement.cli run escapement.examples.heartbeat/agent \\
       --no-tui --session hb --work-dir /tmp/hb-demo

  Let it print a few `HEARTBEAT tick #N` lines, `kill -9` it, then re-run the SAME
  command with `--resume` appended and watch it continue from #N+1."
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.convenience :refer [send-after]]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :refer [on-entry script state transition]]))

(def poll-interval-ms
  "Delay between heartbeat ticks. Short enough to observe a few ticks quickly, long
  enough that a `kill -9` lands while the chart is idle in `:polling` (timer pending)."
  1500)

(def agent
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :polling}
      (state {:id :polling}
        ;; Arm the tick with `send-after` (on-entry send + on-exit cancel) so the
        ;; pending `:poll/tick` is cleaned up whenever `:polling` is left. On a fresh
        ;; start this arms the first tick; on resume the durable queue restores the
        ;; tick that was armed before exit — either way the loop stays alive.
        (send-after {:id :poll-timer :event :poll/tick :delay poll-interval-ms})
        (transition {:event :poll/tick :target :ticked}))
      (state {:id :ticked}
        (on-entry {}
          (script {:expr (fn [_env data]
                           (let [n (inc (long (or (:ticks data) 0)))]
                             (println (str "HEARTBEAT tick #" n))
                             [(ops/assign :ticks n)]))}))
        ;; Eventless loop back to :polling to re-arm the next tick. `:polling` has no
        ;; eventless transition of its own, so the macrostep settles there (no runaway).
        (transition {:target :polling})))))
