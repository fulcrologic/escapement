(ns escapement.runner-outside-events-test
  "Waiting for and delivering outside events (§runtime.outside-events), parking on timers
   (§runtime.stepping/park-timers), strict resume (§runtime.restarts/require-checkpoint), version
   identity on resume (§runtime.chart-data/version-identity) and recorded inputs
   (§runtime.replay/recording)."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :as ele :refer [final on-entry script state transition]]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.chart.helpers :as h]
    [escapement.engine.clock :as clock]
    [escapement.engine.session-lock :as session-lock]
    [escapement.engine.step :as step]
    [escapement.engine.store :as store]
    [escapement.invocation.human-input :as hi]
    [escapement.lib :as lib]
    [escapement.runner :as runner]
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

(defn- saved-queue [dir sid]
  (let [snap (store/get-queue-snapshot (store/new-store (str dir "/chk")) sid)]
    (mapv (fn [{:keys [event]}] [(:name event) (:data event)]) (get-in snap [:sessions sid]))))

(def approval-chart
  "Parks in :awaiting until an outside :approval/answered arrives."
  (chart/statechart {:initial :awaiting}
    (state {:id :awaiting}
      (transition {:event :approval/answered :target :approved}
        (script {:expr (fn [_ data] [(ops/assign [:answer] (get-in data [:_event :data :answer]))])})))
    (final {:id :approved})))

;; ---------------------------------------------------------------------------
;; waiting status
;; ---------------------------------------------------------------------------

(specification ":report-waiting? — a parked run says it is waiting"
  (let [d1 (tmp-dir "wait-1-") d2 (tmp-dir "wait-2-")
        r1 (lib/run (base d1 :wait/a approval-chart :report-waiting? true))
        r2 (lib/run (base d2 :wait/b approval-chart))
        r3 (lib/run (base (tmp-dir "wait-3-") :wait/c
                      (chart/statechart {:initial :end} (final {:id :end}))
                      :report-waiting? true))]
    (assertions
      "opted in, a run parked in a non-final state reports :waiting and names where"
      (:status r1) => :waiting
      (:waiting-in r1) => [:awaiting]
      "without the opt-in it reports :done as before"
      (:status r2) => :done
      (contains? r2 :waiting-in) => false
      "a run that reached a final state still reports :done"
      (:status r3) => :done)))

;; ---------------------------------------------------------------------------
;; park-timers
;; ---------------------------------------------------------------------------

(def timer-chart
  (chart/statechart {:initial :waiting}
    (state {:id :waiting}
      (on-entry {} (ele/send {:event :wake :delay 60000}))
      (transition {:event :wake :target :finished}))
    (final {:id :finished})))

(specification ":park-timers? — park on a timer instead of sleeping"
  (let [dir (tmp-dir "park-")
        c   (clock/manual-clock {:start-ms 5000000})
        o   (base dir :park/a timer-chart :park-timers? true :clock c)
        r1  (lib/run o)
        q1  (saved-queue dir :park/a)
        _   (clock/advance! c 10000)
        r2  (lib/run (assoc o :resume? true))
        _   (clock/advance! c 50000)
        r3  (lib/run (assoc o :resume? true))]
    (assertions
      "the run returns :sleeping with the timer's due time on the run's clock"
      (:status r1) => :sleeping
      (:next-wake-at r1) => 5060000
      (:final-config r1) => [:waiting]
      "the timer is in the checkpoint"
      q1 => [[:wake {}]]
      "resuming early parks again with the same wake time"
      (:status r2) => :sleeping
      (:next-wake-at r2) => 5060000
      "resuming at the due time delivers the timer"
      (:status r3) => :done
      (:final-config r3) => [])))

(specification ":park-timers? needs the default file-backed store"
  (let [dir (tmp-dir "park-store-")
        mem (let [a (atom {})]
              (reify sp/WorkingMemoryStore
                (get-working-memory [_ _ sid] (get @a sid))
                (save-working-memory! [_ _ sid w] (swap! a assoc sid w) nil)
                (delete-working-memory! [_ _ sid] (swap! a dissoc sid) nil)))]
    (assertions
      (try (lib/run (base dir :park/mem timer-chart :park-timers? true :store mem)) :ran
           (catch clojure.lang.ExceptionInfo e (:escapement/refused (ex-data e))))
      => :park-timers-needs-file-store)))

;; ---------------------------------------------------------------------------
;; require-checkpoint
;; ---------------------------------------------------------------------------

(specification ":require-checkpoint? — resuming insists on a checkpoint"
  (let [dir     (tmp-dir "reqck-")
        o       (base dir :reqck/a approval-chart :resume? true :require-checkpoint? true)
        missing (try (lib/run o) :ran (catch clojure.lang.ExceptionInfo e (ex-data e)))
        dir2    (tmp-dir "reqck-final-")
        done-c  (chart/statechart {:initial :end} (final {:id :end}))
        _       (lib/run (base dir2 :reqck/b done-c))
        final   (try (lib/run (base dir2 :reqck/b done-c :resume? true :require-checkpoint? true)) :ran
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))
        loose   (lib/run (base (tmp-dir "reqck-loose-") :reqck/c approval-chart :resume? true))]
    (assertions
      "no checkpoint: refused, naming the session and directory"
      (:escapement/refused missing) => :no-checkpoint
      (:session-id missing) => :reqck/a
      (:checkpoint-dir missing) => (str dir "/chk")
      "nothing was started (no checkpoint was written)"
      (some? (sp/get-working-memory (store/new-store (str dir "/chk")) {} :reqck/a)) => false
      "a checkpoint with no active configuration also counts as none"
      (:escapement/refused final) => :no-checkpoint
      (:checkpoint-found? final) => true
      "without the flag, :resume? on a missing checkpoint starts fresh as before"
      (:final-config loose) => [:awaiting])))

;; ---------------------------------------------------------------------------
;; version identity
;; ---------------------------------------------------------------------------

(specification "resuming against a chart id that is not available is refused"
  (let [dir  (tmp-dir "version-")
        o    (base dir :version/a approval-chart :chart-id :wf.demo/v1)
        _    (lib/run o)
        bad  (try (lib/run (assoc o :chart-id :wf.demo/v2 :resume? true)) :ran
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))
        good (lib/run (assoc o :chart-id :wf.demo/v2 :resume? true
                        :resume-events [{:event :approval/answered :data {:answer :yes}}]
                        :on-env-ready (fn [env] (sp/register-statechart! (::sc/statechart-registry env)
                                                  :wf.demo/v1 approval-chart))))]
    (assertions
      "a v1 checkpoint is not resumed by a run that only knows v2"
      (:escapement/refused bad) => :chart-id-mismatch
      (:checkpoint-chart-id bad) => :wf.demo/v1
      "it resumes when its own version is available"
      (:final-config good) => [])))

;; ---------------------------------------------------------------------------
;; deliver!
;; ---------------------------------------------------------------------------

(specification "deliver! to a saved, waiting session"
  (let [dir  (tmp-dir "deliver-saved-")
        rows (atom [])
        o    (base dir :deliver/a approval-chart :report-waiting? true)
        r1   (lib/run o)
        d    (lib/deliver! {:checkpoint-dir (str dir "/chk") :session-id :deliver/a
                            :event :approval/answered :data {:answer :yes}})
        disk (saved-queue dir :deliver/a)
        r2   (lib/run (assoc o :resume? true :transcript-tap #(swap! rows conj %)))
        row  (first (filter #(and (= :runner/event-processed (:event %))
                                  (= :approval/answered (get-in % [:data :event-name]))) @rows))
        late (lib/deliver! {:checkpoint-dir (str dir "/chk") :session-id :deliver/a
                            :event :approval/answered :data {}})]
    (assertions
      (:status r1) => :waiting
      "accepted and saved with the session"
      d => {:accepted? true :where :saved}
      disk => [[:approval/answered {:answer :yes}]]
      "resuming processes it"
      (:status r2) => :done
      (get-in r2 [:data :answer]) => :yes
      "the transcript marks it as an outside event, with its data"
      (get-in row [:data :external?]) => true
      (get-in row [:data :event-data]) => {:answer :yes}
      "delivering to a finished session is refused"
      late => {:accepted? false :reason :finished})))

(specification "deliver! refusals"
  (let [dir (tmp-dir "deliver-refuse-")
        _   (lib/run (base dir :deliver/r approval-chart))
        h   (session-lock/acquire! (str dir "/chk") :deliver/r)]
    (try
      (assertions
        "no such directory"
        (lib/deliver! {:checkpoint-dir (str dir "/nope") :session-id :x :event :e})
        => {:accepted? false :reason :no-session}
        "no such session"
        (lib/deliver! {:checkpoint-dir (str dir "/chk") :session-id :other :event :e})
        => {:accepted? false :reason :no-session}
        "held by another writer"
        (lib/deliver! {:checkpoint-dir (str dir "/chk") :session-id :deliver/r :event :e})
        => {:accepted? false :reason :locked-elsewhere})
      (finally (session-lock/release! h)))))

(def wait-or-timeout-chart
  "Waits for an outside event, with a far timer keeping the run alive."
  (chart/statechart {:initial :awaiting}
    (state {:id :awaiting}
      (on-entry {} (ele/send {:id :timeout-timer :event :timeout :delay 3600000}))
      (ele/on-exit {} (ele/cancel {:sendid :timeout-timer}))
      (transition {:event :approval/answered :target :approved})
      (transition {:event :timeout :target :timed-out}))
    (final {:id :approved})
    (final {:id :timed-out})))

(specification "deliver! to a session running in this process"
  (let [dir (tmp-dir "deliver-live-")
        c   (clock/manual-clock {:start-ms 0})
        fut (future (lib/run (base dir :deliver/live wait-or-timeout-chart :clock c)))
        _   (Thread/sleep 200)
        d   (lib/deliver! {:checkpoint-dir (str dir "/chk") :session-id :deliver/live
                           :event :approval/answered :data {:answer :yes}})
        r   (deref fut 5000 :timeout)]
    (assertions
      "accepted into the live run"
      d => {:accepted? true :where :running}
      "which processed it (the timer never fired)"
      (:final-config r) => []
      (:status r) => :done)))

(specification "deliver! to a stepping session"
  (let [dir (tmp-dir "deliver-step-")
        s   (step/open (base dir :deliver/step approval-chart))]
    (try
      (let [d  (lib/deliver! {:checkpoint-dir (str dir "/chk") :session-id :deliver/step
                              :event :approval/answered :data {:answer :no}})
            q  (saved-queue dir :deliver/step)
            r  (step/step! s)]
        (assertions
          d => {:accepted? true :where :running}
          "it is in the checkpoint as soon as it is accepted"
          q => [[:approval/answered {:answer :no}]]
          "the next step processes it"
          (:event-name r) => :approval/answered
          (:status r) => :final))
      (finally (step/close! s)))))

;; ---------------------------------------------------------------------------
;; recording
;; ---------------------------------------------------------------------------

(def self-send-chart
  "Raises its own :tick, then waits for an outside :poke."
  (chart/statechart {:initial :a}
    (state {:id :a}
      (on-entry {} (ele/send {:event :tick}))
      (transition {:event :tick :target :b}))
    (state {:id :b}
      (transition {:event :poke :target :end}))
    (final {:id :end})))

(defn- processed [rows]
  (->> rows
    (filter #(= :runner/event-processed (:event %)))
    (mapv (fn [r] [(get-in r [:data :event-name]) (get-in r [:data :external?])]))))

(specification "a run records its inputs"
  (let [dir   (tmp-dir "record-")
        rows1 (atom [])
        rows2 (atom [])
        o     (base dir :record/a self-send-chart :initial-data {:customer "ada"})
        _     (lib/run (assoc o :transcript-tap #(swap! rows1 conj %)))
        _     (lib/run (assoc o :resume? true :transcript-tap #(swap! rows2 conj %)
                         :resume-events [{:event :poke :data {:n 1}}]))
        start (first (filter #(= :runner/started (:event %)) @rows1))]
    (assertions
      "the start row carries the initial data"
      (get-in start [:data :initial-data]) => {:customer "ada"}
      "an event the chart raised for itself is not marked"
      (processed @rows1) => [[:tick false]]
      "the runner's resume signal is not an outside event; a host's resume event is"
      (processed @rows2) => [[:escapement/resumed false] [:poke true]])))

(defn- renderer [answer]
  (reify hi/HumanRenderer
    (prompt-text [_ _] answer)
    (prompt-select [_ _] nil)
    (prompt-multi [_ _] nil)
    (prompt-confirm [_ _] true)
    (start-progress [_ _] nil)
    (update-progress [_ _ _ _] nil)
    (end-progress [_ _] nil)
    (custom-render [_ f env data] (f env data))))

(specification "a human's answer is recorded as an outside event"
  (let [rows (atom [])
        c    (chart/statechart {:initial :ask}
               (state {:id :ask}
                 (h/human-input {:id :ask-name :kind :text :prompt "Name?"})
                 (transition {:event :human.answer :target :end}))
               (final {:id :end}))]
    (lib/run (base (tmp-dir "record-human-") :record/h c :human-renderer (renderer "Ada")
               :transcript-tap #(swap! rows conj %)))
    (assertions
      (processed @rows) => [[:human.answer true]])))
