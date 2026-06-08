(ns escapement.debug.reinvoke-resume-test
  "Foundation characterization + crash-resume regression for re-invoke-on-resume.

   The statechart library (RC) does NOT re-invoke a state already present in a
   restored `::sc/configuration` on resume: invocations start only via
   `run-invocations!`, fed by `::sc/states-to-invoke`, which is filled on state
   ENTRY and cleared every macrostep. On resume the conversation node is already
   in the configuration, never entered → nothing re-invokes.

   This test forks a branch from a node-entry checkpoint (node IN config) and
   resumes it; before the fix the conversation worker never re-runs and the
   chart goes quiescent without consuming the mock response. After the fix the
   worker re-invokes, consumes the response, and the chart reaches `:done`."
  (:require
    [clojure.java.io :as io]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final on-entry send state transition]]
    [com.fulcrologic.statecharts.promise :as p]
    [escapement.chart.helpers :as h]
    [escapement.debug.branch :as branch]
    [escapement.engine.store :as store]
    [escapement.llm.protocol :as llm]
    [escapement.runner :as runner]
    [escapement.test-support :as ts]
    [escapement.tools.protocol :as tp]
    [fulcro-spec.core :refer [=> assertions specification]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir [] (str (Files/createTempDirectory "reinvoke" (into-array FileAttribute []))))

;; Mock backend that records each call so a test can assert the worker re-ran.
(defrecord RecordingBackend [responses calls]
  llm/LLMBackend
  (send-turn [_ request]
    (swap! calls conj request)
    (p/do! (or (ts/pop-first! responses)
             {:stop-reason :end_turn
              :content     [{:type :text :text "done"}]
              :usage       {:input-tokens 1 :output-tokens 1}
              :model       "mock"}))))

;; `:warmup` self-sends a delayed `:go`, so `:talk` (an llm-conversation node) is
;; entered by a real EVENT and a node-entry checkpoint exists before it runs.
(def ^:private talk-chart
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :warmup}
      (state {:id :warmup}
        (on-entry {} (send {:event :go :delay 1}))
        (transition {:event :go :target :talk}))
      (state {:id :talk}
        (h/llm-conversation {:id "writer" :message "hi"})
        (transition {:event :llm.idle :target :done}))
      (final {:id :done}))))

(defn- run-chart! [{:keys [sdir chk session-dir sid responses calls]}]
  (runner/run! {:chart              talk-chart
                :session-id         sid
                :transcript-path    (str sdir "/transcript.jsonl")
                :checkpoint-dir     chk
                :session-dir        session-dir
                :backend            (->RecordingBackend (ts/queue (or responses [])) calls)
                :tool-registry      (tp/new-registry)
                :quiescent-sleep-ms 5}))

(defn- transcript-rows [path]
  (when (.exists (io/file path))
    (with-open [r (io/reader path)] (doall (line-seq r)))))

(specification "fork node-entry checkpoint -> resume re-invokes the conversation worker"
  (let [dir     (tmp-dir)
        sid     "reinvoke-parent"
        sdir    (str dir "/" sid)
        chk     (str sdir "/checkpoints")
        _       (.mkdirs (io/file chk))
        calls   (atom [])
        ;; Parent run: completes; writes a node-entry checkpoint at :talk v0.
        _       (run-chart! {:sdir sdir :chk chk :session-dir sdir :sid sid :calls calls})
        ;; Fork a branch from the node-entry checkpoint (node IN config).
        branch  (branch/fork-session!
                  {:parent-session-id sid
                   :branch-point      {:node-id "talk" :visit 0 :turn 0}
                   :work-dir          dir})
        bid     (:branch-id branch)
        bcalls  (atom [])
        ;; Resume the branch. Re-invoke must restart the conversation node.
        result  (runner/run! {:chart              talk-chart
                              :session-id         bid
                              :transcript-path    (:transcript-path branch)
                              :checkpoint-dir     (:checkpoint-dir branch)
                              :session-dir        (:session-dir branch)
                              :resume?            true
                              :backend            (->RecordingBackend (ts/queue []) bcalls)
                              :tool-registry      (tp/new-registry)
                              :quiescent-sleep-ms 5})
        rows    (transcript-rows (:transcript-path branch))]
    (assertions
      "the branch seeded from the :node-entry source (node in config)"
      (:seed-source branch) => :node-entry
      "resume re-invoked the conversation worker (backend was called on the branch)"
      (pos? (count @bcalls)) => true
      "the branch transcript logged at least one :llm/* row (worker actually ran)"
      (boolean (some #(re-find #"\"llm/" %) rows)) => true
      "the branch chart advanced to its final :done state"
      (contains? (set (:final-config result)) :done) => true)))

(specification "crash-resume mid-invocation: a fresh runner restarts the in-flight conversation"
  (let [dir     (tmp-dir)
        sid     "crash-resume"
        sdir    (str dir "/" sid)
        chk     (str sdir "/checkpoints")
        _       (.mkdirs (io/file chk))
        calls   (atom [])
        ;; First run completes and persists the node-in-config canonical
        ;; checkpoint. The node-entry checkpoint is the restorable mid-flight
        ;; snapshot. We simulate a crash by forking a clean session from it
        ;; (no live worker survives) and resuming.
        _       (run-chart! {:sdir sdir :chk chk :session-dir sdir :sid sid :calls calls})
        branch  (branch/fork-session!
                  {:parent-session-id sid
                   :branch-point      {:node-id "talk" :visit 0 :turn 0}
                   :work-dir          dir})
        bcalls  (atom [])
        result  (runner/run! {:chart              talk-chart
                              :session-id         (:branch-id branch)
                              :transcript-path    (:transcript-path branch)
                              :checkpoint-dir     (:checkpoint-dir branch)
                              :session-dir        (:session-dir branch)
                              :resume?            true
                              :backend            (->RecordingBackend (ts/queue []) bcalls)
                              :tool-registry      (tp/new-registry)
                              :quiescent-sleep-ms 5})]
    (assertions
      "the dead in-flight invocation is restarted on resume (backend called)"
      (pos? (count @bcalls)) => true
      "and the run completes"
      (= (:status result) :done) => true)))
