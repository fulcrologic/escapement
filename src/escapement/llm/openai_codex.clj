(ns escapement.llm.openai-codex
  "LLMBackend implementation for the OpenAI **Responses** API wire.

  Two endpoint profiles, chosen by `:endpoint-profile` (defaulting to
  `:responses` when a `:base-url` is given and `:chatgpt` when it is not).
  Only `:chatgpt` drops the output cap, remaps retired model aliases, and
  sends the ChatGPT dialect headers; `:responses` speaks to any
  Responses-compatible endpoint verbatim.

  Three auth modes; `:auth-fn` wins, then `:api-key`, then stored OAuth:

  * Host-owned callback (`:auth-fn`): the embedding host returns the request
    headers and refreshes on demand. Bypasses every stored-credential and
    browser-login path; see `escapement.llm.auth/transport`.

  * ChatGPT subscription (OAuth): routes through
    `https://chatgpt.com/backend-api/codex/responses` using a token bound
    to a ChatGPT Plus/Pro subscription. Run `escapement login codex` once
    to save credentials to `~/.escapement/openai-auth.json`; the backend
    refreshes the token automatically when it is within 60 seconds of
    expiry.

  * Plain API key: any Responses-compatible endpoint reached with a bearer
    API key — e.g. z.ai's coding-plan v1 endpoint:

      (codex/new-backend
        {:api-key       (System/getenv \"ZAI_API_KEY\")
         :base-url      \"https://api.z.ai/api/v1\"
         :default-model \"glm-5.3\"})

    The `:base-url` is the endpoint ROOT; `/responses` is appended."
  (:require
    [clojure.string :as str]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
    [escapement.llm.auth :as host-auth]
    [escapement.llm.openai-codex.auth :as auth]
    [escapement.llm.openai-codex.http :as http]
    [escapement.llm.openai-codex.translate :as t]
    [escapement.llm.protocol :as proto]
    [escapement.llm.types :as types]
    [com.fulcrologic.statecharts.promise :as p]))

(defn- responses-url
  "Endpoint URL for an API-key base-url root (appends `/responses`)."
  [base-url]
  (-> base-url
    (str/replace #"/+$" "")
    (str "/responses")))

(def ^:private chatgpt-profile-headers
  "Headers that identify a request as speaking the ChatGPT backend's dialect.
   They belong to the ENDPOINT PROFILE, not to an auth mode, so every auth
   branch sends them under `:chatgpt` — the profile also rewrites the body
   (drops the output cap, remaps retired model ids), and a request shaped for
   ChatGPT that omits these headers is silently wrong on the wire."
  {"OpenAI-Beta" "responses=experimental"
   "originator"  "codex_cli_rs"})

(defrecord OpenAICodexBackend [default-model api-key base-url http-timeout-ms http-transport
                               auth-fn endpoint-profile allow-stored-auth? extra-headers
                               max-sse-event-chars]
  proto/LLMBackend
  (send-turn [this request]
    (proto/stream-turn this request nil))

  proto/StreamingLLMBackend
  (stream-turn [this request on-delta]
    (p/do!
      (let [request (cond-> request (not (:model request)) (assoc :model default-model))]
        (when-let [err (types/validate-request request)]
          (throw (ex-info "Invalid LLM request" {:errors err :request request})))
        (let [chatgpt?  (= :chatgpt endpoint-profile)
              body      (cond-> (t/build-request-body request)
                          chatgpt?
                          (-> (dissoc :max_output_tokens)
                            (assoc :model (t/normalize-model (:model request)))))
              base-req  (cond-> {:body                body
                                 :on-delta            on-delta
                                 ;; Profile headers first so a caller's own
                                 ;; :extra-headers can still override them.
                                 :extra-headers       (cond->> extra-headers
                                                        chatgpt? (merge chatgpt-profile-headers))
                                 :max-sse-event-chars max-sse-event-chars
                                 :http-transport      (host-auth/transport this)
                                 :timeout-ms          (or http-timeout-ms 180000)}
                          base-url (assoc :url (responses-url base-url)))
              oauth-req (fn []
                          (when-not (and allow-stored-auth? chatgpt?)
                            (throw (proto/llm-error :auth "Responses backend requires :auth-fn or :api-key")))
                          (let [a (auth/get-auth!)]
                            (assoc base-req
                              :access-token (:access-token a)
                              :account-id (:account-id a))))
              send!     (fn [req] (http/post-responses-stream! req))
              raw       (cond
                          ;; `:headers {}` marks auth as host-owned: it
                          ;; suppresses the stored-OAuth header requirement
                          ;; without contributing any auth header itself. The
                          ;; callback's headers are applied by the transport.
                          auth-fn (send! (assoc base-req :headers {}))
                          api-key (send! (assoc base-req :api-key api-key))
                          :else
                          (try
                            (send! (oauth-req))
                            (catch clojure.lang.ExceptionInfo e
                              (if (and (= 401 (:status (ex-data e))) (:retry? (ex-data e)))
                                ;; Token expired between load and use: refresh once, then retry.
                                (do (auth/save-auth!
                                      (auth/refresh-token! (:refresh-token (auth/load-auth!))))
                                    (send! (oauth-req)))
                                (throw e)))))
              response (t/openai-response->anthropic-response raw (:model request))]
          (when-let [err (types/validate-response response)]
            (throw (ex-info "openai-codex produced an invalid response"
                     {:errors err :response response})))
          response)))))

(>defn new-backend
  "Constructs an OpenAI Codex (Responses wire) backend instance.

Optional opts:
* `:auth-fn`        — host-owned headers callback; see `escapement.llm.auth/transport`.
                       Bypasses ALL stored OAuth and browser login behavior.
* `:endpoint-profile` — `:responses` (with base-url) or `:chatgpt` (without).
                       Only ChatGPT drops the output cap and normalizes model aliases.
* `:allow-stored-auth?` — default true for CLI compatibility; injection sets false.
* `:extra-headers`  — additional headers; host auth callback takes precedence.
* `:max-sse-event-chars` — positive SSE event data ceiling (default 8388608).
                       Overflow fails without retry; see Responses HTTP docstring.
* `:default-model`  — model string used when the Request omits `:model`
                      (default `t/default-model`, currently \"gpt-5.6-sol\").
                      On the ChatGPT-subscription path it must be one of
                      `t/supported-models` — ChatGPT-account auth rejects
                      every `-codex`/`-pro`/`-nano` variant. On the
                      `:api-key` path it is any id the endpoint serves
                      (e.g. `\"glm-5.3\"`).
* `:api-key`        — plain bearer API key. When set, OAuth is bypassed
                      entirely and `:base-url` is REQUIRED (the endpoint
                      root; `/responses` is appended).
* `:base-url`       — endpoint root for the `:api-key` path, e.g.
                      `\"https://api.z.ai/api/v1\"`.
* `:http-timeout-ms`— per-request HTTP timeout (default 180000).
* `:http-transport` — `escapement.llm.http-transport/HttpTransport`. Defaults
                      to the bb http-client backed transport. CLJS hosts
                      must supply their own."
  ([] [=> :any] (new-backend {}))
  ([opts]
   [:map => :any]
   (let [{:keys [default-model api-key base-url http-timeout-ms http-transport
                 auth-fn endpoint-profile extra-headers]} opts
         api-key             (when (seq api-key) api-key)
         max-sse-event-chars (get opts :max-sse-event-chars http/default-max-sse-event-chars)
         profile             (or endpoint-profile (if base-url :responses :chatgpt))]
     (when-not (pos-int? max-sse-event-chars)
       (throw (ex-info ":max-sse-event-chars must be a positive integer"
                {:max-sse-event-chars max-sse-event-chars})))
     (when-not (#{:responses :chatgpt} profile)
       (throw (ex-info "Unknown Responses :endpoint-profile" {:endpoint-profile profile})))
     (when (and (= :responses profile) (str/blank? (str base-url)))
       (throw (ex-info "Responses profile requires :base-url" {:reason :missing-base-url})))
     (when (and api-key (not auth-fn) (str/blank? (str base-url)))
       (throw (ex-info "openai-codex: :api-key mode requires :base-url (the endpoint root, e.g. https://api.z.ai/api/v1)"
                {:reason :missing-base-url})))
     ;; Map constructor, not positional: the record has ten fields and a
     ;; positional call silently transposes two the moment one is added.
     (map->OpenAICodexBackend
       {:default-model       (or default-model t/default-model)
        :api-key             api-key
        :base-url            base-url
        :http-timeout-ms     http-timeout-ms
        :http-transport      http-transport
        :auth-fn             auth-fn
        :endpoint-profile    profile
        :allow-stored-auth?  (get opts :allow-stored-auth? true)
        :extra-headers       extra-headers
        :max-sse-event-chars max-sse-event-chars}))))
