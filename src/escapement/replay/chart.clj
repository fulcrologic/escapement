(ns escapement.replay.chart
  "Replay granularity #3 — **sub-chart / chart-from-history fork.** Re-enter a prior run's chart at a
   historical checkpoint and run it FORWARD into a NEW, isolated session, so you can experiment with
   modified inputs (substituted data-model values, a swapped backend/model, injected events) without
   ever mutating the original session's transcript/artifacts/checkpoints.

   Requires the source run to have been made with `:retain-history?` (so a checkpoint exists at the
   chosen fork point). The fork:

     1. loads the at-or-before combined checkpoint (working memory + the queue that was pending then),
     2. rewrites the embedded source session-id → the fork session-id everywhere (wmem `::sc/session-id`,
        the flat data-model's `:_sessionid`, and each pending event's `:target`),
     3. applies an optional `:transform-wmem` (substitute an input artifact ref, a datum, …),
     4. seeds the fork's own checkpoint with that wmem + retargeted queue, then
     5. runs the chart forward via the ordinary runner RESUME path (queue rehydrate + the
        `:escapement/resumed` signal), pumping any restored/injected events.

   CLJ-only — it drives `escapement.runner`.

   LIMITATIONS (documented, not silent): the fork's data-model may still reference captured-I/O blobs
   under the SOURCE session-dir; those are not auto-copied — point `:fork-session-dir` at a copy of the
   source dir, or override the offending refs via `:transform-wmem`, if the forward run needs them.
   Multi-session/child-session (multiplex) charts are re-targeted only for the ROOT session."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.data-model.working-memory-data-model :as-alias wmdm]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.engine.queue :as queue]
    [escapement.engine.store :as store]
    [escapement.runner :as runner]))

(def ^:private dm-key
  "The working-memory key under which the flat data-model lives in a checkpoint."
  :com.fulcrologic.statecharts.data-model.working-memory-data-model/data-model)

(defn- retarget-event
  "Rewrite every session-id back-reference on a pending `event` (`:target`, `:origin`, and
   `::sc/source-session-id`) from session id `from` to `to`, so a restored timer neither delivers to
   nor claims origin in the source session after a fork."
  [event from to]
  (reduce (fn [e k] (cond-> e (= (get e k) from) (assoc k to)))
    event
    [:target :origin ::sc/source-session-id]))

(defn- retarget-snapshot
  "Rewrite a queue `snapshot`'s session key and each event's session-id back-references from session id
   `from` to `to`, so restored timers deliver to (and originate in) the fork session rather than the
   source. `nil`-safe."
  [snapshot from to]
  (when snapshot
    (update snapshot :sessions
      (fn [sessions]
        (reduce-kv
          (fn [m k evs]
            (assoc m (if (= k from) to k)
              (mapv (fn [e] (update e :event retarget-event from to)) evs)))
          {}
          sessions)))))

(defn- retarget-wmem
  "Rewrite the embedded session id (`::sc/session-id` + the flat data-model's `:_sessionid`) in `wmem`
   from the source id to `fork-session-id`, so the forked run owns a distinct session identity."
  [wmem fork-session-id]
  (-> wmem
    (assoc ::sc/session-id fork-session-id)
    (cond-> (get wmem dm-key) (assoc-in [dm-key :_sessionid] fork-session-id))))

(defn fork!
  "Fork the chart of a prior run at a historical checkpoint and run it forward into a new session.
   Returns the `runner/run!` summary augmented with `:fork-session-id` and `:at-seq`.

   `opts`:
     * `:source-store`       (required) — a `FileBackedStore` over the SOURCE run's checkpoint-dir,
                                          created with `:retain-history?` (see `store/new-store`).
     * `:source-session-id`  (required) — the source run's session id.
     * `:at-seq`             (required) — the fork point; the retained checkpoint at-or-before this
                                          save-index is used (see `store/read-checkpoint-at`).
     * `:chart`              (required) — the chart value (same shape the source run used).
     * `:fork-session-id`    (required) — a NEW session id for the fork (must differ from the source).
     * `:fork-checkpoint-dir`/`:fork-session-dir`/`:fork-transcript-path` (required) — the fork's own,
                                          isolated output locations, so the source is never touched.
     * `:transform-wmem`     (optional) — `wmem -> wmem`, applied AFTER session re-targeting, to
                                          substitute input artifacts / data-model values at the boundary.
     * `:backend`/`:tool-registry`/`:resume-events`/`:retain-history?`/`:quiescent-sleep-ms`/`:cancel`
                                          (optional) — override hooks / passthroughs forwarded to
                                          `runner/run!` (`:cancel` is essential for a long-running fork —
                                          an atom/promise whose truthy value aborts the forked run).

   Throws if no retained checkpoint exists at-or-before `:at-seq` (fork point unreachable)."
  [{:keys [source-store source-session-id at-seq chart fork-session-id
           fork-checkpoint-dir fork-session-dir fork-transcript-path
           backend tool-registry resume-events transform-wmem retain-history?
           quiescent-sleep-ms cancel]}]
  (assert (and source-store source-session-id at-seq chart fork-session-id
            fork-checkpoint-dir fork-session-dir fork-transcript-path)
    "fork! requires :source-store, :source-session-id, :at-seq, :chart, :fork-session-id, and the three fork output paths")
  (assert (not= fork-session-id source-session-id) "fork! :fork-session-id must differ from :source-session-id")
  (let [record (store/read-checkpoint-at source-store source-session-id at-seq)]
    (when-not record
      (throw (ex-info "No retained checkpoint at-or-before the requested fork point"
               {:reason :no-checkpoint-at-seq
                :source-session-id source-session-id :at-seq at-seq})))
    (let [wmem       (cond-> (retarget-wmem (store/record->wmem record) fork-session-id)
                       transform-wmem transform-wmem)
          qsnap      (retarget-snapshot (store/record->queue-snapshot record)
                       source-session-id fork-session-id)
          fork-store (store/new-store fork-checkpoint-dir {:retain-history? (boolean retain-history?)})
          seed-queue (queue/queue-from-snapshot qsnap)]
      ;; Seed the fork's checkpoint with the retargeted wmem + pending queue in one write, so the
      ;; runner's resume path finds a non-empty configuration AND the timers that were live then.
      (sp/save-working-memory! fork-store {::sc/event-queue seed-queue} fork-session-id wmem)
      (-> (runner/run! (cond-> {:chart           chart
                                :session-id      fork-session-id
                                :resume?         true
                                :store           fork-store
                                :checkpoint-dir  fork-checkpoint-dir
                                :session-dir     fork-session-dir
                                :transcript-path fork-transcript-path}
                         backend            (assoc :backend backend)
                         tool-registry      (assoc :tool-registry tool-registry)
                         (seq resume-events) (assoc :resume-events resume-events)
                         retain-history?    (assoc :retain-history? retain-history?)
                         quiescent-sleep-ms (assoc :quiescent-sleep-ms quiescent-sleep-ms)
                         cancel             (assoc :cancel cancel)))
        (assoc :fork-session-id fork-session-id :at-seq at-seq)))))
