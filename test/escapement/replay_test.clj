(ns escapement.replay-test
  (:require
    [com.fulcrologic.statecharts.promise :as p]
    [escapement.capture :as capture]
    [escapement.llm.protocol :as llm]
    [escapement.replay :as replay]
    [escapement.storage.memory :as mem]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(defrecord RecordingBackend [last-request response]
  llm/LLMBackend
  (send-turn [_ request]
    (reset! last-request request)
    (p/do! response)))

(defn- recording-backend [response]
  (->RecordingBackend (atom nil) response))

(def ^:private captured-request
  {:system   "ORIGINAL system prompt"
   :model    "claude-base"
   :messages [{:role :user :content [{:type :text :text "hello"}]}]
   :tools    []})

(defn- store-with-request []
  (let [store (mem/new-store)
        cap   {:store store :session-id "s" :node-id :chat :visit 0}]
    (capture/capture-request! cap 0 captured-request "hello")
    store))

(specification "deep-merge"
  (assertions
    "merges nested maps key-wise"
    (replay/deep-merge {:a {:x 1 :y 2}} {:a {:y 9 :z 3}}) => {:a {:x 1 :y 9 :z 3}}
    "later scalar wins"
    (replay/deep-merge {:m "a"} {:m "b"}) => {:m "b"}
    "replaces non-map collections wholesale rather than merging"
    (replay/deep-merge {:xs [1 2 3]} {:xs [9]}) => {:xs [9]}
    "ignores nil override maps"
    (replay/deep-merge {:a 1} nil) => {:a 1}))

(specification "load-request"
  (let [store (store-with-request)]
    (assertions
      "returns the captured request map for the coordinates"
      (replay/load-request store "s" :chat 0 0) => captured-request
      "returns nil when nothing was captured there"
      (replay/load-request store "s" :chat 0 9) => nil)))

(specification "refine-turn re-issues the captured turn"
  (component "with empty overrides it re-issues the IDENTICAL captured request"
    (let [store   (store-with-request)
          resp    {:stop-reason :end_turn :content [{:type :text :text "new answer"}] :model "claude-base"}
          backend (recording-backend resp)
          out     (replay/refine-turn store "s" :chat 0 0 {:backend backend})]
      (assertions
        "the backend received exactly the captured request"
        @(:last-request backend) => captured-request
        "the returned :request is the captured one"
        (:request out) => captured-request
        "the new response is returned"
        (:response out) => resp
        "the original request is echoed back for diffing"
        (:original-request out) => captured-request)))

  (component "with overrides it deep-merges onto the captured request"
    (let [store   (store-with-request)
          backend (recording-backend {:stop-reason :end_turn :content [] :model "claude-opus-4-7"})
          out     (replay/refine-turn store "s" :chat 0 0
                    {:backend   backend
                     :overrides {:system "TUNED prompt" :model "claude-opus-4-7" :temperature 0.2}})]
      (assertions
        "the tuned system prompt overrides the captured one"
        (get-in out [:request :system]) => "TUNED prompt"
        "the swapped model is applied"
        (get-in out [:request :model]) => "claude-opus-4-7"
        "a new param is added"
        (get-in out [:request :temperature]) => 0.2
        "untouched captured fields are preserved (messages survive)"
        (get-in out [:request :messages]) => (:messages captured-request)
        "the backend received the merged request"
        (:system @(:last-request backend)) => "TUNED prompt")))

  (component "throws when nothing was captured at the coordinates"
    (let [store (store-with-request)]
      (assertions
        "a missing capture is a clear error, not a silent nil send"
        (replay/refine-turn store "s" :chat 0 5 {:backend (recording-backend {})})
        =throws=> clojure.lang.ExceptionInfo))))
