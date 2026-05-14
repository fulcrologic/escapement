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
   [com.fulcrologic.guardrails.malli.core :refer [>defn => ?]]
   [escapement.llm.protocol :as proto]))

(defn- match?
  [matcher ^String model]
  (cond
    (instance? java.util.regex.Pattern matcher) (boolean (re-find matcher model))
    (set? matcher)                              (contains? matcher model)
    (fn? matcher)                               (boolean (matcher model))
    :else                                       false))

(defn- pick-backend
  [routes default-backend model]
  (let [m (or model "")]
    (or (some (fn [[matcher b]] (when (match? matcher m) b)) routes)
        default-backend
        (throw (ex-info "No route matched model and no :default-backend configured"
                        {:model           model
                         :route-matchers  (mapv first routes)})))))

(defrecord MultiBackend [routes default-backend]
  proto/LLMBackend
  (send-turn [_ request]
    (let [b (pick-backend routes default-backend (:model request))]
      (proto/send-turn b request))))

(>defn new-backend
       "Construct a routing backend.

        `opts`:
        - `:routes`           — vector of `[matcher sub-backend]` pairs (required)
        - `:default-backend`  — backend used when no route matches (optional)"
       [opts]
       [[:map
         [:routes [:vector [:tuple :any :any]]]
         [:default-backend {:optional true} :any]]
        => :any]
       (->MultiBackend (vec (:routes opts)) (:default-backend opts)))
