(ns escapement.lib.event-sink
  "Pure normalization adapter over the in-process `:transcript-tap`.

  This namespace is a **pure adapter**: it consumes raw transcript rows (the
  exact maps the runner / llm-conversation already emit through
  `:transcript-tap`) and maps them to a **stable, closed, public Malli event
  schema**. It does not modify the runner, any processor, or any event
  producer — consumption is read-only over the existing tap.

  ## Why an adapter

  The raw transcript stream is an internal implementation detail: row shapes,
  key names and the event union are free to change. A hosted embedder needs a
  small, stable surface. This adapter:

   * normalizes raw rows to a closed public event union (`PublicEvent`),
   * drops internal/unmapped rows (returns `nil`) so the public stream stays
     stable,
   * stamps every public event with the correlation triple
     `:session-id` + `:run-id` (captured from `:runner/started`) and
     `:invokeid` (per-LLM-invocation id, when the source row carries one),
   * **synthesizes** the tool lifecycle (`:tool-call` / `:tool-result` /
     `:tool-validation-failure`) — there is no native single tool-call
     transcript row, so the call/result/validation split is derived here from
     `:llm/tool-result` plus tool-attributed `:llm/error` / `:llm/retry` rows.

  ## Public surface

   * `PublicEvent`        — the closed Malli schema (a multi-schema dispatched
                            on `:type`) for a single normalized event.
   * `normalize`          — pure: `(normalize ctx row) => [public-event ...]`
                            (0, 1 or 2 events). Does not capture state.
   * `make-adapter`        — returns an adapter instance: a stateful-but-local
                            closure capturing the correlation context from
                            `:runner/started` and threading it (plus pending
                            tool-call correlation) onto every subsequent event.
   * `feed!`               — push one raw row into an adapter instance; returns
                            the vector of normalized public events (possibly
                            empty)."
  (:require
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
    [malli.core :as m]))

;; ---------------------------------------------------------------------------
;; Public event schema  (THE PUBLIC API CONTRACT — closed union)
;; ---------------------------------------------------------------------------
;;
;; Every public event is a closed map carrying the correlation triple:
;;   :session-id  string  — captured from :runner/started
;;   :run-id      [:maybe string] — captured from :runner/started (nil if the
;;                                  run was started without a facade run-id)
;;   :invokeid    [:maybe :any]   — per-LLM-invocation correlation id; nil for
;;                                  run/chart-lifecycle events that have none
;; plus a stable :type tag and a normalized, family-specific payload.

(def ^:private correlation
  [[:type :keyword]
   [:session-id [:maybe :string]]
   [:run-id [:maybe :string]]
   [:invokeid {:optional true} [:maybe :any]]])

(defn- evt
  "Build one closed event schema variant: the correlation triple plus the
  variant-specific entries."
  [& entries]
  (into [:map {:closed true}] (concat correlation entries)))

(def PublicEvent
  "Closed Malli schema for a single normalized public event. A multi-schema
  dispatched on `:type`. This is the public, stable, additive contract
  consumed by the hosted embed (task-008) and documented (task-009)."
  [:multi {:dispatch :type}
   ;; ---- run lifecycle ----
   [:run-started (evt [:chart-id {:optional true} [:maybe :string]]
                   [:resume? {:optional true} :boolean])]
   [:run-resumed (evt [:config {:optional true} [:maybe [:vector :any]]])]
   [:run-done (evt [:final-config {:optional true} [:maybe [:vector :any]]])]
   [:run-aborted (evt [:reason {:optional true} [:maybe :any]])]
   [:run-error (evt [:message {:optional true} [:maybe :string]])]
   ;; ---- chart lifecycle ----
   [:chart-event (evt [:event-name {:optional true} [:maybe :any]]
                   [:config-before {:optional true} [:maybe [:vector :any]]]
                   [:config-after {:optional true} [:maybe [:vector :any]]]
                   [:event-data {:optional true} [:maybe :any]])]
   [:chart-config (evt [:config {:optional true} [:maybe [:vector :any]]])]
   [:chart-checkpoint (evt [:checkpoint-session-id {:optional true} [:maybe :string]])]
   ;; ---- LLM lifecycle ----
   [:llm-request (evt [:model {:optional true} [:maybe :any]]
                   [:n-messages {:optional true} [:maybe :int]])]
   [:text-delta (evt [:delta {:optional true} [:maybe :any]]
                  [:model {:optional true} [:maybe :any]])]
   [:llm-response (evt [:model {:optional true} [:maybe :any]]
                    [:stop-reason {:optional true} [:maybe :any]]
                    [:n-blocks {:optional true} [:maybe :int]]
                    [:usage {:optional true} [:maybe :any]])]
   [:llm-retry (evt [:model {:optional true} [:maybe :any]]
                 [:category {:optional true} [:maybe :any]]
                 [:attempt {:optional true} [:maybe :int]])]
   [:llm-fallback (evt [:from-model {:optional true} [:maybe :any]]
                    [:category {:optional true} [:maybe :any]])]
   [:llm-error (evt [:reason {:optional true} [:maybe :any]]
                 [:category {:optional true} [:maybe :any]]
                 [:message {:optional true} [:maybe :string]])]
   [:llm-continuation (evt [:segment {:optional true} [:maybe :int]]
                        [:usage {:optional true} [:maybe :any]])]
   ;; ---- tool lifecycle (synthesized) ----
   [:tool-call (evt [:tool-use-id {:optional true} [:maybe :any]]
                 [:tool {:optional true} [:maybe :any]]
                 [:input {:optional true} [:maybe :any]])]
   [:tool-result (evt [:tool-use-id {:optional true} [:maybe :any]]
                   [:tool {:optional true} [:maybe :any]]
                   [:is-error {:optional true} :boolean]
                   [:content-preview {:optional true} [:maybe :any]])]
   [:tool-validation-failure
    (evt [:tool {:optional true} [:maybe :any]]
      [:category {:optional true} [:maybe :any]]
      [:reason {:optional true} [:maybe :any]]
      [:message {:optional true} [:maybe :string]])]])

(def ^:private public-event-validator (m/validator PublicEvent))

(>defn valid-event?
  "True iff `event` conforms to the closed public `PublicEvent` schema."
  [event]
  [:any => :boolean]
  (boolean (public-event-validator event)))

;; ---------------------------------------------------------------------------
;; Correlation context
;; ---------------------------------------------------------------------------

(def ^:private empty-ctx
  "An adapter correlation context before `:runner/started` has been seen."
  {:session-id    nil
   :run-id        nil
   ;; invokeid -> last tool meta seen for that invocation, used so a
   ;; tool-attributed :llm/error or :llm/retry can be turned into a
   ;; :tool-validation-failure linked by :invokeid.
   :pending-tools {}})

(defn- with-corr
  "Stamp the correlation triple onto a partial public event map."
  [ctx invokeid m]
  (cond-> (assoc m
            :session-id (:session-id ctx)
            :run-id (:run-id ctx))
    (some? invokeid) (assoc :invokeid invokeid)))

;; ---------------------------------------------------------------------------
;; Pure normalization transform
;; ---------------------------------------------------------------------------
;;
;; `normalize` is pure: it does not capture or mutate state. It takes the
;; *current* correlation context and a raw row and returns a vector of 0, 1 or
;; 2 normalized public events. The stateful adapter (`make-adapter`) updates
;; the context (capture session/run id, remember pending tool invocations)
;; and delegates the mapping to this fn.

(defn- tool-attributed-error?
  "An :llm/error / :llm/retry row counts as a *tool* validation failure when
  it carries an :invokeid that we have seen a tool result for, or when its
  reason explicitly names bad/invalid tool use."
  [ctx data]
  (let [reason (:reason data)]
    (or (contains? #{:bad-tool-use :invalid-tool-use :tool-validation
                     :tool-error :tool-input-invalid}
          reason)
      (and (some? (:invokeid data))
        (contains? (:pending-tools ctx) (:invokeid data))))))

(>defn normalize
  "Pure transform: `(normalize ctx row) => [public-event ...]`.

  Returns a vector of 0, 1 or 2 normalized public events. Rows with no
  public mapping (internal/unmapped transcript rows) yield `[]` — they
  are silently dropped to keep the public stream stable. `:llm/tool-result`
  yields a correlated `:tool-call` + `:tool-result` pair (the call is
  synthesized — there is no native tool-call row)."
  [ctx row]
  [:map :map => [:vector :map]]
  (let [{:keys [event data]} row
        iid (:invokeid data)]
    (case event
      ;; ---- run lifecycle ----
      :runner/started
      [(with-corr ctx nil
         {:type     :run-started
          :chart-id (:chart-id data)
          :resume?  (boolean (:resume? data))})]

      :runner/resumed
      [(with-corr ctx nil {:type :run-resumed :config (:config data)})]

      :runner/start-config
      [(with-corr ctx nil {:type :chart-config :config (:config data)})]

      :runner/done
      [(with-corr ctx nil {:type         :run-done
                           :final-config (:final-config data)})]

      :runner/aborted
      [(with-corr ctx nil {:type :run-aborted :reason (:reason data)})]

      :runner/error
      [(with-corr ctx nil {:type :run-error :message (:message data)})]

      ;; ---- chart lifecycle ----
      :runner/event-processed
      [(with-corr ctx nil
         {:type          :chart-event
          :event-name    (:event-name data)
          :config-before (:config-before data)
          :config-after  (:config-after data)
          :event-data    (:event-data data)})]

      :checkpoint/written
      [(with-corr ctx nil
         {:type                  :chart-checkpoint
          :checkpoint-session-id (:session-id data)})]

      ;; ---- LLM lifecycle ----
      :llm/request
      [(with-corr ctx iid {:type       :llm-request
                           :model      (:model data)
                           :n-messages (:n-messages data)})]

      :llm/delta
      [(with-corr ctx iid {:type  :text-delta
                           :delta (dissoc data :invokeid :model)
                           :model (:model data)})]

      :llm/response
      [(with-corr ctx iid {:type        :llm-response
                           :model       (:model data)
                           :stop-reason (:stop-reason data)
                           :n-blocks    (:n-blocks data)
                           :usage       (:usage data)})]

      :llm/retry
      (if (tool-attributed-error? ctx data)
        [(with-corr ctx iid {:type     :tool-validation-failure
                             :tool     (get-in ctx [:pending-tools iid :tool])
                             :category (:category data)
                             :reason   :retry})]
        [(with-corr ctx iid {:type     :llm-retry
                             :model    (:model data)
                             :category (:category data)
                             :attempt  (:attempt data)})])

      :llm/continuation
      [(with-corr ctx iid {:type    :llm-continuation
                           :segment (:segment data)
                           :usage   (:usage data)})]

      :llm/error
      (if (tool-attributed-error? ctx data)
        [(with-corr ctx iid {:type     :tool-validation-failure
                             :tool     (get-in ctx [:pending-tools iid :tool])
                             :category (:category data)
                             :reason   (:reason data)
                             :message  (:message data)})]
        ;; A categorized error after at least one retry is the model
        ;; *fallback* signal; otherwise a plain LLM error.
        (if (and (:category data) (:attempts data))
          [(with-corr ctx iid {:type       :llm-fallback
                               :from-model (:model data)
                               :category   (:category data)})
           (with-corr ctx iid {:type     :llm-error
                               :reason   (:reason data)
                               :category (:category data)
                               :message  (:message data)})]
          [(with-corr ctx iid {:type     :llm-error
                               :reason   (:reason data)
                               :category (:category data)
                               :message  (:message data)})]))

      ;; ---- tool lifecycle (synthesized) ----
      ;; There is no native tool-call row. A :llm/tool-result row is the
      ;; first time we observe the invocation, so synthesize the call and
      ;; the result, correlated by :tool_use_id + :invokeid.
      :llm/tool-result
      [(with-corr ctx iid {:type        :tool-call
                           :tool-use-id (:tool_use_id data)
                           :tool        (:tool data)
                           :input       (:input data)})
       (with-corr ctx iid {:type            :tool-result
                           :tool-use-id     (:tool_use_id data)
                           :tool            (:tool data)
                           :is-error        (boolean (:is-error data))
                           :content-preview (:content-preview data)})]

      ;; everything else: internal / unmapped → drop
      [])))

;; ---------------------------------------------------------------------------
;; Stateful (instance-local) adapter
;; ---------------------------------------------------------------------------

(defn- update-ctx
  "Advance the adapter correlation context for a raw row *before* normalization
  so emitted events carry the right correlation triple. Captures session/run
  id from `:runner/started`; remembers a tool invocation from
  `:llm/tool-result` so a later tool-attributed `:llm/error` / `:llm/retry`
  can be linked by `:invokeid`. State is local to the adapter instance."
  [ctx row]
  (let [event (:event row)
        data  (:data row)]
    (case event
      :runner/started
      (assoc ctx
        :session-id (some-> (:session-id data) str)
        :run-id (some-> (:run-id data) str))

      :llm/tool-result
      (assoc-in ctx [:pending-tools (:invokeid data)]
        {:tool        (:tool data)
         :tool-use-id (:tool_use_id data)})

      ctx)))

(>defn make-adapter
  "Create a stateful-but-instance-local adapter.

  Returns a map with:
   * `:feed`  — `(fn [row] -> [public-event ...])`. Push one raw
                transcript row in; get back the (possibly empty) vector
                of normalized public events. Captures `:session-id` /
                `:run-id` from `:runner/started` and threads them (plus
                pending tool-call correlation) onto every later event.
   * `:ctx`   — `(fn [] -> ctx-map)` for inspection/testing.

  All state (correlation context, pending tool correlation) is held in a
  closure-local atom — no globals, no producer mutation. The adapter is a
  pure consumer of the existing `:transcript-tap`."
  []
  [=> :map]
  (let [state (atom empty-ctx)]
    {:feed (fn [row]
             (let [ctx' (swap! state update-ctx row)]
               (normalize ctx' row)))
     :ctx  (fn [] @state)}))

(>defn feed!
  "Push one raw transcript row into an `adapter` (from `make-adapter`).
  Returns the vector of normalized public events (possibly empty)."
  [adapter row]
  [:map :map => [:vector :map]]
  ((:feed adapter) row))
