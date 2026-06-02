(ns escapement.lib
  "Hosted facade for embedding Escapement as a library.

  This is the public, stable entry point for embedding the engine inside
  another application (the CLI keeps using `escapement.runner/run!` directly
  with its own explicit behavior). The facade is a thin, additive delegation
  over `runner/run!` that adds the ergonomics a host needs:

   * A **closed** Malli option schema — the public API contract. Unknown
     option keys are rejected (enforced unconditionally in this fn, not just
     under guardrails).
   * A generated, stable `:run-id` returned in the result map and emitted on
     the `:runner/started` transcript event so every public event can be
     correlated back to a single run.
   * `:transcript-path` / `:checkpoint-dir` default to a fresh temp directory
     when omitted, so a host can run a chart without managing files. (The CLI
     path keeps its explicit, required transcript/checkpoint behavior.)
   * `:store` is threaded through to `engine.env/new-env`.
   * Quiet logging by default for hosted mode: the statecharts-impl Timbre
     DEBUG/INFO chatter is suppressed to stderr (CLI verbosity is untouched).
   * A `:cancel` signal key (atom/promise) is part of the closed contract and
     is honored: it is forwarded to `runner/run!`, which checks it at a safe
     pump boundary and aborts cleanly, surfacing `:status :aborted`.

  ## Hermetic configuration & credentials (Step 4)

  The facade is **hermetic**: it never reads `.escapement.edn` from disk and
  never sniffs credential env vars. A host configures everything once and
  injects it as explicit data on every `run`:

   * `:credentials` — **required**: an ordered vector of credential descriptor
     maps (`[{:provider :z-ai-plan :subscription true}
     {:provider :anthropic :api-key \"sk-...\"}
     {:provider :openai :api-key \"sk-...\" :base-url \"...\"}]`). The backend
     is assembled from these via
     `escapement.llm.providers/build-injected-credentials-backend` (no env, no
     disk). An explicit `:backend` is an escape hatch that wins verbatim.
   * `:config` — optional: the `.escapement.edn`-shaped map
     (`:llm/preferences`, `:llm/ratings`, `:llm/eligibility-strict?`). Absent
     ⇒ an empty ratings table (`{}`) and the built-in
     `escapement.llm.preferences/default-preferences` order — never a disk
     fallback. The resolved ratings / preference-ordered model list / strict
     flag are threaded into `runner/run!` via the Step 2 injection seam
     (`:catalog-ratings`, `:backend-default-models`, `:eligibility-strict?`).

  Two `run` calls in the same process with different `:config` ratings resolve
  the `:needs` eligibility gate independently — there is no process global.

  Public entry: `run`."
  (:require
    [babashka.fs :as fs]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn ?]]
    [escapement.llm.preferences :as preferences]
    [escapement.llm.providers :as providers]
    [escapement.llm.ratings :as ratings]
    [escapement.runner :as runner]
    [malli.core :as m]
    [malli.error :as me]
    [taoensso.timbre :as log])
  (:import
    (java.util UUID)))

;; ---------------------------------------------------------------------------
;; Public option schema  (THE PUBLIC API CONTRACT — closed)
;; ---------------------------------------------------------------------------

(def Options
  "Closed Malli schema for the hosted facade options map. Unknown keys are
  rejected. This schema is the public API contract for embedding Escapement;
  the event sink, docs, and cancellation all build on it."
  [:map {:closed true}
   ;; --- required ---
   [:chart :any]                                            ; statechart value (from chart/statechart)
   [:session-id :any]                                       ; session id for this run (keyword/string/uuid)
   ;; --- required: injected credentials (app-lifetime, like a DB pool) ---
   ;; An ordered vector of credential descriptor maps. Each names a
   ;; `:provider` (matching the provider keyword in
   ;; `escapement.llm.providers/provider-templates`) plus the env-free
   ;; secrets/overrides that provider needs. The backend is assembled from
   ;; these (unless an explicit `:backend` escape hatch is supplied). Closed +
   ;; required ⇒ omitting it fails validation with the clear ex-info path.
   [:credentials
    [:vector
     [:map
      [:provider :keyword]                                  ; e.g. :z-ai-plan :anthropic :openai
      [:api-key {:optional true} [:maybe :string]]
      [:base-url {:optional true} [:maybe :string]]
      [:model {:optional true} [:maybe :string]]
      [:default-model {:optional true} [:maybe :string]]
      [:auth-mode {:optional true} :any]
      [:subscription {:optional true} :any]]]]
   ;; --- optional: hermetic config (`.escapement.edn`-shaped map) ---
   ;; `:llm/preferences`, `:llm/ratings`, `:llm/eligibility-strict?`. Kept
   ;; permissive (a plain map) — internal keys are not over-constrained. Absent
   ;; ⇒ empty ratings + built-in default preference order (never disk).
   [:config {:optional true} [:maybe :map]]
   ;; --- optional: backend / tools / data ---
   [:backend {:optional true} :any]                         ; an LLMBackend escape hatch; wins verbatim over :credentials assembly
   [:tool-registry {:optional true} :any]                   ; tool registry atom
   [:initial-data {:optional true} [:maybe :map]]           ; seed data-model
   ;; --- optional: host callbacks ---
   [:transcript-tap {:optional true} fn?]                   ; (fn [event]) — receives every transcript row in-process
   [:on-env-ready {:optional true} fn?]                     ; (fn [env])   — called once env is built, before chart start
   ;; --- optional: transcript / checkpoint (default to a fresh temp dir) ---
   [:transcript-path {:optional true} :string]              ; JSONL output path; defaults to <tmp>/transcript.jsonl
   [:checkpoint-dir {:optional true} :string]               ; checkpoint dir;   defaults to <tmp>/checkpoints
   [:session-dir {:optional true} :string]                  ; artifact/session dir; defaults to <tmp>
   ;; --- optional: store passthrough ---
   [:store {:optional true} :any]                           ; working-memory store override → engine.env/new-env
   ;; --- optional: logging ---
   [:quiet? {:optional true} :boolean]                      ; default true — suppress statecharts-impl DEBUG stderr
   ;; --- optional: cancellation (honored: forwarded to runner/run!) ---
   [:cancel {:optional true} :any]                          ; atom/promise; truthy ⇒ runner aborts at a safe pump boundary
   ;; --- optional: passthrough knobs (forwarded verbatim to runner/run!) ---
   [:chart-id {:optional true} :any]
   [:resume? {:optional true} :boolean]
   [:trace? {:optional true} :boolean]
   [:max-iterations {:optional true} :int]
   [:quiescent-sleep-ms {:optional true} :int]])

(>defn validate-options
  "Returns nil when `opts` matches the closed `Options` schema; otherwise a
   humanized error map. Exposed so hosts (and tests) can validate without
   running."
  [opts]
  [:any => (? :any)]
  (when-not (m/validate Options opts)
    (me/humanize (m/explain Options opts))))

;; ---------------------------------------------------------------------------
;; Quiet logging (hosted-mode default)
;; ---------------------------------------------------------------------------

(defn- with-quiet-logging
  "Run `thunk` with Timbre's min level raised to :warn so the statecharts-impl
  DEBUG/INFO chatter does not flood the host's stderr. Restores the prior
  Timbre config afterwards. CLI verbosity is untouched (the CLI never calls
  this facade)."
  [thunk]
  (let [prior log/*config*]
    (try
      (log/set-min-level! :warn)
      (thunk)
      (finally
        (alter-var-root #'log/*config* (constantly prior))))))

;; ---------------------------------------------------------------------------
;; Public entry
;; ---------------------------------------------------------------------------

(>defn run
  "Run `chart` to quiescence through the hosted facade.

  `opts` is validated against the closed `Options` schema (unknown keys
  are rejected). See the `Options` var for the full contract.

  Returns the `runner/run!` summary map augmented with:
   * `:run-id`         — the generated stable run id (string UUID)
   * `:transcript`     — the transcript path used (temp dir if defaulted)
   * `:checkpoint-dir` — the checkpoint dir used (temp dir if defaulted)
   * `:session-id`, `:final-config`, `:env` — as returned by `runner/run!`

  The `:run-id` is also emitted on the `:runner/started` transcript event
  so every public event can be correlated to this run."
  [opts]
  [:map => :map]
  (when-let [errs (validate-options opts)]
    (throw (ex-info "escapement.lib/run: invalid options (closed schema rejected the map)"
             {:errors errs :provided-keys (vec (keys opts))})))
  (let [{:keys [chart session-id credentials config backend tool-registry
                initial-data transcript-tap on-env-ready transcript-path
                checkpoint-dir session-dir store quiet? cancel chart-id resume? trace?
                max-iterations quiescent-sleep-ms]
         :or   {quiet? true}} opts
        ;; --- Hermetic resolution: ONLY from injected `:config` /
        ;; `:credentials`. Zero `config/load-config`, zero
        ;; `System/getenv`. Absent `:config` ⇒ `{}` ratings + built-in
        ;; `default-preferences`; never a disk fallback. The one-arg
        ;; (config-map) arities of `preferences`/`ratings` do not touch
        ;; disk; passing `{}` yields the documented empty/default
        ;; behavior.
        cfg             (or config {})
        resolved-prefs  (preferences/preferences cfg)
        ;; `:llm/aliases` falls back to the built-in `default-aliases` so the
        ;; no-config path resolves THROUGH the alias layer (R7).
        llm-aliases     (preferences/aliases-from-config cfg)
        ;; Flatten the preference aliases' targets into the ordered model-id
        ;; fallback list the processor consumes when a node pins no `:model`.
        default-models  (preferences/model-order resolved-prefs llm-aliases)
        catalog-ratings (ratings/ratings cfg)
        strict?         (boolean (or (:llm/eligibility-strict? cfg)
                                   (get-in cfg [:llm :eligibility-strict?])))
        ;; Backend assembly: explicit `:backend` escape hatch wins
        ;; verbatim; otherwise build hermetically from `:credentials`
        ;; ordered by the resolved preference list.
        ;; Route ordering: `build-injected-credentials-backend` ranks
        ;; providers by their first appearance in this list. Feed it the
        ;; FLATTENED preference-alias targets (which carry `:provider`) so the
        ;; ordering derives from the preference aliases' providers (R8). Task
        ;; 003 may formalize this inside `providers.clj`; the data shape it
        ;; receives is the alias-flattened target list.
        pref-targets    (preferences/flatten-targets resolved-prefs llm-aliases)
        backend         (or backend
                          (providers/build-injected-credentials-backend
                            credentials pref-targets))
        run-id          (str (UUID/randomUUID))
        tmp-dir         (when (or (nil? transcript-path) (nil? checkpoint-dir))
                          (str (fs/create-temp-dir {:prefix "escapement-run-"})))
        t-path          (or transcript-path (str (fs/path tmp-dir "transcript.jsonl")))
        ckpt-dir        (or checkpoint-dir (str (fs/path tmp-dir "checkpoints")))
        sess-dir        (or session-dir tmp-dir)
        run-opts        (cond-> {:chart                  chart
                                 :session-id             session-id
                                 :transcript-path        t-path
                                 :checkpoint-dir         ckpt-dir
                                 :session-dir            sess-dir
                                 :run-id                 run-id
                                 :store                  store
                                 ;; Step 2 injection seam: resolved ONCE here,
                                 ;; threaded as plain values (no global).
                                 :catalog-ratings        catalog-ratings
                                 :llm-aliases            llm-aliases
                                 :llm-preferences        resolved-prefs
                                 :backend-default-models default-models
                                 :eligibility-strict?    strict?}
                          backend (assoc :backend backend)
                          tool-registry (assoc :tool-registry tool-registry)
                          initial-data (assoc :initial-data initial-data)
                          transcript-tap (assoc :transcript-tap transcript-tap)
                          on-env-ready (assoc :on-env-ready on-env-ready)
                          chart-id (assoc :chart-id chart-id)
                          resume? (assoc :resume? resume?)
                          trace? (assoc :trace? trace?)
                          max-iterations (assoc :max-iterations max-iterations)
                          quiescent-sleep-ms (assoc :quiescent-sleep-ms quiescent-sleep-ms)
                          ;; `:cancel` is part of the closed public contract and
                          ;; is honored: forwarded to `runner/run!`, which aborts
                          ;; at a safe pump boundary and returns `:status :aborted`.
                          cancel (assoc :cancel cancel))
        do-run          (fn [] (runner/run! run-opts))
        result          (if quiet?
                          (with-quiet-logging do-run)
                          (do-run))]
    (assoc result
      :run-id run-id
      :transcript t-path
      :checkpoint-dir ckpt-dir
      :session-dir sess-dir)))
