(ns escapement.chart.service
  "Service regions — chart regions that act as stateful tools other LLM regions
  can call.

  A service region declares a set of tool keywords on entry, hangs handlers
  off the states that own them, and (optionally) unregisters on exit. Consumers
  declare `:chart-tools` in their llm-conversation params and see the region's
  tools as ordinary Anthropic tools.

  Three public functions:

    * [[register-tool!]] — on-entry executable content
    * [[unregister-tool!]] — on-exit executable content
    * [[handle]] — produces a `transition` element bound to the tool's event
    * [[post-reply]] — fire a deferred reply for the async (slow-work) pattern

  See `region-tools.md` for the design."
  (:require
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn >defn- ?]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.elements :as elt]
    [com.fulcrologic.statecharts.environment :as env-ns]
    [com.fulcrologic.statecharts.protocols :as sp]))

;; ---------------------------------------------------------------------------
;; Registry helpers
;; ---------------------------------------------------------------------------

(>defn registry
  "Returns the chart-scoped registry atom on `env`. Returns nil when the env
wasn't built by `escapement.engine.env/new-env` (e.g. a hand-rolled test env).

Registry shape: `{tool-kw {owner-id {:owner :description :input-schema}}}`.
A given tool keyword may be claimed by multiple owners (sibling service
regions); consumers disambiguate via `:chart-tools` aliasing. A SINGLE
owner re-registering the same tool keyword is a hard error."
  [env]
  [map? => [:maybe [:fn #(instance? clojure.lang.IAtom %)]]]
  (::registry env))

(>defn entries
  "Snapshot of the registry, flattened to a vector of entry maps. Each
entry carries `:tool`, `:owner`, `:description`, `:input-schema`."
  [env]
  [map? => [:vector :map]]
  (let [reg (some-> (registry env) deref)]
    (vec
      (for [[tool-kw owners] (or reg {})
            [owner entry] owners
            :when (and (keyword? tool-kw) (keyword? owner))]
        (assoc entry :tool tool-kw :owner owner)))))

(>defn- assert-open-schema!
  "Each region-tool input schema must be open so the engine can merge an
implicit `:timeout-ms` field for LLM-supplied per-call deadlines. A closed
map (`{:closed true}`) breaks that merge — error early with the offending
owner so chart authors know exactly where to look.

Only detects the literal top-level `[:map {:closed true} ...]` form. A
closed map wrapped in `[:and ...]`, `[:multi ...]`, or behind a registry
ref will slip past this guard; in that case the implicit-timeout merge
at palette-snapshot time will throw with a less precise message."
  [schema owner tool-kw]
  [:any (? any?) :keyword => :nil]
  (when (and (vector? schema)
          (= :map (first schema))
          (map? (second schema))
          (true? (:closed (second schema))))
    (throw (ex-info (str "Region-tool input-schema must be open (cannot be {:closed true}) "
                      "so the worker can merge an implicit :timeout-ms key. "
                      "Tool: " tool-kw " owner: " owner)
             {:reason :closed-region-tool-schema
              :tool   tool-kw
              :owner  owner
              :schema schema})))
  nil)

(>defn register-tool!
  "Returns a `script` element that, when executed on state-entry, registers a
tool declaration in the chart's `::registry`. `:owner` is read from the
current state's id (`::sc/context-element-id` on the env).

`decl`:
* `:tool`         (required, keyword) — the chart event the LLM call fires
* `:description`  (required, string)  — exposed to the LLM verbatim
* `:input-schema` (required, malli)   — validates the LLM's tool input.
                                        Must be open (no `:closed true`)."
  [decl]
  [[:map
    [:tool :keyword]
    [:description :string]
    [:input-schema :any]]
   => any?]
  (elt/script
    {:expr
     (fn [env _data]
       (let [reg     (registry env)
             owner   (or (env-ns/context-element-id env)
                       (throw (ex-info "register-tool! must run inside a state (on-entry)"
                                {:reason :no-context-state :decl decl})))
             tool-kw (:tool decl)]
         (when-not reg
           (throw (ex-info "No ::escapement.chart.service/registry on env. Build env via escapement.engine.env/new-env."
                    {:reason :no-registry})))
         (assert-open-schema! (:input-schema decl) owner tool-kw)
         (let [entry      {:owner        owner
                           :description  (:description decl)
                           :input-schema (:input-schema decl)}
               ;; Atomic compare-and-set: a single `swap-vals!` rules out
               ;; the race where two concurrent registrations both observe
               ;; "no existing entry" and one quietly overwrites the other.
               ;; Same-owner re-register with the same entry is idempotent;
               ;; same owner with a different entry is a hard error.
               [prev _] (swap-vals! reg
                          (fn [r]
                            (let [existing (get-in r [tool-kw owner])]
                              (if (or (nil? existing) (= existing entry))
                                (assoc-in r [tool-kw owner] entry)
                                r))))
               prev-entry (get-in prev [tool-kw owner])]
           (when (and (some? prev-entry) (not= prev-entry entry))
             (throw (ex-info (str "Region-tool registration collision: "
                               tool-kw " already registered by " owner
                               " with different declaration")
                      {:reason         :registration-collision
                       :tool           tool-kw
                       :existing-owner owner
                       :new-owner      owner}))))
         nil))}))

(>defn unregister-tool!
  "Returns a `script` element that, when executed on state-exit, removes
the (`tool-kw`, current owner) entry from the chart's registry. Stray calls
from non-owners (no matching entry) are silently ignored."
  [tool-kw]
  [:keyword => any?]
  (elt/script
    {:expr
     (fn [env _data]
       (let [reg   (registry env)
             owner (env-ns/context-element-id env)]
         (when (and reg owner)
           (swap! reg
             (fn [r]
               (let [owners' (dissoc (get r tool-kw {}) owner)]
                 (if (seq owners')
                   (assoc r tool-kw owners')
                   (dissoc r tool-kw)))))))
       nil)}))

(>defn prune-owners!
  "Remove every registry entry whose `:owner` is in `owner-ids`. Used by
the engine to auto-prune entries left behind when a service region's
on-exit didn't call `unregister-tool!` (e.g. an unexpected exit path).

`owner-ids` is a collection of state-id keywords."
  [env owner-ids]
  [map? [:every :keyword] => :nil]
  (let [owners (set owner-ids)]
    (when (and (seq owners) (registry env))
      (swap! (registry env)
        (fn [r]
          (reduce-kv
            (fn [acc tool-kw owner->entry]
              (let [survivors (reduce-kv
                                (fn [m owner entry]
                                  (if (contains? owners owner)
                                    m
                                    (assoc m owner entry)))
                                {}
                                owner->entry)]
                (if (seq survivors)
                  (assoc acc tool-kw survivors)
                  acc)))
            {}
            r)))))
  nil)

;; ---------------------------------------------------------------------------
;; Wire helpers (kept private so they can evolve)
;; ---------------------------------------------------------------------------

(defn- post-reply-event!
  "Fire the canonical `:escapement.tool/reply` event back into the chart's
   session. The conversation worker that issued the request listens on this
   event via the `:llm-conversation` processor's `forward-event!`."
  [env reply-id reply-to result is-error]
  (let [queue (::sc/event-queue env)
        sid   (env-ns/session-id env)]
    (when (and queue sid)
      (sp/send! queue env
        {:target            sid
         :source-session-id sid
         :event             :escapement.tool/reply
         :data              {:escapement.tool/reply-id reply-id
                             :escapement.tool/reply-to reply-to
                             :result                   (str result)
                             :is-error                 (boolean is-error)}}))))

(>defn post-reply
  "Explicitly post a deferred reply for the async (slow-work) handler
pattern. Call this from a worker that the chart kicked off so the
originating LLM call can complete.

`reply`:
* `:reply-id` (required) — correlation id captured from the request
* `:reply-to` (required) — caller invokeid (string)
* `:result`   (required, string) — content returned to the LLM
* `:is-error` (optional, default false)"
  [env reply]
  [map?
   [:map
    [:reply-id :string]
    [:reply-to :string]
    [:result :string]
    [:is-error {:optional true} :boolean]]
   => :nil]
  (post-reply-event! env
    (:reply-id reply)
    (:reply-to reply)
    (:result reply)
    (:is-error reply))
  nil)

;; ---------------------------------------------------------------------------
;; handle: produce a transition element for a tool request
;; ---------------------------------------------------------------------------

(defn- request-payload
  "Extract the user-facing payload from a region-tool request event. Strips
   the engine-injected correlation/routing keys."
  [event-data]
  (dissoc (or event-data {})
    :escapement.tool/reply-id
    :escapement.tool/reply-to
    :escapement.tool/owner
    :escapement.tool/timeout-ms))

(defn- run-handler!
  "Call `handler-fn` with `[env request]` where `request` is the shape
   advertised in `handle`'s docstring. The handler returns one of:

     * `{:result <string> :is-error <bool>}` — synchronous reply
     * `nil`                                  — handler will reply later via
                                                `post-reply` (slow-work pattern)
     * a throwable bubbles up to the engine's error.execution path."
  [env handler-fn _data event-data]
  (let [reply-id   (:escapement.tool/reply-id event-data)
        reply-to   (:escapement.tool/reply-to event-data)
        timeout-ms (:escapement.tool/timeout-ms event-data)
        payload    (request-payload event-data)
        request    {:data       payload
                    :reply-id   reply-id
                    :reply-to   reply-to
                    :timeout-ms timeout-ms}
        result     (try
                     (handler-fn env request)
                     (catch Throwable t
                       {:result   (str "handler threw: " (.getMessage t))
                        :is-error true}))]
    (when (some? result)
      (post-reply-event! env reply-id reply-to
        (:result result)
        (:is-error result)))
    nil))

(defn- source-state-owner-ancestor?
  "Walk parent ids in `chart` starting from the source-state of the
   transition identified by `transition-id`. Returns true iff `target-owner`
   appears anywhere on that ancestor chain.

   This is how we derive the (potentially multi-owner) routing decision
   without asking the chart author to repeat `owner` at the call site:
   the source state of the firing transition is the substate that
   contains `handle`; its enclosing service-region state is whatever
   `register-tool!` recorded under `:owner`. Walking up the parent chain
   lets a `handle` placed in any descendant of the registered owner
   match — which is exactly the substate-routing pattern in plan §V7."
  [chart transition-id target-owner]
  (when (and chart transition-id target-owner)
    (let [t-element (get-in chart [::sc/elements-by-id transition-id])
          source    (:parent t-element)
          ;; Defensive bound — a well-formed chart's `:parent` chain terminates
          ;; at `:ROOT` long before this fires; the cap guards against a
          ;; malformed elements-by-id (e.g. a cycle introduced by a buggy
          ;; transformation) turning the walk into a hang.
          max-steps (count (::sc/elements-by-id chart))]
      (loop [state-id source
             steps    0]
        (cond
          (nil? state-id) false
          (= state-id target-owner) true
          (= state-id :ROOT) false
          (>= steps max-steps) false
          :else (recur (get-in chart [::sc/elements-by-id state-id :parent])
                  (inc steps)))))))

(>defn handle
  "Returns a `transition` element bound to `event-kw` on the enclosing
state. The handler runs only if the firing request's
`:escapement.tool/owner` tag refers to the (registered) service-region
state that contains this transition's source state — i.e. the owner is
derived from chart structure at runtime, not from a parameter.

This makes single-owner and multi-owner usage identical at the call
site, while preserving SCXML transition precedence for sibling
substates (plan §V7 `:idle`/`:running` pattern).

`handler-fn` receives `[env request]` where `request` is:

{:data       <user-payload>      ;; LLM-supplied input, sans engine keys
 :reply-id   <s>                  ;; correlation id (engine-injected)
 :reply-to   <s>                  ;; caller invokeid (string)
 :timeout-ms <int>}                ;; per-call deadline (relative ms)

Returns `{:result <string> :is-error <bool>}` to reply synchronously,
or `nil` to reply later via [[post-reply]]. The transition is
internal (no `:target`) so the region's state doesn't change."
  [event-kw handler-fn]
  [:keyword fn? => any?]
  ;; Unique id lets the cond locate THIS transition in the chart's
  ;; ::sc/elements-by-id at fire-time, so it can walk up to the
  ;; service-region state and decide whether the request's owner tag
  ;; matches.
  (let [transition-id (keyword "escapement.chart.service"
                        (str "tool-handler-" (random-uuid)))]
    (elt/transition
      {:id    transition-id
       :event event-kw
       :type  :internal
       :cond  (fn [env data]
                (let [chart (::sc/statechart env)
                      owner (:escapement.tool/owner (get-in data [:_event :data]))]
                  (source-state-owner-ancestor? chart transition-id owner)))}
      (elt/script
        {:expr (fn [env data]
                 (run-handler! env handler-fn data (get-in data [:_event :data]))
                 nil)}))))
