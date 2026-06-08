(ns escapement.debug.branch
  "Branch-fork primitive for the OpenTUI time-travel debugger.

  Re-running an LLM conversation 'from here' never mutates the original run.
  Instead we FORK a new *branch session*: a fresh session-id whose working
  memory is seeded from the parent's pre-conversation node-entry checkpoint, with
  its own transcript + artifact directories and recorded parentage. The parent's
  transcript, checkpoint, and artifacts are strictly read-only inputs.

  This namespace only sets up the branch (new id, seeded wmem, own dirs,
  parentage metadata). The actual forward continuation is driven by
  `escapement.runner/run!` with `:resume? true` against the branch session-id and
  the `:debug-overrides` / `:debug-replay-policy` env seams (see
  `escapement.engine.env`). Layering: this is engine-core (no UI/Pathom require);
  the OpenTUI glue reaches it via `requiring-resolve`.

  Disk layout for a branch (mirrors the CLI's per-session layout, so a branch is
  itself a first-class session that `escapement open` / the api-server can read):

      <work-dir>/<branch-id>/transcript.jsonl
      <work-dir>/<branch-id>/checkpoints/<branch-id>.edn      (seeded here)
      <work-dir>/<branch-id>/branch.edn                       (parentage metadata)
      <work-dir>/<branch-id>/nodes/…                          (written as it runs)"
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.engine.store :as store]))

(defn next-branch-id
  "Unique child session-id derived from `parent-id`. Opaque string; equality only
   (per wire contract). The parent-id may be a namespaced keyword rendered as
   `session/<uuid>` — the `/` would nest the branch's `work-dir/<branch-id>` dir,
   so it is flattened to `_` to keep the branch a single directory segment."
  [parent-id]
  (str (str/replace (str parent-id) #"[^A-Za-z0-9._-]" "_")
    "-branch-" (subs (str (random-uuid)) 0 8)))

(defn fork-session!
  "Fork a new branch session from `parent-session-id` at `branch-point`.

   Args (map):
     * `:parent-session-id` (required) — opaque id of the run being forked.
     * `:branch-point` (required) — `{:node-id <opaque-str> :visit <int> :turn <int>}`
       the conversation/turn the user chose to re-run from.
     * `:work-dir` (required) — the sessions root (`<work-dir>/<id>/…`); the
       parent lives at `<work-dir>/<parent-session-id>` and the new branch dir is
       created under the same root.
     * `:parent-checkpoint-dir` (optional) — where the parent's checkpoints live;
       defaults to `<work-dir>/<parent-session-id>/checkpoints`.
     * `:parent-session-dir` (optional) — the parent's session dir (artifact root,
       read-only source for replay); defaults to `<work-dir>/<parent-session-id>`.
     * `:env` (optional) — an `::sc/env` used only to read the parent's LATEST
       canonical checkpoint as the seed FALLBACK when no `{node-id,visit}`
       node-entry checkpoint exists (see `store/resolve-node-entry-wmem`). When
       omitted, only the explicit node-entry checkpoint is consulted.
     * `:branch-id` (optional) — override the generated child id (tests).

   Effects (the parent is NEVER written):
     1. Resolve the seed working memory for `branch-point` from the parent's
        node-entry checkpoint (fallback: parent's latest checkpoint via `:env`).
     2. Create `<work-dir>/<branch-id>/checkpoints` and write the seeded wmem as
        the branch's canonical checkpoint (so a `:resume? true` run continues
        forward from it).
     3. Write `<work-dir>/<branch-id>/branch.edn` parentage metadata:
        `{:parent <parent-session-id> :branch-point {…} :created-at <epoch-ms>
          :seed-source :node-entry|:latest}`.

   Returns a map describing the new branch:
     `{:branch-id … :parent … :branch-point … :session-dir … :checkpoint-dir …
       :transcript-path … :seed-source :node-entry|:latest
       :statechart-src <chart-id-or-nil> :seeded? bool}`.

   Throws `ex-info` if no seed working memory can be resolved (nothing to fork
   from)."
  [{:keys [parent-session-id branch-point work-dir
           parent-checkpoint-dir parent-session-dir env branch-id]
    :as   _opts}]
  (assert parent-session-id "parent-session-id is required")
  (assert (and (map? branch-point)
            (contains? branch-point :node-id)
            (contains? branch-point :visit)
            (contains? branch-point :turn))
    "branch-point must be {:node-id :visit :turn}")
  (assert work-dir "work-dir is required")
  (let [{:keys [node-id visit]} branch-point
        parent-ck-dir  (or parent-checkpoint-dir
                         (str work-dir "/" parent-session-id "/checkpoints"))
        _parent-sd     (or parent-session-dir
                         (str work-dir "/" parent-session-id))
        ;; Parent checkpoints are READ-ONLY here: a store rooted at the parent
        ;; checkpoint dir, used only to look up the seed snapshot. We never call
        ;; save-* against it, so the parent files cannot be mutated.
        parent-store   (store/new-store parent-ck-dir)
        seed           (store/resolve-node-entry-wmem
                         parent-store (or env {}) parent-session-id node-id (long visit))
        _              (when-not seed
                         (throw (ex-info "No checkpoint to seed branch from"
                                  {:parent parent-session-id :branch-point branch-point})))
        {seed-wmem :wmem seed-source :source} seed
        ;; The chart this seed belongs to — `::sc/statechart-src` is stamped into
        ;; working memory by the engine when a (sub-)chart starts and names the
        ;; registry key the chart is registered under. The branch must resume the
        ;; SAME chart: for a multi-session run the seed is a sub-chart wmem (e.g.
        ;; a poet), NOT the root chart. `rerun-from!` reads this to pick the chart
        ;; to continue (falling back to the caller's chart-id when absent).
        statechart-src (::sc/statechart-src seed-wmem)
        ;; A branch MUST start in some configuration to run forward. A latest
        ;; checkpoint of an already-terminated run has an empty configuration —
        ;; forking from it would resume into nothing. Surface that clearly.
        _              (when (empty? (::sc/configuration seed-wmem #{}))
                         (throw (ex-info "Seed working memory has empty configuration (terminated run?) — nothing to continue"
                                  {:parent parent-session-id :branch-point branch-point
                                   :seed-source seed-source})))
        new-id         (or branch-id (next-branch-id parent-session-id))
        branch-sd      (str work-dir "/" new-id)
        branch-ck-dir  (str branch-sd "/checkpoints")
        transcript     (str branch-sd "/transcript.jsonl")
        branch-store   (store/new-store branch-ck-dir)]
    (.mkdirs (io/file branch-sd))
    ;; Seed the branch's canonical checkpoint with the parent's snapshot, but
    ;; REKEY its `::sc/session-id` to the branch id. The seed carries the
    ;; parent's `::sc/session-id`; the library reads that value when an
    ;; invocation posts its completion/idle events (it targets
    ;; `(env/session-id env)` = the wmem's session-id). Left unchanged, a
    ;; re-invoked conversation on the branch would post `:llm.idle` to the
    ;; PARENT session — which the branch runner never pumps — so the branch
    ;; chart would never advance past the re-run node (it would hang). Rekeying
    ;; routes those events to the branch session so resume continues forward.
    (let [seed-wmem (assoc seed-wmem ::sc/session-id new-id)]
      (sp/save-working-memory! branch-store {} new-id seed-wmem))
    ;; Persist parentage metadata.
    (spit (io/file branch-sd "branch.edn")
      (pr-str {:parent      parent-session-id
               :branch-point branch-point
               :created-at  (System/currentTimeMillis)
               :seed-source seed-source}))
    {:branch-id       new-id
     :parent          parent-session-id
     :branch-point    branch-point
     :session-dir     branch-sd
     :checkpoint-dir  branch-ck-dir
     :transcript-path transcript
     :seed-source     seed-source
     :statechart-src  statechart-src
     :seeded?         true}))

(defn read-parentage
  "Read the `branch.edn` parentage metadata for the branch at `session-dir`,
   or `nil` if the dir is not a branch (no `branch.edn`)."
  [session-dir]
  (let [f (io/file session-dir "branch.edn")]
    (when (.exists f)
      (edn/read-string {:default tagged-literal} (slurp f)))))
