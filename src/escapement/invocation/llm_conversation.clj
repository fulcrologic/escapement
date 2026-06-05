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
    [cheshire.core :as json]
    [clojure.string :as str]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.environment :as env-ns]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.capture :as capture]
    [escapement.chart.service :as service]
    [escapement.llm :as ellm]
    [escapement.llm.catalog :as catalog]
    [escapement.llm.preferences :as preferences]
    [escapement.llm.protocol :as llm]
    [escapement.llm.types :as llm-types]
    [escapement.tools.protocol :as tp]
    [malli.core :as m]
    [malli.error :as me]
    [malli.transform :as mt]
    [com.fulcrologic.statecharts.promise :as p])
  (:import
    (java.util.concurrent ArrayBlockingQueue TimeUnit)))

;; ---------------------------------------------------------------------------
;; Model resolution + resilience now live in `escapement.llm` (one source of
;; truth, shared with the script-facing `ask`/`map-prompt` helpers). These
;; private aliases keep existing `#'llm-conversation/…` test references pointed
;; at the canonical implementations.
;; ---------------------------------------------------------------------------
(def ^:private resolve-candidates ellm/resolve-candidates)
(def ^:private params->resilience ellm/params->resilience)
(def ^:private params->policy ellm/params->policy)
(def effective-max-tokens
  "See `escapement.llm/effective-max-tokens`. Re-exported (public) because the
   per-response output cap is a useful catalog lookup for chart authors."
  ellm/effective-max-tokens)

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

(def ^:private wrap-up-nudge-text
  "Framework-owned final user message appended to the transcript ONLY for the
   forced-tool wrap-up inference. Keeps prompt authors out of the business of
   explaining framework mechanics — the model sees this nudge only when the
   wrap-up runs, never during the normal work loop."
  "Please summarize your conclusions for this turn by calling submit_verdict.")

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
  (let [decls    (or chart-tools [])
        ;; Group registry entries by owner for fast lookup.
        by-owner (reduce
                   (fn [acc e] (update acc (:owner e) (fnil conj []) e))
                   {}
                   registry-snapshot)
        pulled   (vec
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

;; Heavy LLM I/O (full request/response/tool-result) is no longer stored inline: it is captured to
;; the ArtifactStore as a blob and referenced by `:io/ref`, with only an ≤80-char `:io/snippet` kept
;; in the event for human correlation (see `escapement.capture` and `io-refactor-plan.md` §0). The
;; inline preview fields below (`:content`, `:user-blocks`, `:system-preview`, `:content-preview`)
;; are now those same short snippets, not truncated full text.

(defn- ->transcript-content-block
  "Coerce one assistant content block into a transcript-safe map. Text and
   thinking strings are reduced to a short snippet; tool_use carries `:id :name :input`."
  [b]
  (case (:type b)
    :text {:type :text :text (capture/snippet (:text b))}
    :thinking {:type :thinking :thinking (capture/snippet (:thinking b))}
    :tool_use {:type :tool_use :id (:id b) :name (:name b) :input (:input b)}
    {:type (:type b)}))

(defn- trailing-user-blocks
  "Return the content blocks of the trailing user message in `messages`, or
   `[]` if the last message isn't a user turn. Each block's content text is
   reduced to a short snippet for the inline transcript."
  [messages]
  (let [last-msg (peek (vec messages))]
    (if (= :user (:role last-msg))
      (mapv (fn [b]
              (case (:type b)
                :text {:type :text :text (capture/snippet (:text b))}
                :tool_result {:type     :tool_result :tool_use_id (:tool_use_id b)
                              :is-error (boolean (:is-error b))
                              :content  (capture/snippet (:content b))}
                {:type (:type b)}))
        (or (:content last-msg) []))
      [])))

(defn- trailing-user-text
  "Concatenated raw text of the trailing user message in `messages` (empty string if the last
   message is not a user turn). Used as the human-correlation snippet source for a captured request."
  [messages]
  (let [last-msg (peek (vec messages))]
    (if (= :user (:role last-msg))
      (->> (:content last-msg)
        (filter #(= :text (:type %)))
        (map :text)
        (apply str))
      "")))

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

;; Coercion: small LLMs (e.g. llama3.2:3b) often emit nested collections as
;; stringified JSON inside a tool_use input — `{"haikus": "[\"a\",\"b\"]"}`
;; instead of `{"haikus": ["a","b"]}`. We attempt one round of JSON re-parse
;; when the target schema is a collection and the value is a string opening
;; with `[`/`{`. If the re-parse fails the original string is preserved and
;; validation reports the same humanized error as before.
(def ^:private json-string-decoder-vec
  (fn [x] (if (and (string? x) (re-find #"^\s*\[" x))
            (try (json/parse-string x true) (catch Exception _ x))
            x)))

(def ^:private json-string-decoder-map
  (fn [x] (if (and (string? x) (re-find #"^\s*\{" x))
            (try (json/parse-string x true) (catch Exception _ x))
            x)))

(def ^:private json-string-transformer
  (mt/transformer
    {:decoders {:vector     json-string-decoder-vec
                :sequential json-string-decoder-vec
                :set        json-string-decoder-vec
                :map        json-string-decoder-map}}))

(def ^:private tool-input-transformer
  (mt/transformer json-string-transformer mt/string-transformer))

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

;; ---------------------------------------------------------------------------
;; Resilience: transient-error retry + max_tokens continuation
;; ---------------------------------------------------------------------------

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
    (empty? acc) (vec more)
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
   LLM nor the conversation params specify one. 120 seconds is generous for
   slower/rate-limited models whose region handlers themselves call an LLM;
   a chart that needs a different bound passes `:timeout-ms` per call."
  120000)

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
    (let [now    (now-ms)
          remain (- deadline-ms now)]
      (cond
        (= :dying @worker-state) nil
        (<= remain 0) nil
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
           tool-reply-queue worker-state retry-counts transcript-fn capture turn]
    :as   ctx} parent-ctx block]
  (let [{:keys [id name input]} block
        retries (get @retry-counts id 0)
        post-tool-result!
                (fn post-tool-result!
                  ([tool-label is-error result-content]
                   (post-tool-result! tool-label is-error result-content nil))
                  ([tool-label is-error result-content resolved-path]
                   (when transcript-fn
                     ;; Externalize the FULL tool result to a blob (it can be large — file reads,
                     ;; REPL output, and it is a replay input for node-refine); keep only an
                     ;; ≤80-char snippet + :io/ref inline.
                     (let [io-ref (when capture
                                    ;; Best-effort: a storage hiccup must never abort a live turn
                                    ;; (parity with `transcript!` / seed capture).
                                    (try (capture/capture-blob! capture turn
                                           (str "tool-results/" id) result-content result-content)
                                         (catch Throwable _ nil)))]
                       (transcript! transcript-fn
                         {:event :llm/tool-result :ts (now-ms)
                          :data  (cond-> {:tool_use_id     id
                                          :tool            tool-label
                                          :input           input
                                          :is-error        (boolean is-error)
                                          :content-preview (capture/snippet result-content)
                                          :invokeid        (:invokeid parent-ctx)}
                                   resolved-path (assoc :resolved-path resolved-path)
                                   io-ref (assoc :io/ref (:io/ref io-ref)))})))))]
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
              {:fatal?       true
               :error-data   {:reason      :tool-validation-failed
                              :tool        tool-kw
                              :errors      result
                              :tool_use_id id}
               :result-block {:type    :tool_result :tool_use_id id
                              :content result :is-error true}}
              (do
                (swap! retry-counts assoc id (inc retries))
                {:result-block {:type    :tool_result :tool_use_id id
                                :content result :is-error true}})))
          (do
            (post-tool-result! tool-kw is-error (or result "") resolved-path)
            {:result-block {:type    :tool_result :tool_use_id id
                            :content (or result "") :is-error (boolean is-error)}})))

      ;; Event tool
      (contains? name->event-entry name)
      (let [{:keys [event data-schema]} (get name->event-entry name)
            schema  (or data-schema [:map])
            decoded (m/decode schema (or input {}) tool-input-transformer)]
        (if (m/validate schema decoded)
          (do
            (post-event-to-parent! parent-ctx event decoded)
            ;; "Events sent": record the chart event this turn fired, stamped with the invocation
            ;; coordinates (node-id/visit/turn) so the reconstruction layer can interleave it with the
            ;; turn's request/response/tool-results. Kept inline-small (the event name + decoded input).
            (transcript! transcript-fn
              {:event              :llm/event-posted
               :ts                 (now-ms)
               :transcript/node-id (:node-id capture)
               :transcript/visit   (:visit capture)
               :transcript/turn    turn
               :data               {:invokeid   (:invokeid parent-ctx)
                                     :event-name event
                                     :event-data decoded}})
            (post-tool-result! event false "ok")
            {:result-block  {:type :tool_result :tool_use_id id :content "ok"}
             :posted-event? true})
          (let [err (humanize-malli-errors schema decoded)]
            (post-tool-result! event true err)
            (if (>= retries 1)
              {:fatal?       true
               :error-data   {:reason      :tool-validation-failed
                              :tool        event
                              :errors      err
                              :tool_use_id id}
               :result-block {:type    :tool_result :tool_use_id id
                              :content err :is-error true}}
              (do
                (swap! retry-counts assoc id (inc retries))
                {:result-block {:type    :tool_result :tool_use_id id
                                :content err :is-error true}})))))

      ;; Region tool — dispatch synchronously: post the request event,
      ;; poll the worker's `tool-reply-queue` until a matching reply
      ;; arrives or the per-call deadline passes.
      (contains? name->region-tool name)
      (let [{:keys [event-kw owner input-schema timeout-default]}
            (get name->region-tool name)
            schema  (or input-schema [:map])
            decoded (m/decode schema (or input {}) tool-input-transformer)]
        (if (m/validate schema decoded)
          (let [reply-id   (str "tr_" (java.util.UUID/randomUUID))
                ;; LLM may supply :timeout-ms; otherwise fall back to the
                ;; per-tool default. The wire payload carries the relative
                ;; duration; the worker computes the absolute deadline.
                timeout-ms (or (get decoded :timeout-ms) timeout-default
                             region-tool-default-timeout-ms)
                payload    (-> decoded
                             (dissoc :timeout-ms)
                             (assoc :escapement.tool/reply-id reply-id
                                    :escapement.tool/reply-to (->id-str
                                                                (:invokeid parent-ctx))
                                    :escapement.tool/owner owner
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
                {:result-block {:type     :tool_result :tool_use_id id
                                :content  (str (:result reply))
                                :is-error (boolean (:is-error reply))}})
              (let [msg (str name " timed out after " timeout-ms "ms")]
                (post-tool-result! event-kw true msg)
                ;; Timeout still yields a tool_result the conversation
                ;; continues from — same rationale as above; not end-of-turn.
                {:result-block {:type    :tool_result :tool_use_id id
                                :content msg :is-error true}})))
          (let [err (humanize-malli-errors schema decoded)]
            (post-tool-result! event-kw true err)
            (if (>= retries 1)
              {:fatal?       true
               :error-data   {:reason      :tool-validation-failed
                              :tool        event-kw
                              :errors      err
                              :tool_use_id id}
               :result-block {:type    :tool_result :tool_use_id id
                              :content err :is-error true}}
              (do
                (swap! retry-counts assoc id (inc retries))
                {:result-block {:type    :tool_result :tool_use_id id
                                :content err :is-error true}})))))

      :else
      (let [msg (str "Unknown tool: " name)]
        (post-tool-result! name true msg)
        (if (>= retries 1)
          {:fatal?       true
           :error-data   {:reason :unknown-tool :tool name :tool_use_id id}
           :result-block {:type    :tool_result :tool_use_id id
                          :content msg :is-error true}}
          (do
            (swap! retry-counts assoc id (inc retries))
            {:result-block {:type    :tool_result :tool_use_id id
                            :content msg :is-error true}}))))))

(defn ->id-str
  "Normalize an invokeid to its canonical string form. Chart-authors may write
   `:id :researcher` (keyword) or `:id \"researcher\"` (string); both should
   compare equal everywhere we touch them (target routing, :from labels,
   artifact filenames). Public so chart-author helpers can normalize too."
  [x]
  (cond
    (keyword? x) (name x)
    (nil? x) nil
    :else (str x)))

(defn- error-event
  "Compose the SCXML-style error event name from a `:reason` keyword.
   `:reason :backend` → `:error.llm.backend`."
  [reason]
  (keyword (str "error.llm." (name reason))))

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
  "Issue one assistant turn via `escapement.llm/run-turn`, supplying the worker's
   transcript/capture hooks and the shared `model-status` atom, then translate
   the result envelope back into the worker's legacy outcome shapes:

     {:ok response :model-used m}  — successful turn
     {:interrupted t}              — worker is dying / thread was interrupted
     {:exhausted attempts ...}     — every candidate failed; caller posts
                                     :error.llm.<category>. `attempts` is a
                                     vector of {:model :error} pairs (oldest first)
     {:eligibility-empty …}        — fail-closed `:needs` gate excluded everything
     {:string-model s} / {:string-models … :bad …} / {:unknown-alias … :known …}
                                   — categorized resolution errors (never shipped)

   Resolution + retry + cross-provider failover live in `escapement.llm`; this
   adapter owns only the transcript events, request capture, and the interrupt
   signal (`worker-state` → `:alive?`). `catalog-ratings`/`eligibility-strict?`
   are the invocation context's once-resolved `:needs`-gate inputs."
  [{:keys [backend transcript-fn worker-state model-status
           parent-ctx catalog-ratings eligibility-strict? aliases preferences
           capture turn-count]}
   params messages tools]
  (let [turn     (when turn-count (dec (long @turn-count)))
        invokeid (:invokeid parent-ctx)
        hooks    {:before-send
                  (fn [request m]
                    ;; Externalize the FULL request to a blob (the exact value
                    ;; `escapement.replay/refine-turn` re-feeds). Best-effort,
                    ;; first-write-wins so a fallback re-issue keeps the base.
                    (let [io-ref (when (and capture turn)
                                   (try (capture/capture-request! capture turn request
                                          (trailing-user-text messages))
                                        (catch Throwable _ nil)))]
                      (transcript! transcript-fn
                        {:event :llm/request :ts (now-ms)
                         :data  (cond-> {:n-messages     (count (:messages request))
                                         :user-blocks    (trailing-user-blocks messages)
                                         :system-preview (capture/snippet (:system params))
                                         :invokeid       invokeid}
                                  m      (assoc :model m)
                                  io-ref (assoc :io/ref (:io/ref io-ref)))})))
                  :delta-sink
                  (fn [m]
                    (when (:stream? params)
                      (fn [d]
                        (transcript! transcript-fn
                          {:event :llm/delta :ts (now-ms)
                           :data  (assoc d :model m :invokeid invokeid)}))))
                  :on-retry
                  (fn [{:keys [model category attempt max-retries]}]
                    (transcript! transcript-fn
                      {:event :llm/retry :ts (now-ms)
                       :data  {:model       model
                               :category    category
                               :attempt     attempt
                               :max-retries max-retries
                               :invokeid    invokeid}}))
                  :on-model-down
                  (fn [{:keys [model category remaining throwable]}]
                    (transcript! transcript-fn
                      {:event :llm/model-down :ts (now-ms)
                       :data  {:model     model
                               :message   (:message (throwable->details throwable))
                               :category  category
                               :remaining remaining}}))
                  :on-policy-empty
                  (fn [{:keys [policy strict?]}]
                    (transcript! transcript-fn
                      {:event :llm/model-policy-empty :ts (now-ms)
                       :data  {:policy policy :strict? strict?}}))
                  :alive? (fn [] (not= :dying @worker-state))}
        env      (ellm/run-turn
                   {:backend             backend
                    :aliases             aliases
                    :preferences         preferences
                    :catalog-ratings     (or catalog-ratings {})
                    :eligibility-strict? eligibility-strict?
                    :model-status        model-status
                    :hooks               hooks}
                   params messages tools)]
    (case (:status env)
      :ok                {:ok (:response env) :model-used (:model env)}
      :exhausted         {:exhausted      (get-in env [:error :attempts])
                          :last-throwable (:last-throwable env)}
      :interrupted       {:interrupted (get-in env [:error :throwable])}
      :eligibility-empty {:eligibility-empty (:error env)}
      :unknown-alias     {:unknown-alias (get-in env [:error :alias])
                          :known         (get-in env [:error :known])}
      :string-model      {:string-model (get-in env [:error :model])}
      :string-models     {:string-models (get-in env [:error :models])
                          :bad           (get-in env [:error :bad])})))

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
  (let [tool-def      (submit-verdict-tool-def verdict-schema)
        wrap-params   (assoc params
                        :tool-choice {:type :tool :name submit-verdict-tool-name})
        ;; Append a framework-owned user-message nudge so the model has clear
        ;; context that it is being asked to consolidate, not continue
        ;; working. This is wrap-up-local — the working transcript carried in
        ;; `messages-atom` upstream is NOT mutated.
        wrap-messages (conj (vec messages) (text-user-message wrap-up-nudge-text))
        _             (transcript! transcript-fn
                        {:event :llm/verdict-inference :ts (now-ms)
                         :data  {:invokeid (:invokeid parent-ctx)}})
        outcome       (drive-turn! ctx wrap-params wrap-messages [tool-def])]
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

(defn- finalize-idle-data
  "Externalize the conversation OUTPUT so working memory and the transcript never carry the full
   assistant text. Given the assembled idle map `full` (`:text` + `:from` + maybe `:verdict`) for
   `turn`, write `output.edn` via `capture` and return the map with `:text` REPLACED by `:output-ref`
   (the blob locator) + `:io/snippet` (≤80 chars). When `capture` is absent (no artifact store — most
   test envs), the inline map is returned unchanged so the chart still receives `:text`."
  [capture turn full]
  (let [ref (when (and capture (some? turn))
              (try (capture/capture-output! capture turn full)
                   (catch Throwable _ nil)))]
    (if ref
      (-> full
        (dissoc :text)
        (assoc :output-ref (:io/ref ref) :io/snippet (:io/snippet ref)))
      full)))

(defn- maybe-run-verdict-and-finalize-idle!
  "When `verdict-schema` is set, run a forced `submit_verdict` inference and
   return a map `{:idle-data <map>}` carrying the verdict (or `{:error
   <reason> <data>}` on failure). When `verdict-schema` is nil, returns
   `{:idle-data <base-idle-data>}` immediately. In both success paths the idle
   data is run through `finalize-idle-data` so the full text is externalized to
   an `:output-ref` handle (see `finalize-idle-data`)."
  [{:keys [transcript-fn parent-ctx capture] :as ctx} params base-messages verdict-schema turn base-idle-data]
  (if (nil? verdict-schema)
    {:idle-data (finalize-idle-data capture turn base-idle-data)}
    (let [outcome (run-verdict-inference! ctx params base-messages verdict-schema)]
      (cond
        (:verdict outcome)
        (do
          (transcript! transcript-fn
            {:event :llm/verdict :ts (now-ms)
             :data  {:invokeid (->id-str (:invokeid parent-ctx))
                     :verdict  (:verdict outcome)}})
          {:idle-data (finalize-idle-data capture turn
                        (assoc base-idle-data :verdict (:verdict outcome)))})

        (:validation-failed outcome)
        {:error :verdict-validation
         :data  {:reason    :verdict-validation
                 :errors    (:validation-failed outcome)
                 :raw-input (:input outcome)}}

        (:no-tool-use outcome)
        {:error :verdict-validation
         :data  {:reason      :verdict-validation
                 :stop-reason (:no-tool-use outcome)
                 :detail      :no-submit-verdict-tool-use}}

        (:exhausted outcome)
        {:error :backend
         :data  {:reason   :backend
                 :detail   :verdict-inference-failed
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
         :data  {:reason      :unexpected-stop
                 :stop-reason :max_tokens
                 :detail      :verdict-no-progress}}

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
           params parent-ctx capture turn-count] :as ctx}
   post-error! on-end-turn-event]
  (let [turn    (when turn-count (dec (long @turn-count)))
        outcome (drive-turn! ctx params @messages-atom tool-defs)]
    (cond
      (:eligibility-empty outcome)
      (let [{:keys [policy candidates]} (:eligibility-empty outcome)]
        (transcript! transcript-fn
          {:event :llm/error :ts (now-ms)
           :data  {:reason     :invalid-request
                   :detail     :eligibility-empty-strict
                   :policy     policy
                   :candidates candidates}})
        ;; Fail-closed: the eligibility gate excluded every candidate target
        ;; and `:llm/eligibility-strict?` is set. Categorize as
        ;; :invalid-request so the chart's :error.llm.* / .invalid-request
        ;; transitions fire — the node fails rather than run a model the
        ;; host declared ineligible.
        (post-error! :invalid-request {:detail     :eligibility-empty-strict
                                       :policy     policy
                                       :candidates candidates})
        (reset! worker-state :dying)
        :error-and-die)

      ;; R7: keyword :model named an alias not present in :llm/aliases. Fail the
      ;; node with a categorized :invalid-request (never ships the keyword as a
      ;; model id to a backend).
      (:unknown-alias outcome)
      (let [alias (:unknown-alias outcome)
            known (:known outcome)]
        (transcript! transcript-fn
          {:event :llm/error :ts (now-ms)
           :data  {:reason  :invalid-request
                   :detail  :unknown-alias
                   :alias   alias
                   :known   known}})
        (post-error! :invalid-request {:detail :unknown-alias
                                       :alias  alias
                                       :known  known})
        (reset! worker-state :dying)
        :error-and-die)

      ;; R4: a STRING model reference is a categorized chart error — keyword
      ;; is the only legal model form. Never ships a string to a backend.
      (contains? outcome :string-model)
      (let [s (:string-model outcome)]
        (transcript! transcript-fn
          {:event :llm/error :ts (now-ms)
           :data  {:reason :invalid-request
                   :detail :string-model
                   :model  s}})
        (post-error! :invalid-request {:detail :string-model
                                       :model  s})
        (reset! worker-state :dying)
        :error-and-die)

      (contains? outcome :string-models)
      (let [ms  (:string-models outcome)
            bad (:bad outcome)]
        (transcript! transcript-fn
          {:event :llm/error :ts (now-ms)
           :data  {:reason :invalid-request
                   :detail :string-models
                   :models ms
                   :bad    bad}})
        (post-error! :invalid-request {:detail :string-models
                                       :models ms
                                       :bad    bad})
        (reset! worker-state :dying)
        :error-and-die)

      (:no-progress outcome)
      (do
        (transcript! transcript-fn
          {:event :llm/error :ts (now-ms)
           :data  {:reason      :unexpected-stop
                   :stop-reason :max_tokens
                   :detail      :no-forward-progress}})
        (post-error! :unexpected-stop {:stop-reason :max_tokens
                                       :detail      :no-forward-progress})
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
                                             :reason reason
                                             :category category
                                             :attempts attempts)})
        (post-error! reason (-> (select-keys details [:message :class])
                              (assoc :category category
                                     :attempts attempts)))
        (reset! worker-state :dying)
        :error-and-die)

      :else
      (let [response      (:ok outcome)
            {:keys [stop-reason content usage model elapsed-ms]} response
            ctx-window    (some-> model catalog/context-window)
            input-tokens  (:input-tokens usage)
            output-tokens (:output-tokens usage)
            output-tps    (when (and elapsed-ms output-tokens (pos? (long elapsed-ms)))
                            (Double/parseDouble
                              (format "%.2f" (/ (double output-tokens) (/ (double elapsed-ms) 1000.0)))))
            ctx-used-frac (when (and ctx-window input-tokens (pos? ctx-window))
                            (/ (double input-tokens) (double ctx-window)))
            ;; Externalize the FULL assistant response (all content blocks) to a blob — the "output"
            ;; half of this turn's I/O; inline keeps only block snippets + :io/ref.
            first-text    (->> content (filter #(= :text (:type %))) (map :text) (apply str))
            io-ref        (when (and capture turn)
                            ;; Best-effort: never let a storage failure abort the turn.
                            (try (capture/capture-blob! capture turn "response" content first-text)
                                 (catch Throwable _ nil)))]
        (transcript! transcript-fn
          {:event :llm/response
           :ts    (now-ms)
           :data  (cond-> {:stop-reason stop-reason
                           :n-blocks    (count content)
                           :usage       (or usage {})
                           :content     (mapv ->transcript-content-block content)
                           :invokeid    (:invokeid parent-ctx)}
                    model (assoc :model model)
                    elapsed-ms (assoc :elapsed-ms elapsed-ms)
                    output-tps (assoc :output-tps output-tps)
                    ctx-window (assoc :context-window ctx-window)
                    io-ref (assoc :io/ref (:io/ref io-ref))
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
          (let [final-text     (->> content
                                 (filter #(= :text (:type %)))
                                 (map :text)
                                 (apply str))
                base-data      {:text final-text
                                :from (->id-str (:invokeid parent-ctx))}
                verdict-schema (:verdict-schema params)
                wrap           (maybe-run-verdict-and-finalize-idle!
                                 ctx params @messages-atom verdict-schema turn base-data)]
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
                       :transcript-fn     transcript-fn
                       :capture           capture
                       :turn              turn}
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
                                     ctx params @messages-atom verdict-schema turn base-data)]
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
     :max-turns                    — total LLM round-trips before the worker
                                     self-cancels with :error.llm.max-turns. When
                                     a :budget-extender is supplied this is a SOFT
                                     checkpoint, not a hard kill (see below).
     :max-conversation-duration-ms — wall-clock budget (in ms) from worker
                                     start to a clean :end_turn. Self-cancels
                                     with :error.llm.timeout when exceeded. (Always
                                     a HARD limit — a :budget-extender cannot raise
                                     it, so it backstops runaway turn extensions.)
     :budget-extender              — OPTIONAL fn. When :max-turns is reached it is
                                     called with {:messages :turn-count :max-turns
                                     :elapsed-ms :params} and may return a NEW,
                                     larger turn limit to continue (logged as
                                     :llm/budget-extended), or nil/<= current to
                                     stop (→ :error.llm.max-turns, exactly as when
                                     no extender is set). Lets a caller replace the
                                     dumb integer with a progress decision (e.g. a
                                     judge-panel vote). Errors in it are caught and
                                     treated as a stop.

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
           params parent-ctx turn-count] :as ctx}]
  (let [{:keys [on-end-turn-event max-turns max-conversation-duration-ms budget-extender]
         :or   {on-end-turn-event :llm.idle}} params
        eff-max-turns (atom (when max-turns (long max-turns)))
        started-at  (now-ms)
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
                                              :data  {:text msg :invokeid (:invokeid parent-ctx)}})
                  (recur))
                (= :dying @worker-state) (recur)
                :else (recur)))

            (= :running s)
            (cond
              ;; Budget checks BEFORE issuing the next call. Fire the canonical
              ;; error and die — chart authors transition on :error.llm.*.
              (and max-turns (>= @turn-count (long @eff-max-turns)))
              (let [extension (when budget-extender
                                (try
                                  (budget-extender {:messages   @messages-atom
                                                    :turn-count @turn-count
                                                    :max-turns  @eff-max-turns
                                                    :elapsed-ms (- (now-ms) started-at)
                                                    :params     params
                                                    :env        (:env parent-ctx)})
                                  (catch Throwable t
                                    (transcript! transcript-fn
                                      {:event :llm/budget-extender-error :ts (now-ms)
                                       :data  {:message (.getMessage t)}})
                                    nil)))]
                (if (and extension (> (long extension) (long @eff-max-turns)))
                  (do
                    (transcript! transcript-fn
                      {:event :llm/budget-extended :ts (now-ms)
                       :data  {:from @eff-max-turns :to (long extension) :turns @turn-count}})
                    (reset! eff-max-turns (long extension))
                    (recur))
                  (do
                    (transcript! transcript-fn
                      {:event :llm/error :ts (now-ms)
                       :data  {:reason :max-turns :limit @eff-max-turns}})
                    (post-error! :max-turns {:limit @eff-max-turns :turns @turn-count})
                    (reset! worker-state :dying)
                    (recur))))

              (and max-conversation-duration-ms
                (>= (- (now-ms) started-at) (long max-conversation-duration-ms)))
              (let [elapsed (- (now-ms) started-at)]
                (transcript! transcript-fn
                  {:event :llm/error :ts (now-ms)
                   :data  {:reason     :timeout
                           :elapsed-ms elapsed
                           :limit-ms   max-conversation-duration-ms}})
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
                  :continue (recur)
                  :idle (recur)
                  :error-and-die (recur))))

            :else
            (recur))))
      (catch InterruptedException _
        (transcript! transcript-fn {:event :llm/worker-exit :ts (now-ms) :data {:reason :interrupted}}))
      (catch Throwable t
        (transcript! transcript-fn {:event :llm/worker-exit :ts (now-ms)
                                    :data  {:reason  :exception
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
                                     catalog-ratings eligibility-strict? aliases preferences]
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
          [real-defs name->tool-kw] (resolve-real-tools tool-registry real-tools)
          [event-defs name->event] (event-tool-defs (or allowed-events []))
          ;; Palette snapshot for region tools — built once at start. Late
          ;; registrations or unregistrations are NOT reflected in this
          ;; conversation (see region-tools.md "Timing and executable-content
          ;; order").
          registry-snapshot (service/entries env)
          [region-defs name->region] (region-tool-palette registry-snapshot
                                       chart-tools
                                       region-tool-default-timeout-ms)
          tool-defs         (into [] (concat real-defs event-defs region-defs))
          initial-msgs      (cond
                              (seq initial-messages) (vec initial-messages)
                              initial-user-message [(text-user-message initial-user-message)]
                              :else [])
          messages-atom     (atom initial-msgs)
          worker-state      (atom (if (seq initial-msgs) :running :awaiting-user))
          user-msg-queue    (ArrayBlockingQueue. 256)
          tool-reply-queue  (ArrayBlockingQueue. 64)
          retry-counts      (atom {})
          ;; Capture coordinates for this node invocation (io-refactor-plan.md §0/§3). node-id is the
          ;; chart element that holds the <invoke>; visit is its 0-based entry count this run; turn is
          ;; owned per-turn by the worker. Absent artifact-store => capture is a no-op (e.g. tests).
          node-id           (env-ns/context-element-id env)
          artifact-store    (:escapement/artifact-store env)
          visit-counts      (:escapement/visit-counts env)
          visit             (when (and artifact-store visit-counts)
                              (get (swap! visit-counts update node-id (fnil inc -1)) node-id))
          capture           (when artifact-store
                              {:store      artifact-store
                               :session-id parent-session-id
                               :node-id    node-id
                               :visit      (or visit 0)})
          turn-count        (atom 0)
          ;; Seed = the replayable input for this invocation (resolved params + initial messages).
          _                 (when capture
                              (try (capture/capture-seed! capture
                                     {:params params :initial-messages initial-msgs})
                                   (catch Throwable _ nil)))
          parent-ctx        {:env               env :queue queue
                             :parent-session-id parent-session-id
                             :invokeid          invokeid}
          ctx               {:backend             backend
                             :tool-registry       tool-registry
                             :transcript-fn       (or transcript-fn (fn [_] nil))
                             :name->tool-kw       name->tool-kw
                             :name->event-entry   name->event
                             :name->region-tool   name->region
                             :tool-reply-queue    tool-reply-queue
                             :tool-defs           tool-defs
                             :worker-state        worker-state
                             :messages-atom       messages-atom
                             :user-msg-queue      user-msg-queue
                             :retry-counts        retry-counts
                             :model-status        model-status
                             :default-models      default-models
                             :catalog-ratings     (or catalog-ratings {})
                             :eligibility-strict? (boolean eligibility-strict?)
                             :aliases             (or aliases {})
                             :preferences         (if (seq preferences)
                                                    (vec preferences)
                                                    preferences/default-preferences)
                             :capture             capture
                             :turn-count          turn-count
                             :params              params
                             :parent-ctx          parent-ctx}
          runnable          (fn [] (run-worker! ctx))
          ^Thread thread    (doto (Thread. ^Runnable runnable
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
        (cond-> {:event :llm/start :ts (now-ms)
                 :data  {:invokeid invokeid :session-id parent-session-id}}
          capture (assoc :transcript/node-id (:node-id capture)
                         :transcript/visit   (:visit capture))))
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
                              (map? event) (or (:event event) (:name event))
                              :else nil)
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
     proceeding on the unfiltered list (fail-open, the default).
   * `:aliases` (optional) — the validated `:llm/aliases` map
     (`{alias-kw [target-map …]}`, see `escapement.config`). The SINGLE source
     of truth for any concrete target. A node selects models EXCLUSIVELY by
     alias keyword: a keyword `:model`, or a `:models` vector of alias keywords.
     KEYWORD IS THE ONLY LEGAL MODEL FORM — a bare string `:model` or a string
     element inside `:models` is a categorized configuration/chart error, never
     a passthrough to a backend (R4). Each alias keyword expands to its ordered
     vector of cross-provider targets (failover order). Defaults to `{}` (so a
     keyword `:model` with no aliases configured is an unknown-alias error).
   * `:preferences` (optional) — the `:llm/preferences` vector of ALIAS
     KEYWORDS forming the default candidate set when a node names NO model.
     Resolution is uniform: the node's `:model`/`:models` (a vector of alias
     keywords) OR `:preferences` (when the node names nothing) are flattened
     over `:aliases` into the same ordered `{:provider :model :params}`
     candidate list. Defaults to the built-in `preferences/default-preferences`."
  [{:keys [backend tool-registry transcript-fn default-models
           catalog-ratings eligibility-strict? aliases preferences]}]
  (assert backend "backend is required")
  (assert tool-registry "tool-registry is required")
  (->LlmConversationProcessor backend tool-registry (or transcript-fn (fn [_] nil))
    (atom {}) (atom {}) (vec default-models)
    (or catalog-ratings {}) (boolean eligibility-strict?) (or aliases {})
    (if (seq preferences) (vec preferences) preferences/default-preferences)))

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
