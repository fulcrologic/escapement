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

(def ^:private captured-seed
  {:params           {:system         "ORIGINAL node prompt"
                      :model          :some/alias
                      :temperature    0.0
                      :allowed-events [{:event :writer/done :description "finish"}]}
   :initial-messages [{:role :user :content "write a poem"}]})

(defn- store-with-seed []
  (let [store (mem/new-store)
        cap   {:store store :session-id "s" :node-id :writer :visit 0}]
    (capture/capture-seed! cap captured-seed)
    store))

(specification "load-seed"
  (let [store (store-with-seed)]
    (assertions
      "returns the captured seed for the (node,visit) coordinates"
      (replay/load-seed store "s" :writer 0) => captured-seed
      "returns nil when no seed was captured there"
      (replay/load-seed store "s" :writer 9) => nil)))

(specification "refine-node re-issues the node's opening turn from its seed"
  (component "applies params overrides and its tool palette, pinning the model"
    (let [store   (store-with-seed)
          backend (recording-backend {:stop-reason :end_turn
                                      :content     [{:type :text :text "REPLAYED"}]
                                      :model       "gpt-tuned"})
          out     (replay/refine-node store "s" :writer 0
                    {:backend   backend
                     :overrides {:system "TUNED prompt" :temperature 0.7}
                     :pinned    {:model "gpt-tuned"}})]
      (assertions
        "the turn ran to a successful assistant response"
        (:status out) => :ok
        "the tuned system prompt overrides the seed's"
        (get-in out [:params :system]) => "TUNED prompt"
        "a new param is merged onto the seed params"
        (get-in out [:params :temperature]) => 0.7
        "the node's event-tool palette is reconstructed from the seed"
        (mapv :name (:tools out)) => ["event__writer_done"]
        "the effective messages default to the seed's initial messages"
        (:messages out) => (:initial-messages captured-seed)
        "the pinned model is what actually reached the backend"
        (:model @(:last-request backend)) => "gpt-tuned"
        "the backend received the tuned system prompt"
        (:system @(:last-request backend)) => "TUNED prompt"
        "the new response is returned"
        (get-in out [:response :content 0 :text]) => "REPLAYED")))

  (component "overriding :messages replays a different conversation prefix"
    (let [store   (store-with-seed)
          backend (recording-backend {:stop-reason :end_turn :content [] :model "m"})
          out     (replay/refine-node store "s" :writer 0
                    {:backend  backend
                     :messages [{:role :user :content "a DIFFERENT prompt"}]
                     :pinned   {:model "m"}})]
      (assertions
        "the supplied messages replace the seed's"
        (:messages out) => [{:role :user :content "a DIFFERENT prompt"}]
        "and are what the backend saw"
        (:messages @(:last-request backend)) => [{:role :user :content "a DIFFERENT prompt"}])))

  (component "throws when no seed was captured at the coordinates"
    (let [store (store-with-seed)]
      (assertions
        "a missing seed is a clear error, not a silent nil send"
        (replay/refine-node store "s" :writer 9 {:backend (recording-backend {})})
        =throws=> clojure.lang.ExceptionInfo))))

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
