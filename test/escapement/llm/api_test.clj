(ns escapement.llm.api-test
  (:require
   [cheshire.core :as json]
   [escapement.llm.api :as api]
   [escapement.llm.protocol :as proto]
   [escapement.llm.types :as types]
   [fulcro-spec.core :refer [specification assertions component =>]]))

(def sample-request
  {:model     "claude-sonnet-4-6"
   :system    "You are helpful."
   :system-cache-control {:type :ephemeral}
   :messages  [{:role :user
                :content [{:type :text :text "Hello"}]}
               {:role :assistant
                :content [{:type :text :text "Hi"}]}
               {:role :user
                :content [{:type :text :text "Tell me a joke"
                           :cache-control {:type :ephemeral :ttl :5m}}]}]
   :tools     [{:name "get_weather"
                :description "Get the weather"
                :input-schema {:type "object"
                               :properties {:location {:type "string"}}
                               :required ["location"]}
                :cache-control {:type :ephemeral}}]
   :max-tokens 1024})

(specification "request->anthropic-json"
               (let [wire (api/request->anthropic-json sample-request)]
                 (assertions
                  "uses snake_case top-level keys"
                  (get wire "model")      => "claude-sonnet-4-6"
                  (get wire "max_tokens") => 1024
                  "serializes system as a text-block array when system-cache-control is set"
                  (get-in wire ["system" 0 "type"])                    => "text"
                  (get-in wire ["system" 0 "text"])                    => "You are helpful."
                  (get-in wire ["system" 0 "cache_control" "type"])    => "ephemeral"
                  "translates messages with role+content blocks"
                  (count (get wire "messages")) => 3
                  (get-in wire ["messages" 0 "role"]) => "user"
                  (get-in wire ["messages" 0 "content" 0 "type"]) => "text"
                  (get-in wire ["messages" 0 "content" 0 "text"]) => "Hello"
                  "passes cache_control through on message blocks"
                  (get-in wire ["messages" 2 "content" 0 "cache_control" "type"]) => "ephemeral"
                  (get-in wire ["messages" 2 "content" 0 "cache_control" "ttl"])  => "5m"
                  "serializes tools with input_schema and cache_control"
                  (get-in wire ["tools" 0 "name"])                  => "get_weather"
                  (get-in wire ["tools" 0 "input_schema" :type])    => "object"
                  (get-in wire ["tools" 0 "cache_control" "type"])  => "ephemeral")))

(def sample-anthropic-response
  {"id" "msg_01abc"
   "type" "message"
   "role" "assistant"
   "model" "claude-sonnet-4-6"
   "content" [{"type" "text" "text" "Hello back!"}
              {"type" "tool_use" "id" "toolu_1" "name" "get_weather"
               "input" {"location" "Paris"}}]
   "stop_reason" "tool_use"
   "usage" {"input_tokens" 12
            "output_tokens" 8
            "cache_creation_input_tokens" 0
            "cache_read_input_tokens" 100}})

(specification "anthropic-json->response"
               (let [resp (api/anthropic-json->response sample-anthropic-response "claude-sonnet-4-6")]
                 (assertions
                  "produces a Malli-valid Response"
                  (types/validate-response resp) => nil
                  "maps stop_reason to keyword"
                  (:stop-reason resp) => :tool_use
                  "translates content blocks back to our shape"
                  (count (:content resp)) => 2
                  (get-in resp [:content 0 :type]) => :text
                  (get-in resp [:content 0 :text]) => "Hello back!"
                  (get-in resp [:content 1 :type]) => :tool_use
                  (get-in resp [:content 1 :id]) => "toolu_1"
                  ;; Bug #1 (M6.5): tool_use :input must be keyword-keyed so chart authors
                  ;; can use keyword-key Malli schemas (e.g. `[:map [:location :string]]`).
                  (get-in resp [:content 1 :input]) => {:location "Paris"}
                  "surfaces usage including cache token counts"
                  (get-in resp [:usage :cache-read-input-tokens]) => 100
                  (get-in resp [:usage :input-tokens]) => 12
                  "model echoed"
                  (:model resp) => "claude-sonnet-4-6"
                  "backend-metadata tagged"
                  (get-in resp [:backend-metadata :backend]) => :api
                  (get-in resp [:backend-metadata :message-id]) => "msg_01abc")))

(specification "validate-request accepts requests with tools (regression: bug #2)"
               ;; The internal Tool schema requires :input-schema (dash). Verify that the
               ;; canonical sample-request — which uses dash form — passes validation, and
               ;; that the same request flows into request->anthropic-json producing the
               ;; wire-form "input_schema" key.
               (let [wire (api/request->anthropic-json sample-request)]
                 (assertions
                  "Request with a tool defined via :input-schema validates cleanly"
                  (types/validate-request sample-request) => nil
                  "Wire form has snake_case \"input_schema\""
                  (contains? (get-in wire ["tools" 0]) "input_schema") => true
                  "Wire form does not leak the internal dash-keyword"
                  (contains? (get-in wire ["tools" 0]) "input-schema") => false)))

(specification "request->anthropic-json — extended options"
               (component "sampling parameters and stop sequences land on the wire as snake_case"
                          (let [req  (assoc sample-request
                                            :temperature    0.4
                                            :top-p          0.9
                                            :top-k          40
                                            :stop-sequences ["STOP" "<END>"])
                                wire (api/request->anthropic-json req)]
                            (assertions
                             "temperature"     (get wire "temperature")     => 0.4
                             "top_p"           (get wire "top_p")           => 0.9
                             "top_k"           (get wire "top_k")           => 40
                             "stop_sequences"  (get wire "stop_sequences")  => ["STOP" "<END>"])))

               (component "extended thinking shows up as a wire object with budget_tokens"
                          (let [req  (assoc sample-request
                                            :max-tokens 8000
                                            :thinking   {:type :enabled :budget-tokens 4096})
                                wire (api/request->anthropic-json req)]
                            (assertions
                             "thinking wire shape"
                             (get wire "thinking")
                             => {"type" "enabled" "budget_tokens" 4096})))

               (component "tool-choice short forms become tagged wire objects"
                          (let [wire-any  (api/request->anthropic-json (assoc sample-request :tool-choice :any))
                                wire-auto (api/request->anthropic-json (assoc sample-request :tool-choice :auto))
                                wire-none (api/request->anthropic-json (assoc sample-request :tool-choice :none))]
                            (assertions
                             "any"  (get wire-any  "tool_choice") => {"type" "any"}
                             "auto" (get wire-auto "tool_choice") => {"type" "auto"}
                             "none" (get wire-none "tool_choice") => {"type" "none"})))

               (component "tool-choice with a named tool"
                          (let [wire (api/request->anthropic-json
                                      (assoc sample-request :tool-choice {:type :tool :name "get_weather"
                                                                          :disable-parallel-tool-use true}))]
                            (assertions
                             "tool_choice"
                             (get wire "tool_choice")
                             => {"type" "tool" "name" "get_weather" "disable_parallel_tool_use" true})))

               (component "metadata user-id is renamed to snake_case"
                          (let [wire (api/request->anthropic-json
                                      (assoc sample-request :metadata {:user-id "alice-42"}))]
                            (assertions
                             "metadata.user_id"  (get-in wire ["metadata" "user_id"]) => "alice-42")))

               (component "thinking response block round-trips"
                          (let [parsed {"stop_reason" "end_turn"
                                        "model"       "claude-opus-4-7"
                                        "usage"       {"input_tokens" 10 "output_tokens" 20}
                                        "content"     [{"type" "thinking" "thinking" "step by step..."
                                                        "signature" "sig-abc"}
                                                       {"type" "text"     "text"     "final answer"}]}
                                resp   (api/anthropic-json->response parsed "claude-opus-4-7")
                                [thnk txt] (:content resp)]
                            (assertions
                             "thinking block parsed"
                             thnk => {:type :thinking :thinking "step by step..." :signature "sig-abc"}
                             "text block follows"
                             txt  => {:type :text :text "final answer"})))

               (component "redacted_thinking response block round-trips"
                          (let [parsed {"stop_reason" "end_turn"
                                        "model"       "claude-opus-4-7"
                                        "usage"       {"input_tokens" 1 "output_tokens" 1}
                                        "content"     [{"type" "redacted_thinking" "data" "ENC123"}
                                                       {"type" "text" "text" "ok"}]}
                                resp   (api/anthropic-json->response parsed "claude-opus-4-7")]
                            (assertions
                             "redacted_thinking parsed"
                             (first (:content resp))
                             => {:type :redacted_thinking :data "ENC123"})))

               (component "new stop reasons :pause_turn and :refusal are recognized"
                          (let [pause (api/anthropic-json->response
                                       {"stop_reason" "pause_turn" "model" "m" "usage" {} "content" []}
                                       "m")
                                refusal (api/anthropic-json->response
                                         {"stop_reason" "refusal" "model" "m" "usage" {} "content" []}
                                         "m")]
                            (assertions
                             ":pause_turn"  (:stop-reason pause)   => :pause_turn
                             ":refusal"     (:stop-reason refusal) => :refusal))))

(specification "auth-headers"
               (assertions
                "explicit :bearer uses Authorization header"
                (api/auth-headers {:auth-mode :bearer :api-key "sk-zai-xyz"})
                => {"Authorization" "Bearer sk-zai-xyz"}
                "explicit :x-api-key uses x-api-key + anthropic-version"
                (api/auth-headers {:auth-mode :x-api-key :api-key "sk-anth"})
                => {"x-api-key" "sk-anth" "anthropic-version" "2023-06-01"}
                "honors :anthropic-version override"
                (get (api/auth-headers {:auth-mode :x-api-key :api-key "k" :anthropic-version "2099-01-01"})
                     "anthropic-version") => "2099-01-01"
                "auto-sniff z.ai -> bearer"
                (api/auth-headers {:base-url "https://api.z.ai/api/anthropic" :api-key "zk"})
                => {"Authorization" "Bearer zk"}
                "auto-sniff anthropic.com -> x-api-key"
                (get (api/auth-headers {:base-url "https://api.anthropic.com" :api-key "ak"})
                     "x-api-key") => "ak"))

;;; --- Live tests (gated on env vars) -----------------------------------------

(defn- short-prompt []
  {:messages [{:role :user
               :content [{:type :text :text "Reply with exactly: OK"}]}]
   :max-tokens 32})

(specification "live Anthropic API (gated on ANTHROPIC_API_KEY)"
               (if-let [key (System/getenv "ANTHROPIC_API_KEY")]
                 (let [backend (api/new-backend {:base-url      "https://api.anthropic.com"
                                                 :api-key       key
                                                 :default-model "claude-sonnet-4-6"})
                       resp    (proto/send-turn backend (short-prompt))]
                   (assertions
                    "live Anthropic response is Malli-valid"
                    (types/validate-response resp) => nil
                    "has at least one text block"
                    (boolean (some #(= :text (:type %)) (:content resp))) => true))
                 (do (println "[skip] ANTHROPIC_API_KEY not set; skipping live Anthropic test")
                     (assertions "skipped" true => true))))

(specification "live z.ai Anthropic-compat API (gated on ZAI_API_KEY)"
               (if-let [key (System/getenv "ZAI_API_KEY")]
                 (let [backend (api/new-backend {:base-url      "https://api.z.ai/api/anthropic"
                                                 :api-key       key
                                                 :default-model "glm-4.6"})
                       resp    (proto/send-turn backend (short-prompt))]
                   (assertions
                    "live z.ai response is Malli-valid"
                    (types/validate-response resp) => nil
                    "has at least one non-empty text block"
                    (boolean (some #(and (= :text (:type %))
                                         (seq (:text %))) (:content resp))) => true))
                 (do (println "[skip] ZAI_API_KEY not set; skipping live z.ai test")
                     (assertions "skipped" true => true))))
