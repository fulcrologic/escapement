(ns escapement.examples.n-subagents-demo
  "Deterministic demo of the dynamic N-subagents pattern using the multiplex
  invocation processor (`com.fulcrologic.statecharts.invocation.multiplex`).

  The parent spawns N worker sub-charts — one per task — that upper-case
  their assigned task and report back via `mux/reply`. The parent
  accumulates per-child replies by `:idx` and transitions on
  `:done.invoke.workers` when the multiplex's aggregator says every child
  has finished.

  No LLM is involved — workers \"work\" by `clojure.string/upper-case`.
  The point is the choreography: dynamic N at runtime, identity
  auto-seeded by the multiplex, library-owned done aggregation.

  Run from the REPL:

    bb -e \"(require '[escapement.examples.n-subagents-demo :as d])
            (require '[escapement.engine.testing :as t])
            (-> (t/new-testing-env {:statechart d/agent})
                (t/start!)
                (t/drain!)
                ((juxt t/configuration t/data))
                prn)\""
  (:require
   [clojure.string :as str]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.data-model.operations :as ops]
   [com.fulcrologic.statecharts.elements
    :refer [final on-entry script state transition]]
   [com.fulcrologic.statecharts.invocation.multiplex :as mux :refer [multiplex]]
   [com.fulcrologic.statecharts.invocation.multiplex-options :as mo]
   [com.fulcrologic.statecharts.protocols :as sp]))

(def default-tasks
  ["alpha" "beta" "gamma" "delta" "epsilon" "zeta"])

(def worker-chart
  "Child chart: reads its `:task` from invocation params, replies with the
  upper-cased result via `mux/reply`, and reaches its `:done` final so the
  multiplex's aggregator can count the completion."
  (chart/statechart
    {:initial :work
     :name    "n-subagents-worker"}
    (state {:id :work}
      (on-entry {}
        (script {:expr
                 (fn [env data]
                   (let [task   (:task data)
                         result (str/upper-case (str task))]
                     (mux/reply env :worker/result {:result result})
                     nil))}))
      ;; Eventless transition: fires after on-entry actions, sending us
      ;; to :done so the session reaches final and the algorithm emits
      ;; the natural done.invoke.<child-sid> the aggregator listens for.
      (transition {:target :done}))
    (final {:id :done})))

(def ^:private worker-chart-id
  "Registry key under which `worker-chart` is registered at parent on-entry.
   The multiplex's `:src` per child resolves through this key."
  ::worker-chart)

;; `^:multi-session?` is REQUIRED: this chart spawns child sessions via the
;; multiplex invocation, so the runner must drain every session's queue (parent
;; + aggregator + each child), not just the parent's. The CLI reads this var
;; metadata and threads it into `runner/run!`. Without it the run wedges — the
;; children's `done.invoke.*` events queue on un-drained child sessions and the
;; parent never sees `:done.invoke.workers`.
(def ^{:multi-session? true} agent
  (chart/statechart
    {:initial :run
     :name    "n-subagents-demo"}
    (state {:id :run :initial :work}
      (on-entry {}
        (script {:expr
                 (fn [env data]
                   (sp/register-statechart!
                     (::sc/statechart-registry env)
                     worker-chart-id
                     worker-chart)
                   (when-not (:tasks data)
                     [(ops/assign :tasks default-tasks)]))}))

      (state {:id :work}
        (multiplex
          {:id             :workers
           mo/child-type   ::sc/chart
           mo/count        (fn [_ data] (count (:tasks data)))
           mo/child-params (fn [_ data idx]
                             {:src    worker-chart-id
                              :params {:task (nth (:tasks data) idx)}})})

        ;; Accumulate per-child replies as they arrive.
        (transition {:event :worker/result :type :internal}
          (script {:expr
                   (fn [_ data]
                     (let [from   (get-in data [:_event :data mo/from])
                           idx    (:idx from)
                           result (get-in data [:_event :data :result])]
                       [(ops/assign :results
                          (assoc (or (:results data) {}) idx result))]))}))

        ;; All workers done — move to final.
        (transition {:event :done.invoke.workers :target :finished}))

      (final {:id :finished}))))
