(ns escapement.runner-pause-test
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final state transition]]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.debug.controller :as dbg]
    [escapement.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir [prefix]
  (str (Files/createTempDirectory prefix (into-array FileAttribute []))))

(defn- read-jsonl [path]
  (with-open [r (io/reader path)]
    (mapv #(json/parse-string % true) (doall (line-seq r)))))

(defn- await-event!
  "Polls `transcript` (JSONL) up to `timeout-ms` waiting for any row whose
   `:event` equals `event-name`. Returns true if found, false on timeout."
  [transcript event-name timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [hit? (try
                   (and (.exists (io/file transcript))
                     (boolean
                       (some (fn [r] (= event-name (:event r)))
                         (read-jsonl transcript))))
                   (catch Throwable _ false))]
        (cond
          hit? true
          (>= (System/currentTimeMillis) deadline) false
          :else (do (Thread/sleep 25) (recur)))))))

;; A chart that waits for an external :go event before transitioning to a
;; final state. Lets us inject one external event and observe pause behavior.
(def ^:private wait-then-done-chart
  (chart/statechart
    {:initial :idle}
    (state {:id :idle}
      (transition {:event :go :target :done}))
    (final {:id :done})))

(specification "runner debug pause-gate — controller in :run mode"
  (let [dir        (tmp-dir "pause-run")
        transcript (str dir "/run.jsonl")
        chk        (str dir "/chk")
        ctl        (dbg/new-controller)
        env-p      (promise)
        ^Thread t  (Thread.
                     ^Runnable
                     (fn []
                       (try
                         (runner/run! {:chart              wait-then-done-chart
                                       :session-id         :pause-test/run
                                       :transcript-path    transcript
                                       :checkpoint-dir     chk
                                       :debug-controller   ctl
                                       :on-env-ready       (fn [env] (deliver env-p env))
                                       :max-iterations     200
                                       :quiescent-sleep-ms 10})
                         (catch Throwable _ nil))))]
    (.start t)
    (let [env (deref env-p 2000 nil)]
      (sp/send! (::sc/event-queue env) env {:event :go :target :pause-test/run}))
    (.join t 3000)
    (let [rows (read-jsonl transcript)
          ev   (set (map :event rows))]
      (assertions
        "runner exits cleanly when controller is :run"
        (.isAlive t) => false

        "no :debug/awaiting-step events fire"
        (contains? ev "debug/awaiting-step") => false

        "the injected :go event was processed"
        (boolean (some (fn [r]
                         (and (= "runner/event-processed" (:event r))
                           (= "go" (some-> r :data :event-name))))
                   rows)) => true))))

(specification "runner debug pause-gate — initial-pause halts on first event until released"
  (let [dir        (tmp-dir "pause-step")
        transcript (str dir "/run.jsonl")
        chk        (str dir "/chk")
        ctl        (dbg/new-controller {:initial-pause? true})
        env-p      (promise)
        ^Thread t  (Thread.
                     ^Runnable
                     (fn []
                       (try
                         (runner/run! {:chart              wait-then-done-chart
                                       :session-id         :pause-test/step
                                       :transcript-path    transcript
                                       :checkpoint-dir     chk
                                       :debug-controller   ctl
                                       :on-env-ready       (fn [env] (deliver env-p env))
                                       :max-iterations     200
                                       :quiescent-sleep-ms 10})
                         (catch Throwable _ nil))))]
    (.start t)
    (let [env (deref env-p 2000 nil)]
      ;; Inject the only event the chart cares about. The runner will pick it
      ;; up but pause before processing because the controller starts paused.
      (sp/send! (::sc/event-queue env) env {:event :go :target :pause-test/step}))

    (let [awaited? (await-event! transcript "debug/awaiting-step" 2000)]
      (assertions
        "runner emits :debug/awaiting-step while paused"
        awaited? => true

        "runner thread is still alive (parked on the gate)"
        (.isAlive t) => true))

    ;; Releasing the controller lets the runner process the event and finish.
    (dbg/continue! ctl)
    (.join t 3000)

    (let [rows (read-jsonl transcript)]
      (assertions
        "runner finishes after release"
        (.isAlive t) => false

        "the previously parked :go event was processed"
        (boolean (some (fn [r]
                         (and (= "runner/event-processed" (:event r))
                           (= "go" (some-> r :data :event-name))))
                   rows)) => true))))

(specification "runner debug pause-gate — yields when human-input modal is active"
  (let [dir        (tmp-dir "pause-human")
        transcript (str dir "/run.jsonl")
        chk        (str dir "/chk")
        ctl        (dbg/new-controller {:initial-pause? true})
        env-p      (promise)
        ^Thread t  (Thread.
                     ^Runnable
                     (fn []
                       (try
                         (runner/run! {:chart               wait-then-done-chart
                                       :session-id          :pause-test/human
                                       :transcript-path     transcript
                                       :checkpoint-dir      chk
                                       :debug-controller    ctl
                                       ;; Always-true thunk simulates an
                                       ;; in-flight human-input modal.
                                       :human-input-active? (constantly true)
                                       :on-env-ready        (fn [env] (deliver env-p env))
                                       :max-iterations      200
                                       :quiescent-sleep-ms  10})
                         (catch Throwable _ nil))))]
    (.start t)
    (let [env (deref env-p 2000 nil)]
      (sp/send! (::sc/event-queue env) env {:event :go :target :pause-test/human}))

    (.join t 3000)

    (let [rows (read-jsonl transcript)
          ev   (set (map :event rows))]
      (assertions
        "runner finished (debug gate yielded to the human prompt)"
        (.isAlive t) => false

        "no :debug/awaiting-step fired despite controller being paused"
        (contains? ev "debug/awaiting-step") => false

        "the :go event was processed"
        (boolean (some (fn [r]
                         (and (= "runner/event-processed" (:event r))
                           (= "go" (some-> r :data :event-name))))
                   rows)) => true))))
