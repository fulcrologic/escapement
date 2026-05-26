(ns escapement.chart.helpers
  "Authoring sugar for chart elements that talk to the `:llm-conversation`
  InvocationProcessor.

  * `llm-conversation` — wraps `(invoke {:type :llm-conversation ...})`. Forces
    `:autoforward? true` so the chart can push events back into the live LLM via
    `tell-llm`.
  * `tell-llm` — emits a `:script` element that posts a `:llm.user-message` event
    onto the chart's own session. With `:autoforward?` set on the invoke, the
    library will hand the event to the processor's `forward-event!`, which routes
    the message into the live conversation.

  IMPORTANT: `tell-llm` must execute inside the state that owns the binding (or a
  descendant thereof); the invocation only exists while that state is active."
  (:require
    #?(:clj [clojure.java.io :as io])
    [clojure.string :as str]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :as elt]
    [com.fulcrologic.statecharts.environment :as env-ns]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.chart.service :as service]
    [escapement.invocation.llm-conversation :as llmc])
  #?(:clj
     (:import
       (java.nio.file Files Paths StandardCopyOption)
       (java.nio.file.attribute FileAttribute))))

;; ---------------------------------------------------------------------------
;; Region-tool authoring sugar (re-exported from escapement.chart.service)
;; ---------------------------------------------------------------------------

(def ^{:doc "Alias for `escapement.chart.service/handle`. Returns a
  transition element that runs `handler-fn` when the chart fires
  `event-kw` on the addressed service region. See that ns for the full
  contract."}
  handle-tool service/handle)

(def ^{:doc "Alias for `escapement.chart.service/post-reply`. Fire a
  deferred reply for a region-tool request whose handler returned `nil`
  (slow-work pattern). See that ns for the full contract."}
  post-reply service/post-reply)

;; ---------------------------------------------------------------------------
;; Flat authoring-key resolution
;;
;; Every authored key is a literal OR a `(fn [env data])`, resolved at invoke
;; time. `fn?` is the "compute me" signal, so a param whose legitimate VALUE is
;; a function (e.g. human-input's `:render`) must be listed in `raw-ks` to skip
;; resolution. There is no `:params`/`:params-fn` escape hatch — compute several
;; params from `data` with a `(fn [env data])` per key.
;; ---------------------------------------------------------------------------

(def ^:private llm-aliases
  "Friendly authoring names → canonical processor keys for `llm-conversation`."
  {:message   :initial-user-message
   :budget-ms :max-conversation-duration-ms})

(defn- apply-aliases
  "Rename alias keys in `m` to their canonical names per `aliases`
   (alias→canonical). If both the alias and the canonical key are present, the
   canonical key wins and the alias is dropped."
  [m aliases]
  (reduce-kv
    (fn [m a c]
      (if (contains? m a)
        (cond-> m
          (not (contains? m c)) (assoc c (get m a))
          true                  (dissoc a))
        m))
    m aliases))

(defn- resolve-flat-params
  "Returns the params map a processor consumes from flat authoring `opts`.

   Drops the control keys in `:drop-ks`; for every remaining key applies the
   value-or-expression rule `(if (fn? v) (v env data) v)`, EXCEPT keys in
   `:raw-ks` which pass through untouched (they are legitimately
   function-valued, e.g. `:render`); finally applies `:aliases`
   (alias→canonical, canonical wins).

   `cfg` keys: `:drop-ks` (coll), `:raw-ks` (set, default `#{}`),
   `:aliases` (map, optional)."
  [opts env data {:keys [drop-ks raw-ks aliases] :or {raw-ks #{}}}]
  (let [named    (apply dissoc opts drop-ks)
        resolved (reduce-kv
                   (fn [m k v]
                     (assoc m k (if (and (fn? v) (not (contains? raw-ks k)))
                                  (v env data)
                                  v)))
                   {} named)]
    (cond-> resolved
      aliases (apply-aliases aliases))))

(defn llm-conversation
  "Returns an `invoke` element of type `:llm-conversation`.

   Authoring keys are flat: each value is a literal OR a `(fn [env data])`
   resolved at invoke time. Static config stays a literal; only data-dependent
   slots are lambdas. There is no `:params`/`:params-fn` — to compute several
   params from `data`, write a lambda per key.

   Curated keys (others the processor accepts pass through verbatim under the
   same literal-or-fn rule):

     * `:id` (required)       — invoke id / `invokeid` in the processor.
     * `:system`              — system prompt.
     * `:message`             — initial user message; alias for the processor's
                                `:initial-user-message`. Usually a lambda.
     * `:max-turns`           — turn cap.
     * `:budget-ms`           — wall-clock budget; alias for the processor's
                                `:max-conversation-duration-ms`.
     * `:allowed-events`      — event-tool declarations the LLM may emit.
     * `:real-tools`          — real tool ids exposed to the LLM.
     * `:chart-tools`         — chart/region tools exposed to the LLM.
     * `:model`/`:models`/`:needs` — model selection policy.
     * `:verdict-schema`      — schema for the `submit_verdict` wrap-up inference.
     * `:autoforward?` (default true) — whether to autoforward chart events to
                                the invocation so `tell-llm` works. Control key
                                (not a param).

   If both `:message` and `:initial-user-message` (or both `:budget-ms` and
   `:max-conversation-duration-ms`) are given, the canonical processor key wins."
  [{:keys [id autoforward?] :or {autoforward? true} :as opts}]
  (assert id "llm-conversation requires :id")
  (elt/invoke {:type          :llm-conversation
               :id            id
               :autoforward   autoforward?
               :auto-forward? autoforward?
               :params        (fn [env data]
                                (resolve-flat-params opts env data
                                  {:drop-ks [:id :autoforward?]
                                   :raw-ks  #{}
                                   :aliases llm-aliases}))}))

(defn human-input
  "Returns an `invoke` element of type `:human-input`.

   When the state owning this invocation is entered, the human-input processor
   spawns a worker that prompts the user via the active `HumanRenderer` (TUI
   modal when a TUI is running, stdin fallback otherwise). The worker posts
   `:human.answer {:answer …}` (or `:on-answer-event`) to the chart's session
   when the user responds, then dies.

   Authoring keys are flat: each value is a literal OR a `(fn [env data])`
   resolved at invoke time — EXCEPT `:render`, which passes through untouched
   because it IS the function the processor calls.

   `opts`:
    * `:id` (required)       — invoke id. Control key (not a param).
    * `:kind`                — `:text` | `:select` | `:multi-select` | `:confirm` | `:progress` | `:custom`
    * `:prompt`              — string shown to the user (or a lambda).
    * `:options`             — `[{:label … :value …}]` for `:select`/`:multi-select`.
    * `:answer-schema`       — optional Malli schema validating the answer.
    * `:on-answer-event`     — default `:human.answer`.
    * `:on-cancel-event`     — default `:human.cancelled`.
    * `:render`              — required for `:custom`; `(fn [env data] answer)`.
                               Passed through RAW (never resolved against env/data).

   Errors fire SCXML canonical `:error.human.<reason>` events; transition on
   `:error.human.*` to catch any human-side failure."
  [{:keys [id] :as opts}]
  (assert id "human-input requires :id")
  (elt/invoke {:type   :human-input
               :id     id
               :params (fn [env data]
                         (resolve-flat-params opts env data
                           {:drop-ks [:id]
                            :raw-ks  #{:render}
                            :aliases nil}))}))

(declare tell-llm)

(defn with-llm-questions
  "Authoring sugar for the \"LLM can ask the human for design decisions or
   clarifications mid-conversation\" pattern.

   Produces a compound STATE (not an invoke) containing:
     1. An `llm-conversation` invocation owned by the parent state, so the
        conversation stays alive across question detours.
     2. A `:converse` child state that hosts the user-supplied exit transitions.
     3. Two ask child states (`:ask-choice`, `:ask-text`) that pop a
        `human-input` modal and `tell-llm` the answer back to the live LLM.

   Two event-tools are injected into the LLM's `:allowed-events`:

     event__ask_choice  {:question :string
                         :options  [{:label :string :value :any} …]}
     event__ask_text    {:question :string}

   When the LLM calls either, the chart visits the corresponding ask state,
   prompts the human, and `tell-llm`s the answer prefixed with `User chose: `
   or `User said: ` so the LLM can react.

   **REQUIRES an interactive chart** (`^{:interactive? true}` on the chart
   var). Without a `HumanRenderer` wired through the CLI (TUI or stdin), the
   ask states will stall on first invocation. Use plain `llm-conversation`
   for non-interactive charts.

   `opts`:
    * `:id` (required) — id of the compound state.
    * any `llm-conversation` flat keys (`:system`, `:message`, `:allowed-events`,
      `:real-tools`, …) for the underlying conversation. Each is a literal or a
      `(fn [env data])`. Your `:allowed-events` (if any) are concatenated with
      the two injected question events.
    * `:exit-transitions` (optional) — vector of `transition` elements installed
      on the `:converse` child state. Use these to leave the bubble when the
      LLM signals completion (e.g. `(transition {:event :done :target ...})`).
    * `:autoforward?` (optional, default true)."
  [{:keys [id exit-transitions autoforward?]
    :or   {autoforward? true exit-transitions []}
    :as   opts}]
  (assert id "with-llm-questions requires :id")
  (let [base-name     (name id)
        converse-id   (keyword (str base-name ".converse"))
        ask-choice-id (keyword (str base-name ".ask-choice"))
        ask-text-id   (keyword (str base-name ".ask-text"))
        question-events
                      [{:event       :question/ask-choice
                        :description "Ask the human to pick one option. Use when you need a design decision the human must make."
                        :data-schema [:map
                                      [:question :string]
                                      [:options [:vector [:map
                                                          [:label :string]
                                                          [:value :any]]]]]}
                       {:event       :question/ask-text
                        :description "Ask the human a free-text question. Use when you need clarification or a value the human must supply."
                        :data-schema [:map [:question :string]]}]
        ;; Forward the caller's flat conversation keys, but always resolve
        ;; :allowed-events (literal or fn) at invoke time and append the two
        ;; injected question events.
        conv-opts     (-> (dissoc opts :id :exit-transitions :autoforward?)
                        (assoc :allowed-events
                          (fn [env data]
                            (let [evs  (:allowed-events opts)
                                  base (if (fn? evs) (evs env data) (or evs []))]
                              (vec (concat base question-events))))))]
    (apply elt/state {:id id :initial converse-id}
      ;; Conversation owned by the PARENT so child-state transitions
      ;; don't tear it down.
      (llm-conversation
        (assoc conv-opts
          :id           (str base-name "/conv")
          :autoforward? autoforward?))
      (apply elt/state {:id converse-id}
        (elt/transition
          {:event :question/ask-choice :target ask-choice-id}
          (elt/script
            {:expr (fn [_ data]
                     [(ops/assign :question/pending-question
                        (get-in data [:_event :data :question]))
                      (ops/assign :question/pending-options
                        (get-in data [:_event :data :options]))])}))
        (elt/transition
          {:event :question/ask-text :target ask-text-id}
          (elt/script
            {:expr (fn [_ data]
                     [(ops/assign :question/pending-question
                        (get-in data [:_event :data :question]))])}))
        exit-transitions)
      [(elt/state {:id ask-choice-id}
         (human-input
           {:id      (str base-name "/ask-choice")
            :kind    :select
            :prompt  (fn [_ data] (:question/pending-question data))
            :options (fn [_ data] (:question/pending-options data))})
         (elt/transition
           {:event :human.answer :target converse-id}
           (tell-llm
             {:expr (fn [_ data]
                      (str "User chose: "
                        (pr-str (get-in data [:_event :data :answer]))))}))
         (elt/transition
           {:event :human.cancelled :target converse-id}
           (tell-llm
             {:expr (fn [_ _]
                      "User declined to choose; please proceed with your best judgement.")}))
         (elt/transition
           {:event :error.human.* :target converse-id}
           (tell-llm
             {:expr (fn [_ _]
                      "Question prompt errored; please proceed without that input.")})))

       (elt/state {:id ask-text-id}
         (human-input
           {:id     (str base-name "/ask-text")
            :kind   :text
            :prompt (fn [_ data] (:question/pending-question data))})
         (elt/transition
           {:event :human.answer :target converse-id}
           (tell-llm
             {:expr (fn [_ data]
                      (str "User said: "
                        (pr-str (get-in data [:_event :data :answer]))))}))
         (elt/transition
           {:event :human.cancelled :target converse-id}
           (tell-llm
             {:expr (fn [_ _]
                      "User declined to answer; please proceed without that input.")}))
         (elt/transition
           {:event :error.human.* :target converse-id}
           (tell-llm
             {:expr (fn [_ _]
                      "Question prompt errored; please proceed without that input.")})))])))

(defn tell-llm
  "Returns a `script` element that, when executed, posts a `:llm.user-message`
   event into the current session (the chart's session). When the chart has
   one or more live `:llm-conversation` invocations with `:autoforward? true`,
   each invocation's `forward-event!` receives the event.

   Without `:target`, EVERY live conversation accepts the message. With
   `:target`, only the conversation whose `invokeid` matches accepts — use
   `tell-other-llm` for that case.

   `opts`:
    * `:expr` (required) — `(fn [env data] text-string)` returning the user text."
  [{:keys [expr]}]
  (assert (fn? expr) "tell-llm requires :expr")
  (elt/script
    {:expr (fn [env data]
             (let [text  (expr env data)
                   queue (::sc/event-queue env)
                   sid   (env-ns/session-id env)]
               (sp/send! queue env
                 {:target            sid
                  :source-session-id sid
                  :event             :llm.user-message
                  :data              {:text text}})
               nil))}))

(defn tell-other-llm!
  "Direct-call form of [[tell-other-llm]] for use INSIDE a handler lambda
   (e.g. the body of [[handle-tool]] or a `script` `:expr`). Whereas
   [[tell-other-llm]] returns a chart `script` element to embed at chart-
   authoring time, this function performs the send! immediately against the
   `env` passed in.

   Call only from chart-side code (transition handlers, onentry/onexit,
   handle-tool handlers). Never expose to the LLM as a tool."
  [env target text]
  (let [target' (llmc/->id-str target)
        queue   (::sc/event-queue env)
        sid     (env-ns/session-id env)]
    (sp/send! queue env
      {:target            sid
       :source-session-id sid
       :event             :llm.user-message
       :data              {:text text :target target'}})
    nil))

(defn tell-other-llm
  "Like `tell-llm` but targets a specific conversation by its `invokeid`. The
   underlying `:llm.user-message` event carries `{:text … :target <invokeid>}`;
   only the matching `:llm-conversation` invocation's `forward-event!`
   accepts it.

   Use this for the team / advisor pattern, where one LLM's response should
   be routed to a specific peer conversation rather than broadcast.

   `opts`:
    * `:target` (required) — invokeid string of the destination conversation.
                             May also be a function `(fn [env data] invokeid)`
                             so the target can be computed from chart state.
    * `:expr`   (required) — `(fn [env data] text-string)`.

   For direct calls from inside a handler lambda, see [[tell-other-llm!]]."
  [{:keys [target expr]}]
  (assert target "tell-other-llm requires :target")
  (assert (fn? expr) "tell-other-llm requires :expr")
  (elt/script
    {:expr (fn [env data]
             (let [text   (expr env data)
                   target (if (fn? target) (target env data) target)]
               (tell-other-llm! env target text)))}))

;; ===========================================================================
;; Artifact helpers — file-backed LLM outputs
;;
;; Conventions:
;;   - One directory per session: `<session-dir>/artifacts/`.
;;   - Each artifact is a single file. Name includes any extension the
;;     chart-author wants (default = the producing invokeid, no extension).
;;   - Writes are atomic via temp + rename so concurrent reads never see a
;;     partial file.
;;   - Latest write wins. To keep history, pick versioned names yourself
;;     (e.g. `(str "draft-" iteration ".md")`).
;; ===========================================================================

(defn- session-dir
  "Pull the session dir out of an SCXML env. Throws if missing — required for
   any artifact-related operation."
  [env]
  (or (:escapement/session-dir env)
    (throw (ex-info "No :escapement/session-dir on env — set :session-dir on (runner/run! …)"
             {:reason :missing-session-dir}))))

(defn- artifact-path
  "Absolute filesystem path for an artifact named `name` in `env`'s session."
  [env name]
  (str (session-dir env) "/artifacts/" name))

(defn- atomic-write!
  "Write `s` to `path`, durably and atomically. Creates parent dirs.

   CLJ/bb only: requires a real filesystem. CLJS hosts that want artifact
   semantics must supply their own storage layer (no protocol exists yet)."
  [^String path ^String s]
  #?(:clj
     (let [target (Paths/get path (into-array String []))
           parent (.getParent target)
           _      (when parent
                    (Files/createDirectories parent (into-array FileAttribute [])))
           tmp    (Files/createTempFile parent ".artifact-" ".tmp"
                    (into-array FileAttribute []))]
       (Files/writeString tmp s (into-array java.nio.file.OpenOption []))
       (Files/move tmp target
         (into-array java.nio.file.CopyOption
           [StandardCopyOption/ATOMIC_MOVE
            StandardCopyOption/REPLACE_EXISTING])))
     :cljs
     (throw (ex-info "atomic-write! is CLJ/bb only — no filesystem in CLJS host"
              {:reason :no-fs :path path}))))

(defn- read-artifact!
  "Read the artifact named `name` in `env`'s session. Throws ex-info with
   {:reason :missing-artifact :name name :path path} if the file isn't there.

   CLJ/bb only (see [[atomic-write!]])."
  [env name]
  #?(:clj
     (let [path (artifact-path env name)
           f    (io/file path)]
       (when-not (.exists f)
         (throw (ex-info (str "Missing artifact: " name)
                  {:reason :missing-artifact :name name :path path})))
       (slurp f))
     :cljs
     (throw (ex-info "read-artifact! is CLJ/bb only — no filesystem in CLJS host"
              {:reason :no-fs :name name}))))

(defn capture-llm-output
  "Returns a `script` element. When executed inside a transition on
   `:llm.idle` (or any event whose data carries `:text` and `:from`), writes
   the LLM's final assistant text to `<session-dir>/artifacts/<name>`.

   `opts` (all optional):
    * `:as`     — filename. Default `(:from data-of-event)`, i.e. the invokeid
                  of the LLM that finished. Pass `\"report.md\"` etc. for
                  explicit names with extensions."
  ([] (capture-llm-output {}))
  ([{:keys [as] :as _opts}]
   (elt/script
     {:expr
      (fn [env data]
        (let [text (or (get-in data [:_event :data :text]) "")
              from (llmc/->id-str (get-in data [:_event :data :from]))
              name (llmc/->id-str (or as from))]
          (when-not name
            (throw (ex-info "capture-llm-output: no :as and no :from in event data"
                     {:reason :missing-artifact-name})))
          (atomic-write! (artifact-path env name) text)
          ;; Surface for the TUI + transcript JSONL.
          (when-let [tfn (:escapement/transcript-fn env)]
            (try (tfn {:event :artifact/captured
                       :data  {:name name :bytes (count text)}})
                 (catch Throwable _ nil)))
          nil))})))

(def ^:private mustache-pattern
  #"\{\{\s*([A-Za-z0-9_./\-]+)\s*\}\}")

(defn- resolve-token
  "Resolve a single template token. `:output` is reserved for the in-flight
   assistant text passed via `extras`; any other token names an artifact
   file. Throws ex-info on misses so chart-authors catch typos at the call
   site rather than send garbage to the LLM."
  [env extras token]
  (cond
    (= "output" token)
    (or (:output extras)
      (throw (ex-info "Template uses {{output}} but no :output in scope"
               {:reason :missing-template-key :key "output"})))

    :else
    (read-artifact! env token)))

(defn render-template
  "Substitute `{{name}}` tokens in `template` with file-backed artifacts from
   `<session-dir>/artifacts/<name>`. Throws on any missing token (fail-fast
   so a half-populated session doesn't silently produce a malformed prompt).

   `{{output}}` is reserved for the in-flight assistant text and resolves
   only when callers pass `:output` in `extras`.

   Use this inside a `(fn [env data])` authoring key (e.g. `:message`/`:system`,
   where `env` is available) to compose initial user messages or system prompts
   from prior agents' outputs."
  ([template env]
   (render-template template env nil))
  ([template env extras]
   (str/replace template mustache-pattern
     (fn [[_ token]]
       (resolve-token env extras token)))))

(defn forward-llm-output
  "Returns a `script` element that captures the in-flight LLM output to a
   file (unless `:capture? false`), then sends a formatted `:llm.user-message`
   targeted at another conversation.

   `{{output}}` in the template resolves to the in-flight text; all other
   `{{name}}` tokens resolve to artifacts.

   `opts`:
    * `:to`        (required) — destination invokeid string.
    * `:template`  (required) — string with `{{output}}` and/or `{{name}}` slots.
    * `:as`        — filename for the captured artifact; default
                     `(:from event-data)`.
    * `:capture?`  — default true. Set to false to forward without writing
                     a file (e.g. you only want the prompt routed)."
  [{:keys [to template as capture?]
    :or   {capture? true}}]
  (assert to "forward-llm-output requires :to")
  (assert (string? template) "forward-llm-output requires a string :template")
  (elt/script
    {:expr
     (fn [env data]
       (let [text     (or (get-in data [:_event :data :text]) "")
             from     (llmc/->id-str (get-in data [:_event :data :from]))
             art-name (llmc/->id-str (or as from))
             to'      (llmc/->id-str to)
             queue    (::sc/event-queue env)
             sid      (env-ns/session-id env)]
         (when (and capture? art-name)
           (atomic-write! (artifact-path env art-name) text)
           (when-let [tfn (:escapement/transcript-fn env)]
             (try (tfn {:event :artifact/captured
                        :data  {:name art-name :bytes (count text)}})
                  (catch Throwable _ nil))))
         (let [rendered (render-template template env {:output text})]
           (sp/send! queue env
             {:target            sid
              :source-session-id sid
              :event             :llm.user-message
              :data              {:text rendered :target to'}}))
         nil))}))
