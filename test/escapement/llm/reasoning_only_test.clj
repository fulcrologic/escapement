(ns escapement.llm.reasoning-only-test
  "A turn that spent its whole output cap reasoning and returned nothing
   usable. The provider states BOTH halves on the wire — empty content plus a
   non-empty reasoning sibling field — so this is a wire fact, not a heuristic.

   Verified live 2026-09-07: DeepSeek carries `reasoning_content`, Ollama
   carries `reasoning`. Providers whose wire format does NOT carry the
   distinction never match, and so never change behaviour."
  (:require
    [com.fulcrologic.statecharts.promise :as p]
    [escapement.llm :as llm]
    [escapement.llm.openai :as openai]
    [escapement.llm.protocol :as proto]
    [escapement.llm.reasoning :as rsn]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(defn- openai-payload
  "A parsed OpenAI-shaped chat-completions response with the given assistant
   message fields."
  [msg]
  {"id"      "chatcmpl-1"
   "model"   "m"
   "choices" [{"finish_reason" "length" "message" (merge {"role" "assistant"} msg)}]
   "usage"   {"prompt_tokens" 5 "completion_tokens" 8}})

(specification "detecting a reasoning-only turn on the wire"

  (component "it fires only where the provider carries the distinction"
    (assertions
      "DeepSeek's reasoning_content with empty content"
      (rsn/reasoning-only-message? {"content" "" "reasoning_content" "let me think"}) => true

      "Ollama's reasoning with empty content"
      (rsn/reasoning-only-message? {"content" "" "reasoning" "let me think"}) => true

      "a nil content with reasoning counts too"
      (rsn/reasoning-only-message? {"content" nil "reasoning" "thinking"}) => true))

  (component "it stays narrow — no heuristics, no guessing"
    (assertions
      "an empty response with NO reasoning field is left alone (may be legitimate)"
      (rsn/reasoning-only-message? {"content" ""}) => false

      "content present alongside reasoning is a perfectly good turn"
      (rsn/reasoning-only-message? {"content" "the answer" "reasoning" "thinking"}) => false

      "a tool call is usable output, even with no text"
      (rsn/reasoning-only-message?
        {"content"    ""
         "reasoning"  "thinking"
         "tool_calls" [{"id" "t1" "function" {"name" "f" "arguments" "{}"}}]}) => false

      "an empty reasoning field is not reasoning"
      (rsn/reasoning-only-message? {"content" "" "reasoning" ""}) => false))

  (component "the backend flags it on the Response"
    (assertions
      "a reasoning-only turn is marked in backend-metadata"
      (-> (openai/openai-json->response
            (openai-payload {"content" "" "reasoning_content" "thinking"}) "m")
        :backend-metadata :reasoning-only?) => true

      "an ordinary turn carries no such flag"
      (-> (openai/openai-json->response
            (openai-payload {"content" "the answer"}) "m")
        :backend-metadata (contains? :reasoning-only?)) => false

      "and the predicate reads it back"
      (rsn/reasoning-only-response?
        (openai/openai-json->response
          (openai-payload {"content" "" "reasoning" "thinking"}) "m")) => true)))

;;; ---------------------------------------------------------------------------
;;; The bounded retry

(defn- reasoning-only-response []
  {:stop-reason      :max_tokens
   :content          []
   :usage            {:input-tokens 1 :output-tokens 8}
   :model            "mock"
   :backend-metadata {:backend :openai :reasoning-only? true}})

(defn- good-response []
  {:stop-reason      :end_turn
   :content          [{:type :text :text "the answer"}]
   :usage            {:input-tokens 1 :output-tokens 2}
   :model            "mock"
   :backend-metadata {:backend :openai}})

(defrecord ScriptedBackend [calls responses]
  ;; Pops one canned response per call, so a test can say "reason-only twice,
  ;; then answer".
  proto/LLMBackend
  (send-turn [_ req]
    (swap! calls conj (:model req))
    (let [r (first @responses)]
      (swap! responses rest)
      (p/do! (or r (good-response))))))

(defn- run! [responses max-retries]
  (let [b (->ScriptedBackend (atom []) (atom responses))
        env (llm/run-turn
              {:backend     b
               :aliases     {:primary [{:provider :a :model "mock"}]}
               :preferences [:primary]}
              {:model :primary :resilience {:max-retries max-retries :backoff-ms 0}}
              [{:role :user :content [{:type :text :text "hi"}]}]
              [])]
    {:env env :call-count (count @(:calls b))}))

(specification "a reasoning-only turn is retried, bounded"

  (component "one more attempt turns an empty answer into a real one"
    (let [{:keys [env call-count]} (run! [(reasoning-only-response) (good-response)] 3)]
      (assertions
        "the turn succeeds"
        (:status env) => :ok

        "on the second attempt"
        call-count => 2

        "and the caller gets the real content, not the empty turn"
        (llm/response-text (:response env)) => "the answer")))

  (component "a model that ONLY ever reasons cannot loop on the caller's quota"
    ;; The whole risk of this feature: it must be bounded by the SAME
    ;; :max-retries as any transient error.
    (let [{:keys [call-count]} (run! (repeat 10 (reasoning-only-response)) 2)]
      (assertions
        "the initial attempt plus exactly :max-retries retries, then it stops"
        call-count => 3))

    (let [{:keys [call-count]} (run! (repeat 10 (reasoning-only-response)) 0)]
      (assertions
        ":max-retries 0 means no retry at all"
        call-count => 1)))

  (component "absent the condition, behaviour is unchanged"
    (let [{:keys [env call-count]} (run! [(good-response)] 3)]
      (assertions
        "a good turn is not retried"
        call-count => 1

        "and is returned as before"
        (:status env) => :ok)))

  (component "the retry is reported to the caller under its own category"
    (let [seen (atom [])
          b    (->ScriptedBackend (atom []) (atom [(reasoning-only-response) (good-response)]))]
      (llm/run-turn
        {:backend     b
         :aliases     {:primary [{:provider :a :model "mock"}]}
         :preferences [:primary]
         :hooks       {:on-retry (fn [m] (swap! seen conj (:category m)))}}
        {:model :primary :resilience {:max-retries 3 :backoff-ms 0}}
        [{:role :user :content [{:type :text :text "hi"}]}]
        [])
      (assertions
        "an observer sees :reasoning-only, distinct from a transport error"
        @seen => [:reasoning-only]))))
