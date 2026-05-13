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
   [escapement.llm.types :as types]))

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

(defn- max-tokens-key
  "OpenAI deprecated `max_tokens` in favor of `max_completion_tokens` on newer
   models (o-series, gpt-4.1+, gpt-5). Use the new key by default; opt back
   to the legacy key for models whose ids start with `gpt-3.5` or `gpt-4-`."
  [model]
  (if (and model
           (or (str/starts-with? model "gpt-3.5")
               (str/starts-with? model "gpt-4-")
               (str/starts-with? model "gpt-4o")))
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
