(ns escapement.llm.claude-p-test
  (:require
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [escapement.llm.claude-p :as cp]
   [escapement.llm.protocol :as proto]
   [escapement.llm.types :as types]
   [fulcro-spec.core :refer [specification assertions component =>]]))

(def fixture-path "test/resources/claude-p-sample.json")

(defn- load-fixture []
  (json/parse-string (slurp (io/file fixture-path)) true))

(defn- claude-available?
  "True when the live integration test should run.

   We require an explicit `CLAUDE_AVAILABLE=1` opt-in rather than auto-detecting via
   `which claude`, because if the JVM is launched from a process tree that already
   has an interactive `claude` session attached, the child `claude -p` invocation can
   hang waiting on a session lock. Set the env var only when you want this test to run."
  []
  (= "1" (System/getenv "CLAUDE_AVAILABLE")))

(specification "parse-claude-p-response (pure)"
               (let [parsed   (load-fixture)
                     response (cp/parse-claude-p-response parsed "claude-opus")]
                 (assertions
                  "produces a Malli-valid Response"
                  (types/validate-response response) => nil
                  "stop-reason is :end_turn for the fixture"
                  (:stop-reason response) => :end_turn
                  "synthesizes a single :text content block from :result"
                  (count (:content response)) => 1
                  "the text matches the fixture's :result"
                  (get-in response [:content 0 :text]) => (:result parsed)
                  "captures the session-id in backend-metadata"
                  (get-in response [:backend-metadata :session-id]) => (:session_id parsed)
                  "captures usage tokens"
                  (get-in response [:usage :input-tokens]) => (get-in parsed [:usage :input_tokens])
                  (get-in response [:usage :output-tokens]) => (get-in parsed [:usage :output_tokens])
                  (get-in response [:usage :cache-creation-input-tokens])
                  => (get-in parsed [:usage :cache_creation_input_tokens])))

               (component "error paths"
                          (assertions
                           "throws when :is_error is true"
                           (try
                             (cp/parse-claude-p-response {:is_error true :api_error_status "boom"} "m")
                             :no-throw
                             (catch Exception _ :threw)) => :threw)))

(specification "Live claude -p round-trip"
               (if (claude-available?)
                 (component "real CLI"
                            (let [backend (cp/new-backend)
                                  req     {:model    "claude-haiku-4-5"
                                           :messages [{:role :user :content [{:type :text :text "Reply with exactly the word OK"}]}]}
                                  resp    (proto/send-turn backend req)]
                              (assertions
                               "produces a Malli-valid Response"
                               (types/validate-response resp) => nil
                               "has non-empty text in the first content block"
                               (pos? (count (get-in resp [:content 0 :text]))) => true
                               "backend-metadata identifies claude-p"
                               (get-in resp [:backend-metadata :backend]) => :claude-p)))
                 (assertions
                  "claude CLI not available — skipping live test"
                  true => true)))
