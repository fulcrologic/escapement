(ns escapement.llm.reasoning
  "Translation of the normalised request-level `:reasoning` field
   (`escapement.llm.types/Reasoning`) into each provider's own dialect.

   A chart says how hard to think ONCE, as an ordinal:

       {:reasoning {:effort :high}}   ; or the sugar {:reasoning :high}

   and every backend renders that into its own wire format. The caller never
   writes a provider's word, and the library never learns anything about the
   caller. Absent `:reasoning` produces nil everywhere, so a request that does
   not use the field is byte-for-byte identical to what the backend emitted
   before this namespace existed.

   The dialect is a static property of the PROVIDER (see
   `escapement.llm.providers/provider-templates`), never sniffed from a
   base-url: pointing `:openai` at a proxy or a self-hosted gateway must not
   silently change the wire format.

   --- Verification status of each row ---------------------------------------

   Rows verified against the live API on 2026-09-07:

   - `:deepseek`   — `thinking {:type enabled|disabled}` and `reasoning_effort`
                     are accepted TOGETHER; `reasoning_effort` takes
                     low/high/max. Verified on `deepseek-v4-flash`.
   - `:ollama`     — `think` accepts both a boolean and a string level;
                     neither is rejected. Verified on `kimi-k2.7-code`.
   - `:openrouter` — `reasoning {:enabled false}`, `reasoning {:effort …}` and
                     `reasoning {:max_tokens N}` are each accepted, but
                     `:effort` and `:max_tokens` TOGETHER are a hard 400
                     (\"Only one of reasoning.effort and reasoning.max_tokens
                     can be specified\"). Hence `budget-tokens` WINS and effort
                     is dropped when both are present.
   - Anthropic-shaped `thinking {:type enabled :budget_tokens N}` — accepted.

   Rows deliberately left conservative because they could NOT be verified from
   this machine (no OpenAI credentials), and a plausible-but-wrong payload is
   worse than no payload:

   - `:openai` `:none` and `:minimal` — the ordinary low/medium/high
     `reasoning_effort` values are emitted, but `:none` omits the field
     (provider default) rather than guessing at a wire value, and `:minimal`
     is emitted as \"low\".
   - Codex/Responses `:max` — emitted as \"high\", not \"xhigh\"; the xhigh
     level is model-dependent and was not confirmed."
  (:require
    [escapement.llm.types :as types]))

(def dialects
  "Every reasoning dialect this library knows how to speak. `:none` means the
   provider has no reasoning control we can address, so the field is dropped."
  #{:none :anthropic :openai :openrouter :ollama :deepseek :responses})

(defn effort
  "The normalised effort keyword on `request`, or nil when `:reasoning` is
   absent or carries no `:effort`."
  [request]
  (some-> (:reasoning request) :effort))

(defn budget-tokens
  "The caller's explicit token-budget hint, or nil."
  [request]
  (some-> (:reasoning request) :budget-tokens))

(defn reasoning-off?
  "True when the caller explicitly asked for NO reasoning. Distinct from
   saying nothing at all, which leaves the provider default in place."
  [request]
  (= :none (effort request)))

(defn requested?
  "True when the request carries any reasoning directive at all."
  [request]
  (some? (:reasoning request)))

;;; ---------------------------------------------------------------------------
;;; Anthropic-shaped (`escapement.llm.api`): `thinking` + a token budget

(def ^:private effort->budget
  "Effort ordinal → an extended-thinking token budget. Anthropic's floor is
   1024; the steps are deliberately coarse, since the caller who cares about
   the exact number passes `:budget-tokens` instead."
  {:minimal 1024
   :low     2048
   :medium  4096
   :high    16384
   :max     32768})

(defn anthropic-thinking
  "The `:thinking` map the Anthropic-shaped wire should carry, derived from
   `:reasoning`, or nil to emit nothing.

   An explicit `:thinking` on the request always WINS — nothing that works
   today changes.

   `max-tokens` is respected. Anthropic requires the budget to be strictly
   below it, but merely clamping to `max-tokens - 1` would spend the entire
   cap on reasoning and leave nothing for the answer, so a DERIVED budget
   never takes more than half the cap. When there is no room for the
   1024-token floor the directive is dropped rather than sent as a request the
   API will reject. An explicit `:budget-tokens` is the caller's own arithmetic
   and is clamped only by the API's hard `< max-tokens` rule."
  [{:keys [thinking max-tokens] :as request}]
  (cond
    (some? thinking) thinking
    (not (requested? request)) nil
    (reasoning-off? request) nil
    :else
    (when-let [e (effort request)]
      (let [explicit (budget-tokens request)
            wanted   (or explicit (effort->budget e))
            room     (when max-tokens
                       (if explicit (dec max-tokens) (quot max-tokens 2)))
            budget   (if room (min wanted room) wanted)]
        (when (>= budget 1024)
          {:type :enabled :budget-tokens budget})))))

(defn thinking-enabled?
  [thinking]
  (= :enabled (:type thinking)))

(def sampling-keys-thinking-forbids
  "Anthropic rejects a request that enables extended thinking while also
   setting any of these: the model owns its own sampling while reasoning.

   NOTE: documented by Anthropic and long-standing, but NOT live-verified in
   this repository — no Anthropic credentials were reachable when the
   `:reasoning` field was built. It is applied as a drop-and-warn rather than
   as a hard error precisely because of that."
  [:temperature :top-p :top-k])

;;; ---------------------------------------------------------------------------
;;; OpenAI chat-completions family

(def ^:private effort->openai
  {:minimal "low" :low "low" :medium "medium" :high "high" :max "high"})

(defn openai-reasoning-effort
  "OpenAI `reasoning_effort` string, or nil to emit nothing.

   `:none` deliberately emits NOTHING rather than a guessed wire value (see
   the verification note in this namespace's docstring)."
  [request]
  (when-let [e (effort request)]
    (get effort->openai e)))

(def ^:private effort->openrouter
  {:minimal "low" :low "low" :medium "medium" :high "high" :max "high"})

(defn openrouter-reasoning
  "OpenRouter's unified `reasoning` object, or nil to emit nothing.

   `:none` turns reasoning OFF explicitly (`{:enabled false}`) — the one
   dialect where \"do not think\" has a real wire representation.

   `:effort` and `:max_tokens` are mutually exclusive on this API (verified: a
   request carrying both is a 400), so an explicit `:budget-tokens` wins and
   the effort is dropped."
  [request]
  (cond
    (not (requested? request)) nil
    (reasoning-off? request) {"enabled" false}
    (budget-tokens request) {"max_tokens" (budget-tokens request)}
    :else (when-let [e (effort request)]
            (when-let [v (get effort->openrouter e)]
              {"effort" v}))))

(defn ollama-think
  "Ollama's `think` flag, or nil to emit nothing. Ollama accepts a boolean and
   also a string level; the boolean is the form every thinking-capable model
   there understands, so that is what we send."
  [request]
  (cond
    (not (requested? request)) nil
    (reasoning-off? request) false
    (effort request) true
    :else nil))

(def ^:private effort->deepseek
  "DeepSeek accepts low / high / max and defaults to high."
  {:minimal "low" :low "low" :medium "high" :high "high" :max "max"})

(defn deepseek-fields
  "DeepSeek's pair of fields as a map ready to merge into the wire body:
   `thinking` plus, when reasoning is on, `reasoning_effort`. Verified to be
   accepted together. Returns nil to emit nothing."
  [request]
  (cond
    (not (requested? request)) nil
    (reasoning-off? request) {"thinking" {"type" "disabled"}}
    :else (when-let [e (effort request)]
            (cond-> {"thinking" {"type" "enabled"}}
              (get effort->deepseek e) (assoc "reasoning_effort" (get effort->deepseek e))))))

;;; ---------------------------------------------------------------------------
;;; OpenAI Responses wire (`escapement.llm.openai-codex`, z.ai coding plan v1)

(def ^:private effort->responses
  "`:max` maps to \"high\", NOT \"xhigh\": the xhigh level is model-dependent
   and was not verified."
  {:minimal "minimal" :low "low" :medium "medium" :high "high" :max "high"})

(defn responses-reasoning
  "The Responses API `reasoning` object, or nil to emit nothing. `:none` omits
   the field rather than guessing at an \"off\" representation."
  [request]
  (when-not (reasoning-off? request)
    (when-let [e (effort request)]
      (when-let [v (get effort->responses e)]
        ;; Keyword keys, matching the body map `openai-codex.translate` builds.
        {:effort v :summary "auto"}))))

;;; ---------------------------------------------------------------------------
;;; Dispatch

(defn wire-fields
  "The map of wire fields one of the OpenAI chat-completions-shaped dialects
   should merge into its request body, for `dialect`. Returns nil when the
   dialect has no reasoning control or the request asked for nothing."
  [dialect request]
  (case (or dialect :none)
    :openai (when-let [v (openai-reasoning-effort request)] {"reasoning_effort" v})
    :openrouter (when-let [v (openrouter-reasoning request)] {"reasoning" v})
    :ollama (let [v (ollama-think request)] (when (some? v) {"think" v}))
    :deepseek (deepseek-fields request)
    nil))

(defn valid-effort?
  [e]
  (contains? types/effort-rank e))
