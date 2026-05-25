(ns escapement.llm.openai-test
  (:require
    [babashka.http-client]
    [cheshire.core :as json]
    [clojure.string :as str]
    [escapement.llm.openai :as oai]
    [escapement.llm.protocol :as proto]
    [escapement.llm.types :as types]
    [fulcro-spec.core :refer [=> assertions specification]]
    [com.fulcrologic.statecharts.promise :as p])
  (:import (java.io BufferedReader StringReader)))

(def sample-request
  {:model                "gpt-5"
   :system               "You are helpful."
   :system-cache-control {:type :ephemeral}                 ;; should be silently dropped
   :messages             [{:role    :user
                           :content [{:type :text :text "Hello"}]}
                          {:role    :assistant
                           :content [{:type :text :text "Hi"}
                                     {:type  :tool_use :id "call_1" :name "get_weather"
                                      :input {:location "Paris"}}]}
                          {:role    :user
                           :content [{:type    :tool_result :tool_use_id "call_1"
                                      :content "{\"temp\":15}"}
                                     {:type    :tool_result :tool_use_id "call_1b"
                                      :content "boom" :is-error true}]}
                          {:role    :user
                           :content [{:type          :text :text "Tell me a joke"
                                      :cache-control {:type :ephemeral}}]}] ;; dropped
   :tools                [{:name          "get_weather"
                           :description   "Get the weather"
                           :input-schema  {:type       "object"
                                           :properties {:location {:type "string"}}
                                           :required   ["location"]}
                           :cache-control {:type :ephemeral}}] ;; dropped
   :max-tokens           1024})

(specification "request->openai-json"
  (let [wire (oai/request->openai-json sample-request)]
    (assertions
      "uses string top-level keys"
      (get wire "model") => "gpt-5"
      "newer model uses max_completion_tokens, not max_tokens"
      (get wire "max_completion_tokens") => 1024
      (contains? wire "max_tokens") => false
      "system becomes the leading message"
      (get-in wire ["messages" 0 "role"]) => "system"
      (get-in wire ["messages" 0 "content"]) => "You are helpful."
      "user text becomes a single text content-block (uniform shape; the
       array form is OpenAI's superset that vision requires)"
      (get-in wire ["messages" 1 "role"]) => "user"
      (get-in wire ["messages" 1 "content"]) => [{"type" "text" "text" "Hello"}]
      "assistant text+tool_use folds into one assistant message with tool_calls"
      (get-in wire ["messages" 2 "role"]) => "assistant"
      (get-in wire ["messages" 2 "content"]) => "Hi"
      (count (get-in wire ["messages" 2 "tool_calls"])) => 1
      (get-in wire ["messages" 2 "tool_calls" 0 "id"]) => "call_1"
      (get-in wire ["messages" 2 "tool_calls" 0 "type"]) => "function"
      (get-in wire ["messages" 2 "tool_calls" 0 "function" "name"]) => "get_weather"
      "tool_call arguments are JSON-encoded strings"
      (json/parse-string
        (get-in wire ["messages" 2 "tool_calls" 0 "function" "arguments"]))
      => {"location" "Paris"}
      "each tool_result fans out into its own role:tool message"
      (get-in wire ["messages" 3 "role"]) => "tool"
      (get-in wire ["messages" 3 "tool_call_id"]) => "call_1"
      (get-in wire ["messages" 3 "content"]) => "{\"temp\":15}"
      (get-in wire ["messages" 4 "role"]) => "tool"
      (get-in wire ["messages" 4 "tool_call_id"]) => "call_1b"
      "is_error is surfaced inline since OpenAI has no flag for it"
      (.startsWith ^String (get-in wire ["messages" 4 "content"]) "[error]") => true
      "later plain user message still translates to a text content-block"
      (get-in wire ["messages" 5 "role"]) => "user"
      (get-in wire ["messages" 5 "content"]) => [{"type" "text" "text" "Tell me a joke"}]
      "tools become OpenAI function-tools with no cache_control"
      (get-in wire ["tools" 0 "type"]) => "function"
      (get-in wire ["tools" 0 "function" "name"]) => "get_weather"
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
   "usage"   {"prompt_tokens"         12
              "completion_tokens"     8
              "prompt_tokens_details" {"cached_tokens" 100}}})

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
      (get-in resp [:content 1 :id]) => "call_1"
      (get-in resp [:content 1 :name]) => "get_weather"
      "tool_use :input is keyword-keyed (parity with Anthropic backend)"
      (get-in resp [:content 1 :input]) => {:location "Paris"}
      "usage maps to our shape; cached_tokens -> :cache-read-input-tokens"
      (get-in resp [:usage :input-tokens]) => 12
      (get-in resp [:usage :output-tokens]) => 8
      (get-in resp [:usage :cache-read-input-tokens]) => 100
      "model echoed and backend tagged"
      (:model resp) => "gpt-5"
      (get-in resp [:backend-metadata :backend]) => :openai
      (get-in resp [:backend-metadata :message-id]) => "chatcmpl-abc")))

(specification "finish_reason mapping"
  (let [resp (fn [fr]
               (oai/openai-json->response
                 {"model"   "gpt-5"
                  "choices" [{"finish_reason" fr
                              "message"       {"role" "assistant" "content" "x"}}]
                  "usage"   {}}
                 "gpt-5"))]
    (assertions
      "stop -> :end_turn" (:stop-reason (resp "stop")) => :end_turn
      "length -> :max_tokens" (:stop-reason (resp "length")) => :max_tokens
      "tool_calls -> :tool_use" (:stop-reason (resp "tool_calls")) => :tool_use
      "content_filter -> :refusal" (:stop-reason (resp "content_filter")) => :refusal)))

(specification "pure-tool-call response (no text content) is valid"
  ;; Some turns return null content with only tool_calls.
  (let [parsed {"model"   "gpt-5"
                "choices" [{"finish_reason" "tool_calls"
                            "message"       {"role"       "assistant"
                                             "content"    nil
                                             "tool_calls" [{"id"       "c1" "type" "function"
                                                            "function" {"name"      "t"
                                                                        "arguments" "{}"}}]}}]
                "usage"   {}}
        resp   (oai/openai-json->response parsed "gpt-5")]
    (assertions
      "response is Malli-valid"
      (types/validate-response resp) => nil
      "only a single tool_use block"
      (count (:content resp)) => 1
      (get-in resp [:content 0 :type]) => :tool_use)))

;;; ---------------------------------------------------------------------------
;;; Streaming (SSE)

(defn- sse
  "Render a vector of Chat Completions chunk maps into an OpenAI-style
   `data:`/`[DONE]` SSE stream (blank lines and a comment line interleaved to
   exercise the parser's non-data line handling)."
  [chunks]
  (->> (concat
         (mapcat (fn [c] [(str "data: " (json/generate-string c)) ""]) chunks)
         [": keep-alive comment" "data: [DONE]" ""])
    (str/join "\n")))

;; A representative stream: id/model on the first chunk, two content deltas,
;; an indexed tool_call assembled from name + two argument fragments, a finish
;; chunk, then a usage-only chunk (stream_options.include_usage).
(def streamed-chunks
  [{"id"      "chatcmpl-s1" "model" "gpt-5"
    "choices" [{"index" 0 "delta" {"role" "assistant" "content" ""}}]}
   {"choices" [{"index" 0 "delta" {"content" "Hel"}}]}
   {"choices" [{"index" 0 "delta" {"content" "lo"}}]}
   {"choices" [{"index" 0
                "delta" {"tool_calls" [{"index"    0 "id" "call_1"
                                        "type"     "function"
                                        "function" {"name"      "get_weather"
                                                    "arguments" "{\"loc"}}]}}]}
   {"choices" [{"index" 0
                "delta" {"tool_calls" [{"index"    0
                                        "function" {"arguments" "ation\":\"Paris\"}"}}]}}]}
   {"choices" [{"index" 0 "delta" {} "finish_reason" "tool_calls"}]}
   {"choices" []
    "usage"   {"prompt_tokens"         12 "completion_tokens" 8
               "prompt_tokens_details" {"cached_tokens" 100}}}])

(specification "parse-openai-sse! reconstructs a Response and emits deltas"
  (let [deltas (atom [])
        reader (BufferedReader. (StringReader. (sse streamed-chunks)))
        resp   (oai/parse-openai-sse! reader "fallback-model"
                 #(swap! deltas conj %))]
    (assertions
      "final Response is Malli-valid"
      (types/validate-response resp) => nil
      "text deltas were streamed in order"
      (mapv :text (filter #(= :text-delta (:type %)) @deltas))
      => ["Hel" "lo"]
      "all surfaced deltas are :text-delta (no tool-arg leakage)"
      (every? #(= :text-delta (:type %)) @deltas) => true
      "accumulated text block then tool_use block"
      (count (:content resp)) => 2
      (get-in resp [:content 0]) => {:type :text :text "Hello"}
      (get-in resp [:content 1 :type]) => :tool_use
      (get-in resp [:content 1 :id]) => "call_1"
      (get-in resp [:content 1 :name]) => "get_weather"
      "tool args reassembled from fragments + keywordized"
      (get-in resp [:content 1 :input]) => {:location "Paris"}
      "finish_reason -> stop-reason"
      (:stop-reason resp) => :tool_use
      "usage from the include_usage final chunk"
      (get-in resp [:usage :input-tokens]) => 12
      (get-in resp [:usage :output-tokens]) => 8
      (get-in resp [:usage :cache-read-input-tokens]) => 100
      "model + message-id taken from the stream"
      (:model resp) => "gpt-5"
      (get-in resp [:backend-metadata :message-id]) => "chatcmpl-s1")))

(specification "streamed Response == buffered translator output (equivalence)"
  ;; The fully-assembled equivalent buffered OpenAI body run through the SAME
  ;; openai-json->response translator must produce an identical Response.
  (let [reader   (BufferedReader. (StringReader. (sse streamed-chunks)))
        streamed (oai/parse-openai-sse! reader "gpt-5" nil)
        buffered (oai/openai-json->response
                   {"id"      "chatcmpl-s1"
                    "model"   "gpt-5"
                    "choices" [{"index"         0
                                "finish_reason" "tool_calls"
                                "message"
                                {"role"    "assistant"
                                 "content" "Hello"
                                 "tool_calls"
                                 [{"index"    0
                                   "id"       "call_1"
                                   "type"     "function"
                                   "function" {"name" "get_weather"
                                               "arguments"
                                               "{\"location\":\"Paris\"}"}}]}}]
                    "usage"   {"prompt_tokens"         12
                               "completion_tokens"     8
                               "prompt_tokens_details" {"cached_tokens" 100}}}
                   "gpt-5")]
    (assertions
      "blocks + usage + metadata structurally identical"
      streamed => buffered)))

(specification "OpenAI backend advertises streaming capability"
  (let [b (oai/new-backend {:api-key "k" :base-url "http://x/v1"})]
    (assertions
      "(streaming? openai) => true"
      (proto/streaming? b) => true)))

(specification "stream-turn drives the SSE seam and returns the final Response"
  ;; Stub the HTTP layer (same babashka.http-client/post seam the buffered
  ;; path uses) so no network call is made; stream-turn must emit deltas and
  ;; return the finalized Response.
  (let [deltas (atom [])
        b      (oai/new-backend {:api-key "k" :base-url "http://x/v1"})
        req    {:model      "gpt-5"
                :messages   [{:role    :user
                              :content [{:type :text :text "hi"}]}]
                :max-tokens 64}]
    (with-redefs
      [babashka.http-client/post
       (fn [_url _opts]
         {:status 200
          :body   (java.io.ByteArrayInputStream.
                    (.getBytes ^String (sse streamed-chunks) "UTF-8"))})]
      (let [resp (p/await! (proto/stream-turn b req #(swap! deltas conj %)))]
        (assertions
          "deltas emitted during the stream"
          (mapv :text (filter #(= :text-delta (:type %)) @deltas))
          => ["Hel" "lo"]
          "final Response is the finalized translator output"
          (types/validate-response resp) => nil
          (get-in resp [:content 0]) => {:type :text :text "Hello"}
          (:stop-reason resp) => :tool_use
          (get-in resp [:usage :input-tokens]) => 12)
        (assertions
          "send-turn* routes to streaming when on-delta present"
          (let [d2 (atom [])]
            (p/await! (proto/send-turn* b req #(swap! d2 conj %)))
            (count @d2)) => 2)))))



(specification "status->category — error categorization parity with Anthropic backend"
  (let [c (fn [s b] (#'oai/status->category s b))]
    (assertions
      "429 -> rate-limited"        (c 429 "")                                         => :rate-limited
      "529 -> overloaded"          (c 529 "")                                         => :overloaded
      "503 -> overloaded"          (c 503 "")                                         => :overloaded
      "body says overloaded"       (c 500 "server is Overloaded right now")           => :overloaded
      "401 -> auth"                (c 401 "")                                         => :auth
      "403 -> auth"                (c 403 "")                                         => :auth
      "400 + ctx phrase -> context-length"
      (c 400 "Your prompt is too long for the model")                                 => :context-length
      "422 + ctx phrase -> context-length"
      (c 422 "context window exceeded")                                               => :context-length
      "400 plain -> invalid-request" (c 400 "bad json")                               => :invalid-request
      "422 plain -> invalid-request" (c 422 "unprocessable")                          => :invalid-request
      "500 generic -> transport"   (c 500 "")                                         => :transport)))

(specification "retry-after-ms — Retry-After header parsing"
  (let [r (fn [h] (#'oai/retry-after-ms h))]
    (assertions
      "lowercase header, integer seconds" (r {"retry-after" "12"})                    => 12000
      "canonical-case header"             (r {"Retry-After" "30"})                    => 30000
      "whitespace tolerated"              (r {"retry-after" "  5 "})                  => 5000
      "absent header -> nil"              (r {})                                      => nil
      "unparseable -> nil"                (r {"retry-after" "Wed, 21 Oct 2026 07:28:00 GMT"}) => nil)))
