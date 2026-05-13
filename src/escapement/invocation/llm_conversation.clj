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
    LLM gets one corrective retry; a second failure aborts with `:on-error-event`.

  When the assistant returns `:end_turn`, the worker fires `:on-end-turn-event` to the
  parent and parks until either `:llm.user-message` arrives via `forward-event!` (continues
  the conversation with a new user message) or the invocation is stopped.

  See `plan.md` for the design and `SPIKE_FINDINGS.md` for library errata."
  (:require
   [clojure.string :as str]
   [escapement.llm.models :as models]
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
     * `:max-tokens` — int; default 4096 at the backend if absent
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
     * `:conv-id` — claude-p `--resume` correlation id (string/keyword/uuid)"
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
  [{:keys [tool-registry name->tool-kw name->event-entry retry-counts]
    :as   ctx} parent-ctx block]
  (let [{:keys [id name input]} block
        retries (get @retry-counts id 0)]
    (cond
      ;; Real tool
      (contains? name->tool-kw name)
      (let [tool-kw (get name->tool-kw name)
            {:keys [result is-error]} (tp/dispatch tool-registry tool-kw (or input {}))]
        ;; `tp/dispatch` validates; treat validation failures as bad-tool-use for retry semantics.
        (if (and is-error (str/includes? (or result "") "failed validation"))
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
                              :content result :is-error true}}))
          {:result-block {:type :tool_result :tool_use_id id
                          :content (or result "") :is-error (boolean is-error)}}))

      ;; Event tool
      (contains? name->event-entry name)
      (let [{:keys [event data-schema]} (get name->event-entry name)
            schema (or data-schema [:map])]
        (if (m/validate schema (or input {}))
          (do
            (post-event-to-parent! parent-ctx event (or input {}))
            {:result-block {:type :tool_result :tool_use_id id :content "ok"}})
          (let [err (humanize-malli-errors schema input)]
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
      (if (>= retries 1)
        {:fatal?     true
         :error-data {:reason :unknown-tool :tool name :tool_use_id id}
         :result-block {:type :tool_result :tool_use_id id
                        :content (str "Unknown tool: " name) :is-error true}}
        (do
          (swap! retry-counts assoc id (inc retries))
          {:result-block {:type :tool_result :tool_use_id id
                          :content (str "Unknown tool: " name) :is-error true}})))))

(defn- run-worker!
  "Worker loop. Reads from `worker-state` atom and `user-msg-queue` for new user messages.
   Drives the LLM via `backend`, dispatches tools, posts events back to parent."
  [{:keys [backend tool-registry transcript-fn
           name->tool-kw name->event-entry tool-defs
           worker-state messages-atom user-msg-queue retry-counts
           params parent-ctx] :as ctx}]
  (let [{:keys [on-error-event on-end-turn-event]
         :or   {on-error-event :llm.error on-end-turn-event :llm.idle}} params]
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
                  (transcript! transcript-fn {:event :llm/user-message :ts (now-ms) :data {:text msg}})
                  (recur))
                (= :dying @worker-state) (recur)
                :else (recur)))

            (= :running s)
            (let [request (build-request {:system               (:system params)
                                          :messages             @messages-atom
                                          :tools                tool-defs
                                          :model                (:model params)
                                          :max-tokens           (:max-tokens params)
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
                  _       (transcript! transcript-fn {:event :llm/request :ts (now-ms)
                                                      :data  {:n-messages (count (:messages request))}})
                  response (try
                             (llm/send-turn backend request)
                             (catch Throwable t
                               {:_throw t}))]
              (cond
                ;; Clean shutdown: the chart left this state and our thread was
                ;; interrupted mid-HTTP. Not an error — exit quietly.
                (and (:_throw response)
                     (or (instance? InterruptedException (:_throw response))
                         (instance? InterruptedException (ex-cause (:_throw response)))
                         (= :dying @worker-state)))
                (do
                  (transcript! transcript-fn {:event :llm/worker-exit :ts (now-ms)
                                              :data  {:reason :interrupted-mid-turn}})
                  (reset! worker-state :dying)
                  (recur))

                (:_throw response)
                (let [^Throwable t (:_throw response)
                      message (or (.getMessage t)
                                  (some-> (ex-cause t) .getMessage)
                                  (.toString t))
                      details {:reason     :backend-threw
                               :message    message
                               :class      (-> t class .getName)
                               :ex-data    (when-let [d (ex-data t)]
                                             (try (pr-str d)
                                                  (catch Throwable _ "<unprintable>")))
                               :stack      (->> (.getStackTrace t)
                                                (take 6)
                                                (mapv #(str (.getClassName ^StackTraceElement %)
                                                            "." (.getMethodName ^StackTraceElement %)
                                                            "(" (.getFileName ^StackTraceElement %)
                                                            ":" (.getLineNumber ^StackTraceElement %) ")")))}]
                  (transcript! transcript-fn {:event :llm/error :ts (now-ms) :data details})
                  (post-event-to-parent! parent-ctx on-error-event
                                         (select-keys details [:reason :message :class]))
                  (reset! worker-state :dying)
                  (recur))

                :else
                (let [{:keys [stop-reason content usage model]} response
                      ;; Compute context-utilization hint when we know the model.
                      ctx-window     (some-> model models/context-window)
                      input-tokens   (:input-tokens usage)
                      ctx-used-frac  (when (and ctx-window input-tokens (pos? ctx-window))
                                       (/ (double input-tokens) (double ctx-window)))]
                  (transcript! transcript-fn
                               {:event :llm/response
                                :ts    (now-ms)
                                :data  (cond-> {:stop-reason stop-reason
                                                :n-blocks    (count content)
                                                :usage       (or usage {})}
                                         model         (assoc :model model)
                                         ctx-window    (assoc :context-window ctx-window)
                                         ctx-used-frac (assoc :context-used-frac
                                                              (Double/parseDouble (format "%.3f" ctx-used-frac))))})
                  ;; Soft warning when context approaches the model's ceiling.
                  ;; This is a transcript-level signal — chart-side scripts can
                  ;; also call `escapement.llm.models/approaching-limit?` themselves.
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
                      (post-event-to-parent! parent-ctx on-end-turn-event {})
                      ;; CAS: if stop arrived during end_turn, keep :dying.
                      (transition-state! worker-state :awaiting-user)
                      (recur))

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
                                :retry-counts      retry-counts}
                               parent-ctx b)]
                          (swap! results conj result-block)
                          (when fatal? (reset! fatal error-data))))
                      ;; Append a single user message containing all tool_result blocks.
                      (swap! messages-atom conj (user-tool-results-message @results))
                      (if-let [err @fatal]
                        (do
                          (post-event-to-parent! parent-ctx on-error-event err)
                          (reset! worker-state :dying)
                          (recur))
                        (recur)))

                    ;; Other stop reasons (max_tokens, stop_sequence)
                    (do
                      (post-event-to-parent! parent-ctx on-error-event
                                             {:reason :unexpected-stop
                                              :stop-reason stop-reason})
                      (reset! worker-state :dying)
                      (recur))))))

            :else
            (recur))))
      (catch InterruptedException _
        (transcript! transcript-fn {:event :llm/worker-exit :ts (now-ms) :data {:reason :interrupted}}))
      (catch Throwable t
        (transcript! transcript-fn {:event :llm/worker-exit :ts (now-ms)
                                    :data  {:reason :exception
                                            :message (.getMessage t)}})
        (try
          (post-event-to-parent! parent-ctx on-error-event
                                 {:reason :worker-exception
                                  :message (.getMessage t)})
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

(defrecord LlmConversationProcessor [backend tool-registry transcript-fn workers]
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
          ev-data           (when (map? event) (:data event))]
      (when (and entry (= :llm.user-message ev-name))
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
   * `:transcript-fn` (optional) — `(fn [event-map] ...)` for observability; default no-op"
  [{:keys [backend tool-registry transcript-fn]}]
  (assert backend "backend is required")
  (assert tool-registry "tool-registry is required")
  (->LlmConversationProcessor backend tool-registry (or transcript-fn (fn [_] nil)) (atom {})))

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
