(ns escapement.llm
  "Public facade for one-shot and fan-out LLM calls, and the shared
   model-resolution + single-turn failover engine used by BOTH chart scripts
   and the `:llm-conversation` invocation worker.

   Two layers:

   * **Resolution + single turn** — `resolve-candidates` (keyword `:model`/
     `:models` aliases → ordered cross-provider candidate list, with
     `:needs`/eligibility filtering) and `run-turn` (issue ONE assistant turn,
     retrying transient errors and failing over across candidates). These are
     the canonical home; `escapement.invocation.llm-conversation` delegates to
     them so resolution + retry semantics live in exactly one place. `run-turn`
     takes optional `:hooks` so the worker can keep its transcript/capture
     concerns while sharing the control flow.

   * **Script sugar** — `ask`/`ask*` (one prompt → one answer), `elect-model`
     (resolve + verify a working model ONCE, returning a reusable *pinned*
     ctx), and `map-prompt` (bounded-concurrency fan-out of the same prompt
     over a collection, electing the model by doing the first item). Every
     public call returns a uniform result ENVELOPE so a caller can branch on
     per-item status (e.g. a batch that runs out of credit mid-run).

   bb-safe, JVM/bb-only (blocking `p/await!` + `future` for fan-out, exactly
   like the sibling worker). Requires only the LLM core + statechart promise;
   pulls NO web/Pathom/RAD/TUI code.

   --- Result envelope -------------------------------------------------------
   {:status   :ok            ; or :exhausted | :eligibility-empty
                             ;    | :unknown-alias | :string-model
                             ;    | :string-models | :interrupted
    :response <payload>      ; :ok → text (ask) / Response (ask*) / pinned ctx
                             ;       (elect-model); nil on failure
    :model    \"claude-…\"     ; model that actually ran (when known)
    :usage    {…}            ; token usage on success
    :error    {…}}           ; failure detail (category/message/attempts, …)

   --- Model selection -------------------------------------------------------
   `:model` / `:models` are ALIAS KEYWORDS resolved via `:llm/aliases` +
   `:llm/preferences` exactly as charts resolve them — a STRING model is a
   categorized `:string-model` error, never shipped to a backend. The
   env-aware arities pull the session's alias/preference matrix off the env so
   a script gets full resolution for free."
  (:require
    [com.fulcrologic.statecharts.promise :as p]
    [escapement.llm.catalog :as catalog]
    [escapement.llm.needs :as needs]
    [escapement.llm.preferences :as preferences]
    [escapement.llm.prompt-cache :as prompt-cache]
    [escapement.llm.protocol :as proto]))

(defn- now-ms [] (System/currentTimeMillis))

;; ===========================================================================
;; Response helpers
;; ===========================================================================

(defn response-text
  "Concatenate the text of every `:type :text` block in `response`'s `:content`
   into a single string. Non-text blocks (tool_use, thinking, …) are ignored."
  [response]
  (->> (:content response)
    (filter #(= :text (:type %)))
    (map :text)
    (apply str)))

;; ===========================================================================
;; Request assembly
;; ===========================================================================

(defn build-request
  "Assemble an `escapement.llm.types/Request` map. Caller may supply any of the
   common Anthropic Messages-API knobs and they will be passed through to the
   backend; missing keys are simply not put on the wire.

   Recognized keys (all optional unless noted):

     * `:system` — system prompt string
     * `:messages` — required; the running conversation
     * `:tools` — vector of tool definitions
     * `:model` — model id string (e.g. `\"claude-opus-4-7\"`)
     * `:max-tokens` — int output cap. NOT a chart param: the invocation
                       supplies the resolved model's catalog
                       `max-output-tokens` (models-api.json `limit.output`).
                       Falls back to the backend's wire default for models
                       the catalog doesn't know.
     * `:temperature` — number in (0,1]
     * `:top-p` — number in (0,1]
     * `:top-k` — pos-int
     * `:stop-sequences` — vector of strings
     * `:thinking` — `{:type :enabled :budget-tokens N}` to turn on
                     extended thinking. Requires `:max-tokens` > `N`.
     * `:tool-choice` — `:auto` | `:any` | `:none` | `{:type :tool :name \"...\"}`
     * `:metadata` — `{:user-id \"...\"}`
     * `:extra-body` — provider escape hatch: a `{string value}` map merged
                       verbatim onto the serialized request body. Only the
                       OpenAI Chat Completions wire layer honors it (Anthropic
                       drops it). Caller keys win over framework-produced ones.
                       Example: `{\"chat_template_kwargs\" {\"enable_thinking\" false}}`
                       to disable Qwen/llama.cpp reasoning. NOT part of the
                       content-cache key.
     * `:auto-cache?` — boolean, default `true`. When true, fills in
       `:system-cache-control` and `:tools-cache-control` with
       `{:type :ephemeral}` if the caller didn't set them. Set to `false`
       to fully disable prompt-cache markers (use this for guaranteed
       single-turn states where the cache-write surcharge has no payoff).
     * `:system-cache-control` — Anthropic prompt-cache marker on the system
       block. Defaulted to `{:type :ephemeral}` via `:auto-cache?`; pass a
       map (e.g. `{:type :ephemeral :ttl :1h}`) to override or `false` to
       disable just this marker.
     * `:tools-cache-control` — Same shape; applies to the LAST entry in
       `:tools` so the prefix-through-end of `tools` is cached. Defaulted
       to `{:type :ephemeral}` via `:auto-cache?`; pass `false` to disable.
     * `:message-cache-control` — rolling prompt-cache breakpoint(s) on the
       growing `:messages` transcript (so long conversations cache the bulk
       of their context instead of re-prefilling every turn). Defaulted ON
       under `:auto-cache?` (strategy `:last-stable`, ttl `:5m`). Pass
       `false` to disable JUST message caching, or a map
       `{:strategy <:last-stable | {:tail N}> :ttl <:5m | :1h>}` to tune it.
       Placement is provider-neutral and coordinated with the system/tools
       markers so the TOTAL never exceeds Anthropic's 4-breakpoint cap: the
       message strategy may only consume the budget left after system/tools.
       The NEWEST inbound turn is never marked. See `escapement.llm.prompt-cache`.
     * `:conv-id` — conversation correlation id; used as prompt cache key by openai-codex (string/keyword/uuid)"
  [{:keys [system messages tools model max-tokens conv-id
           temperature top-p top-k stop-sequences thinking tool-choice metadata
           extra-body
           system-cache-control tools-cache-control message-cache-control auto-cache?]
    :or   {auto-cache? true}}]
  ;; Auto-cache defaulting: an absent (== `nil`) cache-control marker
  ;; becomes ephemeral when :auto-cache? is true. Anthropic ignores
  ;; cache_control on prompts below ~1024 tokens, so the default is free
  ;; for small prompts and a clear win for any multi-turn state with a
  ;; substantial prefix. Pass `:auto-cache? false` to fully opt out, or
  ;; pass an explicit map / `false` on either individual key.
  (let [system-cache-control (cond
                               (false? system-cache-control) nil
                               (some? system-cache-control) system-cache-control
                               auto-cache? {:type :ephemeral}
                               :else nil)
        tools-cache-control  (cond
                               (false? tools-cache-control) nil
                               (some? tools-cache-control) tools-cache-control
                               auto-cache? {:type :ephemeral}
                               :else nil)
        ;; Rolling MESSAGE-level breakpoint config, mirroring the system/tools
        ;; defaulting `cond`: `false` disables just messages; an explicit map
        ;; is honored; otherwise messages cache by default under :auto-cache?.
        ;; Resolves to a `prompt-cache` opts map (`nil` => no message markers).
        message-cache        (cond
                               (false? message-cache-control) nil
                               (map? message-cache-control)    message-cache-control
                               auto-cache?                     {}
                               :else                           nil)
        ;; The static prefix markers (system + last tool) consume breakpoints
        ;; first; messages may only use what's left of Anthropic's 4-cap. We do
        ;; NOT branch on provider — the wire layer serializes (Anthropic) or
        ;; drops (OpenAI, etc.) the markers regardless.
        used                 (+ (if system-cache-control 1 0)
                               (if (and (seq tools) tools-cache-control) 1 0))
        remaining-budget     (max 0 (- 4 used))
        messages             (if message-cache
                               (prompt-cache/place-message-breakpoints
                                 (vec messages)
                                 (merge {:remaining-budget remaining-budget}
                                   message-cache))
                               messages)]
    ;; Note: :model is omitted from the Request when the caller didn't supply
    ;; one. The backend's `send-turn` fills it from its configured
    ;; `:default-model` before schema validation. That way the chart can simply
    ;; not set :model and the runner's chosen default wins, instead of every
    ;; chart silently locking onto a stale hardcoded model.
    (cond-> {:messages messages
             :tools    (if (and (seq tools) tools-cache-control)
                         ;; Anthropic caches the PREFIX up through the last
                         ;; cache_control marker. Stamping the last tool with
                         ;; the marker therefore caches all tool defs.
                         (-> (vec tools)
                           (update (dec (count tools))
                             assoc :cache-control tools-cache-control))
                         tools)}
      model (assoc :model model)
      system (assoc :system system)
      max-tokens (assoc :max-tokens max-tokens)
      system-cache-control (assoc :system-cache-control system-cache-control)
      (some? temperature) (assoc :temperature temperature)
      (some? top-p) (assoc :top-p top-p)
      (some? top-k) (assoc :top-k top-k)
      (seq stop-sequences) (assoc :stop-sequences (vec stop-sequences))
      thinking (assoc :thinking thinking)
      (some? tool-choice) (assoc :tool-choice tool-choice)
      (seq metadata) (assoc :metadata metadata)
      (seq extra-body) (assoc :extra-body extra-body)
      conv-id (assoc :conversation/id conv-id))))

(defn effective-max-tokens
  "The per-turn output cap for `model`, taken purely from the catalog's
   `max-output-tokens` (sourced from models-api.json `limit.output`).
   `max_tokens` is an API/model fact, not a chart concern: charts never set
   it. Returns nil for models the catalog doesn't know, in which case the
   backend's own hard default applies."
  [model]
  (some-> model catalog/max-output-tokens))

;; ===========================================================================
;; Model resolution (keyword aliases → ordered candidates, with :needs gate)
;; ===========================================================================

(defn- policy-nonempty?
  "True when a canonical policy expresses at least one clause."
  [pol]
  (boolean (and pol (or (seq (:require pol)) (seq (:min pol)) (seq (:max pol))))))

(defn params->policy
  "The canonical declarative model policy from conversation `params`,
   resolved from the `:needs` chart-node surface key — the ergonomic flat
   `fact → constraint` map (bare value, `[:>= n]`, `[:<= n]`). Translated
   to the canonical `{:require/:min/:max}` shape via
   `escapement.llm.needs/needs->policy`.

   Returns nil when no policy clause is expressed. A malformed `:needs`
   throws (see `needs->policy`)."
  [params]
  (let [pol (when (contains? params :needs)
              (needs/needs->policy (:needs params)))]
    (when (policy-nonempty? pol)
      pol)))

(defn- alias-target->candidate
  "Normalize a validated `:llm/aliases` target map into the uniform candidate
   shape consumed by `run-turn`. `:provider`/`:model` route+identify the
   target; `:params` carries this target's optional generation overrides
   (`:temperature`/`:top-p`/`:top-k`/`:thinking`/`:max-tokens`) that are merged
   UNDER the node's explicit params (node wins) on its attempt."
  ([target] (alias-target->candidate target nil))
  ([{:keys [provider model] :as target} alias]
   {:provider provider
    :model    model
    :alias    alias
    :params   (select-keys target [:temperature :top-p :top-k :thinking :max-tokens])}))

(defn- node-alias-vector
  "Normalize a node's model selection into a vector of alias keywords.

   A KEYWORD is the ONLY legal model form. Resolution:
     * `params :models` (a vector of alias keywords) — explicit ordered set
     * `params :model`  (a single alias keyword)     — explicit single pick
     * neither                                        — nil (caller falls back
                                                         to `:llm/preferences`)

   Any STRING — a bare string `:model` or a string element inside `:models` —
   is a categorized configuration/chart error, NEVER a passthrough to a
   backend (R4). Returns one of:
     {:aliases [kw …]}            — normalized alias-keyword vector (or nil)
     {:string-model s}            — string `:model` (categorized error)
     {:string-models [el …] :bad} — `:models` contained non-keyword element(s)"
  [params]
  (let [m  (:model params)
        ms (:models params)]
    (cond
      (seq ms)
      (if (every? keyword? ms)
        {:aliases (vec ms)}
        {:string-models (vec ms)
         :bad           (vec (remove keyword? ms))})

      (some? m)
      (if (keyword? m)
        {:aliases [m]}
        {:string-model m})

      :else {:aliases nil})))

(defn resolve-candidates
  "Produce the ordered candidate list for the next turn as a vector of uniform
   maps `{:provider :model :params}` consumed by `run-turn`.

   KEYWORD-ONLY model form (R4/R5). A node selects models exclusively by alias
   keyword: a single `:model` keyword, or a `:models` vector of alias keywords.
   When the node names NO model, the alias vector is `:llm/preferences` (an
   ordered vector of alias keywords; falls back to the built-in
   `default-preferences`). Both paths normalize to \"a vector of alias
   keywords\", which is then flattened — each alias's ordered target list
   (author order) concatenated — into the ordered candidate sequence. Every
   target carries its own provider, provider-specific model id, and optional
   generation params.

   A STRING anywhere (a bare string `:model`, or a string element inside
   `:models`) is a categorized configuration/chart error — NEVER shipped to a
   backend.

   `:needs` TARGET-GRANULARITY filtering (R6). When `policy` is non-empty,
   each flattened candidate TARGET is evaluated against the policy: objective
   facts are looked up per `:provider`+`:model` from the catalog, while the
   subjective overlay is the candidate's source ALIAS's rating (looked up by
   ALIAS keyword in `catalog-ratings`). Ineligible targets are dropped; an
   alias survives if ≥1 of its targets is eligible; an alias whose targets all
   fail drops out entirely; surviving targets keep author order. The fail-open
   vs `eligibility-strict?` decision is applied to the resulting flattened list
   (matching today's semantics): when the filter empties the list, the
   UNFILTERED candidates are used unless `eligibility-strict?` — in which case
   `:eligibility-empty` is returned.

   Returns one of:
     {:candidates [cand …]}              — flattened candidate vector
     {:eligibility-empty {…}}            — strict + `:needs` excluded every target
     {:unknown-alias kw :known […]}      — alias keyword absent from `aliases`
     {:string-model s}                   — illegal string `:model`
     {:string-models [el …] :bad […]}    — illegal string element(s) in `:models`

   The 4-arg arity is a no-`:needs` convenience (no policy, no ratings, not
   strict) for callers/tests that only exercise alias expansion + dispatch."
  ([params model-status preferences aliases]
   (resolve-candidates params model-status preferences aliases nil {} false))
  ([params model-status preferences aliases policy catalog-ratings eligibility-strict?]
   (let [norm (node-alias-vector params)]
     (cond
       (contains? norm :string-model)  norm
       (contains? norm :string-models) norm

       :else
       (let [explicit? (boolean (seq (:aliases norm)))
             alias-kws (if explicit? (:aliases norm) (vec preferences))
             ;; An EXPLICITLY named alias keyword (node `:model`/`:models`) absent
             ;; from `:llm/aliases` is a categorized error (R5) — never silently
             ;; degraded. The preference fallback path does NOT hard-error on a
             ;; dangling keyword (referential integrity is enforced at config
             ;; load time; the empty-config/test path simply has no targets and
             ;; falls through to the backend-default candidate below).
             unknown   (when explicit?
                         (first (remove (set (keys aliases)) alias-kws)))]
         (if unknown
           {:unknown-alias unknown :known (vec (keys aliases))}
           ;; Flatten alias keywords → candidates, TAGGING each with its source
           ;; alias so the subjective rating can be read by alias keyword (R6).
           (let [tagged  (->> alias-kws
                           (mapcat (fn [k] (map #(alias-target->candidate % k)
                                             (get aliases k))))
                           (filter :model)   ;; drop nil-target slots (none today)
                           distinct
                           vec)
                 ;; R6 target-granularity `:needs` gate: keep targets whose
                 ;; provider+model (objective) + their alias's rating (subjective,
                 ;; by alias keyword) satisfy the policy. An alias survives iff
                 ;; ≥1 of its targets survives (implicit: surviving targets carry
                 ;; the alias). Author order preserved.
                 eligible (if policy
                            (filterv
                              (fn [c]
                                (catalog/target-satisfies-policy?
                                  (:model c) policy (get catalog-ratings (:alias c))))
                              tagged)
                            tagged)
                 ;; Fail-open vs strict on the RESULTING flattened list (matches
                 ;; today's semantics): empty-after-filter → unfiltered list,
                 ;; unless strict (caller surfaces a categorized error).
                 gated-empty? (and policy (seq tagged) (empty? eligible))
                 cands    (if gated-empty? tagged eligible)
                 status   @model-status
                 live     (filterv #(not= :down (get status (:model %))) cands)]
             (if (and gated-empty? eligibility-strict?)
               {:eligibility-empty {:policy policy
                                    :candidates (mapv #(select-keys % [:provider :model :alias]) tagged)}}
               ;; No resolvable targets (no model named AND preferences don't
               ;; flatten — e.g. empty-config / no `:llm/aliases`): hand the
               ;; backend a single nil-model candidate so it picks its own default.
               {:gated-empty? (boolean gated-empty?)
                :policy       policy
                :candidates   (cond
                                (seq live)  live
                                (seq cands) cands
                                :else       [{:provider nil :model nil :params nil}])}))))))))

;; ===========================================================================
;; Resilience: transient-error retry
;; ===========================================================================

(def default-resilience
  "Applied when a caller omits (or partially specifies) `:resilience`. Recovery
   is ON by default and per-call tunable; `:max-retries 0` disables retry.

   `:overrun` is the OUTPUT-TOKEN-CEILING primitive. When a turn's output is
   forcibly truncated at the cap (`stop-reason :max_tokens`), rerun the SAME
   turn with the IDENTICAL context up to `:max-retries` times instead of
   letting a runaway model stream forever (or stitch an unbounded
   continuation). It is OFF by default (`:overrun {:max-retries 0}`) so
   existing behavior is unchanged. Keys:

     * `:max-output-tokens` — the per-turn cap that serves as the trip wire.
       Also used as a `:max-tokens` FALLBACK on the request when the catalog
       doesn't know the model (so a local/unknown model can't run away in a
       single segment). nil ⇒ rely on the explicit/catalog cap only.
     * `:max-retries` — how many times to rerun on truncation (0 = disabled).
     * `:on-exhausted` — once retries are spent and the turn is still
       truncated: `:truncate` (default — accept the truncated turn as `:ok`)
       or `:fail` (surface a `:status :overrun` failure envelope).
     * `:temperature-bump` — optional per-rerun temperature increment. A
       deterministic model (temperature 0) would re-truncate on an identical
       rerun forever; bumping temperature each rerun (attempt N adds
       `N × bump` to the base temperature, clamped to `:temperature-max`)
       forces sampling variance so a different, terminating output can emerge.
       nil ⇒ rerun with the identical request (no bump).
     * `:temperature-max` — clamp for the bumped temperature (default 1.0).

   A plain rerun only helps when sampling already varies the output
   (temperature > 0); pair `:temperature-bump` with a deterministic model so a
   runaway can actually break out instead of re-truncating until
   `:max-retries` is hit and `:on-exhausted` decides.

   `:latency` is the TIME-TO-FIRST-TOKEN cap primitive — fail over to a faster
   provider when a backend is slow to START responding. OFF by default
   (`:first-token-ms nil`); opt in per node. Keys:

     * `:first-token-ms` — abandon the in-flight turn and fail over to the next
       candidate if no first token arrives within this many ms. Measures TTFT
       (model load + queue + prompt eval), NOT total generation: once the first
       token lands the cap is moot and the turn runs to completion. A breach is
       NOT a fault — the slow model is NOT marked `:down`, so it stays in
       rotation for later turns. With no streaming delta sink (no token signal)
       the cap degrades to a total-response cap. nil ⇒ disabled.
     * `:fallback` — optional ordered vector of `{:provider :model …}` targets
       (same shape as an `:llm/aliases` target) APPENDED to the resolved
       candidate list, so a breach (or a hard error) on the primary descends
       this chain. Inline convenience; a multi-target alias works too. Only
       honored when `:first-token-ms` is set. The cap applies to each candidate;
       if the LAST candidate is slow with nowhere left to switch, the turn rides
       it out (a slow answer beats no answer)."
  {:max-retries 3 :backoff-ms 500
   :latency     {:first-token-ms nil :fallback nil}
   :overrun     {:max-output-tokens nil :max-retries 0 :on-exhausted :truncate
                 :temperature-bump nil :temperature-max 1.0}})

(def transient-error-categories
  "Backend error categories that warrant a bounded automatic retry of the same
   model. The remaining categories (`:auth` `:invalid-request`
   `:context-length`) are terminal: they fail fast and are never retried, so a
   bad key or oversized prompt cannot burn quota in a retry loop."
  #{:rate-limited :overloaded :timeout :transport})

(defn params->resilience
  "Merge a caller's `:resilience` over `default-resilience`."
  [params]
  (merge default-resilience (:resilience params)))

(defn backoff-delay-ms
  "Exponential backoff for retry `attempt` (0-based) off `base` ms, honoring an
   explicit `:retry-after-ms` from the throwable's ex-data (e.g. a 429
   Retry-After) when present."
  [base attempt ^Throwable t]
  (let [ra (:retry-after-ms (ex-data t))]
    (if (and (number? ra) (not (neg? ra)))
      (long ra)
      (long (* (long base) (Math/pow 2 attempt))))))

(defn- sleep-while-alive!
  "Sleep up to `ms`, waking early if `alive?` turns false. Sliced so a stop
   signal is honored promptly during backoff."
  [ms alive?]
  (let [deadline (+ (now-ms) (long ms))]
    (loop []
      (let [left (- deadline (now-ms))]
        (when (and (pos? left) (alive?))
          (Thread/sleep (long (min 100 left)))
          (recur))))))

(defn- await-turn!
  "Issue a backend turn via the `send` THUNK (it calls `send-turn*` and returns
   the turn promise), optionally enforcing a time-to-first-token cap.

   CRITICAL — why `send` is a thunk and not a promise: the CLJ/bb backends stream
   SYNCHRONOUSLY. Their `stream-turn` runs inline under `p/do!` and blocks in
   `p/await!` until the SSE stream ends (`openai.clj`/`api.clj`). If we evaluated
   `send-turn*` on THIS thread and merely wrapped the resulting promise, the whole
   turn would already have blocked here before the timeout could fire — the cap
   would be a no-op (it was, until this was fixed). With a cap set we therefore
   invoke `send` ON the side future, so the main thread can race the first-token
   deadline against a still-in-flight stream. Deltas land via `on-delta` from the
   transport's OWN thread, stamping `first-tok` concurrently.

   With `cap-ms` nil this is a plain blocking `p/await!` on the calling thread —
   zero added threads, the latency feature is off. With `cap-ms` set:

     * resolves within the cap                   → return the Response
     * cap elapses but a first token has arrived → ride it out (a real
       generation is underway; the backend's own HTTP timeout backstops a hang)
     * cap elapses, no first token, fallback?    → flip `abandoned` (so the
       delta sink drops late stragglers from this now-orphaned stream) and
       return the `::too-slow` sentinel so the caller fails over WITHOUT marking
       the slow model `:down`
     * cap elapses, no first token, no fallback  → ride it out (a slow answer
       beats no answer when there is nowhere to switch)

   A side-future deref wraps a thrown rejection in `ExecutionException`; we
   unwrap so the caller sees the SAME throwable a plain `p/await!` would raise
   (preserving error categorization + interrupt detection). NOTE: an abandoned
   turn's future + HTTP connection live until the backend timeout — negligible
   for the one-turn-at-a-time worker, but a `map-prompt` fan-out that breaches
   many candidates can pile up parked threads until they drain."
  [send cap-ms first-tok abandoned fallback?]
  (if-not cap-ms
    (p/await! (send))
    (let [f      (future (p/await! (send)))
          unwrap (fn [e] (throw (or (ex-cause e) e)))
          v      (try (deref f cap-ms ::pending)
                      (catch java.util.concurrent.ExecutionException e (unwrap e)))]
      (cond
        (not (identical? v ::pending)) v
        @first-tok (try @f (catch java.util.concurrent.ExecutionException e (unwrap e)))
        fallback?  (do (reset! abandoned true) ::too-slow)
        :else      (try @f (catch java.util.concurrent.ExecutionException e (unwrap e)))))))

;; ===========================================================================
;; run-turn: one assistant turn, retry transient errors + fail over candidates
;; ===========================================================================

(def ^:private no-op-hooks
  {:before-send    (fn [_ _ _] nil)
   :delta-sink     (fn [_ _] nil)
   :on-retry       (fn [_] nil)
   :on-model-down  (fn [_] nil)
   :on-policy-empty (fn [_] nil)
   :on-latency-switch (fn [_] nil)
   :alive?         (fn [] true)})

(defn run-turn
  "Resolve candidates from `ctx` + `params` and issue ONE assistant turn,
   retrying transient backend errors (bounded, exponential backoff) and failing
   over across candidates in order. The single canonical turn engine: the
   `:llm-conversation` worker and the script-facing `ask`/`map-prompt` helpers
   all run through here.

   `ctx`:
     * `:backend` — an `LLMBackend` (required)
     * `:aliases` — `:llm/aliases` map (`{alias-kw [target …]}`)
     * `:preferences` — ordered alias-keyword vector; the candidate set when
       `params` names no model. Defaults to the built-in `default-preferences`.
     * `:catalog-ratings` — subjective ratings table for the `:needs` gate
     * `:eligibility-strict?` — fail-closed flag for the `:needs` gate
     * `:model-status` — shared atom of `{model-string :down}`; a model marked
       `:down` is skipped. A fresh atom is used when absent.
     * `:hooks` — optional side-effect callbacks (the worker supplies these to
       emit transcript events + capture blobs; all default to no-ops):
         - `:before-send`   (fn [request model provider])
         - `:delta-sink`    (fn [model provider] → on-delta | nil) streaming sink factory
         - `:on-retry`      (fn [{:keys [model category attempt max-retries]}])
         - `:on-model-down` (fn [{:keys [model category remaining throwable]}])
         - `:on-policy-empty` (fn [{:keys [policy strict?]}])
         - `:on-latency-switch` (fn [{:keys [model provider first-token-ms
            remaining]}]) — fired when a TTFT-cap breach abandons `model`
         - `:alive?`        (fn [] boolean) — false ⇒ stop retrying / treat an
                            in-flight throw as an interrupt
     * `:pinned` — a single candidate map `{:provider :model :params}` that
       SHORT-CIRCUITS resolution (used by `elect-model`/`map-prompt`): the turn
       runs that one model with retry but no failover.

   Returns the result envelope (see ns docstring). The `:ok` envelope also
   carries `:candidate` (the winning candidate map) so a caller can pin it."
  [{:keys [backend aliases preferences catalog-ratings eligibility-strict?
           model-status hooks pinned]} params messages tools]
  (let [model-status (or model-status (atom {}))
        {:keys [before-send delta-sink on-retry on-model-down on-policy-empty
                on-latency-switch alive?]}
        (merge no-op-hooks hooks)
        {:keys [max-retries backoff-ms latency overrun]} (params->resilience params)
        ttft-cap-ms  (let [c (:first-token-ms latency)]
                       (when (and (number? c) (pos? c)) (long c)))
        overrun-max  (long (or (:max-retries overrun) 0))
        overrun-fail? (= :fail (:on-exhausted overrun))
        policy     (params->policy params)
        resolution (if pinned
                     {:candidates [pinned]}
                     (resolve-candidates params model-status
                       (if (seq preferences) preferences preferences/default-preferences)
                       (or aliases {})
                       policy catalog-ratings (boolean eligibility-strict?)))]
    (when (or (:gated-empty? resolution) (:eligibility-empty resolution))
      (on-policy-empty {:policy policy :strict? (boolean eligibility-strict?)}))
    (cond
      (:eligibility-empty resolution)
      {:status :eligibility-empty :error (:eligibility-empty resolution)}

      (contains? resolution :string-model)
      {:status :string-model :error {:model (:string-model resolution)}}

      (contains? resolution :string-models)
      {:status :string-models :error {:models (:string-models resolution)
                                      :bad    (:bad resolution)}}

      (:unknown-alias resolution)
      {:status :unknown-alias :error {:alias (:unknown-alias resolution)
                                      :known (:known resolution)}}

      :else
      (let [latency-fallbacks (when (and ttft-cap-ms (not pinned) (seq (:fallback latency)))
                                (mapv alias-target->candidate (:fallback latency)))
            candidates (into (vec (:candidates resolution)) latency-fallbacks)]
        (loop [[cand & more] candidates
               attempts      []]
          (let [m        (:model cand)
                ;; Per-target params merged UNDER node params so an explicit
                ;; node value always wins (alias = defaults).
                eff      (merge (:params cand) params)
                ;; effective max-tokens: explicit caller value wins, else an
                ;; alias target's hint, else the catalog cap for the model.
                max-tok  (or (:max-tokens (:params cand))
                           (:max-tokens params)
                           (effective-max-tokens m)
                           (:max-output-tokens overrun))
                request  (cond-> (build-request
                                   {:system               (:system params)
                                    :messages             messages
                                    :tools                tools
                                    :model                m
                                    :max-tokens           max-tok
                                    :temperature          (:temperature eff)
                                    :top-p                (:top-p eff)
                                    :top-k                (:top-k eff)
                                    :stop-sequences       (:stop-sequences params)
                                    :thinking             (:thinking eff)
                                    :tool-choice          (:tool-choice params)
                                    :metadata             (:metadata params)
                                    :extra-body           (:extra-body params)
                                    :system-cache-control (:system-cache-control params)
                                    :tools-cache-control  (:tools-cache-control params)
                                    :message-cache-control (:message-cache-control params)
                                    :auto-cache?          (get params :auto-cache? true)
                                    :conv-id              (:conversation/id params)})
                           ;; R3 dispatch: an alias candidate carries a provider
                           ;; keyword; tagging the request routes MultiBackend by
                           ;; provider (regex bypassed). nil :provider → untouched.
                           (:provider cand) (assoc :provider (:provider cand)))
                _        (before-send request m (:provider cand))
                on-delta (delta-sink m (:provider cand))
                ;; A deterministic model re-truncates on an identical rerun, so
                ;; an overrun rerun can bump temperature to force sampling
                ;; variance (clamped to `:temperature-max`, default 1.0). Off
                ;; unless `:overrun :temperature-bump` is set.
                base-temp (:temperature eff)
                temp-bump (:temperature-bump overrun)
                temp-max  (double (or (:temperature-max overrun) 1.0))
                ;; Bounded same-model retry for transient categories with
                ;; exponential backoff before falling back to the next
                ;; candidate, PLUS a bounded rerun of an OVERRUN turn (output
                ;; truncated at the cap → `stop-reason :max_tokens`) with the
                ;; identical context (optionally with a bumped temperature).
                response (loop [retry 0 over 0]
                           (let [bumped? (and temp-bump (pos? over))
                                 req     (if bumped?
                                           (assoc request :temperature
                                             ;; round to 3 dp so the wire value is
                                             ;; clean (avoids 3×0.3 = 0.8999…).
                                             (-> (min temp-max
                                                   (+ (double (or base-temp 0.0))
                                                     (* (double over) (double temp-bump))))
                                               (* 1000.0) Math/round (/ 1000.0)))
                                           request)
                                 _       (when bumped? (before-send req m (:provider cand)))
                                 t0  (now-ms)
                                 ;; Time-to-first-token: stamp the wall-clock of
                                 ;; the FIRST delta (model load + prompt eval +
                                 ;; queue wait) so the UI can show a reliable
                                 ;; `wait` next to total `:elapsed-ms`. Wrapping
                                 ;; the sink (variadic — backends may pass extra
                                 ;; args) is the only spot that sees the first
                                 ;; token for EVERY backend; the client-side TUI
                                 ;; estimate misses non-streamed turns. Atom is
                                 ;; inside the loop body so it resets per attempt.
                                 first-tok (atom nil)
                                 abandoned (atom false)
                                 on-delta* (when on-delta
                                             (fn [& args]
                                               (when (nil? @first-tok) (reset! first-tok (now-ms)))
                                               ;; Drop stragglers from a stream
                                               ;; abandoned on a TTFT breach so
                                               ;; they never interleave with the
                                               ;; fallback candidate's output.
                                               (when-not @abandoned (apply on-delta args))))
                                 r   (try (let [resp (await-turn!
                                                       (fn [] (proto/send-turn* backend req on-delta*))
                                                       ttft-cap-ms first-tok abandoned
                                                       (boolean (seq more)))]
                                            (if (identical? resp ::too-slow)
                                              {:_too-slow {:first-token-ms ttft-cap-ms}}
                                              (cond-> (assoc resp :elapsed-ms (- (now-ms) t0))
                                                @first-tok (assoc :wait-ms (- (long @first-tok) (long t0))))))
                                          (catch Throwable t {:_throw t}))
                                 t   (:_throw r)
                                 cat (when t (proto/error-category t))]
                             (cond
                               (and t
                                 (contains? transient-error-categories cat)
                                 (< retry (long max-retries))
                                 (not (instance? InterruptedException t))
                                 (not (instance? InterruptedException (ex-cause t)))
                                 (alive?))
                               (do
                                 (on-retry {:model m :category cat
                                            :attempt (inc retry) :max-retries max-retries})
                                 (sleep-while-alive! (backoff-delay-ms backoff-ms retry t) alive?)
                                 (recur (inc retry) over))

                               (and (not t)
                                 (pos? overrun-max)
                                 (= :max_tokens (:stop-reason r))
                                 (< over overrun-max)
                                 (alive?))
                               (do
                                 (on-retry {:model m :category :overrun
                                            :attempt (inc over) :max-retries overrun-max})
                                 (recur retry (inc over)))

                               :else r)))]
            (cond
              ;; TTFT-cap breach: fail over to the next candidate WITHOUT marking
              ;; the slow model `:down` (slowness is transient, not a fault) and
              ;; WITHOUT the `on-model-down` "broken" signal. Only reached when a
              ;; fallback existed (the inner loop rides out a sole/last candidate).
              (:_too-slow response)
              (let [cap       (:first-token-ms (:_too-slow response))
                    attempts' (conj attempts
                                {:model m
                                 :error {:category :too-slow
                                         :message  (str "no first token within " cap "ms")}})]
                (on-latency-switch {:model m :provider (:provider cand)
                                    :first-token-ms cap :remaining (vec more)})
                (if (and (seq more) (alive?))
                  (recur more attempts')
                  {:status :exhausted
                   :error  {:category :too-slow
                            :message  (str "no first token within " cap "ms")
                            :attempts attempts'}}))

              (and (:_throw response)
                (or (instance? InterruptedException (:_throw response))
                  (instance? InterruptedException (ex-cause (:_throw response)))
                  (not (alive?))))
              {:status :interrupted :error {:throwable (:_throw response)}}

              (:_throw response)
              (let [^Throwable t (:_throw response)
                    category     (proto/error-category t)
                    message      (or (.getMessage t)
                                   (some-> (ex-cause t) .getMessage)
                                   (.toString t))
                    ;; Only mark a real model id down; nil (backend's default
                    ;; pick) is not a routable identifier.
                    _            (when m (swap! model-status assoc m :down))
                    _            (on-model-down {:model m :category category
                                                 :remaining (vec more) :throwable t})
                    attempts'    (conj attempts {:model m
                                                 :error {:message message
                                                         :class   (.getName (class t))}})]
                (if (seq more)
                  (recur more attempts')
                  {:status :exhausted
                   :error  {:category category :message message :attempts attempts'}
                   :last-throwable t}))

              ;; Overrun retries spent and still truncated, with
              ;; `:on-exhausted :fail` — surface a failure envelope instead of
              ;; an `:ok` truncated turn. (`:truncate`, the default, falls
              ;; through to `:ok` below.)
              (and overrun-fail?
                (pos? overrun-max)
                (= :max_tokens (:stop-reason response)))
              {:status :overrun :response response :model m
               :usage  (:usage response) :candidate cand
               :error  {:category :overrun
                        :message  (str "output truncated at the token cap after "
                                    overrun-max " retr" (if (= 1 overrun-max) "y" "ies"))
                        :max-retries overrun-max}}

              :else
              {:status :ok :response response :model m
               :usage  (:usage response) :candidate cand})))))))

;; ===========================================================================
;; Script-facing sugar: ask / elect-model / map-prompt
;; ===========================================================================

(defn- env->ctx
  "Normalize either a slim resolution `ctx` or a statechart `env` (carrying the
   `:escapement/llm-*` keys surfaced by `escapement.engine.env`) into the `ctx`
   shape `run-turn` expects. A pinned ctx (`:elected`) passes through verbatim."
  [x]
  (if (contains? x :escapement/llm-backend)
    {:backend             (:escapement/llm-backend x)
     :aliases             (:escapement/llm-aliases x)
     :preferences         (:escapement/llm-preferences x)
     :catalog-ratings     (or (:escapement/llm-catalog-ratings x) {})
     :eligibility-strict? (:escapement/llm-eligibility-strict? x)}
    x))

(def ^:private turn-option-keys
  "`ask`/`map-prompt` option keys that are NOT chart-style turn params and so
   must be stripped before the option map is handed to `run-turn` as `params`."
  #{:prompt :messages :tools :concurrency :on-error :probe-prompt})

(defn- opts->params [opts]
  (apply dissoc opts turn-option-keys))

(defn- prompt-messages [{:keys [prompt messages]}]
  (or messages
    [{:role :user :content [{:type :text :text (str prompt)}]}]))

(defn- public-envelope
  "Strip `run-turn`'s internal keys, keeping the public envelope. On failure
   `:response` is nil. `response-fn` maps the Response on success (identity for
   `ask*`, `response-text` for `ask`)."
  [env response-fn]
  (if (= :ok (:status env))
    {:status :ok :response (response-fn (:response env))
     :model  (:model env) :usage (:usage env)}
    {:status (:status env) :response nil :error (:error env)
     :model  (:model env)}))

(defn ask*
  "Issue ONE LLM turn and return the result envelope with the full Response
   under `:response` on success. `ctx-or-env` is a resolution `ctx`, a `:elected`
   pinned ctx, or a statechart `env`. `opts`:

     * `:prompt`   — user text (shortcut for a single-user-turn `:messages`)
     * `:messages` — explicit message vector (overrides `:prompt`)
     * `:tools`    — tool definitions (optional)
     * `:model` / `:models` — alias keyword(s) (full resolution); omit to use
       the ctx/env preferences
     * any generation knob (`:system` `:temperature` `:top-p` `:top-k`
       `:max-tokens` `:thinking` `:stop-sequences` `:needs` `:resilience` …)

   Never throws on a backend/categorized failure — returns `:status` ≠ `:ok`."
  [ctx-or-env opts]
  (-> (run-turn (env->ctx ctx-or-env) (opts->params opts)
        (prompt-messages opts) (or (:tools opts) []))
    (public-envelope identity)))

(defn ask
  "Like `ask*` but `:response` is the extracted assistant text string (via
   `response-text`) instead of the full Response."
  [ctx-or-env opts]
  (-> (run-turn (env->ctx ctx-or-env) (opts->params opts)
        (prompt-messages opts) (or (:tools opts) []))
    (public-envelope response-text)))

(defn elect-model
  "Resolve `params` to candidates and run ONE turn to find the first
   *actually-working* candidate. On success returns
   `{:status :ok :response <pinned-ctx> :model m :usage …}` where the pinned
   ctx is `ctx` augmented with `{:elected true :pinned <candidate>}` — pass it
   to `ask`/`map-prompt` to skip re-resolution and run that exact model.

   Standalone there is no real item to probe with, so a tiny `:probe-prompt`
   (default `\"ping\"`) is issued. `map-prompt` instead elects with the first
   real item, so no throwaway call is made there."
  [ctx-or-env params]
  (let [ctx  (env->ctx ctx-or-env)
        msgs [{:role :user :content [{:type :text :text (str (or (:probe-prompt params) "ping"))}]}]
        env  (run-turn ctx (opts->params params) msgs [])]
    (if (= :ok (:status env))
      {:status :ok :model (:model env) :usage (:usage env)
       :response (assoc ctx :elected true :pinned (:candidate env))}
      {:status (:status env) :response nil :error (:error env) :model (:model env)})))

(defn- run-pool
  "Run `(f item)` over `items` with at most `concurrency` calls in flight,
   returning a vector of results in input order. A fixed set of worker futures
   pulls indices off a shared cursor (no exotic concurrency classes — `future`
   + atoms only, both bb-safe)."
  [concurrency items f]
  (let [n       (count items)
        results (atom (vec (repeat n nil)))
        cursor  (atom 0)
        worker  (fn []
                  (loop []
                    (let [i (dec (long (swap! cursor inc)))]
                      (when (< i n)
                        (swap! results assoc i (f (nth items i)))
                        (recur)))))
        futs    (mapv (fn [_] (future (worker))) (range (max 1 (min (long concurrency) n))))]
    (run! deref futs)
    @results))

(defn map-prompt
  "Fan the SAME prompt across `coll` with bounded concurrency, returning a
   vector of result envelopes in input order (`:response` is the extracted
   text on success). `->prompt` maps each element to its user text.

   Election-by-doing: unless `ctx-or-env` is already pinned (`elect-model`),
   item 0 runs through full resolution + failover; if that election fails
   nothing works and the batch aborts; otherwise the winning model is pinned
   and items 1..N ride it (cheap per-call retry, no re-resolution). A bounded
   re-election kicks in if the pinned model dies mid-run.

   `opts`:
     * `:concurrency` — max in-flight calls (default 16)
     * `:model`/`:models` — alias keyword(s) (ignored once a pinned ctx is given)
     * `:tools`, `:system`, and any generation knob — applied to every call
     * `:on-error` — `:collect` (default; each failure is its own `:status`
       `:error` envelope) or `:abort` (stop dispatching after the first error;
       not-yet-started items return `{:status :skipped}`)."
  [ctx-or-env {:keys [concurrency on-error tools]
               :or   {concurrency 16 on-error :collect} :as opts}
   ->prompt coll]
  (let [ctx        (env->ctx ctx-or-env)
        params     (opts->params opts)
        tools*     (or tools [])
        items      (vec coll)
        msgs-of    (fn [item] [{:role :user :content [{:type :text :text (str (->prompt item))}]}])
        max-reelect 2]
    (if (empty? items)
      []
      (let [;; Election: pin a working model. Already-pinned ctx skips this.
            [pin0 head] (if (:pinned ctx)
                          [(:pinned ctx) nil]
                          (let [env (run-turn ctx params (msgs-of (first items)) tools*)]
                            (if (= :ok (:status env))
                              [(:candidate env) (public-envelope env response-text)]
                              [::failed env])))]
        (cond
          ;; Election failed outright — nothing works. Abort the batch.
          (= ::failed pin0)
          (let [fail (public-envelope head response-text)]
            (if (= :abort on-error)
              [fail]
              (into [fail] (repeat (dec (count items)) (assoc fail :status :aborted)))))

          :else
          (let [pin         (atom pin0)
                reelections (atom 0)
                reelect-lock (Object.)
                aborted?    (atom false)
                run-one
                (fn [item]
                  (if (and (= :abort on-error) @aborted?)
                    {:status :skipped :response nil}
                    (let [env (run-turn (assoc ctx :pinned @pin) params (msgs-of item) tools*)]
                      (if (or (= :ok (:status env))
                            (not (#{:exhausted :interrupted} (:status env))))
                        (do (when (and (= :abort on-error) (not= :ok (:status env)))
                              (reset! aborted? true))
                            (public-envelope env response-text))
                        ;; Pinned model failed for this item: bounded re-election
                        ;; (a full-failover turn that ALSO produces this result).
                        (let [renv (locking reelect-lock
                                     (if (< @reelections max-reelect)
                                       (let [re (run-turn ctx params (msgs-of item) tools*)]
                                         (when (= :ok (:status re))
                                           (swap! reelections inc)
                                           (reset! pin (:candidate re)))
                                         re)
                                       env))]
                          (when (and (= :abort on-error) (not= :ok (:status renv)))
                            (reset! aborted? true))
                          (public-envelope renv response-text))))))
                ;; Item 0's result is the election result (when we elected here).
                rest-items (if head (subvec items 1) items)
                tail       (run-pool concurrency rest-items run-one)]
            (if head (into [head] tail) (vec tail))))))))
