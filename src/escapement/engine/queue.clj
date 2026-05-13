(ns escapement.engine.queue
  "In-process FIFO `EventQueue` implementation with delayed-send + cancel support.

  This is a slimmed-down replacement for `com.fulcrologic.statecharts.event-queue.manually-polled-queue`
  that we own so we can evolve it without pulling library deps that break under bb. The semantics match:
  separate per-session queues, delivery-time gating for delays, `cancel!` by `send-id`."
  (:require
   [com.fulcrologic.guardrails.malli.core :refer [>defn => ?]]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.events :as evts]
   [com.fulcrologic.statecharts.protocols :as sp]))

(def ^:private now-ms-fn
  ;; Wrapper so tests could mock if needed.
  (fn [] (System/currentTimeMillis)))

(defn- supported-type? [type]
  (or (nil? type)
      (= type ::sc/chart)
      (= type :statechart)
      (and (string? type)
           (clojure.string/starts-with? (clojure.string/lower-case type) "http://www.w3.org/tr/scxml"))))

(defrecord InProcessQueue [session-queues]
  sp/EventQueue
  (send! [_ _env {:keys [event data type target source-session-id send-id invoke-id delay
                         origin origintype]}]
    (if (and (supported-type? type) (or target source-session-id))
      (let [target (or target source-session-id)
            now    (now-ms-fn)
            tm     (if delay (+ now (long delay)) (dec now))
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
                     {::delivery-time tm})]
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
            to-send (filterv (fn [evt] (<= (::delivery-time (meta evt)) cutoff))
                             (get old session-id))]
        (doseq [event to-send]
          (try (handler env event)
               (catch Throwable e
                 (binding [*out* *err*]
                   (println "[engine.queue] handler threw:" (.getMessage e))))))))))

(>defn new-queue
       "Create a new in-process event queue."
       []
       [=> any?]
       (->InProcessQueue (atom {})))

(>defn pending-count
       "Return the number of currently-queued events across all sessions (including deferred ones)."
       [queue]
       [any? => :int]
       (reduce + 0 (map count (vals @(:session-queues queue)))))
