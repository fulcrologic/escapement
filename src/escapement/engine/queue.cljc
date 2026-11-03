(ns escapement.engine.queue
  "In-process FIFO `EventQueue` implementation with delayed-send + cancel support.

  This is a slimmed-down replacement for `com.fulcrologic.statecharts.event-queue.manually-polled-queue`
  that we own so we can evolve it without pulling library deps that break under bb. The semantics match:
  separate per-session queues, delivery-time gating for delays, `cancel!` by `send-id`."
  (:require
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn ?]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.engine.clock :as clock]))

(defn- now-ms
  "Current time on `queue`'s clock (`escapement.engine.clock`); the wall clock when it has none."
  [queue]
  (clock/now-ms (clock/clock-or-wall (:clock queue))))

(defn- supported-type? [type]
  (or (nil? type)
    (= type ::sc/chart)
    (= type :statechart)
    (and (string? type)
      (clojure.string/starts-with? (clojure.string/lower-case type) "http://www.w3.org/tr/scxml"))))

(defn- delivery-key [evt]
  (let [m (meta evt)] [(::delivery-time m) (::ordinal m 0)]))

(defn- take-event!
  "Remove exactly `event` (by identity) from `session-id`'s queue in `session-queues`. Returns true
   when it was still there (a `cancel!` in the meantime means it must not be delivered)."
  [session-queues session-id event]
  (let [[old _] (swap-vals! session-queues update session-id
                  (fn [q] (into [] (remove #(identical? % event)) q)))]
    (boolean (some #(identical? % event) (get old session-id)))))

(defn- deliver-to-handler!
  "Run `handler` on `event`; a throw is reported, never propagated into the queue."
  [handler env event]
  (try (handler env event)
       (catch #?(:clj Throwable :cljs :default) e
         (binding [*out* *err*]
           (println "[engine.queue] handler threw:" (ex-message e))))))

(defn- run-guarded [guard thunk]
  (if guard (guard thunk) (thunk)))

(defrecord InProcessQueue [session-queues next-ordinal clock]
  sp/EventQueue
  (send! [this _env {:keys [event data type target source-session-id send-id invoke-id delay
                         origin origintype at-ms]}]
    (if (and (supported-type? type) (or target source-session-id))
      (let [target (or target source-session-id)
            now    (now-ms this)
            ;; `:at-ms` (opt-in, outside events): an explicit arrival time on the queue's clock,
            ;; so an event that arrived while the host was down is ordered by WHEN it arrived,
            ;; ahead of a timer that came due later (it is also a due time: never earlier).
            tm     (cond at-ms (long at-ms)
                         delay (+ now (long delay))
                         :else (dec now))
            ;; A monotonic insertion ordinal is the STABLE tie-break for events that
            ;; share a delivery-time. Delivery is `(delivery-time, ordinal)`-ordered
            ;; (see `receive-events!`), so after a long resume gap — where many timers
            ;; are simultaneously past-due — a scheduled timeout-error and its paired
            ;; success event still fire in their intended order, and that order is
            ;; reproduced exactly on restore because the ordinal is persisted.
            ord    (swap! next-ordinal inc)
            evt    (with-meta
                     (evts/new-event (cond-> {:name   event
                                              :type   (or type ::sc/chart)
                                              :target target
                                              :data   (or data {})}
                                       source-session-id (assoc ::sc/source-session-id source-session-id)
                                       (or origin source-session-id) (assoc :origin (or origin source-session-id))
                                       true (assoc :origintype (or origintype type ::sc/chart))
                                       send-id (assoc :sendid send-id ::sc/send-id send-id)
                                       invoke-id (assoc :invokeid invoke-id)))
                     {::delivery-time tm ::ordinal ord})]
        (swap! session-queues update target (fnil conj []) evt)
        true)
      false))
  (cancel! [_ _env session-id send-id]
    (swap! session-queues update session-id
      (fn [q]
        (vec (remove (fn [{sid ::sc/send-id}] (= sid send-id)) q))))
    nil)
  (receive-events! [this env handler]
    (sp/receive-events! this env handler {}))
  (receive-events! [this env handler {:keys [session-id guard] :as options}]
    (if (nil? session-id)
      (doseq [sid (keys @session-queues)]
        (sp/receive-events! this env handler (assoc options :session-id sid)))
      (let [cutoff  (now-ms this)
            ;; The batch is every event due at `cutoff`, in (delivery-time, ordinal) order — NOT
            ;; raw insertion order — so simultaneously-due events fire deterministically by the
            ;; time they were scheduled for, then by insertion as a stable tie-break. Events sent
            ;; while the batch runs wait for the next pass, as before.
            to-send (->> (get @session-queues session-id)
                      (filterv (fn [evt] (<= (::delivery-time (meta evt)) cutoff)))
                      (sort-by delivery-key))]
        ;; ACK: each event stays IN the queue until its own turn, and is taken out just before its
        ;; handler runs. The handler processes it and saves the checkpoint, whose queue snapshot
        ;; therefore holds every other pending event (the rest of this batch included) but not this
        ;; one: that one atomic write is the event's acknowledgement. A crash mid-batch no longer
        ;; loses the events not yet handled. (They used to leave the queue all at once.)
        ;; `:guard` (optional, `(fn [thunk])`) runs each take-and-handle as one unit, e.g. under the
        ;; session's write lock, so nothing can checkpoint the queue between the take and the save.
        (doseq [event to-send]
          (run-guarded guard
            (fn []
              (when (take-event! session-queues session-id event)
                (deliver-to-handler! handler env event)))))))))

(>defn new-queue
  "Create a new in-process event queue.

   `opts`:
     * `:clock` — an `escapement.engine.clock/Clock` that stamps delayed sends' due times and decides
                  which events are due; the wall clock when omitted."
  ([] [=> any?] (new-queue {}))
  ([{:keys [clock]}]
   [[:map [:clock {:optional true} any?]] => any?]
   (->InProcessQueue (atom {}) (atom 0) clock)))

(defn snapshot
  "Return a plain-data, EDN-serializable snapshot of `queue`'s pending (undelivered) events across all
   sessions, so it can be persisted alongside the working-memory checkpoint and rehydrated on resume.

   Each pending event is captured as `{:event <event-map> :delivery-time <epoch-ms> :ordinal <n>}` —
   the metadata (`::delivery-time`/`::ordinal`) is lifted OUT of Clojure metadata (which `pr` drops)
   into explicit keys so the round-trip is lossless. Follows a wrapping queue's `:inner` chain (e.g.
   the debug `InstrumentedQueue`) down to the backing `InProcessQueue`. Returns `nil` for a queue that
   does not support snapshotting (so the caller can treat it as \"nothing durable\")."
  [queue]
  (if-let [session-queues (:session-queues queue)]
    {:next-ordinal @(:next-ordinal queue)
     :sessions     (reduce-kv
                     (fn [acc target evs]
                       (assoc acc target
                         (mapv (fn [evt]
                                 (let [m (meta evt)]
                                   {:event        (with-meta evt nil)
                                    :delivery-time (::delivery-time m)
                                    :ordinal      (::ordinal m 0)}))
                           evs)))
                     {}
                     @session-queues)}
    (when-let [inner (:inner queue)]
      (snapshot inner))))

(defn- snapshot->session-queues
  "Rebuild the `{target [event …]}` map from a `snapshot`'s `:sessions`, re-attaching each event's
   `::delivery-time`/`::ordinal` metadata."
  [snap]
  (reduce-kv
    (fn [acc target evs]
      (assoc acc target
        (mapv (fn [{:keys [event delivery-time ordinal]}]
                (with-meta event {::delivery-time delivery-time ::ordinal (or ordinal 0)}))
          evs)))
    {}
    (:sessions snap)))

(>defn queue-from-snapshot
  "Rebuild a fresh `InProcessQueue` from a `snapshot` (see `snapshot`), re-attaching each event's
   `::delivery-time`/`::ordinal` metadata so delivery ordering and future-dated gating resume exactly
   as before the process exited. A `nil`/empty snapshot yields a fresh empty queue. `opts` as in
   `new-queue` (`:clock`)."
  ([snap] [[:maybe :map] => any?] (queue-from-snapshot snap {}))
  ([snap {:keys [clock]}]
   [[:maybe :map] [:map [:clock {:optional true} any?]] => any?]
   (->InProcessQueue (atom (snapshot->session-queues snap)) (atom (or (:next-ordinal snap) 0)) clock)))

(>defn restore-into!
  "Repopulate an EXISTING `queue`'s pending events and ordinal counter from `snapshot`, in place.
   Use this on `--resume`: the execution model has already captured the live queue reference, so the
   pending timers must be rehydrated INTO that same queue rather than by swapping in a new one. Follows
   a wrapping queue's `:inner` chain (e.g. the debug `InstrumentedQueue`). A `nil` snapshot is a no-op.
   Returns the (now-populated) backing queue, or `nil` when there is nothing to restore into."
  [queue snap]
  [any? [:maybe :map] => any?]
  (cond
    (nil? snap) nil
    (:session-queues queue)
    (do (reset! (:session-queues queue) (snapshot->session-queues snap))
        (reset! (:next-ordinal queue) (or (:next-ordinal snap) 0))
        queue)
    (:inner queue) (restore-into! (:inner queue) snap)
    :else nil))

(defn- backing-queue
  "The backing `InProcessQueue` of `queue`, following a wrapping queue's `:inner` chain (e.g. the
   debug `InstrumentedQueue`)."
  [queue]
  (if (:session-queues queue)
    queue
    (some-> (:inner queue) backing-queue)))

(defn- backing-session-queues
  "The `session-queues` atom of `queue`'s backing `InProcessQueue`."
  [queue]
  (:session-queues (backing-queue queue)))

(>defn pending-count
  "Return the number of currently-queued events across all sessions (including deferred ones)."
  [queue]
  [any? => :int]
  (reduce + 0 (map count (vals @(backing-session-queues queue)))))

(>defn deliverable-now-count
  "Number of queued events across all sessions whose delivery-time has already
   arrived — i.e. events the next `receive-events!` would hand to the handler.
   Excludes future-dated delayed sends still waiting on their timer.

   The runner uses this to tell a *planned* idle (pending events that are all
   future-dated, e.g. a safety-stop timer — wait for it) apart from a *wedge*
   (events deliverable now but stranded on sessions this run does not drain,
   e.g. a multiplex chart run without `:multi-session?` — fail fast)."
  [queue]
  [any? => :int]
  (let [now (now-ms (backing-queue queue))]
    (reduce
      (fn [n evs]
        (+ n (count (filter (fn [evt] (<= (::delivery-time (meta evt) 0) now)) evs))))
      0
      (vals @(backing-session-queues queue)))))

(>defn next-delivery-time
  "The earliest due time (epoch ms on the queue's clock) among `queue`'s pending events, or nil when
   nothing is pending. With `session-id`, only that session's events count. Events already due report
   their (past) due time, so `(<= (next-delivery-time q) now)` means something is deliverable now."
  ([queue] [any? => (? :int)] (next-delivery-time queue nil))
  ([queue session-id]
   [any? any? => (? :int)]
   (let [qs  @(backing-session-queues queue)
         evs (if (some? session-id) (get qs session-id) (mapcat identity (vals qs)))
         ts  (keep (fn [evt] (::delivery-time (meta evt))) evs)]
     (when (seq ts) (long (reduce min ts))))))

(>defn queue-clock
  "The clock `queue` (or the queue it wraps) reads time from: its `:clock`, or the wall clock."
  [queue]
  [any? => any?]
  (clock/clock-or-wall (:clock (backing-queue queue))))

(>defn receive-one!
  "Deliver AT MOST ONE due event to `handler` — the earliest by (delivery-time, ordinal) — and return
   it, or nil when nothing is due on the queue's clock. With `:session-id`, only that session's events
   are considered; without, every session's.

   The event stays in the queue until the moment its handler runs (the same acknowledgement rule as
   `receive-events!`), and a handler throw is reported, never propagated. `:guard` as in
   `receive-events!`. Works on the BACKING queue
   of a wrapper, so a debug gate does not apply: the caller is stepping by hand."
  ([queue env handler] [any? any? fn? => any?] (receive-one! queue env handler {}))
  ([queue env handler {:keys [session-id guard]}]
   [any? any? fn? [:maybe :map] => any?]
   (let [bq (backing-queue queue)
         sq (:session-queues bq)]
     (run-guarded guard
       (fn []
         (let [now         (now-ms bq)
               [sid event] (->> @sq
                             (mapcat (fn [[sid evs]]
                                       (when (or (nil? session-id) (= sid session-id))
                                         (keep (fn [evt] (when (<= (::delivery-time (meta evt)) now) [sid evt])) evs))))
                             (sort-by (comp delivery-key second))
                             first)]
           (when (and event (take-event! sq sid event))
             (deliver-to-handler! handler env event)
             event)))))))

(>defn pending-summary
  "Plain data for `queue`'s pending events (optionally only `session-id`'s), in delivery order:
   `[{:event <name> :target <sid> :due-at <epoch ms on the queue's clock> :data <map>} …]`."
  ([queue] [any? => vector?] (pending-summary queue nil))
  ([queue session-id]
   [any? any? => vector?]
   (->> @(backing-session-queues queue)
     (mapcat (fn [[sid evs]] (when (or (nil? session-id) (= sid session-id)) evs)))
     (sort-by delivery-key)
     (mapv (fn [evt] {:event  (:name evt)
                      :target (:target evt)
                      :due-at (::delivery-time (meta evt))
                      :data   (:data evt)})))))

(>defn drop-pending!
  "Remove every pending event (delayed or due) for `session-id` from `queue` (following a wrapper's
   `:inner` chain). Returns how many were dropped. Used when a session has reached its top-level
   final state: SCXML ends the session there, and its delayed sends are never delivered."
  [queue session-id]
  [any? any? => :int]
  (let [sq      (backing-session-queues queue)
        [old _] (swap-vals! sq dissoc session-id)]
    (count (get old session-id))))
