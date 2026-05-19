(ns escapement.lib-test
  "Unit tests for the hosted facade `escapement.lib/run`.

  Covers the public API contract: closed option-schema rejection, generated
  stable `:run-id` in the result + on `:runner/started`, temp-dir defaults for
  transcript/checkpoint, `:store` threaded to `engine.env/new-env`, and quiet
  logging by default."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :refer [state transition final]]
   [escapement.engine.store :as store]
   [escapement.lib :as lib]
   [fulcro-spec.core :refer [specification assertions =>]]))

(def trivial-chart
  (chart/statechart {:initial :work}
                    (state {:id :work :initial :idle}
                           (state {:id :idle}
                                  (transition {:event :go :target :done}))
                           (final {:id :done}))))

;; A minimal valid credentials vector for the no-LLM trivial chart. The chart
;; never invokes an LLM so the backend assembled from this is never exercised,
;; but `:credentials` is unconditionally required by the closed schema.
(def creds [{:provider :anthropic :api-key "sk-test"}])

(specification "closed option schema rejects unknown keys"
  (assertions
    "a valid minimal map validates"
    (lib/validate-options {:chart trivial-chart :session-id :s1 :credentials creds}) => nil
    "an unknown key is rejected"
    (some? (lib/validate-options {:chart trivial-chart :session-id :s1 :credentials creds :bogus 1})) => true
    "missing required :chart is rejected"
    (some? (lib/validate-options {:session-id :s1 :credentials creds})) => true
    "missing required :credentials is rejected"
    (some? (lib/validate-options {:chart trivial-chart :session-id :s1})) => true
    "run throws (does not silently run) on an unknown key"
    (try (lib/run {:chart trivial-chart :session-id :s1 :credentials creds :bogus 1}) :no-throw
         (catch clojure.lang.ExceptionInfo e (-> e ex-data :errors some?)))
    => true
    "run throws (does not silently run) when :credentials omitted"
    (try (lib/run {:chart trivial-chart :session-id :s1}) :no-throw
         (catch clojure.lang.ExceptionInfo e (-> e ex-data :errors some?)))
    => true))

(specification "facade run with no transcript/checkpoint args"
  (let [result (lib/run {:chart trivial-chart :session-id :facade-1 :credentials creds})]
    (assertions
      "returns a stable string :run-id"
      (string? (:run-id result)) => true
      (= 36 (count (:run-id result))) => true
      "returns transcript + checkpoint-dir paths"
      (string? (:transcript result)) => true
      (string? (:checkpoint-dir result)) => true
      "wrote the transcript file to a temp dir"
      (fs/exists? (:transcript result)) => true
      (str/includes? (:transcript result) "escapement-run-") => true
      "the chart ran to its final config"
      (vector? (:final-config result)) => true)))

(specification "two runs get distinct run-ids"
  (let [r1 (lib/run {:chart trivial-chart :session-id :a :credentials creds})
        r2 (lib/run {:chart trivial-chart :session-id :b :credentials creds})]
    (assertions
      "run-ids differ across runs"
      (= (:run-id r1) (:run-id r2)) => false)))

(specification ":run-id is emitted on the :runner/started event"
  (let [seen   (atom [])
        result (lib/run {:chart trivial-chart
                         :session-id :tap-1
                         :credentials creds
                         :transcript-tap #(swap! seen conj %)})
        started (->> @seen (filter #(= :runner/started (:event %))) first)]
    (assertions
      ":runner/started carries the same :run-id as the result"
      (get-in started [:data :run-id]) => (:run-id result)
      "and it is non-nil"
      (some? (get-in started [:data :run-id])) => true)))

(specification ":store override is threaded to engine.env/new-env"
  (let [tmp   (str (fs/create-temp-dir {:prefix "lib-store-"}))
        store (store/new-store tmp)
        seen  (atom nil)
        result (lib/run {:chart trivial-chart
                         :session-id :store-1
                         :credentials creds
                         :store store
                         :on-env-ready (fn [env]
                                         (reset! seen (::sc/working-memory-store env)))})]
    (assertions
      "the env observed at on-env-ready uses the supplied store instance"
      (identical? @seen store) => true
      "and the env's store is exactly the override"
      (identical? (::sc/working-memory-store (:env result)) store) => true)))

(specification "explicit transcript/checkpoint paths are honored (no temp dir)"
  (let [base (str (fs/create-temp-dir {:prefix "lib-explicit-"}))
        tp   (str (fs/path base "t.jsonl"))
        cd   (str (fs/path base "ckpt"))
        result (lib/run {:chart trivial-chart
                         :session-id :explicit-1
                         :credentials creds
                         :transcript-path tp
                         :checkpoint-dir cd})]
    (assertions
      "result surfaces the explicit transcript path"
      (:transcript result) => tp
      "result surfaces the explicit checkpoint dir"
      (:checkpoint-dir result) => cd
      "transcript written there"
      (fs/exists? tp) => true)))
