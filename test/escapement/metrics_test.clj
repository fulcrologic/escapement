(ns escapement.metrics-test
  "What a run used: per-call cost from the catalog (unknown is nil, never zero),
   cache tokens, replay-cache hits, one-shot `ask` rows, and run totals."
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state transition]]
    [escapement.chart.helpers :as h]
    [escapement.lib :as lib]
    [escapement.llm :as ellm]
    [escapement.llm.scripted :as scripted]
    [escapement.metrics :as metrics]
    [escapement.testing.tools :as pt]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(defn- approx= [a b] (and (number? a) (number? b) (< (Math/abs (- (double a) (double b))) 1e-9)))

(specification "metrics: the cost of one call"
  (assertions
    "is priced from the catalog by provider and model"
    (:cost-usd (metrics/call-cost {:provider :anthropic :model "claude-sonnet-5"
                                   :usage {:input-tokens 1000000 :output-tokens 100000}}))
    =fn=> #(approx= % (+ 3.0 1.5))
    (:cost-basis (metrics/call-cost {:provider :anthropic :model "claude-sonnet-5"
                                     :usage {:input-tokens 10}}))
    => :catalog
    "cache tokens are priced (and flagged as estimated when the catalog has no cache price)"
    (metrics/call-cost {:provider :anthropic :model "claude-sonnet-5"
                        :usage {:cache-read-input-tokens 1000000 :cache-creation-input-tokens 1000000}})
    =fn=> #(and (approx= (:cost-usd %) (+ 0.3 3.75)) (= :catalog+cache-estimate (:cost-basis %)))
    "a host price table wins"
    (metrics/call-cost {:model "m" :usage {:input-tokens 1000000}} {:prices {"m" {:input 2 :output 4}}})
    => {:cost-usd 2.0 :cost-basis :prices}
    "an unknown model's cost is unknown, never zero"
    (metrics/call-cost {:model "no-such-model" :usage {:input-tokens 5}})
    => {:cost-usd nil :cost-basis :unknown}
    (metrics/response-row-cost {:model "no-such-model" :usage {}})
    => {:cost-usd nil :cost-basis :unknown :cost-unknown? true}
    "the reported model is priced, falling back to the requested one"
    (:cost-usd (metrics/response-row-cost {:provider :anthropic :model "weird-alias"
                                           :model-requested "claude-opus-5"
                                           :usage {:input-tokens 1000000}}))
    =fn=> #(approx= % 5.0)))

(def rows
  [{:event :runner/started :ts 1000 :data {}}
   {:event :llm/response :ts 1500
    :data {:model "a" :provider :p :invokeid "classify" :elapsed-ms 400 :cost-usd 0.25
           :usage {:input-tokens 100 :output-tokens 10 :cache-read-input-tokens 50}}}
   {:event :runner/event-processed :ts 1600 :data {}}
   {:event :llm/response :ts 2000
    :data {:model "a" :provider :p :invokeid "classify" :elapsed-ms 100 :cost-usd 0.5
           :replay-cache/hit? true :usage {:input-tokens 1 :output-tokens 1}}}
   {:event :runner/done :ts 3000 :data {}}])

(specification "metrics: run totals from transcript rows"
  (let [m (metrics/summarize rows)]
    (assertions
      "count calls, tokens, cost, time and cache hits"
      (:ai-calls m) => 2
      (:tokens m) => {:input 101 :output 11 :cache-read 50 :cache-write 0 :total 162}
      (:cost-usd m) =fn=> #(approx= % 0.75)
      (:llm-ms m) => 500
      (:wall-ms m) => 2000
      (:events m) => 1
      (:replay-cache-hits m) => 1
      (get-in m [:by-step "classify" :calls]) => 2
      (get-in m [:by-model "a" :calls]) => 2))
  (component "an unpriced call makes the total unknown"
    (let [m (metrics/summarize (conj rows {:event :llm/response :ts 2500
                                           :data {:model "zz" :cost-usd nil :usage {}}}))]
      (assertions
        (:cost-usd m) => nil
        (:cost-complete? m) => false
        (:cost-known-usd m) =fn=> #(approx= % 0.75)
        (:unpriced-models m) => #{"zz"})))
  (component "the live collector agrees with summarize"
    (let [{:keys [tap snapshot]} (metrics/collector)]
      (run! tap rows)
      (assertions (snapshot) => (metrics/summarize rows))))
  (component "a price table re-prices every call"
    (assertions
      (:cost-usd (metrics/summarize rows {:prices {"a" {:input 1000000 :output 0 :cache-read 0}}}))
      =fn=> #(approx= % 101.0))))

(specification "metrics: AI calls leave priced transcript rows"
  (let [rows  (atom [])
        chart (chart/statechart {:initial :ask}
                (state {:id :ask}
                  (h/llm-conversation {:id "ask" :message "hi" :real-tools []
                                       :allowed-events [{:event :done}]})
                  (transition {:event :done :target :end})
                  (transition {:event :error.llm :target :end}))
                (state {:id :end}))
        b     (scripted/backend {:script {"ask" [{:event :done :usage {:input-tokens 1000000}
                                                  :model "claude-sonnet-5"}]}})
        result (lib/run {:chart chart :session-id (keyword (str "m" (rand-int 1000000000)))
                         :credentials [] :backend b :tool-registry (pt/pretend-registry {})
                         :transcript-tap #(swap! rows conj %)})
        resp  (first (filter #(= :llm/response (:event %)) @rows))
        mrow  (first (filter #(= :runner/metrics (:event %)) @rows))]
    (assertions
      "the conversation turn's row carries model, usage, latency and cost"
      (get-in resp [:data :model]) => "claude-sonnet-5"
      (get-in resp [:data :usage :input-tokens]) => 1000000
      (contains? (:data resp) :elapsed-ms) => true
      (contains? (:data resp) :cost-usd) => true
      "totals come from the rows"
      (:ai-calls (metrics/summarize @rows)) => 1
      "the run's result carries its totals"
      (get-in result [:metrics :ai-calls]) => 1
      (get-in result [:metrics :tokens :input]) => 1000000
      (number? (get-in result [:metrics :wall-ms])) => true
      "and so does its transcript, just before the done row"
      (get-in mrow [:data :ai-calls]) => 1
      (:event (last @rows)) => :runner/done))
  (component "a one-shot ask from a chart env leaves a row too"
    (let [rows (atom [])
          b    (scripted/backend {:script {:default [{:text "hello" :model "no-such-model"}]}})
          env  {:escapement/llm-backend b :escapement/transcript-fn #(swap! rows conj %)}
          r    (ellm/ask env {:prompt "hi" :models [] :model nil})
          row  (first (filter #(= :llm/response (:event %)) @rows))]
      (assertions
        (:status r) => :ok
        (get-in row [:data :one-shot?]) => true
        "unknown pricing is reported as unknown"
        (get-in row [:data :cost-unknown?]) => true
        (get-in row [:data :cost-usd]) => nil))))
