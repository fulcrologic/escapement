(ns escapement.ui.server-test
  (:require
    [cognitect.transit :as transit]
    [com.fulcrologic.statecharts :as-alias sc]
    [escapement.protocols :as proto]
    [escapement.ui.server :as server]
    [fulcro-spec.core :refer [=> assertions component specification]])
  (:import
    (java.io ByteArrayInputStream ByteArrayOutputStream)))

(defn- ->transit-stream [x]
  (let [out (ByteArrayOutputStream.)]
    (transit/write (transit/writer out :json) x)
    (ByteArrayInputStream. (.toByteArray out))))

(defn- <-transit [bytes]
  (transit/read (transit/reader (ByteArrayInputStream. bytes) :json)))

(defn- stub-store []
  (reify
    proto/SessionIndex
    (list-sessions [_] [{::sc/session-id "s1" :session/status :done}])
    proto/TranscriptStore
    (append-event! [_ _ _] (throw (ex-info "ro" {})))
    (read-events [_ _ _] [])
    proto/ArtifactStore
    (write-artifact! [_ _ _ _ _] nil)
    (list-artifacts [_ _] [])
    (read-artifact [_ _ _] nil)))

(specification "make-handler"
  (let [handler (server/make-handler {:escapement/store (stub-store) :escapement/active-session-id "s1"})]
    (component "OPTIONS preflight"
      (let [resp (handler {:request-method :options :uri "/api"})]
        (assertions
          "answers 200"
          (:status resp) => 200
          "sends a permissive CORS origin"
          (get-in resp [:headers "Access-Control-Allow-Origin"]) => "*")))
    (component "POST /api runs the EQL query"
      (let [resp (handler {:request-method :post :uri "/api"
                           :body (->transit-stream [:escapement/active-session-id
                                                    {:escapement/all-sessions [::sc/session-id]}])})
            body (<-transit (:body resp))]
        (assertions
          "answers 200 with transit content-type"
          (:status resp) => 200
          (get-in resp [:headers "Content-Type"]) => "application/transit+json"
          "reports the active session id"
          (:escapement/active-session-id body) => "s1"
          "returns the session list from the resolvers"
          (mapv ::sc/session-id (:escapement/all-sessions body)) => ["s1"])))
    (component "a malformed request body becomes a transit error, not a dropped connection"
      (let [resp (handler {:request-method :post :uri "/api"
                           :body (ByteArrayInputStream. (.getBytes "not-transit"))})]
        (assertions
          "answers 500"
          (:status resp) => 500
          "carries an :error message"
          (string? (:error (<-transit (:body resp)))) => true)))
    (component "GET / serves the SPA entry point from the classpath public/ tree"
      (let [resp (handler {:request-method :get :uri "/"})]
        (assertions
          "answers 200"
          (:status resp) => 200
          "with an html content type"
          (get-in resp [:headers "Content-Type"]) => "text/html; charset=utf-8"
          "and a body (the index.html resource stream)"
          (some? (:body resp)) => true)))
    (component "a GET for a missing static file 404s rather than erroring"
      (assertions
        "answers 404"
        (:status (handler {:request-method :get :uri "/js/main/does-not-exist.js"})) => 404))
    (component "unknown routes"
      (assertions
        "a path-traversal attempt is refused (404, never escapes public/)"
        (:status (handler {:request-method :get :uri "/../deps.edn"})) => 404
        "a non-GET, non-/api route answers 404"
        (:status (handler {:request-method :put :uri "/other"})) => 404))))
