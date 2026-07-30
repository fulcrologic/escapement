(ns escapement.replay
  "Refinement/replay primitives over captured LLM I/O (see `io-refactor-plan.md` §5b).

   This namespace ships granularity #1: **single-turn refine** — re-issue ONE captured turn with a
   tuned prompt/model/params, with no statechart engine involved. It is the tight prompt-tuning
   inner loop: load the captured request, deep-merge overrides, send it to a live backend, and hand
   back the new response next to the original request for diffing.

   Granularity #2 — **node-invocation refine** (`refine-node`, from `seed.edn`) — re-issues a node's
   opening assistant turn with its original params/tools, tuned by overrides. It reuses the same turn
   engine (`escapement.llm/run-turn`) the live worker uses, so model resolution + resilience match
   production; it is CLJ-only (that engine is CLJ), guarded by a reader conditional so this namespace
   still loads under CLJS. Sub-chart refine (#3, from a checkpoint) is `escapement.replay.chart`.

   CLJC with the backend injected (never reached for globally), so the same code runs against the
   bb backend or a browser LLM remote."
  (:require
    [clojure.edn :as edn]
    [com.fulcrologic.statecharts.promise :as p]
    [escapement.capture :as capture]
    [escapement.llm.protocol :as llm]
    [escapement.protocols :as proto]
    #?(:clj [escapement.llm :as ellm])
    #?(:clj [escapement.invocation.llm-conversation :as llm-conv])))

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

(defn load-seed
  "Load the captured replayable seed `{:params … :initial-messages …}` for the `(node-id, visit)`
   invocation of `session-id` from the `store` (an `ArtifactStore`), or `nil` when none was captured."
  [store session-id node-id visit]
  (some-> (proto/read-artifact store session-id (capture/seed-locator node-id visit)) read-edn))

#?(:clj
   (defn refine-node
     "Re-issue the OPENING assistant turn of the node invocation at `(node-id, visit)` of `session-id`
      from its captured `seed.edn`, tuned by `opts`, WITHOUT re-running the chart. This is the
      node-level analogue of `refine-turn`: `refine-turn` tunes ONE recorded request; `refine-node`
      re-derives the request from the node's SEED (resolved params + initial messages + its tool
      palette) so you can experiment with a different system prompt, model, temperature, tool set, or
      input messages before any turns were taken. It runs through the same `escapement.llm/run-turn`
      engine the live worker uses (model resolution, `:needs` gate, retry/failover, overrun), so the
      result matches production.

      Returns `{:params <effective> :messages <effective> :tools <defs> :response <Response>
      :model-used <str> :status <run-turn status> :seed <original>}`.

      `opts`:
        * `:backend`       (required) — an `LLMBackend` to issue the turn against.
        * `:tool-registry` (optional) — resolves the seed's `:real-tools`; omit to replay with only the
                                        node's event tools (region tools are never included headlessly).
        * `:overrides`     (optional) — a partial PARAMS map deep-merged onto the seed params, e.g.
                                        `{:system \"tuned\" :model \"claude-opus-5\" :temperature 0.2}`.
        * `:messages`      (optional) — replacement message vector; defaults to the seed's
                                        `:initial-messages` (override to replay a different prefix).
        * `:aliases` / `:preferences` (optional) — model-resolution inputs forwarded to `run-turn`.
        * `:pinned`        (optional) — a single candidate map `{:provider :model :params}` that
                                        SHORT-CIRCUITS model resolution and runs exactly that model
                                        against `:backend` (no catalog/alias lookup, no failover). Use
                                        this to force an arbitrary model string in an experiment, or to
                                        drive a mock backend deterministically.

      NOTE this re-runs a SINGLE assistant turn (the node's opening turn). A full multi-turn tool loop
      is a chart concern — fork the whole node via `escapement.replay.chart`. Throws if no seed exists."
     [store session-id node-id visit {:keys [backend tool-registry overrides messages aliases preferences pinned]}]
     (assert backend "refine-node requires a :backend")
     (let [seed (load-seed store session-id node-id visit)]
       (when-not seed
         (throw (ex-info "No captured seed to refine at these coordinates"
                  {:reason :no-captured-seed
                   :session-id session-id :node-id node-id :visit visit})))
       (let [params    (deep-merge (:params seed) overrides)
             messages  (vec (or messages (:initial-messages seed)))
             tool-defs (llm-conv/node-tool-defs tool-registry params)
             result    (ellm/run-turn
                         (cond-> {:backend backend}
                           aliases     (assoc :aliases aliases)
                           preferences (assoc :preferences preferences)
                           pinned      (assoc :pinned pinned))
                         params messages tool-defs)]
         {:params     params
          :messages   messages
          :tools      tool-defs
          :response   (:response result)
          :model-used (:model result)
          :status     (:status result)
          :seed       seed}))))

(defn refine-turn
  "Re-issue the single captured LLM turn at `(node-id, visit, turn)` of `session-id`, tuned by
   `opts`, WITHOUT re-running the chart. Returns
   `{:request <effective> :response <new Response> :original-request <captured>}`; the caller diffs.

   `opts`:
     * `:backend`   (required) — an `escapement.llm.protocol/LLMBackend` to issue the turn against.
     * `:overrides` (optional) — a partial request map deep-merged onto the captured request, e.g.
                                 `{:system \"tuned prompt\" :model \"claude-opus-5\" :temperature 0.2}`.

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
