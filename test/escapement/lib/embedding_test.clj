(ns escapement.lib.embedding-test
  "The embedding seams on `escapement.lib/run` (and `runner/run!`): a host-supplied
   `:human-renderer`, `:debug-controller` (with the human-input yield) and extra
   `:invocation-processors`."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :as ele :refer [final invoke on-entry script state transition]]
    [com.fulcrologic.statecharts.environment :as env-ns]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.chart.helpers :as h]
    [escapement.debug.controller :as dbg]
    [escapement.invocation.human-input :as hi]
    [escapement.lib :as lib]
    [escapement.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def creds [{:provider :anthropic :api-key "sk-test"}])

(defn- tmp-dir [prefix]
  (str (Files/createTempDirectory prefix (into-array FileAttribute []))))

(defn- wait-until [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 5) (recur))))))

;; ---------------------------------------------------------------------------
;; :invocation-processors
;; ---------------------------------------------------------------------------

(defrecord PingProcessor [started]
  sp/InvocationProcessor
  (supports-invocation-type? [_ t] (= t :test/ping))
  (start-invocation! [_ env {:keys [invokeid params]}]
    (swap! started conj params)
    (sp/send! (::sc/event-queue env) env
      {:target            (env-ns/session-id env)
       :source-session-id (env-ns/session-id env)
       :event             (evts/invoke-done-event invokeid)
       :data              {:pong (:n params)}})
    true)
  (stop-invocation! [_ _ _] true)
  (forward-event! [_ _ _] nil))

(def ping-chart
  (chart/statechart {:initial :calling}
    (state {:id :calling}
      (invoke {:id :ping :type :test/ping :params {:n (fn [_ _] 7)}})
      (transition {:event :done.invoke.ping :target :finished}))
    (final {:id :finished})))

(specification "lib/run :invocation-processors installs a host processor"
  (let [started (atom [])
        r       (lib/run {:chart                 ping-chart
                          :session-id            :embed/ping
                          :credentials           creds
                          :invocation-processors [(->PingProcessor started)]})]
    (assertions
      "the option is part of the closed contract"
      (lib/validate-options {:chart ping-chart :session-id :x :credentials creds
                             :invocation-processors [(->PingProcessor started)]}) => nil
      "the chart's invoke of the host type was started with its params"
      @started => [{:n 7}]
      "its done event drove the chart to its final state"
      (:status r) => :done
      (:final-config r) => [])))

(specification "runner/run! :invocation-processors, alongside the built-ins"
  (let [dir     (tmp-dir "embed-procs-")
        started (atom [])
        procs   (atom nil)
        r       (runner/run! {:chart                 ping-chart
                              :session-id            :embed/runner-ping
                              :transcript-path       (str dir "/t.jsonl")
                              :checkpoint-dir        (str dir "/chk")
                              :invocation-processors [(->PingProcessor started)]
                              :on-env-ready          (fn [env] (reset! procs (::sc/invocation-processors env)))})]
    (assertions
      "the host processor ran the invoke"
      @started => [{:n 7}]
      (:status r) => :done
      "the built-in child-chart processor is still installed and comes before the host's"
      (boolean (some #(sp/supports-invocation-type? % ::sc/chart) @procs)) => true
      (instance? PingProcessor (last @procs)) => true)))

;; ---------------------------------------------------------------------------
;; :human-renderer
;; ---------------------------------------------------------------------------

(defn- recording-renderer
  "A HumanRenderer that records every prompt's opts and answers text prompts from `answer-fn`."
  [seen answer-fn]
  (reify hi/HumanRenderer
    (prompt-text [_ opts] (swap! seen conj opts) (answer-fn opts))
    (prompt-select [_ opts] (swap! seen conj opts) nil)
    (prompt-multi [_ opts] (swap! seen conj opts) nil)
    (prompt-confirm [_ opts] (swap! seen conj opts) true)
    (start-progress [_ _] nil)
    (update-progress [_ _ _ _] nil)
    (end-progress [_ _] nil)
    (custom-render [_ f env data] (f env data))))

(def ask-chart
  (chart/statechart {:initial :ask}
    (state {:id :ask}
      (h/human-input {:id :ask-name :kind :text :prompt "Name?"})
      (transition {:event :human.answer :target :finished}
        (script {:expr (fn [_ data] [(ops/assign [:name] (get-in data [:_event :data :answer]))])})))
    (final {:id :finished})))

(specification "lib/run :human-renderer shows prompts through the host"
  (let [seen (atom [])
        r    (lib/run {:chart          ask-chart
                       :session-id     :embed/ask
                       :credentials    creds
                       :human-renderer (recording-renderer seen (fn [_] "Ada"))})
        opts (first @seen)]
    (assertions
      "the host renderer received the prompt"
      (count @seen) => 1
      (:prompt opts) => "Name?"
      "the prompt names its invocation and its session"
      (:invokeid opts) => :ask-name
      (:session-id opts) => :embed/ask
      "the answer drove the chart"
      (:final-config r) => [])))

;; ---------------------------------------------------------------------------
;; :debug-controller (+ human-input yield)
;; ---------------------------------------------------------------------------

(def two-step-chart
  (chart/statechart {:initial :a}
    (state {:id :a}
      (on-entry {} (ele/send {:event :go}))
      (transition {:event :go :target :b}))
    (state {:id :b}
      (on-entry {} (ele/send {:event :go}))
      (transition {:event :go :target :finished}))
    (final {:id :finished})))

(specification "lib/run :debug-controller gates every event"
  (let [ctl (dbg/new-controller {:initial-pause? true})
        fut (future (lib/run {:chart            two-step-chart
                              :session-id       :embed/debug
                              :credentials      creds
                              :debug-controller ctl}))]
    (Thread/sleep 150)
    (let [held? (not (realized? fut))]
      (dbg/step! ctl)
      (Thread/sleep 150)
      (let [held-after-step? (not (realized? fut))]
        (dbg/continue! ctl)
        (let [r (deref fut 5000 :timeout)]
          (assertions
            "while paused the run does not advance"
            held? => true
            "one step advances one event, then it is held again"
            held-after-step? => true
            "continue lets it finish"
            (:final-config r) => []))))))

(def ask-and-ping-chart
  "Opens a prompt AND queues a :ping while it is open. The ping is processed only if the
   debug gate yields to the open prompt."
  (chart/statechart {:initial :ask}
    (state {:id :ask}
      (on-entry {} (ele/send {:event :ping}))
      (h/human-input {:id :ask-name :kind :text :prompt "Name?"})
      (transition {:event :ping}
        (script {:expr (fn [_ _] [(ops/assign [:pinged] true)])}))
      (transition {:event :human.answer :target :finished}))
    (final {:id :finished})))

(specification "lib/run :debug-controller yields while a human-input prompt is open"
  (let [ctl      (dbg/new-controller {:initial-pause? true})
        answer   (promise)
        pinged   (atom false)
        fut      (future (lib/run {:chart            ask-and-ping-chart
                                   :session-id       :embed/debug-ask
                                   :credentials      creds
                                   :debug-controller ctl
                                   :transcript-tap   (fn [ev]
                                                       (when (and (= :runner/event-processed (:event ev))
                                                                  (= :ping (get-in ev [:data :event-name])))
                                                         (reset! pinged true)))
                                   :human-renderer   (recording-renderer (atom []) (fn [_] @answer))}))
        yielded? (wait-until #(deref pinged) 3000)]
    (deliver answer "Ada")
    (Thread/sleep 100)
    (let [held-after-answer? (not (realized? fut))]
      (dbg/continue! ctl)
      (let [r (deref fut 5000 :timeout)]
        (assertions
          "with the prompt open, the paused gate let the chart process its :ping"
          yielded? => true
          "once the prompt closed the gate held the answer event again"
          held-after-answer? => true
          "continue finishes the run"
          (:final-config r) => [])))))
