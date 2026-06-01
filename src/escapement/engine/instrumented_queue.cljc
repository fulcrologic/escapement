(ns escapement.engine.instrumented-queue
  "An `EventQueue` that WRAPS an inner `InProcessQueue` to add two things the
   plain queue cannot offer:

     1. A live **pause gate**. Before each deliverable event is handed to the
        user handler, `receive-events!` consults a `escapement.debug.controller`
        (if one is present): it arms `pause-on-next-external?` from the event's
        externality, parks the draining thread on the controller's promise gate
        when paused, and consumes a positive step budget. This RELOCATES the
        pause primitive out of the runner loop and into the queue, so any driver
        of the queue (the runner-loop, the TUI, the api-server control plane)
        gets single-stepping for free with no double-gating.

     2. **Instrumentation** for the read API: `pending-events` (queued but not
        yet delivered, as plain transit-safe data) and `last-delivered` (the
        most recent event handed to a handler). The api-server's live resolvers
        read these to show the operator what is in flight.

   `send!`/`cancel!` delegate straight through to the inner queue and stay
   NON-BLOCKING — invocation worker threads call `send!` concurrently while the
   runner thread is parked on the gate, and that must never deadlock. Only
   `receive-events!` (which runs on the single draining thread) ever blocks.

   Babashka-safe: atoms + promises only; no `core.async`, no `java.util.concurrent`."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.debug.controller :as dbg]
    [escapement.engine.queue :as queue]))

(defn- external-event?
  "An event lacks `::sc/source-session-id` only when injected from outside the
   chart (CLI, TUI, api-server, fixtures). Internal raises always carry it (set
   in `engine.queue/send!`)."
  [event]
  (nil? (::sc/source-session-id event)))

(defn- gate!
  "Apply the debug pause gate to `event` on the draining thread, BEFORE the
   user handler runs. No-op when `controller` is nil or when `human-input?`
   (a zero-arg thunk) reports an in-flight human-input modal (so the chart can
   answer a prompt without the debugger stealing focus).

   When paused, emits a `:debug/awaiting-step` row via `transcript-fn` (if
   present on the env) and parks the thread on the controller gate until the
   TUI / api-server calls `step!` or `continue!`. Consumes a positive step
   budget after release so `step!` advances exactly one event then re-pauses."
  [controller human-input? env event]
  (when (and controller
          (not (and human-input? (human-input?))))
    (dbg/maybe-arm-from-external! controller (when (external-event? event)
                                               {:external? true}))
    (when (dbg/paused? controller)
      (when-let [transcript-fn (:escapement/transcript-fn env)]
        (transcript-fn {:event :debug/awaiting-step
                        :data  {:event-name (:name event)
                                :external?  (external-event? event)}}))
      (dbg/await-release! controller))
    (when (pos? (:step-budget @controller))
      (dbg/consume-step-budget! controller))))

(defn- event->summary
  "Project a queued/delivered event onto plain, transit-safe data: name, data,
   target, and (queued) delivery-time from the event meta."
  [event]
  {:event/name          (:name event)
   :event/data          (:data event)
   :event/target        (:target event)
   :event/external?     (external-event? event)
   :event/delivery-time (::queue/delivery-time (meta event))})

(defrecord InstrumentedQueue [inner controller human-input? last-delivered]
  sp/EventQueue
  (send! [_ env send-request]
    ;; Pass-through, non-blocking. Worker threads call this concurrently.
    (sp/send! inner env send-request))
  (cancel! [_ env session-id send-id]
    (sp/cancel! inner env session-id send-id))
  (receive-events! [this env handler]
    (sp/receive-events! this env handler {}))
  (receive-events! [_ env handler options]
    ;; Wrap the user handler so the gate fires per deliverable event BEFORE the
    ;; handler, on this (single) draining thread. The inner queue supplies the
    ;; exactly-once, in-order, delivery-time-gated delivery semantics.
    (sp/receive-events! inner env
      (fn [env event]
        (gate! controller human-input? env event)
        (reset! last-delivered event)
        (handler env event))
      options)))

(defn new-instrumented-queue
  "Build an `InstrumentedQueue`.

   `opts`:
     * `:inner`        — (optional) the wrapped `EventQueue`; defaults to a fresh
                         `escapement.engine.queue/InProcessQueue`.
     * `:controller`   — (optional) a `escapement.debug.controller` atom. When
                         absent the gate is a no-op and delivery equals the plain
                         queue (zero overhead beyond `last-delivered` tracking).
     * `:human-input?` — (optional) a zero-arg predicate; when it returns true
                         the gate yields so a human-input modal keeps focus."
  [{:keys [inner controller human-input?]}]
  (->InstrumentedQueue (or inner (queue/new-queue))
    controller
    human-input?
    (atom nil)))

(defn pending-events
  "Plain, transit-safe summaries of every event currently QUEUED (not yet
   delivered) across all sessions in `iq`'s inner queue — including future-dated
   delayed sends. Each summary: `{:event/name :event/data :event/target
   :event/external? :event/delivery-time}`. Ordered by target session then
   queue order."
  [iq]
  (let [session-queues @(:session-queues (:inner iq))]
    (into []
      (mapcat (fn [[_target evs]] (mapv event->summary evs)))
      (sort-by (comp str key) session-queues))))

(defn last-delivered
  "Plain, transit-safe summary of the most recently delivered event for `iq`,
   or nil if nothing has been delivered yet."
  [iq]
  (when-let [event @(:last-delivered iq)]
    (event->summary event)))
