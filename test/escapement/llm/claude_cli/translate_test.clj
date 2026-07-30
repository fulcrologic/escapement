(ns escapement.llm.claude-cli.translate-test
  "Pure wire-contract tests for the `claude -p` adapter. No network, no `claude`
   binary, no subprocess — every assertion here is a value→value check.

   The stream-fold tests replay the `.jsonl` fixtures under
   `test/resources/claude-cli/`, which were captured from the shapes a real
   CC v2.1.220 emits (see the probe log in `workingcontext.md`)."
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [escapement.llm.claude-cli.translate :as t]
    [escapement.llm.protocol :as proto]
    [escapement.llm.types :as types]
    [escapement.tools.builtin :as builtin]
    [escapement.tools.protocol :as tp]
    [fulcro-spec.core :refer [=> assertions component specification]]))

;;; ---------------------------------------------------------------------------
;;; Fixtures / sample data

(def fixture-dir
  "Fixtures live under `test/resources/`, which is NOT on the bb test classpath
   (`bb.edn`'s `:paths` carries `resources`, not `test/resources`), so they are
   read by relative path from the repo root — where `bb test` runs."
  "test/resources/claude-cli")

(defn fixture-file [name]
  (let [f (io/file fixture-dir (str name ".jsonl"))]
    (assert (.exists f) (str "missing fixture: " (.getPath f)))
    f))

(defn fold-fixture
  "Replays `name`.jsonl through the fold and returns the accumulator."
  [name]
  (let [acc (atom (t/stream-acc-init))]
    (with-open [r (io/reader (fixture-file name))]
      (doseq [line (line-seq r)]
        (t/process-stream-line! acc line)))
    @acc))

(defn finalize-fixture
  ([name] (finalize-fixture name {}))
  ([name opts]
   (t/stream-acc-finalize (fold-fixture name)
     (merge {:mechanism :json-schema :request-model "sonnet" :exit 0} opts))))

(def write-tool
  {:name         "fs_write"
   :description  "Write a file."
   :input-schema {"type"                 "object"
                  "additionalProperties" false
                  "properties"           {"path"    {"type" "string"}
                                          "content" {"type" "string"}}
                  "required"             ["path" "content"]}})

(def list-tool
  {:name         "fs_list"
   :description  "List a directory."
   :input-schema {"type"       "object"
                  "properties" {"path" {"type" "string"}}}})

(def simple-request
  {:model    "claude-sonnet-4-6"
   :messages [{:role :user :content [{:type :text :text "SENTINEL-PROMPT"}]}]})

;;; ---------------------------------------------------------------------------
;;; normalize-model

(specification "normalize-model"
  (assertions
    "bare CLI aliases pass through, lower-cased"
    (t/normalize-model "sonnet") => "sonnet"
    (t/normalize-model "Opus") => "opus"
    (t/normalize-model "haiku") => "haiku"
    (t/normalize-model "fable") => "fable"

    "escapement model ids family-map onto the CLI alias, because the CLI rejects
     ids absent from its own registry (claude-sonnet-4-7 was probed as exit 1)"
    (t/normalize-model "claude-sonnet-4-7") => "sonnet"
    (t/normalize-model "claude-sonnet-4-6") => "sonnet"
    (t/normalize-model "claude-opus-4-1-20250805") => "opus"
    (t/normalize-model "claude-haiku-4-5-20251001") => "haiku"
    (t/normalize-model "claude-fable-5") => "fable"

    "Bedrock-style ids still resolve by family"
    (t/normalize-model "us.anthropic.claude-sonnet-4-5-v1:0") => "sonnet"

    "non-Anthropic ids pass through verbatim so the CLI renders its own error"
    (t/normalize-model "gpt-4o") => "gpt-4o"

    "blank/nil yield nil so --model is omitted entirely"
    (t/normalize-model nil) => nil
    (t/normalize-model "  ") => nil))

;;; ---------------------------------------------------------------------------
;;; build-argv  (NN-1, NN-2, NN-3)

(specification "build-argv"
  (let [argv (t/build-argv {:binary             "claude"
                            :model              "sonnet"
                            :system-prompt-file "/tmp/sp.txt"
                            :session-id         "11111111-2222-3333-4444-555555555555"
                            :json-schema        {"type" "object"}})
        argset (set argv)]
    (assertions
      "runs in print mode"
      (contains? argset "-p") => true

      "disables every built-in tool and every MCP server"
      (contains? argset "--tools") => true
      (contains? argset "--strict-mcp-config") => true
      "and never passes --mcp-config, so --strict-mcp-config loads nothing"
      (contains? argset "--mcp-config") => false

      "disables CLAUDE.md / skills / plugins / hooks / agents while keeping auth working"
      (contains? argset "--safe-mode") => true
      (contains? argset "--disable-slash-commands") => true
      (contains? argset "--setting-sources") => true

      "uses stream-json in both directions so real content blocks are available"
      (contains? argset "--input-format") => true
      (contains? argset "--output-format") => true
      (contains? argset "--verbose") => true

      "NN-1: never --bare — bare mode skips OAuth and keychain reads, which
       silently defeats subscription billing"
      (contains? argset "--bare") => false

      "NN-3: always passes --system-prompt-file"
      (contains? argset "--system-prompt-file") => true

      "turns are stateless: fresh session id, no persistence, never --resume"
      (contains? argset "--no-session-persistence") => true
      (contains? argset "--session-id") => true
      (contains? argset "--resume") => false
      (contains? argset "--continue") => false

      "never delegates failover to the CLI — run-turn owns that"
      (contains? argset "--fallback-model") => false

      "never bypasses permissions (there are no tools to permit anyway)"
      (contains? argset "--dangerously-skip-permissions") => false))

  (component "NN-2: no positional prompt, ever"
    ;; One assertion covers all three hazards: --tools/--allowedTools/--mcp-config
    ;; are variadic and would swallow a positional prompt; argv is visible via
    ;; `ps` to any process of the same user; and ARG_MAX is 1 MiB.
    (let [argv (t/build-argv {:binary             "claude"
                              :model              "sonnet"
                              :system-prompt-file "/tmp/sp.txt"
                              :json-schema        (t/envelope-schema [write-tool] :auto)})]
      (assertions
        "no argv element carries prompt text"
        (not-any? #(str/includes? % "SENTINEL-PROMPT") argv) => true
        "the prompt is not smuggled in as a trailing positional either"
        (str/starts-with? (last argv) "-") => false        ; last is the schema VALUE
        "and every non-flag element is the value of the flag before it"
        (even? (count (drop 1 argv))) => true)))

  (component "--tools \"\" arity safety"
    (let [argv (t/build-argv {:binary "claude" :system-prompt-file "/tmp/sp.txt"})
          i    (.indexOf ^java.util.List argv "--tools")]
      (assertions
        "the empty tool list is followed by another flag, so its variadic arity
         cannot absorb a sibling value"
        (nth argv (inc i)) => ""
        (str/starts-with? (nth argv (+ i 2)) "--") => true)))

  (component "system prompt file is always present even with no :system"
    (let [txt (t/system-prompt-text nil nil :json-schema)]
      (assertions
        "synthesizes a neutral default rather than inheriting CC's coding persona"
        (str/blank? txt) => false
        (str/includes? txt "single-turn") => true)))

  (component "binary may be a vector so tests can point at a bb script"
    (let [argv (t/build-argv {:binary ["bb" "fake_claude.clj"] :system-prompt-file "/x"})]
      (assertions
        "the vector becomes the argv head"
        (take 2 argv) => ["bb" "fake_claude.clj"]
        (nth argv 2) => "-p")))

  (component "optional flags"
    (assertions
      "--json-schema is omitted when there is no envelope"
      (contains? (set (t/build-argv {:binary "claude" :system-prompt-file "/x"}))
        "--json-schema") => false
      "--effort is passed through when set"
      (contains? (set (t/build-argv {:binary "claude" :system-prompt-file "/x" :effort "high"}))
        "--effort") => true
      "--max-budget-usd is passed through when set"
      (contains? (set (t/build-argv {:binary "claude" :system-prompt-file "/x" :max-budget-usd 1.5}))
        "--max-budget-usd") => true))

  (component "session ids are fresh per call"
    (let [id-of (fn [] (let [a (t/build-argv {:binary "claude" :system-prompt-file "/x"
                                             :session-id (str (random-uuid))})]
                         (nth a (inc (.indexOf ^java.util.List a "--session-id")))))]
      (assertions
        "two calls never share a session id (concurrent workers must not collide)"
        (= (id-of) (id-of)) => false))))

;;; ---------------------------------------------------------------------------
;;; child-env  (NN-4)

(specification "child-env"
  (let [parent (into {"PATH"                    "/usr/bin"
                      "HOME"                    "/Users/me"
                      "SHELL"                   "/bin/bash"
                      "TERM"                    "xterm"
                      "LANG"                    "en_US.UTF-8"
                      "TMPDIR"                  "/tmp"
                      "USER"                    "me"
                      "SOME_UNRELATED_VAR"      "keepme?"}
                 (map (fn [k] [k "leaked"])) t/scrubbed-env-vars)
        env    (t/child-env parent)]
    (assertions
      "every scrubbed var is absent — an ANTHROPIC_* leak would redirect the turn
       to a metered key, and a CLAUDE_CODE_* leak makes the child think it is nested"
      (filterv #(contains? env %) t/scrubbed-env-vars) => []

      "PATH is preserved so the binary resolves"
      (get env "PATH") => "/usr/bin"

      "HOME is preserved — the macOS keychain read that recovers the subscription
       OAuth token is keyed off it, and a wrong HOME makes auth fail silently"
      (get env "HOME") => "/Users/me"

      "the rest of the preserve-list survives"
      (get env "SHELL") => "/bin/bash"
      (get env "TERM") => "xterm"
      (get env "LANG") => "en_US.UTF-8"
      (get env "TMPDIR") => "/tmp"

      "it is a full REPLACEMENT, not a merge: anything off the preserve-list is gone"
      (contains? env "SOME_UNRELATED_VAR") => false

      "values are all strings (babashka.process :env requires that)"
      (every? string? (vals env)) => true
      (every? string? (keys env)) => true))

  (component "specific billing-critical vars"
    (let [env (t/child-env {"PATH"                  "/usr/bin"
                            "HOME"                  "/h"
                            "ANTHROPIC_API_KEY"     "sk-ant-secret"
                            "ANTHROPIC_AUTH_TOKEN"  "tok"
                            "ANTHROPIC_BASE_URL"    "https://proxy"
                            "CLAUDE_CODE_USE_BEDROCK" "1"
                            "CLAUDECODE"            "1"})]
      (assertions
        "ANTHROPIC_API_KEY cannot reach the child (this is the whole point)"
        (contains? env "ANTHROPIC_API_KEY") => false
        "nor a substituted base URL"
        (contains? env "ANTHROPIC_BASE_URL") => false
        "nor a Bedrock/Vertex redirect"
        (contains? env "CLAUDE_CODE_USE_BEDROCK") => false
        "no value anywhere in the child env mentions the secret"
        (some #(str/includes? % "sk-ant-secret") (vals env)) => nil)))

  (component "tolerates the java.util.Map that System/getenv returns"
    (assertions
      "does not throw and still finds PATH"
      (string? (get (t/child-env (System/getenv)) "PATH")) => true))

  (component "the `extra` escape hatch cannot smuggle a scrubbed var back in"
    (let [env (t/child-env {"PATH" "/usr/bin" "HOME" "/h"}
                {"MY_FLAG"           "on"
                 "ANTHROPIC_API_KEY" "sk-ant-sneaky"
                 "CLAUDECODE"        "1"})]
      (assertions
        "an explicitly-requested extra var does reach the child"
        (get env "MY_FLAG") => "on"

        "but a scrubbed var is filtered even when passed deliberately — this
         escape hatch must never be usable to move billing off the subscription"
        (contains? env "ANTHROPIC_API_KEY") => false
        (contains? env "CLAUDECODE") => false
        (some #(str/includes? % "sk-ant-sneaky") (vals env)) => nil

        "keyword keys are accepted and stringified"
        (get (t/child-env {"PATH" "/usr/bin"} {:MY_KW "v"}) "MY_KW") => "v"))))

;;; ---------------------------------------------------------------------------
;;; JSON-Schema subset

(specification "unsupported-schema-keywords"
  (component "everything malli->json-schema emits is inside the probed subset"
    ;; P1 probed the CLI's --json-schema validator against exactly these
    ;; constructs. If this ever regresses, the mechanism falls back to
    ;; :fenced-json rather than dying at startup with no JSON on stdout.
    (assertions
      "[:maybe X] → oneOf + null"
      (t/unsupported-schema-keywords (types/malli->json-schema [:maybe :string])) => #{}
      "[:or …] → anyOf"
      (t/unsupported-schema-keywords (types/malli->json-schema [:or :string :int])) => #{}
      "[:map {:closed true}] → additionalProperties:false (every builtin tool is closed)"
      (t/unsupported-schema-keywords
        (types/malli->json-schema [:map {:closed true} [:a :string]])) => #{}
      "[:vector {:min 1}] → minItems"
      (t/unsupported-schema-keywords
        (types/malli->json-schema [:vector {:min 1} :string])) => #{}
      "[:map-of …] → additionalProperties as a schema"
      (t/unsupported-schema-keywords
        (types/malli->json-schema [:map-of :string :int])) => #{}
      "[:enum …]"
      (t/unsupported-schema-keywords (types/malli->json-schema [:enum "a" "b"])) => #{}
      "deeply nested combination"
      (t/unsupported-schema-keywords
        (types/malli->json-schema
          [:map [:x {:optional true} [:maybe [:vector [:map [:y :int]]]]]])) => #{}))

  (component "a real builtin tool definition"
    (let [defs (mapv tp/tool->anthropic-tool-def (builtin/builtin-tools))]
      (assertions
        "no builtin tool's schema would be rejected by the CLI validator"
        (into (sorted-set)
          (mapcat #(t/unsupported-schema-keywords (:input-schema %)))
          defs) => #{}
        "and there really are tools being checked"
        (pos? (count defs)) => true)))

  (component "genuinely unsupported keywords are reported"
    (assertions
      "$ref / patternProperties are outside the probed subset"
      (t/unsupported-schema-keywords
        {"type"              "object"
         "patternProperties" {"^a" {"type" "string"}}
         "properties"        {"x" {"$ref" "#/$defs/y"}}}) => #{"$ref" "patternProperties"}

      "if/then/else too"
      (t/unsupported-schema-keywords
        {"type" "object" "if" {"type" "string"} "then" {"const" 1}})
      => #{"if" "then"}

      "property NAMES are never mistaken for keywords"
      (t/unsupported-schema-keywords
        {"type" "object" "properties" {"if" {"type" "string"} "$ref" {"type" "int"}}}) => #{}))

  (component "envelope-mechanism"
    (assertions
      "clean tools use --json-schema (CC validates and re-prompts internally)"
      (t/envelope-mechanism [write-tool list-tool]) => :json-schema
      "no tools at all is still :json-schema (the envelope is simply omitted)"
      (t/envelope-mechanism []) => :json-schema
      "a tool the validator would reject degrades to the fenced envelope instead
       of an opaque exit-1 startup death"
      (t/envelope-mechanism [{:name "x" :description "d"
                              :input-schema {"$ref" "#/$defs/q"}}]) => :fenced-json)))

;;; ---------------------------------------------------------------------------
;;; envelope-schema

(specification "envelope-schema"
  (component "two tools, :auto"
    (let [s      (t/envelope-schema [write-tool list-tool] :auto)
          calls  (get-in s ["properties" "tool_calls"])
          names  (mapv #(get-in % ["properties" "name" "const"]) (get-in calls ["items" "oneOf"]))]
      (assertions
        "an object with additionalProperties:false"
        (get s "type") => "object"
        (get s "additionalProperties") => false

        "a discriminated union over the tool names, pinned with const"
        names => ["fs_write" "fs_list"]

        "each branch requires name and input"
        (get-in calls ["items" "oneOf" 0 "required"]) => ["name" "input"]

        "the tool's own JSON Schema is carried through verbatim, so the model is
         told the real input contract"
        (get-in calls ["items" "oneOf" 0 "properties" "input"]) => (:input-schema write-tool)

        "prose has its own slot so a turn can talk AND call tools"
        (get-in s ["properties" "assistant_text" "type"]) => "string"

        "tool_calls is NEVER required: a required array makes the model invent an
         entry, and escapement would then EXECUTE it for real"
        (get s "required") => nil
        (contains? (set (get s "required" [])) "tool_calls") => false

        "unconstrained call count under :auto"
        (get calls "minItems") => nil
        (get calls "maxItems") => nil)))

  (component ":tool-choice {:type :tool} — the :verdict-schema wrap-up path"
    (let [s     (t/envelope-schema [write-tool list-tool] {:type :tool :name "fs_list"})
          calls (get-in s ["properties" "tool_calls"])]
      (assertions
        "only the forced tool's branch survives"
        (get-in calls ["items" "properties" "name" "const"]) => "fs_list"
        "a single branch is inlined rather than wrapped in a pointless oneOf"
        (get-in calls ["items" "oneOf"]) => nil
        "exactly one call"
        (get calls "minItems") => 1
        (get calls "maxItems") => 1
        "still not required — minItems only constrains a PRESENT array"
        (get s "required") => nil)))

  (component ":tool-choice :any"
    (let [calls (get-in (t/envelope-schema [write-tool list-tool] :any)
                  ["properties" "tool_calls"])]
      (assertions
        "at least one call, any tool"
        (get calls "minItems") => 1
        (get calls "maxItems") => nil)))

  (component "nothing to extract → nil, so --json-schema is omitted entirely"
    (assertions
      "no tools"
      (t/envelope-schema [] :auto) => nil
      (t/envelope-schema nil :auto) => nil
      ":none suppresses tool calling"
      (t/envelope-schema [write-tool] :none) => nil
      "a forced name matching no offered tool"
      (t/envelope-schema [write-tool] {:type :tool :name "nope"}) => nil))

  (component "the synthesized schema is itself inside the supported subset"
    (assertions
      "so we never hand the CLI a schema its own validator would reject"
      (t/unsupported-schema-keywords (t/envelope-schema [write-tool list-tool] :auto)) => #{}
      "and it serializes to JSON cleanly"
      (string? (json/generate-string (t/envelope-schema [write-tool] :auto))) => true)))

;;; ---------------------------------------------------------------------------
;;; system prompt / tools doc

(specification "system-prompt-text"
  (let [txt (t/system-prompt-text "You are a build agent." [write-tool list-tool] :json-schema)]
    (assertions
      "the caller's system prompt leads"
      (str/starts-with? txt "You are a build agent.") => true

      "every tool name is documented — this is the ONLY channel through which the
       model learns the tools exist, since --tools \"\" leaves the CLI toolless and
       --json-schema only applies AFTER the model's turn"
      (str/includes? txt "fs_write") => true
      (str/includes? txt "fs_list") => true

      "with each tool's real input schema"
      (str/includes? txt "\"content\"") => true

      "and the envelope instructions"
      (str/includes? txt "tool_calls") => true))

  (component "fenced-json mechanism gets fence instructions instead"
    (let [txt (t/system-prompt-text "S" [write-tool] :fenced-json)]
      (assertions
        "tells the model to emit one fenced json block"
        (str/includes? txt "```json") => true)))

  (component "no tools"
    (let [txt (t/system-prompt-text "Just answer." [] :json-schema)]
      (assertions
        "no envelope instructions are added when there is nothing to call"
        (str/includes? txt "tool_calls") => false
        (str/trim txt) => "Just answer.")))

  (component "nil system"
    (assertions
      "never blank — an empty --system-prompt-file would hand CC its own persona"
      (str/blank? (t/system-prompt-text nil [] :json-schema)) => false)))

;;; ---------------------------------------------------------------------------
;;; render-transcript / stdin

(specification "render-transcript"
  (let [msgs [{:role :user :content [{:type :text :text "Write a.txt"}]}
              {:role    :assistant
               :content [{:type :thinking :thinking "SECRET REASONING" :signature "sig123"}
                         {:type :text :text "Sure."}
                         {:type :tool_use :id "toolu_1" :name "fs_write"
                          :input {:path "a.txt" :content "hi"}}]}
              {:role    :user
               :content [{:type :tool_result :tool_use_id "toolu_1" :content "wrote 2 bytes"}]}]
        out  (t/render-transcript msgs)]
    (assertions
      "user and assistant turns are labelled"
      (str/includes? out "## User") => true
      (str/includes? out "## Assistant") => true

      "thinking is DROPPED — its signature is invalid outside the thread that
       produced it, and replaying one into escapement.llm.api is a 400"
      (str/includes? out "SECRET REASONING") => false
      (str/includes? out "sig123") => false

      "tool calls render with their id so a result can be matched to its call"
      (str/includes? out "fs_write") => true
      (str/includes? out "toolu_1") => true

      "tool results render with the id they answer"
      (str/includes? out "wrote 2 bytes") => true

      "never starts with / — a leading slash risks slash-command interpretation"
      (str/starts-with? out "/") => false))

  (component "a message that would render leading-slash text"
    (let [out (t/render-transcript [{:role :user :content [{:type :text :text "/Users/x is a path"}]}])]
      (assertions
        "still never starts with /"
        (str/starts-with? out "/") => false)))

  (component "error tool results are marked"
    (let [out (t/render-transcript
                [{:role :user :content [{:type :tool_result :tool_use_id "t1"
                                         :content "boom" :is-error true}]}])]
      (assertions
        "so the model knows the call failed"
        (str/includes? out "ERROR") => true)))

  (component "empty content never yields a blank prompt"
    (assertions
      "the CLI rejects empty stdin outright"
      (str/blank? (t/render-transcript [])) => false)))

(specification "transcript->stdin"
  (let [line   (t/transcript->stdin
                 [{:role    :user
                   :content [{:type :text :text "What is this?"}
                             {:type   :image
                              :source {:type :base64 :media-type "image/png" :data "AAAA"}}]}])
        parsed (json/parse-string line true)]
    (assertions
      "one user message — the CLI's stream-json input accepts only type:user"
      (:type parsed) => "user"
      (get-in parsed [:message :role]) => "user"

      "no trailing newline (the caller adds it)"
      (str/ends-with? line "\n") => false

      "the transcript text comes first"
      (get-in parsed [:message :content 0 :type]) => "text"

      "image blocks ride along verbatim so vision still works"
      (get-in parsed [:message :content 1 :type]) => "image"
      "translated to the wire's snake_case media_type"
      (get-in parsed [:message :content 1 :source :media_type]) => "image/png"
      (get-in parsed [:message :content 1 :source :data]) => "AAAA"))

  (component "url image source"
    (let [parsed (json/parse-string
                   (t/transcript->stdin
                     [{:role :user :content [{:type :image :source {:type :url :url "https://x/y.png"}}]}])
                   true)]
      (assertions
        "passes the url form through"
        (get-in parsed [:message :content 1 :source :type]) => "url"
        (get-in parsed [:message :content 1 :source :url]) => "https://x/y.png"))))

;;; ---------------------------------------------------------------------------
;;; Stream fold → Response

(specification "stream fold — tool-call fixture"
  (let [resp (finalize-fixture "tool-call")
        tu   (first (filterv #(= :tool_use (:type %)) (:content resp)))]
    (assertions
      "produces a schema-valid Response"
      (types/validate-response resp) => nil

      "a tool call means :tool_use"
      (:stop-reason resp) => :tool_use

      "the envelope's tool_calls became a :tool_use block"
      (:name tu) => "fs_write"
      (:input tu) => {:path "a.txt" :content "hi"}

      "with the envelope's prose alongside it"
      (some #(= :text (:type %)) (:content resp)) => true

      "the model that actually ran is reported, not the alias we asked for"
      (:model resp) => "claude-haiku-4-5-20251001"

      "NN-9: no thinking block survives — messages-atom is shared across a
       failover, and a CLI-minted signature replayed into the API is a 400"
      (filterv #(#{:thinking :redacted_thinking} (:type %)) (:content resp)) => []

      "CC's own internal StructuredOutput tool_use is NOT surfaced as a chart tool"
      (filterv #(= "StructuredOutput" (:name %)) (:content resp)) => []

      "backend is identified"
      (get-in resp [:backend-metadata :backend]) => :claude-cli
      (get-in resp [:backend-metadata :mechanism]) => :json-schema))

  (component "NN-10: tool_use ids are globally unique"
    ;; handle-tool-use-block keys retry-counts by tool_use_id and treats a SECOND
    ;; failure for the same id as fatal, so a reused id like "call_1" would let
    ;; turn 2's first validation error kill the node.
    (let [id1 (:id (first (filterv #(= :tool_use (:type %)) (:content (finalize-fixture "tool-call")))))
          id2 (:id (first (filterv #(= :tool_use (:type %)) (:content (finalize-fixture "tool-call")))))]
      (assertions
        "two folds of the SAME fixture still produce different ids"
        (= id1 id2) => false
        "and they carry the traceable prefix"
        (str/starts-with? id1 t/tool-use-id-prefix) => true)))

  (component "NN-6: input tokens come from the per-call maximum, not result.usage"
    (let [resp (finalize-fixture "tool-call")]
      (assertions
        "the real context is max(input + cache_read + cache_creation) over
         assistant messages = 10 + 41 + 10066 = 10117, NOT result.usage's
         40 + 30321 + 10189 = 40550, which aggregates CC's internal calls 4×"
        (get-in resp [:usage :input-tokens]) => 10117

        "output tokens DO come from the aggregate — every one was really generated"
        (get-in resp [:usage :output-tokens]) => 256

        "cache fields are zeroed because they are already folded into
         :input-tokens, and llm_conversation compares that against the context
         window directly"
        (get-in resp [:usage :cache-creation-input-tokens]) => 0
        (get-in resp [:usage :cache-read-input-tokens]) => 0

        "the raw numbers are preserved for auditing"
        (some? (get-in resp [:backend-metadata :usage/raw])) => true))))

(specification "stream fold — text-only fixture"
  (let [resp (finalize-fixture "text-only")]
    (assertions
      "schema-valid"
      (types/validate-response resp) => nil
      "plain prose ends the turn"
      (:stop-reason resp) => :end_turn
      "the text is carried"
      (:text (first (:content resp))) => "The capital of France is Paris."
      "exactly one text block, no thinking"
      (mapv :type (:content resp)) => [:text])))

(specification "stream fold — success with no structured_output"
  (let [resp (finalize-fixture "success-no-structured-output")]
    (assertions
      "schema-valid"
      (types/validate-response resp) => nil
      "no tool call means :end_turn — the same 'model talked instead of calling
       the tool' outcome every other backend produces, which charts handle"
      (:stop-reason resp) => :end_turn
      "the prose is preserved rather than lost"
      (str/includes? (:text (first (:content resp))) "could not find") => true
      "content is never empty"
      (pos? (count (:content resp))) => true)))

(specification "stream fold — internal api_retry then success"
  (let [acc  (fold-fixture "api-retry-rate-limit-then-ok")
        resp (t/stream-acc-finalize acc {:mechanism :json-schema :request-model "sonnet" :exit 0})]
    (assertions
      "the turn succeeded"
      (types/validate-response resp) => nil
      (:stop-reason resp) => :end_turn

      "the CLI's internal retry is recorded so a chart can see it happened"
      (mapv :error (get-in resp [:backend-metadata :cli/api-retries])) => ["rate_limit"]

      "and the subscription rate-limit window is surfaced"
      (get-in resp [:backend-metadata :cli/rate-limit :rateLimitType]) => "five_hour")))

(specification "stream fold — NN-5: truncation is never :max_tokens"
  (let [acc  (fold-fixture "truncated-max-output-tokens")
        resp (t/stream-acc-finalize acc {:mechanism :json-schema :request-model "sonnet" :exit 1})]
    (assertions
      "schema-valid"
      (types/validate-response resp) => nil

      "reported as :end_turn. drive-turn! stitches a :max_tokens stop by appending
       a synthetic ASSISTANT prefill and re-requesting — but the CLI accepts only
       type:user messages, so the prefill is dropped, the no-progress guard trips,
       and the node dies"
      (:stop-reason resp) => :end_turn
      (= :max_tokens (:stop-reason resp)) => false

      "the truncation is flagged in metadata instead"
      (get-in resp [:backend-metadata :truncated]) => true
      (str/includes? (get-in resp [:backend-metadata :truncation-detail]) "output token maximum") => true

      "the partial text is kept, not replaced by the API-Error string"
      (str/includes? (:text (first (:content resp))) "beginning of a very long answer") => true
      (str/includes? (:text (first (:content resp))) "API Error") => false))

  (component "truncated-result? discriminates"
    (assertions
      "recognizes the output-cap message"
      (t/truncated-result?
        {:result "API Error: Claude's response exceeded the 64 output token maximum."}) => true
      "an ordinary error is not truncation"
      (t/truncated-result? {:result "API Error: 401 authentication_error"}) => false
      (t/truncated-result? nil) => false)))

(specification "stream fold — fenced-json envelope"
  (let [resp (finalize-fixture "fenced-json-tool-call" {:mechanism :fenced-json})
        tu   (first (filterv #(= :tool_use (:type %)) (:content resp)))]
    (assertions
      "schema-valid"
      (types/validate-response resp) => nil
      "the tool call is extracted from the fenced block"
      (:stop-reason resp) => :tool_use
      (:name tu) => "fs_list"
      (:input tu) => {:path "."}
      "the LAST fenced block wins, so a worked EXAMPLE earlier in the reply is
       not mistaken for the answer"
      (filterv #(= "example/ignore" (:name %)) (:content resp)) => []))

  (component "parse-fenced-envelope"
    (assertions
      "takes the last block"
      (t/parse-fenced-envelope "```json\n{\"a\":1}\n```\n```json\n{\"a\":2}\n```") => {:a 2}
      "accepts a bare fence with no language tag"
      (t/parse-fenced-envelope "```\n{\"a\":1}\n```") => {:a 1}
      "falls back to whole-string JSON"
      (t/parse-fenced-envelope "{\"a\":3}") => {:a 3}
      "nil on prose"
      (t/parse-fenced-envelope "I have no idea.") => nil
      (t/parse-fenced-envelope nil) => nil)))

(specification "process-stream-line! robustness"
  (component "unknown line types and junk are survivable"
    (let [acc (atom (t/stream-acc-init))]
      (t/process-stream-line! acc "{\"type\":\"some_future_type\",\"x\":1}")
      (t/process-stream-line! acc "{\"type\":\"system\",\"subtype\":\"brand_new\"}")
      (t/process-stream-line! acc "this is not json at all")
      (t/process-stream-line! acc "")
      (t/process-stream-line! acc nil)
      (assertions
        "unknown types are ignored, not fatal — the CLI adds system subtypes
         between releases and one must never fail a turn"
        (:result @acc) => nil
        "non-JSON lines are counted, not thrown"
        (:parse-failures @acc) => 1
        "blank/nil lines are skipped entirely"
        (:line-count @acc) => 3)))

  (component "a <synthetic> model is not adopted"
    (let [acc (atom (t/stream-acc-init))]
      (t/process-stream-line! acc
        "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"model\":\"<synthetic>\",\"content\":[]}}")
      (assertions
        "the CLI reports <synthetic> when it never reached a real model"
        (:model @acc) => nil))))

;;; ---------------------------------------------------------------------------
;;; structured-output->content

(specification "structured-output->content"
  (assertions
    "names round-trip byte-exact — the consumer dispatches by string lookup"
    (:name (first (t/structured-output->content
                    {:tool_calls [{:name "fs_write" :input {:path "a"}}]}
                    #{"fs_write"} nil))) => "fs_write"

    "a call naming an un-offered tool is DROPPED, not dispatched (an unknown name
     is a hard error in handle-tool-use-block)"
    (t/structured-output->content {:tool_calls [{:name "evil/rm" :input {}}]} #{"fs_write"} "fallback")
    => [{:type :text :text "fallback"}]

    "non-map input degrades to {} rather than throwing"
    (:input (first (t/structured-output->content
                     {:tool_calls [{:name "t" :input "oops"}]} #{"t"} nil))) => {}

    "prose plus calls yields both blocks in order"
    (mapv :type (t/structured-output->content
                  {:assistant_text "Doing it." :tool_calls [{:name "t" :input {}}]}
                  #{"t"} nil)) => [:text :tool_use]

    "an empty envelope still yields non-empty content (the schema demands it)"
    (t/structured-output->content {} nil "leftover text") => [{:type :text :text "leftover text"}]
    (t/structured-output->content {} nil nil) => [{:type :text :text ""}]))

;;; ---------------------------------------------------------------------------
;;; categorize-failure  (NN-12, NN-13)

(specification "categorize-failure"
  (component "every api_retry error name maps to a canonical category"
    (assertions
      "auth-flavoured"
      (t/api-retry-error->category "authentication_failed") => :auth
      (t/api-retry-error->category "oauth_org_not_allowed") => :auth
      (t/api-retry-error->category "billing_error") => :auth
      "throttling"
      (t/api-retry-error->category "rate_limit") => :rate-limited
      (t/api-retry-error->category "overloaded") => :overloaded
      "caller error"
      (t/api-retry-error->category "invalid_request") => :invalid-request
      (t/api-retry-error->category "model_not_found") => :invalid-request
      "server-side"
      (t/api-retry-error->category "server_error") => :transport
      (t/api-retry-error->category "unknown") => :transport

      "and every mapped category is one the protocol actually defines"
      (every? proto/error-categories (vals t/api-retry-error->category)) => true))

  (component "NN-13: a timeout prefers the last internal retry category"
    ;; The CLI sits in internal backoff for MINUTES on rate_limit/overloaded; a
    ;; bare :timeout would hide the real cause from the chart.
    (assertions
      "rate_limit backoff surfaces as :rate-limited, not :timeout"
      (:category (t/categorize-failure {:timed-out?  true :exit 143
                                        :api-retries [{:error "rate_limit"}]})) => :rate-limited
      "overload backoff surfaces as :overloaded"
      (:category (t/categorize-failure {:timed-out?  true :exit 143
                                        :api-retries [{:error "overloaded"}]})) => :overloaded
      "the LAST retry wins when several happened"
      (:category (t/categorize-failure
                   {:timed-out? true
                    :api-retries [{:error "rate_limit"} {:error "overloaded"}]})) => :overloaded
      "with no retry information it really is a timeout"
      (:category (t/categorize-failure {:timed-out? true :exit 143})) => :timeout))

  (component "missing binary is TERMINAL with an actionable message"
    ;; :invalid-request is terminal, so run-turn fails fast instead of retrying
    ;; a nonexistent binary three times.
    (let [r (t/categorize-failure {:exit 127 :stderr "claude: command not found" :binary "claude"})]
      (assertions
        (:category r) => :invalid-request
        "and tells the user how to fix it"
        (str/includes? (:message r) "not found") => true
        (str/includes? (:message r) "claude auth login") => true))
    (let [r (t/categorize-failure {:exit 1 :stderr "spawn claude ENOENT"})]
      (assertions
        "ENOENT on stderr counts as a missing binary too"
        (:category r) => :invalid-request)))

  (component "NN-12: a startup death prints only to stderr, with no JSON at all"
    (assertions
      "a rejected --json-schema is a malformed invocation, not a transport blip"
      (:category (t/categorize-failure
                   {:exit 1 :result nil
                    :stderr "Error: --json-schema is not a valid JSON Schema: data/type must be array"}))
      => :invalid-request

      "a missing system-prompt file likewise"
      (:category (t/categorize-failure
                   {:exit 1 :result nil :stderr "Error: System prompt file not found: /tmp/x"}))
      => :invalid-request

      "an unrecognized startup failure defaults to :invalid-request, not a retry loop"
      (:category (t/categorize-failure {:exit 1 :result nil :stderr "Error: something new"}))
      => :invalid-request

      "and the stderr is quoted into the message so it is diagnosable"
      (str/includes? (:message (t/categorize-failure
                                 {:exit 1 :result nil :stderr "Error: something new"}))
        "something new") => true))

  (component "a parsed result line is trusted over the exit code"
    ;; The CLI exits 1 on is_error but still prints a full result line.
    (let [acc (fold-fixture "is-error-auth")
          r   (t/categorize-failure {:exit 1 :result (:result acc) :stderr ""})]
      (assertions
        "an expired OAuth token is :auth, so the chart can prompt a re-login"
        (:category r) => :auth
        (str/includes? (:message r) "OAuth") => true)))

  (component "result text patterns"
    (let [cat (fn [txt] (:category (t/categorize-failure {:exit 1 :result {:result txt}})))]
      (assertions
        "credit balance / billing"
        (cat "Your credit balance is too low") => :auth
        "invalid api key"
        (cat "API Error: 401 invalid api key") => :auth
        "usage limit (the subscription five-hour window)"
        (cat "Claude usage limit reached. Your limit resets at 3pm") => :rate-limited
        "429"
        (cat "API Error: 429 too many requests") => :rate-limited
        "overloaded"
        (cat "API Error: 529 overloaded_error") => :overloaded
        "context length"
        (cat "prompt is too long: 250000 tokens > 200000 maximum") => :context-length
        "unknown model"
        (cat "Error: invalid model name 'claude-sonnet-4-7'") => :invalid-request

        "the CLI's OWN wording for a model outside its registry. Observed live:
         without this pattern the failure landed in :transport and run-turn burned
         three retries on a model that could never work"
        (cat (str "There's an issue with the selected model (gpt-5.4-mini). It may not "
               "exist or you may not have access to it. Run --model to pick a different model."))
        => :invalid-request
        "network trouble"
        (cat "fetch failed: ECONNREFUSED") => :transport
        "anything unrecognized is :transport, i.e. retryable"
        (cat "the flux capacitor fluxed") => :transport)))

  (component "api_error_status on the result line takes precedence"
    (assertions
      "an explicit status name is the most reliable signal"
      (:category (t/categorize-failure
                   {:exit 1 :result {:api_error_status "overloaded" :result "something vague"}}))
      => :overloaded))

  (component "structured-output retry exhaustion"
    (let [acc (fold-fixture "max-structured-retries")
          r   (t/categorize-failure {:exit 1 :result (:result acc)})]
      (assertions
        "categorized (not thrown) and retryable — a re-ask often succeeds"
        (contains? proto/error-categories (:category r)) => true
        "and the subtype is surfaced for diagnosis"
        (str/includes? (:message r) "error_max_structured_output_retries") => true)))

  (component "every branch yields a legal category and a non-blank message"
    (let [cases [{:timed-out? true}
                 {:exit 127 :stderr "command not found"}
                 {:exit 1 :result nil :stderr ""}
                 {:exit 0 :result {:is_error true :result "boom"}}
                 {:exit 143 :result nil :stderr ""}
                 {}]]
      (assertions
        "no input shape escapes categorization"
        (mapv #(contains? proto/error-categories (:category (t/categorize-failure %))) cases)
        => [true true true true true true]
        "and every message is usable"
        (every? #(not (str/blank? (:message (t/categorize-failure %)))) cases) => true))))

;;; ---------------------------------------------------------------------------
;;; Dropped request keys (NN-14)

(specification "dropped request keys"
  (assertions
    "the CLI exposes no surface for sampling params, stop sequences or metadata"
    (set t/dropped-request-keys)
    => #{:temperature :top-p :top-k :stop-sequences :metadata :max-tokens
         :system-cache-control :conversation/id}

    ":max-tokens is deliberately among them. CLAUDE_CODE_MAX_OUTPUT_TOKENS exists
     but exceeding it FAILS the turn hard rather than truncating, which would
     turn a routine long answer into a dead node"
    (contains? (set t/dropped-request-keys) :max-tokens) => true

    "warn-dropped! is a pure side-effect and never throws or alters the request"
    (t/warn-dropped! (assoc simple-request :temperature 0.7 :top-p 0.9
                       :stop-sequences ["x"] :max-tokens 4096)) => nil
    "including on a clean request"
    (t/warn-dropped! simple-request) => nil)

  (component "the warning is emitted ONCE per backend, not once per turn"
    ;; Observed live: a 5-turn chart produced five identical warning lines,
    ;; because every turn carries the same dropped keys.
    (let [warned (atom false)
          dirty  (assoc simple-request :max-tokens 4096 :temperature 0.5)]
      (t/warn-dropped! dirty warned)
      (assertions
        "the first call flips the flag"
        @warned => true)
      ;; Subsequent calls are no-ops (the flag stays true and nothing is logged).
      (t/warn-dropped! dirty warned)
      (t/warn-dropped! dirty warned)
      (assertions
        "and later turns do not re-warn"
        @warned => true)))

  (component "a clean request never trips the once-flag"
    (let [warned (atom false)]
      (t/warn-dropped! simple-request warned)
      (assertions
        "so a genuinely dirty request later still gets its one warning"
        @warned => false))))

;;; ---------------------------------------------------------------------------
;;; stdin size guard (NN-2)

(specification "stdin-byte-limit"
  (assertions
    "the limit is the CLI's documented ~10 MB stdin cap"
    t/stdin-byte-limit => 10000000))
