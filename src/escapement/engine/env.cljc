(ns escapement.engine.env
  "Assemble an `::sc/env` map from our custom engine pieces.

  This replaces `com.fulcrologic.statecharts.simple/simple-env`, which transitively pulls
  `promesa` and crashes under Babashka. Everything required to drive a chart is built from
  the lower-level pieces that are bb-safe."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.algorithms.v20150901 :as alg]
    [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]
    [com.fulcrologic.statecharts.invocation.multiplex-processor :as mux-proc]
    [com.fulcrologic.statecharts.invocation.statechart :as chart-invoke]
    [com.fulcrologic.statecharts.registry.local-memory-registry :as lmr]
    [escapement.chart.service :as-alias service]
    [escapement.engine.exec :as exec]
    [escapement.engine.queue :as queue]
    [escapement.engine.store :as store]
    [escapement.invocation.human-input :as human-input]
    [escapement.invocation.llm-conversation :as llm-conv]))

(defn new-env
  "Build an `::sc/env` map.

  `opts`:
    * `:checkpoint-dir` (required) - directory for atomic working-memory checkpoints
    * `:invocation-processors` (optional, default `[]`) - vector of `InvocationProcessor` instances
    * `:registry` (optional) - statechart registry; defaults to a fresh `LocalMemoryRegistry`
    * `:queue` (optional) - event queue; defaults to a fresh in-process queue
    * `:store` (optional) - working-memory store; defaults to a file-backed store at `:checkpoint-dir`
    * `:llm-catalog-ratings` (optional) - subjective ratings table threaded
      to the llm-conversation processor's eligibility gate. Resolved ONCE by
      the caller (CLI: from disk config at startup; lib facade Step 4: from
      injected `:config`). Defaults to `{}` in the processor.
    * `:llm-eligibility-strict?` (optional) - fail-closed flag for the
      eligibility gate (see `escapement.invocation.llm-conversation/new-processor`).
    * `:llm-aliases` (optional) - `:llm/aliases` map (`{alias-kw [target-map …]}`)
      threaded to the llm-conversation processor for keyword-`:model` resolution.
    * `:llm-preferences` (optional) - `:llm/preferences` vector of alias keywords;
      the default candidate set flattened when a node names no model. Defaults to
      the built-in `preferences/default-preferences` in the processor."
  [{:keys [checkpoint-dir invocation-processors registry queue store
           llm-backend llm-default-models llm-catalog-ratings llm-eligibility-strict?
           llm-aliases llm-preferences
           tool-registry transcript-fn human-renderer
           session-dir artifact-store]
    :or   {invocation-processors []}}]
  (let [registry  (or registry (lmr/new-registry))
        queue     (or queue (queue/new-queue))
        store     (or store (store/new-store (or checkpoint-dir "checkpoints")))
        dm        (wmdm/new-flat-model)
        exec      (exec/new-execution-model dm queue)
        llm-procs (if (and llm-backend tool-registry)
                    [(llm-conv/new-processor {:backend             llm-backend
                                              :tool-registry       tool-registry
                                              :transcript-fn       transcript-fn
                                              :default-models      llm-default-models
                                              :catalog-ratings     llm-catalog-ratings
                                              :eligibility-strict? llm-eligibility-strict?
                                              :aliases             llm-aliases
                                              :preferences         llm-preferences})]
                    [])
        hi-procs  (if human-renderer
                    [(human-input/new-processor {:renderer      human-renderer
                                                 :transcript-fn transcript-fn})]
                    [])
        ;; Statechart-as-invokable: needed both for plain <invoke type=::sc/chart>
        ;; and as a dependency of the multiplex processor (it spawns its own
        ;; aggregator chart via the statechart processor).
        chart-procs [(chart-invoke/new-invocation-processor)]
        ;; Multiplex: general dynamic-N fanout over any registered invocation type.
        mux-procs [(mux-proc/new-processor)]
        all-procs (into [] (concat invocation-processors llm-procs hi-procs chart-procs mux-procs))]
    (cond-> {::sc/statechart-registry   registry
             ::sc/data-model            dm
             ::sc/event-queue           queue
             ::sc/working-memory-store  store
             ::sc/processor             (alg/new-processor)
             ::sc/invocation-processors all-procs
             ::sc/execution-model       exec
             ;; Per-chart-run registry for service-region tool declarations.
             ;; See `escapement.chart.service`. Empty at start; populated by
             ;; on-entry actions calling `service/register-tool!`.
             ::service/registry         (atom {})
             ;; Per-(session,node) entry counter the emit/capture layer reads to
             ;; stamp `:transcript/visit`. The library does not track re-entry,
             ;; so we own it (one atom per run; see `io-refactor-plan.md` §3).
             :escapement/visit-counts   (atom {})}
      tool-registry (assoc :escapement/tool-registry tool-registry)
      session-dir (assoc :escapement/session-dir session-dir)
      ;; Injected by the host (the bb runner builds a disk store from session-dir;
      ;; a browser host would inject an IndexedDB store). The capture layer writes
      ;; full LLM request/response/tool-result blobs here; absent => capture is a no-op.
      artifact-store (assoc :escapement/artifact-store artifact-store)
      ;; Surface the transcript fn on the env so chart actions (e.g.
      ;; `helpers/capture-llm-output`) can emit `:artifact/captured`.
      transcript-fn (assoc :escapement/transcript-fn transcript-fn)
      ;; Surface the LLM backend + model-resolution inputs so chart `<script>`
      ;; expressions can make one-shot / fan-out LLM calls via the env-aware
      ;; `escapement.llm/ask` + `map-prompt` helpers, resolving aliases exactly
      ;; as the `:llm-conversation` worker does. (The worker still receives these
      ;; directly via its processor; this only ALSO exposes them on the env.)
      llm-backend             (assoc :escapement/llm-backend llm-backend)
      llm-default-models      (assoc :escapement/llm-default-models llm-default-models)
      llm-aliases             (assoc :escapement/llm-aliases llm-aliases)
      llm-preferences         (assoc :escapement/llm-preferences llm-preferences)
      llm-catalog-ratings     (assoc :escapement/llm-catalog-ratings llm-catalog-ratings)
      llm-eligibility-strict? (assoc :escapement/llm-eligibility-strict? llm-eligibility-strict?))))
