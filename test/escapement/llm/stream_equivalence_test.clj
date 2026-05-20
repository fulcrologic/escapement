(ns escapement.llm.stream-equivalence-test
  "Executable replacement for the prose-only equivalence claim that used to live
   at `escapement.llm.api` lines 195-199 (\"a streamed turn yields a
   byte-identical Response to a buffered one\").

   For ONE canonical conversation per backend we build two inputs that are
   logically the same turn:
     - a buffered raw API payload, run through the production
       JSON->Response translator (`anthropic-json->response` /
       `openai-json->response`), and
     - the equivalent SSE event sequence, chunked and driven through the
       production streaming finalizer (`parse-anthropic-sse!` /
       `parse-openai-sse!`).
   The two finalized `Response` maps must be structurally `=` (Clojure value
   equality of `:content` / `:usage` / `:stop-reason` / `:model`), covering the
   historically-drifting points: usage fully folded (not partial), block order
   by index, fragmented `tool_use` / tool-call argument deltas reassembled
   identically, and thinking/reasoning blocks.

   Metadata note: the OpenAI streaming path reuses the buffered translator
   verbatim, so `:backend-metadata` is `=` too (asserted in full). The
   Anthropic streaming path intentionally tags
   `{:backend :api :streamed true}` in `stream-acc-finalize` (provenance
   marker) whereas the buffered path carries `{:backend :api :message-id ...
   :role ...}`; both share `:backend :api`. The equivalence contract is
   \"blocks + usage\" (per task-003 / plan.md Task 3), so for Anthropic we
   assert `=` on the content/usage/stop-reason/model payload and assert the
   shared `:backend`, while documenting the deliberate provenance-tag
   divergence rather than papering over it."
  (:require
    [cheshire.core :as json]
    [clojure.data :as data]
    [clojure.string :as str]
    [escapement.llm.api :as api]
    [escapement.llm.openai :as oai]
    [escapement.llm.types :as types]
    [fulcro-spec.core :refer [=> assertions specification]])
  (:import (java.io BufferedReader StringReader)))

;;; ---------------------------------------------------------------------------
;;; Helpers

(defn- payload-of
  "The equivalence contract is the response *payload*: content blocks, folded
   usage, stop-reason and model. `:backend-metadata` differs by design between
   the two Anthropic paths (streamed provenance tag) and is asserted
   separately; OpenAI metadata is asserted in full via `=` on the raw maps."
  [resp]
  (select-keys resp [:content :usage :stop-reason :model]))

(defn- anthropic-sse
  "Render Anthropic SSE event maps into a `data:`/`event:` stream."
  [evs]
  (->> evs
    (mapcat (fn [m] [(str "event: " (get m "type"))
                     (str "data: " (json/generate-string m))
                     ""]))
    (str/join "\n")))

(defn- openai-sse
  "Render OpenAI Chat Completions chunk maps into a `data:`/`[DONE]` stream
   (blank + comment lines interleaved to exercise non-data handling)."
  [chunks]
  (->> (concat
         (mapcat (fn [c] [(str "data: " (json/generate-string c)) ""]) chunks)
         [": keep-alive comment" "data: [DONE]" ""])
    (str/join "\n")))

;;; ---------------------------------------------------------------------------
;;; Anthropic — one canonical conversation, two representations
;;;
;;; Turn: a text block, a `thinking` block (with signature), and a `tool_use`
;;; block whose JSON arguments arrive fragmented across SSE chunks; usage folds
;;; from message_start (input) + message_delta (output); stop_reason = tool_use.

(def anthropic-streamed-events
  [{"type"    "message_start"
    "message" {"id"    "msg_eq1"
               "role"  "assistant"
               "model" "claude-canon"
               "usage" {"input_tokens" 11 "cache_read_input_tokens" 3}}}
   {"type"          "content_block_start" "index" 0
    "content_block" {"type" "text" "text" ""}}
   {"type"  "content_block_delta" "index" 0
    "delta" {"type" "text_delta" "text" "Hel"}}
   {"type"  "content_block_delta" "index" 0
    "delta" {"type" "text_delta" "text" "lo"}}
   {"type" "content_block_stop" "index" 0}
   {"type"          "content_block_start" "index" 1
    "content_block" {"type" "thinking" "thinking" ""}}
   {"type"  "content_block_delta" "index" 1
    "delta" {"type" "thinking_delta" "thinking" "ponder"}}
   {"type"  "content_block_delta" "index" 1
    "delta" {"type" "thinking_delta" "thinking" "ing"}}
   {"type"  "content_block_delta" "index" 1
    "delta" {"type" "signature_delta" "signature" "sig-abc"}}
   {"type" "content_block_stop" "index" 1}
   {"type"          "content_block_start" "index" 2
    "content_block" {"type" "tool_use" "id" "t1" "name" "do_it" "input" {}}}
   {"type"  "content_block_delta" "index" 2
    "delta" {"type" "input_json_delta" "partial_json" "{\"loc"}}
   {"type"  "content_block_delta" "index" 2
    "delta" {"type" "input_json_delta" "partial_json" "ation\":\"Paris\"}"}}
   {"type" "content_block_stop" "index" 2}
   {"type"  "message_delta"
    "delta" {"stop_reason" "tool_use"}
    "usage" {"output_tokens" 7}}
   {"type" "message_stop"}])

(def anthropic-buffered-payload
  "The same turn as a single buffered Messages API response body."
  {"id"          "msg_eq1"
   "role"        "assistant"
   "model"       "claude-canon"
   "stop_reason" "tool_use"
   "content"     [{"type" "text" "text" "Hello"}
                  {"type"      "thinking" "thinking" "pondering"
                   "signature" "sig-abc"}
                  {"type"  "tool_use" "id" "t1" "name" "do_it"
                   "input" {"location" "Paris"}}]
   "usage"       {"input_tokens"  11 "cache_read_input_tokens" 3
                  "output_tokens" 7}})

(specification "Anthropic: finalized streamed Response == buffered Response"
  (let [reader   (BufferedReader.
                   (StringReader.
                     (anthropic-sse anthropic-streamed-events)))
        streamed (api/parse-anthropic-sse! reader "fallback" nil)
        buffered (api/anthropic-json->response
                   anthropic-buffered-payload "fallback")
        [only-s only-b _] (data/diff (payload-of streamed)
                            (payload-of buffered))]
    (assertions
      "both finalized Responses are Malli-valid"
      (types/validate-response streamed) => nil
      (types/validate-response buffered) => nil
      "blocks + usage + stop-reason + model are structurally ="
      (payload-of streamed) => (payload-of buffered)
      "no streamed-only payload divergence"
      only-s => nil
      "no buffered-only payload divergence"
      only-b => nil
      "content blocks reassembled in index order, fragments joined"
      (:content streamed)
      => [{:type :text :text "Hello"}
          {:type      :thinking :thinking "pondering"
           :signature "sig-abc"}
          {:type  :tool_use :id "t1" :name "do_it"
           :input {:location "Paris"}}]
      "usage fully folded (input from start, output from delta)"
      (:usage streamed)
      => {:input-tokens            11 :output-tokens 7
          :cache-read-input-tokens 3}
      "both paths agree on :backend; streamed adds provenance tag"
      (get-in streamed [:backend-metadata :backend]) => :api
      (get-in buffered [:backend-metadata :backend]) => :api
      (get-in streamed [:backend-metadata :streamed]) => true)))

;;; ---------------------------------------------------------------------------
;;; OpenAI — one canonical conversation, two representations
;;;
;;; Turn: a text reply + a tool_call whose JSON arguments arrive fragmented;
;;; usage on the final include_usage chunk; finish_reason = tool_calls.
;;; The streaming path reuses `openai-json->response` verbatim, so the whole
;;; Response (including :backend-metadata) must be `=`.

(def openai-streamed-chunks
  [{"id"      "chatcmpl-eq1" "model" "gpt-canon"
    "choices" [{"index" 0 "delta" {"role" "assistant" "content" ""}}]}
   {"choices" [{"index" 0 "delta" {"content" "Hel"}}]}
   {"choices" [{"index" 0 "delta" {"content" "lo"}}]}
   {"choices" [{"index" 0
                "delta" {"tool_calls"
                         [{"index"    0 "id" "call_1" "type" "function"
                           "function" {"name"      "get_weather"
                                       "arguments" "{\"loc"}}]}}]}
   {"choices" [{"index" 0
                "delta" {"tool_calls"
                         [{"index"    0
                           "function" {"arguments" "ation\":\"Paris\"}"}}]}}]}
   {"choices" [{"index" 0 "delta" {} "finish_reason" "tool_calls"}]}
   {"choices" []
    "usage"   {"prompt_tokens"         12 "completion_tokens" 8
               "prompt_tokens_details" {"cached_tokens" 100}}}])

(def openai-buffered-payload
  {"id"      "chatcmpl-eq1"
   "model"   "gpt-canon"
   "choices" [{"index"         0
               "finish_reason" "tool_calls"
               "message"
               {"role"    "assistant"
                "content" "Hello"
                "tool_calls"
                [{"index"    0
                  "id"       "call_1"
                  "type"     "function"
                  "function" {"name"      "get_weather"
                              "arguments" "{\"location\":\"Paris\"}"}}]}}]
   "usage"   {"prompt_tokens"         12
              "completion_tokens"     8
              "prompt_tokens_details" {"cached_tokens" 100}}})

(specification "OpenAI: finalized streamed Response == buffered Response"
  (let [reader   (BufferedReader.
                   (StringReader.
                     (openai-sse openai-streamed-chunks)))
        streamed (oai/parse-openai-sse! reader "fallback" nil)
        buffered (oai/openai-json->response
                   openai-buffered-payload "fallback")
        [only-s only-b _] (data/diff streamed buffered)]
    (assertions
      "both finalized Responses are Malli-valid"
      (types/validate-response streamed) => nil
      (types/validate-response buffered) => nil
      "the WHOLE Response (incl. :backend-metadata) is ="
      streamed => buffered
      "no streamed-only divergence"
      only-s => nil
      "no buffered-only divergence"
      only-b => nil
      "tool args reassembled from fragments + keywordized"
      (get-in streamed [:content 1 :input]) => {:location "Paris"}
      "usage fully folded from the include_usage chunk"
      (:usage streamed)
      => {:input-tokens            12 :output-tokens 8
          :cache-read-input-tokens 100}
      "stop/finish-reason mapped identically"
      (:stop-reason streamed) => (:stop-reason buffered)
      (:stop-reason streamed) => :tool_use)))
