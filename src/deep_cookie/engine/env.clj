(ns deep-cookie.engine.env
  "Assemble an `::sc/env` map from our custom engine pieces.

  This replaces `com.fulcrologic.statecharts.simple/simple-env`, which transitively pulls
  `promesa` and crashes under Babashka. Everything required to drive a chart is built from
  the lower-level pieces that are bb-safe."
  (:require
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.algorithms.v20150901 :as alg]
   [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]
   [com.fulcrologic.statecharts.registry.local-memory-registry :as lmr]
   [deep-cookie.engine.exec :as exec]
   [deep-cookie.engine.queue :as queue]
   [deep-cookie.engine.store :as store]
   [deep-cookie.invocation.llm-conversation :as llm-conv]))

(defn new-env
  "Build an `::sc/env` map.

  `opts`:
    * `:checkpoint-dir` (required) - directory for atomic working-memory checkpoints
    * `:invocation-processors` (optional, default `[]`) - vector of `InvocationProcessor` instances
    * `:registry` (optional) - statechart registry; defaults to a fresh `LocalMemoryRegistry`
    * `:queue` (optional) - event queue; defaults to a fresh in-process queue
    * `:store` (optional) - working-memory store; defaults to a file-backed store at `:checkpoint-dir`"
  [{:keys [checkpoint-dir invocation-processors registry queue store
           llm-backend tool-registry transcript-fn]
    :or   {invocation-processors []}}]
  (let [registry  (or registry (lmr/new-registry))
        queue     (or queue (queue/new-queue))
        store     (or store (store/new-store (or checkpoint-dir "checkpoints")))
        dm        (wmdm/new-flat-model)
        exec      (exec/new-execution-model dm queue)
        llm-procs (if (and llm-backend tool-registry)
                    [(llm-conv/new-processor {:backend       llm-backend
                                              :tool-registry tool-registry
                                              :transcript-fn transcript-fn})]
                    [])
        all-procs (into [] (concat invocation-processors llm-procs))]
    (cond-> {::sc/statechart-registry   registry
             ::sc/data-model            dm
             ::sc/event-queue           queue
             ::sc/working-memory-store  store
             ::sc/processor             (alg/new-processor)
             ::sc/invocation-processors all-procs
             ::sc/execution-model       exec}
      tool-registry (assoc :deep-cookie/tool-registry tool-registry))))
