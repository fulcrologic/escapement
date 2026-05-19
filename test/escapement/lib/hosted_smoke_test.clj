(ns escapement.lib.hosted-smoke-test
  "task-008 — Hosted-library end-to-end smoke test.

  Drives a `:llm-conversation` chart with a STUB STREAMING backend through the
  hosted facade (`escapement.lib/run`, task-005) wired to the host event-sink
  adapter (`escapement.lib.event-sink`, task-007) over `:transcript-tap`, with
  NO explicit transcript/checkpoint args (exercises task-005 temp-dir
  defaults). It asserts:

  - the ordered public event sequence
    `:run-started -> :llm-request -> :text-delta -> :llm-response -> :run-done`
    (relative ordering + presence; runs interleave other lifecycle events),
  - at least one `:text-delta` carries the OPTIONAL cumulative-usage field
    (task-004) and the running totals are monotonically non-decreasing across
    deltas, while the final `:llm-response` `:usage` stays authoritative
    (buffered == streamed parity, task-003),
  - a single, stable, non-nil `:run-id` on EVERY emitted public event, equal
    to the facade result map's `:run-id` (task-005),
  - the correlation fields `:session-id` (and `:invokeid` where applicable)
    per the task-007 `PublicEvent` schema, and every event conforms to it.

  No network: the stub streaming backend needs no API keys (a `MultiBackend`
  per task-001 routed to a single streaming stub). This is the slice-level
  composition gate proving streaming + facade + event-sink compose end-to-end
  without regressing the CLI path or the embed contract."
  (:require
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :refer [state transition final]]
   [escapement.chart.helpers :as h]
   [escapement.lib :as lib]
   [escapement.lib.event-sink :as es]
   [escapement.llm.multi :as multi]
   [escapement.llm.protocol :as proto]
   [escapement.tools.protocol :as tp]
   [fulcro-spec.core :refer [specification assertions =>]]))

;; ---------------------------------------------------------------------------
;; Stub STREAMING backend (task-001/002 pattern). Each delta carries the
;; OPTIONAL cumulative `:usage` key (task-004 shape == finalized Response
;; usage shape) with a monotonically non-decreasing running output total. The
;; first turn streams deltas then a `tool_use` that fires the chart's `:done`
;; event, taking the chart to a `final` state so the worker stops after one
;; turn (defensive: any later turn returns a clean `end_turn`).
;; ---------------------------------------------------------------------------

(def ^:private stub-deltas
  [{:type :text-delta :text "He"  :usage {:input-tokens 10 :output-tokens 2}}
   {:type :text-delta :text "llo" :usage {:input-tokens 10 :output-tokens 4}}
   {:type :text-delta :text "!"   :usage {:input-tokens 10 :output-tokens 6}}])

(def ^:private first-turn-response
  {:stop-reason :tool_use
   :content     [{:type :tool_use :id "u1" :name "event__done" :input {:msg "hi"}}]
   :usage       {:input-tokens 10 :output-tokens 8}
   :model       "stub-x"})

(def ^:private end-turn-response
  {:stop-reason :end_turn
   :content     [{:type :text :text "bye"}]
   :usage       {:input-tokens 10 :output-tokens 8}
   :model       "stub-x"})

(defrecord OneShotStreamStub [turns]
  proto/LLMBackend
  (send-turn [_ _] end-turn-response)
  proto/StreamingLLMBackend
  (stream-turn [_ _ on-delta]
    (if (zero? (first (swap-vals! turns inc)))
      (do (doseq [d stub-deltas] (on-delta d))
          first-turn-response)
      end-turn-response)))

(defn- new-streaming-stub []
  (->OneShotStreamStub (atom 0)))

(def ^:private smoke-chart
  (chart/statechart
   {:initial :work}
   (state {:id :work :initial :running}
          (state {:id :running}
                 (h/llm-conversation
                  {:id        "main"
                   :params-fn (fn [_ _]
                                {:model                "stub-x"
                                 :stream?              true
                                 :system               "do it"
                                 :real-tools           []
                                 :allowed-events       [{:event       :done
                                                         :data-schema [:map [:msg :string]]}]
                                 :initial-user-message "go"})})
                 (transition {:event :done :target :finished}))
          (final {:id :finished}))))

(defn- index-of [types kw]
  (first (keep-indexed (fn [i t] (when (= kw t) i)) types)))

(defn- monotonic-non-decreasing?
  "True iff `xs` (a seq of numbers, `nil` treated as a 0 floor per task-004
  provider-variance guidance) never decreases."
  [xs]
  (let [ns (map #(or % 0) xs)]
    (every? (fn [[a b]] (<= a b)) (partition 2 1 ns))))

(specification "hosted-smoke: :llm-conversation + stub streaming backend through facade + event-sink"
  (let [backend (multi/new-backend
                 {:routes          [[#"^stub-" (new-streaming-stub)]]
                  :default-backend (new-streaming-stub)})
        adapter (es/make-adapter)
        pub     (atom [])
        ;; NO :transcript-path / :checkpoint-dir -> task-005 temp-dir defaults.
        ;; Explicit `:backend` escape hatch wins; `:credentials` is still
        ;; schema-required (closed contract) but not consulted for assembly.
        result  (lib/run {:chart          smoke-chart
                          :session-id     :hosted-smoke
                          :backend        backend
                          :credentials    [{:provider :anthropic :api-key "sk-unused"}]
                          :tool-registry  (tp/new-registry)
                          :transcript-tap (fn [row]
                                            (doseq [e ((:feed adapter) row)]
                                              (swap! pub conj e)))})
        events  @pub
        types   (mapv :type events)
        deltas  (filter #(= :text-delta (:type %)) events)
        usages  (map #(get-in % [:delta :usage]) deltas)
        resp    (first (filter #(= :llm-response (:type %)) events))
        run-id  (:run-id result)]
    (assertions
      "the streaming stub backend advertises StreamingLLMBackend through the multi"
      (proto/streaming? backend) => true
      "the chart ran to quiescence and reached its final state"
      (:status result) => :done
      (boolean (some #{:finished} (:final-config result))) => true
      "facade returned a stable 36-char string :run-id"
      (string? run-id) => true
      (count run-id) => 36

      ;; ---- Ordered public event sequence (relative ordering + presence) ----
      "every anchor event is present"
      (every? (set types) [:run-started :llm-request :text-delta :llm-response :run-done])
      => true
      ":run-started comes before :llm-request"
      (< (index-of types :run-started) (index-of types :llm-request)) => true
      ":llm-request comes before the first :text-delta"
      (< (index-of types :llm-request) (index-of types :text-delta)) => true
      "the first :text-delta comes before :llm-response"
      (< (index-of types :text-delta) (index-of types :llm-response)) => true
      ":llm-response comes before :run-done"
      (< (index-of types :llm-response) (index-of types :run-done)) => true
      "at least one :text-delta was emitted"
      (>= (count deltas) 1) => true

      ;; ---- Running usage: optional, monotonic, non-authoritative ----
      "at least one :text-delta carries the optional cumulative :usage field"
      (boolean (some some? usages)) => true
      "running output-token totals are monotonically non-decreasing across deltas"
      (monotonic-non-decreasing? (map :output-tokens usages)) => true
      "running input-token totals are monotonically non-decreasing across deltas"
      (monotonic-non-decreasing? (map :input-tokens usages)) => true
      "a delta consumer that ignores :usage still sees text"
      (mapv #(get-in % [:delta :text]) deltas) => ["He" "llo" "!"]
      "the finalized :llm-response :usage is authoritative (>= last running delta total)"
      (>= (:output-tokens (:usage resp))
          (or (:output-tokens (last usages)) 0)) => true
      (:usage resp) => {:input-tokens 10 :output-tokens 8}

      ;; ---- Stable :run-id on EVERY event, == facade result ----
      "exactly one distinct :run-id across all public events"
      (distinct (map :run-id events)) => [run-id]
      "every event carries a non-nil :run-id"
      (every? (comp some? :run-id) events) => true
      "that :run-id equals the facade result map's :run-id"
      (= #{run-id} (set (map :run-id events))) => true

      ;; ---- Correlation fields per task-007 PublicEvent schema ----
      "every event conforms to the closed PublicEvent schema"
      (every? es/valid-event? events) => true
      "every event carries a :session-id correlation field"
      (every? #(contains? % :session-id) events) => true
      "exactly one distinct (non-nil) :session-id across all events"
      (count (distinct (map :session-id events))) => 1
      "LLM-family events carry the :invokeid correlation field"
      (every? #(contains? % :invokeid)
              (filter #(#{:llm-request :text-delta :llm-response} (:type %)) events))
      => true)))
