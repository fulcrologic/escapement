(ns escapement.runner-restart-test
  "Restarts: resuming restarts the invocations of active invoking states (§runtime.restarts/reinvoke),
   and every in-flight invocation keeps a run alive (§runtime.restarts/live-count)."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final invoke on-entry script state transition]]
    [com.fulcrologic.statecharts.environment :as env-ns]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.invocation.future :as future-proc]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.chart.helpers :as h]
    [escapement.engine.liveness :as liveness]
    [escapement.invocation.human-input :as hi]
    [escapement.lib :as lib]
    [escapement.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def creds [{:provider :anthropic :api-key "sk-test"}])

(defn- tmp-dir [prefix]
  (str (Files/createTempDirectory prefix (into-array FileAttribute []))))

(defn- rows [path]
  (with-open [r (io/reader path)] (doall (line-seq r))))

(def ^:dynamic *entries* nil)
(defn- bump! [k] (when *entries* (swap! *entries* update k (fnil inc 0))))

;; ---------------------------------------------------------------------------
;; reinvoke: a child chart
;; ---------------------------------------------------------------------------

(def child-chart
  "Invoked child. Parks forever, so the parent's `<invoke>` is genuinely long-lived."
  (chart/statechart {:initial :busy}
    (state {:id :busy}
      (on-entry {} (script {:expr (fn [_ _] (bump! :child) nil)}))
      (transition {:event :never :target :child-done}))
    (final {:id :child-done})))

(def parent-chart
  (chart/statechart {:initial :working}
    (state {:id :working}
      (invoke {:id :job :type :com.fulcrologic.statecharts/chart :src :restart/child})
      (transition {:event :finish :target :parent-done}))
    (final {:id :parent-done})))

(specification "resume restarts a child-chart invocation of the restored state"
  (let [dir     (tmp-dir "restart-child-")
        entries (atom {})
        base    {:chart              parent-chart
                 :session-id         :restart/child-session
                 :checkpoint-dir     (str dir "/chk")
                 :quiescent-sleep-ms 5
                 :on-env-ready       (fn [env]
                                       (sp/register-statechart!
                                         (::sc/statechart-registry env) :restart/child child-chart))}
        r1      (binding [*entries* entries]
                  (runner/run! (assoc base :transcript-path (str dir "/t1.jsonl"))))
        after-1 @entries
        _       (reset! entries {})
        r2      (binding [*entries* entries]
                  (runner/run! (assoc base :transcript-path (str dir "/t2.jsonl") :resume? true)))]
    (assertions
      "run 1 started the child and parked in the invoking state"
      (get after-1 :child) => 1
      (:final-config r1) => [:working]
      "resume started the child again, as if :working had just been entered"
      (get @entries :child) => 1
      (:final-config r2) => [:working]
      "the restart is visible in the transcript"
      (boolean (some #(and (str/includes? % "runner/reinvoked") (str/includes? % "working"))
                 (rows (str dir "/t2.jsonl")))) => true)))

(specification "resume into a state with no <invoke> restarts nothing"
  (let [dir   (tmp-dir "restart-none-")
        c     (chart/statechart {:initial :idle}
                (state {:id :idle} (transition {:event :go :target :end}))
                (final {:id :end}))
        base  {:chart c :session-id :restart/none :checkpoint-dir (str dir "/chk") :quiescent-sleep-ms 5}
        _     (runner/run! (assoc base :transcript-path (str dir "/t1.jsonl")))
        r2    (runner/run! (assoc base :transcript-path (str dir "/t2.jsonl") :resume? true))]
    (assertions
      (:status r2) => :done
      (:final-config r2) => [:idle]
      "no reinvoke row"
      (boolean (some #(str/includes? % "runner/reinvoked") (rows (str dir "/t2.jsonl")))) => false)))

;; ---------------------------------------------------------------------------
;; reinvoke: an outside call (:future) cut off by a stop
;; ---------------------------------------------------------------------------

(defn- call-chart [src]
  (chart/statechart {:initial :calling}
    (state {:id :calling}
      (invoke {:id :call :type :future :src src :params {:q (fn [_ _] "ping")}})
      (transition {:event :done.invoke.call :target :finished}
        (script {:expr (fn [_ data] (bump! [:answer (get-in data [:_event :data :answer])]) nil)})))
    (final {:id :finished})))

(specification "resume re-runs an outside call that was in flight when the run stopped"
  (let [dir      (tmp-dir "restart-future-")
        calls    (atom [])
        release  (promise)
        ;; First call hangs (the run is stopped mid-call); later calls answer.
        src      (fn [params]
                   (swap! calls conj params)
                   (if (= 1 (count @calls)) (do @release {:answer :late}) {:answer :pong}))
        c        (call-chart src)
        cancel   (atom false)
        base     {:chart c :session-id :restart/future :checkpoint-dir (str dir "/chk")
                  :quiescent-sleep-ms 5}
        fut      (future (runner/run! (assoc base :transcript-path (str dir "/t1.jsonl") :cancel cancel
                                        :invocation-processors [(future-proc/new-future-processor)])))
        _        (Thread/sleep 150)
        running? (not (realized? fut))
        _        (reset! cancel true)
        r1       (deref fut 5000 :timeout)
        entries  (atom {})
        r2       (binding [*entries* entries]
                   (runner/run! (assoc base :transcript-path (str dir "/t2.jsonl") :resume? true
                                  :invocation-processors [(future-proc/new-future-processor)])))]
    (deliver release true)
    (assertions
      "while the call hangs the run is alive (it does not report :done mid-call)"
      running? => true
      "run 1 was stopped mid-call"
      (:status r1) => :aborted
      (:final-config r1) => [:calling]
      "resume called it again with the same parameters"
      @calls => [{:q "ping"} {:q "ping"}]
      "and its result reached the chart"
      (get @entries [:answer :pong]) => 1
      (:final-config r2) => [])))

;; ---------------------------------------------------------------------------
;; reinvoke: a human-input prompt
;; ---------------------------------------------------------------------------

(defn- renderer [seen answer-fn]
  (reify hi/HumanRenderer
    (prompt-text [_ opts] (swap! seen conj (:prompt opts)) (answer-fn))
    (prompt-select [_ _] nil)
    (prompt-multi [_ _] nil)
    (prompt-confirm [_ _] true)
    (start-progress [_ _] nil)
    (update-progress [_ _ _ _] nil)
    (end-progress [_ _] nil)
    (custom-render [_ f env data] (f env data))))

(def ask-chart
  (chart/statechart {:initial :ask}
    (state {:id :ask}
      (h/human-input {:id :ask-name :kind :text :prompt "Name?"})
      (transition {:event :human.answer :target :finished}))
    (final {:id :finished})))

(specification "resume shows an open prompt again"
  (let [dir    (tmp-dir "restart-ask-")
        seen1  (atom [])
        seen2  (atom [])
        cancel (atom false)
        base   {:chart ask-chart :session-id :restart/ask :checkpoint-dir (str dir "/chk")
                :credentials creds :quiescent-sleep-ms 5}
        fut    (future (lib/run (assoc base :transcript-path (str dir "/t1.jsonl") :cancel cancel
                                  :human-renderer (renderer seen1 (fn [] @(promise))))))
        _      (Thread/sleep 150)
        _      (reset! cancel true)
        r1     (deref fut 5000 :timeout)
        r2     (lib/run (assoc base :transcript-path (str dir "/t2.jsonl") :resume? true
                          :human-renderer (renderer seen2 (fn [] "Ada"))))]
    (assertions
      "run 1 stopped with the prompt open"
      @seen1 => ["Name?"]
      (:final-config r1) => [:ask]
      "resume asked again and the answer finished the chart"
      @seen2 => ["Name?"]
      (:final-config r2) => [])))

;; ---------------------------------------------------------------------------
;; live-count
;; ---------------------------------------------------------------------------

(specification "an in-flight :future invocation keeps the run alive"
  (let [entries (atom {})
        r       (binding [*entries* entries]
                  (lib/run {:chart                 (call-chart (fn [_] (Thread/sleep 100) {:answer :pong}))
                            :session-id            :restart/live-future
                            :credentials           creds
                            :quiescent-sleep-ms    5
                            :invocation-processors [(future-proc/new-future-processor)]}))]
    (assertions
      "the run waited for the call instead of reporting :done mid-call"
      (:status r) => :done
      (:final-config r) => []
      "the call's result was processed, not lost"
      (get @entries [:answer :pong]) => 1)))

(defrecord SlowProcessor [running]
  sp/InvocationProcessor
  (supports-invocation-type? [_ t] (= t :test/slow))
  (start-invocation! [_ env {:keys [invokeid]}]
    (swap! running inc)
    (let [sid (env-ns/session-id env) q (::sc/event-queue env)]
      (future
        (Thread/sleep 100)
        (sp/send! q env {:target sid :source-session-id sid
                         :event (evts/invoke-done-event invokeid) :data {}})
        (swap! running dec)))
    true)
  (stop-invocation! [_ _ _] true)
  (forward-event! [_ _ _] nil)
  liveness/ActiveCount
  (active-count [_] @running))

(specification "a host processor that reports an active count keeps the run alive"
  (let [c (chart/statechart {:initial :calling}
            (state {:id :calling}
              (invoke {:id :slow :type :test/slow})
              (transition {:event :done.invoke.slow :target :finished}))
            (final {:id :finished}))
        r (lib/run {:chart c :session-id :restart/slow :credentials creds :quiescent-sleep-ms 5
                    :invocation-processors [(->SlowProcessor (atom 0))]})]
    (assertions
      (:final-config r) => [])))
