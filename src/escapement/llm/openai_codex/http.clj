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
    [com.fulcrologic.statecharts.promise :as p]))

;;; ---------------------------------------------------------------------------
;;; Constants

(def ^:private RESPONSES-URL "https://chatgpt.com/backend-api/codex/responses")

;;; ---------------------------------------------------------------------------
;;; SSE parsing

(defn- parse-stop-reason
  "Derives a stop-reason keyword from the completed-response map and accumulated items."
  [response items]
  (let [status            (:status response)
        incomplete-reason (get-in response [:incomplete_details :reason])]
    (cond
      (some #(= "function_call" (:type %)) items) :tool_use
      (= status "completed") :end_turn
      (and (= status "incomplete")
        (= incomplete-reason "max_output_tokens")) :max_tokens
      (= status "failed") :error
      :else :end_turn)))

(defn- new-stream-acc
  "Fresh accumulator for the Codex SSE parser."
  []
  {:items-by-index {}                                       ; map of output_index -> item
   :item-order     []                                       ; ordered output_index values
   :completed-resp nil})

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
        (update :item-order conj idx)))

    "response.completed"
    (assoc acc :completed-resp (:response parsed))

    "error"
    (throw (ex-info "SSE stream reported error" {:error-payload parsed}))

    ;; ignore deltas and unknown event types
    acc))

(defn- process-sse-line!
  "Fold one SSE line into the atom-held accumulator. Non-`data:` lines and
   `[DONE]` are no-ops. The streaming transport calls this per response line."
  [acc-atom line]
  (when (str/starts-with? line "data: ")
    (let [data (subs line 6)]
      (when (and (seq data) (not= data "[DONE]"))
        (when-let [parsed (try (json/parse-string data true)
                               (catch Throwable _ nil))]
          (swap! acc-atom step-stream-acc parsed))))))

(defn- finalize-stream-acc
  "Build the public result map from a completed accumulator."
  [{:keys [items-by-index item-order completed-resp]}]
  (let [items (mapv items-by-index item-order)]
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
* `:http-transport` — `escapement.llm.http-transport/HttpTransport`. Defaults
                      to `(http-transport/default-transport)`.

Returns `{:items [...] :usage {...} :stop-reason :keyword :model string-or-nil}`.

Error handling:
- 401: throws ex-info with `:retry? true` (caller should refresh and retry)
- 429: throws ex-info with `:retry? false` (surface to user)
- Other non-2xx: throws ex-info with `:status` and `:body`"
  [{:keys [body url api-key access-token account-id timeout-ms http-transport]}]
  [[:map
    [:body :map]
    [:access-token {:optional true} :string]
    [:account-id {:optional true} :string]
    [:api-key {:optional true} :string]
    [:url {:optional true} :string]
    [:timeout-ms {:optional true} pos-int?]
    [:http-transport {:optional true} :any]]
   => :map]
  (let [transport (or http-transport (ht/default-transport))
        headers   (if api-key
                    {"Authorization" (str "Bearer " api-key)
                     "accept"        "text/event-stream"
                     "content-type"  "application/json"}
                    (do
                      (assert (and access-token account-id)
                        "post-responses-stream!: OAuth path requires :access-token and :account-id")
                      {"Authorization"      (str "Bearer " access-token)
                       "chatgpt-account-id" account-id
                       "OpenAI-Beta"        "responses=experimental"
                       "originator"         "codex_cli_rs"
                       "accept"             "text/event-stream"
                       "content-type"       "application/json"}))
        req       {:url        (or url RESPONSES-URL)
                   :method     :post
                   :headers    headers
                   :body       (json/generate-string body)
                   :timeout-ms (or timeout-ms 180000)}
        acc       (atom (new-stream-acc))
        on-line   (fn [line] (process-sse-line! acc line))
        {:keys [status] body-str :body}
                  (p/await! (ht/request-streaming transport req on-line))]
    (when-not (and (>= status 200) (< status 300))
      (case status
        401 (throw (ex-info "Bearer token rejected by Responses backend"
                     {:status 401 :body (or body-str "") :retry? (nil? api-key)}))
        429 (throw (ex-info "Rate limit reached"
                     {:status 429 :body (or body-str "") :retry? false}))
        (throw (ex-info (str "Codex backend error: HTTP " status)
                 {:status status :body (or body-str "")}))))
    (finalize-stream-acc @acc)))
