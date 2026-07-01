(ns escapement.engine.queue-test
  (:require
    [clojure.edn :as edn]
    [com.fulcrologic.statecharts :as-alias sc]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.engine.queue :as q]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(defn- drain-names
  "Drain `session-id` from `queue` and return the ordered vector of delivered event names."
  [queue session-id]
  (let [fired (atom [])]
    (sp/receive-events! queue {} (fn [_ ev] (swap! fired conj (:name ev))) {:session-id session-id})
    @fired))

(specification "receive-events! delivery ordering"
  (component "past-due events fire in (delivery-time, ordinal) order"
    ;; Delivery-times are in the distant past (epoch ms 50/100) so all are deliverable now.
    ;; A(dt100,ord5) B(dt50,ord9) C(dt50,ord3) ⇒ dt primary, ordinal tie-break ⇒ C,B,A.
    (let [snap  {:next-ordinal 10
                 :sessions     {:s [{:event {:name :A} :delivery-time 100 :ordinal 5}
                                    {:event {:name :B} :delivery-time 50 :ordinal 9}
                                    {:event {:name :C} :delivery-time 50 :ordinal 3}]}}
          queue (q/queue-from-snapshot snap)]
      (assertions
        "orders by delivery-time first, then insertion ordinal"
        (drain-names queue :s) => [:C :B :A]
        "drains to empty"
        (q/pending-count queue) => 0)))

  (component "future-dated events are deferred, not delivered"
    (let [queue (q/new-queue)]
      (sp/send! queue {} {:event :later :target :s :delay 60000})
      (let [fired (drain-names queue :s)]
        (assertions
          "a not-yet-due delayed send is withheld from the handler"
          fired => []
          "and remains pending in the queue"
          (q/pending-count queue) => 1))))

  (component "same-time events preserve insertion (FIFO) order"
    ;; Equal delivery-time, ascending ordinals in insertion order ⇒ stable FIFO.
    (let [snap  {:next-ordinal 3
                 :sessions     {:s [{:event {:name :first} :delivery-time 0 :ordinal 1}
                                    {:event {:name :second} :delivery-time 0 :ordinal 2}
                                    {:event {:name :third} :delivery-time 0 :ordinal 3}]}}]
      (assertions
        "equal-time events fire in ordinal (insertion) order"
        (drain-names (q/queue-from-snapshot snap) :s) => [:first :second :third]))))

(specification "send! stamps a monotonic ordinal and future delivery-time"
  (let [queue (q/new-queue)]
    (sp/send! queue {} {:event :a :target :s :delay 1000})
    (sp/send! queue {} {:event :b :target :s :delay 1000})
    (let [snap (q/snapshot queue)
          evs  (get-in snap [:sessions :s])]
      (assertions
        "the ordinal counter advances per send"
        (mapv :ordinal evs) => [1 2]
        "the next-ordinal reflects the number of sends"
        (:next-ordinal snap) => 2
        "each event carries a future delivery-time"
        (every? pos? (map :delivery-time evs)) => true))))

(specification "snapshot / restore round-trip"
  (component "snapshot is EDN-serializable and rebuilds an equivalent queue"
    (let [queue (q/new-queue)]
      (sp/send! queue {} {:event :x :target :s :delay 5000})
      (sp/send! queue {} {:event :y :target :s :delay 5000})
      (let [snap  (q/snapshot queue)
            snap2 (edn/read-string {:default tagged-literal} (pr-str snap))
            q2    (q/queue-from-snapshot snap2)]
        (assertions
          "the snapshot survives an EDN write/read unchanged"
          snap2 => snap
          "the rebuilt queue holds the same pending count"
          (q/pending-count q2) => 2))))

  (component "restore-into! repopulates an existing queue in place"
    (let [queue (q/new-queue)
          snap  {:next-ordinal 7
                 :sessions     {:s [{:event {:name :poll/tick} :delivery-time 10 :ordinal 4}]}}
          ret   (q/restore-into! queue snap)]
      (assertions
        "returns the populated backing queue (same identity, mutated in place)"
        (identical? ret queue) => true
        "the pending event is present"
        (q/pending-count queue) => 1
        "the restored past-due event is deliverable"
        (drain-names queue :s) => [:poll/tick])))

  (assertions
    "restore-into! is a no-op for a nil snapshot"
    (q/restore-into! (q/new-queue) nil) => nil))
