# Getting structured output from small local models

When an Escapement chart needs structured data back from a worker LLM the
first instinct is to declare an `:allowed-events` entry with a Malli
schema — the framework synthesises an OpenAI-format tool, validates the
input, and raises a chart event on success. That works reliably with
frontier models (Claude, GLM-4.6, GPT-4.x). It does not work reliably
with small local models served by ollama.

This doc captures what we measured and the pattern we use instead.

## What goes wrong with `:allowed-events` under llama3.2:3b

Bench against ollama 11434 (Vulkan, `OLLAMA_NUM_PARALLEL=4`,
`OLLAMA_CONTEXT_LENGTH=8192`), warm, llama3.2:3b. 10 trials per cell;
each trial = one `/v1/chat/completions` call asking the model to submit
three haiku via a single `event__poet_done` tool. Counted as **ok** only
if the response contained a `tool_calls` block whose arguments parsed
into the expected shape (after the same JSON-stringified-array coercion
the runtime applies via `tool-input-transformer`).

| tool description style                                  | ok / 10  | dominant failure                              |
|---------------------------------------------------------+----------+-----------------------------------------------|
| default (`"Fire chart event :poet-done"`)               | 0/10     | `idx` returned as string, no coercion target  |
| minimal prose (`"Submit 3 haiku. ..."`)                 | 0/10     | same, plus model invents tool names           |
| skeleton example `{"idx":0,"haikus":["<haiku 1>",...]}` | **9/10** | one stray non-string element                  |
| placeholder example with `<haiku 1 line A>` …           | 0/10     | model emits the tool call as **text content** |
| verbose multi-paragraph prose                           | 5/10     | half emit the tool call as text content       |

Two patterns dominate the failures:

- **Tool call as text content** — `~50 %` of the time llama3.2:3b returns
  `{"name":"event__poet_done","parameters":{...}}` inside the assistant
  `content` field instead of in `tool_calls`. The chart never sees it.
- **Description leakage into tool name** — when the description starts
  with an imperative the model sometimes uses the first words as the
  tool name (`{"name":"Submit","parameters":{...}}`). Putting a literal
  JSON example in the description (`{"idx":0,...}`) avoids this AND
  removes the type errors.

Real haiku examples in the description also seed the model's output —
every llama3.2:3b run echoed "Golden light descends" / "River's gentle
whisper" because those were in the example. Use placeholder strings
(`<haiku 1>`) instead, never sample content.

## What we use instead: plain text with chart-side parsing

Drop `:allowed-events` entirely. Set `:max-turns 1`, give a strict
free-text format in the system prompt, transition on `:llm.idle`, parse
the captured text in a chart `script`.

Same model, same hardware, fresh 10-trial harness:

| task                                          | shape                                  | ok / 10   | per-call latency |
|-----------------------------------------------+----------------------------------------+-----------+------------------|
| poet — one haiku per call                     | three short lines, nothing else        | **10/10** | ~270 ms          |
| judge — pick 1 of N with reason               | line 1: digit; line 2: one sentence    | **10/10** | ~600 ms          |
| poet (tool-call comparison, best description) | `event__poet_done` with idx + haikus[] | 5/10      | ~1100 ms         |

The plain-text path is **2-4× faster per call** in addition to being
~2× more reliable. The chart-side parser fits in five lines:

```clojure
;; Pull text out of an :llm.idle event.
(defn- captured-text [data]
  (some-> (get-in data [:_event :data :text]) clojure.string/trim not-empty))

;; First-line digit, rest joined as reason.
(defn- parse-pick [text max-n]
  (when text
    (let [[head & rest] (clojure.string/split-lines text)]
      (when-let [[_ d] (re-find #"^\s*(\d{1,2})\b" (or head ""))]
        (let [n (Long/parseLong d)]
          (when (<= 1 n max-n)
            [(dec n)
             (some->> rest (map clojure.string/trim)
               (remove clojure.string/blank?)
               (clojure.string/join " "))]))))))
```

## Chart wiring template

```clojure
(state {:id :working}
  (on-entry {} (send {:event :child/safety-stop :delay 60000}))
  (h/llm-conversation
    {:id             "judge"
     :system         (fn [_env data] (judge-system-prompt …))
     :real-tools     []
     :allowed-events []                  ; <-- no tool calls
     :max-turns      1
     :message        (fn [_env data] (judge-user-message data))})

  ;; Success — parse and forward to parent.
  (transition {:event :llm.idle :target :reported}
    (script {:expr
             (fn [env data]
               (if-let [[idx reason] (parse-pick (captured-text data) 3)]
                 (send-to! env (:reply-to data) :judge-result
                   {:idx idx :reason reason})
                 (send-to! env (:reply-to data) :judge-result
                   {:abstained? true :raw (captured-text data)}))
               nil)}))

  ;; LLM errored — abstain, do not wait for safety timer.
  (transition {:event :error.llm :target :reported}
    (script {:expr (fn [env data]
                     (send-to! env (:reply-to data) :judge-result
                       {:abstained? true
                        :error (get-in data [:_event :data])})
                     nil)}))

  ;; True-hang backstop (rare — only fires if ollama itself stops responding).
  (transition {:event :child/safety-stop :target :reported}
    (script {:expr (fn [env data]
                     (send-to! env (:reply-to data) :judge-result
                       {:abstained? true :hang? true})
                     nil)})))
(final {:id :reported})
```

Key points:

- Three exits from `:working`: success (`:llm.idle`), error (`:error.llm`),
  hang (`:child/safety-stop`). The success path runs the parser; the
  other two report an abstention. Order matters in the chart — success
  first, since a successful turn raises *both* the domain event and
  `:llm.idle`; document-order resolution lets success win.
- The safety timer is a backstop only. Earlier versions of this chart
  hard-coded a 30 s safety stop as the ONLY exit on parse failure,
  which made every clean-but-empty turn cost a 30 s wait. The
  `:llm.idle` / `:error.llm` transitions cut a 3×3 tournament from
  ~96 s to ~68 s without any other change.

## Prompting tips for plain-text shapes

- **Be strict about format, loose about content.** "Reply in EXACTLY
  this format and nothing else: Line 1 ... Line 2 ...". Then ban
  preamble, labels, code fences explicitly.
- **Never put real content in the example.** Use placeholders
  (`<haiku 1 line A>`) — small models copy verbatim from examples.
- **One LLM call per atomic output.** Asking llama3.2:3b for "three
  haiku separated by `---`" returned correct content but with the
  wrong separator 10/10 times. Splitting into three calls is simpler
  to parse and parallelises across `OLLAMA_NUM_PARALLEL` slots.
- **Tolerate variations in the leading line.** `re-find #"^\s*(\d+)\b"`
  accepts `1`, `1.`, `1)`, `1 -`, `1 - my pick`. The cost is one regex;
  the win is several percentage points of success rate.

## When tool calls still make sense

- Frontier models (Claude, GLM-4.6, GPT-4.x) get tool calling right
  >99 % of the time even on complex schemas. Use `:allowed-events`
  there — schema validation and `:data-schema`-typed event data are
  worth the wire bytes.
- Whenever the structured payload has more than ~2 fields with mixed
  types and you do NOT want to write a parser. The chart-side parser
  scales poorly past `<digit>\n<reason>`-class shapes.

## Caveats not addressed here

Plain-text I/O does not fix everything. Small models still
hallucinate semantically — in our 3×3 smoke run with the planner
prompt "Run a haiku tournament with 3 poets and 3 judges. Theme: …"
llama3.2:3b dutifully replied `START 17 17 …` and the tournament ran
17×17. Bounding numeric values in the planner state's script (clamp
to user-supplied range, reject on impossible answers) is the next
problem to solve, and it is separate from how we shape the LLM's
response.

## Cross-references

- `localollama.md` — ollama Vulkan setup and the original observation
  that 30 s safety-stops dominated the wall clock.
- `src/escapement/examples/haiku_tournament_dynamic.clj` — full
  reference chart using the pattern in this doc.
- `src/escapement/invocation/llm_conversation.clj` — the event-tool
  machinery (still useful for frontier models) and the
  `tool-input-transformer` that coerces stringified scalars.
- `src/escapement/chart/helpers.clj` `capture-llm-output` — writes
  the captured text to an artifact file. Used by the host step.
