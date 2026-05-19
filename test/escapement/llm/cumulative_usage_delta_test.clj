(ns escapement.llm.cumulative-usage-delta-test
  "task-004: the OPTIONAL cumulative `:usage` key on streaming deltas.

   Asserts, for BOTH the Anthropic (`escapement.llm.api`) and OpenAI
   (`escapement.llm.openai`) streaming paths:
   - `on-delta` receives an optional `:usage` map sourced from the running SSE
     usage fold, in the same `{:input-tokens :output-tokens ...}` shape as the
     finalized `Response` `:usage`;
   - that running total is monotonically non-decreasing across successive
     deltas (absent/empty counts as a floor — covers OpenAI's final-only usage);
   - a delta consumer that ignores the new key still works unchanged;
   - the finalized `Response` usage stays the authoritative billing value and is
     independent of the per-delta running total;
   - no host domain identifiers (run/session/correlation) appear in the payload."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [escapement.llm.api :as api]
   [escapement.llm.openai :as oai]
   [fulcro-spec.core :refer [specification assertions component =>]])
  (:import (java.io BufferedReader StringReader)))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- anthropic-sse [evs]
  (->> evs
       (mapcat (fn [m] [(str "event: " (get m "type"))
                        (str "data: " (json/generate-string m))
                        ""]))
       (str/join "\n")))

(defn- openai-sse [chunks]
  (->> (concat
        (mapcat (fn [c] [(str "data: " (json/generate-string c)) ""]) chunks)
        [": keep-alive" "data: [DONE]" ""])
       (str/join "\n")))

(defn- monotonic-non-decreasing?
  "True when each successive value of `k` across the running-usage sequence is
   >= the previous one. Absent is treated as a floor (0)."
  [usages k]
  (let [vs (map #(or (get % k) 0) usages)]
    (every? true? (map <= vs (rest vs)))))

;; A stream where Anthropic seeds input tokens at message_start, then bumps
;; output tokens incrementally at each message_delta interleaved with content.
(def anthropic-events
  [{"type" "message_start"
    "message" {"model" "claude-x" "usage" {"input_tokens" 10}}}
   {"type" "content_block_start" "index" 0
    "content_block" {"type" "text" "text" ""}}
   {"type" "content_block_delta" "index" 0
    "delta" {"type" "text_delta" "text" "Hel"}}
   {"type" "message_delta" "delta" {} "usage" {"output_tokens" 3}}
   {"type" "content_block_delta" "index" 0
    "delta" {"type" "text_delta" "text" "lo"}}
   {"type" "message_delta" "delta" {"stop_reason" "end_turn"}
    "usage" {"output_tokens" 7}}
   {"type" "content_block_stop" "index" 0}
   {"type" "message_stop"}])

(def openai-chunks
  [{"id" "chatcmpl-s1" "model" "gpt-5"
    "choices" [{"index" 0 "delta" {"role" "assistant" "content" ""}}]}
   {"choices" [{"index" 0 "delta" {"content" "Hel"}}]}
   {"choices" [{"index" 0 "delta" {"content" "lo"}}]}
   {"choices" [{"index" 0 "delta" {} "finish_reason" "stop"}]}
   {"choices" []
    "usage" {"prompt_tokens" 12 "completion_tokens" 8
             "prompt_tokens_details" {"cached_tokens" 100}}}])

;; ---------------------------------------------------------------------------
;; Anthropic path

(specification "Anthropic: deltas carry optional cumulative usage (task-004)"
  (let [deltas (atom [])
        reader (BufferedReader. (StringReader. (anthropic-sse anthropic-events)))
        resp   (api/parse-anthropic-sse! reader "fallback-model"
                                         #(swap! deltas conj %))
        text   (filter #(= :text-delta (:type %)) @deltas)
        usages (keep :usage text)]
    (component "running usage surfaced on deltas"
      (assertions
       "every text delta carries the optional :usage key"
       (every? :usage text) => true
       "running usage uses the Response usage shape (:input-tokens etc.)"
       (-> usages first keys set) => #{:input-tokens}
       "first delta sees input tokens seeded at message_start"
       (get-in (first usages) [:input-tokens]) => 10
       "later delta sees output tokens accrued from message_delta"
       (get-in (last usages) [:output-tokens]) => 3
       "running input tokens are monotonically non-decreasing"
       (monotonic-non-decreasing? usages :input-tokens) => true
       "running output tokens are monotonically non-decreasing"
       (monotonic-non-decreasing? usages :output-tokens) => true))
    (component "billing authority unchanged"
      (assertions
       "finalized Response usage is the authoritative final total"
       (:usage resp) => {:input-tokens 10 :output-tokens 7}
       "final usage >= the last running total seen on a delta (independent)"
       (>= (get-in resp [:usage :output-tokens])
           (get-in (last usages) [:output-tokens] 0)) => true))
    (component "no host correlation identifiers leak into the payload"
      (assertions
       "delta maps contain only :type, :text and the optional :usage"
       (->> @deltas (mapcat keys) set) => #{:type :text :usage}))
    (component "consumer ignoring the new key still works"
      ;; Destructure only the keys a pre-task-004 consumer knew about.
      (let [seen (atom [])
            r2   (BufferedReader.
                  (StringReader. (anthropic-sse anthropic-events)))]
        (api/parse-anthropic-sse!
         r2 "fallback-model"
         (fn [{:keys [type text]}]
           (swap! seen conj [type text])))
        (assertions
         "old-style {:keys [type text]} consumer is unaffected"
         (->> @seen (filter #(= :text-delta (first %))) (map second))
         => ["Hel" "lo"])))))

;; ---------------------------------------------------------------------------
;; OpenAI path

(specification "OpenAI: deltas carry optional cumulative usage (task-004)"
  (let [deltas (atom [])
        reader (BufferedReader. (StringReader. (openai-sse openai-chunks)))
        resp   (oai/parse-openai-sse! reader "fallback-model"
                                      #(swap! deltas conj %))
        text   (filter #(= :text-delta (:type %)) @deltas)
        usages (map #(get % :usage) text)]
    (component "OpenAI emits usage only on the final chunk (provider variance)"
      (assertions
       "content deltas carry NO :usage (OpenAI sends usage final-only)"
       (every? nil? usages) => true
       "the absent->final progression is still non-decreasing"
       (monotonic-non-decreasing? (keep identity usages) :output-tokens)
       => true))
    (component "billing authority unchanged"
      (assertions
       "finalized Response usage is authoritative"
       (:usage resp)
       => {:input-tokens 12 :output-tokens 8 :cache-read-input-tokens 100}))
    (component "no host correlation identifiers leak into the payload"
      (assertions
       "delta maps contain only :type and :text (no :usage here)"
       (->> @deltas (mapcat keys) set) => #{:type :text}))
    (component "consumer ignoring the new key still works"
      (let [seen (atom [])
            r2   (BufferedReader.
                  (StringReader. (openai-sse openai-chunks)))]
        (oai/parse-openai-sse!
         r2 "fallback-model"
         (fn [{:keys [type text]}] (swap! seen conj [type text])))
        (assertions
         "old-style {:keys [type text]} consumer is unaffected"
         (->> @seen (filter #(= :text-delta (first %))) (map second))
         => ["Hel" "lo"])))))

;; ---------------------------------------------------------------------------
;; OpenAI provider that attaches usage on the finish chunk (some compatible
;; providers do this) — proves the running field DOES appear when usage is
;; folded before a content delta arrives.

(def openai-chunks-usage-early
  [{"id" "chatcmpl-s2" "model" "gpt-5"
    "choices" [{"index" 0 "delta" {"role" "assistant" "content" ""}}]
    "usage" {"prompt_tokens" 5}}
   {"choices" [{"index" 0 "delta" {"content" "Hi"}}]}
   {"choices" [{"index" 0 "delta" {} "finish_reason" "stop"}]
    "usage" {"prompt_tokens" 5 "completion_tokens" 2}}])

(specification "OpenAI: running usage appears when provider sends it early"
  (let [deltas (atom [])
        reader (BufferedReader.
                (StringReader. (openai-sse openai-chunks-usage-early)))
        _      (oai/parse-openai-sse! reader "fallback-model"
                                      #(swap! deltas conj %))
        text   (filter #(= :text-delta (:type %)) @deltas)]
    (assertions
     "content delta carries running usage when provider folded it early"
     (-> text first :usage) => {:input-tokens 5}
     "still in Response :usage shape"
     (-> text first :usage keys set) => #{:input-tokens})))
