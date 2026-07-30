(ns escapement.llm.openai-codex-test
  "Backend-level integration tests using stubbed auth and http."
  (:require
    [escapement.llm.openai-codex :as codex]
    [escapement.llm.openai-codex.auth :as auth]
    [escapement.llm.openai-codex.http :as http]
    [escapement.llm.protocol :as proto]
    [fulcro-spec.core :refer [=> assertions specification when-mocking!]]
    [com.fulcrologic.statecharts.promise :as p]))

;;; ---------------------------------------------------------------------------
;;; Sample data

(def valid-request
  {:model    "gpt-5.1-codex"
   :messages [{:role :user :content [{:type :text :text "Hello"}]}]})

(def stub-auth
  {:access-token "tok-abc"
   :account-id   "acct-123"})

(def stub-stream-result
  {:items       [{:type    "message" :role "assistant"
                  :content [{:type "output_text" :text "Hi there!"}]}]
   :usage       {:input_tokens 5 :output_tokens 3}
   :stop-reason :end_turn
   :model       "gpt-5.1-codex"})

(def stub-stream-tool-use
  {:items       [{:type      "function_call" :call_id "call_1" :name "my_fn"
                  :arguments "{\"x\":1}"}]
   :usage       {:input_tokens 8 :output_tokens 2}
   :stop-reason :tool_use
   :model       "gpt-5.1-codex"})

;;; ---------------------------------------------------------------------------
;;; Tests

(specification "OpenAICodexBackend send-turn — happy path"
  (when-mocking!
    (auth/get-auth!) => stub-auth
    (http/post-responses-stream! _) => stub-stream-result

    (let [backend  (codex/new-backend {:default-model "gpt-5.1-codex"})
          response (p/await! (proto/send-turn backend valid-request))]
      (assertions
        "response has :end_turn stop-reason"
        (:stop-reason response) => :end_turn
        "content contains translated text block"
        (get-in response [:content 0 :type]) => :text
        (get-in response [:content 0 :text]) => "Hi there!"
        "model is carried through"
        (:model response) => "gpt-5.1-codex"
        "backend-metadata identifies openai-codex"
        (get-in response [:backend-metadata :backend]) => :openai-codex))))

(specification "OpenAICodexBackend send-turn — validate-request rejection"
  (let [backend (codex/new-backend)]
    (assertions
      "throws ex-info for an invalid request (missing :messages)"
      (try
        (p/await! (proto/send-turn backend {:model "gpt-5.1"}))        ; missing :messages
        nil
        (catch clojure.lang.ExceptionInfo e
          (some? (:errors (ex-data e))))) => true)))

(specification "OpenAICodexBackend send-turn — 401 retry path"
  (let [call-count (atom 0)]
    (when-mocking!
      (auth/get-auth!) => stub-auth
      (auth/load-auth!) => (assoc stub-auth :refresh-token "ref-tok")
      (auth/refresh-token! _) => {:access-token  "tok-new"
                                  :refresh-token "ref-tok-2"
                                  :expires-at    9999999999000}
      (auth/save-auth! _) => nil
      (http/post-responses-stream! _) => (do
                                           (swap! call-count inc)
                                           (if (= 1 @call-count)
                                             (throw (ex-info "token rejected"
                                                      {:status 401 :body "" :retry? true}))
                                             stub-stream-result))

      (let [backend  (codex/new-backend)
            response (p/await! (proto/send-turn backend valid-request))]
        (assertions
          "eventually returns a valid response after retry"
          (:stop-reason response) => :end_turn
          "http was called exactly twice (1 failure + 1 retry)"
          @call-count => 2)))))

(specification "OpenAICodexBackend send-turn — 429 propagates without retry"
  (when-mocking!
    (auth/get-auth!) => stub-auth
    (http/post-responses-stream! _) => (throw (ex-info "rate limited"
                                                {:status 429 :body "" :retry? false}))

    (let [backend (codex/new-backend)]
      (assertions
        "throws ex-info with status 429"
        (try
          (p/await! (proto/send-turn backend valid-request))
          nil
          (catch clojure.lang.ExceptionInfo e
            (:status (ex-data e)))) => 429))))

(specification "new-backend"
  (assertions
    "zero-arg form returns an OpenAICodexBackend"
    (instance? escapement.llm.openai_codex.OpenAICodexBackend
      (codex/new-backend)) => true
    "default-model defaults to translate/default-model (the GPT-5.6 flagship)"
    (:default-model (codex/new-backend)) => "gpt-5.6-sol"
    "default-model is overridable via opts"
    (:default-model (codex/new-backend {:default-model "gpt-5.2-codex"})) => "gpt-5.2-codex"))
