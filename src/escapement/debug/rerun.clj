(ns escapement.debug.rerun
  "Re-run a (possibly completed) session from a node-entry checkpoint — with
   optional LLM overrides + replay — and continue the chart FORWARD to
   completion. Engine-core (no UI/Pathom/RAD); the OpenTUI debugger's
   `rerun-from!` is a thin live-env wrapper over this.

   It composes the three primitives the checkpoint-rerun feature rests on:

     * `escapement.debug.branch/fork-session!` — fork a fresh branch session,
       seeded from the parent's node-entry checkpoint (read-only against the
       parent).
     * `escapement.runner/run! :resume? true`  — which, via
       `escapement.engine.reinvoke`, re-invokes the invoking states present in
       the restored configuration so the LLM actually runs again.
     * the runner's `:chart-env-ready` seam — so a multi-session chart's
       sub-charts are (re-)registered on the branch run (a chart's own
       `on-entry` registration does NOT re-fire on resume).

   ## Scope: single-session vs. multiplex (the lifted limitation)

   For a SINGLE-session run the fork point and the override target are the SAME
   conversation node: fork at it, override it, resume its chart.

   For a MULTIPLEX run (poet/judge sub-charts) the two DIFFER, and this is what
   lets us finish the WHOLE chart rather than re-running one child sub-chart in
   isolation:

     * `:branch-point` is the ROOT's multiplex PARENT state (e.g. `:composing`),
       forked from the ROOT session. Resuming it re-invokes the multiplex, which
       re-spawns every child sub-chart.
     * `:override` is scoped (by `{:node-id :visit}`) to the ONE child
       conversation node you want to change (e.g. `:musing`). It applies only to
       that child's re-issued turn; the other children regenerate normally.

   The downstream phases (judging, summary) then run fresh against the new
   children, so the run reaches its true terminal state. This LIFTS the prior
   single-session-only limitation: a multiplex child can be re-run with an
   override and the tournament re-judged end-to-end.

   For the multiplex case the caller passes the ROOT chart value + chart-id, the
   chart's `:chart-env-ready` (sub-chart registration), and `:multi-session?
   true`. For the single-session case `:multi-session?` is false and
   `:branch-point` == the override node."
  (:require
    [escapement.debug.branch :as branch]
    [escapement.runner :as runner]))

(defn rerun-from-checkpoint!
  "Fork a branch from a node-entry checkpoint and resume it to completion,
   applying `:override` (LLM debug overrides) and `:replay-policy`. Runs
   SYNCHRONOUSLY and returns the fork map augmented with `:result` (the
   `runner/run!` summary: `:status`, `:final-config`, …).

   Required:
     * `:chart`             — the statechart value to resume (the ROOT chart for
                              a multiplex root resume).
     * `:parent-session-id` — id of the session being forked. For a multiplex
                              ROOT resume this is the ROOT session id; for a
                              single-session run, the run's own id.
     * `:branch-point`      — `{:node-id <opaque-str> :visit <int> :turn <int>}`
                              naming the checkpoint to fork at. For a multiplex
                              ROOT resume this is the multiplex PARENT state
                              (e.g. \"composing\"), NOT the child conversation
                              node.

   Locating the parent on disk (recommended for cross-process / completed runs):
     * `:work-dir`              — sessions root (`<work-dir>/<id>/…`).
     * `:parent-checkpoint-dir` — the parent's `checkpoints/` dir.
     * `:parent-session-dir`    — the parent's session dir (artifact root).

   Resume wiring:
     * `:chart-id`        — id to resume under (default `:escapement.runner/chart`).
                            Overridden by the seed's own `::sc/statechart-src`
                            when present (a child seed resumes its sub-chart).
     * `:chart-env-ready` — `(fn [env])` re-applied on the branch run so a
                            multi-session chart's sub-charts register before
                            re-invoke (see runner/run! `:chart-env-ready`).
     * `:multi-session?`  — true for a multiplex ROOT resume (pump all sessions).
     * `:backend` / `:tool-registry` and the model-resolution config
       (`:llm-aliases` `:llm-preferences` `:llm-default-models`
       `:llm-catalog-ratings` `:llm-eligibility-strict?`) — forwarded to run!.

   Debug seams:
     * `:override`      — debug-overrides map naming the conversation node to
                          change via `:node-id` (the CHILD node for a multiplex
                          resume) plus the change (`:system`/`:system-append`/
                          `:alias`/`:provider`+`:model`/`:temperature`/
                          `:messages`). Any caller-supplied `:visit` is IGNORED:
                          the override is auto-scoped to the BRANCH-LOCAL visit
                          0 (the re-issued turn's first invocation in the fresh
                          branch), which is a DIFFERENT coordinate from the
                          `:branch-point` :visit (the parent's global visit used
                          to locate the seed checkpoint).
     * `:replay-policy` — debug-replay-policy (serve captured tool-results by
                          match through the continuation).

   * `:transcript-tap`     — branch transcript tap (passed to run! as
                             `:transcript-tap`).
   * `:quiescent-sleep-ms` — branch pump idle granularity.
   * `:branch-id`          — override the generated branch id (tests).
   * `:run-fn`             — runner entry (default `escapement.runner/run!`);
                             injectable for tests."
  [{:keys [chart chart-id chart-env-ready multi-session?
           parent-session-id branch-point work-dir parent-checkpoint-dir parent-session-dir
           backend tool-registry
           llm-aliases llm-preferences llm-default-models llm-catalog-ratings
           llm-eligibility-strict?
           override replay-policy transcript-tap quiescent-sleep-ms branch-id run-fn]
    :or   {chart-id :escapement.runner/chart
           run-fn   runner/run!}}]
  (assert chart "chart is required")
  (assert parent-session-id "parent-session-id is required")
  (assert branch-point "branch-point {:node-id :visit :turn} is required")
  (let [fork (branch/fork-session!
               (cond-> {:parent-session-id parent-session-id
                        :branch-point      branch-point
                        :work-dir          work-dir
                        :env               {}}
                 parent-checkpoint-dir (assoc :parent-checkpoint-dir parent-checkpoint-dir)
                 parent-session-dir    (assoc :parent-session-dir parent-session-dir)
                 branch-id             (assoc :branch-id branch-id)))
        ;; Resume the chart the seed BELONGS to: a child (sub-chart) seed carries
        ;; its own ::sc/statechart-src; a root/single-session seed falls back to
        ;; the caller's chart-id.
        branch-chart-id (or (:statechart-src fork) chart-id)
        ;; VISIT RECONCILIATION. The override is matched against the re-issued
        ;; turn by {node-id, visit}. The `:branch-point` :visit is the parent's
        ;; GLOBAL visit (used only to LOCATE the seed checkpoint) and is often
        ;; non-zero. The fresh branch, however, has a fresh `:escapement/
        ;; visit-counts` atom, so the override's target node re-invokes at
        ;; BRANCH-LOCAL visit 0 (its first re-invocation here). Scope the override
        ;; to 0 so the caller never has to know — and cannot mis-key it with the
        ;; parent's global visit (which silently misses, leaving the turn
        ;; unchanged). (Targeting a node hit MULTIPLE times within one branch — a
        ;; concurrent multiplex sharing a node id — is not expressible by visit
        ;; and is out of scope; the override targets the fork-point's first hit.)
        override        (when override (assoc override :visit 0))
        result          (run-fn
                          (cond-> {:chart           chart
                                   :chart-id        branch-chart-id
                                   :session-id      (:branch-id fork)
                                   :transcript-path (:transcript-path fork)
                                   :checkpoint-dir  (:checkpoint-dir fork)
                                   :session-dir     (:session-dir fork)
                                   :resume?         true
                                   :multi-session?  (boolean multi-session?)
                                   :backend         backend
                                   :tool-registry   tool-registry
                                   :llm-aliases     llm-aliases
                                   :llm-preferences llm-preferences
                                   :llm-default-models      llm-default-models
                                   :llm-catalog-ratings     llm-catalog-ratings
                                   :llm-eligibility-strict? llm-eligibility-strict?}
                            chart-env-ready    (assoc :chart-env-ready chart-env-ready)
                            override           (assoc :debug-overrides override)
                            replay-policy      (assoc :debug-replay-policy replay-policy)
                            transcript-tap     (assoc :transcript-tap transcript-tap)
                            quiescent-sleep-ms (assoc :quiescent-sleep-ms quiescent-sleep-ms)))]
    (assoc fork :result result)))
