(ns escapement.examples.haiku-tournament-dynamic
  "TRUE dynamic-N haiku tournament — parent LLM picks N at runtime, then
  spawns N sibling sessions via `escapement.engine.spawn/spawn-child!` and
  routes their replies back. This chart contains NO `MAX-*`. The parent has one `:composing` state, one
  `:judging-r1` state, and one `:judging-r2` state. Each `on-entry` spawns
  child SESSIONS via `escapement.engine.spawn/spawn-child!`: `:composing`
  spawns N poets, `:judging-r1` spawns M × N judge1 children (each judges
  ONE poet's 3 haiku in isolation), `:judging-r2` spawns M judge2 children
  (each ranks all finalists together). Each child runs a standalone
  top-level chart (`poet-chart` / `judge1-chart` / `judge2-chart`) in its
  own sid. Children `send!` their reply event to `:reply-to` (= parent sid).
  The parent decrements a `:pending` counter on each reply and transitions
  out when the counter hits zero.

  ## Plain-text I/O instead of tool calls

  Small open-source models (llama3.2:3b in particular) are unreliable at
  OpenAI-format tool calling: in a 10-trial harness against ollama only
  ~50 % of poet/judge calls produced a valid `tool_calls` block — the
  rest emitted the call as text content. See `localollama.md` for the
  evidence. Plain-text responses scored 10/10 on both the poet and judge
  tasks at 2-4× lower per-call latency.

  So every child here drives its LLM with `:allowed-events []` (no tool
  defs at all) and transitions on `:llm.idle`, parsing the captured text
  in a script. The host LLM already worked this way for the summary
  artifact.

  Requires the runner be invoked with `:multi-session? true`, which makes
  the pump loop drain every session's queue (parent + every child) on each
  iteration. The chart var carries `^:multi-session?` metadata so the CLI
  picks this up automatically.

  Run:
    OLLAMA_API_KEY=dummy bb -m escapement.cli run \\
      escapement.examples.haiku-tournament-dynamic/agent \\
      --no-tui --backend ollama \\
      --api-base-url http://localhost:11434/v1 \\
      --model llama3.2:3b \\
      --param 'user-input=\"Run a tournament with 4 poets and 4 judges. \\
                            Theme: lanterns over the river.\"'"
  (:require
   [clojure.string :as str]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.data-model.operations :as ops]
   [com.fulcrologic.statecharts.elements
    :refer [final on-entry script send state transition]]
   [com.fulcrologic.statecharts.protocols :as sp]
   [escapement.chart.helpers :as h]
   [escapement.engine.spawn :as spawn]))

;; True-hang backstop only. Clean conversation termination (`:llm.idle` /
;; `:error.llm`) is handled by explicit transitions in each child chart, so
;; this timer only fires when ollama itself never responds. Keep generous.
(def ^:private child-safety-ms 60000)

(def judge-personas
  ;; Indexed by the child's :idx — vector grows by adding entries, NOT a cap.
  ["Formalist — cares above all about clean 5-7-5 syllable structure."
   "Imagist — rewards vivid, concrete sensory imagery."
   "Lyricist — listens for musicality and cadence."
   "Minimalist — every word must earn its place; suspicious of ornament."
   "Naturalist — loves clear seasonal references (kigo) and nature."
   "Modernist — rewards surprise, fresh metaphor, broken expectations."
   "Traditionalist — values kigo and a true kireji (cutting word)."
   "Romantic — judges by emotional resonance and depth of feeling."
   "Absurdist — loves playful inversion, juxtaposition, and wit."
   "Skeptic — clarity over cleverness; punishes vagueness."])

(defn- persona-for [i]
  (nth judge-personas (mod i (count judge-personas))))

(declare format-haikus format-finalists)

(defn- send-to!
  [env target-sid event-kw payload]
  (let [queue  (get env ::sc/event-queue)
        my-sid (some-> env ::sc/vwmem deref ::sc/session-id)]
    (when (and queue my-sid)
      (sp/send! queue env
        {:target            target-sid
         :source-session-id my-sid
         :event             event-kw
         :data              payload}))))

(defn- raise!
  ([env event-kw] (raise! env event-kw {}))
  ([env event-kw payload]
   (let [my-sid (some-> env ::sc/vwmem deref ::sc/session-id)]
     (send-to! env my-sid event-kw payload))))

(defn- captured-text
  "Pull the assistant text out of a `:llm.idle` event in a chart `script`'s
  `data` 2nd arg. Returns trimmed string or nil."
  [data]
  (some-> (get-in data [:_event :data :text]) str/trim not-empty))

(defn- from-id
  "Pull the invokeid string of the conversation that fired this `:llm.idle`."
  [data]
  (let [v (get-in data [:_event :data :from])]
    (cond
      (keyword? v) (name v)
      (string? v)  v
      :else        nil)))

(defn- parse-three-lines
  "Trim, drop blanks, take the first 3 non-blank lines and join with \\n.
  Tolerates leading prose by greedy-stripping anything before the first
  short-looking line, but in practice the prompt is strict enough that the
  raw response is just the haiku."
  [text]
  (when text
    (let [lines (->> (str/split-lines text)
                     (map str/trim)
                     (remove str/blank?)
                     vec)]
      (when (>= (count lines) 3)
        (str/join "\n" (take 3 lines))))))

(defn- parse-pick
  "Parse `<digit>\\n<reason>` from a judge's reply. Returns `[idx0 reason]`
  where `idx0` is 0-based (we present 1-based to the model). Tolerates `1`,
  `1.`, `1)`, `1 -`. Returns nil if no leading digit in range [1, max-n]."
  [text max-n]
  (when text
    (let [[first-line & rest-lines] (str/split-lines text)
          m (when first-line
              (re-find #"^\s*(\d{1,2})\b" first-line))
          n (some-> m second Long/parseLong)
          reason (some->> rest-lines
                   (map str/trim) (remove str/blank?) (str/join " ")
                   str/trim not-empty)]
      (when (and n (<= 1 n max-n))
        [(dec n) (or reason "")]))))

;; ---------------------------------------------------------------------------
;; Prompts
;; ---------------------------------------------------------------------------

(def planner-prompt
  (str
    "You are the dispatcher for a haiku tournament. Read the user input "
    "and reply on multiple lines in EXACTLY this format and nothing else.\n\n"
    "If the user CLEARLY specifies (a) a poet count in [3,30], (b) a judge "
    "count in [3,30], AND (c) a topic/theme, reply with 4 lines:\n"
    "  Line 1: the word START\n"
    "  Line 2: the poet count (just the integer)\n"
    "  Line 3: the judge count (just the integer)\n"
    "  Line 4: the theme (one short phrase)\n\n"
    "If ANY of those three is missing, ambiguous, or out of range, reply "
    "with 2 lines:\n"
    "  Line 1: the word ABORT\n"
    "  Line 2: one short sentence explaining what's missing.\n\n"
    "Output ONLY those lines. No preamble, no labels, no commentary."))

(defn- poet-system [idx]
  (str "You are Poet #" idx " in a haiku tournament. Write ONE original "
    "haiku on the given theme. A haiku is three short lines. Aim for "
    "vivid imagery and an honest moment. Output ONLY the three lines — "
    "no title, no numbering, no commentary, no preamble."))

(defn- judge1-system [idx persona poet-idx]
  (str "You are Judge #" idx ". Persona: " persona "\n"
    "You will see three numbered haiku, all by Poet #" poet-idx ". Pick "
    "ONE favorite and explain in ONE sentence (true to your persona) why "
    "it stood out over the other two.\n\n"
    "Reply in EXACTLY this format and nothing else:\n"
    "  Line 1: just the digit 1, 2, or 3\n"
    "  Line 2: one short sentence explaining your pick\n"
    "No preamble, no labels, no extra lines."))

(defn- judge2-system [idx persona n-finalists]
  (str "You are Judge #" idx " in the FINAL round. Persona: " persona "\n"
    "You will see " n-finalists " numbered finalist haiku. Pick the "
    "SINGLE best and explain in ONE sentence (true to your persona) why.\n\n"
    "Reply in EXACTLY this format and nothing else:\n"
    "  Line 1: just an integer between 1 and " n-finalists "\n"
    "  Line 2: one short sentence explaining your pick\n"
    "No preamble, no labels, no extra lines."))

(def host-system
  (str "You are the Master of Ceremonies of a haiku tournament. You will be "
    "given the full record of the contest — theme, every poet's three "
    "haiku, each judge's round-1 picks with reasons, the finalists, and "
    "each judge's round-2 vote with reasons — and you must produce ONE "
    "well-formatted Markdown report named `tournament-summary.md`. The "
    "report should:\n"
    "  1. Open with the theme and the size of the field (N poets, M "
    "judges).\n"
    "  2. Announce the WINNER (or declare the tie) up front, with the "
    "winning haiku rendered in a fenced block.\n"
    "  3. Give a one-paragraph reading of why the winner won, drawing on "
    "the round-2 judges' actual reasons.\n"
    "  4. A `## Finalists` section listing each finalist with its haiku, "
    "its poet, and a brief note on the round-1 consensus that lifted it.\n"
    "  5. A `## All entries` section listing every poet's three haiku.\n"
    "  6. A `## Notes from the judges` section quoting one or two of the "
    "most interesting round-2 reasons verbatim, attributed by persona.\n"
    "Reply with ONLY the Markdown — no preamble, no closing remarks, no "
    "code fences around the document itself. End your turn after the "
    "report."))

(defn- host-user-message [data]
  (let [theme    (:theme data)
        haikus   (:haikus data)
        picks    (:judge1-picks data)
        finals   (:finalists data)
        votes    (:judge2-votes data)
        result   (:result data)]
    (str "THEME: " theme "\n"
      "POETS: " (count haikus) ", JUDGES: " (count votes) "\n\n"
      "## All haiku (by poet)\n"
      (format-haikus haikus) "\n\n"
      "## Round-1 picks (one per poet, per judge)\n"
      (str/join "\n"
        (for [[j ps] (sort picks)
              :let [persona (persona-for j)]]
          (str "Judge " j " (" persona "):\n"
            (str/join "\n"
              (for [{:keys [poet_idx haiku_idx reason]} ps]
                (str "  - poet " poet_idx " haiku " haiku_idx " — " reason)))))) "\n\n"
      "## Finalists (poet → finalist index)\n"
      (format-finalists finals) "\n\n"
      "## Round-2 votes\n"
      (str/join "\n"
        (for [[j {:keys [finalist_idx reason]}] (sort votes)
              :let [persona (persona-for j)]]
          (str "Judge " j " (" persona ") → finalist " finalist_idx ": " reason))) "\n\n"
      "## Tally\n"
      (pr-str result) "\n\n"
      "Now write `tournament-summary.md`.")))

(defn- format-haikus [haikus]
  (str/join "\n\n"
    (for [[p hs] (sort haikus)]
      (str "POET " p ":\n"
        (str/join "\n"
          (map-indexed (fn [hi h]
                         (str "  [" hi "] "
                           (str/replace h "\n" " / ")))
            hs))))))

(defn- format-finalists [finalists]
  (str/join "\n\n"
    (map-indexed
      (fn [fi {:keys [poet-idx haiku]}]
        (str "[" fi "] (from Poet " poet-idx ")\n"
          (str/join "\n"
            (map #(str "    " %) (str/split-lines haiku)))))
      finalists)))

(defn- format-numbered-haikus
  "Render haikus 1-based for a judge's user message."
  [haikus]
  (str/join "\n\n"
    (map-indexed (fn [i h] (str (inc i) ".\n" h)) haikus)))

;; ---------------------------------------------------------------------------
;; CHILD: poet — 3 sequential single-haiku LLM calls.
;; Each `:haiku-N` state runs one llm-conversation with the poet system
;; prompt. On `:llm.idle` we capture the text, parse it down to 3 lines,
;; accumulate into `:haikus`, then advance. The third state additionally
;; sends `:poet-result` back to the tournament parent.
;; ---------------------------------------------------------------------------

(defn- poet-step
  "Builds one of the three sequential poet states. `n` is 1-based for the
  invokeid; `next-id` is the state to transition to on success (or
  `:reported` for the last step, which also fires the parent send)."
  [n next-id last?]
  (let [id    (keyword (str "haiku-" n))
        invk  (str "poet-" n)]
    (state {:id id}
      (on-entry {} (send {:event :child/safety-stop :delay child-safety-ms}))
      (h/llm-conversation
        {:id   invk
         :params-fn
         (fn [_env data]
           {:system                       (poet-system (:idx data))
            :real-tools                   []
            :allowed-events               []
            :max-turns                    1
            :max-conversation-duration-ms 60000
            :initial-user-message
            (str "Theme: \"" (:theme data) "\". "
              "Write haiku #" n " of 3. Output only the three lines.")})})

      ;; Success path: capture text, parse 3 lines, accumulate, advance.
      (transition {:event :llm.idle
                   :cond  (fn [_env data] (= invk (from-id data)))
                   :target next-id}
        (script {:expr
                 (fn [env data]
                   (let [text   (captured-text data)
                         haiku  (parse-three-lines text)
                         haikus (cond-> (or (:haikus data) [])
                                  haiku (conj haiku))]
                     (when (and last? (= 3 (count haikus)))
                       (send-to! env (:reply-to data)
                         :poet-result {:idx (:idx data) :haikus haikus}))
                     [(ops/assign :haikus haikus)]))}))

      ;; LLM errored — abstain and stop trying (no more sequential calls).
      (transition {:event :error.llm :target :reported}
        (script {:expr
                 (fn [env data]
                   (send-to! env (:reply-to data)
                     :poet-result
                     {:idx        (:idx data)
                      :abstained? true
                      :error      (get-in data [:_event :data])})
                   nil)}))

      ;; True-hang backstop.
      (transition {:event :child/safety-stop :target :reported}
        (script {:expr
                 (fn [env data]
                   (send-to! env (:reply-to data)
                     :poet-result
                     {:idx (:idx data) :abstained? true :hang? true})
                   nil)})))))

(def poet-chart
  (chart/statechart
    {:initial :haiku-1}
    (poet-step 1 :haiku-2 false)
    (poet-step 2 :haiku-3 false)
    (poet-step 3 :reported true)
    (final {:id :reported})))

;; ---------------------------------------------------------------------------
;; CHILD: judge1 — single LLM call, pick 1-of-3 for ONE poet.
;; Plain text reply: `<digit>\n<reason>`. Parsed in script.
;; ---------------------------------------------------------------------------

(def judge1-chart
  (chart/statechart
    {:initial :working}
    (state {:id :working}
      (on-entry {} (send {:event :child/safety-stop :delay child-safety-ms}))
      (h/llm-conversation
        {:id "judge1"
         :params-fn
         (fn [_env data]
           {:system     (judge1-system (:idx data) (:persona data)
                          (:poet-idx data))
            :real-tools []
            :allowed-events []
            :max-turns                    1
            :max-conversation-duration-ms 60000
            :initial-user-message
            (str "Here are the three haiku by Poet #" (:poet-idx data) ":\n\n"
              (format-numbered-haikus (:haikus data))
              "\n\nNow reply: number on line 1, reason on line 2.")})})

      (transition {:event :llm.idle :target :reported}
        (script {:expr
                 (fn [env data]
                   (let [text   (captured-text data)
                         parsed (parse-pick text 3)]
                     (if parsed
                       (let [[idx0 reason] parsed]
                         (send-to! env (:reply-to data)
                           :judge1-result
                           {:idx       (:idx data)
                            :poet-idx  (:poet-idx data)
                            :haiku-idx idx0
                            :reason    reason}))
                       (send-to! env (:reply-to data)
                         :judge1-result
                         {:idx (:idx data) :poet-idx (:poet-idx data)
                          :abstained? true :raw text}))
                     nil))}))

      (transition {:event :error.llm :target :reported}
        (script {:expr
                 (fn [env data]
                   (send-to! env (:reply-to data)
                     :judge1-result
                     {:idx (:idx data) :poet-idx (:poet-idx data)
                      :abstained? true
                      :error (get-in data [:_event :data])})
                   nil)}))

      (transition {:event :child/safety-stop :target :reported}
        (script {:expr
                 (fn [env data]
                   (send-to! env (:reply-to data)
                     :judge1-result
                     {:idx (:idx data) :poet-idx (:poet-idx data)
                      :abstained? true :hang? true})
                   nil)})))
    (final {:id :reported})))

;; ---------------------------------------------------------------------------
;; CHILD: judge2 — single LLM call, pick 1-of-N finalists.
;; ---------------------------------------------------------------------------

(def judge2-chart
  (chart/statechart
    {:initial :working}
    (state {:id :working}
      (on-entry {} (send {:event :child/safety-stop :delay child-safety-ms}))
      (h/llm-conversation
        {:id "judge2"
         :params-fn
         (fn [_env data]
           (let [n (count (:finalists data))]
             {:system     (judge2-system (:idx data) (:persona data) n)
              :real-tools []
              :allowed-events []
              :max-turns                    1
              :max-conversation-duration-ms 60000
              :initial-user-message
              (str "Here are the " n " finalist haiku:\n\n"
                (str/join "\n\n"
                  (map-indexed (fn [i {:keys [poet-idx haiku]}]
                                 (str (inc i) ". (Poet " poet-idx ")\n" haiku))
                    (:finalists data)))
                "\n\nNow reply: number on line 1, reason on line 2.")}))})

      (transition {:event :llm.idle :target :reported}
        (script {:expr
                 (fn [env data]
                   (let [n      (count (:finalists data))
                         text   (captured-text data)
                         parsed (parse-pick text n)]
                     (if parsed
                       (let [[idx0 reason] parsed]
                         (send-to! env (:reply-to data)
                           :judge2-result
                           {:idx          (:idx data)
                            :finalist_idx idx0
                            :reason       reason}))
                       (send-to! env (:reply-to data)
                         :judge2-result
                         {:idx (:idx data) :abstained? true :raw text}))
                     nil))}))

      (transition {:event :error.llm :target :reported}
        (script {:expr
                 (fn [env data]
                   (send-to! env (:reply-to data)
                     :judge2-result
                     {:idx (:idx data) :abstained? true
                      :error (get-in data [:_event :data])})
                   nil)}))

      (transition {:event :child/safety-stop :target :reported}
        (script {:expr
                 (fn [env data]
                   (send-to! env (:reply-to data)
                     :judge2-result
                     {:idx (:idx data) :abstained? true :hang? true})
                   nil)})))
    (final {:id :reported})))

;; ---------------------------------------------------------------------------
;; Pure tally fns
;; ---------------------------------------------------------------------------
(defn- compute-finalists [haikus judge1-picks]
  (let [poets (sort (keys haikus))]
    (vec
      (for [p poets
            :let [counts (->> (vals judge1-picks)
                              (mapcat identity)
                              (filter #(= p (:poet_idx %)))
                              (group-by :haiku_idx)
                              (map (fn [[hi vs]] [hi (count vs)
                                                  (mapv :reason vs)]))
                              (sort-by (fn [[hi cnt _]] [(- cnt) hi])))
                  [hi cnt reasons] (first counts)
                  hi (or hi 0)]]
        {:poet-idx  p
         :haiku-idx hi
         :haiku     (get-in haikus [p hi])
         :votes     (or cnt 0)
         :reasons   (or reasons [])}))))

(defn- compute-winner [judge2-votes]
  (let [counts  (->> (vals judge2-votes)
                     (map :finalist_idx)
                     frequencies
                     (sort-by (fn [[_ c]] (- c))))
        top     (some-> counts first second)
        leaders (mapv first (take-while #(= (second %) top) counts))]
    (if (= 1 (count leaders))
      {:winner-idx (first leaders) :votes top :standings (vec counts)}
      {:tie leaders :votes top :standings (vec counts)})))

;; ---------------------------------------------------------------------------
;; PARENT — planner uses plain text, then spawns N poets, then M×N judge1s,
;; then M judge2s, then host writes the summary.
;; ---------------------------------------------------------------------------

(defn- spawn-many!
  [env n chart chart-id input-fn]
  (let [parent (spawn/parent-sid env)]
    (vec
      (for [i (range n)]
        (spawn/spawn-child! env
          {:chart    chart
           :chart-id chart-id
           :input    (assoc (input-fn i) :reply-to parent)})))))

(defn- parse-planner
  "Parse planner output. Returns one of:
     {:verb :start :poets P :audience M :theme T}
     {:verb :abort :reason R}
     {:verb :error :raw text}"
  [text]
  (if-let [text (some-> text str/trim not-empty)]
    (let [lines (->> (str/split-lines text)
                     (map str/trim)
                     (remove str/blank?)
                     vec)
          v0    (str/upper-case (or (first lines) ""))]
      (cond
        (str/starts-with? v0 "START")
        (let [poets    (some-> (get lines 1) (->> (re-find #"\d+")) Long/parseLong)
              audience (some-> (get lines 2) (->> (re-find #"\d+")) Long/parseLong)
              theme    (some-> (get lines 3) str/trim not-empty)]
          (if (and poets audience theme
                (<= 3 poets 30) (<= 3 audience 30))
            {:verb :start :poets poets :audience audience :theme theme}
            {:verb :error :raw text}))

        (str/starts-with? v0 "ABORT")
        {:verb :abort :reason (or (->> (rest lines) (str/join " ") str/trim not-empty)
                                "unspecified")}

        :else
        {:verb :error :raw text}))
    {:verb :error :raw ""}))

(def ^{:multi-session? true} agent
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :planning}

      ;; ---------- PHASE 1: planner (plain text START/ABORT) ----------
      (state {:id :planning}
        (h/llm-conversation
          {:id "planner"
           :params-fn
           (fn [_env data]
             {:system                       planner-prompt
              :real-tools                   []
              :allowed-events               []
              :max-turns                    1
              :max-conversation-duration-ms 60000
              :initial-user-message
              (str "USER INPUT:\n" (pr-str (:user-input data "")))})})

        (transition {:event :llm.idle
                     :cond  (fn [_env data] (= "planner" (from-id data)))
                     :target :route-planner}
          (script {:expr
                   (fn [_env data]
                     (let [p (parse-planner (captured-text data))]
                       [(ops/assign :plan p)]))}))

        (transition {:event :error.llm :target :aborted}
          (script {:expr (fn [_env data]
                           [(ops/assign :abort-reason
                              (str "planner error: "
                                (pr-str (get-in data [:_event :data]))))])})))

      ;; Internal routing state — decides next based on planner verb.
      (state {:id :route-planner}
        (on-entry {}
          (script {:expr
                   (fn [env data]
                     (case (:verb (:plan data))
                       :start (do (raise! env :plan/start) nil)
                       :abort (do (raise! env :plan/abort) nil)
                       (do (raise! env :plan/abort) nil)))}))
        (transition {:event :plan/start :target :composing}
          (script {:expr
                   (fn [_env data]
                     (let [{:keys [poets audience theme]} (:plan data)]
                       [(ops/assign :poet-count poets)
                        (ops/assign :audience-count audience)
                        (ops/assign :theme theme)]))}))
        (transition {:event :plan/abort :target :aborted}
          (script {:expr
                   (fn [_env data]
                     [(ops/assign :abort-reason
                        (or (:reason (:plan data))
                          (str "planner output not parseable: "
                            (pr-str (:raw (:plan data))))))])})))

      ;; ---------- PHASE 2: composing ----------
      (state {:id :composing}
        (on-entry {}
          (script {:expr
                   (fn [env data]
                     (let [n (:poet-count data)
                           t (:theme data)]
                       (spawn-many! env n
                         poet-chart ::poet
                         (fn [i] {:idx i :theme t}))
                       [(ops/assign :pending n)
                        (ops/assign :haikus {})]))}))
        (transition {:event :poet-result :type :internal}
          (script {:expr
                   (fn [env data]
                     (let [{:keys [idx haikus abstained?]}
                           (get-in data [:_event :data])
                           h' (cond-> (or (:haikus data) {})
                                (and (not abstained?) (seq haikus))
                                (assoc idx haikus))
                           p' (dec (long (:pending data)))]
                       (when (zero? p')
                         (raise! env :composing-done))
                       [(ops/assign :haikus h')
                        (ops/assign :pending p')]))}))
        (transition {:event :composing-done :target :judging-r1}))

      ;; ---------- PHASE 3: judging round 1 ----------
      (state {:id :judging-r1}
        (on-entry {}
          (script {:expr
                   (fn [env data]
                     (let [m       (:audience-count data)
                           hs      (:haikus data)
                           parent  (spawn/parent-sid env)
                           pairs   (for [i (range m)
                                         p (sort (keys hs))]
                                     [i p])]
                       (doseq [[i p] pairs]
                         (spawn/spawn-child! env
                           {:chart    judge1-chart
                            :chart-id ::judge1
                            :input    {:idx      i
                                       :persona  (persona-for i)
                                       :poet-idx p
                                       :haikus   (get hs p)
                                       :reply-to parent}}))
                       [(ops/assign :pending (count pairs))
                        (ops/assign :judge1-picks {})]))}))
        (transition {:event :judge1-result :type :internal}
          (script {:expr
                   (fn [env data]
                     (let [{:keys [idx poet-idx haiku-idx reason abstained?]}
                           (get-in data [:_event :data])
                           jp  (or (:judge1-picks data) {})
                           jp' (if abstained?
                                 jp
                                 (update jp idx (fnil conj [])
                                   {:poet_idx  poet-idx
                                    :haiku_idx haiku-idx
                                    :reason    reason}))
                           p'  (dec (long (:pending data)))]
                       (when (zero? p')
                         (raise! env :judging-r1-done))
                       [(ops/assign :judge1-picks jp')
                        (ops/assign :pending p')]))}))
        (transition {:event :judging-r1-done :target :tallying-r1}))

      ;; ---------- PHASE 4: tally R1 → finalists ----------
      (state {:id :tallying-r1}
        (on-entry {}
          (script {:expr
                   (fn [env data]
                     (let [f (compute-finalists (:haikus data)
                               (:judge1-picks data))]
                       (raise! env :tally-r1-done)
                       [(ops/assign :finalists f)]))}))
        (transition {:event :tally-r1-done :target :judging-r2}))

      ;; ---------- PHASE 5: judging round 2 ----------
      (state {:id :judging-r2}
        (on-entry {}
          (script {:expr
                   (fn [env data]
                     (let [m (:audience-count data)
                           fs (:finalists data)]
                       (spawn-many! env m
                         judge2-chart ::judge2
                         (fn [i] {:idx       i
                                  :persona   (persona-for i)
                                  :finalists fs}))
                       [(ops/assign :pending m)
                        (ops/assign :judge2-votes {})]))}))
        (transition {:event :judge2-result :type :internal}
          (script {:expr
                   (fn [env data]
                     (let [{:keys [idx finalist_idx reason abstained?]}
                           (get-in data [:_event :data])
                           v' (cond-> (or (:judge2-votes data) {})
                                (not abstained?)
                                (assoc idx {:finalist_idx finalist_idx
                                            :reason       reason}))
                           p' (dec (long (:pending data)))]
                       (when (zero? p')
                         (raise! env :judging-r2-done))
                       [(ops/assign :judge2-votes v')
                        (ops/assign :pending p')]))}))
        (transition {:event :judging-r2-done :target :tallying-r2}))

      ;; ---------- PHASE 6: tally R2 → winner ----------
      (state {:id :tallying-r2}
        (on-entry {}
          (script {:expr
                   (fn [env data]
                     (let [result (compute-winner (:judge2-votes data))]
                       (raise! env :tally-r2-done)
                       [(ops/assign :result result)]))}))
        (transition {:event :tally-r2-done :target :summarizing}))

      ;; ---------- PHASE 7: host LLM writes tournament-summary.md ----------
      (state {:id :summarizing}
        (h/llm-conversation
          {:id "host"
           :params-fn
           (fn [_env data]
             {:system                       host-system
              :real-tools                   []
              :allowed-events               []
              :max-turns                    2
              :max-conversation-duration-ms 120000
              :initial-user-message         (host-user-message data)})})
        (transition {:event :llm.idle
                     :cond  (fn [_env data] (= "host" (from-id data)))
                     :target :finished}
          (h/capture-llm-output {:as "tournament-summary.md"}))
        (transition {:event :error.llm :target :finished}
          (script {:expr (fn [_env data]
                           [(ops/assign :host-error
                              (get-in data [:_event :data]))])})))

      (final {:id :aborted})
      (final {:id :finished}))))
