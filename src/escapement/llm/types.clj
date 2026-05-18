(ns escapement.llm.types
  "Malli schemas for the LLM request/response protocol.

   Shapes mirror the Anthropic Messages API:

   - `Request`  : :model, :system, :messages, :tools, :max-tokens, optional :cache-control carriers
   - `Message`  : :role (:user | :assistant), :content (vector of ContentBlock)
   - `ContentBlock` tagged union by :type — :text | :image | :tool_use | :tool_result | :thinking
   - `Response` : :stop-reason, :content (vector of ContentBlock), :usage, :model, optional :backend-metadata
   - `Tool`     : :name, :description, :input-schema (a JSON Schema map)

   Backends honoring native cache_control (API) will pass markers through.
   Backends that don't (openai-codex) ignore them but the schema accepts them so calling code is portable."
  (:require
   [com.fulcrologic.guardrails.malli.core :refer [>defn => ?]]
   [malli.core :as m]
   [malli.error :as me]
   [malli.json-schema :as mjs]))

(def CacheControl
  "Anthropic-style cache_control marker. Optional on any cacheable item (system, message, tool)."
  [:map
   [:type [:enum :ephemeral]]
   [:ttl {:optional true} [:enum :5m :1h]]])

(def TextBlock
  [:map {:closed false}
   [:type [:= :text]]
   [:text :string]
   [:cache-control {:optional true} CacheControl]])

(def ToolUseBlock
  [:map {:closed false}
   [:type [:= :tool_use]]
   [:id :string]
   [:name :string]
   [:input :map]])

(def ToolResultBlock
  [:map {:closed false}
   [:type [:= :tool_result]]
   [:tool_use_id :string]
   [:content :string]
   [:is-error {:optional true} :boolean]])

(def ImageSource
  "Where an image's bytes come from. `:base64` carries inline data; `:url`
   references a remotely-hosted image (Anthropic fetches it server-side)."
  [:multi {:dispatch :type}
   [:base64 [:map {:closed false}
             [:type [:= :base64]]
             [:media-type [:enum "image/jpeg" "image/png" "image/gif" "image/webp"]]
             [:data :string]]]
   [:url [:map {:closed false}
          [:type [:= :url]]
          [:url :string]]]])

(def ImageBlock
  "Vision input block. Allowed on `:user` messages so a chart can hand a
   reference image to a vision-capable model. Backends without vision
   support should reject or drop it explicitly rather than silently."
  [:map {:closed false}
   [:type [:= :image]]
   [:source ImageSource]
   [:cache-control {:optional true} CacheControl]])

(def ThinkingBlock
  "Anthropic extended-thinking content block. Present on assistant responses
   when `:thinking` was enabled on the request. `:signature` is the opaque
   token the API requires you to echo back if you want to continue the same
   thinking thread on a subsequent turn."
  [:map {:closed false}
   [:type [:= :thinking]]
   [:thinking :string]
   [:signature {:optional true} :string]])

(def RedactedThinkingBlock
  "Some thinking is server-redacted; the block carries an opaque `:data`
   token to round-trip but no plain text."
  [:map {:closed false}
   [:type [:= :redacted_thinking]]
   [:data :string]])

(def ContentBlock
  [:multi {:dispatch :type}
   [:text TextBlock]
   [:image ImageBlock]
   [:tool_use ToolUseBlock]
   [:tool_result ToolResultBlock]
   [:thinking ThinkingBlock]
   [:redacted_thinking RedactedThinkingBlock]])

(def Role [:enum :user :assistant])

(def Message
  [:map
   [:role Role]
   [:content [:vector ContentBlock]]
   [:cache-control {:optional true} CacheControl]])

(def Tool
  [:map
   [:name :string]
   [:description :string]
   [:input-schema :map]
   [:cache-control {:optional true} CacheControl]])

(def Thinking
  "Extended-thinking control. Anthropic requires `:budget-tokens` and
   `:max-tokens` (on the Request) to be set together; `:budget-tokens` must
   be strictly less than `:max-tokens`."
  [:map {:closed true}
   [:type [:enum :enabled :disabled]]
   [:budget-tokens {:optional true} [:int {:min 1024}]]])

(def ToolChoice
  "Anthropic tool_choice. Three short forms plus a named-tool form."
  [:or
   [:enum :auto :any :none]
   [:map {:closed true}
    [:type [:= :tool]]
    [:name :string]
    [:disable-parallel-tool-use {:optional true} :boolean]]])

(def Metadata
  "Optional metadata passed to Anthropic. Currently only `:user-id` is
   honored on the wire (mapped to `\"user_id\"`)."
  [:map
   [:user-id {:optional true} :string]])

(def Request
  [:map
   [:model :string]
   [:system {:optional true} [:maybe :string]]
   [:messages [:vector Message]]
   [:tools {:optional true} [:maybe [:vector Tool]]]
   [:max-tokens {:optional true} [:int {:min 1}]]
   [:system-cache-control {:optional true} CacheControl]
   ;; Sampling parameters (Anthropic accepts 0 < temperature <= 1; top-p in
   ;; (0, 1]; top-k a positive int). We don't constrain ranges in the schema
   ;; — let the model server reject out-of-range values, since they change.
   [:temperature    {:optional true} number?]
   [:top-p          {:optional true} number?]
   [:top-k          {:optional true} pos-int?]
   [:stop-sequences {:optional true} [:vector :string]]
   ;; Extended thinking. When :type is :enabled, :budget-tokens is required
   ;; AND :max-tokens on the Request must be > :budget-tokens.
   [:thinking       {:optional true} Thinking]
   ;; Tool-choice forcing.
   [:tool-choice    {:optional true} ToolChoice]
   ;; Optional audit/metadata.
   [:metadata       {:optional true} Metadata]
   ;; Extension key for conversation tracking / prompt cache key.
   [:conversation/id {:optional true} [:or :string :keyword :uuid]]])

(def StopReason
  "Including newer Anthropic stop reasons:
     * `:pause_turn`  — server-side pause; client should send another request
                       with the same conversation state to continue.
     * `:refusal`     — model refused to respond."
  [:enum :end_turn :max_tokens :tool_use :stop_sequence :pause_turn :refusal])

(def Usage
  [:map
   [:input-tokens {:optional true} :int]
   [:output-tokens {:optional true} :int]
   [:cache-creation-input-tokens {:optional true} :int]
   [:cache-read-input-tokens {:optional true} :int]])

(def Response
  [:map
   [:stop-reason StopReason]
   [:content [:vector ContentBlock]]
   [:usage Usage]
   [:model :string]
   [:backend-metadata {:optional true} :map]])

(>defn validate-request
       "Returns nil when `request` matches the Request schema; otherwise returns a humanized error map."
       [request]
       [:any => (? :any)]
       (when-not (m/validate Request request)
         (me/humanize (m/explain Request request))))

(>defn validate-response
       "Returns nil when `response` matches the Response schema; otherwise returns a humanized error map."
       [response]
       [:any => (? :any)]
       (when-not (m/validate Response response)
         (me/humanize (m/explain Response response))))

(>defn malli->json-schema
       "Convert a Malli `schema` to a JSON Schema map suitable for use as Tool `:input-schema`."
       [schema]
       [:any => :map]
       (mjs/transform schema))
