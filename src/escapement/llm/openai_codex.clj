(ns escapement.llm.openai-codex
  "LLMBackend implementation for the OpenAI **Responses** API wire.

  Two auth modes:

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

(defrecord OpenAICodexBackend [default-model api-key base-url http-timeout-ms http-transport]
  proto/LLMBackend
  (send-turn [_ request]
    (p/do!
      (when-let [err (types/validate-request request)]
        (throw (ex-info "Invalid LLM request" {:errors err :request request})))
      (let [request  (cond-> request
                       (not (:model request)) (assoc :model default-model))
            base-req (cond-> {:body       (t/build-request-body request)
                              :timeout-ms (or http-timeout-ms 180000)}
                       http-transport (assoc :http-transport http-transport))
            api-req  (when api-key
                       (assoc base-req
                         :api-key api-key
                         :url (responses-url base-url)))
            oauth-req (fn []
                        (let [a (auth/get-auth!)]
                          (assoc base-req
                            :access-token (:access-token a)
                            :account-id (:account-id a))))
            send!    (fn [req] (http/post-responses-stream! req))
            raw      (if api-key
                       (send! api-req)
                       (try
                         (send! (oauth-req))
                         (catch clojure.lang.ExceptionInfo e
                           (if (and (= 401 (:status (ex-data e))) (:retry? (ex-data e)))
                             ;; token expired between load and use — refresh once then retry
                             (do (auth/save-auth!
                                   (auth/refresh-token!
                                     (:refresh-token (auth/load-auth!))))
                                 (send! (oauth-req)))
                             (throw e)))))
            response (t/openai-response->anthropic-response raw (:model request))]
        (when-let [err (types/validate-response response)]
          (throw (ex-info "openai-codex produced an invalid response"
                   {:errors err :response response})))
        response))))

(>defn new-backend
  "Constructs an OpenAI Codex (Responses wire) backend instance.

Optional opts:
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
   (let [{:keys [default-model api-key base-url http-timeout-ms http-transport]} opts
         api-key (when (seq api-key) api-key)]
     (when (and api-key (str/blank? (str base-url)))
       (throw (ex-info "openai-codex: :api-key mode requires :base-url (the endpoint root, e.g. https://api.z.ai/api/v1)"
                {:reason :missing-base-url})))
     (->OpenAICodexBackend (or default-model t/default-model)
       api-key
       (when api-key base-url)
       http-timeout-ms
       http-transport))))
