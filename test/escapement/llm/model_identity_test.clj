(ns escapement.llm.model-identity-test
  "Two facts a turn must not lose:

   1. WHICH MODEL ACTUALLY RAN. Providers substitute silently — z.ai answers a
      retired `glm-4.6` request with `glm-5.3-flash`, DeepSeek answers
      `deepseek-chat` with `deepseek-v4-flash` (both verified live 2026-09-07).
      Anything that prices or compares a run by the id it ASKED for is then
      confidently wrong, and nothing looks broken. The requested and reported
      ids are therefore never collapsed into one field.

   2. WHAT A FAILED TURN COST. A streamed turn that errors, is cancelled, or is
      abandoned on the latency cap still burned tokens; the running usage from
      its deltas used to die with the stream. A cancelled run is exactly when
      someone wants to know what it cost."
  (:require
    [com.fulcrologic.statecharts.promise :as p]
    [escapement.llm :as llm]
    [escapement.llm.protocol :as proto]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(defn- response [model]
  {:stop-reason :end_turn
   :content     [{:type :text :text "hi"}]
   :usage       {:input-tokens 1 :output-tokens 2}
   :model       model})

(defrecord FixedModelBackend [answers-as]
  proto/LLMBackend
  (send-turn [_ _] (p/do! (response answers-as))))

(defn- run [answers-as]
  (llm/run-turn
    {:backend     (->FixedModelBackend answers-as)
     :aliases     {:primary [{:provider :a :model "asked-for"}]}
     :preferences [:primary]}
    {:model :primary}
    [{:role :user :content [{:type :text :text "hi"}]}]
    []))

(specification "requested and reported model ids are both kept"

  (component "a silent substitution is observable, not smoothed over"
    (let [env (run "answered-with")]
      (assertions
        "the turn still succeeds — a substitution is not an error"
        (:status env) => :ok

        "the id we asked for is preserved"
        (:model env) => "asked-for"

        "the id the provider reported is preserved separately"
        (:model-reported env) => "answered-with"

        "and the mismatch is flagged"
        (:model-substituted? env) => true

        "both ids ride on the Response too, for anything that records a turn"
        (select-keys (:response env) [:model :model-requested :model-substituted?])
        => {:model "answered-with" :model-requested "asked-for" :model-substituted? true})))

  (component "no substitution, no flag"
    (let [env (run "asked-for")]
      (assertions
        "the ids agree"
        [(:model env) (:model-reported env)] => ["asked-for" "asked-for"]

        "nothing is flagged"
        (:model-substituted? env) => false

        "and the Response carries no substitution marker"
        (contains? (:response env) :model-substituted?) => false

        "though it still records what was asked for"
        (:model-requested (:response env)) => "asked-for")))

  (component "the predicate itself"
    (assertions
      "differing ids are a substitution"
      (llm/model-substituted? "a" {:model "b"}) => true

      "identical ids are not"
      (llm/model-substituted? "a" {:model "a"}) => false

      "a response that reports no model at all claims nothing"
      (llm/model-substituted? "a" {}) => false)))

;;; ---------------------------------------------------------------------------
;;; Usage from a turn that never finished

(defrecord FailingStreamBackend [usage-before-failure throw-fn]
  proto/LLMBackend
  (send-turn [_ _] (p/do! (response "m")))
  proto/StreamingLLMBackend
  (stream-turn [_ _ on-delta]
    (p/do!
      ;; Deltas carry a running cumulative usage, exactly as the real backends
      ;; do, and THEN the turn dies.
      (on-delta {:type :text-delta :text "par" :usage usage-before-failure})
      (throw (throw-fn)))))

(defn- run-failing [t]
  (llm/run-turn
    {:backend     (->FailingStreamBackend {:input-tokens 10 :output-tokens 4} (fn [] t))
     :aliases     {:primary [{:provider :a :model "m"}]}
     :preferences [:primary]
     :hooks       {:delta-sink (fn [_ _] (fn [_] nil))}}
    {:model :primary :resilience {:max-retries 0 :backoff-ms 0}}
    [{:role :user :content [{:type :text :text "hi"}]}]
    []))

(specification "usage survives a turn that did not complete"

  (component "a failed streamed turn reports what it burned"
    (let [env (run-failing (ex-info "boom" {}))]
      (assertions
        "the turn failed"
        (:status env) => :exhausted

        "and the tokens it spent before failing are on the envelope"
        (:partial-usage env) => {:input-tokens 10 :output-tokens 4})))

  (component "a cancelled turn reports what it burned"
    (let [env (run-failing (InterruptedException. "cancelled"))]
      (assertions
        "the turn was interrupted"
        (:status env) => :interrupted

        "the numbers are not thrown away with the stream"
        (:partial-usage env) => {:input-tokens 10 :output-tokens 4})))

  (component "nothing is invented when nothing was seen"
    ;; A backend that fails before emitting any usage has nothing to report,
    ;; and the envelope must not claim otherwise.
    (let [env (llm/run-turn
                {:backend     (->FailingStreamBackend nil (fn [] (ex-info "boom" {})))
                 :aliases     {:primary [{:provider :a :model "m"}]}
                 :preferences [:primary]
                 :hooks       {:delta-sink (fn [_ _] (fn [_] nil))}}
                {:model :primary :resilience {:max-retries 0 :backoff-ms 0}}
                [{:role :user :content [{:type :text :text "hi"}]}]
                [])]
      (assertions
        "no :partial-usage key at all"
        (contains? env :partial-usage) => false)))

  (component "a successful turn is untouched"
    (let [env (run "m")]
      (assertions
        "success reports real usage, and no partial"
        [(:usage env) (contains? env :partial-usage)]
        => [{:input-tokens 1 :output-tokens 2} false])))

  (component "the usage summer"
    (assertions
      "numeric fields add"
      (llm/sum-usage {:input-tokens 1} {:input-tokens 2 :output-tokens 3})
      => {:input-tokens 3 :output-tokens 3}

      "nil operands are identities"
      (llm/sum-usage nil {:input-tokens 1}) => {:input-tokens 1})))
