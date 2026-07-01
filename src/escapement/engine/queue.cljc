(ns escapement.engine.queue
  "In-process FIFO `EventQueue` implementation with delayed-send + cancel support.

  This is a slimmed-down replacement for `com.fulcrologic.statecharts.event-queue.manually-polled-queue`
  that we own so we can evolve it without pulling library deps that break under bb. The semantics match:
  separate per-session queues, delivery-time gating for delays, `cancel!` by `send-id`."
  (:require
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.protocols :as sp]))

(def ^:private now-ms-fn
  ;; Wrapper so tests could mock if needed.
  #?(:clj  (fn [] (System/currentTimeMillis))
     :cljs (fn [] (.getTime (js/Date.)))))

(defn- supported-type? [type]
  (or (nil? type)
    (= type ::sc/chart)
    (= type :statechart)
    (and (string? type)
      (clojure.string/starts-with? (clojure.string/lower-case type) "http://www.w3.org/tr/scxml"))))

(defrecord InProcessQueue [session-queues next-ordinal]
  sp/EventQueue
  (send! [_ _env {:keys [event data type target source-session-id send-id invoke-id delay
                         origin origintype]}]
    (if (and (supported-type? type) (or target source-session-id))
      (let [target (or target source-session-id)
            now    (now-ms-fn)
            tm     (if delay (+ now (long delay)) (dec now))
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
  (receive-events! [this env handler {:keys [session-id]}]
    (if (nil? session-id)
      (doseq [sid (keys @session-queues)]
        (sp/receive-events! this env handler {:session-id sid}))
      (let [cutoff  (now-ms-fn)
            [old _] (swap-vals! session-queues
                      (fn [qs]
                        (let [to-defer (filterv
                                         (fn [evt] (> (::delivery-time (meta evt)) cutoff))
                                         (get qs session-id))]
                          (assoc qs session-id to-defer))))
            ;; Deliver deliverable events in (delivery-time, ordinal) order — NOT raw
            ;; insertion order — so simultaneously-due events fire deterministically by
            ;; the time they were scheduled for, then by insertion as a stable tie-break.
            to-send (->> (get old session-id)
                      (filterv (fn [evt] (<= (::delivery-time (meta evt)) cutoff)))
                      (sort-by (fn [evt] (let [m (meta evt)] [(::delivery-time m) (::ordinal m 0)]))))]
        (doseq [event to-send]
          (try (handler env event)
               (catch #?(:clj Throwable :cljs :default) e
                 (binding [*out* *err*]
                   (println "[engine.queue] handler threw:" (ex-message e))))))))))

(>defn new-queue
  "Create a new in-process event queue."
  []
  [=> any?]
  (->InProcessQueue (atom {}) (atom 0)))

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
   as before the process exited. A `nil`/empty snapshot yields a fresh empty queue."
  [snap]
  [[:maybe :map] => any?]
  (->InProcessQueue (atom (snapshot->session-queues snap)) (atom (or (:next-ordinal snap) 0))))

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

(>defn pending-count
  "Return the number of currently-queued events across all sessions (including deferred ones)."
  [queue]
  [any? => :int]
  (reduce + 0 (map count (vals @(:session-queues queue)))))

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
  (let [now (now-ms-fn)]
    (reduce
      (fn [n evs]
        (+ n (count (filter (fn [evt] (<= (::delivery-time (meta evt) 0) now)) evs))))
      0
      (vals @(:session-queues queue)))))
