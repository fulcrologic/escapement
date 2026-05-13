(ns deep-cookie.llm.types
  "Malli schemas for the LLM request/response protocol.

   Shapes mirror the Anthropic Messages API:

   - `Request`  : :model, :system, :messages, :tools, :max-tokens, optional :cache-control carriers
   - `Message`  : :role (:user | :assistant), :content (vector of ContentBlock)
   - `ContentBlock` tagged union by :type — :text | :tool_use | :tool_result
   - `Response` : :stop-reason, :content (vector of ContentBlock), :usage, :model, optional :backend-metadata
   - `Tool`     : :name, :description, :input-schema (a JSON Schema map)

   Backends honoring native cache_control (API) will pass markers through.
   Backends that don't (`claude-p`) ignore them but the schema accepts them so calling code is portable."
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

(def ContentBlock
  [:multi {:dispatch :type}
   [:text TextBlock]
   [:tool_use ToolUseBlock]
   [:tool_result ToolResultBlock]])

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

(def Request
  [:map
   [:model :string]
   [:system {:optional true} [:maybe :string]]
   [:messages [:vector Message]]
   [:tools {:optional true} [:maybe [:vector Tool]]]
   [:max-tokens {:optional true} [:int {:min 1}]]
   [:system-cache-control {:optional true} CacheControl]
   ;; Extension key used by claude-p backend to track --resume mapping.
   [:conversation/id {:optional true} [:or :string :keyword :uuid]]])

(def StopReason [:enum :end_turn :max_tokens :tool_use :stop_sequence])

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
