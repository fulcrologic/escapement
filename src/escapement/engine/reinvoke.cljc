(ns escapement.engine.reinvoke
  "Re-invoke-on-resume primitive (engine core).

   THE SINGLE SEAM that reaches into the statechart library's invocation
   internals. Everything else in escapement stays decoupled from the library's
   processing-context machinery; an RC bump only has to be reconciled here.

   ## Why this exists

   The statechart library (`com.fulcrologic/statecharts`, RC16/RC18 family)
   does NOT re-invoke a state that is already present in a restored
   `::sc/configuration` on resume. Invocations start ONLY via the library's
   `run-invocations!`, which iterates `::sc/states-to-invoke`; that set is
   filled only when a state is *entered this macrostep*
   (`enter-states!`: `update ::sc/states-to-invoke conj s`) and is *cleared at
   every macrostep boundary* (`with-processing-context`). On resume the
   conversation node is already in `::sc/configuration` → never entered →
   `states-to-invoke` is empty → `run-invocations!` starts nothing → the LLM is
   never called. The same gap breaks real crash-resume mid-invocation.

   This primitive closes that gap: given a restored working memory whose
   configuration contains states that own `<invoke>` elements, it builds a
   processing-env, seeds `::sc/states-to-invoke` with exactly those invoking
   states, and calls the library `run-invocations!` so their invocations start
   (the llm-conversation processor keys workers by `[session-id invokeid]` and
   kills any same-key worker first, so this is idempotent).

   ## Library (RC16/RC18) functions/vars this depends on

   - `com.fulcrologic.statecharts.algorithms.v20150901-impl/processing-env`
     — builds the `::sc/processing-env` (`::sc/statechart`, `::sc/vwmem`
     volatile, `::sc/context-element-id`) from a base env + a wmem.
   - `com.fulcrologic.statecharts.algorithms.v20150901-impl/run-invocations!`
     — iterates `::sc/states-to-invoke` and starts each state's invocations.
   - `com.fulcrologic.statecharts.chart/invocations` — IDs of `<invoke>`
     elements owned by a state (we treat a state as 'invoking' iff non-empty).
   - `com.fulcrologic.statecharts.chart/document-ordered-set` — the concrete
     set type `states-to-invoke` is expected to be.

   NOTE: we deliberately do NOT use the library's `with-processing-context`
   macro, because its first act is to RESET `::sc/states-to-invoke` to empty —
   exactly the value we need to populate. We set the volatile directly after
   `processing-env` and then call `run-invocations!`."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.algorithms.v20150901-impl :as impl]
    [com.fulcrologic.statecharts.chart :as sc-chart]
    [com.fulcrologic.statecharts.protocols :as sp]))

(defn invoking-states-in-config
  "The subset of `wmem`'s `::sc/configuration` whose states own `<invoke>`
   elements, given the resolved `statechart`. Returns a (possibly empty) set of
   state ids. Pure; no side effects."
  [statechart wmem]
  (into #{}
    (filter (fn [state-id]
              (try (seq (sc-chart/invocations statechart state-id))
                   (catch #?(:clj Throwable :cljs :default) _ false))))
    (::sc/configuration wmem #{})))

(defn reinvoke-active-invocations!
  "Re-start the invocations of every invoking state present in the restored
   `wmem`'s configuration.

   Args:
     * `env`  — the live `::sc/env` (carries `::sc/statechart-registry`,
                `::sc/invocation-processors`, the queue/store, and escapement's
                env seams). The SAME env the runner pumps with.
     * `wmem` — the restored working memory (typically the just-loaded resume
                checkpoint, with the conversation node already in
                `::sc/configuration`).

   Resolves the chart from `(::sc/statechart-src wmem)` via the registry, finds
   the invoking states in the configuration, and (if any) calls the library
   `run-invocations!` with `::sc/states-to-invoke` seeded to them.

   Returns the (post-invoke) working memory map derived from the processing
   env's volatile. A no-op (returns `wmem` unchanged) when the configuration
   contains no invoking states — so a normal (non-resume) start, or a resume
   into a non-invoking state, costs nothing.

   Side effect: starts invocation workers (e.g. an llm-conversation worker
   thread). The processor's own idempotency (kill-same-key-worker-first)
   guarantees at most one live worker per `[session-id invokeid]`."
  [env wmem]
  (let [src        (::sc/statechart-src wmem)
        registry   (::sc/statechart-registry env)
        statechart (when (and src registry)
                     (try (sp/get-statechart registry src)
                          (catch #?(:clj Throwable :cljs :default) _ nil)))
        invoking   (when statechart (invoking-states-in-config statechart wmem))]
    (if (empty? invoking)
      wmem
      (let [penv  (impl/processing-env env src wmem)
            vwmem (::sc/vwmem penv)]
        ;; Seed states-to-invoke with the invoking states (the concrete
        ;; document-ordered-set type the library expects), then run.
        (vswap! vwmem assoc ::sc/states-to-invoke
          (apply sc-chart/document-ordered-set statechart invoking))
        (impl/run-invocations! penv)
        @vwmem))))
