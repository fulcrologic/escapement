(ns escapement.engine.spawn
  "Bb-safe child-session primitive (see ../../../n-subagents.md
  §The prerequisite primitive).

  `spawn-child!` starts a SIBLING statechart session in the same env as the
  caller. Both sessions share the env's processor, event queue, working-memory
  store, and invocation processors — the queue partitions events by `:target`
  sid, so each session's processing is isolated even though the env is one.

  The driver (`escapement.runner/run!` invoked with `:multi-session? true`)
  must drain every session's queue from the same pump loop; that's what makes
  child sessions actually progress. Without that flag the parent runner only
  pumps its own sid and any children wedge with un-drained events.

  This is NOT SCXML `<invoke>` — there is no managed lifecycle binding between
  parent and child. The convention is: parent passes its own sid as
  `:reply-to` in the child's initial data; the child `send!`s a reply event
  targeted at that sid when it finishes; the parent transitions on the reply.
  Cancellation is up to the caller (stash child sids in chart data, iterate
  and `send!` a `:cancel` event, or have the child react to a shared event)."
  (:require
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.protocols :as sp]))

(defn random-sid
  "Build a fresh session-id keyword. Prefix is for human-readable transcripts."
  ([] (random-sid "child"))
  ([prefix]
   (keyword "spawn" (str prefix "-" (subs (str (random-uuid)) 0 8)))))

(defn parent-sid
  "Helper for chart scripts: return the current session's sid (intended to be
   passed as `:reply-to` into a child's initial data)."
  [env]
  (some-> env ::sc/vwmem deref ::sc/session-id))

(defn spawn-child!
  "Start a new statechart session sharing `env`.

   `opts`:
    * `:chart` (required) — the statechart value (e.g. from `chart/statechart`)
    * `:chart-id` (optional) — the registry id under which to register `chart`.
      Defaults to a derived keyword based on the chart's `:initial`. Multiple
      spawns of the same chart should pass the SAME `:chart-id` (the registry
      is keyed on this); the registration is idempotent.
    * `:sid` (optional) — explicit session-id. Defaults to a random one.
    * `:input` (optional) — initial data seeded into the child's data model
      (passed as `::sc/invocation-data` to `start!`, mirroring runner.clj's
      `:initial-data` behavior).

   Returns the child sid (the caller will typically `ops/assign` it into
   chart data so the parent can later cancel or correlate replies)."
  [env {:keys [chart chart-id sid input]}]
  (assert chart "spawn-child! requires :chart")
  (let [registry (::sc/statechart-registry env)
        store    (::sc/working-memory-store env)
        proc     (::sc/processor env)
        cid      (or chart-id ::child-chart)
        child    (or sid (random-sid))]
    (sp/register-statechart! registry cid chart)
    (let [w0 (sp/start! proc env cid
               (cond-> {::sc/session-id child}
                 (seq input) (assoc ::sc/invocation-data input)))]
      (sp/save-working-memory! store env child w0))
    ;; Emit a structured spawn record so the transcript carries the
    ;; parent→child relationship as first-class data (one row per child,
    ;; alongside the per-event `:session-id` already on every
    ;; `:runner/event-processed`). Issue #7's reducer can group every
    ;; child's events under its parent using these edges.
    (when-let [tf (:escapement/transcript-fn env)]
      (try (tf {:event :session/spawned
                :ts    (System/currentTimeMillis)
                :data  {:parent-sid (str (parent-sid env))
                        :child-sid  (str child)
                        :chart-id   (str cid)
                        :input-keys (vec (keys input))}})
           (catch Throwable _ nil)))
    child))
