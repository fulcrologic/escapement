(ns escapement.llm.scripted
  "A scripted `LLMBackend`: answers a chart's AI steps from a script instead of a
   provider. No network, no credentials — for tests, dry runs and replays.

   ## Script

   A script maps an AI step (the `:llm-conversation` invoke id, as a string) to a
   vector of replies. Each AI TURN of that step takes the next reply:

       {\"classify\" [{:verdict {:category :billing :confidence 0.95}}
                      {:fail :timeout}
                      {:event :triage/spam :data {:reason \"promo\"}}
                      {:tool :kb/search :input {:q \"refund\"}}
                      {:text \"plain answer\"}
                      {:malformed true}]
        :default    [...]}                   ; steps the script doesn't name

   Reply forms:

     * `{:verdict m}`        — a `submit_verdict` call with input `m` (the step's
                               `:verdict-schema` validates it).
     * `{:event kw :data m}` — a call to the step's event tool for `kw`, so the
                               chart transitions as if the model chose it.
     * `{:tool kw :input m}` — a call to one of the step's real tools; the step's
                               next turn takes the next reply.
     * `{:text s}`           — an `end_turn` text answer.
     * `{:fail category}`    — a backend failure. `category` is one of
                               `escapement.llm.protocol/error-categories`, or
                               `:backend` for an uncategorized failure. The
                               runtime's retries and model failover for the SAME
                               turn repeat the failure without taking another
                               reply (same visit and turn per the request
                               metadata; without it, identical messages), and
                               carry `:retry-after-ms 0` so they don't wait.
     * `{:malformed true}`   — a turn with no usable verdict: plain text, and the
                               forced `submit_verdict` fallback that follows also
                               gets no verdict, so the step fails with its
                               `:error.llm.*` event.
     * `{:response r}`       — a raw Response map, returned verbatim.

   Any reply may carry `:usage` (merged over the backend's default usage) and
   `:model`.

   ## Which step asked

   The step is read from the request's Clojure metadata `:escapement/invokeid`
   when the runtime provides it. Otherwise: a `:match` fn map
   `{step-id (fn [request] bool)}`, then — when the script names exactly one step —
   that step, then `:default`.

   ## Running out

   A turn the script has no reply for fails clearly, naming the step and turn
   (an uncategorized backend error ⇒ `:error.llm.backend`, never retried), and is
   recorded in `divergences`. `:on-exhausted :repeat-last` repeats the step's last
   reply instead."
  (:require
    [clojure.string :as str]
    [com.fulcrologic.statecharts.promise :as p]
    [escapement.llm.protocol :as llm]))

(def ^:private event-tool-prefix "event__")
(def ^:private verdict-tool-name "submit_verdict")

(def ^:private transient
  "Categories the runtime retries on the same turn (`escapement.llm/transient-error-categories`)."
  #{:rate-limited :overloaded :timeout :transport})

(defn- kw->tool-name
  "Same encoding as the llm-conversation processor: `ns_name`, every char outside
   `[A-Za-z0-9_]` replaced by `_`."
  [kw]
  (let [s (if (qualified-keyword? kw)
            (str (namespace kw) "_" (name kw))
            (name kw))]
    (str/replace s #"[^A-Za-z0-9_]" "_")))

(defn event-tool-name
  "The tool name the model calls to raise chart event `kw` (`event__<ns>_<name>`)."
  [kw]
  (str event-tool-prefix (kw->tool-name kw)))

(defn real-tool-name
  "The tool name the model calls for real tool `kw` (`<ns>_<name>`)."
  [kw]
  (kw->tool-name kw))

(defn- step-key [x]
  (cond (keyword? x) (name x) (nil? x) nil :else (str x)))

(defn- offered-tools [request]
  (into #{} (map :name) (:tools request)))

(defn- forced-verdict? [request]
  (= verdict-tool-name (get-in request [:tool-choice :name])))

(defn- step-of
  "Which script step `request` belongs to."
  [{:keys [script match]} request]
  (let [named (remove #{:default} (keys script))]
    (or (step-key (:escapement/invokeid (meta request)))
      (some (fn [[k f]] (when (f request) (step-key k))) match)
      (when (= 1 (count named)) (step-key (first named)))
      :default)))

(defn- replies-for [script step]
  (or (get script step)
    (get script (keyword step))
    (when-not (= step :default) (get script :default))))

(defn- id-gen [state]
  (str "toolu_scripted_" (:n (swap! state update :n (fnil inc 0)))))

(defn- fail [category step turn]
  (let [msg (str "scripted LLM failure (" (name category) ") for AI step "
              (pr-str step) ", turn " turn)]
    (if (contains? llm/error-categories category)
      (llm/llm-error category msg {:data {:retry-after-ms 0 :scripted/step step}})
      (ex-info msg {:scripted/step step :retry-after-ms 0}))))

(defn- response
  [{:keys [usage model]} reply stop-reason content]
  {:stop-reason stop-reason
   :content     content
   :usage       (merge usage (:usage reply))
   :model       (or (:model reply) model)})

(defn- tool-use [state nm input]
  {:type :tool_use :id (id-gen state) :name nm :input (or input {})})

(defn- build-reply
  "Turn one script `reply` into a promise of a Response (or a rejection)."
  [{:keys [state] :as cfg} step turn request reply]
  (let [offered (offered-tools request)
        not-offered (fn [nm what]
                      (swap! state update :divergences conj
                        {:step step :turn turn :reason :not-offered :tool nm :what what})
                      (p/rejected
                        (ex-info (str "scripted reply for AI step " (pr-str step) ", turn " turn
                                   " calls " what " (tool " nm ") but the step does not offer it."
                                   " Offered: " (str/join ", " (sort offered)))
                          {:scripted/step step :tool nm :offered offered})))]
    (cond
      (contains? reply :response)
      (let [names (keep #(when (= :tool_use (:type %)) (:name %)) (:content (:response reply)))
            bad   (first (remove offered names))]
        (if bad
          (not-offered bad "a recorded tool call")
          (p/resolved (:response reply))))

      (contains? reply :fail)
      (p/rejected (fail (:fail reply) step turn))

      (contains? reply :verdict)
      (if (contains? offered verdict-tool-name)
        (p/resolved (response cfg reply :tool_use
                      [(tool-use state verdict-tool-name (:verdict reply))]))
        (not-offered verdict-tool-name "submit_verdict (no :verdict-schema on the step?)"))

      (contains? reply :event)
      (let [nm (event-tool-name (:event reply))]
        (if (contains? offered nm)
          (p/resolved (response cfg reply :tool_use [(tool-use state nm (:data reply))]))
          (not-offered nm (str "event " (:event reply)))))

      (contains? reply :tool)
      (let [nm (real-tool-name (:tool reply))]
        (if (contains? offered nm)
          (p/resolved (response cfg reply :tool_use [(tool-use state nm (:input reply))]))
          (not-offered nm (str "tool " (:tool reply)))))

      (:malformed reply)
      (p/resolved (response cfg reply :end_turn
                    [{:type :text :text (or (:text reply) "I am not sure.")}]))

      (contains? reply :text)
      (p/resolved (response cfg reply :end_turn [{:type :text :text (str (:text reply))}]))

      :else
      (p/rejected (ex-info (str "scripted reply for AI step " (pr-str step)
                             " has no recognised key: " (pr-str reply))
                    {:scripted/step step :reply reply})))))

(defn- next-reply!
  "Pick the reply for this request, updating per-step cursors. Returns
   `{:reply r :turn n}` or `{:exhausted? true :turn n}`."
  [{:keys [state script on-exhausted]} step request]
  (let [msgs   (:messages request)
        rmeta  (meta request)
        at     (when (and (number? (:escapement/visit rmeta)) (number? (:escapement/turn rmeta)))
                 [(:escapement/visit rmeta) (:escapement/turn rmeta)])
        st     @state
        cur    (get-in st [:steps step])
        turn   (:turns cur 0)
        retry? (and (:last-fail cur)
                 (if (and at (:last-fail-at cur))
                   (= at (:last-fail-at cur))
                   (and (= msgs (:last-fail-messages cur))
                     (:last-fail-transient? cur))))]
    (cond
      ;; A retry of a turn that failed (same visit+turn when the runtime says so;
      ;; otherwise identical context) ⇒ same failure.
      retry?
      {:reply (:last-fail cur) :turn (dec turn) :retry? true}

      ;; The forced verdict fallback after a malformed turn: still no verdict.
      (and (:malformed? cur) (forced-verdict? request))
      (do (swap! state assoc-in [:steps step :malformed?] false)
          {:reply {:malformed true} :turn (dec turn) :follow-up? true})

      :else
      (let [replies (vec (replies-for script step))
            idx     (:cursor cur 0)
            reply   (cond
                      (< idx (count replies)) (nth replies idx)
                      (and (= on-exhausted :repeat-last) (seq replies)) (peek replies)
                      :else nil)]
        (swap! state update-in [:steps step]
          (fn [c] (-> (or c {})
                    (assoc :turns (inc turn) :cursor (inc idx)
                      :malformed? (boolean (:malformed reply)))
                    (cond-> (contains? reply :fail)
                      (assoc :last-fail reply :last-fail-messages msgs
                        :last-fail-at at
                        :last-fail-transient? (contains? transient (:fail reply))))
                    (cond-> (not (contains? reply :fail))
                      (dissoc :last-fail :last-fail-messages :last-fail-at :last-fail-transient?)))))
        (if reply
          {:reply reply :turn turn}
          {:exhausted? true :turn turn})))))

(defrecord ScriptedBackend [cfg]
  llm/LLMBackend
  (send-turn [_ request]
    (let [{:keys [state]} cfg
          step (step-of cfg request)
          {:keys [reply turn exhausted?] :as pick} (next-reply! cfg step request)]
      (swap! state update :calls conj
        (cond-> {:step step :turn turn :request request}
          reply (assoc :reply reply)
          (:retry? pick) (assoc :retry? true)
          exhausted? (assoc :exhausted? true)))
      (if exhausted?
        (do (swap! state update :divergences conj {:step step :turn turn :reason :exhausted})
            (p/rejected
              (ex-info (str "scripted LLM has no reply for AI step " (pr-str step)
                         " (turn " turn "): the script ran out")
                {:scripted/step step :scripted/turn turn :scripted/reason :exhausted})))
        (build-reply cfg step turn request reply)))))

(defn backend
  "A scripted `LLMBackend`. `opts`:
     * `:script`       — `{step-id [reply …]}` (see the ns doc). Step ids are the AI
                         steps' invoke ids; strings or keywords.
     * `:match`        — optional `{step-id (fn [request] bool)}` used when a request
                         carries no `:escapement/invokeid` metadata.
     * `:on-exhausted` — `:error` (default) | `:repeat-last`.
     * `:usage`        — default usage on every reply
                         (default `{:input-tokens 0 :output-tokens 0}`).
     * `:model`        — model reported on replies (default \"scripted\").
"
  [{:keys [script match on-exhausted usage model]
    :or   {on-exhausted :error
           usage        {:input-tokens 0 :output-tokens 0}
           model        "scripted"}}]
  (->ScriptedBackend
    {:script       (reduce-kv (fn [m k v] (assoc m (if (= k :default) k (step-key k)) (vec v)))
                     {} (or script {}))
     :match        match
     :on-exhausted on-exhausted
     :usage        usage
     :model        model
     :state        (atom {:calls [] :divergences [] :steps {}})}))

(defn calls
  "Every request the backend answered, in order:
   `[{:step s :turn n :request r :reply r} …]` (`:exhausted? true` when it had none)."
  [b]
  (:calls @(:state (:cfg b))))

(defn divergences
  "Where the run asked for something the script didn't have:
   `[{:step s :turn n :reason :exhausted|:not-offered …} …]`."
  [b]
  (:divergences @(:state (:cfg b))))

(defn remaining
  "`{step [unused reply …]}` — replies the run never asked for."
  [b]
  (let [{:keys [script state]} (:cfg b)
        steps (:steps @state)]
    (into {}
      (keep (fn [[k replies]]
              (let [used (get-in steps [k :cursor] 0)
                    left (subvec replies (min used (count replies)))]
                (when (seq left) [k left]))))
      script)))
