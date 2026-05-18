(ns escapement.invocation.llm-conversation
  "InvocationProcessor for `:type :llm-conversation`.

  On state entry a worker thread is spawned that holds a live LLM conversation. The chart
  author declares `:real-tools` (looked up in the tool registry) and `:allowed-events` (each
  with a Malli `:data-schema`). The worker exposes BOTH as Anthropic tool definitions to the
  LLM:

  * Real-tool `tool_use` blocks are dispatched through the tool registry and the
    `tool_result` is appended to the next assistant turn input. The chart never sees them.
  * Event-tool `tool_use` blocks are validated against the declared `:data-schema`. On
    success the corresponding chart event is posted to the parent session (with the tool
    input as event data) and a synthetic `tool_result` `\"ok\"` is appended. On failure the
    LLM gets one corrective retry; a second failure aborts with `:error.llm.tool-validation`.

  When the assistant returns `:end_turn`, the worker fires `:on-end-turn-event` to the
  parent and parks until either `:llm.user-message` arrives via `forward-event!` (continues
  the conversation with a new user message) or the invocation is stopped.

  See `plan.md` for the design."
  (:require
   [clojure.string :as str]
   [escapement.llm.catalog :as catalog]
   [com.fulcrologic.guardrails.malli.core :refer [>defn =>]]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.environment :as env-ns]
   [com.fulcrologic.statecharts.protocols :as sp]
   [escapement.llm.protocol :as llm]
   [escapement.llm.types :as llm-types]
   [escapement.tools.protocol :as tp]
   [malli.core :as m]
   [malli.error :as me])
  (:import
   (java.util.concurrent ArrayBlockingQueue TimeUnit)))

;; ---------------------------------------------------------------------------
;; Name encoding for tool defs sent to the LLM
;; ---------------------------------------------------------------------------

(def ^:const event-tool-prefix "event__")

(defn- kw->anthropic-name
  "Encode a keyword as an Anthropic-tool-compatible name (`/` and `.` replaced by `_`)."
  [kw]
  (let [s (if (qualified-keyword? kw)
            (str (namespace kw) "_" (name kw))
            (name kw))]
    (str/replace s #"[^A-Za-z0-9_]" "_")))

(defn- event-tool-name
  "Returns the Anthropic-tool name string for a chart event keyword."
  [event-kw]
  (str event-tool-prefix (kw->anthropic-name event-kw)))

;; ---------------------------------------------------------------------------
;; Tool definitions sent to the LLM
;; ---------------------------------------------------------------------------

(defn- resolve-real-tools
  "Decide which real tools this conversation sees.

   `selector` may be:
     * `nil` / absent     — expose every tool in the registry (default).
     * a collection/set   — whitelist by keyword. Unknown keywords throw.

   Returns `[anthropic-tool-defs ^Map name->tool-kw]`."
  [registry selector]
  (let [tools (cond
                ;; Default: every tool in the registry. Sorted by tool-name
                ;; for determinism (Anthropic doesn't care, but our transcripts
                ;; and tests do).
                (nil? selector)
                (tp/all-tools registry)

                :else
                (mapv (fn [kw]
                        (or (tp/lookup registry kw)
                            (throw (ex-info (str "Unknown tool: " kw) {:tool kw}))))
                      selector))]
    (reduce
     (fn [[defs index] tool]
       (let [d (tp/tool->anthropic-tool-def tool)]
         [(conj defs d) (assoc index (:name d) (tp/tool-name tool))]))
     [[] {}]
     tools)))

(defn- event-tool-defs
  "Returns `[anthropic-tool-defs name->entry]` for `:allowed-events` declarations.
   Each `entry` is the original allowed-event map (with `:event`, `:data-schema`, ...)."
  [allowed-events]
  (reduce
   (fn [[defs index] {:keys [event description data-schema] :as entry}]
     (let [tname (event-tool-name event)
           def   {:name         tname
                  :description  (or description (str "Fire chart event " event))
                  :input-schema (llm-types/malli->json-schema (or data-schema [:map]))}]
       [(conj defs def) (assoc index tname entry)]))
   [[] {}]
   allowed-events))

;; ---------------------------------------------------------------------------
;; Worker state and helpers
;; ---------------------------------------------------------------------------

(defn- now-ms [] (System/currentTimeMillis))

(defn- transition-state!
  "CAS-style transition of the worker state atom: set to `new-state` only if the
   current state is NOT `:dying`. Returns the post-update value. This protects
   `stop-worker-entry!`'s `:dying` write from being trampled by the worker's
   own self-state-writes (e.g. transitioning to `:awaiting-user` after `:end_turn`)."
  [state-atom new-state]
  (swap! state-atom (fn [s] (if (= :dying s) :dying new-state))))

(defn- transcript! [transcript-fn ev]
  (try (transcript-fn ev) (catch Throwable _ nil)))

(def ^:const transcript-block-cap
  "Per-content-block byte cap for transcript events (text/thinking/tool-result
   strings). Larger payloads are truncated with a marker so JSONL stays sane;
   full content remains available to the LLM in the live conversation buffer."
  8192)

(def ^:const transcript-truncate-marker "…(truncated)")

(defn- truncate-for-transcript
  "Truncate `s` at `transcript-block-cap` bytes (utf-8 approximated as char
   count), appending a marker on overflow. Returns nil/short strings unchanged."
  [s]
  (let [s (when (some? s) (str s))]
    (cond
      (nil? s) nil
      (<= (count s) transcript-block-cap) s
      :else (str (subs s 0 transcript-block-cap) transcript-truncate-marker))))

(defn- ->transcript-content-block
  "Coerce one assistant content block into a transcript-safe map. Text and
   thinking strings are capped; tool_use carries `:id :name :input`."
  [b]
  (case (:type b)
    :text     {:type :text     :text (truncate-for-transcript (:text b))}
    :thinking {:type :thinking :thinking (truncate-for-transcript (:thinking b))}
    :tool_use {:type :tool_use :id (:id b) :name (:name b) :input (:input b)}
    {:type (:type b)}))

(defn- trailing-user-blocks
  "Return the content blocks of the trailing user message in `messages`, or
   `[]` if the last message isn't a user turn. Each block's content text is
   truncated for transcript-safety."
  [messages]
  (let [last-msg (peek (vec messages))]
    (if (= :user (:role last-msg))
      (mapv (fn [b]
              (case (:type b)
                :text        {:type :text :text (truncate-for-transcript (:text b))}
                :tool_result {:type :tool_result :tool_use_id (:tool_use_id b)
                              :is-error (boolean (:is-error b))
                              :content  (truncate-for-transcript (:content b))}
                {:type (:type b)}))
            (or (:content last-msg) []))
      [])))

(defn- post-event-to-parent!
  "Send a chart event back to the parent session."
  [{:keys [env queue parent-session-id invokeid]} event data]
  (sp/send! queue env
            {:target            parent-session-id
             :source-session-id parent-session-id
             :sendid            (str parent-session-id "." invokeid "." (name event))
             :invokeid          invokeid
             :event             event
             :data              data}))

(defn- humanize-malli-errors [schema input]
  (-> (m/explain schema input) me/humanize pr-str))

(defn- find-tool-uses [content-blocks]
  (filterv #(= :tool_use (:type %)) content-blocks))

(defn- assistant-message
  "Build an :assistant role message from a response's :content blocks."
  [content-blocks]
  {:role :assistant :content (vec content-blocks)})

(defn- user-tool-results-message
  "Build a :user role message carrying a vector of tool_result blocks."
  [results]
  {:role :user :content (vec results)})

(defn- text-user-message [text]
  {:role :user :content [{:type :text :text text}]})

(defn- build-request
  "Assemble a `escapement.llm.types/Request` map. Caller may supply any of the
   common Anthropic Messages-API knobs and they will be passed through to the
   backend; missing keys are simply not put on the wire.

   Recognized keys (all optional unless noted):

     * `:system` — system prompt string
     * `:messages` — required; the running conversation
     * `:tools` — vector of tool definitions
     * `:model` — model id string (e.g. `\"claude-opus-4-7\"`)
     * `:max-tokens` — int output cap. NOT a chart param: the invocation
                       supplies the resolved model's catalog
                       `max-output-tokens` (models-api.json `limit.output`).
                       Falls back to the backend's wire default for models
                       the catalog doesn't know.
     * `:temperature` — number in (0,1]
     * `:top-p` — number in (0,1]
     * `:top-k` — pos-int
     * `:stop-sequences` — vector of strings
     * `:thinking` — `{:type :enabled :budget-tokens N}` to turn on
                     extended thinking. Requires `:max-tokens` > `N`.
     * `:tool-choice` — `:auto` | `:any` | `:none` | `{:type :tool :name \"...\"}`
     * `:metadata` — `{:user-id \"...\"}`
     * `:auto-cache?` — boolean, default `true`. When true, fills in
       `:system-cache-control` and `:tools-cache-control` with
       `{:type :ephemeral}` if the caller didn't set them. Set to `false`
       to fully disable prompt-cache markers (use this for guaranteed
       single-turn states where the cache-write surcharge has no payoff).
     * `:system-cache-control` — Anthropic prompt-cache marker on the system
       block. Defaulted to `{:type :ephemeral}` via `:auto-cache?`; pass a
       map (e.g. `{:type :ephemeral :ttl :1h}`) to override or `false` to
       disable just this marker.
     * `:tools-cache-control` — Same shape; applies to the LAST entry in
       `:tools` so the prefix-through-end of `tools` is cached. Defaulted
       to `{:type :ephemeral}` via `:auto-cache?`; pass `false` to disable.
     * `:conv-id` — conversation correlation id; used as prompt cache key by openai-codex (string/keyword/uuid)"
  [{:keys [system messages tools model max-tokens conv-id
           temperature top-p top-k stop-sequences thinking tool-choice metadata
           system-cache-control tools-cache-control auto-cache?]
    :or   {auto-cache? true}}]
  ;; Auto-cache defaulting: an absent (== `nil`) cache-control marker
  ;; becomes ephemeral when :auto-cache? is true. Anthropic ignores
  ;; cache_control on prompts below ~1024 tokens, so the default is free
  ;; for small prompts and a clear win for any multi-turn state with a
  ;; substantial prefix. Pass `:auto-cache? false` to fully opt out, or
  ;; pass an explicit map / `false` on either individual key.
  (let [system-cache-control (cond
                               (false? system-cache-control)         nil
                               (some? system-cache-control)          system-cache-control
                               auto-cache?                           {:type :ephemeral}
                               :else                                 nil)
        tools-cache-control  (cond
                               (false? tools-cache-control)          nil
                               (some? tools-cache-control)           tools-cache-control
                               auto-cache?                           {:type :ephemeral}
                               :else                                 nil)]
    ;; Note: :model is omitted from the Request when the caller didn't supply
    ;; one. The backend's `send-turn` fills it from its configured
    ;; `:default-model` before schema validation. That way the chart can simply
    ;; not set :model and the runner's chosen default wins, instead of every
    ;; chart silently locking onto a stale hardcoded model.
    (cond-> {:messages messages
             :tools    (if (and (seq tools) tools-cache-control)
                         ;; Anthropic caches the PREFIX up through the last
                         ;; cache_control marker. Stamping the last tool with
                         ;; the marker therefore caches all tool defs.
                         (-> (vec tools)
                             (update (dec (count tools))
                                     assoc :cache-control tools-cache-control))
                         tools)}
      model                (assoc :model model)
      system               (assoc :system system)
      max-tokens           (assoc :max-tokens max-tokens)
      system-cache-control (assoc :system-cache-control system-cache-control)
      (some? temperature)  (assoc :temperature temperature)
      (some? top-p)        (assoc :top-p top-p)
      (some? top-k)        (assoc :top-k top-k)
      (seq stop-sequences) (assoc :stop-sequences (vec stop-sequences))
      thinking             (assoc :thinking thinking)
      (some? tool-choice)  (assoc :tool-choice tool-choice)
      (seq metadata)       (assoc :metadata metadata)
      conv-id              (assoc :conversation/id conv-id))))

(defn effective-max-tokens
  "The per-turn output cap for `model`, taken purely from the catalog's
   `max-output-tokens` (sourced from models-api.json `limit.output`).
   `max_tokens` is an API/model fact, not a chart concern: charts never set
   it. Returns nil for models the catalog doesn't know, in which case the
   backend's own hard default applies."
  [model]
  (some-> model catalog/max-output-tokens))

;; ---------------------------------------------------------------------------
;; Resilience: transient-error retry + max_tokens continuation
;; ---------------------------------------------------------------------------

(def ^:private default-resilience
  "Applied when a chart omits (or partially specifies) `:resilience`. Recovery
   is ON by default and per-state tunable; `:max-retries 0` disables retry.
   There is intentionally no continuation knob — `:max_tokens` truncation is
   always continued (see `drive-turn!`)."
  {:max-retries 3 :backoff-ms 500})

(def ^:private transient-error-categories
  "Backend error categories that warrant a bounded automatic retry of the same
   model. The remaining categories (`:auth` `:invalid-request`
   `:context-length`) are terminal: they fail fast and are never retried, so a
   bad key or oversized prompt cannot burn quota in a retry loop."
  #{:rate-limited :overloaded :timeout :transport})

(defn- params->resilience [params]
  (merge default-resilience (:resilience params)))

(defn- sleep-unless-dying!
  "Sleep up to `ms`, but wake early (and stop) if the worker is told to die.
   Sliced so a stop signal is honored promptly during backoff."
  [ms worker-state]
  (let [deadline (+ (now-ms) (long ms))]
    (loop []
      (let [left (- deadline (now-ms))]
        (when (and (pos? left) (not= :dying @worker-state))
          (Thread/sleep (long (min 100 left)))
          (recur))))))

(defn- backoff-delay-ms
  "Exponential backoff for retry `attempt` (0-based) off `base` ms, honoring an
   explicit `:retry-after-ms` from the throwable's ex-data (e.g. a 429
   Retry-After) when present."
  [base attempt ^Throwable t]
  (let [ra (:retry-after-ms (ex-data t))]
    (if (and (number? ra) (not (neg? ra)))
      (long ra)
      (long (* (long base) (Math/pow 2 attempt))))))

(defn- merge-with-usage
  "Sum numeric usage fields across continuation segments; non-numeric fields
   take the latest non-nil value."
  [a b]
  (merge-with (fn [x y]
                (if (and (number? x) (number? y)) (+ x y) (or y x)))
              (or a {}) (or b {})))

(defn- merge-segment-content
  "Append continuation blocks `more` onto accumulated `acc`, merging the
   boundary when both sides are `:text` (a truncation mid-prose) so the
   stitched message reads as one coherent block."
  [acc more]
  (cond
    (empty? acc)  (vec more)
    (empty? more) (vec acc)
    (and (= :text (:type (peek acc)))
         (= :text (:type (first more))))
    (-> (vec (pop (vec acc)))
        (conj {:type :text
               :text (str (:text (peek (vec acc))) (:text (first more)))})
        (into (rest more)))
    :else (into (vec acc) (vec more))))

;; ---------------------------------------------------------------------------
;; Worker loop
;; ---------------------------------------------------------------------------

(defn- handle-tool-use-block
  "Process a single tool_use block. Returns a map:
     `{:result-block {tool_result-block}
      :retry-bumped <map of id->count updates>
      :fatal? <bool>
      :error-data <map when fatal>}`

   `parent-ctx` is the worker's context (env/queue/parent-session-id/invokeid).
   `state*` holds the per-tool_use_id retry counters."
  [{:keys [tool-registry name->tool-kw name->event-entry retry-counts transcript-fn]
    :as   ctx} parent-ctx block]
  (let [{:keys [id name input]} block
        retries (get @retry-counts id 0)
        post-tool-result!
        (fn [tool-label is-error result-content]
          (when transcript-fn
            (transcript! transcript-fn
                         {:event :llm/tool-result :ts (now-ms)
                          :data  {:tool_use_id     id
                                  :tool            tool-label
                                  :input           input
                                  :is-error        (boolean is-error)
                                  :content-preview (truncate-for-transcript result-content)
                                  :invokeid        (:invokeid parent-ctx)}})))]
    (cond
      ;; Real tool
      (contains? name->tool-kw name)
      (let [tool-kw (get name->tool-kw name)
            {:keys [result is-error]} (tp/dispatch tool-registry tool-kw (or input {}))]
        ;; `tp/dispatch` validates; treat validation failures as bad-tool-use for retry semantics.
        (if (and is-error (str/includes? (or result "") "failed validation"))
          (do
            (post-tool-result! tool-kw true result)
            (if (>= retries 1)
              {:fatal?     true
               :error-data {:reason :tool-validation-failed
                            :tool   tool-kw
                            :errors result
                            :tool_use_id id}
               :result-block {:type :tool_result :tool_use_id id
                              :content result :is-error true}}
              (do
                (swap! retry-counts assoc id (inc retries))
                {:result-block {:type :tool_result :tool_use_id id
                                :content result :is-error true}})))
          (do
            (post-tool-result! tool-kw is-error (or result ""))
            {:result-block {:type :tool_result :tool_use_id id
                            :content (or result "") :is-error (boolean is-error)}})))

      ;; Event tool
      (contains? name->event-entry name)
      (let [{:keys [event data-schema]} (get name->event-entry name)
            schema (or data-schema [:map])]
        (if (m/validate schema (or input {}))
          (do
            (post-event-to-parent! parent-ctx event (or input {}))
            (post-tool-result! event false "ok")
            {:result-block {:type :tool_result :tool_use_id id :content "ok"}})
          (let [err (humanize-malli-errors schema input)]
            (post-tool-result! event true err)
            (if (>= retries 1)
              {:fatal?     true
               :error-data {:reason :tool-validation-failed
                            :tool   event
                            :errors err
                            :tool_use_id id}
               :result-block {:type :tool_result :tool_use_id id
                              :content err :is-error true}}
              (do
                (swap! retry-counts assoc id (inc retries))
                {:result-block {:type :tool_result :tool_use_id id
                                :content err :is-error true}})))))

      :else
      (let [msg (str "Unknown tool: " name)]
        (post-tool-result! name true msg)
        (if (>= retries 1)
          {:fatal?     true
           :error-data {:reason :unknown-tool :tool name :tool_use_id id}
           :result-block {:type :tool_result :tool_use_id id
                          :content msg :is-error true}}
          (do
            (swap! retry-counts assoc id (inc retries))
            {:result-block {:type :tool_result :tool_use_id id
                            :content msg :is-error true}}))))))

(defn ->id-str
  "Normalize an invokeid to its canonical string form. Chart-authors may write
   `:id :researcher` (keyword) or `:id \"researcher\"` (string); both should
   compare equal everywhere we touch them (target routing, :from labels,
   artifact filenames). Public so chart-author helpers can normalize too."
  [x]
  (cond
    (keyword? x) (name x)
    (nil? x)     nil
    :else        (str x)))

(defn- error-event
  "Compose the SCXML-style error event name from a `:reason` keyword.
   `:reason :backend` → `:error.llm.backend`."
  [reason]
  (keyword (str "error.llm." (name reason))))

(defn- params->policy
  "The declarative model policy from conversation `params`.

   `:model-policy` is a `catalog/satisfies-policy?` map (`:require`/`:min`
   /`:max` over any objective or subjective info key — including
   `:intelligence`, which is just one ratings key, not a special case).
   Returns nil when no policy clause is expressed."
  [params]
  (let [pol (:model-policy params)]
    (when (or (seq (:require pol)) (seq (:min pol)) (seq (:max pol)))
      pol)))

(defn- candidate-models
  "Decide the ordered list of models to try for the next turn.

   Resolution order:
     1. `params :models`    — explicit ordered preference (no auto-substitution)
     2. `params :model`     — explicit single pick (no fallback)
     3. `default-models`    — processor-level auto-detected fallback list. When
                              a model policy is expressed (`params
                              :model-policy`) the list is filtered to
                              entries satisfying it via
                              `escapement.llm.catalog/satisfies-policy?`.
                              If the filter empties the list, the
                              unfiltered default-models is used so the
                              conversation still runs (the gap is surfaced
                              as an `:llm/model-policy-empty` transcript
                              event by the caller).
     4. `[nil]`             — let the backend pick its own default.

   Cases 1 and 2 are honored verbatim: when the user names a model, we never
   silently switch.

   Always filters out anything marked `:down` in `model-status` AFTER the
   resolution above; `nil` (case 4) is preserved unconditionally."
  [params default-models model-status]
  (let [policy    (params->policy params)
        defaults  (if (and policy (seq default-models))
                    (let [filtered (filterv #(catalog/satisfies-policy? % policy) default-models)]
                      (if (seq filtered) filtered default-models))
                    default-models)
        requested (cond
                    (seq (:models params)) (vec (:models params))
                    (:model params)        [(:model params)]
                    (seq defaults)         (vec defaults)
                    :else                  [nil])
        status    @model-status
        live      (filterv (fn [m] (or (nil? m) (not= :down (get status m)))) requested)]
    (if (seq live) live requested)))

(defn- throwable->details [^Throwable t]
  (let [message (or (.getMessage t)
                    (some-> (ex-cause t) .getMessage)
                    (.toString t))]
    {:message message
     :class   (-> t class .getName)
     :ex-data (when-let [d (ex-data t)]
                (try (pr-str d)
                     (catch Throwable _ "<unprintable>")))
     :stack   (->> (.getStackTrace t)
                   (take 6)
                   (mapv #(str (.getClassName ^StackTraceElement %)
                               "." (.getMethodName ^StackTraceElement %)
                               "(" (.getFileName ^StackTraceElement %)
                               ":" (.getLineNumber ^StackTraceElement %) ")")))}))

(defn- try-models!
  "Issue the LLM call, falling back across `:models` in order. On every
   backend throw we mark that model `:down` in the shared `model-status`
   atom and try the next candidate. Returns one of:

     {:ok response}         — successful turn
     {:interrupted t}       — worker is dying / thread was interrupted
     {:exhausted attempts}  — every candidate failed; caller should post
                              :error.llm.backend. `attempts` is a vector of
                              `{:model :error}` pairs (oldest first)."
  [{:keys [backend transcript-fn worker-state model-status default-models parent-ctx]} params messages tools]
  (let [{:keys [max-retries backoff-ms]} (params->resilience params)
        policy        (params->policy params)
        auto-fallback? (and (not (seq (:models params)))
                            (nil? (:model params)))
        _              (when (and auto-fallback? policy (seq default-models)
                                  (not-any? #(catalog/satisfies-policy? % policy) default-models))
                         (transcript! transcript-fn
                                      {:event :llm/model-policy-empty
                                       :ts    (now-ms)
                                       :data  {:policy         policy
                                               :default-models (vec default-models)}}))
        candidates    (candidate-models params default-models model-status)]
    (loop [[m & more] candidates
           attempts   []]
      (let [request  (build-request
                      {:system               (:system params)
                       :messages             messages
                       :tools                tools
                       :model                m
                       :max-tokens           (effective-max-tokens m)
                       :temperature          (:temperature params)
                       :top-p                (:top-p params)
                       :top-k                (:top-k params)
                       :stop-sequences       (:stop-sequences params)
                       :thinking             (:thinking params)
                       :tool-choice          (:tool-choice params)
                       :metadata             (:metadata params)
                       :system-cache-control (:system-cache-control params)
                       :tools-cache-control  (:tools-cache-control params)
                       :auto-cache?          (get params :auto-cache? true)
                       :conv-id              (:conversation/id params)})
            _        (transcript! transcript-fn
                                  {:event :llm/request :ts (now-ms)
                                   :data  (cond-> {:n-messages (count (:messages request))
                                                   :user-blocks (trailing-user-blocks messages)
                                                   :system-preview (truncate-for-transcript (:system params))
                                                   :invokeid (:invokeid parent-ctx)}
                                            m (assoc :model m))})
            on-delta (when (:stream? params)
                       (fn [d]
                         (transcript! transcript-fn
                                      {:event :llm/delta :ts (now-ms)
                                       :data  (assoc d
                                                     :model    m
                                                     :invokeid (:invokeid parent-ctx))})))
            ;; Bounded same-model retry for *transient* categories
            ;; (rate-limited/overloaded/timeout/transport) with exponential
            ;; backoff before falling back to the next candidate. Terminal
            ;; categories (auth/invalid-request/context-length) and
            ;; uncategorized throws are NOT retried here — they fall straight
            ;; through to model fallback / :exhausted.
            response (loop [retry 0]
                       (let [r (try (llm/send-turn* backend request on-delta)
                                     (catch Throwable t {:_throw t}))
                             t (:_throw r)
                             cat (when t (llm/error-category t))]
                         (if (and t
                                  (contains? transient-error-categories cat)
                                  (< retry (long max-retries))
                                  (not (instance? InterruptedException t))
                                  (not (instance? InterruptedException (ex-cause t)))
                                  (not= :dying @worker-state))
                           (do
                             (transcript! transcript-fn
                                          {:event :llm/retry :ts (now-ms)
                                           :data  {:model     m
                                                   :category  cat
                                                   :attempt   (inc retry)
                                                   :max-retries max-retries
                                                   :invokeid  (:invokeid parent-ctx)}})
                             (sleep-unless-dying!
                              (backoff-delay-ms backoff-ms retry t) worker-state)
                             (recur (inc retry)))
                           r)))]
        (cond
          (and (:_throw response)
               (or (instance? InterruptedException (:_throw response))
                   (instance? InterruptedException (ex-cause (:_throw response)))
                   (= :dying @worker-state)))
          {:interrupted (:_throw response)}

          (:_throw response)
          (let [^Throwable t (:_throw response)
                details      (throwable->details t)
                category     (llm/error-category t)
                ;; Only mark a real model id down. nil (backend's default
                ;; pick) is not a routable identifier.
                _            (when m (swap! model-status assoc m :down))
                _            (transcript! transcript-fn
                                          {:event :llm/model-down :ts (now-ms)
                                           :data  {:model m
                                                   :message (:message details)
                                                   :category category
                                                   :remaining (vec more)}})
                attempts'    (conj attempts {:model m :error (select-keys details [:message :class])})]
            (if (seq more)
              (recur more attempts')
              {:exhausted attempts'
               :last-throwable t}))

          :else
          {:ok response :model-used m})))))

(defn- drive-turn!
  "Issue one logical assistant turn. A `stop_reason :max_tokens` means the API
   forcibly cut the model off mid-message — it is NOT a finished turn. We
   transparently request continuation (the partial assistant content is used as
   prefill but is NOT persisted in the conversation) and keep going until a
   terminal stop (`:end_turn` / `:tool_use` / `:stop_sequence` / …). Only the
   merged terminal Response is returned, so callers never see a truncated
   message and no tool runs until the model is genuinely done.

   Continuation is unbounded by design: it is just \"finish reading the
   message\". The sole guard is forward progress — if a continuation segment
   adds no new content (a stuck model), we stop with `{:no-progress response}`
   rather than loop and drain quota. Other `try-models!` shapes
   (`:interrupted` / `:exhausted`) pass straight through.

   Note: continuation prefill is the Anthropic-supported pattern for truncated
   *text*. A truncation landing inside a tool_use block cannot be reassembled
   from parsed content (its JSON args never parsed); such a segment yields no
   forward progress and is surfaced via `:no-progress` rather than a malformed
   tool call ever being dispatched."
  [{:keys [transcript-fn parent-ctx] :as ctx} params base-messages tools]
  (loop [acc-content nil
         acc-usage   {}
         seg         0]
    (let [msgs    (if (seq acc-content)
                    (conj (vec base-messages) (assistant-message acc-content))
                    (vec base-messages))
          outcome (try-models! ctx params msgs tools)]
      (if-not (:ok outcome)
        outcome
        (let [resp         (:ok outcome)
              {:keys [stop-reason content usage]} resp
              merged       (if acc-content
                             (merge-segment-content acc-content content)
                             (vec content))
              merged-usage (merge-with-usage acc-usage usage)
              resp'        (assoc resp :content merged :usage merged-usage)]
          (cond
            (not= :max_tokens stop-reason)
            {:ok resp' :model-used (:model-used outcome)}

            ;; truncated but the continuation added nothing new → stuck.
            (and acc-content (= merged (vec acc-content)))
            {:no-progress resp'}

            :else
            (do
              (transcript! transcript-fn
                           {:event :llm/continuation :ts (now-ms)
                            :data  {:segment  (inc seg)
                                    :blocks   (count content)
                                    :usage    (or usage {})
                                    :invokeid (:invokeid parent-ctx)}})
              (recur merged merged-usage (inc seg)))))))))

(defn- handle-running-turn!
  "Issue one LLM round-trip and process its response. Returns one of:
     :continue        — call `recur` in the outer loop
     :error-and-die   — error already posted; outer loop should `recur` (which
                        then hits the :dying branch)
     :idle            — :end_turn fired; outer loop should `recur` and park
                        in :awaiting-user."
  [{:keys [tool-registry transcript-fn
           name->tool-kw name->event-entry tool-defs
           worker-state messages-atom retry-counts
           params parent-ctx] :as ctx}
   post-error! on-end-turn-event]
  (let [outcome (drive-turn! ctx params @messages-atom tool-defs)]
    (cond
      (:no-progress outcome)
      (do
        (transcript! transcript-fn
                     {:event :llm/error :ts (now-ms)
                      :data  {:reason :unexpected-stop
                              :stop-reason :max_tokens
                              :detail :no-forward-progress}})
        (post-error! :unexpected-stop {:stop-reason :max_tokens
                                       :detail :no-forward-progress})
        (reset! worker-state :dying)
        :error-and-die)

      (:interrupted outcome)
      (do
        (transcript! transcript-fn
                     {:event :llm/worker-exit :ts (now-ms)
                      :data  {:reason :interrupted-mid-turn}})
        (reset! worker-state :dying)
        :error-and-die)

      (:exhausted outcome)
      (let [attempts (:exhausted outcome)
            last-t   ^Throwable (:last-throwable outcome)
            details  (throwable->details last-t)
            category (llm/error-category last-t)
            ;; Known category → reason is that category (yields
            ;; :error.llm.<category>). Uncategorized/unknown → :backend,
            ;; which preserves the legacy :error.llm.backend event exactly.
            reason   (if (contains? llm/error-categories category)
                       category
                       :backend)]
        (transcript! transcript-fn {:event :llm/error :ts (now-ms)
                                    :data  (assoc details
                                                  :reason   reason
                                                  :category category
                                                  :attempts attempts)})
        (post-error! reason (-> (select-keys details [:message :class])
                                (assoc :category category
                                       :attempts attempts)))
        (reset! worker-state :dying)
        :error-and-die)

      :else
      (let [response (:ok outcome)
            {:keys [stop-reason content usage model]} response
            ctx-window    (some-> model catalog/context-window)
            input-tokens  (:input-tokens usage)
            ctx-used-frac (when (and ctx-window input-tokens (pos? ctx-window))
                            (/ (double input-tokens) (double ctx-window)))]
        (transcript! transcript-fn
                     {:event :llm/response
                      :ts    (now-ms)
                      :data  (cond-> {:stop-reason stop-reason
                                      :n-blocks    (count content)
                                      :usage       (or usage {})
                                      :content     (mapv ->transcript-content-block content)
                                      :invokeid    (:invokeid parent-ctx)}
                               model         (assoc :model model)
                               ctx-window    (assoc :context-window ctx-window)
                               ctx-used-frac (assoc :context-used-frac
                                                    (Double/parseDouble (format "%.3f" ctx-used-frac))))})
        (when (and ctx-used-frac (>= ctx-used-frac 0.8))
          (transcript! transcript-fn
                       {:event :llm/context-warning
                        :ts    (now-ms)
                        :data  {:input-tokens   input-tokens
                                :context-window ctx-window
                                :used-frac      ctx-used-frac
                                :model          model}}))
        (swap! messages-atom conj (assistant-message content))
        (case stop-reason
          :end_turn
          (do
            ;; Carry the assistant's final text + invokeid so chart authors
            ;; can route the answer back to another invocation (advisor /
            ;; team pattern). Backward compatible: data was previously {}.
            (let [final-text (->> content
                                  (filter #(= :text (:type %)))
                                  (map :text)
                                  (apply str))]
              (post-event-to-parent! parent-ctx on-end-turn-event
                                     {:text final-text
                                      :from (->id-str (:invokeid parent-ctx))}))
            (transition-state! worker-state :awaiting-user)
            :idle)

          :tool_use
          (let [tool-use-blocks (find-tool-uses content)
                results         (atom [])
                fatal           (atom nil)]
            (doseq [b tool-use-blocks
                    :while (nil? @fatal)]
              (let [{:keys [result-block fatal? error-data]}
                    (handle-tool-use-block
                     {:tool-registry     tool-registry
                      :name->tool-kw     name->tool-kw
                      :name->event-entry name->event-entry
                      :retry-counts      retry-counts
                      :transcript-fn     transcript-fn}
                     parent-ctx b)]
                (swap! results conj result-block)
                (when fatal? (reset! fatal error-data))))
            (swap! messages-atom conj (user-tool-results-message @results))
            (if-let [err @fatal]
              (do
                (post-error! :tool-validation err)
                (reset! worker-state :dying)
                :error-and-die)
              :continue))

          ;; max_tokens / stop_sequence / pause_turn / refusal — anything else.
          (do
            (post-error! :unexpected-stop {:stop-reason stop-reason})
            (reset! worker-state :dying)
            :error-and-die))))))

(defn- run-worker!
  "Worker loop. Reads from `worker-state` atom and `user-msg-queue` for new user messages.
   Drives the LLM via `backend`, dispatches tools, posts events back to parent.

   Per-invocation budgets:
     :max-turns                    — maximum total LLM round-trips before the
                                     worker self-cancels with :error.llm.max-turns.
     :max-conversation-duration-ms — wall-clock budget (in ms) from worker
                                     start to a clean :end_turn. Self-cancels
                                     with :error.llm.timeout when exceeded.

   Streaming:
     :stream? — when true AND the backend implements
                `escapement.llm.protocol/StreamingLLMBackend`, incremental
                output is emitted as `:llm/delta` transcript events
                (`{:type :text-delta|:thinking-delta :text s :model :invokeid}`)
                while the turn is in flight. The final Response and all chart
                semantics are identical to a non-streamed turn; consumers
                relay deltas off the transcript tap. No-op if the backend
                lacks streaming support.

   Error events follow SCXML convention `:error.llm.<reason>`:
     :error.llm.backend          — backend call threw an UNCATEGORIZED
                                    throwable (HTTP / parse / etc.). This is
                                    the back-compat fallback: any throw that
                                    is not an `escapement.llm.protocol`
                                    categorized error still collapses here.
     :error.llm.rate-limited     — backend threw a categorized 429/rate-limit
     :error.llm.overloaded       — backend threw a categorized overload (529)
     :error.llm.auth             — backend threw a categorized auth failure
     :error.llm.invalid-request  — backend threw a categorized bad request
     :error.llm.context-length   — backend threw a categorized context/token
                                    length error
     :error.llm.timeout          — backend threw a categorized timeout, OR
                                    the :max-conversation-duration-ms budget
                                    was exceeded (both map here intentionally)
     :error.llm.transport        — backend threw a categorized transport error
     :error.llm.tool-validation  — tool/event-tool input failed schema twice
     :error.llm.unexpected-stop  — stop_reason other than :end_turn / :tool_use
     :error.llm.max-turns        — :max-turns budget exceeded
     :error.llm.worker-exception — uncaught throwable in the worker loop

   The categorized events above come from a backend throwing
   `(escapement.llm.protocol/llm-error category msg ...)`; consumers map
   `category` ∈ `protocol/error-categories` to `:error.llm.<category>`."
  [{:keys [backend tool-registry transcript-fn
           name->tool-kw name->event-entry tool-defs
           worker-state messages-atom user-msg-queue retry-counts
           params parent-ctx] :as ctx}]
  (let [{:keys [on-end-turn-event max-turns max-conversation-duration-ms]
         :or   {on-end-turn-event :llm.idle}} params
        started-at  (now-ms)
        turn-count  (atom 0)
        post-error! (fn [reason data]
                      (post-event-to-parent! parent-ctx (error-event reason)
                                             (assoc data :reason reason)))]
    (try
      (loop []
        (let [s @worker-state]
          (cond
            (= :dying s)
            (transcript! transcript-fn {:event :llm/worker-exit :ts (now-ms) :data {:reason :stopped}})

            (= :awaiting-user s)
            ;; park until a user message arrives or stop is signaled
            (let [msg (.poll ^ArrayBlockingQueue user-msg-queue 200 TimeUnit/MILLISECONDS)]
              (cond
                msg
                (do
                  (swap! messages-atom conj (text-user-message msg))
                  (transition-state! worker-state :running)
                  (transcript! transcript-fn {:event :llm/user-message :ts (now-ms)
                                              :data {:text msg :invokeid (:invokeid parent-ctx)}})
                  (recur))
                (= :dying @worker-state) (recur)
                :else (recur)))

            (= :running s)
            (cond
              ;; Budget checks BEFORE issuing the next call. Fire the canonical
              ;; error and die — chart authors transition on :error.llm.*.
              (and max-turns (>= @turn-count (long max-turns)))
              (do
                (transcript! transcript-fn
                             {:event :llm/error :ts (now-ms)
                              :data  {:reason :max-turns :limit max-turns}})
                (post-error! :max-turns {:limit max-turns :turns @turn-count})
                (reset! worker-state :dying)
                (recur))

              (and max-conversation-duration-ms
                   (>= (- (now-ms) started-at) (long max-conversation-duration-ms)))
              (let [elapsed (- (now-ms) started-at)]
                (transcript! transcript-fn
                             {:event :llm/error :ts (now-ms)
                              :data  {:reason :timeout
                                      :elapsed-ms elapsed
                                      :limit-ms max-conversation-duration-ms}})
                (post-error! :timeout {:elapsed-ms elapsed
                                       :limit-ms   max-conversation-duration-ms})
                (reset! worker-state :dying)
                (recur))

              :else
              (do
                (swap! turn-count inc)
                (case (handle-running-turn! ctx post-error! on-end-turn-event)
                  ;; All three outcomes just re-enter the loop; the inner
                  ;; helper already mutated worker-state and posted events.
                  :continue      (recur)
                  :idle          (recur)
                  :error-and-die (recur))))

            :else
            (recur))))
      (catch InterruptedException _
        (transcript! transcript-fn {:event :llm/worker-exit :ts (now-ms) :data {:reason :interrupted}}))
      (catch Throwable t
        (transcript! transcript-fn {:event :llm/worker-exit :ts (now-ms)
                                    :data  {:reason :exception
                                            :message (.getMessage t)}})
        (try
          (post-error! :worker-exception {:message (.getMessage t)})
          (catch Throwable _ nil))))))

;; ---------------------------------------------------------------------------
;; Processor
;; ---------------------------------------------------------------------------

(defn- worker-key [parent-session-id invokeid]
  [parent-session-id invokeid])

(defn- stop-worker-entry! [entry]
  (when entry
    (reset! (:worker-state entry) :dying)
    (try
      (when-let [^Thread t (:thread entry)] (.interrupt t))
      (catch Throwable _ nil))))

(defrecord LlmConversationProcessor [backend tool-registry transcript-fn workers model-status default-models]
  sp/InvocationProcessor
  (supports-invocation-type? [_ typ]
    (= typ :llm-conversation))

  (start-invocation! [_this env {:keys [invokeid params]}]
    (let [parent-session-id (env-ns/session-id env)
          k                 (worker-key parent-session-id invokeid)
          ;; Idempotency: signal any pre-existing worker for this key to die
          ;; gracefully (it polls `:worker-state` between turns). We deliberately
          ;; DO NOT `.interrupt` the thread here — doing so would also abort an
          ;; HTTP call already in flight on the NEW thread when start-invocation!
          ;; is invoked twice in quick succession (e.g. during a state-transition
          ;; race), surfacing as `InterruptedException` on the legitimate new
          ;; request. Letting the old worker die at its next loop iteration is
          ;; the safe choice; it is a daemon thread and will exit naturally.
          _                 (when-let [old (get @workers k)]
                              (reset! (:worker-state old) :dying))
          queue             (::sc/event-queue env)
          {:keys [real-tools allowed-events initial-user-message]} params
          [real-defs name->tool-kw]   (resolve-real-tools tool-registry real-tools)
          [event-defs name->event]    (event-tool-defs (or allowed-events []))
          tool-defs                   (into [] (concat real-defs event-defs))
          initial-msgs                (if initial-user-message
                                        [(text-user-message initial-user-message)]
                                        [])
          messages-atom               (atom initial-msgs)
          worker-state                (atom (if initial-user-message :running :awaiting-user))
          user-msg-queue              (ArrayBlockingQueue. 256)
          retry-counts                (atom {})
          parent-ctx                  {:env env :queue queue
                                       :parent-session-id parent-session-id
                                       :invokeid invokeid}
          ctx                         {:backend            backend
                                       :tool-registry      tool-registry
                                       :transcript-fn      (or transcript-fn (fn [_] nil))
                                       :name->tool-kw      name->tool-kw
                                       :name->event-entry  name->event
                                       :tool-defs          tool-defs
                                       :worker-state       worker-state
                                       :messages-atom      messages-atom
                                       :user-msg-queue     user-msg-queue
                                       :retry-counts       retry-counts
                                       :model-status       model-status
                                       :default-models     default-models
                                       :params             params
                                       :parent-ctx         parent-ctx}
          runnable                    (fn [] (run-worker! ctx))
          ^Thread thread              (doto (Thread. ^Runnable runnable
                                                     (str "llm-conv-" parent-session-id "-" invokeid))
                                        (.setDaemon true))]
      (swap! workers assoc k
             {:thread         thread
              :worker-state   worker-state
              :messages-atom  messages-atom
              :user-msg-queue user-msg-queue
              :retry-counts   retry-counts
              :params         params})
      (transcript! (or transcript-fn (fn [_] nil))
                   {:event :llm/start :ts (now-ms)
                    :data  {:invokeid invokeid :session-id parent-session-id}})
      (.start thread)
      true))

  (stop-invocation! [_this env {:keys [invokeid]}]
    (let [parent-session-id (env-ns/session-id env)
          k                 (worker-key parent-session-id invokeid)
          entry             (get @workers k)]
      (when entry
        (stop-worker-entry! entry)
        (swap! workers dissoc k))
      true))

  (forward-event! [_this env {:keys [invokeid event]}]
    (let [parent-session-id (env-ns/session-id env)
          k                 (worker-key parent-session-id invokeid)
          entry             (get @workers k)
          ev-name           (cond
                              (keyword? event) event
                              (map? event)     (or (:event event) (:name event))
                              :else            nil)
          ev-data           (when (map? event) (:data event))
          ;; Target filter: when :target is present in the event data, only the
          ;; invocation whose invokeid matches receives the message. Without a
          ;; :target, ALL llm-conversation invocations get it (today's behavior
          ;; — backward-compatible default). Comparison is on the canonical
          ;; string form so keyword/string ids match transparently.
          target            (:target ev-data)
          accept?           (or (nil? target)
                                (= (->id-str target) (->id-str invokeid)))]
      (when (and entry accept? (= :llm.user-message ev-name))
        (let [text (:text ev-data)]
          (when (string? text)
            ;; Just enqueue; the worker's :awaiting-user branch will pick it up,
            ;; append it to messages, and switch to :running itself.
            (.offer ^ArrayBlockingQueue (:user-msg-queue entry) text))))
      true)))

;; ---------------------------------------------------------------------------
;; Constructor
;; ---------------------------------------------------------------------------

(defn new-processor
  "Create a new `LlmConversationProcessor`.

  `opts`:
   * `:backend` (required) — an `LLMBackend` instance
   * `:tool-registry` (required) — a `Tool` registry atom (from `tools.protocol/new-registry`)
   * `:transcript-fn` (optional) — `(fn [event-map] ...)` for observability; default no-op
   * `:default-models` (optional) — ordered vector of model id strings to try
     when a chart's invocation params don't specify `:model`/`:models`. Used
     for cross-backend fallback when only some credentials are healthy."
  [{:keys [backend tool-registry transcript-fn default-models]}]
  (assert backend "backend is required")
  (assert tool-registry "tool-registry is required")
  (->LlmConversationProcessor backend tool-registry (or transcript-fn (fn [_] nil))
                              (atom {}) (atom {}) (vec default-models)))

(>defn active-worker-count
       "Returns the number of workers whose state is not `:dying`. Used by the runner
        to decide when it's safe to terminate."
       [processor]
       [any? => :int]
       (reduce-kv
        (fn [n _ entry]
          (let [s (some-> (:worker-state entry) deref)]
            (if (or (nil? s) (= :dying s)) n (inc n))))
        0
        @(:workers processor)))

(>defn worker-info
       "Return a snapshot of the worker entry for `[session-id invokeid]`, for tests/debug."
       [processor session-id invokeid]
       [any? any? any? => [:maybe :map]]
       (some-> (get @(:workers processor) [session-id invokeid])
               (select-keys [:worker-state :messages-atom :retry-counts])))
