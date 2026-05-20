(ns escapement.llm.openai-codex.http-test
  "Pure SSE parsing tests using canned event streams over StringReader."
  (:require
    [escapement.llm.openai-codex.http :as http]
    [fulcro-spec.core :refer [=> assertions component specification]])
  (:import
    (java.io BufferedReader StringReader)))

;;; ---------------------------------------------------------------------------
;;; Helpers

(defn- make-reader
  "Wraps a SSE string in a BufferedReader for parse-sse-stream!."
  [s]
  (BufferedReader. (StringReader. s)))

(defn- call-parse
  "Calls the private parse-sse-stream! fn via reflection (it's internal but we need to test it)."
  [s]
  (#'http/parse-sse-stream! (make-reader s)))

;;; ---------------------------------------------------------------------------
;;; Canned SSE streams

(def sse-text-message
  "data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hello\"}]}}\n\ndata: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hello world\"}]}}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"model\":\"gpt-5.1-codex\",\"usage\":{\"input_tokens\":10,\"output_tokens\":3}}}\n\ndata: [DONE]\n\n")

(def sse-function-call
  "data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"type\":\"function_call\",\"call_id\":\"call_abc\",\"name\":\"my_tool\",\"arguments\":\"\"}}\n\ndata: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"function_call\",\"call_id\":\"call_abc\",\"name\":\"my_tool\",\"arguments\":\"{\\\"key\\\":\\\"val\\\"}\"}}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"model\":\"gpt-5.1-codex\",\"usage\":{\"input_tokens\":5,\"output_tokens\":2}}}\n\ndata: [DONE]\n\n")

(def sse-with-deltas
  "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hel\"}\n\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"lo\"}\n\ndata: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hello\"}]}}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"model\":\"gpt-5.1\",\"usage\":{\"input_tokens\":2,\"output_tokens\":1}}}\n\n")

(def sse-error-event
  "data: {\"type\":\"error\",\"code\":\"server_error\",\"message\":\"Something went wrong\"}\n\n")

(def sse-incomplete-max-tokens
  "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Truncated\"}]}}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"model\":\"gpt-5.1-codex\",\"usage\":{\"input_tokens\":5,\"output_tokens\":10}}}\n\n")

;;; ---------------------------------------------------------------------------
;;; Tests

(specification "parse-sse-stream! — text message"
  (let [result (call-parse sse-text-message)]
    (assertions
      "output_item.done supersedes output_item.added for the same index"
      (get-in result [:items 0 :content 0 :text]) => "Hello world"
      "item type is preserved"
      (get-in result [:items 0 :type]) => "message"
      "model is extracted from response.completed"
      (:model result) => "gpt-5.1-codex"
      "input_tokens extracted from usage"
      (get-in result [:usage :input_tokens]) => 10
      "output_tokens extracted from usage"
      (get-in result [:usage :output_tokens]) => 3
      "stop-reason is :end_turn for completed with no function_calls"
      (:stop-reason result) => :end_turn)))

(specification "parse-sse-stream! — function_call item"
  (let [result (call-parse sse-function-call)]
    (assertions
      "item type is function_call"
      (get-in result [:items 0 :type]) => "function_call"
      "call_id is preserved"
      (get-in result [:items 0 :call_id]) => "call_abc"
      "name is preserved"
      (get-in result [:items 0 :name]) => "my_tool"
      "arguments from done event are used"
      (get-in result [:items 0 :arguments]) => "{\"key\":\"val\"}"
      "stop-reason is :tool_use when function_call items present"
      (:stop-reason result) => :tool_use)))

(specification "parse-sse-stream! — delta events are ignored"
  (let [result (call-parse sse-with-deltas)]
    (assertions
      "only final item is present (delta events produce no items)"
      (count (:items result)) => 1
      "final item text comes from output_item.done not deltas"
      (get-in result [:items 0 :content 0 :text]) => "Hello")))

(specification "parse-sse-stream! — error event"
  (component "when an error event is present in the stream"
    (assertions
      "throws ex-info"
      (try (call-parse sse-error-event) nil
           (catch clojure.lang.ExceptionInfo e
             (:type (:error-payload (ex-data e))))) => "error")))

(specification "parse-sse-stream! — incomplete / max_output_tokens"
  (let [result (call-parse sse-incomplete-max-tokens)]
    (assertions
      "stop-reason is :max_tokens for incomplete + max_output_tokens reason"
      (:stop-reason result) => :max_tokens
      "still returns the partial item"
      (count (:items result)) => 1)))
