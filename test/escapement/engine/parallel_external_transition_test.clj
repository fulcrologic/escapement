(ns escapement.engine.parallel-external-transition-test
  "Minimal reproducer for the question 'does an EXTERNAL transition declared
   on the parent of a substate, with target inside the same parent, cause
   sibling parallel regions to be exited and re-entered?'

   Chart shape:

   :root (compound, initial :work)
     :work (parallel)
       :A  (compound, initial :A1)
         (transition {:event :flip :target :A2 :type EXTERNAL_OR_INTERNAL})
         :A1 (atomic)
         :A2 (atomic)
       :B  (compound, initial :B1)
         :B1 (atomic, on-entry/on-exit bump counters)

   We watch the entry/exit counters of :B1. If firing :flip causes B1 to
   exit/re-enter, the sibling region was exited. Per SCXML §3.13 that
   only happens for EXTERNAL transitions because the LCCA of source :A
   plus target :A2 is the PARENT of :A (proper ancestors do NOT include
   :A itself), i.e. the parallel :work — exiting :work exits both branches."
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [on-entry on-exit parallel
                                                  script state transition]]
    [escapement.engine.testing :as dct]
    [fulcro-spec.core :refer [assertions specification]]))

(defn- build-chart
  [transition-type b1-entries b1-exits]
  (chart/statechart
    {:initial :work}
    (parallel {:id :work}
      (state {:id :A :initial :A1}
        (transition {:event :flip :target :A2 :type transition-type})
        (state {:id :A1})
        (state {:id :A2}))
      (state {:id :B :initial :B1}
        (state {:id :B1}
          (on-entry {} (script {:expr (fn [_ _] (swap! b1-entries inc) nil)}))
          (on-exit {} (script {:expr (fn [_ _] (swap! b1-exits inc) nil)})))))))

(specification "external substate transition declared on parent of A exits/re-enters sibling B"
  (let [b1-entries          (atom 0)
        b1-exits            (atom 0)
        chart               (build-chart :external b1-entries b1-exits)
        t                   (-> (dct/new-testing-env {:statechart chart})
                              (dct/start!))
        entries-after-start @b1-entries
        exits-after-start   @b1-exits
        t                   (dct/run-events! t :flip)]
    (assertions
      "B1 entered exactly once on startup"
      entries-after-start => 1
      "B1 not exited on startup"
      exits-after-start => 0
      "after :flip, A is in :A2"
      (dct/in? t :A2) => true
      "*** with :external transition, B1 was exited and re-entered (sibling restart) ***"
      @b1-exits => 1
      @b1-entries => 2)))

(specification "INTERNAL substate transition declared on parent of A does NOT exit sibling B"
  (let [b1-entries (atom 0)
        b1-exits   (atom 0)
        chart      (build-chart :internal b1-entries b1-exits)
        t          (-> (dct/new-testing-env {:statechart chart})
                     (dct/start!))
        t          (dct/run-events! t :flip)]
    (assertions
      "after :flip, A is in :A2"
      (dct/in? t :A2) => true
      "with :internal transition, B1 was NOT exited"
      @b1-exits => 0
      "with :internal transition, B1 was entered exactly once (at startup only)"
      @b1-entries => 1)))
