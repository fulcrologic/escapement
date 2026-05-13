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
   [escapement.llm.types :as types]))

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
               :tool_use    {"type"  "tool_use"
                             "id"    (:id blk)
                             "name"  (:name blk)
                             "input" (:input blk)}
               :tool_result (cond-> {"type"        "tool_result"
                                     "tool_use_id" (:tool_use_id blk)
                                     "content"     (:content blk)}
                              (:is-error blk) (assoc "is_error" true)))]
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

(>defn request->anthropic-json
       "Pure translation from our Request map to the Anthropic Messages API request body
   (as a Clojure map with string keys, ready for JSON serialization)."
       [request]
       [:map => :map]
       (let [{:keys [model system messages tools max-tokens system-cache-control]} request
             sys (system->wire system system-cache-control)]
         (cond-> {"model"      model
                  "messages"   (mapv message->wire messages)
                  "max_tokens" (or max-tokens 4096)}
           sys          (assoc "system" sys)
           (seq tools)  (assoc "tools" (mapv tool->wire tools)))))

;;; ---------------------------------------------------------------------------
;;; Translation: Anthropic JSON -> our Response

(defn- parse-stop-reason [s]
  (case s
    "end_turn"      :end_turn
    "max_tokens"    :max_tokens
    "tool_use"      :tool_use
    "stop_sequence" :stop_sequence
    :end_turn))

(defn- wire->block [{:strs [type] :as blk}]
  (case type
    "text"        {:type :text :text (get blk "text")}
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
;;; HTTP

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
        {:keys [status body]} (http/post url
                                         {:headers headers
                                          :body    body
                                          :timeout (or http-timeout-ms 60000)
                                          :throw   false})]
    (when-not (and (>= status 200) (< status 300))
      (throw (ex-info (str "API error: HTTP " status)
                      {:status status :body body :url url})))
    (try
      (json/parse-string body)
      (catch Throwable t
        (throw (ex-info "Failed to parse API JSON response"
                        {:body body :cause (.getMessage t)}))))))

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
