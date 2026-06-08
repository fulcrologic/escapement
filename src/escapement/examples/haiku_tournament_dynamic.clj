(ns escapement.examples.haiku-tournament-dynamic
  "TRUE dynamic-N haiku tournament built on the multiplex invocation
  processor (`com.fulcrologic.statecharts.invocation.multiplex`). The
  parent LLM (planner) picks N and M at runtime; the chart then drives:

    Phase 2 (composing)   — multiplex of N poet sub-charts.
    Phase 3 (judging-r1)  — multiplex of M × N judge1 sub-charts (each
                            judges one poet's 3 haiku).
    Phase 5 (judging-r2)  — multiplex of M judge2 sub-charts (each ranks
                            all finalists together).

  Each child runs a standalone top-level chart (`poet-chart` /
  `judge1-chart` / `judge2-chart`). Children call `mux/reply` to send
  result events back to the grandparent; the multiplex's library-owned
  aggregator fires `done.invoke.<phase>` when every child has reached
  its `:reported` final.

  ## Plain-text I/O instead of tool calls

  Small open-source models (llama3.2:3b in particular) are unreliable at
  OpenAI-format tool calling: in a 10-trial harness against ollama only
  ~50 % of poet/judge calls produced a valid `tool_calls` block — the
  rest emitted the call as text content. See `localollama.md` for the
  evidence. Plain-text responses scored 10/10 on both the poet and judge
  tasks at 2-4× lower per-call latency.

  So every child here drives its LLM with `:allowed-events []` (no tool
  defs at all) and transitions on `:llm.idle`, parsing the captured text
  in a script.

  Requires the runner be invoked with `:multi-session? true`, which makes
  the pump loop drain every session's queue (parent + every child +
  aggregator) on each iteration. The chart var carries `^:multi-session?`
  metadata so the CLI picks this up automatically.

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
    :refer [cancel final on-entry on-exit script send state transition]]
   [com.fulcrologic.statecharts.invocation.multiplex :as mux :refer [multiplex]]
   [com.fulcrologic.statecharts.invocation.multiplex-options :as mo]
   [com.fulcrologic.statecharts.protocols :as sp]
   [escapement.chart.helpers :as h]))

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

(def poet-judge-model-pool
  "Alias keywords (defined in `.escapement.edn :llm/aliases`) that poets and
  judges draw from at random. Spans all three pool providers: local ollama
  (gemma), z.ai (GLM), and the opencode-go gateway (qwen/kimi). The HOST is NOT
  in this pool — it is pinned to gpt-5.5 (`:host-gpt`) below. Each child is
  assigned ONE random model by the parent's multiplex `child-params`, so a given
  poet/judge uses a single model across all its turns."
  [:p-gemma1b :p-gemma270m :p-glm47 :p-glm-turbo :p-oc-qwen :p-oc-kimi])

(def ^:private host-model :host-gpt)

(def ^:private pool-latency
  "TTFT failover for POOL-MODEL turns (poets / judges / revise). The opencode-go
  `qwen3.5-plus` gateway can stall ~30s — or hang outright — before its first
  token, which blocks the whole multiplex phase on a single child. Cap time-to-
  first-token and fail a stalled draw over to fast local gemma so the bracket
  never stalls on one slow provider. 10s clears the legitimately slower cloud
  models (opencode-go kimi + z.ai GLM peak ~8s ttft here) while still catching
  the 30s+ qwen stall. The HOST steps (muse / critique, pinned to gpt-5.5) are
  deliberately NOT capped — gpt-5.5 legitimately takes 4-8s to first token."
  {:latency {:first-token-ms 10000
             :fallback [{:provider :ollama :model "gemma3:1b"}]}})

(defn- rand-pool-model []
  (rand-nth poet-judge-model-pool))

(declare format-haikus format-finalists r1-support)

(defn- raise!
  "Self-targeted event send, used for chart-internal transitions out of
  on-entry scripts (e.g. routing decisions)."
  ([env event-kw] (raise! env event-kw {}))
  ([env event-kw payload]
   (let [queue  (get env ::sc/event-queue)
         my-sid (some-> env ::sc/vwmem deref ::sc/session-id)]
     (when (and queue my-sid)
       (sp/send! queue env {:target            my-sid
                            :source-session-id my-sid
                            :event             event-kw
                            :data              payload})))))

(defn- captured-text
  "Pull the assistant text out of a `:llm.idle` event in a chart `script`. The
  conversation delivers its output as an `:output-ref` handle, so this derefs it
  via `h/deref-output` (`env` + the script's `data`). Returns trimmed string or nil."
  [env data]
  (some-> (h/deref-output env data) str/trim not-empty))

(defn- from-id
  "Pull the invokeid string of the conversation that fired this `:llm.idle`.
  This is the LLM conversation invokeid (unrelated to multiplex `mo/from`)."
  [data]
  (let [v (get-in data [:_event :data :from])]
    (cond
      (keyword? v) (name v)
      (string? v)  v
      :else        nil)))

(defn- parse-three-lines
  "Trim, drop blanks, take the first 3 non-blank lines and join with \\n.
  Tolerates leading prose; in practice the prompt is strict enough that the
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

(defn- muse-system [idx]
  (str "You are the Muse whispering to Poet #" idx ". Offer a short burst of "
    "creative inspiration on the given theme: 3-5 vivid images, sensory "
    "fragments, or unexpected angles the poet might draw on. Do NOT write a "
    "haiku yourself. Output only the fragments, one per line, no preamble."))

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
  (str "You are the Master of Ceremonies of a haiku tournament. From the full "
    "contest record you must produce ONE tight Markdown report named "
    "`tournament-summary.md`. Be CONCISE. Use the poet numbers EXACTLY as they "
    "appear in the input; a poet absent from the record produced no usable "
    "haiku — say so, never invent an entry.\n\n"
    "TWO poets were SECRETLY helped by an LLM (the judges were not told; they "
    "saw only haiku text): the MUSE poet got an inspiration pass woven into "
    "its drafts BEFORE composing; the CRITIQUE poet drafted three, then an "
    "editor critiqued them and the poet revised all three. The input names "
    "both poets, the helper model, exactly what each was given, and their "
    "vote tallies.\n\n"
    "Structure the report EXACTLY like this:\n"
    "  1. `# <Theme>` — one line.\n"
    "  2. `## The Experiment` FIRST — a compact two-row table with columns "
    "`Role | Poet | Helper model | Round-1 | Round-2`, one row for the MUSE "
    "and one for the CRITIQUE, filled from the input (round-1 support count "
    "and round-2 vote count; the helper model verbatim from the input). "
    "Immediately below the table, render each of the two poets' winning/best "
    "haiku in a fenced block, labelled `Muse — Poet N` and `Critique — "
    "Poet N`.\n"
    "  3. `## The Laureate` — crown the WINNER (or declare the tie): \"Poet N "
    "— The Laureate\". Below that, a one-row Markdown table with columns "
    "`Poet | Role | Round-1 | Round-2` giving the winner's vote counts (Role "
    "is `Muse`, `Critique`, or `—` if unaided). Then the winning haiku in a "
    "fenced block.\n"
    "  4. Then AT MOST TWO short paragraphs of prose covering everything else "
    "— why the Laureate won (grounded in round-2 reasons), who was The "
    "Overlooked (fewest votes), and whether the hidden help appears to have "
    "moved the judging (stay honest if it didn't). No more sections, no "
    "per-poet entry dump.\n\n"
    "Reply with ONLY the Markdown — no preamble, no closing remarks, no "
    "code fences around the document itself. End your turn after the "
    "report."))

(defn- poet-scoreline
  "Explicit one-line record for a poet so the host reasons from numbers rather
  than eyeballing indices: round-1 support, finalist status, round-2 votes — or
  a clear note that the poet produced no usable haiku (absent from the record)."
  [data poet-idx]
  (let [haikus  (:haikus data)
        finals  (vec (:finalists data))
        votes   (:judge2-votes data)
        r1      (get (r1-support haikus (:judge1-picks data)) poet-idx 0)
        fin-idx (first (keep-indexed (fn [i f] (when (= poet-idx (:poet-idx f)) i)) finals))
        r2      (->> (vals votes)
                  (map :finalist_idx)
                  (filter #(= poet-idx (:poet-idx (nth finals % nil))))
                  count)]
    (if-not (contains? haikus poet-idx)
      "produced NO usable haiku — absent from the contest record (abstained or unparseable)"
      (str "round-1 support: " r1 " judge pick" (when (not= 1 r1) "s") "; "
        (if fin-idx (str "reached the finals (finalist " (inc fin-idx) ")") "did NOT reach the finals")
        "; round-2 votes: " r2))))

(defn- host-user-message [data]
  (let [theme    (:theme data)
        haikus   (:haikus data)
        picks    (:judge1-picks data)
        finals   (:finalists data)
        votes    (:judge2-votes data)
        result   (:result data)
        muse     (:muse-poet data)
        critique (:critique-poet data)
        support  (r1-support haikus picks)]
    (str "THEME: " theme "\n"
      "POETS: " (count haikus) ", JUDGES: " (count votes) "\n\n"
      "## SECRET ROLES — host eyes only (the judges were NOT told)\n"
      "Two poets were secretly helped; every other poet worked unaided.\n\n"
      "MUSE poet: Poet " (inc muse) " (helper model: gpt-5.5)\n"
      "  • what it was given (gpt-5.5 inspiration, woven into all 3 drafts):\n"
      (if-let [m (:muse-text data)]
        (str "      " (str/replace (str/trim m) "\n" "\n      ") "\n")
        "      (the muse pass produced nothing / failed)\n")
      "  • how it fared: " (poet-scoreline data muse) "\n\n"
      "CRITIQUE poet: Poet " (inc critique) " (helper model: gpt-5.5)\n"
      "  • what it was given (gpt-5.5 editor feedback; the poet then revised all 3):\n"
      (if-let [c (:critique-text data)]
        (str "      " (str/replace (str/trim c) "\n" "\n      ") "\n")
        "      (the critique pass produced nothing / failed)\n")
      (when-let [d (:critique-drafts data)]
        (str "  • its PRE-revision drafts were:\n"
          "      " (str/replace (str/join " / " (map #(str/replace % "\n" " / ") d))
                     "\n" " ") "\n"))
      "  • how it fared (after revision — this is what the judges saw): "
      (poet-scoreline data critique) "\n\n"
      "## All haiku (by poet)\n"
      (format-haikus haikus) "\n\n"
      "## Round-1 picks (one per poet, per judge)\n"
      (str/join "\n"
        (for [[j ps] (sort picks)
              :let [persona (persona-for j)]]
          (str "Judge " j " (" persona "):\n"
            (str/join "\n"
              (for [{:keys [poet_idx haiku_idx reason]} ps]
                (str "  - poet " (inc poet_idx) " haiku " (inc haiku_idx) " — " reason)))))) "\n\n"
      "## Finalists (poet → finalist index)\n"
      (format-finalists finals) "\n\n"
      "## Round-2 votes\n"
      (str/join "\n"
        (for [[j {:keys [finalist_idx reason]}] (sort votes)
              :let [persona (persona-for j)]]
          (str "Judge " j " (" persona ") → finalist " (inc finalist_idx) ": " reason))) "\n\n"
      "## Round-1 support (judges who picked each poet — lowest = The Overlooked)\n"
      (str/join "\n"
        (for [[p n] support]
          (str "  - poet " (inc p) ": " n " vote" (when (not= 1 n) "s")))) "\n\n"
      "## Tally\n"
      (pr-str result) "\n\n"
      "Now write `tournament-summary.md`, concise and in the exact structure "
      "from your instructions: `## The Experiment` table + the Muse and "
      "Critique haiku first, then `## The Laureate` with the winning haiku, "
      "then at most two short paragraphs for everything else.")))

(defn- format-haikus [haikus]
  (str/join "\n\n"
    (for [[p hs] (sort haikus)]
      (str "POET " (inc p) ":\n"
        (str/join "\n"
          (map-indexed (fn [hi h]
                         (str "  [" (inc hi) "] "
                           (str/replace h "\n" " / ")))
            hs))))))

(defn- format-finalists [finalists]
  (str/join "\n\n"
    (map-indexed
      (fn [fi {:keys [poet-idx haiku]}]
        (str "[" (inc fi) "] (from Poet " (inc poet-idx) ")\n"
          (str/join "\n"
            (map #(str "    " %) (str/split-lines haiku)))))
      finalists)))

(defn- format-numbered-haikus
  "Render haikus 1-based for a judge's user message."
  [haikus]
  (str/join "\n\n"
    (map-indexed (fn [i h] (str (inc i) ".\n" h)) haikus)))

(defn- critic-system [idx]
  (str "You are a trusted editor mentoring Poet #" idx ". You will see the "
    "poet's three draft haiku. Give brief, concrete feedback on how to make "
    "them sharper — imagery, word choice, rhythm, or the 5-7-5 shape. Be "
    "specific. Do NOT rewrite them yourself; the poet will revise. Output "
    "only your feedback."))

(defn- poet-revise-msg
  "Single revise turn: show the poet all three of its drafts plus the editor's
  free-form feedback, and ask it to regenerate the whole set."
  [data]
  (str "Theme: \"" (:theme data) "\". Here are your three draft haiku:\n\n"
    (format-numbered-haikus (:drafts data)) "\n\n"
    "An editor offered this feedback:\n" (:critique-text data) "\n\n"
    "Rewrite all three haiku, applying the feedback. Output the three revised "
    "haiku, each on three lines, separated by a blank line. No numbering, no "
    "commentary."))

(defn- parse-haiku-set
  "Parse `n` haiku out of a single free-form response. Prefers blank-line
  separated blocks; falls back to chunking all non-blank lines into groups of
  three. Returns a vector of up to `n` newline-joined haiku."
  [text n]
  (when text
    (let [blocks (->> (str/split text #"\n\s*\n")
                      (map str/trim) (remove str/blank?)
                      (keep parse-three-lines) vec)]
      (if (>= (count blocks) n)
        (vec (take n blocks))
        (->> (str/split-lines text)
             (map str/trim) (remove str/blank?)
             (partition-all 3) (take n)
             (mapv #(str/join "\n" %)))))))

;; ---------------------------------------------------------------------------
;; CHILD: poet — composes 3 haiku, with two optional secret roles the parent
;; assigns to ONE poet each (never the same poet):
;;
;;   :role :muse      — a gpt-5.5 inspiration pass runs BEFORE drafting; its
;;                      whisper (`:muse-text`) is woven into every draft.
;;   :role :critique  — AFTER the 3 drafts, a gpt-5.5 editor critiques the set
;;                      (`:critique-text`); the poet then takes one more turn
;;                      and REGENERATES all 3 haiku with its OWN model, applying
;;                      the feedback. The revised set is what the judges see.
;;
;; `:role-route` sends the muse poet through `:musing` first; everyone else
;; starts at `:haiku-1`. After `:haiku-3`, `:compose-route` sends the critique
;; poet through `:critiquing` → `:revising`; everyone else goes straight to
;; `:report`. The judges never learn which poet took either branch — they only
;; ever see haiku text indexed by a bare poet number.
;;
;; IMPORTANT: the poet ALWAYS sends exactly one `:haiku/poet-result` — the final
;; set if any haiku parsed, else an abstain. (A previous version replied only on
;; exactly 3 parsed haiku, so a poet whose draft failed to parse finalized
;; SILENTLY and vanished from the record — which is what confused the host.)
;; ---------------------------------------------------------------------------

(defn- poet-abstain!
  "Reply to the parent that this poet produced nothing usable, then fall to the
  `:reported` final. `extra` carries the reason (`:error` / `:hang?` / …). Still
  forwards any secret-role artifacts so the host can report that a helped poet
  was given something yet produced no haiku."
  [env data extra]
  (mux/reply env :haiku/poet-result
    (cond-> (merge {:idx (:idx data) :abstained? true} extra)
      (:muse-text data)     (assoc :muse-text (:muse-text data))
      (:critique-text data) (assoc :critique-text (:critique-text data))))
  nil)

(defn- poet-report!
  "Reply to the parent with the poet's final haiku set (partial sets are fine),
  carrying the secret-role artifacts (`:muse-text` / `:critique-text`) so the
  host — and ONLY the host — can report what each blessed poet was given. If no
  haiku parsed at all, abstain instead."
  [env data]
  (let [haikus (:haikus data)]
    (if (seq haikus)
      (mux/reply env :haiku/poet-result
        (cond-> {:idx (:idx data) :haikus haikus}
          (:muse-text data)     (assoc :muse-text (:muse-text data))
          (:critique-text data) (assoc :critique-text (:critique-text data)
                                  :drafts (:drafts data))))
      (poet-abstain! env data {:reason :no-parseable-haiku})))
  nil)

(defn- poet-step
  "One draft state: compose haiku #n with the poet's own model (plus the Muse's
  whisper, if present), accumulate into `:haikus`, advance to `next-id`. Never
  replies itself; the terminal `:report` state owns the single reply."
  [n next-id]
  (let [id     (keyword (str "haiku-" n))
        invk   (str "poet-" n)
        sendid (str "safety-haiku-" n)]
    (state {:id id}
      ;; Per-state hang backstop, cancelled on exit so a stale timer from a fast
      ;; early step can't fire during a slower later step and spuriously abort.
      (on-entry {} (send {:event :child/safety-stop :delay child-safety-ms :id sendid}))
      (on-exit  {} (cancel {:sendid sendid}))
      (h/llm-conversation
        {:id             invk
         :stream?        true
         :model          (fn [_env data] (:model data))
         :resilience     pool-latency
         :system         (fn [_env data] (poet-system (:idx data)))
         :real-tools     []
         :allowed-events []
         :max-turns      1
         :budget-ms      60000
         :message        (fn [_env data]
                           (str "Theme: \"" (:theme data) "\". "
                             ;; If this poet was secretly given a Muse, its
                             ;; gpt-5.5 inspiration is woven into every draft.
                             (when-let [m (:muse-text data)]
                               (str "Your muse whispers:\n" m "\n"))
                             "Write haiku #" n " of 3. Output only the three lines."))})

      (transition {:event :llm.idle
                   :cond  (fn [_env data] (= invk (from-id data)))
                   :target next-id}
        (script {:expr
                 (fn [env data]
                   (let [text   (captured-text env data)
                         haiku  (parse-three-lines text)
                         haikus (cond-> (or (:haikus data) [])
                                  haiku (conj haiku))]
                     [(ops/assign :haikus haikus)]))}))

      (transition {:event :error.llm :target :reported}
        (script {:expr (fn [env data]
                         (poet-abstain! env data {:error (get-in data [:_event :data])}))}))

      (transition {:event :child/safety-stop :target :reported}
        (script {:expr (fn [env data] (poet-abstain! env data {:hang? true}))})))))

;; The Muse — one secret inspiration call that runs BEFORE any haiku, ONLY for
;; the poet the parent randomly blessed (`:role :muse`). Its model is HARD-PINNED
;; to `:host-gpt` (gpt-5.5) regardless of the run's `--model`, so we measure what
;; a strong model's inspiration does for an otherwise-pooled poet. Its output is
;; stashed in `:muse-text` and woven into every draft (see `poet-step`'s message).
;; Any failure (error / hang) falls through to composing unaided.
(defn- muse-step []
  (state {:id :musing}
    (on-entry {} (send {:event :child/safety-stop :delay child-safety-ms :id "safety-musing"}))
    (on-exit  {} (cancel {:sendid "safety-musing"}))
    (h/llm-conversation
      {:id             "muse"
       :stream?        true
       :model          host-model                       ;; HARD-PINNED gpt-5.5
       :system         (fn [_env data] (muse-system (:idx data)))
       :real-tools     []
       :allowed-events []
       :max-turns      1
       :budget-ms      60000
       :message        (fn [_env data]
                         (str "Theme: \"" (:theme data) "\". Whisper your inspiration."))})
    (transition {:event :llm.idle
                 :cond  (fn [_env data] (= "muse" (from-id data)))
                 :target :haiku-1}
      (script {:expr (fn [env data]
                       [(ops/assign :muse-text (captured-text env data))])}))
    (transition {:event :error.llm :target :haiku-1})
    (transition {:event :child/safety-stop :target :haiku-1})))

;; The Critique — a gpt-5.5 editor reads the critique poet's 3 drafts and writes
;; feedback (`:critique-text`); the poet then revises. HARD-PINNED to gpt-5.5,
;; mirroring the Muse. Shows live as `poets.<idx>.critique`. Failure → keep the
;; drafts unrevised.
(defn- critique-step []
  (state {:id :critiquing}
    (on-entry {} (send {:event :child/safety-stop :delay child-safety-ms :id "safety-critiquing"}))
    (on-exit  {} (cancel {:sendid "safety-critiquing"}))
    (h/llm-conversation
      {:id             "critique"
       :stream?        true
       :model          host-model                       ;; HARD-PINNED gpt-5.5
       :system         (fn [_env data] (critic-system (:idx data)))
       :real-tools     []
       :allowed-events []
       :max-turns      1
       :budget-ms      60000
       :message        (fn [_env data]
                         (str "Here are Poet #" (:idx data) "'s three draft haiku:\n\n"
                           (format-numbered-haikus (:haikus data))
                           "\n\nGive your feedback for the revision."))})
    (transition {:event :llm.idle
                 :cond  (fn [_env data] (= "critique" (from-id data)))
                 :target :revising}
      (script {:expr (fn [env data]
                       [(ops/assign :drafts (:haikus data))
                        (ops/assign :critique-text (captured-text env data))])}))
    (transition {:event :error.llm :target :report})
    (transition {:event :child/safety-stop :target :report})))

;; Revise — the critique poet regenerates all 3 haiku with its OWN pool model,
;; applying the editor's feedback. The revised set replaces the drafts (judges
;; see only this). Parse failure / error / hang → fall back to the drafts.
(defn- revise-step []
  (state {:id :revising}
    (on-entry {} (send {:event :child/safety-stop :delay child-safety-ms :id "safety-revising"}))
    (on-exit  {} (cancel {:sendid "safety-revising"}))
    (h/llm-conversation
      {:id             "revise"
       :stream?        true
       :model          (fn [_env data] (:model data))   ;; poet's own model
       :resilience     pool-latency
       :system         (fn [_env data] (poet-system (:idx data)))
       :real-tools     []
       :allowed-events []
       :max-turns      1
       :budget-ms      60000
       :message        (fn [_env data] (poet-revise-msg data))})
    (transition {:event :llm.idle
                 :cond  (fn [_env data] (= "revise" (from-id data)))
                 :target :report}
      (script {:expr (fn [env data]
                       (let [revised (parse-haiku-set (captured-text env data)
                                       (count (:drafts data)))]
                         [(ops/assign :haikus (if (seq revised) revised (:drafts data)))]))}))
    (transition {:event :error.llm :target :report}
      (script {:expr (fn [_env data] [(ops/assign :haikus (:drafts data))])}))
    (transition {:event :child/safety-stop :target :report}
      (script {:expr (fn [_env data] [(ops/assign :haikus (:drafts data))])}))))

(def poet-chart
  (chart/statechart
    {:initial :role-route
     :name    "haiku-poet"}
    ;; Route the muse poet through the Muse first; everyone else composes
    ;; straight away. Judges never learn which poet took this branch.
    (state {:id :role-route}
      (on-entry {}
        (script {:expr (fn [env data]
                         (raise! env (if (= :muse (:role data)) :role/muse :role/compose))
                         nil)}))
      (transition {:event :role/muse :target :musing})
      (transition {:event :role/compose :target :haiku-1}))
    (muse-step)
    (poet-step 1 :haiku-2)
    (poet-step 2 :haiku-3)
    (poet-step 3 :compose-route)
    ;; After drafting: the critique poet gets an editor + a revise turn; all
    ;; others report their drafts as-is. A poet that drafted nothing skips
    ;; straight to report (which abstains).
    (state {:id :compose-route}
      (on-entry {}
        (script {:expr (fn [env data]
                         (raise! env (if (and (= :critique (:role data))
                                           (seq (:haikus data)))
                                       :role/critique
                                       :role/report))
                         nil)}))
      (transition {:event :role/critique :target :critiquing})
      (transition {:event :role/report :target :report}))
    (critique-step)
    (revise-step)
    ;; Single reply point for the normal path (error/hang paths reply inline and
    ;; jump straight to :reported, so there is never a double reply).
    (state {:id :report}
      (on-entry {}
        (script {:expr (fn [env data]
                         (poet-report! env data)
                         (raise! env :report/done)
                         nil)}))
      (transition {:event :report/done :target :reported}))
    (final {:id :reported})))

;; ---------------------------------------------------------------------------
;; CHILD: judge1 — single LLM call, pick 1-of-3 for ONE poet.
;; ---------------------------------------------------------------------------

(def judge1-chart
  (chart/statechart
    {:initial :working
     :name    "haiku-judge1"}
    (state {:id :working}
      (on-entry {} (send {:event :child/safety-stop :delay child-safety-ms}))
      (h/llm-conversation
        {:id             "judge1"
         :stream?        true
         :model          (fn [_env data] (:model data))
         :resilience     pool-latency
         :system         (fn [_env data]
                           (judge1-system (:idx data) (:persona data)
                             (:poet-idx data)))
         :real-tools     []
         :allowed-events []
         :max-turns      1
         :budget-ms      60000
         :message        (fn [_env data]
                           (str "Here are the three haiku by Poet #" (:poet-idx data) ":\n\n"
                             (format-numbered-haikus (:haikus data))
                             "\n\nNow reply: number on line 1, reason on line 2."))})

      (transition {:event :llm.idle :target :reported}
        (script {:expr
                 (fn [env data]
                   (let [text   (captured-text env data)
                         parsed (parse-pick text 3)]
                     (if parsed
                       (let [[idx0 reason] parsed]
                         (mux/reply env :haiku/judge1-result
                           {:idx       (:idx data)
                            :poet-idx  (:poet-idx data)
                            :haiku-idx idx0
                            :reason    reason}))
                       (mux/reply env :haiku/judge1-result
                         {:idx (:idx data) :poet-idx (:poet-idx data)
                          :abstained? true :raw text}))
                     nil))}))

      (transition {:event :error.llm :target :reported}
        (script {:expr
                 (fn [env data]
                   (mux/reply env :haiku/judge1-result
                     {:idx (:idx data) :poet-idx (:poet-idx data)
                      :abstained? true
                      :error (get-in data [:_event :data])})
                   nil)}))

      (transition {:event :child/safety-stop :target :reported}
        (script {:expr
                 (fn [env data]
                   (mux/reply env :haiku/judge1-result
                     {:idx (:idx data) :poet-idx (:poet-idx data)
                      :abstained? true :hang? true})
                   nil)})))
    (final {:id :reported})))

;; ---------------------------------------------------------------------------
;; CHILD: judge2 — single LLM call, pick 1-of-N finalists.
;; ---------------------------------------------------------------------------

(def judge2-chart
  (chart/statechart
    {:initial :working
     :name    "haiku-judge2"}
    (state {:id :working}
      (on-entry {} (send {:event :child/safety-stop :delay child-safety-ms}))
      (h/llm-conversation
        {:id             "judge2"
         :stream?        true
         :model          (fn [_env data] (:model data))
         :resilience     pool-latency
         :system         (fn [_env data]
                           (judge2-system (:idx data) (:persona data)
                             (count (:finalists data))))
         :real-tools     []
         :allowed-events []
         :max-turns      1
         :budget-ms      60000
         :message        (fn [_env data]
                           (let [n (count (:finalists data))]
                             (str "Here are the " n " finalist haiku:\n\n"
                               (str/join "\n\n"
                                 (map-indexed (fn [i {:keys [poet-idx haiku]}]
                                                (str (inc i) ". (Poet " poet-idx ")\n" haiku))
                                   (:finalists data)))
                               "\n\nNow reply: number on line 1, reason on line 2.")))})

      (transition {:event :llm.idle :target :reported}
        (script {:expr
                 (fn [env data]
                   (let [n      (count (:finalists data))
                         text   (captured-text env data)
                         parsed (parse-pick text n)]
                     (if parsed
                       (let [[idx0 reason] parsed]
                         (mux/reply env :haiku/judge2-result
                           {:idx          (:idx data)
                            :finalist_idx idx0
                            :reason       reason}))
                       (mux/reply env :haiku/judge2-result
                         {:idx (:idx data) :abstained? true :raw text}))
                     nil))}))

      (transition {:event :error.llm :target :reported}
        (script {:expr
                 (fn [env data]
                   (mux/reply env :haiku/judge2-result
                     {:idx (:idx data) :abstained? true
                      :error (get-in data [:_event :data])})
                   nil)}))

      (transition {:event :child/safety-stop :target :reported}
        (script {:expr
                 (fn [env data]
                   (mux/reply env :haiku/judge2-result
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

(defn- r1-support
  "Round-1 support per poet: how many judge picks landed on that poet's haiku.
  Returns a sorted-map poet-idx → count (poets with zero picks included)."
  [haikus judge1-picks]
  (let [tally (frequencies (map :poet_idx (mapcat val judge1-picks)))]
    (into (sorted-map) (for [p (sort (keys haikus))] [p (get tally p 0)]))))

(defn- laureate-entry
  "The winning per-poet champion (`compute-finalists` entry) — The Laureate.
  On a tie, the first leader. nil if there are no finalists."
  [finalists result]
  (let [idx (or (:winner-idx result) (first (:tie result)))]
    (when idx (nth finalists idx nil))))

(defn- overlooked-entry
  "The poet who fared worst — The Overlooked — by fewest round-1 votes (ties
  broken by lowest poet index), excluding the Laureate. Returns that poet's
  best haiku as a `compute-finalists`-shaped entry."
  [haikus judge1-picks finalists laureate-poet-idx]
  (let [worst (->> (r1-support haikus judge1-picks)
                (remove (fn [[p _]] (= p laureate-poet-idx)))
                (sort-by (fn [[p n]] [n p]))
                ffirst)]
    (when worst
      (or (first (filter #(= worst (:poet-idx %)) finalists))
        {:poet-idx worst :haiku-idx 0 :haiku (get-in haikus [worst 0]) :votes 0}))))

(defn- verdict-markdown
  "Deterministic, model-independent verdict block prepended to the summary so
  the report ALWAYS states exactly which poet earned The Laureate (the winner)
  and which was The Overlooked (fewest votes), each with its haiku, plus a
  deterministic note naming the two secretly-helped poets (the gpt-5.5 Muse and
  the gpt-5.5 Critique), independent of what the host model writes."
  [data]
  (let [{:keys [theme haikus judge1-picks finalists judge2-votes result
                muse-poet critique-poet]} data
        laureate (laureate-entry finalists result)
        over     (overlooked-entry haikus judge1-picks finalists (:poet-idx laureate))
        fence    (fn [h] (str "```\n" (str/trim (str h)) "\n```"))
        won?     (fn [idx] (and (some? idx) (= idx (:poet-idx laureate))))]
    (str "# Haiku Tournament — " theme "\n\n"
      "**Field:** " (count haikus) " poets · " (count judge2-votes) " judges"
      (when (:tie result) "  ·  _round-2 tie_") "\n\n"
      "## 🏆 The Laureate — Poet " (inc (:poet-idx laureate))
      "  (" (:votes laureate 0) " round-1 votes)\n\n"
      (fence (:haiku laureate)) "\n\n"
      "## 🥀 The Overlooked — Poet " (inc (:poet-idx over))
      "  (" (get (r1-support haikus judge1-picks) (:poet-idx over) 0)
      " round-1 votes — the least loved)\n\n"
      (fence (:haiku over)) "\n\n"
      ;; Deterministic record of the hidden experiment — the two secretly-helped
      ;; poets are named here regardless of what the host writes.
      "## 🎭 The Muse — secretly given to Poet " (inc muse-poet)
      (when (won? muse-poet) " — who went on to win")
      " (gpt-5.5 inspiration before composing, hidden from the judges)\n\n"
      "## ✍️ The Critique — secretly given to Poet " (inc critique-poet)
      (when (won? critique-poet) " — who went on to win")
      " (gpt-5.5 editor feedback, then the poet revised all three, hidden from the judges)\n\n"
      "---\n\n")))

;; ---------------------------------------------------------------------------
;; Child chart registry keys
;; ---------------------------------------------------------------------------

(def ^:private poet-chart-id   ::poet)
(def ^:private judge1-chart-id ::judge1)
(def ^:private judge2-chart-id ::judge2)

(defn- register-child-charts!
  "Register the three child charts in env's statechart registry so the
  underlying statechart processor can resolve them by `:src`."
  [env]
  (let [reg (::sc/statechart-registry env)]
    (sp/register-statechart! reg poet-chart-id   poet-chart)
    (sp/register-statechart! reg judge1-chart-id judge1-chart)
    (sp/register-statechart! reg judge2-chart-id judge2-chart)))

;; ---------------------------------------------------------------------------
;; PARENT — planner uses plain text, then multiplex of N poets, then M×N
;; judge1s, then M judge2s, then host writes the summary.
;; ---------------------------------------------------------------------------

(defn- strip-label
  "Strip instruction-echo prefixes that small models prepend to each line, e.g.
  `Line 1: the word START` → `START`, `Line 2: 3` → `3`. Tolerant of `:`/`.`/`-`
  separators. Belt-and-suspenders so a weak instruction-follower (gemma3:1b)
  still parses; strong models emit the bare value and are unaffected."
  [s]
  (-> (or s "")
    (str/replace #"(?i)^\s*line\s*\d+\s*[:.)\-]\s*" "")
    (str/replace #"(?i)^\s*the\s+word\s+" "")
    str/trim))

(defn- parse-planner
  "Parse planner output. Returns one of:
     {:verb :start :poets P :audience M :theme T}
     {:verb :abort :reason R}
     {:verb :error :raw text}"
  [text]
  (if-let [text (some-> text str/trim not-empty)]
    (let [lines    (->> (str/split-lines text)
                        (map strip-label)
                        (remove str/blank?)
                        vec)
          v0       (str/upper-case (or (first lines) ""))
          abort?   (str/starts-with? v0 "ABORT")
          ;; Data-driven extraction so a tiny model that picks the WRONG verb
          ;; keyword (gemma3:1b emits "ABORT" with full counts+theme filled in)
          ;; still runs: the two in-range integers (in order) are the poet and
          ;; judge counts, and the first remaining non-numeric line is the theme.
          body     (if (str/starts-with? v0 "START") (rest lines)
                       (if abort? (rest lines) lines))
          ints     (->> body (keep #(some-> (re-find #"\d+" %) Long/parseLong))
                        (filter #(<= 3 % 30)) vec)
          [poets audience] ints
          theme    (->> body (remove #(re-matches #"\s*\d+\s*" %))
                        (map str/trim) (remove str/blank?) first)]
      (cond
        ;; Enough structured data → START regardless of the verb keyword.
        (and poets audience theme)
        {:verb :start :poets poets :audience audience :theme theme}

        abort?
        {:verb :abort :reason (or (->> (rest lines) (str/join " ") str/trim not-empty)
                                "unspecified")}

        :else
        {:verb :error :raw text}))
    {:verb :error :raw ""}))

(def ^{:multi-session? true :interactive? true} agent
  (chart/statechart
    {:initial :run
     :name    "haiku-tournament-dynamic"}
    (state {:id :run :initial :planning}

      (on-entry {}
        (script {:expr (fn [env _data]
                         (register-child-charts! env)
                         nil)}))

      ;; ---------- PHASE 1: planner (plain text START/ABORT) ----------
      (state {:id :planning}
        (h/llm-conversation
          {:id             "planner"
           :stream?        true
           :system         planner-prompt
           :real-tools     []
           :allowed-events []
           :max-turns      1
           :budget-ms      60000
           :message        (fn [_env data]
                             (str "USER INPUT:\n" (pr-str (:user-input data ""))))})

        (transition {:event :llm.idle
                     :cond  (fn [_env data] (= "planner" (from-id data)))
                     :target :route-planner}
          (script {:expr
                   (fn [env data]
                     (let [p (parse-planner (captured-text env data))]
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
                     (let [{:keys [poets audience theme]} (:plan data)
                           ;; Secretly bless ONE random poet with the Muse and a
                           ;; DIFFERENT random poet with the Critique (editor +
                           ;; revise). Judges never learn who; only the host is
                           ;; told. poets >= 3 (planner-enforced), so the two
                           ;; roles always land on distinct poets.
                           muse     (rand-int poets)
                           critique (let [c (rand-int (dec poets))]
                                      (if (>= c muse) (inc c) c))]
                       [(ops/assign :poet-count poets)
                        (ops/assign :audience-count audience)
                        (ops/assign :theme theme)
                        (ops/assign :muse-poet muse)
                        (ops/assign :critique-poet critique)]))}))
        (transition {:event :plan/abort :target :aborted}
          (script {:expr
                   (fn [_env data]
                     [(ops/assign :abort-reason
                        (or (:reason (:plan data))
                          (str "planner output not parseable: "
                            (pr-str (:raw (:plan data))))))])})))

      ;; ---------- PHASE 2: composing — multiplex of N poets ----------
      (state {:id :composing}
        (multiplex
          {:id             :poets
           ;; Children only mux/reply UP to the parent; they never read events
           ;; forwarded DOWN. Leaving autoforward on (the library default) makes
           ;; the parent re-broadcast every result into all N children as silent
           ;; no-ops — N×N event-processed churn. Off = linear fan-in.
           ;; (This is unrelated to parallelism — children still run on their
           ;; own worker threads; ollama's OLLAMA_NUM_PARALLEL gates concurrency.)
           :autoforward    false
           mo/child-type   ::sc/chart
           mo/count        (fn [_env data] (:poet-count data))
           mo/child-params (fn [_env data idx]
                             {:src    poet-chart-id
                              :params {:idx   idx
                                       :theme (:theme data)
                                       :model (rand-pool-model)
                                       ;; One poet gets :muse, a different one
                                       ;; gets :critique; the rest get nil and
                                       ;; compose unaided.
                                       :role  (cond
                                                (= idx (:muse-poet data))     :muse
                                                (= idx (:critique-poet data)) :critique
                                                :else                         nil)}})})

        ;; Accumulate per-poet results as they reply.
        (transition {:event :haiku/poet-result :type :internal}
          (script {:expr
                   (fn [_env data]
                     (let [{:keys [idx haikus abstained? muse-text critique-text drafts]}
                           (get-in data [:_event :data])
                           h' (cond-> (or (:haikus data) {})
                                (and (not abstained?) (seq haikus))
                                (assoc idx haikus))]
                       ;; Stash the secret-role artifacts (host-only) as they
                       ;; arrive: what the Muse whispered, and the editor's
                       ;; critique + the poet's pre-revision drafts.
                       (cond-> [(ops/assign :haikus h')]
                         muse-text     (conj (ops/assign :muse-text muse-text))
                         critique-text (conj (ops/assign :critique-text critique-text))
                         (seq drafts)  (conj (ops/assign :critique-drafts drafts)))))}))

        ;; Library-emitted cohort done — every poet has reported.
        (transition {:event :done.invoke.poets :target :judging-r1}))

      ;; ---------- PHASE 3: judging round 1 — multiplex of M × N judges ----------
      ;; Each child judges ONE poet. The multiplex's :idx is a flat 0..(M*N - 1);
      ;; we derive judge-idx and poet-idx from it using the current haiku keys.
      (state {:id :judging-r1}
        (multiplex
          {:id             :judges-r1
           :autoforward    false                            ;; see :poets — children reply-only
           mo/child-type   ::sc/chart
           mo/count        (fn [_env data]
                             (* (long (:audience-count data))
                                (count (:haikus data))))
           mo/child-params (fn [_env data idx]
                             (let [poet-keys (vec (sort (keys (:haikus data))))
                                   n         (count poet-keys)
                                   judge-i   (long (quot idx n))
                                   poet-i    (nth poet-keys (mod idx n))]
                               {:src    judge1-chart-id
                                :params {:idx      judge-i
                                         :persona  (persona-for judge-i)
                                         :poet-idx poet-i
                                         :model    (rand-pool-model)
                                         :haikus   (get-in data [:haikus poet-i])}}))})

        (transition {:event :haiku/judge1-result :type :internal}
          (script {:expr
                   (fn [_env data]
                     (let [{:keys [idx poet-idx haiku-idx reason abstained?]}
                           (get-in data [:_event :data])
                           jp  (or (:judge1-picks data) {})
                           jp' (if abstained?
                                 jp
                                 (update jp idx (fnil conj [])
                                   {:poet_idx  poet-idx
                                    :haiku_idx haiku-idx
                                    :reason    reason}))]
                       [(ops/assign :judge1-picks jp')]))}))

        (transition {:event :done.invoke.judges-r1 :target :tallying-r1}))

      ;; ---------- PHASE 4: tally R1 → finalists ----------
      (state {:id :tallying-r1}
        (on-entry {}
          (script {:expr
                   (fn [env data]
                     (let [f (compute-finalists (:haikus data)
                               (:judge1-picks data))]
                       (raise! env :haiku/tally-r1-done)
                       [(ops/assign :finalists f)]))}))
        (transition {:event :haiku/tally-r1-done :target :judging-r2}))

      ;; ---------- PHASE 5: judging round 2 — multiplex of M judges ----------
      (state {:id :judging-r2}
        (multiplex
          {:id             :judges-r2
           :autoforward    false                            ;; see :poets — children reply-only
           mo/child-type   ::sc/chart
           mo/count        (fn [_env data] (:audience-count data))
           mo/child-params (fn [_env data idx]
                             {:src    judge2-chart-id
                              :params {:idx       idx
                                       :persona   (persona-for idx)
                                       :model     (rand-pool-model)
                                       :finalists (:finalists data)}})})

        (transition {:event :haiku/judge2-result :type :internal}
          (script {:expr
                   (fn [_env data]
                     (let [{:keys [idx finalist_idx reason abstained?]}
                           (get-in data [:_event :data])
                           v' (cond-> (or (:judge2-votes data) {})
                                (not abstained?)
                                (assoc idx {:finalist_idx finalist_idx
                                            :reason       reason}))]
                       [(ops/assign :judge2-votes v')]))}))

        (transition {:event :done.invoke.judges-r2 :target :tallying-r2}))

      ;; ---------- PHASE 6: tally R2 → winner ----------
      (state {:id :tallying-r2}
        (on-entry {}
          (script {:expr
                   (fn [env data]
                     (let [result (compute-winner (:judge2-votes data))]
                       (raise! env :haiku/tally-r2-done)
                       [(ops/assign :result result)]))}))
        (transition {:event :haiku/tally-r2-done :target :summarizing}))

      ;; ---------- PHASE 7: host LLM writes tournament-summary.md ----------
      (state {:id :summarizing}
        (h/llm-conversation
          {:id             "host"
           :stream?        true
           :model          host-model
           :system         host-system
           :real-tools     []
           :allowed-events []
           :max-turns      2
           :budget-ms      120000
           ;; Output-token ceiling for the host's prose: cap each turn at 6k
           ;; tokens and, on truncation (`stop-reason :max_tokens`), rerun the
           ;; same turn twice instead of stitching an unbounded continuation.
           ;; The slight per-rerun temperature bump lets a deterministic host
           ;; model break out of a re-truncating loop. Retries spent ⇒ accept
           ;; the truncated turn (`:on-exhausted :truncate`, the default).
           :resilience     {:overrun {:max-output-tokens 6000
                                      :max-retries       2
                                      :temperature-bump  0.1
                                      :temperature-max   1.0}}
           :message        (fn [_env data] (host-user-message data))})
        (transition {:event :llm.idle
                     :cond  (fn [_env data] (= "host" (from-id data)))
                     :target :finished}
          ;; First write the host LLM's prose, then prepend the deterministic
          ;; verdict so the report ALWAYS names The Muse and The Critique
          ;; exactly — independent of how capable the host model is.
          (h/capture-llm-output {:as "tournament-summary.md"})
          (script {:expr
                   (fn [env data]
                     (let [sdir  (:escapement/session-dir env)
                           path  (str sdir "/artifacts/tournament-summary.md")
                           prose (try (str/trim (slurp path)) (catch Throwable _ ""))
                           md    (str (verdict-markdown data)
                                   "## Master of Ceremonies' reading\n\n"
                                   (if (str/blank? prose)
                                     "_(the host model produced no prose)_" prose)
                                   "\n")]
                       (spit path md))
                     nil)}))
        (transition {:event :error.llm :target :finished}
          (script {:expr (fn [_env data]
                           [(ops/assign :host-error
                              (get-in data [:_event :data]))])})))

      (final {:id :aborted})
      (final {:id :finished}))))
