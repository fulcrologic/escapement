(ns escapement.llm.multi
  "A multi-backend dispatcher. Holds a routing table of `[matcher sub-backend]`
   pairs and routes each `send-turn` to the sub-backend whose matcher accepts
   the request's `:model`.

   Matchers may be:

   - A regex pattern (`java.util.regex.Pattern` on CLJ, `js/RegExp` on CLJS) —
     matched against the model string with `re-find`.
   - A set of strings — exact membership.
   - A function `(fn [model-string] boolean)`.

   Routes are tried in order; the first match wins. If no route matches the
   `:default-backend` (if provided) handles the request; otherwise an
   `ex-info` is thrown.

   Every sub-backend already returns the canonical Anthropic-shaped Response,
   so no translation is needed at this layer.

   Example:

       (require '[escapement.llm.api          :as anthropic]
                '[escapement.llm.openai-codex :as codex]
                '[escapement.llm.multi        :as multi])

       (multi/new-backend
         {:routes          [[#\"^claude-\" (anthropic/new-backend {...})]
                            [#\"^gpt-5\"  (codex/new-backend {})]]
          :default-backend (anthropic/new-backend {...})})"
  (:require
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
    [escapement.llm.protocol :as proto]))

(defn routes->provider-index
  "Build a `{provider-kw -> sub-backend}` map from routes that carry an
   optional third element (their provider keyword). Routes are the 2-tuple
   `[matcher sub-backend]` (no provider tag → not indexed) or the 3-tuple
   `[matcher sub-backend provider-kw]`. The first occurrence of a provider
   wins (mirrors first-match-wins for matcher routes). Routes without a
   provider tag contribute nothing, so the index is empty for legacy callers
   and the provider branch stays inert."
  [routes]
  (reduce (fn [idx route]
            (let [provider (when (> (count route) 2) (nth route 2))
                  b        (nth route 1)]
              (if (and (some? provider) (not (contains? idx provider)))
                (assoc idx provider b)
                idx)))
    {} routes))

(defn- match?
  [matcher ^String model]
  (cond
    #?(:clj  (instance? java.util.regex.Pattern matcher)
       :cljs (instance? js/RegExp matcher))
    (boolean (re-find matcher model))
    (set? matcher) (contains? matcher model)
    (fn? matcher) (boolean (matcher model))
    :else false))

(defn- pick-backend
  [routes default-backend model]
  (let [m (or model "")]
    (or (some (fn [[matcher b]] (when (match? matcher m) b)) routes)
      default-backend
      (throw (ex-info "No route matched model and no :default-backend configured"
               {:model          model
                :route-matchers (mapv first routes)})))))

(defn- select-backend
  "Shared sub-backend selection used by both `send-turn` and `stream-turn`,
   so streaming dispatch uses the exact same routing logic (no duplication,
   no separate selection side effects).

   Provider-keyed dispatch (R3): when the request carries an explicit
   `:provider` keyword AND a sub-backend is tagged with that provider, route
   straight to it — bypassing the model-string regex entirely. This is the
   path that lets `:llm/aliases` candidates target a provider by name instead
   of re-guessing it from the model string. When `:provider` is absent (the
   legacy path), or names an untagged provider, selection falls back to the
   existing matcher logic, so behavior is byte-for-byte unchanged for every
   bare-string-model request."
  [{:keys [routes default-backend provider-index]} request]
  (or (when-let [provider (:provider request)]
        (get provider-index provider))
    (pick-backend routes default-backend (:model request))))

(defn- delegate-stream-turn
  "Pure forwarding: select the sub-backend with the SAME logic as
   `send-turn`, then delegate. Selection (and any attempt/usage accounting
   the sub-backend performs) happens exactly once per turn — this layer only
   forwards deltas and the final Response, never re-shapes or re-counts. If
   the selected sub-backend does not stream, behave like `send-turn` (no
   deltas), still returning the Response (capability guard, never throws)."
  [this request on-delta]
  (let [b (select-backend this request)]
    (if (proto/streaming? b)
      (proto/stream-turn b request on-delta)
      (proto/send-turn b request))))

;; Two concrete variants share one selection path. The variant is chosen at
;; construction: a `StreamingMultiBackend` is constructed when ANY selectable
;; sub-backend implements `StreamingLLMBackend`. Per request it streams from
;; streaming-capable sub-backends and falls back to a plain `send-turn` for the
;; rest (`delegate-stream-turn`), so the request-less `proto/streaming?` reports
;; "can stream" for the route table as a whole.

(defrecord MultiBackend [routes default-backend provider-index]
  proto/LLMBackend
  (send-turn [this request]
    (proto/send-turn (select-backend this request) request)))

(defrecord StreamingMultiBackend [routes default-backend provider-index]
  proto/LLMBackend
  (send-turn [this request]
    (proto/send-turn (select-backend this request) request))

  proto/StreamingLLMBackend
  (stream-turn [this request on-delta]
    (delegate-stream-turn this request on-delta)))

(>defn new-backend
  "Construct a routing backend.

   `opts`:
   - `:routes`           — vector of routes (required). Each route is either
                           the 2-tuple `[matcher sub-backend]` (legacy) or the
                           3-tuple `[matcher sub-backend provider-kw]`. The
                           optional third element tags the sub-backend with its
                           provider keyword so a request carrying `:provider`
                           can be dispatched directly (see `select-backend`).
   - `:default-backend`  — backend used when no route matches (optional)

   Returns a backend that also implements `StreamingLLMBackend` iff every
   selectable sub-backend (all routed backends plus any default) supports
   streaming, so `protocol/streaming?` reflects the picked sub-backend and
   `stream-turn` delegates via the same routing logic as `send-turn`."
  [opts]
  [[:map
    [:routes [:vector [:or [:tuple :any :any] [:tuple :any :any :any]]]]
    [:default-backend {:optional true} :any]]
   => :any]
  (let [routes   (vec (:routes opts))
        default  (:default-backend opts)
        prov-idx (routes->provider-index routes)
        subs     (cond-> (mapv second routes) default (conj default))]
    ;; Stream whenever ANY selectable sub-backend can: `delegate-stream-turn`
    ;; selects per-request and falls back to a plain `send-turn` for the
    ;; non-streaming ones, so a single non-streaming sub-backend (e.g. the codex
    ;; Responses backend) must NOT disable token streaming — and the live
    ;; first-token `wait`/`t/s` — for the streaming providers it routes
    ;; alongside. (Was `every?`, which made one non-streaming backend collapse
    ;; the whole route table to non-streaming.)
    (if (and (seq subs) (some proto/streaming? subs))
      (->StreamingMultiBackend routes default prov-idx)
      (->MultiBackend routes default prov-idx))))
