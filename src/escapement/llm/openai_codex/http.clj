(ns escapement.llm.openai-codex.http
  "Streaming HTTP client for the OpenAI Responses API via the ChatGPT backend.

  Sends POST requests with SSE streaming to
  `https://chatgpt.com/backend-api/codex/responses` and parses the
  event stream into a structured result map.

  Babashka-compatible: uses `babashka.http-client` with `:as :stream`
  and stdlib `BufferedReader` for line-by-line SSE parsing."
  (:require
   [babashka.http-client :as http]
   [cheshire.core :as json]
   [clojure.string :as str]
   [com.fulcrologic.guardrails.malli.core :refer [>defn =>]])
  (:import
   (java.io BufferedReader InputStreamReader)))

;;; ---------------------------------------------------------------------------
;;; Constants

(def ^:private RESPONSES-URL "https://chatgpt.com/backend-api/codex/responses")

;;; ---------------------------------------------------------------------------
;;; SSE parsing

(defn- parse-stop-reason
  "Derives a stop-reason keyword from the completed-response map and accumulated items."
  [response items]
  (let [status           (:status response)
        incomplete-reason (get-in response [:incomplete_details :reason])]
    (cond
      (some #(= "function_call" (:type %)) items)    :tool_use
      (= status "completed")                          :end_turn
      (and (= status "incomplete")
           (= incomplete-reason "max_output_tokens")) :max_tokens
      (= status "failed")                             :error
      :else                                           :end_turn)))

(defn- parse-sse-stream!
  "Parses a SSE `BufferedReader` into a result map.

  Accumulates output items by tracking `output_item.added` / `output_item.done`
  events; collects usage and stop-reason from `response.completed`.

  Returns `{:items [...] :usage {...} :stop-reason :keyword :model string-or-nil}`."
  [^BufferedReader reader]
  ;; items-by-index: map of output_index -> item (done supersedes added)
  (let [items-by-index (atom {})
        item-order     (atom [])          ; ordered output_index values
        completed-resp (atom nil)]
    (loop []
      (let [line (.readLine reader)]
        (when (some? line)
          (cond
            (str/starts-with? line "data: ")
            (let [data (subs line 6)]
              (when (and (seq data) (not= data "[DONE]"))
                (let [parsed (try (json/parse-string data true) (catch Throwable _ nil))]
                  (when parsed
                    (case (:type parsed)
                      "response.output_item.added"
                      (let [item  (:item parsed)
                            idx   (:output_index parsed 0)]
                        (when-not (contains? @items-by-index idx)
                          (swap! item-order conj idx))
                        (swap! items-by-index assoc idx item))

                      "response.output_item.done"
                      (let [item (:item parsed)
                            idx  (:output_index parsed 0)]
                        (when-not (contains? @items-by-index idx)
                          (swap! item-order conj idx))
                        (swap! items-by-index assoc idx item))

                      "response.completed"
                      (reset! completed-resp (:response parsed))

                      "error"
                      (throw (ex-info "SSE stream reported error"
                                      {:error-payload parsed}))

                      ;; ignore deltas and unknown event types
                      nil))))
              (recur))

            :else
            (recur)))))
    (let [items     (mapv @items-by-index @item-order)
          completed @completed-resp
          usage     (:usage completed)
          model     (:model completed)]
      {:items       items
       :usage       (or usage {})
       :stop-reason (parse-stop-reason completed items)
       :model       model})))

;;; ---------------------------------------------------------------------------
;;; HTTP request

(>defn post-responses-stream!
       "POSTs `body` to the Codex Responses endpoint and parses the SSE stream.

  Options map:
    * `:body`         — Clojure map to serialize as JSON request body (required)
    * `:access-token` — OAuth bearer token (required)
    * `:account-id`   — ChatGPT account ID header value (required)
    * `:timeout-ms`   — HTTP timeout in milliseconds (default 180000)

  Returns `{:items [...] :usage {...} :stop-reason :keyword :model string-or-nil}`.

  Error handling:
    - 401: throws ex-info with `:retry? true` (caller should refresh and retry)
    - 429: throws ex-info with `:retry? false` (surface to user)
    - Other non-2xx: throws ex-info with `:status` and `:body`"
       [{:keys [body access-token account-id timeout-ms]}]
       [[:map
         [:body :map]
         [:access-token :string]
         [:account-id :string]
         [:timeout-ms {:optional true} pos-int?]]
        => :map]
       (let [headers {"Authorization"    (str "Bearer " access-token)
                      "chatgpt-account-id" account-id
                      "OpenAI-Beta"      "responses=experimental"
                      "originator"       "codex_cli_rs"
                      "accept"           "text/event-stream"
                      "content-type"     "application/json"}
             resp    (http/post RESPONSES-URL
                                {:headers headers
                                 :body    (json/generate-string body)
                                 :as      :stream
                                 :timeout (or timeout-ms 180000)
                                 :throw   false})
             status  (:status resp)]
         (when-not (and (>= status 200) (< status 300))
           (let [body-str (try
                            (if (instance? java.io.InputStream (:body resp))
                              (slurp (:body resp))
                              (str (:body resp)))
                            (catch Throwable _ ""))]
             (case status
               401 (throw (ex-info "OAuth token rejected by ChatGPT backend"
                                   {:status 401 :body body-str :retry? true}))
               429 (throw (ex-info "Subscription rate limit reached"
                                   {:status 429 :body body-str :retry? false}))
               (throw (ex-info (str "Codex backend error: HTTP " status)
                               {:status status :body body-str})))))
         (with-open [reader (BufferedReader. (InputStreamReader. (:body resp) "UTF-8"))]
           (parse-sse-stream! reader))))
