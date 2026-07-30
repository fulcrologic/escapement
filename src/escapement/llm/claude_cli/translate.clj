(ns escapement.llm.claude-cli.translate
  "Pure translation between Escapement's Request/Response protocol and the
   `claude -p` (Claude Code CLI) stream-json wire format. Zero I/O — every
   function here is a value→value transform so the whole wire contract is
   unit-testable without a `claude` binary on PATH.

   The CLI is presented to Escapement as a plain **one-turn model**: it is
   invoked once per `send-turn`, with all built-in tools disabled, and the
   tool calls it wants come back through a JSON envelope that this namespace
   synthesizes and parses. Escapement keeps owning the agentic loop and keeps
   executing every tool itself, so a chart behaves identically here and on
   `:anthropic`.

   Why an envelope at all: `--tools \"\"` removes CC's built-in tools, and CC has
   no way to be handed *foreign* tool definitions. So escapement's tools are
   described to the model in the SYSTEM PROMPT (`tools-doc`) and the model's
   chosen calls are extracted into a schema-validated object — either by CC
   itself (`--json-schema`, the default) or by parsing a fenced ```json block
   (`:fenced-json`, the fallback for schemas CC's validator would reject).

   See `docs/` and the `## :claude-cli backend` section of `workingcontext.md`
   for the probe results this file encodes."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn ?]]
    [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Model normalization

(def cli-model-aliases
  "Bare model aliases the CLI's `--model` accepts directly."
  #{"fable" "opus" "sonnet" "haiku" "default" "opusplan"})

(def ^:private family-alias-patterns
  "Ordered `[pattern alias]` pairs mapping a dated/versioned Anthropic model id
   onto the CLI alias for its family."
  [[#"(?i)^claude-fable"  "fable"]
   [#"(?i)^claude-opus"   "opus"]
   [#"(?i)^claude-sonnet" "sonnet"]
   [#"(?i)^claude-haiku"  "haiku"]
   ;; `us.anthropic.claude-sonnet-…-v1:0` style Bedrock ids
   [#"(?i)claude-3-7-sonnet|claude-sonnet" "sonnet"]
   [#"(?i)claude-3-5-haiku|claude-haiku"   "haiku"]])

(>defn normalize-model
  "Returns the `--model` value to hand the CLI for Escapement `model`.

   The CLI resolves `--model` against **its own** registry and exits 1 on an id
   it does not know — `claude-sonnet-4-7`, for example, is rejected outright.
   Escapement model ids therefore family-map onto the bare CLI aliases
   (`claude-opus-4-1-20250805` → `\"opus\"`), which are always resolvable and
   always point at the current model for that family. Bare aliases pass
   through; anything non-Anthropic-looking passes through verbatim so the CLI
   can render its own error.

   The model that actually ran is reported back on the Response's `:model`
   (read off the assistant message), so the transcript records the truth even
   though the request was made with an alias."
  [model]
  [(? :string) => (? :string)]
  (when-not (str/blank? model)
    (let [m (str/trim model)]
      (if (contains? cli-model-aliases (str/lower-case m))
        (str/lower-case m)
        (or (some (fn [[pat alias]] (when (re-find pat m) alias)) family-alias-patterns)
          m)))))

;;; ---------------------------------------------------------------------------
;;; JSON-Schema subset support

(def supported-schema-keywords
  "JSON-Schema keywords the CLI's `--json-schema` validator was probed to
   accept (CC v2.1.220). Anything outside this set forces the `:fenced-json`
   envelope instead of risking an opaque startup death (exit 1, stderr only,
   no JSON on stdout).

   Everything `escapement.llm.types/malli->json-schema` emits — including the
   `oneOf` of `[:maybe X]`, the `additionalProperties:false` of a closed map,
   and the `minItems` of `[:vector {:min 1} …]` — is inside this set."
  #{"type" "properties" "required" "items" "additionalProperties"
    "description" "title" "default" "examples"
    "enum" "const"
    "oneOf" "anyOf" "allOf"
    "minItems" "maxItems" "uniqueItems"
    "minimum" "maximum" "exclusiveMinimum" "exclusiveMaximum" "multipleOf"
    "minLength" "maxLength" "pattern" "format"
    "minProperties" "maxProperties"})

(>defn unsupported-schema-keywords
  "Returns the sorted set of schema keywords appearing anywhere in `schema`
   that are outside `supported-schema-keywords`.

   `schema` is a JSON-Schema map (keys may be keywords or strings). Only map
   keys in *schema position* are inspected — the keys inside a `properties`
   map are user-chosen property names, not keywords, and are skipped."
  [schema]
  [:any => [:set :string]]
  (let [acc (volatile! (transient #{}))]
    (letfn [(walk-schema [node]
              (when (map? node)
                (doseq [[k v] node]
                  (let [ks (name k)]
                    (when-not (contains? supported-schema-keywords ks)
                      (vswap! acc conj! ks))
                    (case ks
                      ;; values are name→schema maps: skip the names, walk the schemas
                      ("properties" "patternProperties" "$defs" "definitions" "dependentSchemas")
                      (when (map? v) (run! walk-schema (vals v)))
                      ;; values are schema or vector-of-schema
                      ("items" "additionalProperties" "oneOf" "anyOf" "allOf" "not"
                        "if" "then" "else" "contains" "propertyNames" "unevaluatedProperties")
                      (cond
                        (sequential? v) (run! walk-schema v)
                        (map? v) (walk-schema v))
                      ;; anything else: only recurse into nested schema-ish maps
                      nil)))))]
      (walk-schema schema))
    (into (sorted-set) (persistent! @acc))))

;;; ---------------------------------------------------------------------------
;;; The tool-call envelope

(def envelope-text-key "assistant_text")
(def envelope-calls-key "tool_calls")

(defn- tool-branch
  "One `oneOf` branch of the envelope: a `{name, input}` object pinned to a
   single tool by `const`."
  [{:keys [name input-schema]}]
  {"type"                 "object"
   "additionalProperties" false
   "properties"           {"name"  {"const"       name
                                    "description" (str "Always the literal string \"" name "\".")}
                           "input" (or (not-empty input-schema) {"type" "object"})}
   "required"             ["name" "input"]})

(>defn envelope-schema
  "Synthesizes the `--json-schema` object for `tools` under `tool-choice`.

   Shape: `{assistant_text?: string, tool_calls?: [{name, input}]}`.

   `tool_calls` is deliberately **never** in `required`. A required array makes
   the model invent an entry to satisfy the schema, and Escapement then
   *executes it for real* (`handle-tool-use-block` dispatches `:fs/write` /
   `:shell/run` and posts chart events). When no call comes back we report
   `:end_turn`, which is the same \"model talked instead of calling the tool\"
   outcome every other backend produces and which existing charts handle.

   `tool-choice` shapes the branch set instead:
   - `:auto` / nil — every tool, any number of calls
   - `:any`        — every tool, `minItems` 1
   - `:none`       — no `tool_calls` property at all
   - `{:type :tool :name N}` — only N's branch, `minItems`/`maxItems` 1, which
     is what makes the `:verdict-schema` wrap-up work for free

   Returns **nil** when there is no tool to extract (no `:tools`, `:none`, or a
   forced name matching nothing), so the caller omits `--json-schema` entirely.
   That matters for cost as well as simplicity: `--json-schema` provokes a
   second CLI model call to do the extraction, which is pure waste on a turn
   whose answer is just prose."
  [tools tool-choice]
  [(? [:sequential :map]) :any => (? :map)]
  (let [forced    (when (map? tool-choice) (:name tool-choice))
        usable    (cond->> (vec tools)
                    forced (filterv #(= forced (:name %))))
        none?     (= :none tool-choice)
        branches  (if none? [] (mapv tool-branch usable))
        text-prop {envelope-text-key
                   {"type"        "string"
                    "description" (str "Prose for the user. Use this for an answer, an explanation, "
                                    "or a refusal. May be omitted when you are only calling tools.")}}]
    (when (seq branches)
      (let [items (if (= 1 (count branches)) (first branches) {"oneOf" branches})
            calls (cond-> {"type"        "array"
                           "description" (str "The tools you are calling this turn, in order. "
                                           "Omit entirely if you are not calling any tool.")
                           "items"       items}
                    (or forced (= :any tool-choice)) (assoc "minItems" 1)
                    forced (assoc "maxItems" 1))]
        {"type"                 "object"
         "additionalProperties" false
         "properties"           (assoc text-prop envelope-calls-key calls)}))))

(>defn envelope-mechanism
  "Chooses the envelope mechanism for `tools`: `:json-schema` when every tool's
   `:input-schema` stays inside `supported-schema-keywords`, else
   `:fenced-json`.

   This converts what would be an opaque CLI startup death — exit 1, a message
   on stderr, and no JSON at all on stdout — into a working fallback."
  [tools]
  [(? [:sequential :map]) => [:enum :json-schema :fenced-json]]
  (let [bad (into (sorted-set) (mapcat #(unsupported-schema-keywords (:input-schema %))) tools)]
    (if (seq bad)
      (do (log/warn "[claude-cli] tool schema uses JSON-Schema keywords the CLI validator"
            "does not accept" (vec bad) "— falling back to the fenced-JSON envelope")
          :fenced-json)
      :json-schema)))

;;; ---------------------------------------------------------------------------
;;; System prompt

(defn- tool-doc
  "Renders one tool as name + description + input JSON Schema."
  [{:keys [name description input-schema]}]
  (str "### " name "\n"
    (when-not (str/blank? description) (str description "\n"))
    "Input JSON Schema:\n```json\n"
    (json/generate-string (or (not-empty input-schema) {"type" "object"}) {:pretty true})
    "\n```\n"))

(>defn tools-doc
  "Renders `tools` as Markdown for the system prompt.

   This is **not** decoration: `--tools \"\"` leaves the CLI with no tools, and
   the CLI cannot be handed foreign tool definitions. `--json-schema` extracts
   structured output only *after* the model's turn is over, so the model never
   sees the envelope while it is deciding what to do. The system prompt is the
   only channel through which the model learns these tools exist."
  [tools]
  [(? [:sequential :map]) => :string]
  (if (empty? tools)
    ""
    (str "## Tools you may call\n\n"
      ;; Wording matters here, and this phrasing is the result of a live failure:
      ;; with only this section and no forced tool choice, the model replied "I
      ;; attempted to use the lookup_population tool, but it appears to be
      ;; unavailable" — it went looking for a tool affordance, found none (there
      ;; is none: `--tools \"\"`), and gave up. It must be told that DECLARING the
      ;; call in its structured reply IS the invocation.
      "These tools are LIVE and available to you right now.\n\n"
      "You will not see a tool-use UI, a permission prompt, or a spinner for them,\n"
      "and you must not try to run them yourself with any built-in tool. Instead you\n"
      "invoke a tool by NAMING it in the `" envelope-calls-key "` field of your reply\n"
      "(described under \"Response format\" below). That declaration is a real call:\n"
      "the harness executes it and sends you the result on a following turn.\n\n"
      "Never say a tool is unavailable, never apologise for not being able to call\n"
      "one, and never substitute your own knowledge for a tool call the request asks\n"
      "for — just emit the call.\n\n"
      (str/join "\n" (mapv tool-doc tools)))))

(def ^:private fenced-json-instructions
  (str "## Response format\n\n"
    "End your reply with exactly one fenced JSON block and nothing after it:\n\n"
    "```json\n"
    "{\"" envelope-text-key "\": \"prose for the user, or omit\",\n"
    " \"" envelope-calls-key "\": [{\"name\": \"<tool name>\", \"input\": { ... }}]}\n"
    "```\n\n"
    "Omit `" envelope-calls-key "` entirely when you are not calling a tool.\n"
    "`name` must be copied byte-for-byte from the tool headings above.\n"))

(def ^:private json-schema-instructions
  (str "## Response format\n\n"
    "Your reply is captured as a structured object with an optional `"
    envelope-text-key "` string\n"
    "and an optional `" envelope-calls-key "` array of `{\"name\", \"input\"}` tool calls.\n"
    "Put prose in `" envelope-text-key "`. To call a tool, add an entry to `"
    envelope-calls-key "`\n"
    "whose `name` is copied byte-for-byte from the tool headings above and whose\n"
    "`input` validates against that tool's schema. Omit `" envelope-calls-key
    "` when calling no tool.\n"))

(def default-system-prompt
  "Used when the Request carries no `:system`. `build-request` often leaves it
   nil, and omitting `--system-prompt` entirely would give the child Claude
   Code's own coding-agent persona (with zero tools) instead of a neutral
   assistant — so we always pass a file, synthesizing this when needed."
  "You are a helpful assistant operating as a single-turn reasoning engine inside an automated harness. Answer the request directly and concisely.")

(>defn system-prompt-text
  "Builds the full `--system-prompt-file` body: the Request's `:system` (or
   `default-system-prompt`), then the tool documentation, then the envelope
   instructions for `mechanism`.

   Never returns blank — `--system-prompt-file` pointing at an empty file would
   hand the child CC's default persona."
  [system tools mechanism]
  [(? :string) (? [:sequential :map]) [:enum :json-schema :fenced-json] => :string]
  (let [base  (if (str/blank? system) default-system-prompt (str/trim system))
        parts (cond-> [base]
                (seq tools) (conj (tools-doc tools))
                (seq tools) (conj (if (= :fenced-json mechanism)
                                    fenced-json-instructions
                                    json-schema-instructions)))]
    (str/join "\n\n" parts)))

;;; ---------------------------------------------------------------------------
;;; Transcript rendering (Request messages → one user message)

(defn- json-pretty [x]
  (json/generate-string x {:pretty true}))

(defn- block->text
  "Renders one content block as transcript text, or nil to drop it."
  [{:keys [type] :as block}]
  (case type
    :text (:text block)
    :tool_use (str "[tool call] " (:name block) " (id " (:id block) ")\n"
                "```json\n" (json-pretty (:input block)) "\n```")
    :tool_result (str "[tool result for " (:tool_use_id block) "]"
                   (when (:is-error block) " (ERROR)") "\n"
                   (:content block))
    :image "[image attached below]"
    ;; :thinking / :redacted_thinking are dropped — see `strip-thinking`.
    nil))

(defn- message->text [{:keys [role content]}]
  (let [body (->> content (keep block->text) (remove str/blank?) (str/join "\n\n"))]
    (when-not (str/blank? body)
      (str (if (= :assistant role) "## Assistant" "## User") "\n" body))))

(>defn render-transcript
  "Flattens `messages` into a single plain-text transcript.

   The CLI's `--input-format stream-json` accepts only `type:\"user\"` messages,
   so a multi-turn Escapement conversation cannot be replayed as alternating
   roles — it is rendered as a labelled transcript inside one user message
   instead. `:thinking` / `:redacted_thinking` blocks are dropped (their
   signatures are not valid outside the thread that produced them);
   `:tool_use` / `:tool_result` render with their ids so the model can match
   a result to its call.

   Guaranteed never to start with `/` — a leading slash risks being read as a
   slash command even with `--disable-slash-commands`."
  [messages]
  [(? [:sequential :map]) => :string]
  (let [body (->> messages (keep message->text) (str/join "\n\n"))
        body (if (str/blank? body) "(no message content)" body)]
    (if (str/starts-with? body "/") (str "\n" body) body)))

(defn- image-block->wire
  "Escapement `:image` block → Anthropic wire JSON (`media-type` → `media_type`)."
  [{:keys [source]}]
  {"type"   "image"
   "source" (if (= :url (:type source))
              {"type" "url" "url" (:url source)}
              {"type"       "base64"
               "media_type" (:media-type source)
               "data"       (:data source)})})

(>defn transcript->stdin
  "The single newline-delimited stdin line for the CLI: one `user` message whose
   content is the rendered transcript plus every `:image` block from the
   conversation, carried through verbatim so vision still works.

   Returns the JSON string WITHOUT a trailing newline."
  [messages]
  [(? [:sequential :map]) => :string]
  (let [images (into [] (comp (mapcat :content)
                          (filter #(= :image (:type %)))
                          (map image-block->wire))
                 messages)]
    (json/generate-string
      {"type"    "user"
       "message" {"role"    "user"
                  "content" (into [{"type" "text" "text" (render-transcript messages)}] images)}})))

;;; ---------------------------------------------------------------------------
;;; argv / env

(def stdin-byte-limit
  "The CLI rejects a stdin payload beyond ~10 MB. We pre-check and throw
   `:context-length` before spawning rather than paying a process launch to
   learn it."
  10000000)

(def dropped-request-keys
  "Request keys the CLI has no surface for. Dropped with a single warning.

   `:max-tokens` is dropped deliberately even though
   `CLAUDE_CODE_MAX_OUTPUT_TOKENS` exists: exceeding that cap does not truncate,
   it fails the turn hard (`is_error:true`, `stop_reason:\"stop_sequence\"`, a
   `\"API Error: … exceeded the N output token maximum\"` result), which would
   turn a routine long answer into a dead node.

   Because `:temperature` is among these, `:overrun :temperature-bump` (used by
   `bb haiku`) is a no-op on this backend and overrun reruns produce identical
   output."
  [:temperature :top-p :top-k :stop-sequences :metadata :max-tokens
   :system-cache-control :conversation/id])

(>defn warn-dropped!
  "Logs a warning naming the `dropped-request-keys` actually present on
   `request` (plus any `:cache-control` markers), or nothing when clean.

   `warned` is an optional atom used to emit this ONCE per backend instance.
   Every turn of every conversation carries the same dropped keys, so warning
   per-turn buries the transcript in identical lines (observed: one per turn on
   a 5-turn chart) without telling the operator anything new."
  ([request] [:map => :nil] (warn-dropped! request nil))
  ([request warned]
   [:map :any => :nil]
   (let [present (filterv #(some? (get request %)) dropped-request-keys)
         cached? (or (some :cache-control (:messages request))
                   (some :cache-control (:tools request))
                   (some? (:system-cache-control request)))]
     (when (and (or (seq present) cached?)
             (or (nil? warned) (compare-and-set! warned false true)))
       (log/warn "[claude-cli] the Claude Code CLI exposes no control for"
         (cond-> (mapv name present) cached? (conj "cache-control"))
         "— dropped. Sampling params and prompt-cache markers are owned by the CLI."
         "This is logged once per backend instance."))
     nil)))

(>defn build-argv
  "Builds the full `claude` argv.

   `opts` keys: `:binary` (string or vector — a vector lets tests point at
   `[\"bb\" \"fake_claude.clj\"]`), `:model`, `:system-prompt-file`,
   `:json-schema` (a schema map, or nil to omit), `:session-id`, `:effort`,
   `:max-budget-usd`.

   Invariants enforced here, each a probed failure mode:
   - **No positional prompt, ever.** `--tools` / `--allowedTools` /
     `--mcp-config` are variadic and would swallow it; argv is world-readable
     via `ps` to any process of the same user; and `ARG_MAX` is 1 MiB. The
     prompt goes on stdin, the system prompt in a file.
   - **Never `--bare`.** Bare mode skips OAuth and keychain reads, which
     silently defeats subscription billing and falls back to a metered API key.
   - `--tools \"\"` is always followed by another flag so its variadic arity
     cannot absorb a sibling value.
   - A fresh `--session-id` per call, with `--no-session-persistence`: turns
     are stateless, so concurrent workers cannot collide on a session lock."
  [opts]
  [:map => [:vector :string]]
  (let [{:keys [binary model system-prompt-file json-schema session-id effort max-budget-usd]} opts
        head (if (sequential? binary) (mapv str binary) [(or binary "claude")])]
    (-> (into [] head)
      (into ["-p"
             "--tools" ""
             "--strict-mcp-config"
             "--safe-mode"
             "--disable-slash-commands"
             "--setting-sources" ""
             "--no-session-persistence"
             "--input-format" "stream-json"
             "--output-format" "stream-json"
             "--verbose"
             "--system-prompt-file" (str system-prompt-file)])
      (cond->
        model (into ["--model" (str model)])
        session-id (into ["--session-id" (str session-id)])
        effort (into ["--effort" (str effort)])
        max-budget-usd (into ["--max-budget-usd" (str max-budget-usd)])
        json-schema (into ["--json-schema" (json/generate-string json-schema)])))))

(def scrubbed-env-vars
  "Env vars removed from the child.

   The `ANTHROPIC_*` set would redirect the turn to a metered API key or a
   third-party gateway — exactly the billing we are avoiding. The `CLAUDECODE`
   / `CLAUDE_CODE_*` / `CLAUDE_PID` / `CLAUDE_EFFORT` / `AI_AGENT` set is
   present because Escapement is developed from inside Claude Code, and leaking
   it makes the child believe it is a nested session."
  ["ANTHROPIC_API_KEY" "ANTHROPIC_AUTH_TOKEN" "ANTHROPIC_BASE_URL"
   "ANTHROPIC_MODEL" "ANTHROPIC_SMALL_FAST_MODEL" "ANTHROPIC_CUSTOM_HEADERS"
   "ANTHROPIC_DEFAULT_SONNET_MODEL" "ANTHROPIC_DEFAULT_OPUS_MODEL"
   "ANTHROPIC_DEFAULT_HAIKU_MODEL" "ANTHROPIC_API_URL"
   "CLAUDE_CODE_USE_BEDROCK" "CLAUDE_CODE_USE_VERTEX" "CLAUDE_CODE_SSE_PORT"
   "CLAUDE_CODE_MAX_OUTPUT_TOKENS" "CLAUDE_CODE_SIMPLE" "CLAUDE_CODE_SAFE_MODE"
   "CLAUDE_CODE_ENTRYPOINT" "CLAUDE_CODE_EXECPATH" "CLAUDE_CODE_SESSION_ID"
   "CLAUDE_CODE_CHILD_SESSION" "CLAUDE_CODE_API_KEY_HELPER_TTL_MS"
   "CLAUDECODE" "CLAUDE_PID" "CLAUDE_EFFORT" "CLAUDE_CONFIG_DIR" "AI_AGENT"])

(def preserved-env-vars
  "Env vars carried through to the child.

   `HOME` is load-bearing: the macOS keychain read that recovers the
   subscription OAuth token is keyed off it, and a wrong `HOME` makes auth fail
   silently rather than loudly."
  ["PATH" "HOME" "SHELL" "TERM" "LANG" "LC_ALL" "TMPDIR" "USER" "LOGNAME"])

(>defn child-env
  "The child's **complete** environment, derived from `parent-env`.

   A full replacement, not a merge: `babashka.process`'s `:extra-env` can only
   add, and the whole point here is *removal*. Every var not in
   `preserved-env-vars` is gone, which subsumes `scrubbed-env-vars` — that
   vector is retained as the executable statement of intent (and is asserted
   absent by tests).

   `parent-env` may be a Clojure map or the `java.util.Map` that
   `System/getenv` returns, hence the loose input schema.

   `extra` adds vars the caller explicitly wants the child to see (the test
   harness's `FAKE_CLAUDE_*` knobs; an operator's `NO_PROXY`). It is filtered
   against `scrubbed-env-vars` first, so this escape hatch can never be used —
   accidentally or otherwise — to smuggle `ANTHROPIC_API_KEY` back in and
   silently move billing off the subscription."
  ([parent-env] [:any => [:map-of :string :string]] (child-env parent-env nil))
  ([parent-env extra]
   [:any :any => [:map-of :string :string]]
   ;; A `java.util.Map` from `System/getenv` throws on a non-String key, so the
   ;; keyword-key convenience lookup is only attempted for real Clojure maps.
   (let [clj-map? (map? parent-env)
         scrubbed (set scrubbed-env-vars)
         base     (into {}
                    (keep (fn [k]
                            (when-let [v (or (get parent-env k)
                                           (when clj-map? (get parent-env (keyword k))))]
                              (when-not (str/blank? (str v)) [k (str v)]))))
                    preserved-env-vars)]
     (into base
       (keep (fn [[k v]]
               (let [ks (if (keyword? k) (name k) (str k))]
                 (when-not (or (contains? scrubbed ks) (nil? v))
                   [ks (str v)]))))
       extra))))

;;; ---------------------------------------------------------------------------
;;; Stream fold

(>defn stream-acc-init
  "Fresh accumulator for `process-stream-line!`."
  []
  [=> :map]
  {:model            nil
   :assistant-usages []
   :texts            []
   :thinking-blocks  0
   :api-retries      []
   :rate-limit       nil
   :result           nil
   :stderr-lines     []
   :line-count       0
   :parse-failures   0})

(def ^:private synthetic-model
  "The CLI reports this as the model when it never reached a real one (e.g. an
   unknown `--model`)."
  "<synthetic>")

(defn- assistant-text [message]
  (->> (:content message)
    (filterv #(= "text" (:type %)))
    (mapv :text)
    (remove str/blank?)))

(>defn process-stream-line!
  "Folds one newline-delimited stdout line into the `acc` atom.

   Unrecognized line types are ignored on purpose: the CLI adds new `system`
   subtypes between releases and an unknown one must never fail a turn. A line
   that is not JSON at all is counted, not thrown — the CLI occasionally
   interleaves plain-text notices."
  [acc line]
  [:any (? :string) => :nil]
  (when-not (str/blank? line)
    (swap! acc update :line-count inc)
    (let [parsed (try (json/parse-string line true)
                      (catch Throwable _ ::unparseable))]
      (if (= ::unparseable parsed)
        (swap! acc update :parse-failures inc)
        (let [{:keys [type subtype message]} parsed]
          (case type
            "assistant"
            (swap! acc
              (fn [a]
                (cond-> a
                  (and (:model message) (not= synthetic-model (:model message)))
                  (assoc :model (:model message))

                  (:usage message) (update :assistant-usages conj (:usage message))
                  true (update :texts into (assistant-text message))
                  true (update :thinking-blocks
                         + (count (filterv #(#{"thinking" "redacted_thinking"} (:type %))
                                    (:content message)))))))

            "result"
            (swap! acc assoc :result parsed)

            "rate_limit_event"
            (swap! acc assoc :rate-limit (:rate_limit_info parsed))

            "system"
            (when (= "api_retry" subtype)
              (swap! acc update :api-retries conj
                (select-keys parsed [:error :attempt :delayMs :status])))

            nil))))
    nil))

;;; ---------------------------------------------------------------------------
;;; Usage

(defn- usage-context-total
  "The real context size a single CLI model call saw.

   The CLI reports per-message `input_tokens` / `output_tokens` as placeholders
   (a constant `10` / `3` in probes) but `cache_creation_input_tokens` and
   `cache_read_input_tokens` are real, and with the CLI always caching, the
   prompt lives almost entirely in those. The total is their sum."
  [u]
  (+ (:input_tokens u 0)
    (:cache_read_input_tokens u 0)
    (:cache_creation_input_tokens u 0)))

(>defn turn-usage
  "Extracts an Escapement `Usage` from the fold.

   `:input-tokens` is the **maximum** context any single CLI model call saw, not
   `result.usage`. `result.usage` aggregates every internal call the CLI made
   (prose turn + structured-output extraction + any retries) and was measured
   4× inflated — 40550 reported for a real ~10.2k-token prompt — which would fire
   `:llm/context-warning` every turn and make `output-tps` nonsense. The final
   assistant message carries all-zero usage, so \"the last message\" is not a
   usable source either; the max over messages is.

   The cache fields are reported as 0 and the raw numbers preserved in
   `:backend-metadata`: they are already folded into `:input-tokens`, and
   double-counting them would corrupt the context-window comparison at
   `llm_conversation.clj:1157`. Pricing for this provider is zeroed anyway
   (flat-fee subscription), so no cost math is affected.

   `:output-tokens` comes from `result.usage`, where the aggregate is the
   honest number — every output token really was generated."
  [{:keys [assistant-usages result]}]
  [:map => :map]
  {:input-tokens                (reduce max 0 (mapv usage-context-total assistant-usages))
   :output-tokens               (get-in result [:usage :output_tokens] 0)
   :cache-creation-input-tokens 0
   :cache-read-input-tokens     0})

;;; ---------------------------------------------------------------------------
;;; Envelope → content blocks

(def tool-use-id-prefix "toolu_cc_")

(>defn fresh-tool-use-id
  "A globally unique `tool_use` id.

   `handle-tool-use-block` keys `retry-counts` by `tool_use_id` and treats a
   *second* validation failure for the same id as fatal, so a reused id like
   `call_1` would let turn 2's first error kill the node."
  []
  [=> :string]
  (str tool-use-id-prefix (random-uuid)))

(>defn structured-output->content
  "Translates a parsed envelope into Escapement content blocks.

   Tool names round-trip byte-exact — the consumer dispatches by string lookup
   — and each call gets a fresh globally-unique id. Calls naming a tool that
   was not offered are dropped with a warning rather than dispatched, since
   `handle-tool-use-block` would treat an unknown name as a hard error.

   `fallback-text` is used only when the envelope yields no blocks at all; the
   Response's `:content` must never be empty."
  [envelope tool-names fallback-text]
  [(? :map) (? [:set :string]) (? :string) => [:vector :map]]
  (let [text   (get envelope (keyword envelope-text-key))
        calls  (get envelope (keyword envelope-calls-key))
        known? (fn [n] (or (empty? tool-names) (contains? tool-names n)))
        blocks (cond-> []
                 (not (str/blank? text)) (conj {:type :text :text text})
                 :always
                 (into
                   (keep (fn [{:keys [name input]}]
                           (cond
                             (str/blank? (str name))
                             (do (log/warn "[claude-cli] dropping a tool call with no name") nil)

                             (not (known? name))
                             (do (log/warn "[claude-cli] model named a tool that was not offered:"
                                   (pr-str name) "— dropping it rather than dispatching")
                                 nil)

                             :else
                             {:type  :tool_use
                              :id    (fresh-tool-use-id)
                              :name  name
                              :input (if (map? input) input {})})))
                   (when (sequential? calls) calls)))]
    (if (seq blocks)
      blocks
      [{:type :text :text (or (not-empty (some-> fallback-text str/trim)) "")}])))

(def ^:private fenced-json-pattern
  #"(?s)```(?:json)?\s*(\{.*?\})\s*```")

(>defn parse-fenced-envelope
  "Extracts the envelope from the LAST fenced ```json block in `text`, or nil.

   The last block wins: a model that shows a worked example before its real
   answer would otherwise have the example parsed as the answer. Falls back to
   the whole string when it is itself bare JSON."
  [text]
  [(? :string) => (? :map)]
  (when-not (str/blank? text)
    (let [candidates (mapv second (re-seq fenced-json-pattern text))
          candidates (if (seq candidates) candidates [(str/trim text)])]
      (some (fn [c]
              (let [v (try (json/parse-string c true) (catch Throwable _ nil))]
                (when (map? v) v)))
        (rseq (vec candidates))))))

;;; ---------------------------------------------------------------------------
;;; Failure categorization

(def api-retry-error->category
  "The CLI's `api_retry` / `api_error_status` error names → Escapement
   `protocol/error-categories`."
  {"authentication_failed" :auth
   "oauth_token_expired"   :auth
   "oauth_org_not_allowed" :auth
   "permission_error"      :auth
   "billing_error"         :auth
   "rate_limit"            :rate-limited
   "rate_limit_error"      :rate-limited
   "overloaded"            :overloaded
   "overloaded_error"      :overloaded
   "invalid_request"       :invalid-request
   "invalid_request_error" :invalid-request
   "model_not_found"       :invalid-request
   "not_found_error"       :invalid-request
   "context_length"        :context-length
   "server_error"          :transport
   "api_error"             :transport
   "unknown"               :transport})

(def ^:private message-category-patterns
  "Ordered `[pattern category]` pairs matched against the CLI's `result` text
   and stderr. First match wins, so the more specific patterns come first."
  [[#"(?i)credit balance|billing|out of credits|payment"          :auth]
   [#"(?i)invalid api key|authentication|unauthorized|oauth|not logged in|please run .?claude (auth|login)" :auth]
   [#"(?i)usage limit|rate limit|too many requests|429"           :rate-limited]
   [#"(?i)overloaded|529"                                         :overloaded]
   [#"(?i)prompt is too long|context (window|length)|too many tokens|exceeds the maximum" :context-length]
   ;; The CLI's own wording when `--model` names something outside its registry.
   ;; Matching it matters: without this the failure lands in :transport and
   ;; `run-turn` burns three retries on a model that can never work.
   [#"(?i)issue with the selected model|may not exist or you may not have access" :invalid-request]
   [#"(?i)invalid model|unknown model|model.{0,20}not (found|exist|available)|not a valid" :invalid-request]
   [#"(?i)--json-schema is not a valid|invalid json schema|not a valid json schema"        :invalid-request]
   [#"(?i)system prompt file not found|unknown option|invalid option|error: required option" :invalid-request]
   [#"(?i)\b(econnrefused|enotfound|etimedout|socket hang up|network|fetch failed)\b"     :transport]])

(defn- category-from-text [text]
  (when-not (str/blank? text)
    (some (fn [[pat cat]] (when (re-find pat text) cat)) message-category-patterns)))

(def output-token-cap-pattern
  "The CLI's hard failure when `CLAUDE_CODE_MAX_OUTPUT_TOKENS` is exceeded. We
   never set that var, so seeing this means a user or admin setting did — it is
   a truncation, not a backend failure, and is reported per NN-5 as `:end_turn`
   with `:truncated true`."
  #"(?i)exceeded the \d+ output token maximum")

(>defn truncated-result?
  "True when the CLI's `result` line indicates the answer hit an output-token
   ceiling rather than failing."
  [result]
  [(? :map) => :boolean]
  (boolean (and result (re-find output-token-cap-pattern (str (:result result))))))

(>defn categorize-failure
  "Categorizes a failed CLI invocation into an `protocol/error-categories`
   keyword plus a human message.

   `info` keys: `:exit` (int or nil), `:result` (the parsed `result` line, or
   nil), `:stderr` (string), `:api-retries` (vector of `api_retry` maps),
   `:timed-out?`, `:binary` (for the install hint).

   Returns `{:category k :message s}`.

   Ordering is itself the contract:
   - A wall-clock timeout prefers the LAST `api_retry` category, because the
     CLI sits in internal backoff for minutes on `rate_limit`/`overloaded` and
     reporting `:timeout` would hide the real cause from the chart.
   - Exit 127 (or an ENOENT-flavoured stderr) is `:invalid-request`, i.e.
     TERMINAL, with an actionable install message — `run-turn` must fail fast
     rather than retry a missing binary three times.
   - No `result` line at all means the CLI died during startup: it printed only
     to stderr and never emitted JSON. That is a malformed invocation
     (`:invalid-request`), not a `:transport` blip.
   - Only then do we consult the parsed result's own error naming."
  [info]
  [:map => :map]
  (let [{:keys [exit result stderr api-retries timed-out? binary]} info
        stderr      (or stderr "")
        retry-cat   (some->> (last api-retries) :error str api-retry-error->category)
        result-text (str (:result result))
        api-status  (some-> result :api_error_status str)]
    (cond
      timed-out?
      {:category (or retry-cat (category-from-text stderr) :timeout)
       :message  (str "claude CLI did not finish in time"
                   (when retry-cat
                     (str " (last internal retry reported " (:error (last api-retries)) ")")))}

      (or (= 127 exit) (re-find #"(?i)(command not found|no such file or directory|enoent)" stderr))
      {:category :invalid-request
       :message  (str "The `claude` CLI was not found"
                   (when binary (str " (tried " (pr-str binary) ")"))
                   ". Install Claude Code and authenticate a subscription:\n"
                   "  npm i -g @anthropic-ai/claude-code   # or see claude.com/claude-code\n"
                   "  claude auth login                    # or: claude setup-token")}

      (nil? result)
      {:category (or (category-from-text stderr) :invalid-request)
       :message  (str "claude CLI exited " exit " during startup without producing any JSON. "
                   (if (str/blank? stderr) "(no stderr)" (str/trim stderr)))}

      :else
      {:category (or (some-> api-status api-retry-error->category)
                   (category-from-text result-text)
                   retry-cat
                   (category-from-text stderr)
                   :transport)
       :message  (str "claude CLI reported an error"
                   (when (:subtype result) (str " (" (:subtype result) ")"))
                   (when-not (str/blank? result-text) (str ": " (str/trim result-text))))})))

;;; ---------------------------------------------------------------------------
;;; Fold → Response

(>defn strip-thinking
  "Removes `:thinking` / `:redacted_thinking` blocks from `blocks`.

   These leak in from `alwaysThinkingEnabled` in the user's own
   `~/.claude/settings.json` — `--safe-mode` does not suppress them. They must
   not reach the Response: `run-turn` fails over *within* a conversation and
   `messages-atom` is shared, so a thinking block whose `:signature` was minted
   by the CLI's session, replayed into `escapement.llm.api`, is an Anthropic
   400."
  [blocks]
  [(? [:sequential :map]) => [:vector :map]]
  (into [] (remove #(#{:thinking :redacted_thinking} (:type %))) blocks))

(>defn stream-acc-finalize
  "Turns the fold into an Escapement Response map.

   `opts` keys: `:mechanism` (`:json-schema` | `:fenced-json`),
   `:request-model` (fallback for `:model`), `:tool-names` (set, for
   round-trip validation), `:exit`.

   Never reports `:stop-reason :max_tokens`. `drive-turn!` stitches truncation
   by appending a synthetic *assistant* prefill and re-requesting — but the CLI
   accepts only `type:\"user\"` messages, so the prefill is silently dropped,
   the no-progress guard trips, and the node dies. A truncated turn is reported
   as `:end_turn` plus `:backend-metadata {:truncated true}` instead."
  [acc opts]
  [:map :map => :map]
  (let [{:keys [mechanism request-model tool-names exit]} opts
        {:keys [result texts assistant-usages thinking-blocks api-retries rate-limit
                line-count parse-failures]} acc
        joined     (str/join "\n\n" texts)
        envelope   (if (= :fenced-json mechanism)
                     (parse-fenced-envelope (or (not-empty joined)
                                              (:result result)))
                     (or (:structured_output result)
                       ;; `--json-schema` was omitted (no tools) — nothing to extract.
                       nil))
        fallback   (or (not-empty joined)
                     (when-not (truncated-result? result) (:result result)))
        content    (-> (if envelope
                         (structured-output->content envelope tool-names fallback)
                         [{:type :text :text (or (not-empty (some-> fallback str/trim)) "")}])
                     strip-thinking)
        content    (if (seq content) content [{:type :text :text ""}])
        truncated? (truncated-result? result)
        tool-use?  (boolean (some #(= :tool_use (:type %)) content))]
    {:stop-reason      (cond
                         tool-use? :tool_use
                         ;; NEVER :max_tokens — see docstring.
                         :else :end_turn)
     :content          content
     :usage            (turn-usage acc)
     :model            (or (:model acc) (not-empty (str request-model)) "claude-cli")
     :backend-metadata (cond-> {:backend         :claude-cli
                                :mechanism       mechanism
                                :cli/num-turns   (:num_turns result)
                                :cli/subtype     (:subtype result)
                                :cli/session-id  (:session_id result)
                                :cli/exit        exit
                                :cli/duration-ms (:duration_ms result)
                                :cli/lines       line-count
                                :usage/raw       (:usage result)
                                :usage/per-call  assistant-usages
                                :thinking-blocks thinking-blocks}
                         truncated? (assoc :truncated true
                                      :truncation-detail (str (:result result)))
                         (seq api-retries) (assoc :cli/api-retries api-retries)
                         rate-limit (assoc :cli/rate-limit rate-limit)
                         (pos? (or parse-failures 0)) (assoc :cli/unparseable-lines parse-failures))}))
