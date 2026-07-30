(ns escapement.llm.openai-codex.translate-test
  "Pure roundtrip tests for the Anthropic ↔ OpenAI translation layer."
  (:require
    [cheshire.core :as json]
    [escapement.llm.catalog :as catalog]
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
    "passes the supported ids through unchanged"
    (t/normalize-model "gpt-5.6-sol") => "gpt-5.6-sol"
    (t/normalize-model "gpt-5.6-terra") => "gpt-5.6-terra"
    (t/normalize-model "gpt-5.6-luna") => "gpt-5.6-luna"
    (t/normalize-model "gpt-5.5") => "gpt-5.5"
    (t/normalize-model "gpt-5.4") => "gpt-5.4"
    (t/normalize-model "gpt-5.4-mini") => "gpt-5.4-mini"

    "nil falls back to the flagship (Sol)"
    (t/normalize-model nil) => "gpt-5.6-sol"
    (t/normalize-model "  ") => "gpt-5.6-sol"

    "unknown string passes through so the backend surfaces its own error"
    (t/normalize-model "some-future-model") => "some-future-model")

  (component "retired ids are REMAPPED, not passed through"
    ;; ChatGPT-account auth (the only mode this backend has) rejects every one of
    ;; these with "The '<id>' model is not supported when using Codex with a
    ;; ChatGPT account" — verified live 2026-07-29. Passing them through would
    ;; guarantee a 400, and `gpt-5.1-codex` was this backend's OWN documented
    ;; default, so `--backend codex` with no --model could not work at all.
    (assertions
      "every `-codex` variant maps to Sol, OpenAI's best coding model"
      (t/normalize-model "gpt-5.1-codex") => "gpt-5.6-sol"
      (t/normalize-model "gpt-5.1-codex-max") => "gpt-5.6-sol"
      (t/normalize-model "gpt-5.2-codex") => "gpt-5.6-sol"
      (t/normalize-model "gpt-5.3-codex") => "gpt-5.6-sol"
      (t/normalize-model "gpt-5-codex") => "gpt-5.6-sol"
      "the mini/spark tier maps to Luna, the cheapest of the family"
      (t/normalize-model "gpt-5.1-codex-mini") => "gpt-5.6-luna"
      (t/normalize-model "gpt-5.3-codex-spark") => "gpt-5.6-luna"
      (t/normalize-model "codex-mini-latest") => "gpt-5.6-luna"
      "superseded general models map forward"
      (t/normalize-model "gpt-5") => "gpt-5.6-sol"
      (t/normalize-model "gpt-5.1") => "gpt-5.6-sol"
      (t/normalize-model "gpt-5.2") => "gpt-5.6-sol"
      "bare `gpt-5.6` is REJECTED by the endpoint — only the suffixed
       Luna/Terra/Sol ids work, so it maps to the flagship"
      (t/normalize-model "gpt-5.6") => "gpt-5.6-sol"
      "Sol's \"Ultra\" is a high-effort MODE, not a distinct model id"
      (t/normalize-model "gpt-5.6-sol-ultra") => "gpt-5.6-sol"
      "`-pro` / `-nano` tiers have no ChatGPT-account equivalent, so they degrade"
      (t/normalize-model "gpt-5.5-pro") => "gpt-5.6-sol"
      (t/normalize-model "gpt-5.4-pro") => "gpt-5.6-sol"
      (t/normalize-model "gpt-5.4-nano") => "gpt-5.6-luna"

      "and NOTHING normalizes to an id outside the supported set"
      (->> ["gpt-5.1-codex" "gpt-5.2-codex" "gpt-5.1-codex-mini" "gpt-5.3-codex"
            "gpt-5.3-codex-spark" "codex-mini-latest" "gpt-5" "gpt-5.1" "gpt-5.2"
            "gpt-5.6" "gpt-5.6-sol-ultra" "gpt-5-codex" "gpt-5-mini"
            "gpt-5.5-pro" "gpt-5.4-pro" "gpt-5.4-nano" nil
            "claude-opus-5" "claude-sonnet-5" "claude-fable-5" "claude-haiku-4-5"]
        (mapv t/normalize-model)
        (remove (set t/supported-models))
        vec) => []))

  (component "Anthropic names map by family"
    (assertions
      "opus/sonnet/fable → flagship"
      (t/normalize-model "claude-opus-5") => "gpt-5.6-sol"
      (t/normalize-model "claude-sonnet-4-6") => "gpt-5.6-terra"
      (t/normalize-model "claude-fable-5") => "gpt-5.6-sol"
      "haiku → mini"
      (t/normalize-model "claude-haiku-4-5") => "gpt-5.6-luna"
      "matching is by PREFIX, so dated and future ids both resolve"
      (t/normalize-model "claude-sonnet-5") => "gpt-5.6-terra"
      (t/normalize-model "claude-opus-5") => "gpt-5.6-sol"
      (t/normalize-model "claude-haiku-4-5-20251001") => "gpt-5.6-luna"))

  (component "the catalog and the backend agree on the supported set"
    ;; Drift here is what produced the original bug: the catalog advertised
    ;; `gpt-5`/`gpt-5-mini`/`o3` while the backend defaulted to `gpt-5.1-codex`,
    ;; and none of the four actually worked.
    (assertions
      "the catalog's :openai-codex models are exactly translate/supported-models"
      (set (keys (:models (catalog/provider-info :openai-codex))))
      => (set t/supported-models)

      "the default model is one of them"
      (contains? (set t/supported-models) t/default-model) => true

      "and :openai-codex is still priced as a flat-fee subscription — EVERY
       supported model is zero-cost, however many there are"
      (catalog/subscription? :openai-codex) => true
      (->> t/supported-models
        (remove #(= {:input 0 :output 0} (catalog/pricing :openai-codex %)))
        vec) => []

      "the `:codex` spelling of the provider agrees — `provider-templates`
       accepts both, and `preferences/default-aliases` names `:codex`"
      (catalog/subscription? :codex) => true
      (->> t/supported-models
        (remove #(= {:input 0 :output 0} (catalog/pricing :codex %)))
        vec) => [])))

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
      (get-in items [1 :content 0 :type]) => "output_text"
      (get-in items [1 :content 0 :text]) => "Hi there!")))

(specification "anthropic-messages->openai-input — verdict follow-up history"
  (let [messages [{:role :user
                   :content [{:type :text :text "work"}]}
                  {:role :assistant
                   :content [{:type :thinking :thinking "calling tool" :signature "encrypted-1"}
                             {:type :tool_use :id "call-1" :name "work" :input {}}]}
                  {:role :user
                   :content [{:type :tool_result :tool_use_id "call-1" :content "result"}]}
                  {:role :assistant
                   :content [{:type :thinking :thinking "finalizing" :signature "encrypted-2"}
                             {:type :text :text "{\"status\":\"done\"}"}]}
                  {:role :user
                   :content [{:type :text :text "Please call submit_verdict."}]}]
        items    (t/anthropic-messages->openai-input messages)]
    (assertions
      "preserves the Responses API item order"
      (mapv :type items) => ["message" "reasoning" "function_call" "function_call_output"
                             "reasoning" "message" "message"]
      "preserves the final encrypted reasoning signature"
      (get-in items [4 :encrypted_content]) => "encrypted-2"
      "replays assistant text as output text"
      (select-keys (get items 5) [:type :role]) => {:type "message" :role "assistant"}
      (get-in items [5 :content 0 :type]) => "output_text"
      "encodes the verdict nudge as user input text"
      (select-keys (get items 6) [:type :role]) => {:type "message" :role "user"}
      (get-in items [6 :content 0 :type]) => "input_text")))

(specification "build-request-body — named tool choice"
  (let [body (t/build-request-body
               {:model       "gpt-5.5"
                :messages    []
                :tools       [{:name "submit_verdict" :description "Submit" :input-schema {}}]
                :tool-choice {:type :tool :name "submit_verdict"}})]
    (assertions
      "forces the named Responses API function"
      (:tool_choice body) => {:type "function" :name "submit_verdict"})))

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
