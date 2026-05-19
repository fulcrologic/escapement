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
   [escapement.chart.service :as service]
   [escapement.llm.catalog :as catalog]
   [escapement.llm.needs :as needs]
   [com.fulcrologic.guardrails.malli.core :refer [>defn =>]]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.environment :as env-ns]
   [com.fulcrologic.statecharts.protocols :as sp]
   [escapement.llm.protocol :as llm]
   [escapement.llm.types :as llm-types]
   [escapement.tools.protocol :as tp]
   [malli.core :as m]
   [malli.error :as me]
   [malli.transform :as mt])
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
;; Verdict wrap-up (submit_verdict forced-tool inference at idle boundary)
;; ---------------------------------------------------------------------------

(def ^:const submit-verdict-tool-name
  "Anthropic-tool name for the `submit_verdict` wrap-up inference. Reserved;
   chart-authors must not declare an `:allowed-events`/`:real-tools` entry
   that would collide with this name."
  "submit_verdict")

(defn- submit-verdict-tool-def
  "Build the Anthropic tool definition for the wrap-up `submit_verdict` call.
   `verdict-schema` is the chart-supplied Malli schema describing the
   structured payload the LLM must produce."
  [verdict-schema]
  {:name         submit-verdict-tool-name
   :description  (str "Submit your final structured verdict for this turn. "
                      "Call this tool exactly once. Your input becomes the "
                      "chart's typed verdict payload.")
   :input-schema (llm-types/malli->json-schema (or verdict-schema [:map]))})

;; ---------------------------------------------------------------------------
;; Region-tool palette (chart-tools)
;; ---------------------------------------------------------------------------

(declare ->id-str)

(def ^:const region-tool-prefix
  "Anthropic-tool name prefix for region-tools. Distinct from the
   `event__` prefix so we can tell the two kinds apart in transcripts."
  "region__")

(defn- closed-map-schema?
  "True for a Malli `[:map {:closed true} ...]` form."
  [schema]
  (and (vector? schema)
       (= :map (first schema))
       (map? (second schema))
       (true? (:closed (second schema)))))

(defn- assoc-implicit-timeout
  "Merge an optional `:timeout-ms` field into a region-tool input schema.
   The schema must be open so the merge doesn't reject valid LLM input."
  [schema]
  (let [base (or schema [:map])]
    (when (closed-map-schema? base)
      (throw (ex-info (str "Region-tool input-schema is closed (:closed true); "
                           "cannot merge implicit :timeout-ms. Make the schema open.")
                      {:reason :closed-region-tool-schema :schema base})))
    (if (and (vector? base) (= :map (first base)))
      ;; Append the optional field, preserving any existing properties map.
      (let [props? (and (> (count base) 1) (map? (second base)))
            head   (if props? (subvec base 0 2) (subvec base 0 1))
            tail   (subvec base (count head))]
        (-> (into head tail)
            (conj [:timeout-ms {:optional true} [:int {:min 1}]])))
      ;; Schema isn't a vanilla :map — wrap it. This is rare; chart authors
      ;; usually pass `[:map ...]`. Caller's schema is preserved via :and.
      [:and base [:map [:timeout-ms {:optional true} [:int {:min 1}]]]])))

(defn- region-tool-name
  "Anthropic-tool name string for a region tool, given the consumer-facing
   `tool-kw` (already aliased by `:as` if applicable)."
  [tool-kw]
  (str region-tool-prefix (kw->anthropic-name tool-kw)))

(defn- region-tool-palette
  "Build the consumer-facing region-tool palette for one conversation.

   `registry-snapshot` is the flattened entry vector from
   `escapement.chart.service/entries`.

   `chart-tools` is the params declaration: a vector of
   `{:owner <state-id> :as <kw-prefix>?}` maps. Missing/empty → no region
   tools.

   Returns `[anthropic-tool-defs name->region-entry]` where each entry is

       {:event-kw         <chart event keyword>
        :owner            <state-id>
        :input-schema     <malli, post-implicit-timeout merge>
        :raw-input-schema <malli, as registered>
        :timeout-default  <int>
        :tool-kw          <consumer-facing keyword, aliased if applicable>}

   Throws on collision (two entries mapping to the same LLM-facing name —
   typically caused by two owners aliased to the same prefix, or an
   undisambiguated multi-owner scenario)."
  [registry-snapshot chart-tools default-timeout-ms]
  (let [decls (or chart-tools [])
        ;; Group registry entries by owner for fast lookup.
        by-owner (reduce
                  (fn [acc e] (update acc (:owner e) (fnil conj []) e))
                  {}
                  registry-snapshot)
        pulled (vec
                (mapcat
                 (fn [{:keys [owner as]}]
                   (for [entry (get by-owner owner [])
                         :let [event-kw (:tool entry)
                               tool-kw  (if as
                                          (keyword (name as) (name event-kw))
                                          event-kw)
                               raw      (or (:input-schema entry) [:map])]]
                     {:event-kw         event-kw
                      :owner            owner
                      :description      (:description entry)
                      :raw-input-schema raw
                      :input-schema     (assoc-implicit-timeout raw)
                      :timeout-default  default-timeout-ms
                      :tool-kw          tool-kw}))
                 decls))]
    (reduce
     (fn [[defs index] entry]
       (let [tname (region-tool-name (:tool-kw entry))]
         (when (contains? index tname)
           (let [other (get index tname)]
             (throw (ex-info (str "Region-tool palette collision: "
                                  (:tool-kw entry) " resolves to LLM-name "
                                  tname " for both owner " (:owner other)
                                  " and owner " (:owner entry)
                                  ". Disambiguate with :as in :chart-tools.")
                             {:reason :region-palette-collision
                              :tool   (:tool-kw entry)
                              :owners [(:owner other) (:owner entry)]}))))
         (let [def {:name         tname
                    :description  (or (:description entry)
                                      (str "Call chart tool " (:event-kw entry)))
                    :input-schema (llm-types/malli->json-schema (:input-schema entry))}]
           [(conj defs def) (assoc index tname entry)])))
     [[] {}]
     pulled)))

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

(def ^:const region-tool-default-timeout-ms
  "Default per-call deadline for a region-tool dispatch when neither the
   LLM nor the conversation params specify one. 30 seconds matches the
   plan's worked example (`{:timeout-ms 30000}`)."
  30000)

(def ^:const region-tool-poll-step-ms
  "Maximum single poll wait for the tool-reply queue. Polling in short
   slices lets the worker observe `:dying` promptly even when a region
   handler is slow."
  200)

(defn- poll-reply-queue!
  "Wait on the worker's `tool-reply-queue` until a reply for `reply-id`
   arrives, until `deadline-ms` passes, or until `worker-state` flips to
   `:dying`.

   Mis-correlated replies (for a different `reply-id`) are dropped with
   a transcript-log entry, NOT stashed: the worker has at most one
   outstanding region call at a time, so any other reply is a strict
   straggler."
  [^ArrayBlockingQueue queue reply-id deadline-ms worker-state transcript-fn invokeid]
  (loop []
    (let [now      (now-ms)
          remain   (- deadline-ms now)]
      (cond
        (= :dying @worker-state) nil
        (<= remain 0)            nil
        :else
        (let [wait (min (long remain) (long region-tool-poll-step-ms))
              msg  (.poll queue wait TimeUnit/MILLISECONDS)]
          (cond
            (nil? msg) (recur)
            (= reply-id (:escapement.tool/reply-id msg)) msg
            :else
            (do
              (transcript! transcript-fn
                           {:event :llm/region-tool-late-reply
                            :ts    (now-ms)
                            :data  {:invokeid invokeid
                                    :expected reply-id
                                    :got      (:escapement.tool/reply-id msg)}})
              (recur))))))))

(defn- handle-tool-use-block
  "Process a single tool_use block. Returns a map:
     `{:result-block {tool_result-block}
      :retry-bumped <map of id->count updates>
      :fatal? <bool>
      :error-data <map when fatal>}`

   `parent-ctx` is the worker's context (env/queue/parent-session-id/invokeid).
   `state*` holds the per-tool_use_id retry counters."
  [{:keys [tool-registry name->tool-kw name->event-entry name->region-tool
           tool-reply-queue worker-state retry-counts transcript-fn]
    :as   ctx} parent-ctx block]
  (let [{:keys [id name input]} block
        retries (get @retry-counts id 0)
        post-tool-result!
        (fn post-tool-result!
          ([tool-label is-error result-content]
           (post-tool-result! tool-label is-error result-content nil))
          ([tool-label is-error result-content resolved-path]
           (when transcript-fn
             (transcript! transcript-fn
                          {:event :llm/tool-result :ts (now-ms)
                           :data  (cond-> {:tool_use_id     id
                                           :tool            tool-label
                                           :input           input
                                           :is-error        (boolean is-error)
                                           :content-preview (truncate-for-transcript result-content)
                                           :invokeid        (:invokeid parent-ctx)}
                                    resolved-path (assoc :resolved-path resolved-path))}))))]
    (cond
      ;; Real tool
      (contains? name->tool-kw name)
      (let [tool-kw (get name->tool-kw name)
            {:keys [result is-error resolved-path]} (tp/dispatch tool-registry tool-kw (or input {}))]
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
            (post-tool-result! tool-kw is-error (or result "") resolved-path)
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
            {:result-block  {:type :tool_result :tool_use_id id :content "ok"}
             :posted-event? true})
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

      ;; Region tool — dispatch synchronously: post the request event,
      ;; poll the worker's `tool-reply-queue` until a matching reply
      ;; arrives or the per-call deadline passes.
      (contains? name->region-tool name)
      (let [{:keys [event-kw owner input-schema timeout-default]}
            (get name->region-tool name)
            schema (or input-schema [:map])]
        (if (m/validate schema (or input {}))
          (let [reply-id   (str "tr_" (java.util.UUID/randomUUID))
                ;; LLM may supply :timeout-ms; otherwise fall back to the
                ;; per-tool default. The wire payload carries the relative
                ;; duration; the worker computes the absolute deadline.
                timeout-ms (or (get input :timeout-ms) timeout-default
                               region-tool-default-timeout-ms)
                payload    (-> (or input {})
                               (dissoc :timeout-ms)
                               (assoc :escapement.tool/reply-id   reply-id
                                      :escapement.tool/reply-to   (->id-str
                                                                   (:invokeid parent-ctx))
                                      :escapement.tool/owner      owner
                                      :escapement.tool/timeout-ms timeout-ms))
                _          (post-event-to-parent! parent-ctx event-kw payload)
                deadline   (+ (now-ms) (long timeout-ms))
                reply      (poll-reply-queue! tool-reply-queue reply-id deadline
                                              worker-state transcript-fn
                                              (:invokeid parent-ctx))]
            (if reply
              (do
                (post-tool-result! event-kw (boolean (:is-error reply)) (str (:result reply)))
                ;; A region-tool is a synchronous request/reply *within* the
                ;; turn — the reply is fed back into the SAME conversation as a
                ;; tool_result and the LLM continues. It is NOT an end-of-turn
                ;; signal (unlike an event-tool). Do NOT set :posted-event?
                ;; here or the worker parks in :awaiting-user mid-turn and the
                ;; region/service/repl/scan flows break (R1 is event-tool only).
                {:result-block  {:type :tool_result :tool_use_id id
                                 :content (str (:result reply))
                                 :is-error (boolean (:is-error reply))}})
              (let [msg (str name " timed out after " timeout-ms "ms")]
                (post-tool-result! event-kw true msg)
                ;; Timeout still yields a tool_result the conversation
                ;; continues from — same rationale as above; not end-of-turn.
                {:result-block  {:type :tool_result :tool_use_id id
                                 :content msg :is-error true}})))
          (let [err (humanize-malli-errors schema input)]
            (post-tool-result! event-kw true err)
            (if (>= retries 1)
              {:fatal?     true
               :error-data {:reason :tool-validation-failed
                            :tool   event-kw
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

(defn- policy-nonempty?
  "True when a canonical policy expresses at least one clause."
  [pol]
  (boolean (and pol (or (seq (:require pol)) (seq (:min pol)) (seq (:max pol))))))

(defn- params->policy
  "The canonical declarative model policy from conversation `params`,
   resolved from the `:needs` chart-node surface key — the ergonomic flat
   `fact → constraint` map (bare value, `[:>= n]`, `[:<= n]`). Translated
   to the canonical `{:require/:min/:max}` shape via
   `escapement.llm.needs/needs->policy`.

   Returns nil when no policy clause is expressed. A malformed `:needs`
   throws (see `needs->policy`)."
  [params]
  (let [pol (when (contains? params :needs)
              (needs/needs->policy (:needs params)))]
    (when (policy-nonempty? pol)
      pol)))

(defn- candidate-models
  "Decide the ordered list of models to try for the next turn.

   Resolution order:
     1. `params :models`    — explicit ordered preference (no auto-substitution)
     2. `params :model`     — explicit single pick (no fallback)
     3. `default-models`    — processor-level auto-detected fallback list. When
                              an eligibility gate is expressed (`params
                              :needs`) the list is filtered to entries
                              satisfying it via
                              `escapement.llm.catalog/satisfies-policy?`,
                              with subjective ratings resolved from the
                              injected `catalog-ratings` value.
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
  [params default-models model-status catalog-ratings]
  (let [policy    (params->policy params)
        defaults  (if (and policy (seq default-models))
                    (let [filtered (filterv #(catalog/satisfies-policy? % policy catalog-ratings) default-models)]
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
                              `{:model :error}` pairs (oldest first).
     {:eligibility-empty …} — fail-closed: `:llm/eligibility-strict?` was
                              set in the injected config and the
                              eligibility gate excluded every fallback
                              model; the caller fails the node with a
                              categorized error rather than silently
                              running an ineligible model.

   `catalog-ratings` is the subjective ratings table the invocation
   context resolved ONCE (CLI: from disk config at startup; lib: from
   injected `:config`). It is passed explicitly to every
   `catalog/satisfies-policy?` call — the Step-1 2-arg disk-resolving
   arity is intentionally not relied on here. `eligibility-strict?` is
   the injected fail-closed flag."
  [{:keys [backend transcript-fn worker-state model-status default-models
           parent-ctx catalog-ratings eligibility-strict?]}
   params messages tools]
  (let [{:keys [max-retries backoff-ms]} (params->resilience params)
        policy        (params->policy params)
        auto-fallback? (and (not (seq (:models params)))
                            (nil? (:model params)))
        gate-empties?  (and auto-fallback? policy (seq default-models)
                            (not-any? #(catalog/satisfies-policy? % policy catalog-ratings)
                                      default-models))
        _              (when gate-empties?
                         (transcript! transcript-fn
                                      {:event :llm/model-policy-empty
                                       :ts    (now-ms)
                                       :data  {:policy         policy
                                               :default-models (vec default-models)
                                               :strict?        (boolean eligibility-strict?)}}))]
    (if (and gate-empties? eligibility-strict?)
      ;; Fail-closed: a webapp/lib host opted into never running an
      ;; ineligible model. Surface a categorized error so the node fails
      ;; instead of proceeding on the unfiltered fallback list.
      {:eligibility-empty {:policy         policy
                           :default-models (vec default-models)}}
      (let [candidates (candidate-models params default-models model-status catalog-ratings)]
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
              {:ok response :model-used m})))))))

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

(defn- run-verdict-inference!
  "Run a single forced-tool inference asking the model to call `submit_verdict`
   with a payload matching `verdict-schema` (Malli). Returns one of:

     {:verdict <validated-input>}              — happy path
     {:validation-failed <humanized-errors>}   — model returned a tool_use whose
                                                  input doesn't match the schema
     {:no-tool-use <stop-reason>}              — model didn't emit a submit_verdict
                                                  tool_use block (e.g. model
                                                  ignored the forced tool-choice)
     {:exhausted ...} / {:interrupted ...} /
     {:eligibility-empty ...} / {:no-progress ...}
                                                — pass-through from `drive-turn!`

   Tools are restricted to ONLY the `submit_verdict` tool def — real-tools,
   event-tools, and region-tools are dropped for this wrap-up call so the
   model has exactly one path to satisfy the forced tool-choice. `params` is
   the original conversation params merged with `:tool-choice` and (because
   the wrap-up is itself the terminating step) thinking off; everything else
   (system, model, temperature, ...) is carried verbatim."
  [{:keys [transcript-fn parent-ctx] :as ctx} params messages verdict-schema]
  (let [tool-def    (submit-verdict-tool-def verdict-schema)
        wrap-params (assoc params
                           :tool-choice {:type :tool :name submit-verdict-tool-name})
        _           (transcript! transcript-fn
                                 {:event :llm/verdict-inference :ts (now-ms)
                                  :data  {:invokeid (:invokeid parent-ctx)}})
        outcome     (drive-turn! ctx wrap-params messages [tool-def])]
    (cond
      (not (:ok outcome)) outcome

      :else
      (let [resp        (:ok outcome)
            {:keys [stop-reason content]} resp
            tool-uses   (find-tool-uses content)
            verdict-blk (some (fn [b]
                                (when (= submit-verdict-tool-name (:name b)) b))
                              tool-uses)
            ;; The LLM returns tool_use input as raw JSON-shaped data
            ;; (strings, numbers, vectors of those, never keywords). When
            ;; the chart-supplied verdict-schema declares keyword shapes
            ;; (e.g. `[:enum :a :b]`, qualified keyword keys), validation
            ;; would otherwise fail. Decode through Malli's
            ;; json-transformer so string → keyword (and similar)
            ;; coercions land before validation.
            decoded     (when verdict-blk
                          (m/decode (or verdict-schema [:map])
                                    (or (:input verdict-blk) {})
                                    (mt/json-transformer)))]
        (cond
          (nil? verdict-blk)
          {:no-tool-use stop-reason}

          (not (m/validate (or verdict-schema [:map]) decoded))
          {:validation-failed (humanize-malli-errors (or verdict-schema [:map]) decoded)
           :input             (:input verdict-blk)
           :decoded           decoded}

          :else
          {:verdict decoded})))))

(defn- maybe-run-verdict-and-finalize-idle!
  "When `verdict-schema` is set, run a forced `submit_verdict` inference and
   return a map `{:idle-data <map>}` carrying the verdict (or `{:error
   <reason> <data>}` on failure). When `verdict-schema` is nil, returns
   `{:idle-data <base-idle-data>}` immediately — preserving today's behavior."
  [{:keys [transcript-fn parent-ctx] :as ctx} params base-messages verdict-schema base-idle-data]
  (if (nil? verdict-schema)
    {:idle-data base-idle-data}
    (let [outcome (run-verdict-inference! ctx params base-messages verdict-schema)]
      (cond
        (:verdict outcome)
        (do
          (transcript! transcript-fn
                       {:event :llm/verdict :ts (now-ms)
                        :data  {:invokeid (->id-str (:invokeid parent-ctx))
                                :verdict  (:verdict outcome)}})
          {:idle-data (assoc base-idle-data :verdict (:verdict outcome))})

        (:validation-failed outcome)
        {:error :verdict-validation
         :data  {:reason     :verdict-validation
                 :errors     (:validation-failed outcome)
                 :raw-input  (:input outcome)}}

        (:no-tool-use outcome)
        {:error :verdict-validation
         :data  {:reason      :verdict-validation
                 :stop-reason (:no-tool-use outcome)
                 :detail      :no-submit-verdict-tool-use}}

        (:exhausted outcome)
        {:error :backend
         :data  {:reason :backend
                 :detail :verdict-inference-failed
                 :attempts (:exhausted outcome)}}

        (:interrupted outcome)
        {:error :interrupted}

        (:eligibility-empty outcome)
        {:error :invalid-request
         :data  (assoc (:eligibility-empty outcome)
                       :reason :invalid-request
                       :detail :verdict-eligibility-empty)}

        (:no-progress outcome)
        {:error :unexpected-stop
         :data  {:reason :unexpected-stop
                 :stop-reason :max_tokens
                 :detail :verdict-no-progress}}

        :else
        {:error :backend
         :data  {:reason :backend
                 :detail :verdict-inference-unknown}}))))

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
      (:eligibility-empty outcome)
      (let [{:keys [policy default-models]} (:eligibility-empty outcome)]
        (transcript! transcript-fn
                     {:event :llm/error :ts (now-ms)
                      :data  {:reason         :invalid-request
                              :detail         :eligibility-empty-strict
                              :policy         policy
                              :default-models default-models}})
        ;; Fail-closed: the eligibility gate excluded every fallback
        ;; model and `:llm/eligibility-strict?` is set. Categorize as
        ;; :invalid-request so the chart's :error.llm.* / .invalid-request
        ;; transitions fire — the node fails rather than run a model the
        ;; host declared ineligible.
        (post-error! :invalid-request {:detail         :eligibility-empty-strict
                                       :policy         policy
                                       :default-models default-models})
        (reset! worker-state :dying)
        :error-and-die)

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
          (let [final-text  (->> content
                                 (filter #(= :text (:type %)))
                                 (map :text)
                                 (apply str))
                base-data   {:text final-text
                             :from (->id-str (:invokeid parent-ctx))}
                verdict-schema (:verdict-schema params)
                wrap        (maybe-run-verdict-and-finalize-idle!
                             ctx params @messages-atom verdict-schema base-data)]
            (cond
              (:idle-data wrap)
              (do
                (post-event-to-parent! parent-ctx on-end-turn-event (:idle-data wrap))
                (transition-state! worker-state :awaiting-user)
                :idle)

              (:error wrap)
              (do
                (post-error! (:error wrap) (or (:data wrap) {}))
                (reset! worker-state :dying)
                :error-and-die)))

          :tool_use
          (let [tool-use-blocks (find-tool-uses content)
                results         (atom [])
                fatal           (atom nil)
                posted-event?   (atom false)]
            (doseq [b tool-use-blocks
                    :while (nil? @fatal)]
              (let [{:keys [result-block fatal? error-data]
                     :as   block-res}
                    (handle-tool-use-block
                     {:tool-registry     tool-registry
                      :name->tool-kw     name->tool-kw
                      :name->event-entry name->event-entry
                      :name->region-tool (:name->region-tool ctx)
                      :tool-reply-queue  (:tool-reply-queue ctx)
                      :worker-state      worker-state
                      :retry-counts      retry-counts
                      :transcript-fn     transcript-fn}
                     parent-ctx b)]
                (swap! results conj result-block)
                (when (:posted-event? block-res) (reset! posted-event? true))
                (when fatal? (reset! fatal error-data))))
            (swap! messages-atom conj (user-tool-results-message @results))
            (cond
              @fatal
              (do
                (post-error! :tool-validation @fatal)
                (reset! worker-state :dying)
                :error-and-die)

              ;; glm-class models batch the terminating event-tool
              ;; (event__done / event__tick) into a :tool_use response
              ;; instead of emitting a separate :end_turn turn. When a
              ;; block posted a chart event, mirror the :end_turn branch:
              ;; fire on-end-turn-event with the assembled final text and
              ;; park the worker in :awaiting-user.
              ;;
              ;; De-dupe: only post when the worker is not already
              ;; :awaiting-user/:dying. `transition-state!` then moves the
              ;; worker to :awaiting-user, which makes a stray later real
              ;; :end_turn for the same logical turn impossible — the outer
              ;; loop parks in :awaiting-user and won't drive another turn
              ;; (and transition-state! is a no-op once :dying), so the
              ;; :end_turn post-path cannot run again. Hence: exactly one
              ;; on-end-turn-event per logical turn.
              (and @posted-event?
                   (not (#{:awaiting-user :dying} @worker-state)))
              (let [final-text     (->> content
                                        (filter #(= :text (:type %)))
                                        (map :text)
                                        (apply str))
                    base-data      {:text final-text
                                    :from (->id-str (:invokeid parent-ctx))}
                    verdict-schema (:verdict-schema params)
                    wrap           (maybe-run-verdict-and-finalize-idle!
                                    ctx params @messages-atom verdict-schema base-data)]
                (cond
                  (:idle-data wrap)
                  (do
                    (post-event-to-parent! parent-ctx on-end-turn-event (:idle-data wrap))
                    (transition-state! worker-state :awaiting-user)
                    :idle)

                  (:error wrap)
                  (do
                    (post-error! (:error wrap) (or (:data wrap) {}))
                    (reset! worker-state :dying)
                    :error-and-die)))

              :else
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

(defrecord LlmConversationProcessor [backend tool-registry transcript-fn workers model-status default-models
                                     catalog-ratings eligibility-strict?]
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
          {:keys [real-tools allowed-events chart-tools initial-messages initial-user-message]} params
          [real-defs name->tool-kw]   (resolve-real-tools tool-registry real-tools)
          [event-defs name->event]    (event-tool-defs (or allowed-events []))
          ;; Palette snapshot for region tools — built once at start. Late
          ;; registrations or unregistrations are NOT reflected in this
          ;; conversation (see region-tools.md "Timing and executable-content
          ;; order").
          registry-snapshot           (service/entries env)
          [region-defs name->region]  (region-tool-palette registry-snapshot
                                                           chart-tools
                                                           region-tool-default-timeout-ms)
          tool-defs                   (into [] (concat real-defs event-defs region-defs))
          initial-msgs                (cond
                                        (seq initial-messages) (vec initial-messages)
                                        initial-user-message [(text-user-message initial-user-message)]
                                        :else [])
          messages-atom               (atom initial-msgs)
          worker-state                (atom (if (seq initial-msgs) :running :awaiting-user))
          user-msg-queue              (ArrayBlockingQueue. 256)
          tool-reply-queue            (ArrayBlockingQueue. 64)
          retry-counts                (atom {})
          parent-ctx                  {:env env :queue queue
                                       :parent-session-id parent-session-id
                                       :invokeid invokeid}
          ctx                         {:backend            backend
                                       :tool-registry      tool-registry
                                       :transcript-fn      (or transcript-fn (fn [_] nil))
                                       :name->tool-kw      name->tool-kw
                                       :name->event-entry  name->event
                                       :name->region-tool  name->region
                                       :tool-reply-queue   tool-reply-queue
                                       :tool-defs          tool-defs
                                       :worker-state       worker-state
                                       :messages-atom      messages-atom
                                       :user-msg-queue     user-msg-queue
                                       :retry-counts       retry-counts
                                       :model-status       model-status
                                       :default-models     default-models
                                       :catalog-ratings     (or catalog-ratings {})
                                       :eligibility-strict? (boolean eligibility-strict?)
                                       :params             params
                                       :parent-ctx         parent-ctx}
          runnable                    (fn [] (run-worker! ctx))
          ^Thread thread              (doto (Thread. ^Runnable runnable
                                                     (str "llm-conv-" parent-session-id "-" invokeid))
                                        (.setDaemon true))]
      (swap! workers assoc k
             {:thread           thread
              :worker-state     worker-state
              :messages-atom    messages-atom
              :user-msg-queue   user-msg-queue
              :tool-reply-queue tool-reply-queue
              :retry-counts     retry-counts
              :params           params})
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
      (cond
        ;; Region-tool reply — hard-routed by `:escapement.tool/reply-to`,
        ;; NOT broadcast. The engine calls `forward-event!` once per live
        ;; autoforwarded invocation; we deliver only on the call whose
        ;; `invokeid` matches the reply's `:reply-to` (so a single physical
        ;; reply lands on the addressed worker's queue exactly once).
        ;;
        ;; Invariant: `forward-event!` is only invoked by the engine for
        ;; LIVE autoforwarding invocations, and every live invocation has an
        ;; entry in `@workers` (added by `start-invocation!`, removed by
        ;; `stop-invocation!`). When `invokeid == reply-to`, `entry` is
        ;; therefore non-nil. No orphan branch is needed; the `(when entry …)`
        ;; guard is purely defensive for direct callers (e.g. tests).
        (= :escapement.tool/reply ev-name)
        (let [reply-to (:escapement.tool/reply-to ev-data)]
          (when (and entry (= (->id-str invokeid) (->id-str reply-to)))
            (.offer ^ArrayBlockingQueue (:tool-reply-queue entry) ev-data)))

        (and entry accept? (= :llm.user-message ev-name))
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
     for cross-backend fallback when only some credentials are healthy.
   * `:catalog-ratings` (optional) — the subjective ratings table
     (`id → opinion-map`, the shape `escapement.llm.ratings/ratings`
     returns) resolved ONCE by the invocation/env builder and threaded
     explicitly to every `catalog/satisfies-policy?` call. Defaults to
     `{}` (a subjective `:needs` clause then matches nothing). The CLI
     path resolves this from disk config at startup; the lib facade
     (Step 4) will feed it from injected `:config`. This is the clean
     injection seam — the processor never reads disk or a global.
   * `:eligibility-strict?` (optional, default false) — fail-closed
     flag. When true, an eligibility gate (`:needs`)
     that excludes every auto-fallback model fails the node with a
     categorized `:error.llm.invalid-request` instead of silently
     proceeding on the unfiltered list (fail-open, the default)."
  [{:keys [backend tool-registry transcript-fn default-models
           catalog-ratings eligibility-strict?]}]
  (assert backend "backend is required")
  (assert tool-registry "tool-registry is required")
  (->LlmConversationProcessor backend tool-registry (or transcript-fn (fn [_] nil))
                              (atom {}) (atom {}) (vec default-models)
                              (or catalog-ratings {}) (boolean eligibility-strict?)))

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
