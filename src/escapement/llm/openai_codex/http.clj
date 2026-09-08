(ns escapement.llm.openai-codex.http
  "Streaming HTTP client for the OpenAI Responses API via the ChatGPT backend.

  Sends POST requests with SSE streaming to
  `https://chatgpt.com/backend-api/codex/responses` and parses the
  event stream into a structured result map.

  Also serves any OpenAI-Responses-compatible endpoint reached with a
  plain API key (`:api-key` + `:url`) — e.g. z.ai's coding-plan v1
  endpoint `https://api.z.ai/api/v1/responses`. The api-key path sends
  none of the ChatGPT OAuth headers.

  Goes through `escapement.llm.http-transport`, so the host picks the
  underlying HTTP impl. The CLJ default uses bb's http-client."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
    [escapement.llm.http-transport :as ht]
    [escapement.llm.protocol :as proto]
    [taoensso.timbre :as log]
    [com.fulcrologic.statecharts.promise :as p]))

;;; ---------------------------------------------------------------------------
;;; Constants

(def ^:private RESPONSES-URL "https://chatgpt.com/backend-api/codex/responses")

(def default-max-sse-event-chars
  "Maximum buffered SSE data characters per event, including each field's newline.
   Bounds parser accumulation, not the transport's allocation of an individual line."
  (* 8 1024 1024))

(defn- error-category
  "Category for a failed Responses call.

   Body evidence for a context overflow is checked BEFORE the 5xx status
   sweep on purpose: proxies in front of a Responses endpoint routinely
   surface an upstream context overflow as a 500 carrying
   `context_length_exceeded`. Classified `:overloaded`, the engine would
   retry the identical oversized prompt through its whole budget and then
   report exhaustion; `:context-length` is non-retryable and lets the caller
   compact history instead. The remaining body sniffs stay AFTER the status
   sweep — they only speak for a status that named nothing (a 400, or the
   status-less SSE `error` payload)."
  [status body]
  (cond
    (#{401 403} status) :auth
    (= 429 status) :rate-limited
    (= 408 status) :timeout
    (re-find #"(?i)context.{0,30}(length|window|limit)|context_length_exceeded" (str body)) :context-length
    (and status (>= status 500)) :overloaded
    (re-find #"rate_limit" (str body)) :rate-limited
    (re-find #"server_error|overloaded" (str body)) :overloaded
    :else :invalid-request))

;;; ---------------------------------------------------------------------------
;;; SSE parsing

(defn- parse-stop-reason
  "Derives a stop-reason keyword from the completed-response map and accumulated items."
  [response items]
  (let [status            (:status response)
        incomplete-reason (get-in response [:incomplete_details :reason])]
    (cond
      (and (= status "incomplete")
        (= incomplete-reason "content_filter")) :refusal
      (and (= status "incomplete")
        (= incomplete-reason "max_output_tokens")) :max_tokens
      (some #(= "function_call" (:type %)) items) :tool_use
      ;; Everything else is a normal end of turn. There is deliberately no
      ;; `failed` clause: `response.failed` is latched as :error by
      ;; `step-stream-acc` and thrown by `finalize-stream-acc` before this
      ;; runs, and `:error` is not a member of `types/StopReason` — returning
      ;; it would surface a provider failure as an invalid-response error.
      :else :end_turn)))

(defn- new-stream-acc
  "Fresh accumulator for the Codex SSE parser."
  ([] (new-stream-acc default-max-sse-event-chars))
  ([max-sse-event-chars]
   (when-not (pos-int? max-sse-event-chars)
     (throw (ex-info ":max-sse-event-chars must be a positive integer" {})))
   {:items-by-index {}                                       ; map of output_index -> item
    :item-order     []                                       ; ordered output_index values
    :done-indices   #{}                                      ; indices confirmed by output_item.done
    :completed-resp nil
    :data-lines     []
    :data-chars     0
    :max-sse-event-chars max-sse-event-chars
    :first-line?    true}))

(defn- step-stream-acc
  "Fold one SSE event payload into accumulator `acc`."
  [acc parsed]
  (case (:type parsed)
    ("response.output_item.added"
      "response.output_item.done")
    (let [item (:item parsed)
          idx  (:output_index parsed 0)]
      (cond-> (assoc-in acc [:items-by-index idx] item)
        (not (contains? (:items-by-index acc) idx))
        (update :item-order conj idx)

        (= "response.output_item.done" (:type parsed))
        (update :done-indices conj idx)))

    ("response.completed" "response.incomplete")
    (assoc acc :completed-resp (:response parsed))

    ("error" "response.failed")
    ;; Transports isolate on-line exceptions, so retain errors until finalization.
    (assoc acc :error parsed)

    ;; Final items are authoritative; deltas are delivered separately.
    acc))

(defn- dispatch-sse-event!
  "Dispatch the buffered data fields as one event, clearing them before callbacks."
  [acc-atom on-delta]
  (let [data (str/join "\n" (:data-lines @acc-atom))]
    (swap! acc-atom assoc :data-lines [] :data-chars 0)
    (when (and (seq data) (not= data "[DONE]"))
      (when-let [parsed (try (json/parse-string data true)
                            (catch Throwable _ nil))]
        (swap! acc-atom step-stream-acc parsed)
        (when on-delta
          (when-let [delta
                     (case (:type parsed)
                       "response.output_text.delta" {:type :text-delta :text (:delta parsed)}
                       ("response.reasoning_summary_text.delta" "response.reasoning_text.delta")
                       {:type :thinking-delta :text (:delta parsed)}
                       ("response.completed" "response.incomplete")
                       (when-let [usage (get-in parsed [:response :usage])]
                         {:type :usage
                          :usage {:input-tokens (:input_tokens usage 0)
                                  :output-tokens (:output_tokens usage 0)
                                  :cache-read-input-tokens (get-in usage [:input_tokens_details :cached_tokens] 0)
                                  :cache-creation-input-tokens 0}})
                       nil)]
            (try (on-delta delta) (catch Throwable _ nil))))))))

(defn- process-sse-line!
  "Buffer SSE data fields until a blank line. Strip only the optional single
   space after the colon; comments and other fields do not dispatch events.
   Readers strip CRLF; tolerate a trailing CR from host line transports too."
  ([acc-atom line] (process-sse-line! acc-atom line nil))
  ([acc-atom line on-delta]
   (when-not (:stream-error @acc-atom)
     (let [line (cond-> line
                  (and (:first-line? @acc-atom) (str/starts-with? line "\uFEFF")) (subs 1))
           line (if (str/ends-with? line "\r") (subs line 0 (dec (count line))) line)]
       (swap! acc-atom assoc :first-line? false)
       (if (empty? line)
         (dispatch-sse-event! acc-atom on-delta)
         (let [colon (str/index-of line ":")
               field (if colon (subs line 0 colon) line)
               value (if colon (subs line (inc colon)) "")]
           (when (= "data" field)
             (let [value (if (str/starts-with? value " ") (subs value 1) value)
                   size  (+ (:data-chars @acc-atom) (count value) 1)
                   limit (:max-sse-event-chars @acc-atom)]
               (if (> size limit)
                 ;; Do not throw from on-line: transports isolate its exceptions.
                 (swap! acc-atom assoc :data-lines [] :data-chars 0
                   :stream-error (proto/llm-error :invalid-request "Responses SSE event exceeds configured size limit"
                                   {:data {:reason :sse-event-too-large :max-sse-event-chars limit}}))
                 (swap! acc-atom #(-> % (update :data-lines conj value) (assoc :data-chars size))))))))))))

(defn- finalize-stream-acc
  "Build the public result map from a completed accumulator."
  [{:keys [items-by-index item-order done-indices completed-resp error stream-error]}]
  (when stream-error (throw stream-error))
  (when error
    (throw (proto/llm-error (error-category nil error) "Responses SSE stream reported failure"
             {:data {:error-payload error}})))
  (when-not completed-resp
    (throw (proto/llm-error :transport "Responses SSE stream ended before a terminal response")))
  ;; Subscription endpoints can report output: [] after emitting complete
  ;; items. Only `output_item.done`-confirmed indices are replayed: an item
  ;; seen solely via `output_item.added` still carries partial content — a
  ;; function_call's `arguments` is a JSON fragment there — and handing that
  ;; to the tool layer is worse than dropping it.
  (let [confirmed (filter done-indices (sort item-order))
        items     (if (seq (:output completed-resp))
                    (:output completed-resp)
                    (mapv items-by-index confirmed))]
    (when (and (empty? (:output completed-resp))
            (< (count confirmed) (count item-order)))
      ;; Rare, and otherwise invisible: the turn simply comes back short.
      (log/debug "[openai-codex] terminal carried no output and"
        (- (count item-order) (count confirmed))
        "output item(s) were never confirmed by output_item.done; dropped rather than replayed partial"))
    {:items       items
     :usage       (or (:usage completed-resp) {})
     :stop-reason (parse-stop-reason completed-resp items)
     :model       (:model completed-resp)}))

(defn- parse-sse-stream!
  "Drive an SSE `BufferedReader` to completion, returning the result map.

   Test-only convenience: lets tests feed a synthetic `BufferedReader`
   without going through `http-transport`. Production code uses
   `process-sse-line!` per line via the streaming transport."
  [^java.io.BufferedReader reader]
  (let [acc (atom (new-stream-acc))]
    (loop []
      (let [line (.readLine reader)]
        (when (some? line)
          (process-sse-line! acc line)
          (recur))))
    (dispatch-sse-event! acc nil)
    (finalize-stream-acc @acc)))

;;; ---------------------------------------------------------------------------
;;; HTTP request

(>defn post-responses-stream!
  "POSTs `body` to the Codex Responses endpoint and parses the SSE stream.

Options map:
* `:body`           — Clojure map to serialize as JSON request body (required)
* `:access-token`   — OAuth bearer token (OAuth path; mutually exclusive with
                      `:api-key`)
* `:account-id`     — ChatGPT account ID header value (OAuth path)
* `:api-key`        — plain API-key bearer auth (alternative path; suppresses
                      every ChatGPT-specific header)
* `:url`            — full endpoint URL (api-key path; defaults to the ChatGPT
                      backend URL for the OAuth path)
* `:timeout-ms`     — HTTP timeout in milliseconds (default 180000)
* `:on-delta`       — callback for text, thinking and cumulative usage deltas.
* `:headers`        — explicit auth headers; bypasses stored-OAuth header requirements.
* `:extra-headers`  — additional headers merged into the request.
* `:max-sse-event-chars` — positive per-event data ceiling (default 8388608),
                       including one newline per data field. Overflow latches
                       non-retryable :invalid-request / :sse-event-too-large,
                       clears buffered data and ignores subsequent lines.
* `:http-transport` — `escapement.llm.http-transport/HttpTransport`. Defaults
                      to `(http-transport/default-transport)`.

Returns `{:items [...] :usage {...} :stop-reason :keyword :model string-or-nil}`.

Error handling:
- 401: throws ex-info with `:retry? true` (caller should refresh and retry)
- All failures carry `:llm/category`; non-2xx also carry `:llm/status`,
  legacy `:status` and `:body`. `:retry?` refers ONLY to stored OAuth refresh,
  not the engine's bounded retry policy (which uses the category)."
  [{:keys [body url api-key access-token account-id timeout-ms http-transport
           headers extra-headers on-delta max-sse-event-chars]}]
  [[:map
    [:body :map]
    [:access-token {:optional true} :string]
    [:account-id {:optional true} :string]
    [:api-key {:optional true} :string]
    [:url {:optional true} :string]
    [:timeout-ms {:optional true} pos-int?]
    [:max-sse-event-chars {:optional true} pos-int?]
    [:http-transport {:optional true} :any]]
   => :map]
  (let [transport (or http-transport (ht/default-transport))
        headers   (merge
                    (cond
                      (some? headers)
                      (merge {"accept" "text/event-stream" "content-type" "application/json"} headers)
                      api-key
                      {"Authorization" (str "Bearer " api-key)
                       "accept"        "text/event-stream"
                       "content-type"  "application/json"}
                      :else
                      (do
                        (assert (and access-token account-id)
                          "post-responses-stream!: OAuth path requires :access-token and :account-id")
                        {"Authorization"      (str "Bearer " access-token)
                         "chatgpt-account-id"  account-id
                         "OpenAI-Beta"        "responses=experimental"
                         "originator"         "codex_cli_rs"
                         "accept"             "text/event-stream"
                         "content-type"       "application/json"}))
                    extra-headers)
        req       {:url        (or url RESPONSES-URL)
                   :method     :post
                   :headers    headers
                   :body       (json/generate-string body)
                   :timeout-ms (or timeout-ms 180000)}
        acc       (atom (new-stream-acc (or max-sse-event-chars default-max-sse-event-chars)))
        on-line   (fn [line] (process-sse-line! acc line on-delta))
        {:keys [status] body-str :body}
                  (try (p/await! (ht/request-streaming transport req on-line))
                       (catch Throwable e
                          (cond
                            (:stream-error @acc) (throw (:stream-error @acc))
                            (proto/error-category e) (throw e)
                            :else (throw (proto/llm-error
                                    (if (re-find #"(?i)timeout|timed out" (str (ex-message e))) :timeout :transport)
                                    "Responses HTTP transport failed" {:cause e})))))]
    (when-not (and (>= status 200) (< status 300))
      (throw (proto/llm-error (error-category status body-str)
               (str "Responses backend error: HTTP " status)
               {:status status
                :data {:status status :body (or body-str "")
                       :retry? (and (= 401 status) (nil? api-key))}})))
    ;; Some Responses endpoints close directly after the terminal data field.
    (dispatch-sse-event! acc on-delta)
    (finalize-stream-acc @acc)))
