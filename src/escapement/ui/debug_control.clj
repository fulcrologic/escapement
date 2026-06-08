(ns escapement.ui.debug-control
  "Agent-side CONTROL SURFACE for the OpenTUI time-travel debugger
   (`docs/opentui-debugger.md`, wire §9). This is an `escapement.ui.*` add-on:
   the sidecar back-channel (`tui/opentui/sidecar.clj` `make-ws-handlers`) reaches
   it only via `requiring-resolve`, so the architecture boundary stays intact
   (core never statically requires UI/Pathom/RAD, and this ns never statically
   requires them either).

   It orchestrates the engine-core pieces built by tasks 002–005:
     * branch fork .................. `escapement.debug.branch/fork-session!` (002)
     * `:debug/overrides` injection .. `escapement.llm/apply-debug-overrides` +
                                       `llm_conversation` (003), passed THROUGH
                                       `runner/run!` as `:debug-overrides`
     * replay policy ................. `escapement.debug.replay` (004), passed
                                       THROUGH `runner/run!` as `:debug-replay-policy`
     * LLM-turn breakpoint + nav ..... `escapement.debug.controller` (005)

   It also provides two read-side providers pushed to the sidecar as forward
   frames (wire §9): the `model-catalog` (aliases + expanded targets + preferences
   from `.escapement.edn` merged with built-in defaults) and a `conversation`
   (the editable transcript reconstructed from captured turns).

   Everything here is SCI/bb-safe."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.protocols :as sp]
   [escapement.capture :as capture]
   [escapement.config :as config]
   [escapement.debug.branch :as branch]
   [escapement.debug.controller :as dbg]
   [escapement.llm.preferences :as prefs]
   [escapement.protocols :as proto]))

;; -------------------------------------------------------------------------------------------------
;; Wire encoding helpers (mirror docs/opentui-wire.md §2)
;; -------------------------------------------------------------------------------------------------

(defn- kw->wire
  "A keyword -> its wire string (name without leading colon, namespace kept with `/`)."
  [k]
  (cond
    (keyword? k) (subs (str k) 1)
    (string? k)  k
    (nil? k)     nil
    :else        (str k)))

;; -------------------------------------------------------------------------------------------------
;; Model catalog (wire §9 forward frame `model-catalog`)  — R10
;; -------------------------------------------------------------------------------------------------

(defn- target->wire
  "Encode one `{:provider :model …}` alias target into the wire shape
   `{\"provider\":\"openai\",\"model\":\"gpt-4o\"}` (provider kw -> name-string;
   model a plain string both sides). Extra target keys are dropped."
  [{:keys [provider model]}]
  {:provider (kw->wire provider)
   :model    (str model)})

(defn model-catalog
  "Build the `model-catalog` forward frame (wire §9). Enumerates the configured
   `:llm/aliases` (alias-kw -> ordered vector of `{:provider :model}` targets),
   each alias's expanded targets, plus `:llm/preferences` (an ordered vector of
   alias-name strings) — all merged with the BUILT-IN defaults
   (`preferences/default-aliases` / `default-preferences`) when the config sets
   neither key.

   `cfg` is a loaded config map; when omitted, `config/load-config` is read from
   disk (`.escapement.edn` user+project, deep-merged). Returns:

     {:kind \"model-catalog\"
      :aliases [{:alias \"smart\" :targets [{:provider \"openai\" :model \"gpt-4o\"}]} …]
      :preferences [\"smart\" \"fast\" …]}

   Per wire §2: alias keyword -> name string (no leading colon); preferences are
   ordered name-strings."
  ([] (model-catalog (try (config/load-config) (catch Throwable _ {}))))
  ([cfg]
   (let [aliases (prefs/aliases-from-config cfg)
         pref-kw (prefs/preferences cfg)]
     {:kind     "model-catalog"
      :aliases  (mapv (fn [[alias-kw targets]]
                        {:alias   (kw->wire alias-kw)
                         :targets (mapv target->wire targets)})
                  aliases)
      :preferences (mapv kw->wire pref-kw)})))

;; -------------------------------------------------------------------------------------------------
;; Conversation transcript-for-edit (wire §9 forward frame `conversation`)  — R3 transcript edit
;; -------------------------------------------------------------------------------------------------

(defn- content->text
  "Flatten an LLM message `:content` (a vector of `{:type :text :text …}` blocks,
   or a plain string) back into a single editable text string. Non-text blocks
   (tool-use/tool-result) are skipped — the editable transcript exposes prose."
  [content]
  (cond
    (string? content) content
    (sequential? content)
    (->> content
      (filter #(= :text (:type %)))
      (map :text)
      (apply str))
    :else (str (or content ""))))

(defn- request->turn-frame
  "Reduce a captured `request.edn` map (the losslessly captured turn request:
   `{:system … :messages [{:role :content} …] :model …}`) into the wire
   `conversation` turn shape: `{:turn :model :system :messages [{:role :text}]}`."
  [turn request]
  {:turn     turn
   :model    (some-> (:model request) str)
   :system   (some-> (:system request) str)
   :messages (mapv (fn [m]
                     {:role (kw->wire (:role m))
                      :text (content->text (:content m))})
               (:messages request))})

(defn- read-edn [s]
  (when (string? s)
    (try (edn/read-string s) (catch Throwable _ nil))))

(defn conversation
  "Build the `conversation` forward frame (wire §9) — the EDITABLE transcript for
   a captured invocation. Reads the captured turns under
   `nodes/<node-id>/<visit>/turns/<turn>/request.edn` from `store` (an
   `ArtifactStore`) for `session-id`, walking `turn` 0,1,2,… until a request is
   missing. Each turn yields `{:turn :model :system :messages}` where `messages`
   is `[{:role :text} …]` (prose flattened from the captured content blocks).

   `invokeid`/`node-id` are echoed back OPAQUE for correlation. Returns:

     {:kind \"conversation\" :invokeid \"…\" :node-id \"…\" :visit 0
      :turns [{:turn 0 :model \"…\" :system \"…\" :messages [{:role :user :text \"…\"}]} …]}

   Returns the frame even with an empty `:turns` (no captures) so the UI can show
   an empty editor rather than hang."
  [store session-id {:keys [invokeid node-id visit] :or {visit 0}}]
  (let [turns (loop [t 0 acc []]
                (let [path (str (capture/turn-dir node-id visit t) "/request.edn")
                      req  (some-> (proto/read-artifact store session-id path) read-edn)]
                  (if (nil? req)
                    acc
                    (recur (inc t) (conj acc (request->turn-frame t req))))))]
    {:kind     "conversation"
     :invokeid (some-> invokeid str)
     :node-id  (some-> node-id str)
     :visit    visit
     :turns    turns}))

;; -------------------------------------------------------------------------------------------------
;; Breakpoint / turn navigation (delegates to escapement.debug.controller, task 005)  — R5/R9
;; -------------------------------------------------------------------------------------------------

(defn arm-llm-breakpoint!
  "Arm the one-shot \"break before next LLM turn\" gate (wire op `arm-llm-breakpoint`).
   No-op when `controller` is nil (normal, non-debug run)."
  [controller]
  (when controller (dbg/arm-llm-breakpoint! controller))
  nil)

(defn turn-next!
  "Release the parked worker for exactly one turn, then re-arm (wire op `turn-next`).
   No-op when `controller` is nil."
  [controller]
  (when controller (dbg/turn-next! controller))
  nil)

(defn turn-back!
  "Move the turn pointer back one (pure pointer move; wire op `turn-back`).
   No-op when `controller` is nil."
  [controller]
  (when controller (dbg/turn-back! controller))
  nil)

(defn continue!
  "Resume EVERYTHING from a debug pause (wire op `continue` routed to whichever
   gate is engaged). Releases BOTH the per-event gate (`dbg/continue!`) and the
   LLM turn gate (`dbg/turn-continue!`), per task 005's contract. No-op when
   `controller` is nil."
  [controller]
  (when controller
    (try (dbg/turn-continue! controller) (catch Throwable _ nil))
    (try (dbg/continue! controller) (catch Throwable _ nil)))
  nil)

(defn debug-frame
  "Build the EXTENDED `debug` forward frame (wire §9) reflecting current
   breakpoint/turn state. `extra` is an optional map merged last (e.g. a
   `:branch` map after `rerun-from!`, or `:paused`/`:step-budget`/`:config` from
   the per-event gate). Absent `controller` ⇒ a running, unarmed frame.

     {:kind \"debug\"
      :mode \"running\"|\"paused-at-turn\"|\"branch-running\"
      :turn-index <int>|nil :breakpoint-armed <bool>
      :branch {…}|nil  …}"
  ([controller] (debug-frame controller {}))
  ([controller extra]
   (let [armed?     (boolean (and controller (dbg/turn-armed? controller)))
         turn-index (when controller (long (or (:turn-index @controller) 0)))
         mode       (cond
                      (:branch extra)       "branch-running"
                      armed?                "paused-at-turn"
                      :else                 "running")]
     (merge {:kind             "debug"
             :mode             mode
             :turn-index       turn-index
             :breakpoint-armed armed?
             :branch           nil}
            extra))))

;; -------------------------------------------------------------------------------------------------
;; set-overrides! — stage the override draft for the next rerun  (R3/R8)
;; -------------------------------------------------------------------------------------------------

(defn- wire-msg->edn
  "Normalize one wire message `{\"role\":… \"text\":…}` (or already-keywordized
   `{:role :text}`) into the agent-side `{:role <kw> :text \"…\"}` shape that the
   `:debug/overrides :messages` prefix consumes (task 003)."
  [m]
  (let [role (or (:role m) (get m "role"))
        text (or (:text m) (get m "text"))]
    {:role (keyword (kw->wire role)) :text (str text)}))

(defn normalize-overrides
  "Translate the wire `overrides` map (from a `rerun-from` op, all keys optional —
   `alias`/`provider`/`model`/`temperature`/`system`/`messages`) into the
   agent-side `:debug/overrides` payload (task 003 contract). Provider/alias
   become keywords; model stays a string; messages become `{:role <kw> :text}`.
   Scope keys (`:node-id`/`:visit`/`:turn`) are added by `rerun-from!`."
  [overrides]
  (let [ov (or overrides {})]
    (cond-> {}
      (some? (or (:alias ov) (get ov "alias")))
      (assoc :alias (keyword (kw->wire (or (:alias ov) (get ov "alias")))))

      (some? (or (:provider ov) (get ov "provider")))
      (assoc :provider (keyword (kw->wire (or (:provider ov) (get ov "provider")))))

      (some? (or (:model ov) (get ov "model")))
      (assoc :model (str (or (:model ov) (get ov "model"))))

      (some? (or (:temperature ov) (get ov "temperature")))
      (assoc :temperature (or (:temperature ov) (get ov "temperature")))

      (some? (or (:system ov) (get ov "system")))
      (assoc :system (str (or (:system ov) (get ov "system"))))

      (seq (or (:messages ov) (get ov "messages")))
      (assoc :messages (mapv wire-msg->edn (or (:messages ov) (get ov "messages")))))))

(defn set-overrides!
  "Stage an override DRAFT on `draft-atom` (an atom the control surface keeps).
   `rerun-from!` reads it (merged under any op-supplied overrides). Returns the
   normalized payload. Pure-ish; the actual injection happens at branch run."
  [draft-atom overrides]
  (let [norm (normalize-overrides overrides)]
    (when draft-atom (reset! draft-atom norm))
    norm))

;; -------------------------------------------------------------------------------------------------
;; rerun-from! — fork a branch, apply overrides + replay policy, continue the chart  (R4/R6/R7/R8)
;; -------------------------------------------------------------------------------------------------

(defn- live-chart
  "Resolve the loaded statechart value from the live env's registry under
   `chart-id` (runner default `:escapement.runner/chart`)."
  [env chart-id]
  (try
    (when-let [reg (::sc/statechart-registry env)]
      (sp/get-statechart reg chart-id))
    (catch Throwable _ nil)))

(defn rerun-from!
  "Fork a NEW branch session from the parent's pre-conversation checkpoint, apply
   the LLM `overrides` (task 003) + the replay policy (task 004), and continue the
   chart forward on a background thread (the branch run is a normal
   `runner/run! … :resume? true` plus the two debug seams). The parent run is
   never mutated (task 002 `fork-session!` is read-only against the parent).

   `m`:
     :live        — the live `{:env :session-id :queue :controller}` from
                     `escapement.debug.control-handle/live` (REQUIRED for a real run).
     :session-id  — parent session id (defaults to `(:session-id live)`).
     :node-id     — opaque conversation node id (REQUIRED).
     :visit       — 0-based visit of that node (default 0).
     :turn        — resume turn (default 0).
     :overrides   — wire override map (optional; normalized + scoped here).
     :work-dir    — sessions root (default: parent dir of the live `:escapement/session-dir`).
     :chart-id    — chart-id key the chart is registered under (default `:escapement.runner/chart`).
     :transcript-fn — branch transcript tap (default: the live env's `:escapement/transcript-fn`,
                      so branch events flow into the SAME ws hub).
     :run-fn      — runner entry (default `requiring-resolve 'escapement.runner/run!`); injectable for tests.
     :now         — clock fn (default `System/currentTimeMillis`); injectable for tests.
     :scope       — `:node` (default) resumes the seed's OWN (sub)chart standalone
                    — for a multiplex child this re-runs just that sub-chart and
                    does NOT re-feed the aggregate. `:root` (requires
                    `:root-branch-point`) instead forks the ROOT at the multiplex
                    PARENT state and resumes the WHOLE chart multi-session, with
                    the override scoped to the selected child node — so downstream
                    phases (judging, summary) re-run and the chart finishes. The
                    root run re-applies the chart's sub-chart registration via the
                    live env's `:escapement/chart-env-ready` (stashed by the
                    runner). Lifts the prior single-session-only limitation.
     :root-branch-point — `{:node-id :visit :turn}` of the ROOT multiplex parent
                    state to fork at (required for `:scope :root`); the caller
                    (sidecar/wire) supplies it since mapping a child selection to
                    its root re-entry point is a UI concern.

   Returns the `fork-session!` result map AUGMENTED with `:branch-frame` (the
   extended `debug` frame describing the active branch) and `:future` (the
   running branch thread, or nil when no live env / chart to continue). The
   caller pushes `:branch-frame` to the sidecar via `broadcast!`."
  [{:keys [live session-id node-id visit turn overrides work-dir chart-id
           transcript-fn run-fn now scope root-branch-point]
    :or   {visit 0 turn 0
           chart-id :escapement.runner/chart
           scope    :node
           now #(System/currentTimeMillis)}}]
  (let [env        (:env live)
        ;; Two DIFFERENT sessions are in play for a multi-session (sub-chart) run:
        ;;   • `session-id` — the conversation's OWN session. For a poet/judge
        ;;     sub-chart this is the CHILD session (e.g. `:multiplex.poets.4`),
        ;;     under which its node-entry checkpoint was saved. The sidecar sends
        ;;     it as the selected live row's session-id (wire §9). The branch is
        ;;     forked (seeded) from THIS session's node-entry checkpoint.
        ;;   • `root-session-id` — the live runner/root session. The PARENT
        ;;     transcript + captured tool-results live under its on-disk dir
        ;;     (`<work-dir>/<root>/…`), so the replay index must source from it.
        ;; For a single-session run the two coincide (session-id defaults to root).
        root-session-id (:session-id live)
        session-id (or session-id root-session-id)
        session-dir (some-> env :escapement/session-dir str)
        work-dir   (or work-dir
                     (some-> session-dir
                       (->> (clojure.java.io/file))
                       (.getParentFile)
                       (.getPath)))
        ;; The CLI names the session DIR by the uuid alone while the live
        ;; session-id is the namespaced keyword `:session/<uuid>` — so the
        ;; `work-dir/<session-id>` assumption in `fork-session!` would look under
        ;; `work-dir/session/<uuid>` (the `/` nests). Hand it the REAL parent dirs
        ;; off the live env instead, so the node-entry checkpoint + parent
        ;; artifacts resolve regardless of the session-id's namespace.
        parent-ck-dir  (some-> env ::sc/working-memory-store :dir str)
        ;; ROOT scope (task: finish the whole chart): fork the ROOT session at the
        ;; multiplex PARENT state (`root-branch-point`), not the child conversation
        ;; node. The override below still scopes to the child node-id, so resuming
        ;; the root re-invokes the multiplex (re-spawning children), the override
        ;; hits only the targeted child turn, and judging/summary run fresh.
        root-resume? (and (= scope :root) (some? root-branch-point))
        branch-point (if root-resume?
                       {:node-id (str (:node-id root-branch-point))
                        :visit   (:visit root-branch-point 0)
                        :turn    (:turn root-branch-point 0)}
                       {:node-id (str node-id) :visit visit :turn turn})
        fork       (branch/fork-session!
                     (cond-> {:parent-session-id (if root-resume? root-session-id session-id)
                              :branch-point      branch-point
                              :work-dir          work-dir
                              :env               (or env {})}
                       parent-ck-dir (assoc :parent-checkpoint-dir parent-ck-dir)
                       session-dir   (assoc :parent-session-dir session-dir)))
        branch-id  (:branch-id fork)
        ;; Scope the normalized overrides to this node/visit/turn (task 003 reads
        ;; :node-id/:visit; the resume point is the seeded :messages prefix length).
        ;; The override `:node-id` must be the STATE-ID KEYWORD that
        ;; `llm_conversation` compares against `(context-element-id env)` — the
        ;; wire/branch-point form is the colon-less string ("talk"), so re-key it
        ;; to `:talk`. Mismatch here ⇒ the override silently never applies.
        ov-node-id (keyword (str/replace (str (kw->wire node-id)) #"^:+" ""))
        ;; VISIT RECONCILIATION: the selected `visit` is the parent's GLOBAL visit
        ;; (used above to LOCATE the seed checkpoint). The fresh branch has a fresh
        ;; visit counter, so the re-issued turn re-invokes at BRANCH-LOCAL visit 0.
        ;; Scope the override to 0, not the parent visit, or it silently never
        ;; matches when the selected node's parent visit was non-zero.
        ov         (merge (normalize-overrides overrides)
                     {:node-id ov-node-id :visit 0 :turn turn})
        replay     {;; The ROOT session's transcript holds every captured
                    ;; tool-result (child sub-charts have no own on-disk dir), so
                    ;; the replay index must source from the root, NOT the child
                    ;; conversation session we forked from.
                    :source       (str root-session-id)
                    ;; Sessions root so the branch worker can build the PARENT's
                    ;; combined transcript+artifact store (disk-read keyed by
                    ;; :source under :work-dir) to serve captured tool-results by
                    ;; match (escapement.debug.replay/make-index). Without it the
                    ;; index is empty ⇒ every tool runs live.
                    :work-dir     work-dir
                    :branch-point branch-point
                    :mode         :replay-then-live
                    :flag-unmatched? true}
        branch-info {:session-id   (str branch-id)
                     :parent       (str session-id)
                     :branch-point branch-point}
        branch-frame (debug-frame (:controller live) {:branch branch-info})
        ;; Resume the chart the seed BELONGS to. For a multi-session run the seed
        ;; is a sub-chart wmem (poet/judge), registered under its `::sc/statechart-src`
        ;; — NOT the root chart-id. Fall back to the caller's chart-id (single
        ;; top-level run) when the seed carries no src.
        branch-chart-id (or (:statechart-src fork) chart-id)
        chart      (live-chart env branch-chart-id)
        run!       (or run-fn
                     (try (requiring-resolve 'escapement.runner/run!) (catch Throwable _ nil)))
        tfn        (or transcript-fn (:escapement/transcript-fn env))
        fut        (when (and run! chart)
                     (future
                       (try
                         (run! (cond-> {:chart            chart
                                        :chart-id         branch-chart-id
                                        :session-id       branch-id
                                        :transcript-path  (:transcript-path fork)
                                        :checkpoint-dir   (:checkpoint-dir fork)
                                        :session-dir      (:session-dir fork)
                                        :resume?          true
                                        ;; ROOT scope pumps ALL sessions (the
                                        ;; multiplex children) and re-applies the
                                        ;; chart's sub-chart registration so the
                                        ;; re-invoked multiplex resolves them.
                                        :multi-session?   root-resume?
                                        :chart-env-ready  (when root-resume?
                                                            (:escapement/chart-env-ready env))
                                        :backend          (:escapement/llm-backend env)
                                        :tool-registry    (:escapement/tool-registry env)
                                        ;; Forward the live run's model-resolution
                                        ;; config so the branch resolves aliases /
                                        ;; preferences / resilience identically to
                                        ;; the parent (else a node that pins an alias
                                        ;; re-invokes into `:unknown-alias`).
                                        :llm-aliases      (:escapement/llm-aliases env)
                                        :llm-preferences  (:escapement/llm-preferences env)
                                        :llm-default-models (:escapement/llm-default-models env)
                                        :llm-catalog-ratings (:escapement/llm-catalog-ratings env)
                                        :llm-eligibility-strict? (:escapement/llm-eligibility-strict? env)
                                        :debug-overrides  ov
                                        :debug-replay-policy replay}
                                 ;; `run!` taps branch transcript events via
                                 ;; `:transcript-tap` (NOT `:transcript-fn`, which
                                 ;; is not a run! arg) — this is the seam the live
                                 ;; ws-push hub subscribes to, so branch events flow
                                 ;; into the SAME sidecar stream as the parent.
                                 tfn (assoc :transcript-tap tfn)))
                         (catch Throwable t
                           (when tfn
                             (try (tfn {:event :debug/branch-error :ts (now)
                                        :data  {:session-id (str branch-id)
                                                :error      (.getMessage t)}})
                                  (catch Throwable _ nil)))
                           nil))))]
    (assoc fork
      :branch-frame branch-frame
      :branch       branch-info
      :overrides    ov
      :replay       replay
      :future       fut)))
