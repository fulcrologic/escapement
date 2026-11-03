(ns escapement.testing.cases
  "Run one TEST CASE (plain EDN) against a chart: pretend AI answers (the scripted
   backend), pretend tools, outside events at fake times, and a test clock so waits
   and retry backoffs take no real time. Returns the path the run took and every
   expectation that failed.

   ## Case

       {:name         \"billing email goes to billing\"
        :start        \"2026-11-23T09:00:00Z\"     ; test-clock start (default: now)
        :initial-data {:email {…}}
        :ai           {\"classify\" [{:verdict {:category :billing :confidence 0.95}}]}
        :ai-on-exhausted :error                     ; or :repeat-last
        :tools        {:email/send {:reply \"sent\"}  ; pretend tools (escapement.testing.tools)
                       :crm/lookup {:replies [{:result \"down\" :is-error true} {:result \"{}\"}]}}
        :events       [{:event :approval/answered :data {:approved? true} :at-ms 3600000}]
        :expect       {:status :done                ; :done | :waiting | :sleeping
                       :final  #{:done}             ; where it ended (⊆)
                       :path   [:classify :send]    ; ordered SUBSEQUENCE of entered states
                       :never  #{:failed}           ; states never entered
                       :data   {[:route] :billing}  ; get-in path (or key) -> value
                       :tool-calls {:email/send 1}  ; exact call counts
                       :max-ai-calls 3
                       :ai-request-contains {\"draft\" \"Reply in German\"}
                       :min-elapsed-fake-ms 3600000}}

   Events are delivered in order, each once the run has settled (nothing due now,
   nothing in flight). An event with `:at-ms` is delivered at that offset from the
   start on the test clock; timers due before it fire first. After the last event
   the clock keeps jumping to each due timer until the run finishes or only an
   outside event could move it (bounded by `:max-fake-ms`, default 60 days).

   ## Running

       (run-case {:chart c :chart-id id :shape shape} case
                 {:tool-registry base :work-dir dir})
       ;; => {:pass? :status :path :final :final-config :data :failures
       ;;     :tool-calls :ai-calls :guards :metrics :elapsed-fake-ms :transcript-path …}"
  (:require
    [babashka.fs :as fs]
    [clojure.string :as str]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.chart.data :as data]
    [escapement.engine.clock :as clock]
    [escapement.engine.step :as step]
    [escapement.llm.preferences :as preferences]
    [escapement.llm.scripted :as scripted]
    [escapement.metrics :as metrics]
    [escapement.testing.tools :as pt]
    [taoensso.timbre :as log]))

(def ^:private day-ms (* 24 60 60 1000))

(defn- start-ms [start]
  (cond
    (nil? start) (System/currentTimeMillis)
    (number? start) (long start)
    (string? start) (.toEpochMilli (java.time.Instant/parse start))
    :else (throw (ex-info "case :start must be an ISO instant string or epoch ms" {:start start}))))

(defn- llm-opts
  "The model-resolution inputs `escapement.lib/run` derives from an empty config."
  []
  (let [prefs   (preferences/preferences {})
        aliases (preferences/aliases-from-config {})]
    {:llm-aliases            aliases
     :llm-preferences        prefs
     :backend-default-models (preferences/model-order prefs aliases)
     :catalog-ratings        {}
     :eligibility-strict?    false}))

(defn- send-outside! [session {:keys [event data]}]
  (let [env (:env session)
        sid (:session-id session)]
    ;; No :source-session-id: that marks an event the chart raised itself; this
    ;; one comes from outside (and is recorded as such).
    (sp/send! (::sc/event-queue env) env {:event event :data (or data {}) :target sid})))

(defn- current-data [session]
  (let [{:keys [env store session-id]} session
        wm (sp/get-working-memory store env session-id)]
    (when wm
      (sp/current-data (::sc/data-model env)
        (assoc env ::sc/vwmem (volatile! wm) ::sc/context-element-id nil)))))

(defn- depth [c id]
  (loop [p (chart/get-parent c id) n 0]
    (if (or (nil? p) (= :ROOT p) (> n 100)) n (recur (chart/get-parent c p) (inc n)))))

(defn path-of
  "Entered states, in order, from transcript `rows`: the `:chart/entered` rows a
   data-built chart writes; for other charts, the runner's per-event `:entered`
   (ancestors before children within one event)."
  [c rows]
  (if (some #(= :chart/entered (:event %)) rows)
    (into [] (keep #(when (= :chart/entered (:event %)) (get-in % [:data :state]))) rows)
    (vec
      (mapcat (fn [{:keys [event data]}]
                (case event
                  :runner/start-config (sort-by #(depth c %) (:config data))
                  :runner/event-processed (sort-by #(depth c %) (:entered data))
                  nil))
        rows))))

(defn- subsequence? [want have]
  (loop [w (seq want) h (seq have)]
    (cond (nil? w) true
          (nil? h) false
          (= (first w) (first h)) (recur (next w) (next h))
          :else (recur w (next h)))))

(defn- request-text [req]
  (str/join "\n"
    (concat [(str (:system req))]
      (for [m (:messages req) b (:content m)]
        (str (or (:text b) (:content b) (some-> (:input b) pr-str)))))))

(defn- check-expectations
  [{:keys [expect]} {:keys [status path final data tool-counts ai-calls elapsed-fake-ms]}]
  (let [fail (fn [k expected actual msg] {:expect k :expected expected :actual actual :message msg})
        status-ok? (fn [want] (or (= want status) (and (#{:done :final} want) (= :done status))))]
    (vec
      (concat
        (when-let [s (:status expect)]
          (when-not (status-ok? s)
            [(fail :status s status (str "Expected the run to end " (name s) ", but it is " (name status) "."))]))
        (when-let [f (:final expect)]
          (let [f (if (coll? f) (set f) #{f})]
            (when-not (every? (set final) f)
              [(fail :final f final (str "Expected the run to end in " (pr-str f) ", but it ended in "
                                      (pr-str final) "."))])))
        (when-let [p (:path expect)]
          (when-not (subsequence? p path)
            [(fail :path p path (str "Expected the path to pass through " (pr-str p) " in that order; it went "
                                  (pr-str path) "."))]))
        (when-let [never (:never expect)]
          (let [hit (filter (set (if (coll? never) never [never])) path)]
            (when (seq hit)
              [(fail :never never (vec (distinct hit)) (str "The run entered " (pr-str (vec (distinct hit)))
                                                         ", which it should never enter."))])))
        (for [[k v] (:data expect)
              :let [p (if (sequential? k) k [k]) actual (get-in data p)]
              :when (not= v actual)]
          (fail :data {p v} actual (str "Expected data " (pr-str p) " to be " (pr-str v) ", but it is "
                                     (pr-str actual) ".")))
        (for [[tool n] (:tool-calls expect)
              :let [actual (get tool-counts tool 0)]
              :when (not= n actual)]
          (fail :tool-calls {tool n} actual (str "Expected " tool " to be called " n " time(s), but it was called "
                                              actual ".")))
        (when-let [mx (:max-ai-calls expect)]
          (let [n (count (remove :retry? ai-calls))]
            (when (> n mx)
              [(fail :max-ai-calls mx n (str "Expected at most " mx " AI call(s), but there were " n "."))])))
        (for [[step needle] (:ai-request-contains expect)
              :let [texts (map (comp request-text :request) (filter #(= (str step) (str (:step %))) ai-calls))]
              :when (not-any? #(str/includes? % (str needle)) texts)]
          (fail :ai-request-contains {step needle} (count texts)
            (str "Expected an AI request for step " (pr-str (str step)) " to contain " (pr-str needle)
              (if (seq texts) ", but none did." ", but that step was never asked."))))
        (when-let [mn (:min-elapsed-fake-ms expect)]
          (when (< elapsed-fake-ms mn)
            [(fail :min-elapsed-fake-ms mn elapsed-fake-ms
               (str "Expected at least " mn " ms of fake time to pass, but only " elapsed-fake-ms " did."))]))))))

(defn- drive!
  "Step `session` through `events` on `clk`. Returns the last session state."
  [session clk events start {:keys [max-fake-ms max-steps]}]
  (let [limit (+ start max-fake-ms)]
    (loop [events (seq events) n 0]
      (let [st (step/step-until-quiet! session)
            ev (first events)
            at (when (:at-ms ev) (+ start (long (:at-ms ev))))
            wake (:next-wake-at st)]
        (cond
          (or (:final? st) (> n max-steps)) st

          (and ev (or (nil? at) (nil? wake) (<= at wake)))
          (do (when (and at (> at (clock/now-ms clk))) (clock/set-ms! clk at))
              (send-outside! session ev)
              (recur (next events) (inc n)))

          (and wake (<= wake limit))
          (do (when (> wake (clock/now-ms clk)) (clock/set-ms! clk wake))
              (recur events (inc n)))

          :else st)))))

(defn run-case
  "Run test case `case` against `compiled` (`{:chart :chart-id}`, as returned by
   `escapement.chart.data/compile`; optional `:shape` supplies `:data` defaults).

   `opts`:
     * `:tool-registry` — base tools (e.g. a Fakeworld registry); the case's
                          `:tools` add or override pretend ones;
     * `:backend`       — an LLM backend to use instead of the case's `:ai` script;
     * `:work-dir`      — where the run's transcript/checkpoints go (default a temp dir);
     * `:max-fake-ms`   — how far the clock may jump past the start (default 60 days);
     * `:clock`         — a MANUAL `escapement.engine.clock` clock to drive (e.g. one
                          shared with a Fakeworld world); set to the case's `:start`
                          when it has one. Default: a fresh manual clock;
     * `:session-id`, `:transcript-tap`.
   Returns the result map (see the ns doc); `:pass?` is true when no expectation failed."
  ([compiled tc] (run-case compiled tc {}))
  ([{:keys [chart chart-id shape]} tc
    {:keys [tool-registry backend work-dir max-fake-ms max-steps session-id transcript-tap clock]
     :or   {max-fake-ms (* 60 day-ms) max-steps 100000}}]
   (assert chart "run-case needs a compiled :chart")
   (let [t0       (System/currentTimeMillis)
         clk      (or clock (clock/manual-clock {:start-ms (start-ms (:start tc)) :auto-advance? false}))
         start    (if (and clock (:start tc))
                    (do (clock/set-ms! clk (start-ms (:start tc))) (start-ms (:start tc)))
                    (clock/now-ms clk))
         dir      (or work-dir (str (fs/create-temp-dir {:prefix "escapement-case-"})))
         sid      (or session-id (keyword (str "case-" (Math/abs (long (hash [(:name tc) t0]))))))
         backend  (or backend (scripted/backend {:script       (:ai tc)
                                                 :on-exhausted (or (:ai-on-exhausted tc) :error)}))
         reg      (pt/pretend-registry tool-registry (:tools tc))
         rows     (atom [])
         tap      (fn [row] (swap! rows conj row) (when transcript-tap (transcript-tap row)))
         t-path   (str (fs/path dir "transcript.jsonl"))
         session  (binding [log/*config* (assoc log/*config* :min-level :warn)]
                    (step/open (merge (llm-opts)
                                 {:chart           chart
                                  :session-id      sid
                                  :transcript-path t-path
                                  :checkpoint-dir  (str (fs/path dir "checkpoints"))
                                  :session-dir     dir
                                  :backend         backend
                                  :tool-registry   reg
                                  :transcript-tap  tap
                                  :clock           clk
                                  :initial-data    (data/initial-data shape (:initial-data tc))}
                                 (when chart-id {:chart-id chart-id}))))
         [st dmodel] (try
                       (binding [log/*config* (assoc log/*config* :min-level :warn)]
                         (let [st (drive! session clk (:events tc) start
                                    {:max-fake-ms max-fake-ms :max-steps max-steps})]
                           [st (current-data session)]))
                       (finally (step/close! session)))
         path     (path-of chart @rows)
         finals   (keep #(when (and (= :chart/entered (:event %)) (get-in % [:data :final?]))
                               (get-in % [:data :state])) @rows)
         final    (if (:final? st) (vec (take-last 1 finals)) (:configuration st))
         status   (case (:status st) :final :done (:status st))
         ai-calls (or (try (scripted/calls backend) (catch Exception _ nil)) [])
         result   {:name            (:name tc)
                   :status          status
                   :path            path
                   :final           final
                   :final-config    (:configuration st)
                   :data            dmodel
                   :tool-calls      (pt/calls reg)
                   :tool-counts     (pt/call-counts reg)
                   :ai-calls        (mapv #(select-keys % [:step :turn :reply :request :retry? :exhausted?]) ai-calls)
                   :ai-divergences  (or (try (scripted/divergences backend) (catch Exception _ nil)) [])
                   :guards          (mapv :data (filter #(= :chart/guard (:event %)) @rows))
                   :metrics         (metrics/summarize @rows)
                   :elapsed-fake-ms (- (clock/now-ms clk) start)
                   :elapsed-ms      (- (System/currentTimeMillis) t0)
                   :transcript-path t-path
                   :pending-events  (:pending-events st)}
         failures (check-expectations tc result)]
     (assoc result :pass? (empty? failures) :failures failures))))

(defn run-cases
  "Run every case in `cases` against `compiled`; returns
   `{:passed n :failed n :results [result …]}`."
  ([compiled cases] (run-cases compiled cases {}))
  ([compiled cases opts]
   (let [results (mapv #(run-case compiled % opts) cases)]
     {:passed  (count (filter :pass? results))
      :failed  (count (remove :pass? results))
      :results results})))
