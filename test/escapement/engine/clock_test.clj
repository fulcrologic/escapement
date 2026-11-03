(ns escapement.engine.clock-test
  "The run's clock: a test clock makes delayed events due when the test advances it, or at once when
   it advances on its own; due-time order holds under a jumping clock; no clock = the wall clock."
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :as ele :refer [final on-entry state transition]]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.engine.clock :as clock]
    [escapement.engine.queue :as queue]
    [escapement.lib :as lib]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def creds [{:provider :anthropic :api-key "sk-test"}])

(defn- drain-names [q sid]
  (let [out (atom [])]
    (sp/receive-events! q {} (fn [_ ev] (swap! out conj (:name ev))) {:session-id sid})
    @out))

(specification "manual clock"
  (let [c (clock/manual-clock {:start-ms 1000})
        a (clock/manual-clock {:start-ms 0 :auto-advance? true})]
    (assertions
      "starts where it is told and only moves when moved"
      (clock/now-ms c) => 1000
      (clock/advance! c 500) => 1500
      (clock/set-ms! c 9000) => 9000
      (clock/now-ms c) => 9000
      "a non-auto clock's idle! does not move its time"
      (do (clock/idle! c 20000 1) (clock/now-ms c)) => 9000
      "an auto-advance clock jumps to the wake time, never backwards"
      (do (clock/idle! a 5000 10000) (clock/now-ms a)) => 5000
      (do (clock/idle! a 100 10000) (clock/now-ms a)) => 5000
      (clock/auto-advance? a) => true
      (clock/auto-advance? c) => false
      "the wall clock is the default"
      (<= (Math/abs (- (clock/now-ms (clock/clock-or-wall nil)) (System/currentTimeMillis))) 1000) => true)))

(specification "queue on a manual clock"
  (let [c (clock/manual-clock {:start-ms 10000})
        q (queue/new-queue {:clock c})]
    (sp/send! q {} {:event :late :target :s :delay 300})
    (sp/send! q {} {:event :early :target :s :delay 100})
    (sp/send! q {} {:event :mid :target :s :delay 200})
    (assertions
      "delayed events are due on the queue's clock, not the wall clock"
      (queue/deliverable-now-count q) => 0
      (drain-names q :s) => []
      "next-delivery-time names the earliest due time in epoch ms on that clock"
      (queue/next-delivery-time q) => 10100
      (queue/next-delivery-time q :s) => 10100
      (queue/next-delivery-time q :other) => nil
      "advancing short of the due time delivers nothing"
      (do (clock/advance! c 99) (drain-names q :s)) => []
      "jumping past all of them delivers them in due-time order"
      (do (clock/advance! c 1000) (drain-names q :s)) => [:early :mid :late]
      "nothing pending means no wake time"
      (queue/next-delivery-time q) => nil
      "the queue reports its clock"
      (identical? c (queue/queue-clock q)) => true)))

(def two-day-chart
  (chart/statechart {:initial :waiting}
    (state {:id :waiting}
      (on-entry {} (ele/send {:event :wake :delay (* 2 24 60 60 1000)}))
      (transition {:event :wake :target :finished}))
    (final {:id :finished})))

(specification "a run on an auto-advancing clock skips the wait"
  (let [c  (clock/manual-clock {:start-ms 0 :auto-advance? true})
        t0 (System/currentTimeMillis)
        r  (lib/run {:chart two-day-chart :session-id :clock/two-days :credentials creds :clock c})
        el (- (System/currentTimeMillis) t0)]
    (assertions
      "the two-day timer fired and the chart finished"
      (:status r) => :done
      (:final-config r) => []
      "in well under real time"
      (< el 10000) => true
      "the clock now stands at the timer's due time"
      (clock/now-ms c) => (* 2 24 60 60 1000))))

(specification "a run on a manual clock waits until the test advances it"
  (let [c     (clock/manual-clock {:start-ms 0})
        env   (promise)
        fut   (future (lib/run {:chart two-day-chart :session-id :clock/manual :credentials creds
                                :clock c :quiescent-sleep-ms 5 :on-env-ready #(deliver env %)}))
        ;; Advance only once the timer is armed (stamped from t=0), however slow startup is.
        armed (let [q (:com.fulcrologic.statecharts/event-queue (deref env 5000 nil))
                    deadline (+ (System/currentTimeMillis) 5000)]
                (loop []
                  (cond (and q (pos? (queue/pending-count q))) true
                        (> (System/currentTimeMillis) deadline) false
                        :else (do (Thread/sleep 5) (recur)))))]
    (Thread/sleep 50)
    (let [held? (not (realized? fut))]
      (clock/advance! c (* 2 24 60 60 1000))
      (let [r (deref fut 5000 :timeout)]
        (assertions
          "the timer is not due while the clock stands still"
          armed => true
          held? => true
          "advancing the clock fires it"
          (:final-config r) => [])))))
