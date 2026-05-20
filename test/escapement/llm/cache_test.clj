(ns escapement.llm.cache-test
  (:require
    [escapement.llm.cache :as cache]
    [escapement.llm.protocol :as proto]
    [fulcro-spec.core :refer [=> assertions component specification]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir []
  (str (Files/createTempDirectory "llm-cache-test" (into-array FileAttribute []))))

(defrecord CountingBackend [call-count response]
  proto/LLMBackend
  (send-turn [_ _request]
    (swap! call-count inc)
    response))

(def request1
  {:model    "claude-opus"
   :system   "S"
   :messages [{:role :user :content [{:type :text :text "Hi"}]}]})

(def response1
  {:stop-reason :end_turn
   :content     [{:type :text :text "Hello"}]
   :usage       {:input-tokens 1 :output-tokens 1}
   :model       "claude-opus"})

(specification "cache-key"
  (assertions
    "is deterministic"
    (cache/cache-key request1) => (cache/cache-key request1)
    "ignores non-canonical keys like :max-tokens"
    (cache/cache-key request1) => (cache/cache-key (assoc request1 :max-tokens 100))
    "differs when messages differ"
    (= (cache/cache-key request1)
      (cache/cache-key (update-in request1 [:messages 0 :content 0 :text] str "!"))) => false))

(specification "CachingBackend"
  (component "first call invokes inner; second returns from cache"
    (let [calls   (atom 0)
          inner   (->CountingBackend calls response1)
          backend (cache/caching-backend inner (tmp-dir))
          r1      (proto/send-turn backend request1)
          r2      (proto/send-turn backend request1)]
      (assertions
        "first call delegates to inner backend"
        @calls => 1
        "second call does NOT call inner backend"
        (do (proto/send-turn backend request1) @calls) => 1
        "first response matches inner response"
        r1 => response1
        "cached response equals first response"
        r2 => r1)))

  (component "different requests miss the cache"
    (let [calls   (atom 0)
          inner   (->CountingBackend calls response1)
          backend (cache/caching-backend inner (tmp-dir))]
      (proto/send-turn backend request1)
      (proto/send-turn backend (assoc-in request1 [:messages 0 :content 0 :text] "Bye"))
      (assertions
        "each distinct request invokes inner"
        @calls => 2))))

(specification "enabled-by-env?"
  (assertions
    "is a boolean"
    (boolean? (cache/enabled-by-env?)) => true))
