(ns escapement.runner-inspect-test
  "§runtime.stepping/inspect (read a saved session without running or locking it) and the opt-in
   `:at-ms` arrival time on outside events."
  (:require
    [babashka.fs :as fs]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :as ele :refer [final on-entry state transition]]
    [escapement.engine.clock :as clock]
    [escapement.engine.session-lock :as session-lock]
    [escapement.lib :as lib]
    [fulcro-spec.core :refer [=> assertions specification]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def creds [{:provider :anthropic :api-key "sk-test"}])

(defn- tmp-dir [prefix]
  (str (Files/createTempDirectory prefix (into-array FileAttribute []))))

(defn- base [dir sid chart & {:as more}]
  (merge {:chart chart :session-id sid :credentials creds
          :checkpoint-dir (str dir "/chk") :transcript-path (str dir "/t.jsonl")
          :quiescent-sleep-ms 5}
    more))

(def timer-chart
  (chart/statechart {:initial :waiting}
    (state {:id :waiting}
      (on-entry {} (ele/send {:event :wake :delay 60000}))
      (transition {:event :wake :target :finished}))
    (final {:id :finished})))

(specification "inspect reads a saved session without running or locking it"
  (let [dir  (tmp-dir "inspect-")
        chk  (str dir "/chk")
        c    (clock/manual-clock {:start-ms 1000000})
        o    (base dir :inspect/a timer-chart :park-timers? true :clock c :chart-id :wf.t/v1)
        _    (lib/run o)
        ckpt (str chk "/inspect/a.edn")
        before (slurp ckpt)
        i1   (lib/inspect {:checkpoint-dir chk :session-id :inspect/a})
        lock (session-lock/acquire! chk :inspect/a)
        i2   (try (lib/inspect {:checkpoint-dir chk :session-id :inspect/a})
                  (finally (session-lock/release! lock)))
        unchanged? (= before (slurp ckpt))
        _    (clock/advance! c 60000)
        _    (lib/run (assoc o :resume? true))
        i3   (lib/inspect {:checkpoint-dir chk :session-id :inspect/a})]
    (assertions
      "a parked session: chart id, where it waits, its timer and the wake time"
      i1 => {:exists?        true
             :chart-id       :wf.t/v1
             :configuration  [:waiting]
             :final?         false
             :pending-events [{:event :wake :data {} :due-at 1060000}]
             :next-wake-at   1060000}
      "it answers while another writer holds the session lock"
      i2 => i1
      "reading wrote nothing"
      unchanged? => true
      "a finished session"
      (select-keys i3 [:exists? :final? :configuration :next-wake-at])
      => {:exists? true :final? true :configuration [] :next-wake-at nil}
      "unknown session or directory: does not exist, and nothing is created"
      (lib/inspect {:checkpoint-dir chk :session-id :inspect/none}) => {:exists? false}
      (fs/exists? (str chk "/inspect/none.lock")) => false
      (lib/inspect {:checkpoint-dir (str dir "/nope") :session-id :x}) => {:exists? false}
      (fs/exists? (str dir "/nope")) => false)))

(def approval-chart
  "Waits for an answer, with a timeout due at start + 10 s."
  (chart/statechart {:initial :awaiting}
    (state {:id :awaiting}
      (on-entry {} (ele/send {:event :timeout :delay 10000}))
      (transition {:event :approval/answered :target :approved})
      (transition {:event :timeout :target :timed-out}))
    (final {:id :approved})
    (final {:id :timed-out})))

(specification ":at-ms — which path won"
  (let [winner (fn [how at-ms]
                 (let [rows (atom [])
                       dir  (tmp-dir "at-ms-win-")
                       c    (clock/manual-clock {:start-ms 0})
                       o    (base dir :atms/w approval-chart :park-timers? true :clock c)
                       _    (lib/run o)
                       _    (clock/set-ms! c 15000)
                       ev   (cond-> {:event :approval/answered :data {}} at-ms (assoc :at-ms at-ms))]
                   (case how
                     :resume-event (lib/run (assoc o :resume? true :resume-events [ev]
                                              :transcript-tap #(swap! rows conj %)))
                     :deliver      (do (lib/deliver! (merge {:checkpoint-dir (str dir "/chk") :session-id :atms/w
                                                             :clock c :event :approval/answered}
                                                       (when at-ms {:at-ms at-ms})))
                                       (lib/run (assoc o :resume? true :transcript-tap #(swap! rows conj %)))))
                   (->> @rows
                     (filter #(= :runner/event-processed (:event %)))
                     (map #(get-in % [:data :event-name]))
                     (some #{:approval/answered :timeout}))))]
    (assertions
      "with :at-ms before the timeout's due time, the answer is processed first"
      (winner :resume-event 5000) => :approval/answered
      (winner :deliver 5000) => :approval/answered
      "without :at-ms it arrives now, after the overdue timeout (unchanged)"
      (winner :resume-event nil) => :timeout
      (winner :deliver nil) => :timeout)))

(specification ":at-ms in the future is never delivered early"
  (let [dir (tmp-dir "at-ms-future-")
        c   (clock/manual-clock {:start-ms 0})
        o   (base dir :atms/f (chart/statechart {:initial :w}
                                (state {:id :w} (transition {:event :go :target :end}))
                                (final {:id :end}))
              :park-timers? true :clock c)
        _   (lib/run o)
        r1  (lib/run (assoc o :resume? true :resume-events [{:event :go :at-ms 50000}]))
        _   (clock/set-ms! c 50000)
        r2  (lib/run (assoc o :resume? true))]
    (assertions
      "before its time it waits like a timer"
      (:status r1) => :sleeping
      (:next-wake-at r1) => 50000
      "at its time it is delivered"
      (:final-config r2) => [])))
