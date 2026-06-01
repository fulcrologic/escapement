(ns escapement.engine.instrumented-queue-test
  "Unit tests for the instrumented event queue: pass-through delivery, the pause
   gate (park/step/continue), pending-events instrumentation, and external-vs-
   internal arming. Deterministic — releases the gate via the controller (step
   budget / continue!) rather than racing sleeps."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.debug.controller :as dbg]
    [escapement.engine.instrumented-queue :as iq]
    [escapement.engine.queue :as queue]
    [escapement.test-support :as ts]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(defn- drain!
  "Deliver all currently-deliverable events for `sid` on `q`, collecting their
   names into the `out` atom-vector. Returns whether anything was delivered."
  [q sid out]
  (let [progressed? (atom false)]
    (sp/receive-events! q {} (fn [_ ev]
                               (reset! progressed? true)
                               (ts/push-last! out (:name ev)))
      {:session-id sid})
    @progressed?))

(specification "instrumented queue — pass-through delivery (no controller)"
  (let [plain     (queue/new-queue)
        iqueue    (iq/new-instrumented-queue {})
        plain-out (ts/queue)
        iq-out    (ts/queue)]
    ;; Send the same two events to both queues.
    (doseq [q [plain iqueue]]
      (sp/send! q {} {:event :a :target :s1 :data {:n 1}})
      (sp/send! q {} {:event :b :target :s1}))
    (drain! plain :s1 plain-out)
    (drain! iqueue :s1 iq-out)
    (assertions
      "delivers the same events, in the same order, as the plain queue"
      @iq-out => @plain-out
      "delivers both events"
      @iq-out => [:a :b]
      "records the last-delivered event as plain transit-safe data"
      (:event/name (iq/last-delivered iqueue)) => :b)))

(specification "instrumented queue — pending-events instrumentation"
  (let [iqueue (iq/new-instrumented-queue {})]
    (sp/send! iqueue {} {:event :a :target :s1 :data {:n 1}})
    (sp/send! iqueue {} {:event :b :target :s1})
    (let [pending (iq/pending-events iqueue)]
      (assertions
        "reflects events queued but not yet delivered"
        (mapv :event/name pending) => [:a :b]
        "carries event data for queued events"
        (:event/data (first pending)) => {:n 1}
        "marks externally-injected events (no source-session-id) external"
        (:event/external? (first pending)) => true))
    (drain! iqueue :s1 (ts/queue))
    (assertions
      "is empty after the events are delivered"
      (iq/pending-events iqueue) => [])))

(specification "instrumented queue — external vs internal arming"
  (component "an external event (no source-session-id) arms the pause and parks"
    (let [ctl    (dbg/new-controller)
          iqueue (iq/new-instrumented-queue {:controller ctl})
          out    (ts/queue)]
      (dbg/arm-pause-on-next-external! ctl)
      ;; The external event trips the arm INSIDE the gate (flips :paused), so the
      ;; drain parks. Run it on a background thread so the test thread is free to
      ;; observe the paused state and then release the gate.
      (let [^Thread t (Thread. ^Runnable
                        (fn []
                          (sp/send! iqueue {} {:event :ext :target :s1})
                          (drain! iqueue :s1 out)))]
        (.start t)
        (Thread/sleep 50)
        (let [paused-after-arm (dbg/paused? ctl)
              nothing-yet      @out]
          (dbg/continue! ctl)
          (.join t 2000)
          (assertions
            "the external event flips the controller to paused (arm consumed)"
            paused-after-arm => true
            "the event is held (not delivered) while parked"
            nothing-yet => []
            "after continue! the held external event is delivered"
            @out => [:ext])))))
  (component "an internal raise (carries source-session-id) does not arm the pause"
    (let [ctl    (dbg/new-controller)
          iqueue (iq/new-instrumented-queue {:controller ctl})
          out    (ts/queue)]
      (dbg/arm-pause-on-next-external! ctl)
      ;; Internal raise: source-session-id present => send! stamps it, so the
      ;; event is NOT external and must not trip the arm — drain stays unparked.
      (sp/send! iqueue {} {:event :internal :target :s1 :source-session-id :s1})
      (drain! iqueue :s1 out)
      (assertions
        "the internal event is delivered without parking"
        @out => [:internal]
        "the run is NOT paused (arm not consumed by an internal event)"
        (dbg/paused? ctl) => false))))

(specification "instrumented queue — gate parks until released, step delivers one"
  (component "paused gate parks the draining thread until continue!"
    (let [ctl       (dbg/new-controller {:initial-pause? true})
          iqueue    (iq/new-instrumented-queue {:controller ctl})
          out       (ts/queue)
          done      (promise)
          ^Thread t (Thread. ^Runnable
                      (fn []
                        (sp/send! iqueue {} {:event :go :target :s1})
                        (drain! iqueue :s1 out)
                        (deliver done :drained)))]
      (.start t)
      ;; The drain thread is parked on the gate: nothing delivered yet.
      (Thread/sleep 50)
      (let [parked-empty @out
            still-alive  (.isAlive t)]
        (dbg/continue! ctl)
        (let [released (deref done 2000 :timeout)]
          (assertions
            "no event is delivered while paused"
            parked-empty => []
            "the draining thread is parked on the gate"
            still-alive => true
            "the event is delivered after continue!"
            released => :drained
            "exactly the parked event is delivered"
            @out => [:go])))))

  (component "step! delivers exactly one event then re-pauses"
    (let [ctl       (dbg/new-controller {:initial-pause? true})
          iqueue    (iq/new-instrumented-queue {:controller ctl})
          out       (ts/queue)
          done      (promise)
          ^Thread t (Thread. ^Runnable
                      (fn []
                        ;; Two events queued; with a 1-event budget only the
                        ;; first should get through before the gate re-pauses.
                        (sp/send! iqueue {} {:event :one :target :s1})
                        (sp/send! iqueue {} {:event :two :target :s1})
                        (drain! iqueue :s1 out)
                        (deliver done :drained)))]
      (.start t)
      (Thread/sleep 50)
      (dbg/step! ctl)
      ;; After one step the budget is spent; the thread re-parks before :two.
      (Thread/sleep 50)
      (let [after-step  @out
            still-alive (.isAlive t)]
        (dbg/continue! ctl)
        (deref done 2000 :timeout)
        (assertions
          "step! lets exactly one event through"
          after-step => [:one]
          "the gate re-pauses (thread parks again before the second event)"
          still-alive => true
          "continue! then delivers the rest"
          @out => [:one :two])))))
