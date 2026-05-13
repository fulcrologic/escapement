(ns escapement.charts.iterate-test
  "Unit tests for the M7 iterate coding-agent chart.

  Uses a mock `LLMBackend` to drive the LLM-authored event tools, and a tool
  registry that contains a stubbed `:shell/run` whose canned responses are
  controlled by the test. The chart itself is exercised end-to-end through
  the testing harness."
  (:require
   [escapement.charts.iterate :as it]
   [escapement.engine.testing :as dct]
   [escapement.invocation.llm-conversation :as llmc]
   [escapement.llm.protocol :as llm]
   [escapement.tools.builtin :as builtin]
   [escapement.tools.protocol :as tp]
   [fulcro-spec.core :refer [specification assertions =>]])
  (:import (java.util.concurrent LinkedBlockingDeque)))

(defrecord MockBackend [responses call-log]
  llm/LLMBackend
  (send-turn [_ request]
    (swap! call-log conj request)
    (or (.pollFirst ^LinkedBlockingDeque responses)
        (throw (ex-info "mock out of canned responses" {})))))

(defn- mock-backend [responses]
  (let [q (LinkedBlockingDeque.)]
    (doseq [r responses] (.add q r))
    (->MockBackend q (atom []))))

(defn- tool-use [tool-uses]
  {:stop-reason :tool_use
   :content     (mapv (fn [{:keys [id name input]}]
                        {:type :tool_use :id id :name name :input input})
                      tool-uses)
   :usage       {} :model "mock"})

(defn- end-turn []
  {:stop-reason :end_turn :content [{:type :text :text "ok"}] :usage {} :model "mock"})

;; ---- mock shell tool ---------------------------------------------------------

(defrecord StubShellTool [responses calls]
  tp/Tool
  (tool-name [_] :shell/run)
  (description [_] "stub shell")
  (input-schema [_] [:map [:command :string]])
  (invoke [_ input]
    (swap! calls conj input)
    (let [r (.pollFirst ^LinkedBlockingDeque responses)]
      (or r {:result "no canned response" :is-error true}))))

(defn- stub-shell [responses]
  (let [q (LinkedBlockingDeque.)]
    (doseq [r responses] (.add q r))
    (->StubShellTool q (atom []))))

(defn- registry-with-shell [shell-tool]
  (let [reg (builtin/new-builtin-registry)]
    (tp/register! reg shell-tool)
    reg))

;; ---- helpers ----------------------------------------------------------------

(defn- await-config! [t state-kw max-ms]
  (let [deadline (+ (System/currentTimeMillis) max-ms)]
    (loop []
      (dct/drain! t)
      (cond
        (dct/in? t state-kw) t
        (>= (System/currentTimeMillis) deadline) t
        :else (do (Thread/sleep 25) (recur))))))

(defn- run-chart!
  [{:keys [backend shell-responses initial-data]}]
  (let [shell    (stub-shell (or shell-responses []))
        registry (registry-with-shell shell)
        proc     (llmc/new-processor {:backend backend :tool-registry registry})
        t        (-> (dct/new-testing-env {:statechart    it/agent
                                           :tool-registry registry}
                                          proc)
                     (dct/start! initial-data))]
    (let [t (await-config! t :finished 4000)]
      {:t t :shell-calls (-> shell :calls deref) :backend backend})))

;; ============================================================================
;; Tests
;; ============================================================================

(specification "iterate chart: happy path — patch passes on first try"
               (let [be (mock-backend
                         [(tool-use [{:id "s" :name "event__spec_ready"
                                      :input {:summary "make square"}}])
                          (end-turn)
                          (tool-use [{:id "p" :name "event__patch_applied"
                                      :input {:rationale "wrote square"}}])
                          (end-turn)])
                     {:keys [t shell-calls]}
                     (run-chart! {:backend be
                                  :shell-responses [{:result "ok" :is-error false}]
                                  :initial-data {:spec-path "/tmp/spec" :target-path "/tmp/t.clj"
                                                 :test-cmd "true" :max-iterations 3}})
                     d (dct/data t)]
                 (assertions
                  "reached :finished"
                  (dct/in? t :finished) => true
                  "final-status is :passed"
                  (:final-status d) => :passed
                  "exactly one shell invocation"
                  (count shell-calls) => 1
                  "iterations incremented to 1"
                  (:iterations d) => 1)))

(specification "iterate chart: retry then pass"
               (let [be (mock-backend
                         [(tool-use [{:id "s" :name "event__spec_ready"
                                      :input {:summary "make square"}}])
                          (end-turn)
                          (tool-use [{:id "p1" :name "event__patch_applied"
                                      :input {:rationale "first attempt"}}])
                          (end-turn)
                          (tool-use [{:id "r" :name "event__retry"
                                      :input {:reasoning "try again"}}])
                          (end-turn)
                          (tool-use [{:id "p2" :name "event__patch_applied"
                                      :input {:rationale "second attempt"}}])
                          (end-turn)])
                     {:keys [t shell-calls]}
                     (run-chart! {:backend be
                                  :shell-responses [{:result "boom" :is-error true}
                                                    {:result "ok" :is-error false}]
                                  :initial-data {:spec-path "/tmp/spec" :target-path "/tmp/t.clj"
                                                 :test-cmd "true" :max-iterations 3}})
                     d (dct/data t)]
                 (assertions
                  "reached :finished"
                  (dct/in? t :finished) => true
                  "final-status is :passed"
                  (:final-status d) => :passed
                  "two shell invocations"
                  (count shell-calls) => 2
                  "iterations is 2"
                  (:iterations d) => 2)))

(specification "iterate chart: exhaust iterations"
               (let [be (mock-backend
                         [(tool-use [{:id "s" :name "event__spec_ready"
                                      :input {:summary "make square"}}])
                          (end-turn)
                          (tool-use [{:id "p1" :name "event__patch_applied"
                                      :input {:rationale "first"}}])
                          (end-turn)
                          (tool-use [{:id "r1" :name "event__retry"
                                      :input {:reasoning "again"}}])
                          (end-turn)
                          (tool-use [{:id "p2" :name "event__patch_applied"
                                      :input {:rationale "second"}}])
                          (end-turn)
                          (tool-use [{:id "r2" :name "event__retry"
                                      :input {:reasoning "yet again"}}])
                          (end-turn)])
                     {:keys [t shell-calls]}
                     (run-chart! {:backend be
                                  :shell-responses [{:result "boom1" :is-error true}
                                                    {:result "boom2" :is-error true}]
                                  :initial-data {:spec-path "/tmp/spec" :target-path "/tmp/t.clj"
                                                 :test-cmd "true" :max-iterations 2}})
                     d (dct/data t)]
                 (assertions
                  "reached :finished"
                  (dct/in? t :finished) => true
                  "final-status is :exhausted"
                  (:final-status d) => :exhausted
                  "two shell invocations (capped by :max-iterations)"
                  (count shell-calls) => 2
                  "iterations equals max-iterations"
                  (:iterations d) => 2)))

(specification "iterate chart: give-up"
               (let [be (mock-backend
                         [(tool-use [{:id "s" :name "event__spec_ready"
                                      :input {:summary "make square"}}])
                          (end-turn)
                          (tool-use [{:id "p1" :name "event__patch_applied"
                                      :input {:rationale "first"}}])
                          (end-turn)
                          (tool-use [{:id "g" :name "event__give_up"
                                      :input {:reason "stuck"}}])
                          (end-turn)])
                     {:keys [t shell-calls]}
                     (run-chart! {:backend be
                                  :shell-responses [{:result "boom" :is-error true}]
                                  :initial-data {:spec-path "/tmp/spec" :target-path "/tmp/t.clj"
                                                 :test-cmd "true" :max-iterations 3}})
                     d (dct/data t)]
                 (assertions
                  "reached :finished"
                  (dct/in? t :finished) => true
                  "final-status is :gave-up"
                  (:final-status d) => :gave-up
                  "one shell invocation"
                  (count shell-calls) => 1)))
