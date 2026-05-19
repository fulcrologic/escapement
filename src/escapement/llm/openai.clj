(ns escapement.llm.openai
  "OpenAI Chat Completions backend. A sibling to `escapement.llm.api` that
   accepts the SAME internal Request map (Anthropic-shaped) and translates it
   to OpenAI's `/v1/chat/completions` wire format. Charts and the
   `llm-conversation` invocation processor are unchanged.

   Works against:
   - OpenAI            (`https://api.openai.com/v1`)
   - OpenAI-compatible (`https://openrouter.ai/api/v1`, local llama.cpp, vLLM,
                        Codex-style coding models served via OpenAI shape, etc.)

   Anthropic-only inputs are silently dropped: `:cache-control` markers on
   blocks/tools/system, `:system-cache-control`, `:tools-cache-control`,
   `:top-k`, `:thinking`, `:redacted_thinking` content blocks. OpenAI does its
   own implicit prefix caching and reports it on `usage.prompt_tokens_details
   .cached_tokens`, which we surface as `:cache-read-input-tokens` for
   transcript symmetry."
  (:require
   [babashka.http-client :as http]
   [cheshire.core :as json]
   [clojure.string :as str]
   [com.fulcrologic.guardrails.malli.core :refer [>defn =>]]
   [escapement.llm.protocol :as proto]
   [escapement.llm.types :as types])
  (:import
   (java.io BufferedReader InputStreamReader)))

;;; ---------------------------------------------------------------------------
;;; Request translation: our Request -> OpenAI Chat Completions JSON

(defn- text-content
  "Concatenate :text blocks from a content vector; nil when there are none."
  [blocks]
  (let [s (->> blocks
               (filter #(= :text (:type %)))
               (map :text)
               (str/join ""))]
    (when (seq s) s)))

(defn- tool-use-blocks [blocks]
  (filterv #(= :tool_use (:type %)) blocks))

(defn- tool-result-blocks [blocks]
  (filterv #(= :tool_result (:type %)) blocks))

(defn- tool-use->openai-call
  "An assistant tool_use block -> OpenAI tool_call entry. `:input` is a Clojure
   map; OpenAI expects the arguments as a JSON-encoded string."
  [{:keys [id name input]}]
  {"id"       id
   "type"     "function"
   "function" {"name"      name
               "arguments" (json/generate-string (or input {}))}})

(defn- user-message->openai
  "A :user message expands to ONE user message (its text + any non-tool content)
   PLUS one `role:tool` message per tool_result block. Returns a vector — the
   caller flattens."
  [{:keys [content]}]
  (let [trs (tool-result-blocks content)
        txt (text-content content)
        msgs (cond-> []
               txt (conj {"role" "user" "content" txt}))]
    (into msgs
          (mapv (fn [{:keys [tool_use_id content is-error]}]
                  (cond-> {"role"         "tool"
                           "tool_call_id" tool_use_id
                           "content"      (or content "")}
                    ;; OpenAI has no is_error flag; surface it inline so the
                    ;; model can still see the signal.
                    is-error (update "content" #(str "[error] " %))))
                trs))))

(defn- assistant-message->openai
  "An :assistant message becomes ONE OpenAI assistant message that may carry
   both `content` and `tool_calls`. Thinking blocks are dropped."
  [{:keys [content]}]
  (let [txt   (text-content content)
        calls (tool-use-blocks content)]
    [(cond-> {"role" "assistant"}
       txt          (assoc "content" txt)
       (seq calls)  (assoc "tool_calls" (mapv tool-use->openai-call calls))
       ;; OpenAI requires `content` to be present (string or null) when there
       ;; are no tool_calls — fill in nil explicitly to avoid 400s.
       (and (nil? txt) (empty? calls))
       (assoc "content" ""))]))

(defn- message->openai [{:keys [role] :as msg}]
  (case role
    :user      (user-message->openai msg)
    :assistant (assistant-message->openai msg)))

(defn- tool->openai [{:keys [name description input-schema]}]
  {"type"     "function"
   "function" {"name"        name
               "description" description
               "parameters"  input-schema}})

(defn- tool-choice->openai [tc]
  (cond
    (nil? tc)     nil
    (= :auto tc)  "auto"
    (= :any tc)   "required"
    (= :none tc)  "none"
    (map? tc)     {"type"     "function"
                   "function" {"name" (:name tc)}}))

(def ^:private legacy-max-tokens-prefixes
  "Model-id prefixes that still require the deprecated `max_tokens` key:
   older OpenAI families plus known OpenAI-compatible provider families."
  ["gpt-3.5" "gpt-4-" "gpt-4o" "gpt-oss"
   "glm-" "kimi-" "deepseek-" "minimax-" "mimo-"])

(defn- max-tokens-key
  "OpenAI deprecated `max_tokens` in favor of `max_completion_tokens` on newer
   models (o-series, gpt-4.1+, gpt-5). Use the new key by default; opt back
   to the legacy key for the families in `legacy-max-tokens-prefixes`."
  [model]
  (if (some #(str/starts-with? (str model) %) legacy-max-tokens-prefixes)
    "max_tokens"
    "max_completion_tokens"))

(>defn request->openai-json
       "Pure translation from our Request map to an OpenAI Chat Completions request
   body (Clojure map with string keys, ready for JSON serialization)."
       [request]
       [:map => :map]
       (let [{:keys [model system messages tools max-tokens
                     temperature top-p stop-sequences tool-choice metadata]} request
             sys-msg  (when system [{"role" "system" "content" system}])
             rest-msg (into [] (mapcat message->openai messages))
             all-msgs (into (vec sys-msg) rest-msg)]
         (cond-> {"model"    model
                  "messages" all-msgs}
           max-tokens           (assoc (max-tokens-key model) max-tokens)
           (seq tools)          (assoc "tools" (mapv tool->openai tools))
           (some? temperature)  (assoc "temperature" temperature)
           (some? top-p)        (assoc "top_p" top-p)
           (seq stop-sequences) (assoc "stop" (vec stop-sequences))
           (some? tool-choice)  (assoc "tool_choice" (tool-choice->openai tool-choice))
           (:user-id metadata)  (assoc "user" (:user-id metadata)))))

;;; ---------------------------------------------------------------------------
;;; Response translation: OpenAI JSON -> our Response

(defn- parse-finish-reason [s]
  (case s
    "stop"           :end_turn
    "length"         :max_tokens
    "tool_calls"     :tool_use
    "function_call"  :tool_use
    "content_filter" :refusal
    :end_turn))

(defn- openai-call->tool-use-block
  "OpenAI tool_call -> our :tool_use block. `arguments` is a JSON string; parse
   it into a map and keywordize the top-level keys (same convention as the
   Anthropic backend)."
  [{:strs [id function]}]
  (let [{:strs [name arguments]} function
        parsed (try
                 (json/parse-string (or arguments "{}"))
                 (catch Throwable _ {}))
        input  (if (map? parsed)
                 (reduce-kv (fn [m k v] (assoc m (keyword k) v)) {} parsed)
                 {})]
    {:type :tool_use :id id :name name :input input}))

(defn- message->content-blocks
  "OpenAI assistant message -> vector of our content blocks. Text first (if
   present), then one :tool_use block per tool_call."
  [{:strs [content tool_calls]}]
  (let [text-blk (when (and (string? content) (seq content))
                   [{:type :text :text content}])
        tu-blks  (mapv openai-call->tool-use-block (or tool_calls []))]
    (into (vec text-blk) tu-blks)))

(defn- usage->ours [usage]
  (let [cached (some-> usage (get "prompt_tokens_details") (get "cached_tokens"))]
    (cond-> {}
      (get usage "prompt_tokens")     (assoc :input-tokens (get usage "prompt_tokens"))
      (get usage "completion_tokens") (assoc :output-tokens (get usage "completion_tokens"))
      cached                          (assoc :cache-read-input-tokens cached))))

(>defn openai-json->response
       "Pure translation from a parsed OpenAI Chat Completions response to our
   Response map."
       [parsed request-model]
       [:map :string => :map]
       (let [choice  (first (get parsed "choices"))
             msg     (get choice "message")
             finish  (get choice "finish_reason")
             content (message->content-blocks msg)]
         {:stop-reason      (parse-finish-reason finish)
     ;; Our Response schema requires at least an empty content vector. Some
     ;; pure-tool-call turns have no text — that's fine, the :tool_use blocks
     ;; carry the payload. Guarantee a non-nil vector.
          :content          (vec content)
          :usage            (usage->ours (get parsed "usage" {}))
          :model            (or (get parsed "model") request-model)
          :backend-metadata (cond-> {:backend :openai}
                              (get parsed "id") (assoc :message-id (get parsed "id")))}))

;;; ---------------------------------------------------------------------------
;;; HTTP

(defn- mask-key [k]
  (when k
    (let [s (str k)]
      (if (> (count s) 8)
        (str (subs s 0 4) "..." (subs s (- (count s) 4)))
        "***"))))

(defn- post-chat! [{:keys [base-url api-key extra-headers http-timeout-ms]} body-map]
  (let [url     (str base-url "/chat/completions")
        headers (merge {"Content-Type"  "application/json"
                        "Authorization" (str "Bearer " api-key)}
                       extra-headers)
        body    (json/generate-string body-map)
        {:keys [status body]} (http/post url
                                         {:headers headers
                                          :body    body
                                          :timeout (or http-timeout-ms 60000)
                                          :throw   false})]
    (when-not (and (>= status 200) (< status 300))
      (throw (ex-info (str "OpenAI API error: HTTP " status)
                      {:status status :body body :url url})))
    (try
      (json/parse-string body)
      (catch Throwable t
        (throw (ex-info "Failed to parse OpenAI API JSON response"
                        {:body body :cause (.getMessage t)}))))))

;;; ---------------------------------------------------------------------------
;;; Streaming (SSE) — Chat Completions with "stream": true
;;;
;;; Wire shape: a sequence of SSE `data:` lines (JSON) terminated by
;;; `data: [DONE]`. Each chunk carries `choices[].delta` with incremental
;;; `content` and/or indexed `tool_calls` fragments; the trailing chunk(s)
;;; carry `finish_reason` and (with `stream_options.include_usage`) a final
;;; `usage` object. We accumulate the fragments into a buffered-shaped OpenAI
;;; response body and finalize it through the SAME `openai-json->response`
;;; translator the non-streaming path uses, so a streamed turn yields a
;;; structurally identical Response to a buffered one. Mirrors the Anthropic
;;; SSE accumulate-then-finalize pattern in `escapement.llm.api`.

(defn stream-acc-init
  "Fresh accumulator. `:tool-calls` is a sorted-map keyed by the streamed
   tool_call `index` so fragments reassemble in arrival order. `:usage` holds
   the last streamed usage object (running usage is kept here so a downstream
   consumer can surface it on deltas without re-architecting — see task-004)."
  []
  {:id nil :model nil :content "" :tool-calls (sorted-map)
   :finish-reason nil :usage nil})

(defn- merge-tool-call-delta
  "Fold one streamed tool_call delta fragment into the accumulator's
   `:tool-calls` map, keyed by `index`. `id` and `function.name` arrive once;
   `function.arguments` arrives as string fragments that must be concatenated."
  [acc {:strs [index id type] :as tc}]
  (let [idx     (or index 0)
        fn*     (get tc "function")
        cur     (get-in acc [:tool-calls idx]
                        {"index" idx "type" "function"
                         "function" {"name" "" "arguments" ""}})
        updated (cond-> cur
                  id   (assoc "id" id)
                  type (assoc "type" type)
                  (get fn* "name")
                  (assoc-in ["function" "name"] (get fn* "name"))
                  (get fn* "arguments")
                  (update-in ["function" "arguments"]
                             str (get fn* "arguments")))]
    (assoc-in acc [:tool-calls idx] updated)))

(defn- with-running-usage
  "Attach the cumulative usage already folded into `acc` onto a delta map as
   the OPTIONAL `:usage` key, using the same `{:input-tokens :output-tokens
   :cache-read-input-tokens}` shape (via `usage->ours`) as the finalized
   `Response` `:usage`. Omitted entirely when no usage has been accumulated yet.
   NOTE: OpenAI Chat Completions only emits usage on the final usage-only chunk
   (with `stream_options.include_usage`), so most content deltas will carry no
   `:usage`; the running total still moves monotonically non-decreasing
   (absent/empty -> final). Purely informational; finalized `Response` usage
   remains the billing source of truth and is independent of this."
  [delta acc]
  (let [u (some-> (:usage acc) usage->ours)]
    (cond-> delta
      (seq u) (assoc :usage u))))

(defn stream-acc-step
  "Fold one parsed Chat Completions SSE chunk into accumulator `acc`. Calls
   `(on-delta {:type :text-delta :text s})` for each incremental content
   chunk (with an optional cumulative `:usage` key sourced from the running
   fold — see `with-running-usage`). Pure except for the supplied callback."
  [acc chunk on-delta]
  (let [acc    (cond-> acc
                 (get chunk "id")    (assoc :id (get chunk "id"))
                 (get chunk "model") (assoc :model (get chunk "model"))
                 ;; Final usage chunk (stream_options.include_usage) — and
                 ;; some providers attach usage to the finish chunk too.
                 (get chunk "usage") (assoc :usage (get chunk "usage")))
        choice (first (get chunk "choices"))
        delta  (get choice "delta")
        text   (get delta "content")
        acc    (cond-> acc
                 (get choice "finish_reason")
                 (assoc :finish-reason (get choice "finish_reason")))
        acc    (if (and (string? text) (seq text))
                 (do (when on-delta
                       (on-delta (with-running-usage
                                  {:type :text-delta :text text} acc)))
                     (update acc :content str text))
                 acc)]
    (reduce merge-tool-call-delta acc (get delta "tool_calls" []))))

(defn stream-acc->openai-body
  "Assemble the accumulator into a buffered-shaped OpenAI Chat Completions
   response body — the exact shape `openai-json->response` consumes, so the
   non-streaming translator finalizes the streamed turn unchanged."
  [acc]
  (let [content (when (seq (:content acc)) (:content acc))
        calls   (vec (vals (:tool-calls acc)))
        msg     (cond-> {"role" "assistant" "content" content}
                  (seq calls) (assoc "tool_calls" calls))]
    (cond-> {"choices" [{"index"         0
                         "finish_reason" (:finish-reason acc)
                         "message"       msg}]
             "usage"   (or (:usage acc) {})}
      (:id acc)    (assoc "id" (:id acc))
      (:model acc) (assoc "model" (:model acc)))))

(defn parse-openai-sse!
  "Drive an SSE `BufferedReader` to completion, returning the final Response
   (translated via the shared `openai-json->response`). Invokes `on-delta`
   for each incremental content chunk. `on-delta` exceptions never abort the
   turn."
  [^BufferedReader reader request-model on-delta]
  (loop [acc (stream-acc-init)]
    (let [line (.readLine reader)]
      (if (nil? line)
        (openai-json->response (stream-acc->openai-body acc)
                               (str request-model))
        (if (str/starts-with? line "data:")
          (let [data (str/trim (subs line 5))]
            (if (or (empty? data) (= data "[DONE]"))
              (recur acc)
              (recur (stream-acc-step
                      acc (json/parse-string data)
                      (fn [d] (try (when on-delta (on-delta d))
                                   (catch Throwable _ nil)))))))
          (recur acc))))))

(defn- stream-chat! [{:keys [base-url api-key extra-headers http-timeout-ms]}
                     body-map request-model on-delta]
  (let [url     (str base-url "/chat/completions")
        headers (merge {"Content-Type"  "application/json"
                        "Accept"        "text/event-stream"
                        "Authorization" (str "Bearer " api-key)}
                       extra-headers)
        ;; stream:true plus stream_options.include_usage so the final usage
        ;; chunk arrives, matching the buffered path's usage.
        body    (json/generate-string
                 (assoc body-map
                        "stream" true
                        "stream_options" {"include_usage" true}))
        {:keys [status body]} (http/post url
                                         {:headers headers
                                          :body    body
                                          :as      :stream
                                          :timeout (or http-timeout-ms 60000)
                                          :throw   false})]
    (when-not (and (>= status 200) (< status 300))
      (throw (ex-info (str "OpenAI API error: HTTP " status)
                      {:status status
                       :body   (try (slurp body) (catch Throwable _ ""))
                       :url    url})))
    (with-open [reader (BufferedReader. (InputStreamReader. body "UTF-8"))]
      (parse-openai-sse! reader request-model on-delta))))

;;; ---------------------------------------------------------------------------
;;; Backend record

(defrecord OpenAIBackend [opts]
  proto/LLMBackend
  (send-turn [_ request]
    (let [request (cond-> request
                    (and (nil? (:model request)) (:default-model opts))
                    (assoc :model (:default-model opts)))]
      (when-let [err (types/validate-request request)]
        (throw (ex-info "Invalid LLM request" {:errors err :request request})))
      (let [transcript-fn (:transcript-fn opts)
            body-map      (request->openai-json request)
            _             (when transcript-fn
                            (transcript-fn {:event    :llm/request
                                            :backend  :openai
                                            :base-url (:base-url opts)
                                            :api-key  (mask-key (:api-key opts))
                                            :model    (:model request)
                                            :body     body-map}))
            parsed        (post-chat! opts body-map)
            response      (openai-json->response parsed (:model request))]
        (when transcript-fn
          (transcript-fn {:event    :llm/response
                          :backend  :openai
                          :response response}))
        (when-let [err (types/validate-response response)]
          (throw (ex-info "OpenAI backend produced an invalid response"
                          {:errors err :response response :raw parsed})))
        response)))

  proto/StreamingLLMBackend
  (stream-turn [_ request on-delta]
    (let [request (cond-> request
                    (and (nil? (:model request)) (:default-model opts))
                    (assoc :model (:default-model opts)))]
      (when-let [err (types/validate-request request)]
        (throw (ex-info "Invalid LLM request" {:errors err :request request})))
      (let [transcript-fn (:transcript-fn opts)
            body-map      (request->openai-json request)
            _             (when transcript-fn
                            (transcript-fn {:event    :llm/request
                                            :backend  :openai
                                            :base-url (:base-url opts)
                                            :api-key  (mask-key (:api-key opts))
                                            :model    (:model request)
                                            :stream   true
                                            :body     body-map}))
            response      (stream-chat! opts body-map (:model request) on-delta)]
        (when transcript-fn
          (transcript-fn {:event    :llm/response
                          :backend  :openai
                          :response response}))
        (when-let [err (types/validate-response response)]
          (throw (ex-info "OpenAI backend produced an invalid response"
                          {:errors err :response response})))
        response))))

(>defn new-backend
       "Construct an OpenAI Chat Completions backend.

   Required opts:
   - `:api-key`  — string.
   - `:base-url` — string, e.g. \"https://api.openai.com/v1\" or an
                   OpenAI-compatible endpoint root (must NOT include the
                   trailing `/chat/completions`).

   Optional opts:
   - `:default-model`   — string used when Request omits `:model`.
   - `:extra-headers`   — map of additional request headers.
   - `:http-timeout-ms` — request timeout (default 60000).
   - `:transcript-fn`   — `(fn [event])` called with `:llm/request` / `:llm/response`."
       ([] [=> :any] (new-backend {}))
       ([opts]
        [:map => :any]
        (->OpenAIBackend opts)))
