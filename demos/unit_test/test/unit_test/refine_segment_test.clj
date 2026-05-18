(ns unit-test.refine-segment-test
  "Segment-composition test for the unit-test demo's `:refine` state.

   Demonstrates the pattern documented in `Guide.adoc` under
   `[[chart-segment-testing]]`: any chart node is a plain map, so a single
   state's `def` can be reused inside a minimal shim chart to drive integration
   tests against just that state. The full `unit-test.chart/agent` boots two
   parallel regions and seven LLM-bound states; this test exercises ONLY
   `refine-state` with the real engine, real invocation processor, real
   `params-fn`, and a mocked LLM backend."
  (:require
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :refer [final state]]
   [escapement.engine.testing :as dct]
   [escapement.invocation.llm-conversation :as llmc]
   [escapement.llm.protocol :as llm]
   [escapement.tools.builtin :as builtin]
   [fulcro-spec.core :refer [specification assertions =>]]
   [unit-test.chart :as ut]))

;; ---------------------------------------------------------------------------
;; Shim chart: wraps `refine-state` with the minimum parent state + final
;; required so the segment's `:target :pipeline-done` transitions resolve.
;; ---------------------------------------------------------------------------

(def shim
  (chart/statechart
   {:initial :run}
   (state {:id :run :initial :refine}
          ut/refine-state
          (final {:id :pipeline-done}))))

;; ---------------------------------------------------------------------------
;; Mock LLM backend (same pattern as test/escapement/examples/iterate_test.clj)
;; ---------------------------------------------------------------------------

(defn- pop-first! [a]
  (let [[old _] (swap-vals! a (fn [v] (if (seq v) (subvec v 1) v)))]
    (first old)))

(defrecord MockBackend [responses call-log]
  llm/LLMBackend
  (send-turn [_ request]
    (swap! call-log conj request)
    (or (pop-first! responses)
        (throw (ex-info "mock out of canned responses" {})))))

(defn- mock-backend [responses]
  (->MockBackend (atom (vec responses)) (atom [])))

(defn- tool-use [tool-uses]
  {:stop-reason :tool_use
   :content     (mapv (fn [{:keys [id name input]}]
                        {:type :tool_use :id id :name name :input input})
                      tool-uses)
   :usage       {} :model "mock"})

(defn- end-turn []
  {:stop-reason :end_turn :content [{:type :text :text "ok"}] :usage {} :model "mock"})

;; ---------------------------------------------------------------------------
;; Minimum data required by `refine-params` -> `prompts/render-phase :refine`.
;; The prompt files on disk (demos/unit_test/prompts/refine.md) are real, so
;; these substitution keys must all be present.
;; ---------------------------------------------------------------------------

(defn- refine-data []
  {:source-path        "src/sample/core.clj"
   :function           "tempid"
   :source-namespace   "sample.core"
   :test-file          "/tmp/escapement-segtest/test/sample/core_spec.clj"
   :test-namespace     "sample.core-spec"
   :behaviors-file     "/tmp/escapement-segtest/behaviors.md"
   :mock-strategy-file "/tmp/escapement-segtest/abstraction.md"
   :gap-analysis-file  "/tmp/escapement-segtest/gap-analysis.md"
   :max-iterations     3
   :nrepl-port         50643
   :project-dir        "."})

;; ---------------------------------------------------------------------------
;; Driver
;; ---------------------------------------------------------------------------

(defn- await-config! [t state-kw max-ms]
  (let [deadline (+ (System/currentTimeMillis) max-ms)]
    (loop []
      (dct/drain! t)
      (cond
        (dct/in? t state-kw) t
        (>= (System/currentTimeMillis) deadline) t
        :else (do (Thread/sleep 25) (recur))))))

(defn- run-segment! [backend]
  (let [registry (builtin/new-builtin-registry)
        proc     (llmc/new-processor {:backend backend :tool-registry registry})
        t        (-> (dct/new-testing-env {:statechart    shim
                                           :tool-registry registry}
                                          proc)
                     (dct/start! (refine-data)))]
    (await-config! t :pipeline-done 3000)))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(specification "refine segment: LLM seals -> chart records :sealed status + signature"
               (let [be (mock-backend
                         [(tool-use [{:id "x"
                                      :name "event__refine_sealed"
                                      :input {:signature "abc123" :iterations 2}}])
                          (end-turn)])
                     t  (run-segment! be)
                     d  (dct/data t)]
                 (assertions
                  "reached :pipeline-done"
                  (dct/in? t :pipeline-done) => true
                  "final-status is :sealed"
                  (:final-status d) => :sealed
                  "covers-signature captured from event data"
                  (:covers-signature d) => "abc123")))

(specification "refine segment: LLM gives up -> chart records :gave-up + reason"
               (let [be (mock-backend
                         [(tool-use [{:id "x"
                                      :name "event__refine_give_up"
                                      :input {:reason "tests too flaky"}}])
                          (end-turn)])
                     t  (run-segment! be)
                     d  (dct/data t)]
                 (assertions
                  "reached :pipeline-done"
                  (dct/in? t :pipeline-done) => true
                  "final-status is :gave-up"
                  (:final-status d) => :gave-up
                  "give-up-reason captured from event data"
                  (:give-up-reason d) => "tests too flaky")))
