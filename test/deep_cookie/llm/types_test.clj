(ns deep-cookie.llm.types-test
  (:require
   [deep-cookie.llm.types :as types]
   [fulcro-spec.core :refer [specification assertions =>]]))

(def valid-request
  {:model    "claude-opus"
   :system   "Be helpful."
   :messages [{:role :user :content [{:type :text :text "Hi"}]}]})

(def valid-response
  {:stop-reason :end_turn
   :content     [{:type :text :text "Hello"}]
   :usage       {:input-tokens 5 :output-tokens 2}
   :model       "claude-opus"})

(specification "validate-request"
               (assertions
                "returns nil for a valid request"
                (types/validate-request valid-request) => nil
                "returns nil when system is omitted"
                (types/validate-request (dissoc valid-request :system)) => nil
                "accepts tool_use content blocks"
                (types/validate-request
                 (assoc valid-request :messages
                        [{:role    :assistant
                          :content [{:type :tool_use :id "t1" :name "fs/read" :input {:path "foo"}}]}]))
                => nil
                "accepts tools with input-schema"
                (types/validate-request
                 (assoc valid-request :tools
                        [{:name "fs/read" :description "read a file"
                          :input-schema {:type "object" :properties {:path {:type "string"}}}}]))
                => nil
                "rejects missing :model"
                (some? (types/validate-request (dissoc valid-request :model))) => true
                "rejects bad role"
                (some? (types/validate-request
                        (assoc-in valid-request [:messages 0 :role] :system))) => true
                "rejects unknown content block type"
                (some? (types/validate-request
                        (assoc-in valid-request [:messages 0 :content]
                                  [{:type :video :url "x"}]))) => true))

(specification "validate-response"
               (assertions
                "returns nil for a valid response"
                (types/validate-response valid-response) => nil
                "accepts backend-metadata"
                (types/validate-response (assoc valid-response :backend-metadata {:backend :claude-p})) => nil
                "rejects unknown stop-reason"
                (some? (types/validate-response (assoc valid-response :stop-reason :nope))) => true
                "rejects missing :model"
                (some? (types/validate-response (dissoc valid-response :model))) => true))

(specification "malli->json-schema"
               (let [js (types/malli->json-schema [:map [:path :string]])]
                 (assertions
                  "produces an object schema"
                  (:type js) => "object"
                  "marks required keys"
                  (:required js) => [:path]
                  "lists properties"
                  (get-in js [:properties :path :type]) => "string")))
