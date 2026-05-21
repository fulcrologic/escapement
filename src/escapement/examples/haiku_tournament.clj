(ns escapement.examples.haiku-tournament
  "LLM-driven haiku tournament — a concrete N-subagents demo
  (see ../../../n-subagents.md).

  ONE chart, one session, real LLM calls throughout. Phases:

    1. :planning  — a single \"main\" LLM reads a free-text user input.
                    It must either call `event__start_tournament` with
                    {poets ∈ [3,5], audience ∈ [3,10], theme} or
                    `event__abort` with a reason.
    2. :composing — N poet sub-LLMs each compose 3 haikus on the theme
                    IN PARALLEL.
    3. :judging-r1 — M audience sub-LLMs (each with a small persona) IN
                    PARALLEL judge round one: every judge picks ONE
                    favorite haiku per poet (so N picks each, with a
                    short reason).
    4. :tallying-r1 — pure script: for each poet, the haiku with the most
                    judge votes becomes that poet's finalist. The N
                    finalists move to round two.
    5. :judging-r2 — M audience LLMs vote again IN PARALLEL: one pick
                    among the N finalists, with a short reason.
    6. :tallying-r2 — pure script: plurality winner, or :tie.

  Dynamicism: N and M are decided by the planner LLM at runtime. The
  chart pre-allocates MAX-POETS=5 poet slots and MAX-AUDIENCE=10 judge
  slots; slots above the chosen N/M short-circuit straight to their
  region-final on entry, so the parallel-join still completes.

  Run from the REPL (needs ZAI_API_KEY set):

    bb -e \"(System/setProperty \\\"ZAI_API_KEY\\\" \\\"...\\\")\"
    bb -m escapement.cli run escapement.examples.haiku-tournament/agent \\
      --no-tui --model glm-4.6 \\
      --initial-data '{:user-input \"Run a tournament with 3 poets and \\
                                    4 judges. Theme: solitude in autumn.\"}'

  Or programmatically via `escapement.engine.testing`:

    (require '[escapement.examples.haiku-tournament :as h])
    (require '[escapement.engine.testing :as t])
    (-> (t/new-testing-env {:statechart h/agent})
        (t/start! {:user-input \"...\"})
        (t/drain! 10000)
        ((juxt t/configuration t/data))
        clojure.pprint/pprint)"
  (:require
   [clojure.string :as str]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.data-model.operations :as ops]
   [com.fulcrologic.statecharts.elements
    :refer [final on-entry parallel script state transition]]
   [com.fulcrologic.statecharts.protocols :as sp]
   [escapement.chart.helpers :as h]))

;; ---------------------------------------------------------------------------
;; Capacity. The chart is pre-allocated up to these maxes; the planner LLM
;; chooses N/M at runtime within the user-allowed bounds (3..5 poets,
;; 3..10 audience).
;; ---------------------------------------------------------------------------
(def MAX-POETS 5)
(def MAX-AUDIENCE 10)

(def judge-personas
  ;; 10 personas — one per max audience slot.
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

;; ---------------------------------------------------------------------------
;; Self-event helper (same pattern as iterate.clj / n-subagents-demo).
;; ---------------------------------------------------------------------------
(defn- raise!
  ([env event] (raise! env event {}))
  ([env event data]
   (let [queue (get env ::sc/event-queue)
         sid   (some-> env ::sc/vwmem deref ::sc/session-id)]
     (when (and queue sid)
       (sp/send! queue env {:target            sid
                            :source-session-id sid
                            :event             event
                            :data              data})))))

(defn- mine? [i]
  (fn [_env data] (= i (get-in data [:_event :data :idx]))))

;; ---------------------------------------------------------------------------
;; Prompts
;; ---------------------------------------------------------------------------
(def planner-prompt
  (str
   "You are the dispatcher for a haiku tournament. Read the user input.\n"
   "If the user CLEARLY specifies (a) a number of poets in [3,5], "
   "(b) a number of judges in [3,10], AND (c) a topic/theme, call "
   "`event__start_tournament` exactly once with "
   "`{\"poets\":<int>,\"audience\":<int>,\"theme\":\"<string>\"}` and END.\n"
   "If ANY of those three is missing, ambiguous, or out of range, call "
   "`event__abort` exactly once with "
   "`{\"reason\":\"<one short sentence>\"}` and END. Do not call both."))

(defn- poet-system-prompt [i]
  (str "You are Poet #" i " in a haiku tournament. You will compose THREE "
       "original haiku on the given theme. Strive for genuine quality — "
       "vivid imagery, careful sound, an honest moment. Return them by "
       "calling `event__poet_done` EXACTLY ONCE with "
       "`{\"idx\":" i ",\"haikus\":[\"line1\\nline2\\nline3\","
       "\"line1\\nline2\\nline3\",\"line1\\nline2\\nline3\"]}`. "
       "Each haiku must be three lines separated by newlines. End your turn "
       "after that call."))

(defn- poet-initial-message [theme]
  (str "Compose 3 haiku on the theme: " (pr-str theme)
       ". Then call event__poet_done with idx and the 3 haiku."))

(defn- format-haikus-for-judging [haikus]
  ;; haikus: {poet-idx ["h1" "h2" "h3"]}
  (str/join "\n\n"
    (for [[p hs] (sort haikus)]
      (str "POET " p ":\n"
        (str/join "\n"
          (map-indexed (fn [hi h] (str "  [" hi "] " (str/replace h "\n" " / ")))
            hs))))))

(defn- judge1-system-prompt [i persona]
  (str "You are Judge #" i ". Persona: " persona "\n"
       "You will see haiku from several poets. For EACH poet, pick exactly "
       "one favorite haiku and explain in ONE sentence (true to your "
       "persona) why it stood out over that poet's other entries. Return "
       "your verdict by calling `event__judge1` EXACTLY ONCE with "
       "`{\"idx\":" i ",\"picks\":[{\"poet_idx\":<int>,"
       "\"haiku_idx\":<int>,\"reason\":\"<one sentence>\"}, ...]}` — one "
       "entry per poet. End your turn after that call."))

(defn- judge1-initial-message [haikus]
  (str "Here are the haiku to judge:\n\n" (format-haikus-for-judging haikus)
       "\n\nNow call event__judge1 with one pick per poet."))

(defn- format-finalists [finalists]
  ;; finalists: vector of {:poet-idx :haiku-idx :haiku :votes}
  (str/join "\n\n"
    (map-indexed
      (fn [fi {:keys [poet-idx haiku]}]
        (str "[" fi "] (from Poet " poet-idx ")\n"
          (str/join "\n" (map #(str "    " %) (str/split-lines haiku)))))
      finalists)))

(defn- judge2-system-prompt [i persona]
  (str "You are Judge #" i " in the FINAL round. Persona: " persona "\n"
       "Pick the SINGLE best haiku among the finalists and explain in ONE "
       "sentence (true to your persona) why. Return your vote by calling "
       "`event__judge2` EXACTLY ONCE with "
       "`{\"idx\":" i ",\"finalist_idx\":<int>,\"reason\":\"<one sentence>\"}`. "
       "End your turn after that call."))

(defn- judge2-initial-message [finalists]
  (str "The finalists are:\n\n" (format-finalists finalists)
       "\n\nNow call event__judge2 with your single favorite."))

;; ---------------------------------------------------------------------------
;; Poet region (one per slot 0..MAX-POETS-1)
;; ---------------------------------------------------------------------------
(defn- poet-region [i]
  (let [check   (keyword (str "poet-" i "-check"))
        working (keyword (str "poet-" i "-working"))
        done    (keyword (str "poet-" i "-done"))]
    (state {:id (keyword (str "poet-" i)) :initial check}

      (state {:id check}
        (on-entry {}
          (script {:expr
                   (fn [env data]
                     (if (< i (or (:poet-count data) 0))
                       (raise! env (keyword (str "poet-go-" i)))
                       (raise! env (keyword (str "poet-skip-" i))))
                     nil)}))
        (transition {:event (keyword (str "poet-go-" i)) :target working})
        (transition {:event (keyword (str "poet-skip-" i)) :target done}))

      (state {:id working}
        (h/llm-conversation
          {:id (str "poet-" i)
           :params-fn
           (fn [_env data]
             {:system               (poet-system-prompt i)
              :real-tools           []
              :allowed-events
              [{:event       :poet-done
                :description "Submit the 3 haiku."
                :data-schema [:map [:idx :int]
                              [:haikus [:vector :string]]]}]
              :max-turns                    3
              :max-conversation-duration-ms 120000
              :initial-user-message         (poet-initial-message
                                              (:theme data))})})
        (transition {:event  :poet-done
                     :cond   (mine? i)
                     :target done}
          (script {:expr
                   (fn [_env data]
                     (let [hs (get-in data [:_event :data :haikus])]
                       [(ops/assign :haikus
                          (assoc (or (:haikus data) {}) i (vec hs)))]))})))

      (final {:id done}))))

;; ---------------------------------------------------------------------------
;; Judge round 1
;; ---------------------------------------------------------------------------
(defn- judge1-region [i]
  (let [check   (keyword (str "j1-" i "-check"))
        working (keyword (str "j1-" i "-working"))
        done    (keyword (str "j1-" i "-done"))
        persona (nth judge-personas i)]
    (state {:id (keyword (str "j1-" i)) :initial check}
      (state {:id check}
        (on-entry {}
          (script {:expr
                   (fn [env data]
                     (if (< i (or (:audience-count data) 0))
                       (raise! env (keyword (str "j1-go-" i)))
                       (raise! env (keyword (str "j1-skip-" i))))
                     nil)}))
        (transition {:event (keyword (str "j1-go-" i)) :target working})
        (transition {:event (keyword (str "j1-skip-" i)) :target done}))

      (state {:id working}
        (h/llm-conversation
          {:id (str "judge1-" i)
           :params-fn
           (fn [_env data]
             {:system     (judge1-system-prompt i persona)
              :real-tools []
              :allowed-events
              ;; NOTE: cheshire only keywordizes top-level keys on the tool
              ;; :input map; nested maps inside :picks keep string keys, so
              ;; the inner schema stays loose and we keywordize below.
              [{:event       :judge1
                :description "Submit round-1 picks (one per poet)."
                :data-schema [:map [:idx :int]
                              [:picks [:vector :any]]]}]
              :max-turns                    3
              :max-conversation-duration-ms 180000
              :initial-user-message         (judge1-initial-message
                                              (:haikus data))})})
        (transition {:event  :judge1
                     :cond   (mine? i)
                     :target done}
          (script {:expr
                   (fn [_env data]
                     (let [raw   (get-in data [:_event :data :picks])
                           picks (mapv
                                   #(if (map? %)
                                      (reduce-kv (fn [m k v]
                                                   (assoc m (keyword k) v))
                                        {} %)
                                      %)
                                   raw)]
                       [(ops/assign :judge1-picks
                          (assoc (or (:judge1-picks data) {}) i picks))]))})))

      (final {:id done}))))

;; ---------------------------------------------------------------------------
;; Judge round 2
;; ---------------------------------------------------------------------------
(defn- judge2-region [i]
  (let [check   (keyword (str "j2-" i "-check"))
        working (keyword (str "j2-" i "-working"))
        done    (keyword (str "j2-" i "-done"))
        persona (nth judge-personas i)]
    (state {:id (keyword (str "j2-" i)) :initial check}
      (state {:id check}
        (on-entry {}
          (script {:expr
                   (fn [env data]
                     (if (< i (or (:audience-count data) 0))
                       (raise! env (keyword (str "j2-go-" i)))
                       (raise! env (keyword (str "j2-skip-" i))))
                     nil)}))
        (transition {:event (keyword (str "j2-go-" i)) :target working})
        (transition {:event (keyword (str "j2-skip-" i)) :target done}))

      (state {:id working}
        (h/llm-conversation
          {:id (str "judge2-" i)
           :params-fn
           (fn [_env data]
             {:system     (judge2-system-prompt i persona)
              :real-tools []
              :allowed-events
              [{:event       :judge2
                :description "Final-round vote."
                :data-schema [:map [:idx :int]
                              [:finalist_idx :int]
                              [:reason :string]]}]
              :max-turns                    3
              :max-conversation-duration-ms 120000
              :initial-user-message         (judge2-initial-message
                                              (:finalists data))})})
        (transition {:event  :judge2
                     :cond   (mine? i)
                     :target done}
          (script {:expr
                   (fn [_env data]
                     (let [ev (get-in data [:_event :data])]
                       [(ops/assign :judge2-votes
                          (assoc (or (:judge2-votes data) {})
                            i (select-keys ev [:finalist_idx :reason])))]))})))

      (final {:id done}))))

;; ---------------------------------------------------------------------------
;; Pure tallies
;; ---------------------------------------------------------------------------
(defn- compute-finalists
  "For each poet, take the haiku-idx with the most judge1 votes (ties → lowest
   idx). Returns a vector of finalist maps ordered by poet-idx."
  [haikus judge1-picks]
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

(defn- compute-winner
  "Plurality over judge2-votes. Returns either {:winner-idx i :votes N
   :runners-up [...]} or {:tie [idx ...] :votes N}."
  [judge2-votes]
  (let [counts (->> (vals judge2-votes)
                    (map :finalist_idx)
                    frequencies
                    (sort-by (fn [[_ c]] (- c))))
        top    (some-> counts first second)
        leaders (mapv first (take-while #(= (second %) top) counts))]
    (if (= 1 (count leaders))
      {:winner-idx (first leaders) :votes top
       :standings  (vec counts)}
      {:tie leaders :votes top :standings (vec counts)})))

;; ---------------------------------------------------------------------------
;; The chart
;; ---------------------------------------------------------------------------
(def agent
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
                :description "Begin the tournament."
                :data-schema [:map
                              [:poets :int]
                              [:audience :int]
                              [:theme :string]]}
               {:event       :abort
                :description "Refuse to start; give a reason."
                :data-schema [:map [:reason :string]]}]
              :max-turns                    2
              :max-conversation-duration-ms 60000
              :initial-user-message
              (str "USER INPUT:\n" (pr-str (:user-input data "")))})})

        (transition {:event :start-tournament :target :composing}
          (script {:expr
                   (fn [_env data]
                     (let [d (get-in data [:_event :data])]
                       [(ops/assign :poet-count
                          (max 0 (min MAX-POETS (long (:poets d)))))
                        (ops/assign :audience-count
                          (max 0 (min MAX-AUDIENCE (long (:audience d)))))
                        (ops/assign :theme (:theme d))]))}))

        (transition {:event :abort :target :aborted}
          (script {:expr
                   (fn [_env data]
                     [(ops/assign :abort-reason
                        (get-in data [:_event :data :reason]))])})))

      ;; ---------- PHASE 2: composing (parallel poets) ----------
      (apply parallel {:id :composing}
        (map poet-region (range MAX-POETS)))

      (transition {:event :done.state.composing :target :judging-r1})

      ;; ---------- PHASE 3: judging round 1 (parallel judges) ----------
      (apply parallel {:id :judging-r1}
        (map judge1-region (range MAX-AUDIENCE)))

      (transition {:event :done.state.judging-r1 :target :tallying-r1})

      ;; ---------- PHASE 4: tally round 1 → finalists ----------
      (state {:id :tallying-r1}
        (on-entry {}
          (script {:expr
                   (fn [env data]
                     (let [finalists (compute-finalists
                                       (:haikus data)
                                       (:judge1-picks data))]
                       (raise! env :tally-r1-done)
                       [(ops/assign :finalists finalists)]))}))
        (transition {:event :tally-r1-done :target :judging-r2}))

      ;; ---------- PHASE 5: judging round 2 (parallel judges) ----------
      (apply parallel {:id :judging-r2}
        (map judge2-region (range MAX-AUDIENCE)))

      (transition {:event :done.state.judging-r2 :target :tallying-r2})

      ;; ---------- PHASE 6: tally round 2 → winner ----------
      (state {:id :tallying-r2}
        (on-entry {}
          (script {:expr
                   (fn [env data]
                     (let [result (compute-winner (:judge2-votes data))]
                       (raise! env :tally-r2-done)
                       [(ops/assign :result result)]))}))
        (transition {:event :tally-r2-done :target :finished}))

      (final {:id :aborted})
      (final {:id :finished}))))
