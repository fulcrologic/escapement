(ns escapement.tools.input-keys-test
  "Tool input arrives as JSON: every provider hands back string keys at some
   depth (Anthropic/OpenAI keywordize only the top level; the claude CLI and
   Codex keywordize every level, including open `:map-of :string` data). These
   specs pin the schema-driven key decoding that normalizes all of them."
  (:require
    [cheshire.core :as json]
    [escapement.llm.api :as api]
    [escapement.llm.claude-cli.translate :as cc]
    [escapement.llm.openai :as openai]
    [escapement.llm.openai-codex.translate :as codex]
    [escapement.tools.input-keys :as ik]
    [fulcro-spec.core :refer [=> assertions component specification]]
    [malli.core :as m]))

(def claims-schema
  [:map
   [:claims [:vector [:map
                      [:claim :string]
                      [:evidence {:optional true} [:vector [:map [:path :string]]]]]]]
   [:scores [:map-of :string :int]]
   [:note {:optional true} [:maybe [:map [:text :string]]]]])

(specification "decode-keys"
  (assertions
    "keywordizes declared keys of maps nested in vectors, at every depth"
    (ik/decode-keys claims-schema
      {:claims [{"claim" "x" "evidence" [{"path" "a.clj"}]}] :scores {}})
    => {:claims [{:claim "x" :evidence [{:path "a.clj"}]}] :scores {}}
    "keywordizes declared keys when the top level is still string-keyed"
    (ik/decode-keys claims-schema {"claims" [] "scores" {}})
    => {:claims [] :scores {}}
    "keywordizes declared keys inside a :maybe map"
    (ik/decode-keys claims-schema {:claims [] :scores {} :note {"text" "hi"}})
    => {:claims [] :scores {} :note {:text "hi"}}
    "leaves a nil :maybe value alone"
    (ik/decode-keys claims-schema {:claims [] :scores {} :note nil})
    => {:claims [] :scores {} :note nil}
    "keeps :map-of :string keys as strings"
    (ik/decode-keys claims-schema {:claims [] :scores {"a" 1}})
    => {:claims [] :scores {"a" 1}}
    "restores :map-of :string keys a provider already keywordized"
    (ik/decode-keys claims-schema {:claims [] :scores {:a 1 :b/c 2}})
    => {:claims [] :scores {"a" 1 "b/c" 2}}
    "keeps undeclared keys exactly as they arrived"
    (ik/decode-keys claims-schema {:claims [{"claim" "x" "extra" 1}] :scores {}})
    => {:claims [{:claim "x" "extra" 1}] :scores {}}
    "passes through keys that are already keywords"
    (ik/decode-keys claims-schema {:claims [{:claim "x"}] :scores {}})
    => {:claims [{:claim "x"}] :scores {}}
    "matches a qualified declared key by its JSON name"
    (ik/decode-keys [:map [:a/b [:map [:c/d :int]]]] {"a/b" {"c/d" 1}})
    => {:a/b {:c/d 1}}
    "matches a declared key carrying Clojure punctuation"
    (ik/decode-keys [:map [:done? :boolean]] {"done?" true})
    => {:done? true}
    "returns non-map input unchanged"
    (ik/decode-keys claims-schema "nope") => "nope"))

(specification "decode-keys through :or and :multi"
  (let [or-schema    [:map [:item [:or
                                   [:map [:kind [:= "a"]] [:a :int]]
                                   [:map [:kind [:= "b"]] [:b :int]]]]]
        multi-schema [:map [:shapes [:vector
                                     [:multi {:dispatch :kind}
                                      ["circle" [:map [:kind :string] [:r :int]]]
                                      ["square" [:map [:kind :string] [:side :int]]]]]]]
        decoded      (ik/decode-keys multi-schema
                       {:shapes [{"kind" "circle" "r" 1} {"kind" "square" "side" 2}]})]
    (assertions
      "keywordizes the keys declared by the :or branch that matches"
      (ik/decode-keys or-schema {:item {"kind" "b" "b" 2}}) => {:item {:kind "b" :b 2}}
      "keywordizes before :multi dispatches, so each value lands in its branch"
      decoded => {:shapes [{:kind "circle" :r 1} {:kind "square" :side 2}]}
      "produces a value that validates"
      (m/validate multi-schema decoded) => true)))

;; ---------------------------------------------------------------------------
;; Every wire: what each provider's parser hands the conversation, for the
;; same model output, must decode to the same valid value.
;; ---------------------------------------------------------------------------

(def model-input
  "What a model emits for `claims-schema` (JSON, as it appears on the wire)."
  {"claims" [{"claim" "x" "evidence" [{"path" "a.clj"}]}]
   "scores" {"k" 3}})

(def expected {:claims [{:claim "x" :evidence [{:path "a.clj"}]}] :scores {"k" 3}})

(defn- tool-input [content] (:input (first (filter #(= :tool_use (:type %)) content))))

(specification "decode-keys over every provider wire"
  (let [anthropic (tool-input
                    (:content (api/anthropic-json->response
                                {"id"      "m"                                            "model" "claude" "stop_reason" "tool_use"
                                 "usage"   {"input_tokens" 1 "output_tokens" 1}
                                 "content" [{"type"  "tool_use"  "id" "t1" "name" "judge"
                                             "input" model-input}]}
                                "claude")))
        oai       (tool-input
                    (:content (openai/openai-json->response
                                {"model"   "gpt"
                                 "usage"   {"prompt_tokens" 1 "completion_tokens" 1}
                                 "choices" [{"finish_reason" "tool_calls"
                                             "message"       {"role"       "assistant"
                                                              "tool_calls" [{"id"       "c1"
                                                                             "type"     "function"
                                                                             "function" {"name"      "judge"
                                                                                         "arguments" (json/generate-string model-input)}}]}}]}
                                "gpt")))
        claude    (tool-input
                    (cc/structured-output->content
                      (json/parse-string
                        (json/generate-string {"tool_calls" [{"name" "judge" "input" model-input}]})
                        true)
                      #{"judge"} nil))
        codex-in  (tool-input
                    (codex/openai-items->anthropic-content
                      [{:type      "function_call"                    :call_id "c1" :name "judge"
                        :arguments (json/generate-string model-input)}]))]
    (component "raw provider output (the bug: nested keys are not the schema's)"
      (assertions
        "Anthropic keywordizes only the top level"
        (get-in anthropic [:claims 0]) => {"claim" "x" "evidence" [{"path" "a.clj"}]}
        "OpenAI-compatible keywordizes only the top level"
        (get-in oai [:claims 0]) => {"claim" "x" "evidence" [{"path" "a.clj"}]}
        "the claude CLI keywordizes open string-keyed data too"
        (:scores claude) => {:k 3}
        "Codex keywordizes open string-keyed data too"
        (:scores codex-in) => {:k 3}))
    (assertions
      "Anthropic input decodes to the schema's shape"
      (ik/decode-keys claims-schema anthropic) => expected
      "OpenAI-compatible input decodes to the schema's shape"
      (ik/decode-keys claims-schema oai) => expected
      "claude CLI input decodes to the schema's shape"
      (ik/decode-keys claims-schema claude) => expected
      "Codex input decodes to the schema's shape"
      (ik/decode-keys claims-schema codex-in) => expected
      "the decoded value validates"
      (m/validate claims-schema expected) => true)))
