(ns escapement.llm.api
  "Anthropic Messages API backend. Parameterized by `:base-url` and `:api-key` so the
   same backend works against:

   - Anthropic (`https://api.anthropic.com`) — auth via `x-api-key` + `anthropic-version` headers.
   - z.ai's Anthropic-compatible endpoint (`https://api.z.ai/api/anthropic`) — auth via
     `Authorization: Bearer <key>` (z.ai uses standard OAuth-style Bearer; same Messages API shape).

   Auth mode auto-sniffs the base-url host (z.ai → :bearer, else :x-api-key) unless an
   explicit `:auth-mode` is provided.

   This is a near-passthrough: our Request shape mirrors Anthropic's, so translation is a
   key-rename plus content-block serialization. `cache_control` markers are passed through
   verbatim — both Anthropic and z.ai consume them natively."
  (:require
   [babashka.http-client :as http]
   [cheshire.core :as json]
   [clojure.string :as str]
   [com.fulcrologic.guardrails.malli.core :refer [>defn => ?]]
   [escapement.llm.protocol :as proto]
   [escapement.llm.types :as types])
  (:import
   (java.io BufferedReader InputStreamReader)))

;;; ---------------------------------------------------------------------------
;;; Translation: our Request -> Anthropic JSON

(defn- cc->wire
  "Translate our cache-control map to Anthropic's wire shape (snake_case keys, string values)."
  [cc]
  (when cc
    (cond-> {"type" (name (:type cc))}
      (:ttl cc) (assoc "ttl" (name (:ttl cc))))))

(defn- block->wire
  "Translate a single content block from our shape to Anthropic JSON."
  [{:keys [type] :as blk}]
  (let [base (case type
               :text        {"type" "text" "text" (:text blk)}
               :image       {"type"   "image"
                             "source" (let [{:keys [type] :as s} (:source blk)]
                                        (case type
                                          :base64 {"type"       "base64"
                                                   "media_type" (:media-type s)
                                                   "data"       (:data s)}
                                          :url    {"type" "url" "url" (:url s)}))}
               :tool_use    {"type"  "tool_use"
                             "id"    (:id blk)
                             "name"  (:name blk)
                             "input" (:input blk)}
               :tool_result (cond-> {"type"        "tool_result"
                                     "tool_use_id" (:tool_use_id blk)
                                     "content"     (:content blk)}
                              (:is-error blk) (assoc "is_error" true))
               :thinking    (cond-> {"type"     "thinking"
                                     "thinking" (:thinking blk)}
                              (:signature blk) (assoc "signature" (:signature blk)))
               :redacted_thinking {"type" "redacted_thinking"
                                   "data" (:data blk)})]
    (cond-> base
      (:cache-control blk) (assoc "cache_control" (cc->wire (:cache-control blk))))))

(defn- message->wire [{:keys [role content cache-control]}]
  (let [content-wire (mapv block->wire content)
        ;; If message-level cache-control is set, attach to last block (Anthropic convention).
        content-wire (if cache-control
                       (update content-wire (dec (count content-wire))
                               assoc "cache_control" (cc->wire cache-control))
                       content-wire)]
    {"role" (name role) "content" content-wire}))

(defn- tool->wire [{:keys [name description input-schema cache-control]}]
  (cond-> {"name"         name
           "description"  description
           "input_schema" input-schema}
    cache-control (assoc "cache_control" (cc->wire cache-control))))

(defn- system->wire [system system-cc]
  (cond
    (nil? system) nil
    system-cc     [{"type" "text" "text" system "cache_control" (cc->wire system-cc)}]
    :else         system))

(defn- thinking->wire [t]
  (when t
    (cond-> {"type" (name (:type t))}
      (:budget-tokens t) (assoc "budget_tokens" (:budget-tokens t)))))

(defn- tool-choice->wire [tc]
  (cond
    (nil? tc) nil
    (keyword? tc) {"type" (name tc)}
    (map? tc) (cond-> {"type" (name (:type tc))
                       "name" (:name tc)}
                (some? (:disable-parallel-tool-use tc))
                (assoc "disable_parallel_tool_use" (boolean (:disable-parallel-tool-use tc))))))

(defn- metadata->wire [m]
  (when m
    (cond-> {}
      (:user-id m) (assoc "user_id" (:user-id m)))))

(>defn request->anthropic-json
       "Pure translation from our Request map to the Anthropic Messages API request body
   (as a Clojure map with string keys, ready for JSON serialization)."
       [request]
       [:map => :map]
       (let [{:keys [model system messages tools max-tokens system-cache-control
                     temperature top-p top-k stop-sequences
                     thinking tool-choice metadata]} request
             sys (system->wire system system-cache-control)]
         (cond-> {"model"      model
                  "messages"   (mapv message->wire messages)
                  "max_tokens" (or max-tokens 8192)}
           sys                      (assoc "system" sys)
           (seq tools)              (assoc "tools" (mapv tool->wire tools))
           (some? temperature)      (assoc "temperature" temperature)
           (some? top-p)            (assoc "top_p" top-p)
           (some? top-k)            (assoc "top_k" top-k)
           (seq stop-sequences)     (assoc "stop_sequences" (vec stop-sequences))
           thinking                 (assoc "thinking" (thinking->wire thinking))
           (some? tool-choice)      (assoc "tool_choice" (tool-choice->wire tool-choice))
           (seq metadata)           (assoc "metadata" (metadata->wire metadata)))))

;;; ---------------------------------------------------------------------------
;;; Translation: Anthropic JSON -> our Response

(defn- parse-stop-reason [s]
  (case s
    "end_turn"      :end_turn
    "max_tokens"    :max_tokens
    "tool_use"      :tool_use
    "stop_sequence" :stop_sequence
    "pause_turn"    :pause_turn
    "refusal"       :refusal
    :end_turn))

(defn- wire->block [{:strs [type] :as blk}]
  (case type
    "text"        {:type :text :text (get blk "text")}
    "image"       {:type   :image
                   :source (let [s (get blk "source")]
                             (case (get s "type")
                               "base64" {:type       :base64
                                         :media-type (get s "media_type")
                                         :data       (get s "data")}
                               "url"    {:type :url :url (get s "url")}))}
    "tool_use"    {:type  :tool_use
                   :id    (get blk "id")
                   :name  (get blk "name")
                   ;; Keywordize the :input map keys ONLY (not the rest of the response):
                   ;; chart-author schemas use keyword keys and this avoids forcing string-key
                   ;; schemas everywhere. Nested map values are left as-is by cheshire's default.
                   :input (let [raw (get blk "input" {})]
                            (if (map? raw)
                              (reduce-kv (fn [m k v] (assoc m (keyword k) v)) {} raw)
                              raw))}
    "tool_result" (cond-> {:type        :tool_result
                           :tool_use_id (get blk "tool_use_id")
                           :content     (let [c (get blk "content")]
                                          (if (string? c) c (json/generate-string c)))}
                    (get blk "is_error") (assoc :is-error true))
    "thinking"    (cond-> {:type     :thinking
                           :thinking (get blk "thinking")}
                    (get blk "signature") (assoc :signature (get blk "signature")))
    "redacted_thinking" {:type :redacted_thinking
                         :data (get blk "data")}
    ;; Unknown block type — preserve as text marker
    {:type :text :text (str "[unknown block: " type "]")}))

(defn- wire->usage [usage]
  (cond-> {}
    (get usage "input_tokens")                 (assoc :input-tokens (get usage "input_tokens"))
    (get usage "output_tokens")                (assoc :output-tokens (get usage "output_tokens"))
    (get usage "cache_creation_input_tokens") (assoc :cache-creation-input-tokens
                                                     (get usage "cache_creation_input_tokens"))
    (get usage "cache_read_input_tokens")     (assoc :cache-read-input-tokens
                                                     (get usage "cache_read_input_tokens"))))

(>defn anthropic-json->response
       "Pure translation from a parsed Anthropic Messages API response to our Response map."
       [parsed request-model]
       [:map :string => :map]
       {:stop-reason      (parse-stop-reason (get parsed "stop_reason"))
        :content          (mapv wire->block (get parsed "content" []))
        :usage            (wire->usage (get parsed "usage" {}))
        :model            (or (get parsed "model") request-model)
        :backend-metadata (cond-> {:backend :api}
                            (get parsed "id")   (assoc :message-id (get parsed "id"))
                            (get parsed "role") (assoc :role (get parsed "role")))})

;;; ---------------------------------------------------------------------------
;;; Streaming (SSE) — Messages API with "stream": true
;;;
;;; Wire shape: a sequence of SSE events. We only read `data:` lines (JSON);
;;; `event:` lines are redundant with the JSON `type`. Blocks are rebuilt by
;;; index from content_block_start/_delta/_stop; the final assembly reuses the
;;; same `wire->block` / `wire->usage` translators as the non-streaming path,
;;; so a streamed turn yields a Response whose blocks + usage are structurally
;;; identical to a buffered one. That equivalence is no longer asserted by
;;; prose: it is proven, regression-gated, by the executable equivalence test
;;; `escapement.llm.stream-equivalence-test` (Anthropic + OpenAI backends).

(defn stream-acc-init []
  {:blocks (sorted-map) :model nil :usage {} :stop-reason nil})

(defn- block-builder [{:strs [type] :as cb}]
  (case type
    "text"              {:type "text" :text (or (get cb "text") "")}
    "thinking"          {:type "thinking" :thinking (or (get cb "thinking") "") :signature ""}
    "redacted_thinking" {:type "redacted_thinking" :data (get cb "data")}
    "tool_use"          {:type "tool_use" :id (get cb "id") :name (get cb "name")
                         :partial-json ""}
    {:type (or type "text") :text ""}))

(defn- builder->wire [b]
  (case (:type b)
    "text"              {"type" "text" "text" (:text b)}
    "thinking"          (cond-> {"type" "thinking" "thinking" (:thinking b)}
                          (seq (:signature b)) (assoc "signature" (:signature b)))
    "redacted_thinking" {"type" "redacted_thinking" "data" (:data b)}
    "tool_use"          {"type" "tool_use" "id" (:id b) "name" (:name b)
                         "input" (let [pj (:partial-json b)]
                                   (if (seq pj)
                                     (try (json/parse-string pj) (catch Throwable _ {}))
                                     {}))}
    {"type" "text" "text" (or (:text b) "")}))

(defn- with-running-usage
  "Attach the cumulative usage already folded into `acc` onto a delta map as
   the OPTIONAL `:usage` key, using the same `{:input-tokens :output-tokens ...}`
   shape as the finalized `Response` `:usage`. Omitted entirely when no usage
   has been accumulated yet (optional means absent, not a zero placeholder).
   Purely informational live-progress; the finalized `Response` usage stays the
   billing source of truth and is independent of this running total."
  [delta acc]
  (let [u (:usage acc)]
    (cond-> delta
      (seq u) (assoc :usage u))))

(defn stream-acc-step
  "Fold one parsed SSE `data:` payload into accumulator `acc`. Calls
   `(on-delta {:type :text-delta|:thinking-delta :text s})` for incremental
   text/thinking (with an optional cumulative `:usage` key sourced from the
   running fold — see `with-running-usage`). Pure except for the supplied
   callback."
  [acc {:strs [type index] :as ev} on-delta]
  (case type
    "message_start"
    (let [m (get ev "message")]
      (-> acc
          (assoc :model (get m "model"))
          (update :usage merge (wire->usage (get m "usage" {})))))

    "content_block_start"
    (assoc-in acc [:blocks index] (block-builder (get ev "content_block")))

    "content_block_delta"
    (let [d (get ev "delta")]
      (case (get d "type")
        "text_delta"
        (do (when on-delta (on-delta (with-running-usage
                                      {:type :text-delta :text (get d "text")}
                                      acc)))
            (update-in acc [:blocks index :text] str (get d "text")))
        "thinking_delta"
        (do (when on-delta (on-delta (with-running-usage
                                      {:type :thinking-delta :text (get d "thinking")}
                                      acc)))
            (update-in acc [:blocks index :thinking] str (get d "thinking")))
        "signature_delta"
        (update-in acc [:blocks index :signature] str (get d "signature"))
        "input_json_delta"
        (update-in acc [:blocks index :partial-json] str (get d "partial_json"))
        acc))

    "message_delta"
    (-> acc
        (cond-> (get-in ev ["delta" "stop_reason"])
          (assoc :stop-reason (get-in ev ["delta" "stop_reason"])))
        (update :usage merge (wire->usage (get ev "usage" {}))))

    "error"
    (let [err  (get ev "error")
          etyp (str/lower-case (str (when (map? err) (get err "type"))))
          cat  (if (str/includes? etyp "overloaded") :overloaded :transport)]
      (throw (proto/llm-error cat "Anthropic SSE stream reported an error"
                              {:data {:error err}})))

    ;; content_block_stop, message_stop, ping, unknown — no-op
    acc))

(defn stream-acc-finalize
  "Build the final Response map from a completed accumulator."
  [acc request-model]
  {:stop-reason      (parse-stop-reason (:stop-reason acc))
   :content          (mapv (comp wire->block builder->wire)
                            (vals (:blocks acc)))
   :usage            (:usage acc)
   :model            (or (:model acc) request-model)
   :backend-metadata {:backend :api :streamed true}})

(defn parse-anthropic-sse!
  "Drive an SSE `BufferedReader` to completion, returning the final Response.
   Invokes `on-delta` for each incremental text/thinking chunk."
  [^BufferedReader reader request-model on-delta]
  (loop [acc (stream-acc-init)]
    (let [line (.readLine reader)]
      (if (nil? line)
        (stream-acc-finalize acc request-model)
        (if (str/starts-with? line "data:")
          (let [data (str/trim (subs line 5))]
            (if (or (empty? data) (= data "[DONE]"))
              (recur acc)
              (recur (stream-acc-step acc (json/parse-string data)
                                      (fn [d] (try (when on-delta (on-delta d))
                                                   (catch Throwable _ nil)))))))
          (recur acc))))))

;;; ---------------------------------------------------------------------------
;;; HTTP

(defn- status->category
  "Map an HTTP status (and best-effort response body) to an
   `escapement.llm.protocol` error category."
  [status body]
  (let [b (str/lower-case (str body))
        ;; Match the specific provider phrasings for an over-long prompt
        ;; (Anthropic: "prompt is too long: N tokens > M maximum";
        ;; OpenAI-compat: "maximum context length is N tokens"). Deliberately
        ;; narrow so a generic 400 that merely mentions "token" is NOT
        ;; misclassified as :context-length.
        ctx-len? (some #(str/includes? b %)
                       ["prompt is too long"
                        "context length"
                        "context window"
                        "maximum context"
                        "exceeds the maximum"
                        "too many tokens"
                        "reduce the length"])]
    (cond
      (= status 429)                         :rate-limited
      (= status 529)                         :overloaded
      (str/includes? b "overloaded")         :overloaded
      (or (= status 401) (= status 403))     :auth
      (and (or (= status 400) (= status 422))
           ctx-len?)                         :context-length
      (or (= status 400) (= status 422))     :invalid-request
      :else                                  :transport)))

(defn- non-2xx!
  "Throw a categorized `protocol/llm-error` for a non-2xx HTTP response,
   preserving the legacy message text and ex-data keys (:status :body :url)."
  [status body url]
  (throw (proto/llm-error (status->category status body)
                          (str "API error: HTTP " status)
                          {:status status
                           :data   {:status status :body body :url url}})))

(defn- with-timeout-category
  "Run `f`; if the HTTP client throws a timeout or transport-level exception,
   rethrow it as a categorized error (other throwables propagate as-is). A
   timed-out request is `:timeout`; a refused/failed connection is
   `:transport` (the request never completed at the transport layer)."
  [url f]
  (try
    (f)
    (catch java.net.http.HttpTimeoutException t
      (throw (proto/llm-error :timeout (str "API request timed out: " url)
                              {:cause t :data {:url url}})))
    (catch java.net.ConnectException t
      (throw (proto/llm-error :transport (str "API connection failed: " url)
                              {:cause t :data {:url url}})))
    (catch java.io.IOException t
      (throw (proto/llm-error :transport (str "API transport error: " url)
                              {:cause t :data {:url url}})))))

(defn- mask-key [k]
  (when k
    (let [s (str k)]
      (if (> (count s) 8)
        (str (subs s 0 4) "..." (subs s (- (count s) 4)))
        "***"))))

(defn- sniff-auth-mode [base-url]
  (if (and base-url (str/includes? base-url "z.ai"))
    :bearer
    :x-api-key))

(>defn auth-headers
       "Build the auth headers for a request, based on auth-mode and api-key."
       [{:keys [auth-mode base-url api-key anthropic-version]}]
       [:map => :map]
       (let [mode (or auth-mode (sniff-auth-mode base-url))]
         (case mode
           :bearer    {"Authorization" (str "Bearer " api-key)}
           :x-api-key {"x-api-key"         api-key
                       "anthropic-version" (or anthropic-version "2023-06-01")})))

(defn- post-messages! [{:keys [base-url api-key auth-mode anthropic-version
                               extra-headers http-timeout-ms]} body-map]
  (let [url     (str base-url "/v1/messages")
        headers (merge {"Content-Type" "application/json"}
                       (auth-headers {:auth-mode         auth-mode
                                      :base-url          base-url
                                      :api-key           api-key
                                      :anthropic-version anthropic-version})
                       extra-headers)
        body    (json/generate-string body-map)
        {:keys [status body]} (with-timeout-category
                                url
                                #(http/post url
                                            {:headers headers
                                             :body    body
                                             :timeout (or http-timeout-ms 60000)
                                             :throw   false}))]
    (when-not (and (>= status 200) (< status 300))
      (non-2xx! status body url))
    (try
      (json/parse-string body)
      (catch Throwable t
        (throw (ex-info "Failed to parse API JSON response"
                        {:body body :cause (.getMessage t)}))))))

(defn- stream-messages! [{:keys [base-url api-key auth-mode anthropic-version
                                 extra-headers http-timeout-ms]} body-map
                          request-model on-delta]
  (let [url     (str base-url "/v1/messages")
        headers (merge {"Content-Type" "application/json"
                        "Accept"       "text/event-stream"}
                       (auth-headers {:auth-mode         auth-mode
                                      :base-url          base-url
                                      :api-key           api-key
                                      :anthropic-version anthropic-version})
                       extra-headers)
        {:keys [status body]} (with-timeout-category
                                url
                                #(http/post url
                                            {:headers headers
                                             :body    (json/generate-string
                                                       (assoc body-map "stream" true))
                                             :as      :stream
                                             :timeout (or http-timeout-ms 60000)
                                             :throw   false}))]
    (when-not (and (>= status 200) (< status 300))
      (non-2xx! status (try (slurp body) (catch Throwable _ "")) url))
    (with-open [reader (BufferedReader. (InputStreamReader. body "UTF-8"))]
      (parse-anthropic-sse! reader request-model on-delta))))

;;; ---------------------------------------------------------------------------
;;; Backend record

(defrecord AnthropicAPIBackend [opts]
  proto/LLMBackend
  (send-turn [_ request]
    (let [request (cond-> request
                    (and (nil? (:model request)) (:default-model opts))
                    (assoc :model (:default-model opts)))]
      (when-let [err (types/validate-request request)]
        (throw (ex-info "Invalid LLM request" {:errors err :request request})))
      (let [transcript-fn (:transcript-fn opts)
            body-map      (request->anthropic-json request)
            _             (when transcript-fn
                            (transcript-fn {:event       :llm/request
                                            :backend     :api
                                            :base-url    (:base-url opts)
                                            :api-key     (mask-key (:api-key opts))
                                            :model       (:model request)
                                            :body        body-map}))
            parsed        (post-messages! opts body-map)
            response      (anthropic-json->response parsed (:model request))]
        (when transcript-fn
          (transcript-fn {:event    :llm/response
                          :backend  :api
                          :response response}))
        (when-let [err (types/validate-response response)]
          (throw (ex-info "API produced an invalid response"
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
            body-map      (request->anthropic-json request)
            _             (when transcript-fn
                            (transcript-fn {:event    :llm/request
                                            :backend  :api
                                            :base-url (:base-url opts)
                                            :api-key  (mask-key (:api-key opts))
                                            :model    (:model request)
                                            :stream   true
                                            :body     body-map}))
            response      (stream-messages! opts body-map (:model request) on-delta)]
        (when transcript-fn
          (transcript-fn {:event    :llm/response
                          :backend  :api
                          :response response}))
        (when-let [err (types/validate-response response)]
          (throw (ex-info "API produced an invalid response"
                          {:errors err :response response})))
        response))))

(>defn new-backend
       "Construct an Anthropic-compatible API backend.

   Required opts:
   - `:api-key`  — string. Caller supplies (no env lookup here).
   - `:base-url` — string, e.g. \"https://api.anthropic.com\" or
                   \"https://api.z.ai/api/anthropic\".

   Optional opts:
   - `:default-model`     — string used when `Request` omits `:model`.
   - `:auth-mode`         — `:bearer` | `:x-api-key`. Auto-sniffed from `:base-url` if absent.
   - `:anthropic-version` — header value for x-api-key mode (default \"2023-06-01\").
   - `:extra-headers`     — map of additional request headers.
   - `:http-timeout-ms`   — request timeout (default 60000).
   - `:transcript-fn`     — `(fn [event])` called with `:llm/request` / `:llm/response`."
       ([] [=> :any] (new-backend {}))
       ([opts]
        [:map => :any]
        (->AnthropicAPIBackend opts)))
