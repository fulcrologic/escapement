(ns deep-cookie.tools.protocol-test
  (:require
   [deep-cookie.tools.protocol :as tp]
   [fulcro-spec.core :refer [specification assertions component =>]]))

(defrecord EchoTool []
  tp/Tool
  (tool-name    [_] :test/echo)
  (description  [_] "Echo the supplied :msg.")
  (input-schema [_] [:map {:closed true} [:msg :string]])
  (invoke [_ {:keys [msg]}] {:result msg :is-error false}))

(specification "Tool registry"
               (component "register/lookup/all-tools round-trip"
                          (let [reg (tp/new-registry [(->EchoTool)])]
                            (assertions
                             "lookup by keyword name"
                             (tp/tool-name (tp/lookup reg :test/echo)) => :test/echo
                             "all-tools returns a vector of registered tools"
                             (mapv tp/tool-name (tp/all-tools reg)) => [:test/echo]
                             "unknown lookup returns nil"
                             (tp/lookup reg :nope) => nil)))

               (component "dispatch validates input"
                          (let [reg (tp/new-registry [(->EchoTool)])]
                            (assertions
                             "happy path returns result + non-error"
                             (tp/dispatch reg :test/echo {:msg "hi"}) => {:result "hi" :is-error false}
                             "validation failure is reported as is-error"
                             (:is-error (tp/dispatch reg :test/echo {:msg 42})) => true
                             "unknown tool produces an error result"
                             (:is-error (tp/dispatch reg :missing/tool {})) => true)))

               (component "tool->anthropic-tool-def shape"
                          (let [def (tp/tool->anthropic-tool-def (->EchoTool))]
                            (assertions
                             "renders qualified keyword as underscore-joined name"
                             (:name def) => "test_echo"
                             "carries the description"
                             (:description def) => "Echo the supplied :msg."
                             "input-schema (dash form, internal) is JSON-Schema-shaped (a map with :type \"object\")"
                             (get-in def [:input-schema :type]) => "object"
                             "no snake_case :input_schema key leaks out of the protocol layer"
                             (contains? def :input_schema) => false))))
