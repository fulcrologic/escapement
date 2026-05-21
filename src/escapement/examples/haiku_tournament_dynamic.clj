(ns escapement.examples.haiku-tournament-dynamic
  "TRUE dynamic-N haiku tournament — see ../../../n-subagents.md
  §CORRECTION (\"What 'dynamic N' actually means\") and §Fix-list.

  Contrast with `escapement.examples.haiku-tournament`, which preallocates
  `MAX-POETS=5` / `MAX-AUDIENCE=10` parallel regions and gates them with
  entry-time skip transitions. That is static capacity, not dynamic spawn.

  This chart contains NO `MAX-*`. The parent has one `:composing` state, one
  `:judging-r1` state, and one `:judging-r2` state. Each `on-entry` spawns
  `(:poet-count data)` or `(:audience-count data)` child SESSIONS via
  `escapement.engine.spawn/spawn-child!`. Each child runs a standalone
  top-level chart (`poet-chart` / `judge1-chart` / `judge2-chart`) in its
  own sid. Children `send!` their reply event to `:reply-to` (= parent sid).
  The parent decrements a `:pending` counter on each reply and transitions
  out when the counter hits zero.

  Requires the runner be invoked with `:multi-session? true`, which makes
  the pump loop drain every session's queue (parent + every child) on each
  iteration. The chart var carries `^:multi-session?` metadata so the CLI
  picks this up automatically.

  Run:
    ZAI_API_KEY=... bb -m escapement.cli run \\
      escapement.examples.haiku-tournament-dynamic/agent \\
      --no-tui --model glm-4.6 \\
      --param 'user-input=\"Run a tournament with 4 poets and 6 judges. \\
                            Theme: lanterns over the river.\"'"
  (:require
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

(defn- send-to-parent!
  "Post a chart event to the child's `:reply-to` sid (= parent sid). Used
  by child charts to report their result before reaching :final."
  [env event-kw data]
  (let [queue   (get env ::sc/event-queue)
        my-sid  (some-> env ::sc/vwmem deref ::sc/session-id)
        ;; The child's :reply-to lives in its own data model. Pull it
        ;; off the data model via a dummy-data lookup pattern:
        ;; scripts get `data` as 2nd arg, so callers pass it through.
        ]
    (when (and queue my-sid)
      (sp/send! queue env
        {:target            (:reply-to data)
         :source-session-id my-sid
         :event             event-kw
         :data              data}))))

;; Hmm — the helper above closes over a stale `data`. Pass everything in
;; explicitly so each script's `data` is local.
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
  "Post a self-targeted event onto this session's queue."
  ([env event-kw] (raise! env event-kw {}))
  ([env event-kw payload]
   (let [my-sid (some-> env ::sc/vwmem deref ::sc/session-id)]
     (send-to! env my-sid event-kw payload))))

;; ---------------------------------------------------------------------------
;; Prompts
;; ---------------------------------------------------------------------------
(def planner-prompt
  (str
   "You are the dispatcher for a haiku tournament. Read the user input.\n"
   "If the user CLEARLY specifies (a) a number of poets in [3,30], "
   "(b) a number of judges in [3,30], AND (c) a topic/theme, call "
   "`event__start_tournament` exactly once with "
   "`{\"poets\":<int>,\"audience\":<int>,\"theme\":\"<string>\"}` and END.\n"
   "If ANY of those three is missing, ambiguous, or out of range, call "
   "`event__abort` exactly once with `{\"reason\":\"<one short sentence>\"}` "
   "and END. Do not call both."))

(defn- poet-system [idx]
  (str "You are Poet #" idx " in a haiku tournament. Compose THREE original "
       "haiku on the given theme. Aim for genuine quality — vivid imagery, "
       "careful sound, an honest moment. Return them by calling "
       "`event__poet_done` EXACTLY ONCE with "
       "`{\"idx\":" idx ",\"haikus\":[\"line1\\nline2\\nline3\","
       "\"line1\\nline2\\nline3\",\"line1\\nline2\\nline3\"]}`. Three lines "
       "per haiku, separated by newlines. End your turn after that call."))

(defn- judge1-system [idx persona]
  (str "You are Judge #" idx ". Persona: " persona "\n"
       "You will see haiku from several poets. For EACH poet, pick exactly "
       "one favorite and explain in ONE sentence (true to your persona) why "
       "it stood out over that poet's other entries. Return your verdict by "
       "calling `event__judge1` EXACTLY ONCE with "
       "`{\"idx\":" idx ",\"picks\":[{\"poet_idx\":<int>,"
       "\"haiku_idx\":<int>,\"reason\":\"<one sentence>\"}, ...]}` — one "
       "entry per poet. End your turn after that call."))

(defn- judge2-system [idx persona]
  (str "You are Judge #" idx " in the FINAL round. Persona: " persona "\n"
       "Pick the SINGLE best haiku among the finalists and explain in ONE "
       "sentence (true to your persona) why. Return your vote by calling "
       "`event__judge2` EXACTLY ONCE with "
       "`{\"idx\":" idx ",\"finalist_idx\":<int>,\"reason\":\"<one sentence>\"}`. "
       "End your turn after that call."))

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
      (clojure.string/join "\n"
        (for [[j ps] (sort picks)
              :let [persona (persona-for j)]]
          (str "Judge " j " (" persona "):\n"
            (clojure.string/join "\n"
              (for [{:keys [poet_idx haiku_idx reason]} ps]
                (str "  - poet " poet_idx " haiku " haiku_idx " — " reason)))))) "\n\n"
      "## Finalists (poet → finalist index)\n"
      (format-finalists finals) "\n\n"
      "## Round-2 votes\n"
      (clojure.string/join "\n"
        (for [[j {:keys [finalist_idx reason]}] (sort votes)
              :let [persona (persona-for j)]]
          (str "Judge " j " (" persona ") → finalist " finalist_idx ": " reason))) "\n\n"
      "## Tally\n"
      (pr-str result) "\n\n"
      "Now write `tournament-summary.md`.")))

(defn- format-haikus [haikus]
  (clojure.string/join "\n\n"
    (for [[p hs] (sort haikus)]
      (str "POET " p ":\n"
        (clojure.string/join "\n"
          (map-indexed (fn [hi h]
                         (str "  [" hi "] "
                           (clojure.string/replace h "\n" " / ")))
            hs))))))

(defn- format-finalists [finalists]
  (clojure.string/join "\n\n"
    (map-indexed
      (fn [fi {:keys [poet-idx haiku]}]
        (str "[" fi "] (from Poet " poet-idx ")\n"
          (clojure.string/join "\n"
            (map #(str "    " %) (clojure.string/split-lines haiku)))))
      finalists)))

;; ---------------------------------------------------------------------------
;; CHILD CHARTS — each is a standalone top-level chart, instantiated N times
;; as independent sessions by `spawn-child!`. There is NO MAX bound here.
;; ---------------------------------------------------------------------------

(def poet-chart
  (chart/statechart
    {:initial :working}
    (state {:id :working}
      (on-entry {} (send {:event :child/safety-stop :delay child-safety-ms}))
      (h/llm-conversation
        {:id "poet"
         :params-fn
         (fn [_env data]
           {:system     (poet-system (:idx data))
            :real-tools []
            :allowed-events
            [{:event       :poet-done
              :data-schema [:map [:idx :int]
                            [:haikus [:vector :string]]]}]
            :max-turns                    3
            :max-conversation-duration-ms 180000
            :initial-user-message
            (str "Compose 3 haiku on the theme: "
              (pr-str (:theme data))
              ". Then call event__poet_done.")})})
      (transition {:event :poet-done :target :reported}
        (script {:expr
                 (fn [env data]
                   (let [payload {:idx    (:idx data)
                                  :haikus (get-in data
                                            [:_event :data :haikus])}]
                     (send-to! env (:reply-to data)
                       :poet-result payload)
                     nil))}))
      ;; LLM finished its turn loop without ever calling event__poet_done
      ;; (plain prose, refusal, or :max-turns exhausted). The worker has
      ;; exited; no point waiting on the safety timer. Listed AFTER
      ;; :poet-done so the success event wins when both fire.
      (transition {:event :llm.idle :target :reported}
        (script {:expr
                 (fn [env data]
                   (send-to! env (:reply-to data)
                     :poet-result
                     {:idx (:idx data) :abstained? true})
                   nil)}))
      (transition {:event :error.llm :target :reported}
        (script {:expr
                 (fn [env data]
                   (send-to! env (:reply-to data)
                     :poet-result
                     {:idx        (:idx data)
                      :abstained? true
                      :error      (get-in data [:_event :data])})
                   nil)}))
      ;; True-hang backstop: only fires if the LLM call itself never returns.
      (transition {:event :child/safety-stop :target :reported}
        (script {:expr
                 (fn [env data]
                   (send-to! env (:reply-to data)
                     :poet-result
                     {:idx (:idx data) :abstained? true :hang? true})
                   nil)})))
    (final {:id :reported})))

(def judge1-chart
  (chart/statechart
    {:initial :working}
    (state {:id :working}
      (on-entry {} (send {:event :child/safety-stop :delay child-safety-ms}))
      (h/llm-conversation
        {:id "judge1"
         :params-fn
         (fn [_env data]
           {:system     (judge1-system (:idx data) (:persona data))
            :real-tools []
            ;; Nested map keys in :picks stay as strings (cheshire only
            ;; keywordizes top-level tool :input keys), so the inner
            ;; element schema is loose; we keywordize in the script.
            :allowed-events
            [{:event       :judge1
              :data-schema [:map [:idx :int]
                            [:picks [:vector :any]]]}]
            :max-turns                    3
            :max-conversation-duration-ms 240000
            :initial-user-message
            (str "Here are the haiku to judge:\n\n"
              (format-haikus (:haikus data))
              "\n\nNow call event__judge1 with one pick per poet.")})})
      (transition {:event :judge1 :target :reported}
        (script {:expr
                 (fn [env data]
                   (let [raw   (get-in data [:_event :data :picks])
                         picks (mapv
                                 #(if (map? %)
                                    (reduce-kv (fn [m k v]
                                                 (assoc m (keyword k) v))
                                      {} %)
                                    %)
                                 raw)]
                     (send-to! env (:reply-to data)
                       :judge1-result
                       {:idx (:idx data) :picks picks})
                     nil))}))
      (transition {:event :llm.idle :target :reported}
        (script {:expr
                 (fn [env data]
                   (send-to! env (:reply-to data)
                     :judge1-result
                     {:idx (:idx data) :picks [] :abstained? true})
                   nil)}))
      (transition {:event :error.llm :target :reported}
        (script {:expr
                 (fn [env data]
                   (send-to! env (:reply-to data)
                     :judge1-result
                     {:idx        (:idx data) :picks []
                      :abstained? true
                      :error      (get-in data [:_event :data])})
                   nil)}))
      (transition {:event :child/safety-stop :target :reported}
        (script {:expr
                 (fn [env data]
                   (send-to! env (:reply-to data)
                     :judge1-result
                     {:idx (:idx data) :picks [] :abstained? true :hang? true})
                   nil)})))
    (final {:id :reported})))

(def judge2-chart
  (chart/statechart
    {:initial :working}
    (state {:id :working}
      (on-entry {} (send {:event :child/safety-stop :delay child-safety-ms}))
      (h/llm-conversation
        {:id "judge2"
         :params-fn
         (fn [_env data]
           {:system     (judge2-system (:idx data) (:persona data))
            :real-tools []
            :allowed-events
            [{:event       :judge2
              :data-schema [:map [:idx :int]
                            [:finalist_idx :int]
                            [:reason :string]]}]
            :max-turns                    3
            :max-conversation-duration-ms 180000
            :initial-user-message
            (str "The finalists are:\n\n"
              (format-finalists (:finalists data))
              "\n\nNow call event__judge2 with your single favorite.")})})
      (transition {:event :judge2 :target :reported}
        (script {:expr
                 (fn [env data]
                   (let [ev (get-in data [:_event :data])]
                     (send-to! env (:reply-to data)
                       :judge2-result
                       {:idx          (:idx data)
                        :finalist_idx (:finalist_idx ev)
                        :reason       (:reason ev)})
                     nil))}))
      (transition {:event :llm.idle :target :reported}
        (script {:expr
                 (fn [env data]
                   (send-to! env (:reply-to data)
                     :judge2-result
                     {:idx (:idx data) :abstained? true})
                   nil)}))
      (transition {:event :error.llm :target :reported}
        (script {:expr
                 (fn [env data]
                   (send-to! env (:reply-to data)
                     :judge2-result
                     {:idx        (:idx data)
                      :abstained? true
                      :error      (get-in data [:_event :data])})
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
;; Pure tally fns (same as the bounded version — these were never the problem).
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
;; PARENT CHART — no MAX, no per-region :check states. Just three dispatch
;; states, each spawning N children in on-entry and counting their replies.
;; ---------------------------------------------------------------------------

(defn- spawn-many!
  "Spawn `n` children of `chart`, each with `(input-fn i)` as initial data
   merged with `{:reply-to parent-sid}`. Returns vector of child sids."
  [env n chart chart-id input-fn]
  (let [parent (spawn/parent-sid env)]
    (vec
      (for [i (range n)]
        (spawn/spawn-child! env
          {:chart    chart
           :chart-id chart-id
           :input    (assoc (input-fn i) :reply-to parent)})))))

(def ^{:multi-session? true} agent
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :planning}

      ;; ---------- PHASE 1: planner ----------
      (state {:id :planning}
        (h/llm-conversation
          {:id "planner"
           :params-fn
           (fn [_env data]
             {:system     planner-prompt
              :real-tools []
              :allowed-events
              [{:event       :start-tournament
                :data-schema [:map [:poets :int]
                              [:audience :int]
                              [:theme :string]]}
               {:event       :abort
                :data-schema [:map [:reason :string]]}]
              :max-turns                    2
              :max-conversation-duration-ms 60000
              :initial-user-message
              (str "USER INPUT:\n" (pr-str (:user-input data "")))})})

        (transition {:event :start-tournament :target :composing}
          (script {:expr
                   (fn [_env data]
                     (let [d (get-in data [:_event :data])]
                       [(ops/assign :poet-count (long (:poets d)))
                        (ops/assign :audience-count (long (:audience d)))
                        (ops/assign :theme (:theme d))]))}))

        (transition {:event :abort :target :aborted}
          (script {:expr
                   (fn [_env data]
                     [(ops/assign :abort-reason
                        (get-in data [:_event :data :reason]))])})))

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
                                (not abstained?) (assoc idx haikus))
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
                     (let [m (:audience-count data)
                           hs (:haikus data)]
                       (spawn-many! env m
                         judge1-chart ::judge1
                         (fn [i] {:idx     i
                                  :persona (persona-for i)
                                  :haikus  hs}))
                       [(ops/assign :pending m)
                        (ops/assign :judge1-picks {})]))}))
        (transition {:event :judge1-result :type :internal}
          (script {:expr
                   (fn [env data]
                     (let [{:keys [idx picks abstained?]}
                           (get-in data [:_event :data])
                           jp' (cond-> (or (:judge1-picks data) {})
                                 (not abstained?) (assoc idx picks))
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
              :max-turns                    2
              :max-conversation-duration-ms 120000
              :initial-user-message         (host-user-message data)})})
        ;; Host writes plain markdown then ends_turn → :llm.idle fires.
        ;; Filter on :from so any stray idle from a finishing child
        ;; can't end the summary state early.
        (transition {:event :llm.idle
                     :cond  (fn [_env data]
                              (= "host" (get-in data [:_event :data :from])))
                     :target :finished}
          (h/capture-llm-output {:as "tournament-summary.md"}))
        ;; Host LLM unreachable / timed-out / errored — don't park in
        ;; :summarizing waiting for a turn that will never end. Terminate
        ;; cleanly so the runner reaches :finished and the raw data in
        ;; the transcript remains the source of truth.
        (transition {:event :error.llm :target :finished}
          (script {:expr (fn [_env data]
                           [(ops/assign :host-error
                              (get-in data [:_event :data]))])})))

      (final {:id :aborted})
      (final {:id :finished}))))
