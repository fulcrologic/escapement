(ns escapement.engine.step-test
  "Event-driven stepping (`escapement.engine.step`): one event at a time, acknowledged in the same
   checkpoint write that records its result, an error event instead of a silent drop, and the next
   wake time."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :as ele :refer [final on-entry state transition]]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.engine.clock :as clock]
    [escapement.engine.queue :as queue]
    [escapement.engine.step :as step]
    [escapement.engine.store :as store]
    [escapement.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir [prefix]
  (str (Files/createTempDirectory prefix (into-array FileAttribute []))))

(defn- opts [dir sid chart & {:as more}]
  (merge {:chart           chart
          :session-id      sid
          :transcript-path (str dir "/t.jsonl")
          :checkpoint-dir  (str dir "/chk")}
    more))

(defn- saved-queue-names
  "The event names in `sid`'s checkpoint queue ON DISK (a fresh store: what a restarted process sees)."
  [dir sid]
  (let [snap (store/get-queue-snapshot (store/new-store (str dir "/chk")) sid)]
    (mapv (comp :name :event) (get-in snap [:sessions sid]))))

(defn- saved-config [dir sid]
  (let [s (store/new-store (str dir "/chk"))]
    (::sc/configuration (sp/get-working-memory s {} sid))))

(def go-go-chart
  "Queues two events on entry; each moves one state."
  (chart/statechart {:initial :a}
    (state {:id :a}
      (on-entry {} (ele/send {:event :one}) (ele/send {:event :two}))
      (transition {:event :one :target :b}))
    (state {:id :b}
      (transition {:event :two :target :finished}))
    (final {:id :finished})))

(specification "step! processes exactly one event, and acknowledges it in the checkpoint"
  (let [dir (tmp-dir "step-one-")
        sid :step/one
        s   (step/open (opts dir sid go-go-chart))]
    (try
      (let [st0   (step/state s)
            r1    (step/step! s)
            disk1 {:config (saved-config dir sid) :queue (saved-queue-names dir sid)}
            r2    (step/step! s)
            disk2 {:config (saved-config dir sid) :queue (saved-queue-names dir sid)}
            r3    (step/step! s)]
        (assertions
          "opening starts the chart; both events are pending and due"
          (:configuration st0) => [:a]
          (mapv :event (:pending-events st0)) => [:one :two]
          (:status st0) => :running
          "the first step processes only the first event"
          (:processed? r1) => true
          (:event-name r1) => :one
          (:configuration r1) => [:b]
          (mapv :event (:pending-events r1)) => [:two]
          "on disk: the new state AND the rest of the queue, without the processed event"
          disk1 => {:config #{:b} :queue [:two]}
          "the second step processes the second"
          (:event-name r2) => :two
          (:final? r2) => true
          (:status r2) => :final
          disk2 => {:config #{} :queue []}
          "with nothing due, a step processes nothing and says so"
          (:processed? r3) => false
          (:event-name r3) => nil))
      (finally (step/close! s)))))

(specification "a crash mid-batch loses nothing (queue-level acknowledgement)"
  ;; `receive-events!` hands out a batch of due events; each must stay queued until its own turn,
  ;; so the checkpoint written while handling the first still holds the others.
  (let [q    (queue/new-queue)
        seen (atom [])]
    (doseq [e [:e1 :e2 :e3]] (sp/send! q {} {:event e :target :s}))
    (sp/receive-events! q {}
      (fn [_ ev] (swap! seen conj [(:name ev) (mapv (comp :name :event) (get-in (queue/snapshot q) [:sessions :s]))]))
      {:session-id :s})
    (assertions
      "while each event is handled, the snapshot holds every later one but not it"
      @seen => [[:e1 [:e2 :e3]] [:e2 [:e3]] [:e3 []]])))

(specification "a cancelled event is not delivered even when it was already due"
  (let [q    (queue/new-queue)
        seen (atom [])]
    (sp/send! q {} {:event :first :target :s})
    (sp/send! q {} {:event :second :target :s :send-id :cancel-me})
    (sp/receive-events! q {}
      (fn [_ ev] (swap! seen conj (:name ev))
        (when (= :first (:name ev)) (sp/cancel! q {} :s :cancel-me)))
      {:session-id :s})
    (assertions
      @seen => [:first])))

(def explode-chart
  (chart/statechart {:initial :a}
    (state {:id :a}
      (transition {:event :explode :target :b})
      (transition {:event :error.execution :target :failed}))
    (state {:id :b})
    (state {:id :failed})))

(defn- throwing-processor
  "Wraps `real`, throwing for events named in `bad`."
  [real bad]
  (reify sp/Processor
    (start! [_ env id params] (sp/start! real env id params))
    (process-event! [_ env wmem event]
      (if (contains? bad (:name event))
        (throw (ex-info "processor blew up" {}))
        (sp/process-event! real env wmem event)))
    (exit! [_ env wmem skip?] (sp/exit! real env wmem skip?))))

(specification "an event whose processing throws reaches the chart as error.execution"
  (let [dir  (tmp-dir "step-throw-")
        sid  :step/throw
        rows (atom [])
        s    (step/open (opts dir sid explode-chart :transcript-tap #(swap! rows conj %)))
        s    (assoc s :processor (throwing-processor (:processor s) #{:explode}))
        q    (::sc/event-queue (:env s))]
    (try
      (sp/send! q (:env s) {:event :explode :target sid})
      (let [r1 (step/step! s)
            r2 (step/step! s)]
        (assertions
          "the failing event is consumed, not retried"
          (:event-name r1) => :explode
          (:configuration r1) => [:a]
          "the chart got error.execution and took its error transition"
          (:event-name r2) => :error.execution
          (:configuration r2) => [:failed]
          "the failure is in the transcript"
          (boolean (some #(and (= :runner/event-failed (:event %))
                               (= :explode (get-in % [:data :event-name]))) @rows)) => true))
      (finally (step/close! s)))))

(specification "an error event whose own processing throws does not loop"
  (let [dir (tmp-dir "step-throw-loop-")
        sid :step/loop
        s   (step/open (opts dir sid explode-chart))
        s   (assoc s :processor (throwing-processor (:processor s) #{:explode :error.execution}))
        q   (::sc/event-queue (:env s))]
    (try
      (sp/send! q (:env s) {:event :explode :target sid})
      (let [r1 (step/step! s)
            r2 (step/step! s)
            r3 (step/step! s)]
        (assertions
          (:event-name r1) => :explode
          (:event-name r2) => :error.execution
          "no further error is raised; the session simply waits"
          (:processed? r3) => false
          (:status r3) => :waiting))
      (finally (step/close! s)))))

(def timer-chart
  (chart/statechart {:initial :waiting}
    (state {:id :waiting}
      (on-entry {} (ele/send {:event :wake :delay 60000}))
      (transition {:event :wake :target :finished}))
    (final {:id :finished})))

(specification "next-wake-at: the host learns when to come back, across a restart"
  (let [dir (tmp-dir "step-wake-")
        sid :step/wake
        c   (clock/manual-clock {:start-ms 1000000})
        s1  (step/open (opts dir sid timer-chart :clock c))
        r1  (step/step! s1)
        _   (step/close! s1)
        _   (clock/advance! c 30000)
        s2  (step/open (opts dir sid timer-chart :clock c :resume? true))
        r2  (step/step-until-quiet! s2)
        _   (clock/advance! c 30000)
        r3  (step/step! s2)]
    (step/close! s2)
    (assertions
      "nothing is due yet: the session sleeps until the timer's due time on the run's clock"
      (:processed? r1) => false
      (:status r1) => :sleeping
      (:next-wake-at r1) => 1060000
      "after a restart the timer is still pending at the same due time, and not delivered early"
      (:status r2) => :sleeping
      (:next-wake-at r2) => 1060000
      "at its due time it is delivered"
      (:event-name r3) => :wake
      (:status r3) => :final)))

(specification "the session lock is held while stepping"
  (let [dir (tmp-dir "step-lock-")
        sid :step/lock
        s   (step/open (opts dir sid timer-chart))]
    (try
      (assertions
        "a second opener of the same session is refused"
        (try (step/open (opts dir sid timer-chart :resume? true)) :opened
             (catch clojure.lang.ExceptionInfo e (boolean (:escapement/session-locked (ex-data e)))))
        => true)
      (finally (step/close! s)))
    (let [s2 (step/open (opts dir sid timer-chart :resume? true))]
      (step/close! s2)
      (assertions
        "after close! it can be reopened"
        (map? s2) => true))))

(def cancel-in-batch-chart
  "Entering :a queues :go and :late together (both due at once). Processing :go leaves :a,
   whose on-exit cancels :late. SCXML <cancel>: a cancelled send is never delivered."
  (chart/statechart {:initial :a}
    (state {:id :a}
      (on-entry {} (ele/send {:event :go}) (ele/send {:id :late-send :event :late}))
      (ele/on-exit {} (ele/cancel {:sendid :late-send}))
      (transition {:event :go :target :b}))
    (state {:id :b}
      (transition {:event :late :target :wrong}))
    (state {:id :wrong})))

(specification "regression: a send cancelled while its batch is being delivered is not delivered"
  ;; Before the per-event ack, a whole batch of due events left the queue at once, so an event
  ;; cancelled by an earlier event in the same batch was still delivered.
  (let [dir (tmp-dir "cancel-batch-")
        r   (runner/run! {:chart           cancel-in-batch-chart
                                     :session-id      :cancel/batch
                                     :transcript-path (str dir "/t.jsonl")
                                     :checkpoint-dir  (str dir "/chk")
                                     :quiescent-sleep-ms 5})]
    (assertions
      "the chart stays in :b; the cancelled :late never arrives"
      (:final-config r) => [:b])))
