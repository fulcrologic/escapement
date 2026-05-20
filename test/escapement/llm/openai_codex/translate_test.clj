(ns escapement.llm.openai-codex.translate-test
  "Pure roundtrip tests for the Anthropic ↔ OpenAI translation layer."
  (:require
    [cheshire.core :as json]
    [escapement.llm.openai-codex.translate :as t]
    [fulcro-spec.core :refer [=> assertions component specification]]))

;;; ---------------------------------------------------------------------------
;;; Sample data

(def user-text-msg
  {:role :user :content [{:type :text :text "Hello!"}]})

(def assistant-text-msg
  {:role :assistant :content [{:type :text :text "Hi there!"}]})

(def assistant-tool-use-msg
  {:role    :assistant
   :content [{:type  :tool_use
              :id    "call_xyz"
              :name  "lookup"
              :input {:query "foo"}}]})

(def user-tool-result-msg
  {:role    :user
   :content [{:type        :tool_result
              :tool_use_id "call_xyz"
              :content     "bar result"}]})

(def assistant-thinking-msg
  {:role    :assistant
   :content [{:type      :thinking
              :thinking  "I should look this up"
              :signature "enc_sig_abc"}]})

;;; ---------------------------------------------------------------------------
;;; Tests

(specification "normalize-model"
  (assertions
    "passes known codex IDs through unchanged"
    (t/normalize-model "gpt-5.1-codex") => "gpt-5.1-codex"
    (t/normalize-model "gpt-5.2-codex") => "gpt-5.2-codex"
    (t/normalize-model "gpt-5.1-codex-mini") => "gpt-5.1-codex-mini"
    (t/normalize-model "codex-mini-latest") => "codex-mini-latest"
    "maps claude-opus to gpt-5.2-codex"
    (t/normalize-model "claude-opus-4-7") => "gpt-5.2-codex"
    "maps claude-sonnet to gpt-5.2-codex"
    (t/normalize-model "claude-sonnet-4-6") => "gpt-5.2-codex"
    "maps claude-haiku to gpt-5.1-codex-mini"
    (t/normalize-model "claude-haiku-4-5") => "gpt-5.1-codex-mini"
    "nil falls back to gpt-5.1"
    (t/normalize-model nil) => "gpt-5.1"
    "unknown string passes through"
    (t/normalize-model "some-future-model") => "some-future-model"))

(specification "anthropic-messages->openai-input — text-only messages"
  (let [items (t/anthropic-messages->openai-input [user-text-msg assistant-text-msg])]
    (assertions
      "produces two items"
      (count items) => 2
      "first item is a user message"
      (get-in items [0 :type]) => "message"
      (get-in items [0 :role]) => "user"
      (get-in items [0 :content 0 :type]) => "input_text"
      (get-in items [0 :content 0 :text]) => "Hello!"
      "second item is an assistant message"
      (get-in items [1 :role]) => "assistant"
      (get-in items [1 :content 0 :text]) => "Hi there!")))

(specification "anthropic-messages->openai-input — assistant tool_use"
  (let [items (t/anthropic-messages->openai-input [assistant-tool-use-msg])]
    (assertions
      "produces one function_call item"
      (count items) => 1
      (get-in items [0 :type]) => "function_call"
      (get-in items [0 :call_id]) => "call_xyz"
      (get-in items [0 :name]) => "lookup"
      "arguments are JSON-serialized input"
      (get-in items [0 :arguments]) => (json/generate-string {:query "foo"}))))

(specification "anthropic-messages->openai-input — user tool_result"
  (let [items (t/anthropic-messages->openai-input [user-tool-result-msg])]
    (assertions
      "produces one function_call_output item"
      (count items) => 1
      (get-in items [0 :type]) => "function_call_output"
      (get-in items [0 :call_id]) => "call_xyz"
      (get-in items [0 :output]) => "bar result")))

(specification "anthropic-messages->openai-input — thinking block preserves signature"
  (let [items (t/anthropic-messages->openai-input [assistant-thinking-msg])]
    (assertions
      "produces one reasoning item"
      (count items) => 1
      (get-in items [0 :type]) => "reasoning"
      (get-in items [0 :encrypted_content]) => "enc_sig_abc"
      "summary text is preserved"
      (get-in items [0 :summary 0 :text]) => "I should look this up")))

(specification "openai-items->anthropic-content — output items to content blocks"
  (component "message item with output_text"
    (let [item   {:type    "message" :role "assistant"
                  :content [{:type "output_text" :text "Answer"}]}
          blocks (t/openai-items->anthropic-content [item])]
      (assertions
        "produces one text block"
        (count blocks) => 1
        (:type (first blocks)) => :text
        (:text (first blocks)) => "Answer")))

  (component "function_call item"
    (let [item   {:type      "function_call" :call_id "call_1" :name "my_fn"
                  :arguments "{\"x\":42}"}
          blocks (t/openai-items->anthropic-content [item])]
      (assertions
        "produces one :tool_use block"
        (count blocks) => 1
        (:type (first blocks)) => :tool_use
        (:id (first blocks)) => "call_1"
        (:name (first blocks)) => "my_fn"
        "arguments are parsed as keyword-keyed map"
        (:input (first blocks)) => {:x 42})))

  (component "reasoning item with summary"
    (let [item   {:type    "reasoning" :encrypted_content "enc_abc"
                  :summary [{:type "summary_text" :text "My reasoning"}]}
          blocks (t/openai-items->anthropic-content [item])]
      (assertions
        "produces one :thinking block"
        (count blocks) => 1
        (:type (first blocks)) => :thinking
        (:signature (first blocks)) => "enc_abc"
        (:thinking (first blocks)) => "My reasoning")))

  (component "function_call with invalid JSON arguments"
    (let [item   {:type      "function_call" :call_id "c2" :name "fn"
                  :arguments "not-json"}
          blocks (t/openai-items->anthropic-content [item])]
      (assertions
        "falls back to empty map for :input"
        (:input (first blocks)) => {}))))

(specification "openai-response->anthropic-response"
  (component "happy path"
    (let [stream-result {:items       [{:type    "message" :role "assistant"
                                        :content [{:type "output_text" :text "Hi"}]}]
                         :usage       {:input_tokens         5 :output_tokens 2
                                       :input_tokens_details {:cached_tokens 3}}
                         :stop-reason :end_turn
                         :model       "gpt-5.1-codex"}
          resp          (t/openai-response->anthropic-response stream-result "gpt-5.1-codex")]
      (assertions
        "stop-reason is carried through"
        (:stop-reason resp) => :end_turn
        "model from stream result is used"
        (:model resp) => "gpt-5.1-codex"
        "content blocks are translated"
        (get-in resp [:content 0 :type]) => :text
        (get-in resp [:content 0 :text]) => "Hi"
        "input-tokens from usage"
        (get-in resp [:usage :input-tokens]) => 5
        "output-tokens from usage"
        (get-in resp [:usage :output-tokens]) => 2
        "cached tokens extracted from input_tokens_details"
        (get-in resp [:usage :cache-read-input-tokens]) => 3
        "cache-creation is always 0"
        (get-in resp [:usage :cache-creation-input-tokens]) => 0
        "backend-metadata identifies openai-codex"
        (get-in resp [:backend-metadata :backend]) => :openai-codex)))

  (component "empty/missing usage does not blow up"
    (let [stream-result {:items [] :usage {} :stop-reason :end_turn :model nil}
          resp          (t/openai-response->anthropic-response stream-result "fallback-model")]
      (assertions
        "falls back to request-model when stream model is nil"
        (:model resp) => "fallback-model"
        "input-tokens defaults to 0"
        (get-in resp [:usage :input-tokens]) => 0
        "output-tokens defaults to 0"
        (get-in resp [:usage :output-tokens]) => 0
        "content is empty vector"
        (:content resp) => []))))
