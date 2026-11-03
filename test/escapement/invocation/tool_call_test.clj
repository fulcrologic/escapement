(ns escapement.invocation.tool-call-test
  "§runtime.embedding/tool-call: a registry Tool called as an invocation."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :as ele :refer [final invoke on-entry script state transition]]
    [escapement.engine.queue :as queue]
    [escapement.invocation.tool-call :as tool-call]
    [escapement.lib :as lib]
    [escapement.tools.protocol :as tp]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def creds [{:provider :anthropic :api-key "sk-test"}])

(defrecord FnTool [kw schema f]
  tp/Tool
  (tool-name [_] kw)
  (description [_] (str kw))
  (input-schema [_] schema)
  (invoke [_ input] (f input)))

(defn- tool [kw f] (->FnTool kw [:map] f))

(defn- registry [& tools] (tp/new-registry (vec tools)))

(defn- record! [k]
  (script {:expr (fn [_ data] [(ops/assign [:got] [k (get-in data [:_event :data])])])}))

(defn- call-chart
  "Calls `tool-kw` once; records which event came back and its data."
  [tool-kw & {:keys [input timeout-ms] :or {input {}}}]
  (chart/statechart {:initial :calling}
    (state {:id :calling}
      (invoke {:id :call :type :tool-call
               :params (cond-> {:tool tool-kw :input input}
                         timeout-ms (assoc :timeout-ms timeout-ms))})
      (transition {:event :done.invoke.call :target :end} (record! :done))
      (transition {:event :error.call.tool :target :end} (record! :tool))
      (transition {:event :error.call.unknown :target :end} (record! :unknown))
      (transition {:event :error.call.exception :target :end} (record! :exception))
      (transition {:event :error.call.timeout :target :end} (record! :timeout)))
    (final {:id :end})))

(defn- run-call [reg chart]
  (:got (:data (lib/run {:chart chart :session-id (keyword "tc" (str (gensym))) :credentials creds
                         :tool-registry reg :quiescent-sleep-ms 5}))))

(specification "tool-call: each outcome reaches the chart as exactly one event"
  (let [reg (registry
              (tool :t/ok (fn [in] (Thread/sleep 50) {:result "fine" :is-error false :value {:echo in}}))
              (tool :t/err (fn [_] {:result "nope" :is-error true}))
              (tool :t/unsure (fn [_] {:result "?" :is-error true :outcome :unknown}))
              (tool :t/boom (fn [_] (throw (ex-info "kaboom" {}))))
              (tool :t/slow (fn [_] (Thread/sleep 2000) {:result "late" :is-error false})))]
    (assertions
      "success: done.invoke.<id> with the tool's whole result (the run waited for it)"
      (run-call reg (call-chart :t/ok :input {:n 1}))
      => [:done {:result "fine" :is-error false :value {:echo {:n 1}}}]
      "a reported error: error.call.tool with the result"
      (let [[k d] (run-call reg (call-chart :t/err))] [k (:result d) (:escapement/tool d)])
      => [:tool "nope" :t/err]
      "outcome unknown: error.call.unknown (wins over :is-error)"
      (let [[k d] (run-call reg (call-chart :t/unsure))] [k (:outcome d)])
      => [:unknown :unknown]
      "a throw: error.call.exception with the message"
      (let [[k d] (run-call reg (call-chart :t/boom))] [k (:message d)])
      => [:exception "kaboom"]
      "too slow: error.call.timeout"
      (let [[k d] (run-call reg (call-chart :t/slow :timeout-ms 50))] [k (:timeout-ms d)])
      => [:timeout 50]
      "no such tool: error.call.tool"
      (first (run-call reg (call-chart :t/missing))) => :tool)))

(specification "tool-call: input is validated against the tool's schema"
  (let [reg (registry (->FnTool :t/typed [:map [:n :int]] (fn [_] {:result "ok" :is-error false})))]
    (assertions
      (first (run-call reg (call-chart :t/typed :input {:n "x"}))) => :tool
      (first (run-call reg (call-chart :t/typed :input {:n 3}))) => :done)))

(specification "tool-call: runs off the chart's thread"
  (let [gate (promise)
        reg  (registry (tool :t/wait (fn [_] {:result (str (deref gate 3000 :blocked)) :is-error false})))
        c    (chart/statechart {:initial :calling}
               (state {:id :calling}
                 (on-entry {} (ele/send {:event :ping}))
                 (invoke {:id :call :type :tool-call :params {:tool :t/wait :input {}}})
                 ;; The chart processes :ping while the tool is still running, and opens its gate.
                 (transition {:event :ping} (script {:expr (fn [_ _] (deliver gate "opened") nil)}))
                 (transition {:event :done.invoke.call :target :end} (record! :done)))
               (final {:id :end}))]
    (assertions
      (run-call reg c) => [:done {:result "opened" :is-error false}])))

(specification "tool-call: leaving the state cancels the call and nothing is reported"
  (let [finished (promise)
        reg      (registry (tool :t/slow (fn [_] (try (Thread/sleep 300) {:result "late" :is-error false}
                                                      (finally (deliver finished true))))))
        c        (chart/statechart {:initial :calling}
                   (state {:id :calling}
                     (on-entry {} (ele/send {:event :give-up :delay 20}))
                     (invoke {:id :call :type :tool-call :params {:tool :t/slow :input {}}})
                     (transition {:event :give-up :target :gave-up})
                     (transition {:event :done.invoke.call :target :end}))
                   (state {:id :gave-up}
                     (transition {:event :done.invoke.call :target :end}))
                   (final {:id :end}))
        r        (lib/run {:chart c :session-id :tc/cancel :credentials creds :tool-registry reg
                           :quiescent-sleep-ms 5})
        _        (deref finished 2000 nil)
        _        (Thread/sleep 50)]
    (assertions
      "the run left the state and ended without waiting for the call"
      (:final-config r) => [:gave-up]
      "the cancelled call posted nothing"
      (queue/pending-count (::sc/event-queue (:env r))) => 0)))

(specification "tool-call: installed only when the run has a tool registry"
  (let [procs (fn [reg]
                (let [seen (atom nil)]
                  (lib/run (cond-> {:chart (chart/statechart {:initial :end} (final {:id :end}))
                                    :session-id (keyword "tc" (str (gensym))) :credentials creds
                                    :on-env-ready (fn [env] (reset! seen (::sc/invocation-processors env)))}
                             reg (assoc :tool-registry reg)))
                  (boolean (some #(instance? escapement.invocation.tool_call.ToolCallProcessor %) @seen))))]
    (assertions
      (procs (registry)) => true
      (procs nil) => false)))
