(ns escapement.llm.openai-test
  (:require
   [cheshire.core :as json]
   [escapement.llm.openai :as oai]
   [escapement.llm.protocol :as proto]
   [escapement.llm.types :as types]
   [fulcro-spec.core :refer [specification assertions component =>]]))

(def sample-request
  {:model     "gpt-5"
   :system    "You are helpful."
   :system-cache-control {:type :ephemeral} ;; should be silently dropped
   :messages  [{:role :user
                :content [{:type :text :text "Hello"}]}
               {:role :assistant
                :content [{:type :text :text "Hi"}
                          {:type :tool_use :id "call_1" :name "get_weather"
                           :input {:location "Paris"}}]}
               {:role :user
                :content [{:type :tool_result :tool_use_id "call_1"
                           :content "{\"temp\":15}"}
                          {:type :tool_result :tool_use_id "call_1b"
                           :content "boom" :is-error true}]}
               {:role :user
                :content [{:type :text :text "Tell me a joke"
                           :cache-control {:type :ephemeral}}]}] ;; dropped
   :tools     [{:name "get_weather"
                :description "Get the weather"
                :input-schema {:type "object"
                               :properties {:location {:type "string"}}
                               :required ["location"]}
                :cache-control {:type :ephemeral}}] ;; dropped
   :max-tokens 1024})

(specification "request->openai-json"
               (let [wire (oai/request->openai-json sample-request)]
                 (assertions
                  "uses string top-level keys"
                  (get wire "model") => "gpt-5"
                  "newer model uses max_completion_tokens, not max_tokens"
                  (get wire "max_completion_tokens") => 1024
                  (contains? wire "max_tokens") => false
                  "system becomes the leading message"
                  (get-in wire ["messages" 0 "role"])    => "system"
                  (get-in wire ["messages" 0 "content"]) => "You are helpful."
                  "user text becomes a string-content user message"
                  (get-in wire ["messages" 1 "role"])    => "user"
                  (get-in wire ["messages" 1 "content"]) => "Hello"
                  "assistant text+tool_use folds into one assistant message with tool_calls"
                  (get-in wire ["messages" 2 "role"])           => "assistant"
                  (get-in wire ["messages" 2 "content"])        => "Hi"
                  (count (get-in wire ["messages" 2 "tool_calls"])) => 1
                  (get-in wire ["messages" 2 "tool_calls" 0 "id"]) => "call_1"
                  (get-in wire ["messages" 2 "tool_calls" 0 "type"]) => "function"
                  (get-in wire ["messages" 2 "tool_calls" 0 "function" "name"]) => "get_weather"
                  "tool_call arguments are JSON-encoded strings"
                  (json/parse-string
                   (get-in wire ["messages" 2 "tool_calls" 0 "function" "arguments"]))
                  => {"location" "Paris"}
                  "each tool_result fans out into its own role:tool message"
                  (get-in wire ["messages" 3 "role"])         => "tool"
                  (get-in wire ["messages" 3 "tool_call_id"]) => "call_1"
                  (get-in wire ["messages" 3 "content"])      => "{\"temp\":15}"
                  (get-in wire ["messages" 4 "role"])         => "tool"
                  (get-in wire ["messages" 4 "tool_call_id"]) => "call_1b"
                  "is_error is surfaced inline since OpenAI has no flag for it"
                  (.startsWith ^String (get-in wire ["messages" 4 "content"]) "[error]") => true
                  "later plain user message still translates"
                  (get-in wire ["messages" 5 "role"])    => "user"
                  (get-in wire ["messages" 5 "content"]) => "Tell me a joke"
                  "tools become OpenAI function-tools with no cache_control"
                  (get-in wire ["tools" 0 "type"])                  => "function"
                  (get-in wire ["tools" 0 "function" "name"])       => "get_weather"
                  (get-in wire ["tools" 0 "function" "parameters" :type]) => "object"
                  (contains? (get-in wire ["tools" 0]) "cache_control") => false)))

(specification "max_tokens key flip for legacy models"
               (assertions
                "gpt-4o uses legacy max_tokens"
                (get (oai/request->openai-json (assoc sample-request :model "gpt-4o")) "max_tokens")
                => 1024
                "gpt-3.5-turbo uses legacy max_tokens"
                (get (oai/request->openai-json (assoc sample-request :model "gpt-3.5-turbo")) "max_tokens")
                => 1024
                "o3 uses new max_completion_tokens"
                (get (oai/request->openai-json (assoc sample-request :model "o3")) "max_completion_tokens")
                => 1024
                "OpenAI-compatible provider models use max_tokens"
                (get (oai/request->openai-json (assoc sample-request :model "kimi-k2.5")) "max_tokens")
                => 1024
                "OpenRouter-style namespaced ids keep existing max_completion_tokens behavior"
                (get (oai/request->openai-json (assoc sample-request :model "openai/gpt-4o-mini")) "max_completion_tokens")
                => 1024))

(specification "tool-choice translation"
               (assertions
                ":auto -> \"auto\""
                (get (oai/request->openai-json (assoc sample-request :tool-choice :auto)) "tool_choice")
                => "auto"
                ":any -> \"required\""
                (get (oai/request->openai-json (assoc sample-request :tool-choice :any)) "tool_choice")
                => "required"
                ":none -> \"none\""
                (get (oai/request->openai-json (assoc sample-request :tool-choice :none)) "tool_choice")
                => "none"
                "named tool -> function object"
                (get (oai/request->openai-json
                      (assoc sample-request :tool-choice {:type :tool :name "get_weather"}))
                     "tool_choice")
                => {"type" "function" "function" {"name" "get_weather"}}))

(specification "metadata.user-id maps to \"user\""
               (assertions
                (get (oai/request->openai-json (assoc sample-request :metadata {:user-id "alice"}))
                     "user")
                => "alice"))

(def sample-openai-response
  {"id"      "chatcmpl-abc"
   "model"   "gpt-5"
   "choices" [{"index"         0
               "finish_reason" "tool_calls"
               "message"       {"role"       "assistant"
                                "content"    "calling tool"
                                "tool_calls" [{"id"       "call_1"
                                               "type"     "function"
                                               "function" {"name"      "get_weather"
                                                           "arguments" "{\"location\":\"Paris\"}"}}]}}]
   "usage"   {"prompt_tokens"          12
              "completion_tokens"      8
              "prompt_tokens_details"  {"cached_tokens" 100}}})

(specification "openai-json->response"
               (let [resp (oai/openai-json->response sample-openai-response "gpt-5")]
                 (assertions
                  "produces a Malli-valid Response"
                  (types/validate-response resp) => nil
                  "finish_reason tool_calls -> :tool_use"
                  (:stop-reason resp) => :tool_use
                  "text then tool_use blocks"
                  (count (:content resp)) => 2
                  (get-in resp [:content 0]) => {:type :text :text "calling tool"}
                  (get-in resp [:content 1 :type]) => :tool_use
                  (get-in resp [:content 1 :id])   => "call_1"
                  (get-in resp [:content 1 :name]) => "get_weather"
                  "tool_use :input is keyword-keyed (parity with Anthropic backend)"
                  (get-in resp [:content 1 :input]) => {:location "Paris"}
                  "usage maps to our shape; cached_tokens -> :cache-read-input-tokens"
                  (get-in resp [:usage :input-tokens])            => 12
                  (get-in resp [:usage :output-tokens])           => 8
                  (get-in resp [:usage :cache-read-input-tokens]) => 100
                  "model echoed and backend tagged"
                  (:model resp) => "gpt-5"
                  (get-in resp [:backend-metadata :backend])    => :openai
                  (get-in resp [:backend-metadata :message-id]) => "chatcmpl-abc")))

(specification "finish_reason mapping"
               (let [resp (fn [fr]
                            (oai/openai-json->response
                             {"model" "gpt-5"
                              "choices" [{"finish_reason" fr
                                          "message"       {"role" "assistant" "content" "x"}}]
                              "usage" {}}
                             "gpt-5"))]
                 (assertions
                  "stop -> :end_turn"           (:stop-reason (resp "stop"))           => :end_turn
                  "length -> :max_tokens"       (:stop-reason (resp "length"))         => :max_tokens
                  "tool_calls -> :tool_use"     (:stop-reason (resp "tool_calls"))     => :tool_use
                  "content_filter -> :refusal"  (:stop-reason (resp "content_filter")) => :refusal)))

(specification "pure-tool-call response (no text content) is valid"
  ;; Some turns return null content with only tool_calls.
               (let [parsed {"model"   "gpt-5"
                             "choices" [{"finish_reason" "tool_calls"
                                         "message"       {"role"       "assistant"
                                                          "content"    nil
                                                          "tool_calls" [{"id" "c1" "type" "function"
                                                                         "function" {"name" "t"
                                                                                     "arguments" "{}"}}]}}]
                             "usage"   {}}
                     resp   (oai/openai-json->response parsed "gpt-5")]
                 (assertions
                  "response is Malli-valid"
                  (types/validate-response resp) => nil
                  "only a single tool_use block"
                  (count (:content resp)) => 1
                  (get-in resp [:content 0 :type]) => :tool_use)))

