(ns escapement.llm.openai-codex.translate
  "Translation layer between Escapement's Anthropic-shaped Request/Response
  and the OpenAI Responses API request/response bodies.

  All functions are pure; no I/O.  The entry point for callers is
  `build-request-body` (Anthropic Request → OpenAI body) and
  `openai-response->anthropic-response` (OpenAI stream result → Anthropic Response)."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn ?]]
    [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Model normalization

(def supported-models
  "Model ids the ChatGPT-account Codex endpoint actually accepts, flagship first.

   Verified live 2026-07-29. This backend authenticates ONLY with a ChatGPT
   subscription token, and that path rejects far more than the model catalog
   suggests — every `-codex`, `-pro`, and `-nano` variant, plus the previous
   generation of general models, comes back as:

     \"The '<id>' model is not supported when using Codex with a ChatGPT account.\"

   Confirmed rejected: `gpt-5.1`, `gpt-5.2`, `gpt-5.6`, `sol`, `gpt-5.1-codex`,
   `gpt-5.2-codex`, `gpt-5.1-codex-mini`, `gpt-5.3-codex`, `gpt-5.3-codex-spark`,
   `gpt-5.4-nano`, `gpt-5.4-pro`, `gpt-5.5-pro`, `gpt-5.6-sol-ultra`.

   The GPT-5.6 generation is the Luna/Terra/Sol family (least → most capable,
   released 2026-07-09). Only the SUFFIXED ids work — bare `gpt-5.6` is rejected,
   and Sol's \"Ultra\" high-effort mode is not a separate model id.

   `bb_test/codex_models_probe.clj` re-verifies this list against a live token."
  ["gpt-5.6-sol" "gpt-5.6-terra" "gpt-5.6-luna" "gpt-5.5" "gpt-5.4" "gpt-5.4-mini"])

(def default-model
  "The flagship of `supported-models`. Used whenever a caller names no model."
  (first supported-models))

(def ^:private legacy-model-aliases
  "Retired ids → the nearest model that still works.

   These were this backend's documented ids (and its old `:default-model`) but
   are now hard 400s on ChatGPT-account auth, so passing them through would
   guarantee failure. Substituting the nearest live model keeps existing charts
   and `.escapement.edn` aliases running; the Response reports what actually ran."
  {;; Every `-codex` variant — the family this backend was originally built
   ;; around — now 404s on ChatGPT-account auth. Coding work goes to Sol, which
   ;; OpenAI positions as its best coding model.
   "gpt-5.1-codex"       "gpt-5.6-sol"
   "gpt-5.1-codex-max"   "gpt-5.6-sol"
   "gpt-5.2-codex"       "gpt-5.6-sol"
   "gpt-5.3-codex"       "gpt-5.6-sol"
   "gpt-5-codex"         "gpt-5.6-sol"
   "gpt-5.1-codex-mini"  "gpt-5.6-luna"
   "gpt-5.3-codex-spark" "gpt-5.6-luna"
   "codex-mini-latest"   "gpt-5.6-luna"
   ;; Superseded general models map forward within their tier.
   "gpt-5"               "gpt-5.6-sol"
   "gpt-5.1"             "gpt-5.6-sol"
   "gpt-5.2"             "gpt-5.6-sol"
   "gpt-5.3"             "gpt-5.6-sol"
   "gpt-5.6"             "gpt-5.6-sol"      ; bare 5.6 is rejected; Sol is the flagship
   "gpt-5-mini"          "gpt-5.6-luna"
   ;; `-pro` / `-nano` tiers and Sol's Ultra MODE have no distinct ChatGPT-account
   ;; model id, so they degrade to the nearest real one.
   "gpt-5.6-sol-ultra"   "gpt-5.6-sol"
   "gpt-5.5-pro"         "gpt-5.6-sol"
   "gpt-5.4-pro"         "gpt-5.6-sol"
   "gpt-5.4-nano"        "gpt-5.6-luna"})

(def ^:private anthropic-model-aliases
  "Anthropic-style names → rough codex equivalents, so a chart written against
   Claude ids still runs here. Matched by PREFIX so dated ids resolve too."
  {"claude-opus"   "gpt-5.6-sol"
   "claude-fable"  "gpt-5.6-sol"
   "claude-sonnet" "gpt-5.6-terra"
   "claude-haiku"  "gpt-5.6-luna"})

(>defn normalize-model
  "Maps a requested model name onto an id the ChatGPT-account Codex endpoint
   accepts.

   - ids in `supported-models` pass through unchanged
   - retired ids (incl. every `-codex` variant) map to the nearest live model
     via `legacy-model-aliases` — they are hard 400s on this auth path, so
     passing them through would guarantee failure
   - Anthropic-style names map by family (`claude-opus-*` → flagship,
     `claude-haiku-*` → mini)
   - nil falls back to `default-model`
   - anything else passes through so the backend can surface its own error"
  [m]
  [(? :string) => :string]
  (let [m (some-> m str/trim)]
    (cond
      (str/blank? m) default-model
      (some #{m} supported-models) m
      (contains? legacy-model-aliases m) (get legacy-model-aliases m)
      :else
      (or (some (fn [[prefix target]] (when (str/starts-with? m prefix) target))
            anthropic-model-aliases)
        m))))

;;; ---------------------------------------------------------------------------
;;; Anthropic → OpenAI request translation

(>defn anthropic-tool->openai-tool
  "Converts an Anthropic-shaped tool definition to an OpenAI Responses API function tool."
  [{:keys [name description input-schema]}]
  [:map => :map]
  {:type        "function"
   :name        name
   :description (or description "")
   :strict      false
   :parameters  input-schema})

(defn- text-block->input-item
  "Converts a `:text` block to a role-appropriate OpenAI message item."
  [role block]
  {:type    "message"
   :role    (name role)
   :content [{:type (if (= :assistant role) "output_text" "input_text")
              :text (:text block)}]})

(defn- tool-result-block->input-item
  "Converts a `:tool_result` content block to an OpenAI `function_call_output` item."
  [block]
  {:type    "function_call_output"
   :call_id (:tool_use_id block)
   :output  (:content block)})

(defn- tool-use-block->input-item
  "Converts an assistant `:tool_use` block to an OpenAI `function_call` input item."
  [block]
  {:type      "function_call"
   :call_id   (:id block)
   :name      (:name block)
   :arguments (json/generate-string (:input block))})

(defn- thinking-block->input-item
  "Converts an assistant `:thinking` block (with `:signature`) to an OpenAI `reasoning` item."
  [block]
  (when (:signature block)
    {:type              "reasoning"
     :encrypted_content (:signature block)
     :summary           [{:type "summary_text" :text (:thinking block)}]}))

(defn- redacted-thinking-block->input-item
  "Converts an assistant `:redacted_thinking` block to an OpenAI `reasoning` item."
  [block]
  {:type              "reasoning"
   :encrypted_content (:data block)})

(defn- user-message->input-items
  "Expands a user message into a vector of OpenAI input items."
  [{:keys [content]}]
  (into []
    (keep (fn [block]
            (case (:type block)
              :text (text-block->input-item :user block)
              :tool_result (tool-result-block->input-item block)
              nil)))
    content))

(defn- assistant-message->input-items
  "Expands an assistant message into a vector of OpenAI input items."
  [{:keys [content]}]
  (into []
    (keep (fn [block]
            (case (:type block)
              :text (text-block->input-item :assistant block)
              :tool_use (tool-use-block->input-item block)
              :thinking (thinking-block->input-item block)
              :redacted_thinking (redacted-thinking-block->input-item block)
              nil)))
    content))

(>defn anthropic-messages->openai-input
  "Converts a vector of Anthropic-shaped messages to an OpenAI Responses API input array."
  [messages]
  [[:vector :map] => [:vector :map]]
  (into []
    (mapcat (fn [{:keys [role] :as msg}]
              (case role
                :user (user-message->input-items msg)
                :assistant (assistant-message->input-items msg)
                [])))
    messages))

(defn- has-cache-control?
  "Returns true when any message, tool, or the system string carries a cache-control marker."
  [{:keys [messages tools system-cache-control]}]
  (or system-cache-control
    (some :cache-control tools)
    (some (fn [m]
            (or (:cache-control m)
              (some :cache-control (:content m))))
      messages)))

(>defn build-request-body
  "Converts an Escapement LLM Request map to an OpenAI Responses API body map.

Notes:
* `:max-tokens` is silently ignored (not accepted by the ChatGPT backend).
* `:temperature`, `:top-p`, `:top-k`, `:stop-sequences` are silently dropped.
* `cache_control` markers are no-ops; a debug log line is emitted when present.
* The `:reasoning` key (if present) is passed through as-is; otherwise
 `{:effort \"medium\" :summary \"auto\"}` is used."
  [{:keys [model system messages tools reasoning tool-choice] :as request}]
  [:map => :map]
  (when (has-cache-control? request)
    ;; Must go through timbre (NOT a raw println/`*err*` write): in a TUI run the
    ;; OpenTUI sidecar / JLine alt-screen owns the terminal, and a stray stdout/
    ;; stderr write scrolls it — losing the header's top border and ghosting the
    ;; whole frame (the classic Tab-toggle corruption). `route-logs-to-file!`
    ;; sends timbre to the session log instead.
    (log/debug "[openai-codex] backend ignores cache-control markers; subscription billing uses backend's own caching"))
  (cond-> {:model        (normalize-model model)
           :instructions (or system "")
           :input        (anthropic-messages->openai-input messages)
           :tools        (mapv anthropic-tool->openai-tool (or tools []))
           :store        false
           :stream       true
           :include      ["reasoning.encrypted_content"]
           :reasoning    (or reasoning {:effort "medium" :summary "auto"})
           :text         {:verbosity "medium"}}
    (= :tool (:type tool-choice))
    (assoc :tool_choice {:type "function" :name (:name tool-choice)})))

;;; ---------------------------------------------------------------------------
;;; OpenAI → Anthropic response translation

(defn- openai-message-item->content-blocks
  "Converts an OpenAI `message` output item to Anthropic content blocks."
  [item]
  (into []
    (keep (fn [c]
            (when (= "output_text" (:type c))
              {:type :text :text (:text c)})))
    (:content item)))

(defn- openai-function-call-item->content-block
  "Converts an OpenAI `function_call` output item to an Anthropic `:tool_use` block."
  [item]
  (let [input (try (json/parse-string (:arguments item) true)
                   (catch Throwable _
                     (log/warn "[openai-codex] could not parse function_call arguments as JSON; using {}")
                     {}))]
    {:type  :tool_use
     :id    (:call_id item)
     :name  (:name item)
     :input input}))

(defn- openai-reasoning-item->content-block
  "Converts an OpenAI `reasoning` output item to an Anthropic `:thinking` block."
  [item]
  (when-let [enc (:encrypted_content item)]
    (let [text (->> (:summary item)
                 (filterv #(= "summary_text" (:type %)))
                 (mapv :text)
                 (str/join "\n"))]
      {:type      :thinking
       :thinking  text
       :signature enc})))

(>defn openai-items->anthropic-content
  "Converts a vector of OpenAI output items to Anthropic content blocks."
  [items]
  [[:vector :map] => [:vector :map]]
  (into []
    (mapcat (fn [item]
              (case (:type item)
                "message" (openai-message-item->content-blocks item)
                "function_call" [(openai-function-call-item->content-block item)]
                "reasoning" (if-let [b (openai-reasoning-item->content-block item)] [b] [])
                [])))
    items))

(>defn openai-response->anthropic-response
  "Converts an OpenAI stream result map to an Escapement Anthropic-shaped Response map.

- `stream-result`  — map from `post-responses-stream!`
- `request-model`  — the model string from the original request (fallback)"
  [{:keys [items usage stop-reason model]} request-model]
  [:map :string => :map]
  {:stop-reason      stop-reason
   :content          (openai-items->anthropic-content (or items []))
   :usage            {:input-tokens                (:input_tokens usage 0)
                      :output-tokens               (:output_tokens usage 0)
                      :cache-creation-input-tokens 0
                      :cache-read-input-tokens     (get-in usage [:input_tokens_details :cached_tokens] 0)}
   :model            (or model request-model)
   :backend-metadata {:backend :openai-codex}})
