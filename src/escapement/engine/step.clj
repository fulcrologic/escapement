(ns escapement.engine.step
  "Event-driven stepping: drive a session one event at a time instead of handing it to the blocking
   pump in `escapement.runner/run!`.

     (def s (step/open {...run! options...}))   ; start, or `:resume? true` from the checkpoint
     (step/step! s)   ; => {:processed? true :event-name :go :status :running ...}
     (step/step! s)   ; => {:processed? false :status :sleeping :next-wake-at 1793... ...}
     (step/close! s)  ; release the session lock, close the transcript

   `step!` processes AT MOST ONE due event (the earliest by due time, then arrival), runs the chart's
   whole macrostep for it, writes the checkpoint and acknowledges the event in that same write. A crash
   at any point therefore leaves the event either pending in the saved queue or processed and gone.
   When nothing is due it processes nothing and says so.

   Every result reports where the session stands (`escapement.runner/session-state`):
     `:status`           `:running` (more is due now, or an invocation is in flight), `:sleeping` (only
                         delayed events not yet due; come back at `:next-wake-at`), `:waiting`
                         (nothing pending: only an outside event can move it) or `:final`
     `:next-wake-at`     earliest due time of a pending event, epoch ms on the run's clock, or nil
     `:configuration`    the active states; `:final?`
     `:pending-events`   the session's queued events, in delivery order
     `:live-invocations` invocations in flight (their results arrive as events)

   A host that sleeps between events closes the session and reopens it with `:resume? true` at
   `:next-wake-at` (or when an outside event arrives): delayed events are part of the saved queue.

   The debug pause gate is not applied when stepping (the host is the stepper). Saved timers reload on
   reopen only from the default file-backed store."
  (:require
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
    [escapement.runner :as runner]))

(>defn open
  "Open a session for stepping, taking `escapement.runner/run!`'s options (`:chart`, `:session-id`,
   `:transcript-path`, `:checkpoint-dir`, `:resume?`, `:clock`, `:backend`, …; the pump knobs are
   ignored). Starts the chart fresh, or resumes it from its checkpoint, and holds the session lock
   until `close!`. Returns the session."
  [opts]
  [:map => :map]
  (runner/open-stepping-session! opts))

(>defn step!
  "Process at most one due event of `session`. Returns its state plus `:processed?` and `:event-name`."
  [session]
  [:map => :map]
  (runner/step-session! session))

(>defn state
  "Where `session` stands, without processing anything."
  [session]
  [:map => :map]
  (runner/session-state session))

(>defn close!
  "Close `session`: release its lock and transcript. Returns its final state."
  [session]
  [:map => :map]
  (runner/close-stepping-session! session))

(>defn step-until-quiet!
  "Step `session` until nothing is due now and nothing is in flight (or `max-steps` events were
   processed, default 10000). Waits up to `:live-wait-ms` (default 50) real ms between polls while an
   invocation is in flight. Returns the last state."
  ([session] [:map => :map] (step-until-quiet! session {}))
  ([session {:keys [max-steps live-wait-ms] :or {max-steps 10000 live-wait-ms 50}}]
   [:map :map => :map]
   (loop [n 0]
     (let [r (step! session)]
       (cond
         (>= n max-steps) r
         (:processed? r) (recur (inc n))
         (= :running (:status r)) (do (Thread/sleep (long live-wait-ms)) (recur n))
         :else r)))))
