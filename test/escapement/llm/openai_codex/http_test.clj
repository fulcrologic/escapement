(ns escapement.llm.openai-codex.http-test
  "Pure SSE parsing tests using canned event streams over StringReader."
  (:require
    [clojure.string :as str]
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

(specification "SSE reader framing"
  (component "BOM, comments, unknown fields and every newline convention"
    (doseq [newline    ["\n" "\r\n" "\r"]
            end-event? [false true]]
      (let [lines  (concat ["﻿: initial BOM and comment" "event: response.completed"
                            "data:{\"type\":\"response.completed\","
                            ": keep-alive" "unknown" "id: 123"
                            "data" "data:"
                            "data: \"response\":{\"status\":\"completed\","
                            "data:  \"model\":\"test-model\",\"output\":[],\"usage\":{\"input_tokens\":7}}}"]
                     (when end-event? ["" "" "data:[DONE]" "" ""]))
            result (call-parse (str/join newline lines))]
        (assertions
          "every newline convention, with and without a trailing [DONE], reaches a terminal"
          (:stop-reason result) => :end_turn
          "model survives the split data fields"
          (:model result) => "test-model"
          "usage survives the split data fields"
          (get-in result [:usage :input_tokens]) => 7)))))

(specification "SSE events dispatch only at event boundaries"
  (let [acc    (atom (#'http/new-stream-acc))
        deltas (atom [])
        line!  #(#'http/process-sse-line! acc % (fn [delta] (swap! deltas conj delta)))]
    (line! "data:  {\"type\":\"response.output_text.delta\",")
    (line! ": comment")
    (line! "event: ignored")
    (line! " \r")
    (assertions
      "exactly one optional space is removed; whitespace-only lines are not boundaries"
      (:data-lines @acc) => [" {\"type\":\"response.output_text.delta\","])
    (line! "data:\"delta\":\" hello \"}")
    (assertions
      "no callback before the blank line"
      @deltas => [])
    (line! "\r")
    (assertions
      "the blank line dispatches the buffered event"
      @deltas => [{:type :text-delta :text " hello "}]
      "and releases the buffer"
      (:data-lines @acc) => [])
    (line! "")
    (line! "data:[DONE]")
    (line! "")
    (assertions
      "blank events and [DONE] do not duplicate callbacks"
      (count @deltas) => 1)))

(specification "the SSE event-size ceiling latches and releases the buffer"
  (doseq [lines [["data:abcdef"]
                 ["data:ab" "data:cd" "data:e"]
                 (repeat 7 "data:")]]
    (let [acc (atom (#'http/new-stream-acc 6))]
      (doseq [line lines] (#'http/process-sse-line! acc line))
      (let [error (:stream-error @acc)]
        (assertions
          "overflow latches a :sse-event-too-large failure"
          (:reason (ex-data error)) => :sse-event-too-large
          "and clears the buffered data"
          (:data-lines @acc) => []
          (:data-chars @acc) => 0)
        (doseq [_ (range 1000)] (#'http/process-sse-line! acc "data:ignored"))
        (#'http/process-sse-line! acc "")
        (assertions
          "later lines cannot replace the latched failure"
          (identical? error (:stream-error @acc)) => true
          "later lines are not buffered"
          (:data-lines @acc) => []
          "and cannot complete the response"
          (:completed-resp @acc) => nil
          "finalization rethrows the latched failure"
          (try (#'http/finalize-stream-acc @acc) nil (catch Throwable e e)) => error)))))

(specification "the SSE event-size ceiling is per event and inclusive"
  (let [data   "{\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}"
        limit  (inc (count data))
        acc    (atom (#'http/new-stream-acc limit))
        deltas (atom [])]
    (dotimes [_ 3]
      (#'http/process-sse-line! acc (str "data:" data) #(swap! deltas conj %))
      (assertions
        "a data field exactly at the limit is accepted"
        (:data-chars @acc) => limit)
      (#'http/process-sse-line! acc "" #(swap! deltas conj %))
      (assertions
        "the boundary resets the per-event count"
        (:data-chars @acc) => 0))
    (assertions
      "every event was delivered"
      @deltas => (vec (repeat 3 {:type :text-delta :text "hi"}))
      "and none tripped the ceiling"
      (:stream-error @acc) => nil))
  (assertions
    "the default ceiling is 8 MiB"
    (:max-sse-event-chars (#'http/new-stream-acc)) => (* 8 1024 1024))
  (doseq [limit [0 -1 nil "64"]]
    (assertions
      "a non-positive-integer limit is rejected at construction"
      (some? (try (#'http/new-stream-acc limit) nil (catch Exception e e))) => true)))

(specification "error-category weighs body evidence of a context overflow above a 5xx status"
  (let [category #(#'http/error-category %1 %2)]
    (assertions
      "a proxy reporting an upstream context overflow as a 500 is NOT retryable"
      (category 500 "{\"error\":{\"code\":\"context_length_exceeded\"}}") => :context-length
      "a 500 with no such evidence stays retryable"
      (category 500 "{\"error\":{\"message\":\"internal\"}}") => :overloaded
      "the status-named categories still win outright"
      (category 401 "context length exceeded") => :auth
      (category 429 "context window") => :rate-limited
      (category 408 "") => :timeout
      "a status-less SSE error payload is still classified from its body"
      (category nil "{\"code\":\"server_error\"}") => :overloaded
      (category nil "{\"code\":\"context_length_exceeded\"}") => :context-length
      "and an unrecognised failure is an invalid request"
      (category 400 "{\"error\":{\"message\":\"bad tool schema\"}}") => :invalid-request)))

(specification "an empty terminal replays only done-confirmed output items"
  (component "an item seen solely via output_item.added carries partial content"
    (let [stream (str "data: {\"type\":\"response.output_item.done\",\"output_index\":0,"
                   "\"item\":{\"type\":\"message\",\"role\":\"assistant\","
                   "\"content\":[{\"type\":\"output_text\",\"text\":\"done text\"}]}}\n\n"
                   "data: {\"type\":\"response.output_item.added\",\"output_index\":1,"
                   "\"item\":{\"type\":\"function_call\",\"call_id\":\"call_1\","
                   "\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\"}}\n\n"
                   "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\","
                   "\"model\":\"gpt-5.1\",\"output\":[],\"usage\":{\"input_tokens\":1}}}\n\n")
          result (call-parse stream)]
      (assertions
        "the confirmed item survives the empty terminal"
        (mapv :type (:items result)) => ["message"]
        (get-in result [:items 0 :content 0 :text]) => "done text"
        "the half-built function_call is dropped rather than handed on with fragment arguments"
        (some #(= "function_call" (:type %)) (:items result)) => nil
        "so the turn does not claim a tool call"
        (:stop-reason result) => :end_turn))))
