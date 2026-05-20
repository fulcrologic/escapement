(ns escapement.llm.multi
  "A multi-backend dispatcher. Holds a routing table of `[matcher sub-backend]`
   pairs and routes each `send-turn` to the sub-backend whose matcher accepts
   the request's `:model`.

   Matchers may be:

   - A `java.util.regex.Pattern` — matched against the model string with `re-find`.
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

(defn- match?
  [matcher ^String model]
  (cond
    (instance? java.util.regex.Pattern matcher) (boolean (re-find matcher model))
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
   no separate selection side effects)."
  [{:keys [routes default-backend]} request]
  (pick-backend routes default-backend (:model request)))

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
;; construction so the request-less `proto/streaming?` (which is
;; `satisfies?`-based) faithfully reflects the sub-backend that would be
;; picked: a `StreamingMultiBackend` is only constructed when every
;; selectable sub-backend implements `StreamingLLMBackend`.

(defrecord MultiBackend [routes default-backend]
  proto/LLMBackend
  (send-turn [this request]
    (proto/send-turn (select-backend this request) request)))

(defrecord StreamingMultiBackend [routes default-backend]
  proto/LLMBackend
  (send-turn [this request]
    (proto/send-turn (select-backend this request) request))

  proto/StreamingLLMBackend
  (stream-turn [this request on-delta]
    (delegate-stream-turn this request on-delta)))

(>defn new-backend
  "Construct a routing backend.

   `opts`:
   - `:routes`           — vector of `[matcher sub-backend]` pairs (required)
   - `:default-backend`  — backend used when no route matches (optional)

   Returns a backend that also implements `StreamingLLMBackend` iff every
   selectable sub-backend (all routed backends plus any default) supports
   streaming, so `protocol/streaming?` reflects the picked sub-backend and
   `stream-turn` delegates via the same routing logic as `send-turn`."
  [opts]
  [[:map
    [:routes [:vector [:tuple :any :any]]]
    [:default-backend {:optional true} :any]]
   => :any]
  (let [routes  (vec (:routes opts))
        default (:default-backend opts)
        subs    (cond-> (mapv second routes) default (conj default))]
    (if (and (seq subs) (every? proto/streaming? subs))
      (->StreamingMultiBackend routes default)
      (->MultiBackend routes default))))
