(ns escapement.llm.protocol-test
  (:require
   [babashka.http-client :as http]
   [escapement.llm.api :as api]
   [escapement.llm.protocol :as proto]
   [fulcro-spec.core :refer [specification assertions component =>]]))

(specification "llm-error / error-category round-trip"
  (component "llm-error carries the category in ex-data"
    (let [e (proto/llm-error :rate-limited "slow down"
                             {:status 429 :data {:url "http://x"}})]
      (assertions
       "category is recorded"
       (:llm/category (ex-data e)) => :rate-limited
       "status is recorded"
       (:llm/status (ex-data e)) => 429
       "extra data is merged in"
       (:url (ex-data e)) => "http://x"
       "message is preserved"
       (ex-message e) => "slow down"
       "error-category reads it back"
       (proto/error-category e) => :rate-limited)))

  (component "error-category walks the ex-cause chain"
    (let [inner (proto/llm-error :overloaded "529")
          wrap  (ex-info "wrapped backend failure" {:some :context} inner)]
      (assertions
       "a wrapped/rethrown categorized error is still detectable"
       (proto/error-category wrap) => :overloaded)))

  (component "uncategorized throwables yield nil"
    (assertions
     "plain RuntimeException"
     (proto/error-category (RuntimeException. "boom")) => nil
     "ex-info without :llm/category"
     (proto/error-category (ex-info "nope" {:status 500})) => nil
     "nil is safe"
     (proto/error-category nil) => nil))

  (component "error-categories is the canonical set"
    (assertions
     proto/error-categories
     => #{:rate-limited :overloaded :auth :invalid-request
          :context-length :timeout :transport})))

(specification "native api backend throws categorized errors on non-2xx"
  (component "429 → :rate-limited (preserves :status/:body/:url ex-data)"
    (with-redefs [http/post (fn [_ _] {:status 429 :body "{\"type\":\"rate_limit_error\"}"})]
      (let [backend (api/new-backend {:base-url "http://test" :api-key "k"
                                      :default-model "claude-x"})
            t       (try (proto/send-turn backend
                                          {:messages [{:role :user
                                                       :content [{:type :text :text "hi"}]}]})
                         nil
                         (catch Throwable e e))]
        (assertions
         "an error was thrown"
         (some? t) => true
         "it is categorized :rate-limited"
         (proto/error-category t) => :rate-limited
         "legacy message text preserved"
         (ex-message t) => "API error: HTTP 429"
         "legacy ex-data keys preserved"
         (:status (ex-data t)) => 429
         (:url (ex-data t)) => "http://test/v1/messages"))))

  (component "401 → :auth, 529 → :overloaded, 500 → :transport"
    (letfn [(cat-for [status body]
              (with-redefs [http/post (fn [_ _] {:status status :body body})]
                (let [backend (api/new-backend {:base-url "http://t" :api-key "k"
                                                :default-model "m"})]
                  (proto/error-category
                   (try (proto/send-turn backend
                                         {:messages [{:role :user
                                                      :content [{:type :text :text "hi"}]}]})
                        (catch Throwable e e))))))]
      (assertions
       "401 unauthorized"
       (cat-for 401 "{}") => :auth
       "529 overloaded"
       (cat-for 529 "{}") => :overloaded
       "400 context-length when body indicates token length"
       (cat-for 400 "prompt is too long: maximum context length tokens exceeded")
       => :context-length
       "400 otherwise invalid-request"
       (cat-for 400 "{\"type\":\"invalid_request_error\"}") => :invalid-request
       "400 that merely mentions 'token' is NOT misclassified as context-length"
       (cat-for 400 "{\"error\":\"invalid token parameter\"}") => :invalid-request
       "500 unknown → transport"
       (cat-for 500 "boom") => :transport)))

  (component "a refused/failed connection is :transport (not :timeout)"
    (let [cat (with-redefs [http/post (fn [_ _]
                                        (throw (java.net.ConnectException. "refused")))]
                (let [backend (api/new-backend {:base-url "http://t" :api-key "k"
                                                :default-model "m"})]
                  (proto/error-category
                   (try (proto/send-turn backend
                                         {:messages [{:role :user
                                                      :content [{:type :text :text "hi"}]}]})
                        (catch Throwable e e)))))]
      (assertions
       "ConnectException categorized as :transport"
       cat => :transport))))
