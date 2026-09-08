(ns escapement.llm.embedded-provider-contracts-test
  "Cross-cutting contracts an embedding host depends on: host-owned auth,
   endpoint profiles, streaming, error categories and per-request routing.

   Deliberately one namespace rather than per-backend files: every
   specification here asserts a promise made to a HOST across the assembly
   seam (`lib` schema → `providers` descriptor → concrete backend → wire), and
   the per-backend suites cannot see that seam. Backend-internal behaviour
   stays in `openai_codex_test` / `openai_codex/http_test` / `providers_test`."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [com.fulcrologic.statecharts.promise :as p]
    [escapement.cli :as cli]
    [escapement.lib :as lib]
    [escapement.llm :as llm]
    [escapement.llm.http-transport :as ht]
    [escapement.llm.openai-codex :as codex]
    [escapement.llm.openai-codex.auth :as saved-auth]
    [escapement.llm.protocol :as proto]
    [escapement.llm.providers :as providers]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(def request {:model "gpt-5.1" :max-tokens 123
              :messages [{:role :user :content [{:type :text :text "Hello"}]}]})
(def output [{:type "message" :content [{:type "output_text" :text "Hello"}]}])
(def terminal {:type "response.completed"
               :response {:status "completed" :model "reported-model" :output output
                          :usage {:input_tokens 8 :output_tokens 2
                                  :input_tokens_details {:cached_tokens 3}}}})

(defn mock-transport [f]
  (reify ht/HttpTransport
    (request [_ req] (p/resolved (f req nil)))
    (request-streaming [_ req on-line] (p/resolved (f req on-line)))))

(defn emit! [on-line events]
  (doseq [event events]
    ;; Match the real transport: it isolates per-line callback exceptions.
    (try
      (on-line (str "data: " (json/generate-string event)))
      (on-line "")
      (catch Throwable _ nil)))
  {:status 200})

(defn caught [f]
  (try (p/await! (f)) nil (catch Throwable e e)))

(specification "an injected z.ai vision credential defaults inside the provider's own limit"
  ;; Real provider assembly and run-turn default resolution; only HTTP is mocked.
  ;; Native ZAI live evidence: glm-4.6V rejects max_tokens 131072 (1210), cap 32768.
  (doseq [model      ["glm-4.6V" "glm-4.6v"]
          streaming? [false true]
          budget     [nil 8192]]
    (let [sent    (atom [])
          urls    (atom [])
          backend (providers/build-injected-credentials-backend
                    [{:provider :z-ai :model model :api-key "mock"
                      :http-transport (mock-transport
                                        (fn [req cb]
                                          (swap! sent conj (json/parse-string (:body req) true))
                                          (swap! urls conj (:url req))
                                          (if cb
                                            (emit! cb [{:type "message_start" :message {:model model :usage {:input_tokens 1}}}
                                                       {:type "content_block_start" :index 0 :content_block {:type "text" :text "ok"}}
                                                       {:type "content_block_stop" :index 0}
                                                       {:type "message_delta" :delta {:stop_reason "end_turn"} :usage {:output_tokens 1}}
                                                       {:type "message_stop"}])
                                            {:status 200 :body (json/generate-string
                                                                 {:model model :content [{:type "text" :text "ok"}]
                                                                  :stop_reason "end_turn" :usage {:input_tokens 1 :output_tokens 1}})})))}]
                    [])
          result  (llm/run-turn
                    {:backend backend :aliases {:vision [{:provider :z-ai :model model}]} :preferences [:vision]
                     :hooks {:delta-sink (fn [_ _] (when streaming? (fn [_])))}}
                    (cond-> {:model :vision :resilience {:max-retries 0}}
                      budget (assoc :max-tokens budget))
                    (:messages request) [])]
      (assertions
        "the turn succeeds"
        (:status result) => :ok
        (llm/response-text (:response result)) => "ok"
        "on the Messages endpoint"
        (first @urls) => "https://api.z.ai/api/anthropic/v1/messages"
        "one HTTP call"
        (count @sent) => 1
        "the catalog alias must not rewrite the wire model"
        (:model (first @sent)) => model
        "and the default budget comes from the vision row, not the text prefix"
        (:max_tokens (first @sent)) => (or budget 32768))
      ;; Constructor's own omitted-model/omitted-budget path stays safe too.
      (p/await! (proto/send-turn* backend (dissoc request :model :max-tokens)
                  (when streaming? (fn [_]))))
      (assertions
        "the constructor default is capped by the same row"
        (:model (last @sent)) => model
        (:max_tokens (last @sent)) => 8192))))

(def codex-empty-terminal-events
  ;; Sanitized event sequence from trace-codex-text-64972ded: HTTP 200,
  ;; a two-character output_item.done, then response.completed with output [].
  [{:type "response.created" :response {:status "in_progress" :output []}}
   {:type "response.in_progress" :response {:status "in_progress" :output []}}
   {:type "response.output_item.added" :output_index 0
    :item {:id "msg-1" :type "message" :role "assistant" :content []}}
   {:type "response.content_part.added" :output_index 0 :content_index 0
    :part {:type "output_text" :text ""}}
   {:type "response.output_text.delta" :output_index 0 :content_index 0 :delta "hi"}
   {:type "response.output_text.done" :output_index 0 :content_index 0 :text "hi"}
   {:type "response.content_part.done" :output_index 0 :content_index 0
    :part {:type "output_text" :text "hi"}}
   {:type "response.output_item.done" :output_index 0
    :item {:id "msg-1" :type "message" :role "assistant"
           :content [{:type "output_text" :text "hi"}]}}
   {:type "response.completed" :response {:status "completed" :model "gpt-5.6-sol" :output []
                                          :usage {:input_tokens 33 :output_tokens 5}}}])

(specification "a Responses terminal carrying output [] preserves the collected text"
  (doseq [terminal-output [::absent nil [] output]]
    (let [calls    (atom 0)
          deltas   (atom [])
          events   (update-in codex-empty-terminal-events [8 :response]
                     #(if (= ::absent terminal-output) (dissoc % :output) (assoc % :output terminal-output)))
          backend  (codex/new-backend
                     {:auth-fn (fn [_] {:headers {"Authorization" "Bearer mock" "chatgpt-account-id" "account"}})
                      :http-transport (mock-transport (fn [_ cb] (swap! calls inc) (emit! cb events)))})
          streamed (p/await! (proto/stream-turn backend request #(swap! deltas conj %)))
          sent     (p/await! (proto/send-turn backend request))]
      (assertions
        "streaming and non-streaming agree"
        streamed => sent
        "a non-empty terminal wins; an empty one falls back to the collected item"
        (llm/response-text sent) => (if (= output terminal-output) "Hello" "hi")
        (:stop-reason sent) => :end_turn
        (:model sent) => "gpt-5.6-sol"
        "usage survives either way"
        (:usage sent) => {:input-tokens 33 :output-tokens 5
                          :cache-read-input-tokens 0 :cache-creation-input-tokens 0}
        "deltas were delivered live"
        (:text (first @deltas)) => "hi"
        "exactly one HTTP call per turn"
        @calls => 2))))

(specification "an empty terminal keeps item order and the final tool arguments"
  (doseq [streaming?  [false true]
          incomplete? [false true]]
    (let [text    {:type "message" :role "assistant" :content [{:type "output_text" :text "hi"}]}
          tool-a  {:type "function_call" :call_id "call-a" :name "first" :arguments "{\"a\":1}"}
          tool-b  {:type "function_call" :call_id "call-b" :name "second" :arguments "{\"b\":2}"}
          backend (codex/new-backend
                    {:api-key "mock" :base-url "https://mock/v1"
                     :http-transport (mock-transport
                                       (fn [_ cb]
                                         (emit! cb
                                           [{:type "response.output_item.added" :output_index 2 :item (assoc tool-b :arguments "{\"b\":")}
                                            {:type "response.output_item.added" :output_index 0 :item (assoc text :content [])}
                                            {:type "response.output_item.added" :output_index 1 :item (assoc tool-a :arguments "")}
                                            {:type "response.output_item.done" :output_index 1 :item tool-a}
                                            {:type "response.output_item.done" :output_index 2 :item tool-b}
                                            {:type "response.output_item.done" :output_index 0 :item text}
                                            {:type (if incomplete? "response.incomplete" "response.completed")
                                             :response {:status (if incomplete? "incomplete" "completed") :output []
                                                        :incomplete_details (when incomplete? {:reason "max_output_tokens"})}}])))})
          result  (p/await! (proto/send-turn* backend request (when streaming? (fn [_]))))]
      (assertions
        "the terminal's own reason still decides the stop reason"
        (:stop-reason result) => (if incomplete? :max_tokens :tool_use)
        "items replay in output_index order, each with its FINAL arguments"
        (:content result) => [{:type :text :text "hi"}
                              {:type :tool_use :id "call-a" :name "first" :input {:a 1}}
                              {:type :tool_use :id "call-b" :name "second" :input {:b 2}}]))))

(specification "the CLI's stored OAuth refreshes and persists exactly once"
  (doseq [streaming?    [false true]
          second-status [200 401]]
    (let [stored     (atom {:access-token "old" :account-id "account" :refresh-token "refresh-old"})
          refreshed  {:access-token "new" :account-id "account" :refresh-token "refresh-new"}
          operations (atom [])
          transport  (mock-transport
                       (fn [req cb]
                         (let [token (get-in req [:headers "Authorization"])]
                           (swap! operations conj [:http token])
                           (if (or (= token "Bearer old") (= second-status 401))
                             {:status 401 :body "expired"}
                             (emit! cb codex-empty-terminal-events)))))]
      (with-redefs [cli/build-codex-backend (fn [opts] (codex/new-backend (assoc opts :http-transport transport)))
                    saved-auth/get-auth! (fn [] (swap! operations conj :get) @stored)
                    saved-auth/load-auth! (fn [] (swap! operations conj :load) @stored)
                    saved-auth/refresh-token! (fn [token] (swap! operations conj [:refresh token]) refreshed)
                    saved-auth/save-auth! (fn [value] (swap! operations conj [:save value]) (reset! stored value))
                    saved-auth/login-flow! (fn [] (throw (ex-info "Browser login must not run" {})))]
        (let [backend (:backend (#'cli/make-backend {:backend "codex"}))
              result  (try (p/await! (proto/send-turn* backend request (when streaming? (fn [_]))))
                           (catch Throwable e e))]
          (assertions
            "the CLI route opts into saved credentials"
            (:allow-stored-auth? backend) => true
            "a token expiring between load and use refreshes once, then the turn runs"
            (if (= second-status 200) (llm/response-text result) (proto/error-category result))
            => (if (= second-status 200) "hi" :auth)
            "and the refresh happens exactly once, in this order"
            @operations => [:get [:http "Bearer old"] :load [:refresh "refresh-old"] [:save refreshed]
                            :get [:http "Bearer new"]]
            (deref stored) => refreshed)
          (let [before   @operations
                embedded (providers/build-injected-credentials-backend [{:provider :codex}] [])
                error    (caught #(proto/send-turn* embedded request (when streaming? (fn [_]))))]
            (assertions
              "an embedded Codex credential with no host auth fails closed"
              (proto/error-category error) => :auth
              "and must not load, refresh or persist stored CLI credentials"
              @operations => before)))))))

(specification "the SSE size ceiling survives injection and is never retried"
  (let [calls   (atom 0)
        drained? (atom false)
        backend (providers/build-injected-credentials-backend
                  [{:provider :codex :api-key "mock" :base-url "https://mock/v1" :max-sse-event-chars 64
                    :http-transport (mock-transport
                                      (fn [_ cb]
                                        (swap! calls inc)
                                        ;; Exercise the real transport's exception-isolation behavior.
                                        (doseq [line (concat (repeat 1000 "data:abcdefgh") [""])]
                                          (try (cb line) (catch Throwable _ nil)))
                                        (emit! cb codex-empty-terminal-events)
                                        (reset! drained? true)
                                        {:status 200}))}]
                  [])]
    (doseq [streaming? [false true]]
      (let [deltas (atom [])
            error  (caught #(proto/send-turn* backend request (when streaming? (fn [d] (swap! deltas conj d)))))]
        (assertions
          "overflow is a non-retryable invalid request, carrying its limit"
          (proto/error-category error) => :invalid-request
          (:reason (ex-data error)) => :sse-event-too-large
          (:max-sse-event-chars (ex-data error)) => 64
          "the whole stream is still drained"
          @drained? => true
          "and no delta escapes from a stream that failed"
          @deltas => [])))
    ;; Count only what the retry policy itself does — the two turns above
    ;; already moved this atom, so measure a delta, not a running total.
    (reset! calls 0)
    (let [result (llm/run-turn
                   {:backend backend :aliases {:primary [{:provider :codex :model "test"}]} :preferences [:primary]}
                   {:model :primary :resilience {:max-retries 2 :backoff-ms 0}}
                   (:messages request) [])]
      (assertions
        "the run gives up rather than looping"
        (:status result) => :exhausted
        "overflow is deterministic: the same oversized event is sent once, not retried"
        @calls => 1))))

(specification "a latched size failure survives a later transport error"
  (let [backend (codex/new-backend
                  {:api-key "mock" :base-url "https://mock/v1" :max-sse-event-chars 1
                   :http-transport (mock-transport
                                     (fn [_ cb]
                                       (try (cb "data:oversized") (catch Throwable _ nil))
                                       (throw (ex-info "timeout while draining" {}))))})
        error   (caught #(proto/send-turn backend request))]
    (assertions
      "the first, more specific failure is the one reported"
      (proto/error-category error) => :invalid-request
      (:reason (ex-data error)) => :sse-event-too-large)))

(specification "the Responses backend streams"
  (let [deltas    (atom [])
        mid-flight (atom nil)
        transport (mock-transport
                    (fn [_ on-line]
                      (emit! on-line [{:type "response.output_text.delta" :delta "Hel"}
                                      {:type "response.output_text.delta" :delta "lo"}
                                      {:type "response.reasoning_summary_text.delta" :delta "Thinking"}])
                      (reset! mid-flight (apply str (map :text (filter #(= :text-delta (:type %)) @deltas))))
                      (emit! on-line [terminal])))
        backend   (codex/new-backend {:api-key "mock" :base-url "https://mock/v1" :http-transport transport})
        streamed  (p/await! (proto/stream-turn backend request #(swap! deltas conj %)))
        sent      (p/await! (proto/send-turn backend request))]
    (assertions
      "it declares the capability"
      (proto/streaming? backend) => true
      "text arrives BEFORE the terminal event, not after"
      @mid-flight => "Hello"
      "streaming and non-streaming produce the same Response"
      streamed => sent
      (get-in streamed [:content 0 :text]) => "Hello"
      (:model streamed) => "reported-model"
      (get-in streamed [:usage :cache-read-input-tokens]) => 3
      "text, reasoning and terminal usage all reach the sink"
      (mapv :type @deltas) => [:text-delta :text-delta :thinking-delta :usage]
      (:usage (last @deltas)) => (:usage streamed)
      "and a throwing delta callback cannot abort the turn"
      (p/await! (proto/stream-turn backend request (fn [_] (throw (ex-info "UI failed" {}))))) => sent)))

(specification "the endpoint profile decides model id, output cap and dialect headers"
  (doseq [[profile model cap] [[:responses "gpt-5.1" 123] [:chatgpt "gpt-5.6-sol" nil]]
          auth                [:auth-fn :api-key]]
    (let [seen    (atom nil)
          headers (atom nil)
          backend (codex/new-backend
                    (cond-> {:base-url "https://proxy/v1" :endpoint-profile profile
                             :http-transport (mock-transport (fn [req on-line]
                                                               (reset! seen (json/parse-string (:body req) true))
                                                               (reset! headers (:headers req))
                                                               (emit! on-line [terminal])))}
                      (= :auth-fn auth) (assoc :auth-fn (fn [_] {:headers {"Authorization" "Bearer host"}}))
                      (= :api-key auth) (assoc :api-key "sk-mock")))]
      (p/await! (proto/send-turn backend (assoc request :reasoning {:effort :none})))
      (assertions
        "only the ChatGPT profile remaps a model id"
        (:model @seen) => model
        "only the ChatGPT profile drops the output cap"
        (:max_output_tokens @seen) => cap
        "explicit off reaches the wire either way"
        (:reasoning @seen) => {:effort "none"}
        ;; The profile shapes the BODY unconditionally, so the headers that tell
        ;; the endpoint which dialect that body speaks must not depend on how
        ;; the request was authenticated.
        "the ChatGPT dialect headers follow the profile, not the auth mode"
        (get @headers "OpenAI-Beta") => (when (= :chatgpt profile) "responses=experimental")
        (get @headers "originator") => (when (= :chatgpt profile) "codex_cli_rs")))))

(specification "a caller's own extra headers outrank the profile's"
  (let [headers (atom nil)
        backend (codex/new-backend
                  {:api-key "sk-mock" :base-url "https://proxy/v1" :endpoint-profile :chatgpt
                   :extra-headers {"originator" "my-host"}
                   :http-transport (mock-transport (fn [req cb]
                                                     (reset! headers (:headers req))
                                                     (emit! cb [terminal])))})]
    (p/await! (proto/send-turn backend request))
    (assertions
      "the caller wins where they overlap"
      (get @headers "originator") => "my-host"
      "and the rest of the profile is still applied"
      (get @headers "OpenAI-Beta") => "responses=experimental")))

(specification "new-backend rejects a configuration it cannot honour"
  (let [thrown (fn [opts] (try (codex/new-backend opts) nil (catch Throwable e e)))]
    (assertions
      "a non-positive SSE ceiling"
      (some? (thrown {:base-url "https://mock/v1" :max-sse-event-chars 0})) => true
      (some? (thrown {:base-url "https://mock/v1" :max-sse-event-chars "8"})) => true
      "an unknown endpoint profile"
      (:endpoint-profile (ex-data (thrown {:base-url "https://mock/v1" :endpoint-profile :chatgtp}))) => :chatgtp
      "the :responses profile without an endpoint to talk to"
      (:reason (ex-data (thrown {:endpoint-profile :responses}))) => :missing-base-url
      "an :api-key with nowhere to send it"
      (:reason (ex-data (thrown {:api-key "sk-mock"}))) => :missing-base-url
      "while the two valid shapes construct"
      (some? (thrown {})) => false
      (some? (thrown {:base-url "https://mock/v1" :max-sse-event-chars 64})) => false)))

(specification "Responses failures carry a category the retry policy can act on"
  (doseq [[status body category] [[401 "" :auth] [403 "" :auth] [429 "" :rate-limited]
                                  [500 "" :overloaded] [503 "" :overloaded] [408 "" :timeout]
                                  [400 "context_length_exceeded" :context-length] [400 "bad model" :invalid-request]
                                  ;; A proxy reporting an upstream context overflow as a 5xx
                                  ;; must not be retried as transient congestion.
                                  [500 "context_length_exceeded" :context-length]]]
    (let [backend (codex/new-backend {:api-key "mock" :base-url "https://mock/v1"
                                      :http-transport (mock-transport (fn [_ _] {:status status :body body}))})
          error   (caught #(proto/send-turn backend request))]
      (assertions
        "the HTTP status and body together decide the category"
        (proto/error-category error) => category
        "and the status is preserved for the caller"
        (:llm/status (ex-data error)) => status)))
  (doseq [[events category] [[[] :transport]
                             [[{:type "error" :code "server_error"}] :overloaded]
                             [[{:type "response.failed" :response {:error {:code "rate_limit_exceeded"}}}] :rate-limited]]]
    (let [backend (codex/new-backend {:api-key "mock" :base-url "https://mock/v1"
                                      :http-transport (mock-transport (fn [_ cb] (emit! cb events)))})]
      (assertions
        "an in-stream failure is a categorized error, never an empty success"
        (proto/error-category (caught #(proto/send-turn backend request))) => category))))

(specification "Responses categories reach the engine's retry policy"
  (doseq [[status category] [[429 :rate-limited] [503 :overloaded]]]
    (let [calls   (atom 0)
          retries (atom [])
          backend (codex/new-backend
                    {:api-key "mock" :base-url "https://mock/v1"
                     :http-transport (mock-transport
                                       (fn [_ cb]
                                         (if (= 1 (swap! calls inc))
                                           {:status status :body "busy"}
                                           (emit! cb [terminal]))))})
          result  (llm/run-turn
                    {:backend backend
                     :aliases {:primary [{:provider :codex :model "test"}]}
                     :preferences [:primary]
                     :hooks {:on-retry #(swap! retries conj (:category %))}}
                    {:model :primary :resilience {:max-retries 1 :backoff-ms 0}}
                    (:messages request) [])]
      (assertions
        "a transient failure is retried once and then succeeds"
        (:status result) => :ok
        @calls => 2
        "and the retry is attributed to the provider's own category"
        @retries => [category])))
  (doseq [[message category] [["request timed out" :timeout] ["connection reset" :transport]]]
    (let [backend (codex/new-backend {:api-key "mock" :base-url "https://mock/v1"
                                      :http-transport (mock-transport (fn [_ _] (throw (ex-info message {}))))})]
      (assertions
        "a transport exception keeps a meaningful category too"
        (proto/error-category (caught #(proto/send-turn backend request))) => category))))

(specification "the public credential schema accepts host-auth options"
  (let [opts {:chart {} :session-id :mock
              :credentials [{:provider :codex
                             :auth-fn (fn [_] {:headers {"Authorization" "Bearer mock"}})
                             :http-timeout-ms 123 :endpoint-profile :chatgpt :max-sse-event-chars 1024
                             :extra-headers {"example" "value"}}]}]
    (assertions
      "a fully specified host-auth descriptor validates"
      (lib/validate-options opts) => nil
      "an :auth-fn that is not callable does not"
      (some? (lib/validate-options (assoc-in opts [:credentials 0 :auth-fn] "not-a-function"))) => true
      "nor does a ceiling that can never be met"
      (some? (lib/validate-options (assoc-in opts [:credentials 0 :max-sse-event-chars] 0))) => true)))

(specification "incomplete and tool-calling Responses turns"
  (doseq [[response stop content-type]
          [[{:status "incomplete" :incomplete_details {:reason "max_output_tokens"} :output output} :max_tokens :text]
           [{:status "completed" :output [{:type "function_call" :call_id "call-1" :name "test" :arguments "{\"a\":1}"}]} :tool_use :tool_use]]]
    (let [backend (codex/new-backend {:api-key "mock" :base-url "https://mock/v1"
                                      :http-transport (mock-transport
                                                        (fn [_ cb] (emit! cb [{:type (if (= stop :max_tokens) "response.incomplete" "response.completed")
                                                                               :response response}])))})
          result  (p/await! (proto/send-turn backend request))]
      (assertions
        "the stop reason is the terminal's own"
        (:stop-reason result) => stop
        (get-in result [:content 0 :type]) => content-type))))

(specification "content-filtered Responses output is a refusal"
  (doseq [streaming? [false true]
          event-type ["response.incomplete" "response.completed"]
          items      [[] output [{:type "function_call" :call_id "call-1" :name "test" :arguments "{}"}]]]
    (let [backend (codex/new-backend
                    {:api-key "mock" :base-url "https://mock/v1"
                     :http-transport (mock-transport
                                       (fn [_ cb]
                                         (emit! cb [(-> terminal
                                                      (assoc :type event-type)
                                                      (assoc-in [:response :status] "incomplete")
                                                      (assoc-in [:response :incomplete_details] {:reason "content_filter"})
                                                      (assoc-in [:response :output] items))])))})
          result  (p/await! (proto/send-turn* backend request (when streaming? (fn [_]))))]
      (assertions
        "a content filter outranks any collected tool item"
        (:stop-reason result) => :refusal
        "whatever was produced is still returned"
        (count (:content result)) => (count items)
        "and the turn's cost is reported"
        (get-in result [:usage :output-tokens]) => 2))))

(specification "a validly framed stream is never re-billed as a retry"
  (doseq [separator  ["" " " "  "]
          cr         ["" "\r"]
          terminate? [false true]]
    (let [calls    (atom 0)
          deltas   (atom [])
          lines    (mapv #(str % cr)
                     (concat [": comment" "event: ignored" "id: 1"
                              (str "data:" separator "{\"type\":\"response.output_text.delta\",")
                              ": comment between data fields" "retry: 1000"
                              (str "data:" separator "\"delta\":\"Hello\"}") ""
                              "data:[DONE]" "" ""
                              (str "data:" separator "{\"type\":\"response.completed\",")
                              (str "data:" separator "\"response\":" (json/generate-string (:response terminal)) "}")]
                       (when terminate? [""])))
          backend  (codex/new-backend
                     {:api-key "mock" :base-url "https://mock/v1"
                      :http-transport (mock-transport
                                        (fn [_ cb]
                                          (swap! calls inc)
                                          (doseq [line lines] (cb line))
                                          {:status 200}))})
          streamed (p/await! (proto/stream-turn backend request #(swap! deltas conj %)))
          sent     (p/await! (proto/send-turn backend request))
          ;; Measure the retry policy alone: the two direct turns above already
          ;; moved this atom, so zero it and count what run-turn adds.
          _        (reset! calls 0)
          result   (llm/run-turn
                     {:backend backend :aliases {:primary [{:provider :codex :model "test"}]}
                      :preferences [:primary]}
                     {:model :primary :resilience {:max-retries 2 :backoff-ms 0}}
                     (:messages request) [])]
      (assertions
        "framing quirks do not change the parsed turn"
        streamed => sent
        (get-in streamed [:content 0 :text]) => "Hello"
        (mapv :type @deltas) => [:text-delta :usage]
        (:text (first @deltas)) => "Hello"
        "and a successful turn costs exactly one HTTP call"
        (:status result) => :ok
        @calls => 1))))

(specification "host auth reaches every HTTP provider"
  (doseq [provider   [:anthropic :openai :codex :zai-coding-plan]
          streaming? [false true]]
    (component "one refresh per turn, on every wire"
      (let [contexts (atom [])
            calls    (atom [])
            backend  (providers/build-injected-credentials-backend
                       [{:provider provider :api-key "must-not-leak" :base-url "https://mock/v1"
                         :auth-fn (fn [ctx]
                                    (swap! contexts conj ctx)
                                    {:headers {"authorization" (if (:refresh? ctx) "Bearer new" "Bearer old")
                                               "chatgpt-account-id" "host-account"}})
                         :http-transport (mock-transport
                                           (fn [req cb]
                                             (swap! calls conj req)
                                             ;; Two independent turns each force exactly one refresh.
                                             (if (odd? (count @calls))
                                               {:status 401 :body "expired"}
                                               (cond
                                                 (str/ends-with? (:url req) "/responses") (emit! cb [terminal])
                                                 cb (if (= provider :anthropic)
                                                      (emit! cb [{:type "message_start" :message {:model "test" :usage {:input_tokens 1}}}
                                                                 {:type "message_delta" :delta {:stop_reason "end_turn"} :usage {:output_tokens 0}}])
                                                      (emit! cb [{:model "test" :choices [{:delta {:content "ok"} :finish_reason "stop"}]}]))
                                                 :else {:status 200
                                                        :body (json/generate-string
                                                                (if (= provider :anthropic)
                                                                  {:model "test" :content [{:type "text" :text "ok"}] :stop_reason "end_turn" :usage {}}
                                                                  {:model "test" :choices [{:message {:content "ok"} :finish_reason "stop"}] :usage {}}))}))))}]
                       [])]
        (with-redefs [saved-auth/get-auth! (fn [] (throw (ex-info "MUST NOT load/login" {})))]
          (dotimes [_ 2]
            (p/await! (proto/send-turn* backend request (when streaming? (fn [_]))))))
        (assertions
          "the callback is asked once, then once more to refresh, per turn"
          (mapv :refresh? @contexts) => [false true false true]
          "and the refreshed headers are the ones actually sent"
          (mapv #(get-in % [:headers "authorization"]) @calls)
          => ["Bearer old" "Bearer new" "Bearer old" "Bearer new"]
          "the descriptor's static key never reaches the wire"
          (every? #(not (or (contains? (:headers %) "Authorization")
                          (contains? (:headers %) "x-api-key"))) @calls) => true)))))

(specification "host auth fails closed and its refresh is bounded"
  (doseq [auth-fn [(fn [_] nil) (fn [_] {:headers {}}) (fn [_] {:headers {"Authorization" "  "}})
                   (fn [_] (throw (ex-info "secret" {:token "secret"})))]]
    (let [attempted (atom false)
          backend   (codex/new-backend {:auth-fn auth-fn
                                        :http-transport (mock-transport (fn [_ _] (reset! attempted true) {:status 200}))})
          error     (caught #(proto/send-turn backend request))]
      (assertions
        "an unusable callback result is an :auth failure"
        (proto/error-category error) => :auth
        "no request is attempted without headers"
        @attempted => false
        "and the callback's own exception data — which may hold a token — is dropped"
        (str/includes? (str error) "secret") => false)))
  (let [calls   (atom 0)
        backend (codex/new-backend {:auth-fn (fn [_] {:headers {"Authorization" "Bearer mock"}})
                                    :http-transport (mock-transport (fn [_ _] (swap! calls inc) {:status 401 :body "rejected"}))})]
    (assertions
      "a persistent 401 is an :auth failure, not an endless refresh loop"
      (proto/error-category (caught #(proto/send-turn backend request))) => :auth
      @calls => 2))
  (component "a transport that streams a body and THEN reports 401"
    ;; HttpTransport promises a non-2xx delivers no lines. A host transport can
    ;; break that promise, and retrying would fold two attempts' lines into one
    ;; accumulator — duplicated deltas, or a stale terminal event.
    (let [calls   (atom 0)
          backend (codex/new-backend
                    {:auth-fn (fn [_] {:headers {"Authorization" "Bearer mock"}})
                     :http-transport (mock-transport
                                       (fn [_ cb]
                                         (swap! calls inc)
                                         (when cb (emit! cb [terminal]))
                                         {:status 401 :body "expired"}))})
          error   (caught #(proto/stream-turn backend request (fn [_])))]
      (assertions
        "is refused rather than retried into a half-filled stream"
        (proto/error-category error) => :auth
        @calls => 1)))
  (let [contexts (atom [])
        urls     (atom [])
        headers  (atom [])
        backend  (codex/new-backend
                   {:api-key "ignored-without-base-url"
                    :auth-fn (fn [ctx] (swap! contexts conj ctx)
                               {:headers {"Authorization" "Bearer mock"}})
                    :http-transport (mock-transport
                                      (fn [req _]
                                        (swap! urls conj (:url req))
                                        (swap! headers conj (:headers req))
                                        {:status 403 :body "forbidden"}))})]
    (assertions
      "a 403 is not a refresh trigger"
      (proto/error-category (caught #(proto/send-turn backend request))) => :auth
      @contexts => [{:refresh? false}]
      "and with no base-url the ChatGPT endpoint and its dialect headers are used"
      (first @urls) => "https://chatgpt.com/backend-api/codex/responses"
      (get (first @headers) "OpenAI-Beta") => "responses=experimental"))
  (let [loaded? (atom false)]
    (with-redefs [saved-auth/get-auth! (fn [] (reset! loaded? true) {})]
      (let [backend (providers/build-injected-credentials-backend [{:provider :codex}] [])]
        (assertions
          "an injected credential never falls back to disk or a browser"
          (proto/error-category (caught #(proto/send-turn backend request))) => :auth
          @loaded? => false)))))

(specification "one opencode-go credential serves both of the gateway's wires"
  (let [calls   (atom [])
        backend (providers/build-injected-credentials-backend
                  [{:provider :opencode-go :api-key "mock" :http-timeout-ms 9876
                    :http-transport (mock-transport
                                      (fn [req cb]
                                        (swap! calls conj req)
                                        (if (str/ends-with? (:url req) "/messages")
                                          (if cb
                                            (emit! cb [{:type "message_delta" :delta {:stop_reason "end_turn"} :usage {}}])
                                            {:status 200 :body "{\"content\":[],\"stop_reason\":\"end_turn\",\"usage\":{}}"})
                                          (if cb
                                            (emit! cb [{:choices [{:delta {:content "ok"} :finish_reason "stop"}]}])
                                            {:status 200 :body "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{}}"}))))}]
                  [])]
    (doseq [model      ["minimax-m2.7" "qwen3.5-plus" "Qwen3.6" "glm-5" "kimi-k2.7-code"]
            streaming? [false true]
            explicit?  [false true]]
      (p/await! (proto/send-turn* backend (cond-> (assoc request :model model)
                                            explicit? (assoc :provider :opencode-go))
                  (when streaming? (fn [_]))))
      (assertions
        "the wire follows the request's own model, even when the provider is named explicitly"
        (str/ends-with? (:url (last @calls))
          (if (providers/opencode-go-anthropic-model? model) "/go/v1/messages" "/go/v1/chat/completions"))
        => true))
    (assertions
      "the host's timeout survives the split"
      (every? #(= 9876 (:timeout-ms %)) @calls) => true
      "and the gateway's mandatory session header is on every request"
      (every? #(seq (get-in % [:headers "x-opencode-session"])) @calls) => true)))

(specification "opencode-go honours a host-declared reasoning dialect"
  (doseq [[dialect expected] [[nil nil] [:openai "high"]]]
    (let [sent    (atom nil)
          backend (providers/build-injected-credentials-backend
                    [(cond-> {:provider :opencode-go :api-key "mock"
                              :http-transport (mock-transport
                                                (fn [req cb]
                                                  (reset! sent (json/parse-string (:body req) true))
                                                  (emit! cb [{:choices [{:delta {:content "ok"} :finish_reason "stop"}]}])))}
                       dialect (assoc :reasoning-dialect dialect))]
                    [])]
      (p/await! (proto/stream-turn backend (assoc request :model "glm-5" :reasoning {:effort :high}) (fn [_])))
      (assertions
        "the gateway default emits nothing; a declared dialect is not discarded"
        (:reasoning_effort @sent) => expected))))

(specification "a ChatGPT-subscription credential still ignores a stray api-key"
  ;; REGRESSION GUARD. `:codex` names the ChatGPT subscription, whose endpoint
  ;; authenticates with an OAuth token; `new-backend` rejects an `:api-key`
  ;; that has no `:base-url` to go to. The CLI's `resolve-config-credentials`
  ;; stamps `:api-key` onto keyless providers too, so a `.escapement.edn`
  ;; `{:provider :codex :key-from …}` whose store happens to hold a key
  ;; produces exactly that descriptor — and it has always meant "use my
  ;; ChatGPT login". Widening which descriptor keys reach the constructor must
  ;; not turn that into an assembly failure.
  (assertions
    "a keyless ChatGPT credential assembles"
    (some? (providers/build-credential-backend {:kind :codex :default-model "gpt-5.6-sol"})) => true
    "and so does one carrying a key it cannot use"
    (some? (providers/build-credential-backend
             {:kind :codex :api-key "sk-unusable" :default-model "gpt-5.6-sol"})) => true
    "the key does not reach the backend"
    (:api-key (providers/build-credential-backend
                {:kind :codex :api-key "sk-unusable" :default-model "gpt-5.6-sol"})) => nil
    "the same descriptor WITH an endpoint keeps the key — there it is the credential"
    (:api-key (providers/build-credential-backend
                {:kind :codex :api-key "sk-usable" :base-url "https://mock/v1"
                 :default-model "glm-5.3"})) => "sk-usable"
    "and the full injected path assembles for both shapes"
    (every? some?
      (map #(providers/build-injected-credentials-backend [%] [])
        [{:provider :codex}
         {:provider :codex :api-key "sk-unusable"}
         {:provider :openai-codex :api-key "sk-unusable"}])) => true))

(specification "credential-detection fields never reach a backend constructor"
  ;; The descriptor also describes HOW the credential was found. Those fields
  ;; are not wire configuration, and a backend that later reads `(:model opts)`
  ;; or `(:route opts)` must not silently pick one up.
  (let [seen (atom nil)]
    (with-redefs [providers/build-openai-backend (fn [opts] (reset! seen opts) ::backend)]
      (providers/build-credential-backend
        {:kind :openai :source "OPENAI_API_KEY" :route #"^gpt-" :subscription true
         :api-key "sk-mock" :base-url "https://mock/v1" :default-model "gpt-4o-mini"
         :reasoning-dialect :openai :allow-stored-auth? true}))
    (assertions
      "the wire keys are forwarded"
      (select-keys @seen [:api-key :base-url :default-model :reasoning-dialect])
      => {:api-key "sk-mock" :base-url "https://mock/v1" :default-model "gpt-4o-mini"
          :reasoning-dialect :openai}
      "and the detection keys are not"
      (select-keys @seen [:kind :source :route :subscription :allow-stored-auth?]) => {})))
