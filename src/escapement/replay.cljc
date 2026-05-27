(ns escapement.replay
  "Refinement/replay primitives over captured LLM I/O (see `io-refactor-plan.md` §5b).

   This namespace ships granularity #1: **single-turn refine** — re-issue ONE captured turn with a
   tuned prompt/model/params, with no statechart engine involved. It is the tight prompt-tuning
   inner loop: load the captured request, deep-merge overrides, send it to a live backend, and hand
   back the new response next to the original request for diffing.

   Node-invocation refine (#2, from `seed.edn`) and sub-chart refine (#3, from a checkpoint) are
   designed-for but not implemented here.

   CLJC with the backend injected (never reached for globally), so the same code runs against the
   bb backend or a browser LLM remote."
  (:require
    [clojure.edn :as edn]
    [com.fulcrologic.statecharts.promise :as p]
    [escapement.capture :as capture]
    [escapement.llm.protocol :as llm]
    [escapement.protocols :as proto]))

(defn- read-edn [s]
  (edn/read-string {:default tagged-literal} s))

(defn deep-merge
  "Recursively merge maps; for matching keys whose values are both maps, merge them, otherwise the
   later value wins. Non-map collections (e.g. `:messages`) are replaced wholesale."
  [& maps]
  (let [maps (remove nil? maps)]
    (when (seq maps)
      (apply merge-with
        (fn [a b] (if (and (map? a) (map? b)) (deep-merge a b) b))
        maps))))

(defn load-request
  "Load the captured base request map for the turn at `(node-id, visit, turn)` of `session-id` from
   the `store` (an `ArtifactStore`), or `nil` if no request was captured there."
  [store session-id node-id visit turn]
  (let [path (str (capture/turn-dir node-id visit turn) "/request.edn")]
    (some-> (proto/read-artifact store session-id path) read-edn)))

(defn refine-turn
  "Re-issue the single captured LLM turn at `(node-id, visit, turn)` of `session-id`, tuned by
   `opts`, WITHOUT re-running the chart. Returns
   `{:request <effective> :response <new Response> :original-request <captured>}`; the caller diffs.

   `opts`:
     * `:backend`   (required) — an `escapement.llm.protocol/LLMBackend` to issue the turn against.
     * `:overrides` (optional) — a partial request map deep-merged onto the captured request, e.g.
                                 `{:system \"tuned prompt\" :model \"claude-opus-4-7\" :temperature 0.2}`.

   Throws if no request was captured at the coordinates (nothing to refine)."
  [store session-id node-id visit turn {:keys [backend overrides]}]
  (assert backend "refine-turn requires a :backend")
  (let [original (load-request store session-id node-id visit turn)]
    (when-not original
      (throw (ex-info "No captured request to refine at these coordinates"
               {:reason :no-captured-request
                :session-id session-id :node-id node-id :visit visit :turn turn})))
    (let [effective (deep-merge original overrides)
          response  (p/await! (llm/send-turn* backend effective nil))]
      {:request          effective
       :response         response
       :original-request original})))
