(ns escapement.llm.openai-codex
  "LLMBackend implementation that routes through the ChatGPT Responses API
  using an OAuth token bound to a ChatGPT Plus/Pro subscription.

  Usage:
    (require '[escapement.llm.openai-codex :as codex])
    (codex/new-backend {:default-model \"gpt-5.6-sol\"})

  Authentication:
    Run `escapement login codex` once to save credentials to
    `~/.escapement/openai-auth.json`. The backend refreshes the token
    automatically when it is within 60 seconds of expiry."
  (:require
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
    [escapement.llm.openai-codex.auth :as auth]
    [escapement.llm.openai-codex.http :as http]
    [escapement.llm.openai-codex.translate :as t]
    [escapement.llm.protocol :as proto]
    [escapement.llm.types :as types]
    [com.fulcrologic.statecharts.promise :as p]))

(defrecord OpenAICodexBackend [default-model http-transport]
  proto/LLMBackend
  (send-turn [_ request]
    (p/do!
      (when-let [err (types/validate-request request)]
        (throw (ex-info "Invalid LLM request" {:errors err :request request})))
      (let [request  (cond-> request
                       (not (:model request)) (assoc :model default-model))
            auth-map (auth/get-auth!)
            body     (t/build-request-body request)
            send!    (fn [a]
                       (http/post-responses-stream!
                         (cond-> {:body         body
                                  :access-token (:access-token a)
                                  :account-id   (:account-id a)
                                  :timeout-ms   180000}
                           http-transport (assoc :http-transport http-transport))))
            raw      (try
                       (send! auth-map)
                       (catch clojure.lang.ExceptionInfo e
                         (if (= 401 (:status (ex-data e)))
                           ;; token expired between load and use — refresh once then retry
                           (do (auth/save-auth!
                                 (auth/refresh-token!
                                   (:refresh-token (auth/load-auth!))))
                               (send! (auth/get-auth!)))
                           (throw e))))
            response (t/openai-response->anthropic-response raw (:model request))]
        (when-let [err (types/validate-response response)]
          (throw (ex-info "openai-codex produced an invalid response"
                   {:errors err :response response})))
        response))))

(>defn new-backend
  "Constructs an OpenAI Codex backend instance.

Optional opts:
* `:default-model`  — model string used when the Request omits `:model`
                     (default `t/default-model`, currently \"gpt-5.6-sol\").
                     Must be one of `t/supported-models` — ChatGPT-account auth
                     rejects every `-codex`/`-pro`/`-nano` variant.
* `:http-transport` — `escapement.llm.http-transport/HttpTransport`. Defaults
                     to the bb http-client backed transport. CLJS hosts
                     must supply their own."
  ([] [=> :any] (new-backend {}))
  ([opts]
   [:map => :any]
   (->OpenAICodexBackend (or (:default-model opts) t/default-model)
     (:http-transport opts))))
