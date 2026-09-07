(ns escapement.invocation.llm-conversation-test
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final on-entry script state transition]]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.chart.helpers :as h]
    [escapement.engine.testing :as dct]
    [escapement.invocation.llm-conversation :as llmc]
    [escapement.llm.protocol :as llm]
    [escapement.protocols :as proto]
    [escapement.storage.disk :as disk]
    [escapement.llm.types :as llm-types]
    [escapement.test-support :as ts]
    [escapement.tools.builtin :as builtin]
    [escapement.tools.protocol :as tp]
    [fulcro-spec.core :refer [=> assertions component specification]]
    [com.fulcrologic.statecharts.promise :as p]))

;; ---------------------------------------------------------------------------
;; Mock LLMBackend
;; ---------------------------------------------------------------------------

(defrecord MockBackend [responses call-log]
  llm/LLMBackend
  (send-turn [_ request]
    (p/do!
      (swap! call-log conj request)
      (let [r (ts/pop-first! responses)]
        (when (nil? r)
          (throw (ex-info "Mock backend out of canned responses" {:n-calls (count @call-log)})))
        r))))

(defn mock-backend
  "Build a mock backend whose `send-turn` will return canned responses in order."
  [responses]
  (->MockBackend (ts/queue responses) (atom [])))

(defn end-turn-response [text]
  {:stop-reason :end_turn
   :content     [{:type :text :text (or text "done")}]
   :usage       {:input-tokens 1 :output-tokens 1}
   :model       "mock"})

(defn tool-use-response
  "Build an assistant `tool_use` response. `tool-uses` is a vector of `{:id :name :input}`."
  [tool-uses]
  {:stop-reason :tool_use
   :content     (mapv (fn [{:keys [id name input]}]
                        {:type :tool_use :id id :name name :input input})
                  tool-uses)
   :usage       {:input-tokens 1 :output-tokens 1}
   :model       "mock"})

(defrecord AlwaysOkTool []
  tp/Tool
  (tool-name [_] :test/noop)
  (description [_] "always succeeds, posts no chart event")
  (input-schema [_] [:map])
  (invoke [_ _] {:result "ok" :is-error false}))

(defrecord ThrowingBackend [throw-fn]
  llm/LLMBackend
  (send-turn [_ _] (p/do! (throw (throw-fn)))))

(defn throwing-backend
  "Build a backend whose every `send-turn` throws `(throw-fn)`."
  [throw-fn]
  (->ThrowingBackend throw-fn))

;; ---------------------------------------------------------------------------
;; Helpers for building a test env with the LLM processor
;; ---------------------------------------------------------------------------

(defn- truncated-response
  "A `:max_tokens` assistant response — the API forcibly cut the model off."
  [text]
  {:stop-reason :max_tokens
   :content     [{:type :text :text (or text "cut off")}]
   :usage       {:input-tokens 1 :output-tokens 99}
   :model       "mock"})

(defn- new-llm-test-env
  [{:keys [statechart backend tool-registry transcript-fn session-dir artifact-store
           resilience]}]
  (let [processor (llmc/new-processor {:backend       backend
                                       :tool-registry (or tool-registry (tp/new-registry))
                                       :transcript-fn (or transcript-fn (fn [_] nil))
                                       :resilience    resilience})]
    (-> (dct/new-testing-env
          (cond-> {:statechart statechart}
            session-dir    (assoc :session-dir session-dir)
            artifact-store (assoc :artifact-store artifact-store))
          processor)
      (dct/start!))))

(defn- wait-quiescent!
  "Drain repeatedly, sleeping briefly between pumps to allow worker threads to send.
  Times out at `max-ms`."
  ([t] (wait-quiescent! t 2000))
  ([t max-ms]
   (let [deadline (+ (System/currentTimeMillis) max-ms)]
     (loop []
       (dct/drain! t)
       (Thread/sleep 30)
       (let [progressed? (try (dct/drain! t) true (catch Exception _ false))]
         (when (and progressed? (< (System/currentTimeMillis) deadline))
           (recur))))
     t)))

(defn- await-config!
  "Poll until `state-kw` is in the chart's configuration or `max-ms` elapses.
   Returns the testing-env."
  [t state-kw max-ms]
  (let [deadline (+ (System/currentTimeMillis) max-ms)]
    (loop []
      (dct/drain! t)
      (cond
        (dct/in? t state-kw) t
        (>= (System/currentTimeMillis) deadline) t
        :else (do (Thread/sleep 25) (recur))))))

(defn- await-pred!
  "Poll until 0-arg `pred` returns truthy or `max-ms` elapses, draining the
   testing-env each iteration so async worker-thread events get processed.
   Returns the testing-env. Use instead of a fixed `Thread/sleep` when waiting
   on work a worker thread performs off the pump (e.g. a backend call landing)."
  [t pred max-ms]
  (let [deadline (+ (System/currentTimeMillis) max-ms)]
    (loop []
      (dct/drain! t)
      (cond
        (pred) t
        (>= (System/currentTimeMillis) deadline) t
        :else (do (Thread/sleep 25) (recur))))))

;; ---------------------------------------------------------------------------
;; #1: Happy path, one event-tool fired
;; ---------------------------------------------------------------------------

(specification "effective-max-tokens is purely catalog-driven (models-api.json limit.output)"
  (assertions
    "resolves the model's catalog output cap"
    (llmc/effective-max-tokens "claude-sonnet-5") => 128000
    ;; A second model with a DIFFERENT cap, so this cannot pass by returning a
    ;; constant. `claude-3-sonnet-20240229` used to serve that role and was
    ;; retired from models.dev upstream — a catalog refresh legitimately drops
    ;; ids, and an id the catalog no longer knows correctly returns nil (the
    ;; backend's own default then applies), which the next assertion covers.
    (llmc/effective-max-tokens "claude-haiku-4-5") => 64000
    "unknown model → nil (backend wire default applies)"
    (llmc/effective-max-tokens "totally-unknown-model") => nil
    "nil model (backend default pick) → nil"
    (llmc/effective-max-tokens nil) => nil))

(specification "happy path: single event-tool fired then end_turn"
  (let [captured (atom [])
        backend  (mock-backend
                   [(tool-use-response [{:id "u1" :name "event__ok" :input {:msg "hello"}}])
                    (end-turn-response "bye")])
        chart    (chart/statechart
                   {:initial :work}
                   (state {:id :work :initial :running}
                     (state {:id :running}
                       (h/llm-conversation
                         {:id             "main"
                          :system         "do it"
                          :real-tools     []
                          :allowed-events [{:event       :ok
                                            :data-schema [:map [:msg :string]]}]
                          :message        "go"})
                       (transition {:event :ok :target :done}))
                     (final {:id :done})))
        t        (new-llm-test-env
                   {:statechart    chart
                    :backend       backend
                    :transcript-fn (fn [ev] (swap! captured conj ev))})
        t        (await-config! t :done 3000)]
    (assertions
      "chart received the event and reached :done"
      (dct/in? t :done) => true
      "captured a request and a response in transcript"
      (some #(= :llm/request (:event %)) @captured) => true
      (some #(= :llm/response (:event %)) @captured) => true)))

(specification "overrun primitive: a truncated turn is rerun (not continued) and recovers"
  ;; Processor-level resilience (the global-enable path: config/CLI → processor)
  ;; turns the overrun primitive on without a chart edit. First turn is
  ;; truncated at the cap; the rerun (identical context) finishes with an
  ;; event-tool that drives the chart to :done. Crucially the truncated segment
  ;; is NOT stitched via continuation — it is rerun from scratch.
  (let [captured (atom [])
        backend  (mock-backend
                   [(truncated-response "half")
                    (tool-use-response [{:id "u1" :name "event__ok" :input {:msg "hi"}}])])
        chart    (chart/statechart
                   {:initial :work}
                   (state {:id :work :initial :running}
                     (state {:id :running}
                       (h/llm-conversation
                         {:id             "main"
                          :system         "do it"
                          :real-tools     []
                          :allowed-events [{:event :ok :data-schema [:map [:msg :string]]}]
                          :message        "go"})
                       (transition {:event :ok :target :done}))
                     (final {:id :done})))
        t        (new-llm-test-env
                   {:statechart    chart
                    :backend       backend
                    :resilience    {:overrun {:max-retries 2}}
                    :transcript-fn (fn [ev] (swap! captured conj ev))})
        t        (await-config! t :done 3000)]
    (assertions
      "the rerun's event-tool drove the chart to :done"
      (dct/in? t :done) => true
      "exactly two backend calls — the original plus ONE overrun rerun"
      (count @(:call-log backend)) => 2
      "both calls carried the identical message context (same turn, rerun)"
      (apply = (map :messages @(:call-log backend))) => true
      "a retry event was emitted, categorized :overrun"
      (some #(and (= :llm/retry (:event %))
               (= :overrun (:category (:data %)))) @captured) => true)))

(specification "overrun primitive: :on-exhausted :fail fails the node after reruns are spent"
  (let [backend  (mock-backend
                   [(truncated-response "runaway-1")
                    (truncated-response "runaway-2")])
        chart    (chart/statechart
                   {:initial :work}
                   (state {:id :work :initial :running}
                     (state {:id :running}
                       (h/llm-conversation
                         {:id             "main"
                          :system         "do it"
                          :real-tools     []
                          :allowed-events [{:event :ok :data-schema [:map [:msg :string]]}]
                          :message        "go"})
                       (transition {:event :error.llm.unexpected-stop :target :failed}))
                     (final {:id :done})
                     (final {:id :failed})))
        t        (new-llm-test-env
                   {:statechart chart
                    :backend    backend
                    :resilience {:overrun {:max-retries 1 :on-exhausted :fail}}})
        t        (await-config! t :failed 3000)]
    (assertions
      "the node failed via :error.llm.unexpected-stop, never reaching :done"
      (dct/in? t :failed) => true
      (dct/in? t :done) => false
      "original attempt plus exactly :max-retries reruns were issued"
      (count @(:call-log backend)) => 2)))

(specification "stringified-JSON coercion: nested vector/map in tool_use input is re-parsed"
  ;; Small open-weight models (e.g. llama3.2:3b) regularly emit nested
  ;; collections as JSON strings inside a tool_use input — e.g.
  ;; `{"haikus": "[\"a\",\"b\",\"c\"]"}` instead of `{"haikus": [...]}`.
  ;; The decoder must JSON-parse the string when the target schema is a
  ;; collection. Validation still runs after; malformed input still fails.
  (let [captured (atom [])
        backend  (mock-backend
                   [(tool-use-response
                      [{:id "u1" :name "event__poet_done"
                        ;; nested array AND nested map arrive as raw strings
                        :input {:idx     "1"
                                :haikus  "[\"line1\\nline2\\nline3\",\"a\\nb\\nc\",\"x\\ny\\nz\"]"
                                :meta    "{\"genre\":\"haiku\"}"}}])
                    (end-turn-response "ok")])
        chart    (chart/statechart
                   {:initial :work}
                   (state {:id :work :initial :running}
                     (state {:id :running}
                       (h/llm-conversation
                         {:id             "poet"
                          :system         "poet"
                          :allowed-events [{:event       :poet-done
                                            :data-schema [:map
                                                          [:idx :int]
                                                          [:haikus [:vector :string]]
                                                          [:meta [:map [:genre :string]]]]}]
                          :message        "compose"})
                       (transition {:event :poet-done :target :done :type :internal}
                         (script {:expr (fn [_ data]
                                          (reset! captured (:_event data))
                                          nil)})))
                     (final {:id :done})))
        t        (new-llm-test-env {:statechart chart :backend backend})
        t        (await-config! t :done 3000)]
    (assertions
      "chart received the event (coercion + validation succeeded)"
      (dct/in? t :done) => true
      "idx string was coerced to int by string-transformer"
      (get-in @captured [:data :idx]) => 1
      "haikus stringified-array was JSON-parsed to a real vector"
      (vector? (get-in @captured [:data :haikus])) => true
      "vector has the right size and string elements"
      (count (get-in @captured [:data :haikus])) => 3
      "meta stringified-object was JSON-parsed and keys keywordized"
      (get-in @captured [:data :meta :genre]) => "haiku")))

;; ---------------------------------------------------------------------------
;; #2: Fan-out (3 tool_use in one assistant turn)
;; ---------------------------------------------------------------------------

(specification "fan-out: multiple event-tool calls in one assistant message"
  (let [backend  (mock-backend
                   [(tool-use-response
                      [{:id "u1" :name "event__found_bug" :input {:n 1}}
                       {:id "u2" :name "event__found_bug" :input {:n 2}}
                       {:id "u3" :name "event__found_bug" :input {:n 3}}])
                    (end-turn-response "done")])
        chart    (chart/statechart
                   {:initial :wrap}
                   (state {:id :wrap :initial :scanning}
                     (state {:id :scanning}
                       (h/llm-conversation
                         {:id             "scan"
                          :system         "scan"
                          :allowed-events [{:event       :found-bug
                                            :data-schema [:map [:n :int]]}]
                          :message        "scan"})
                       (transition {:event :llm.idle :target :done}))
                     (final {:id :done})))
        ;; We need to capture the findings during traversal; track via an atom and a script.
        captured (atom [])
        chart'   (chart/statechart
                   {:initial :wrap}
                   (state {:id :wrap :initial :scanning}
                     (state {:id :scanning}
                       (h/llm-conversation
                         {:id             "scan"
                          :system         "scan"
                          :allowed-events [{:event       :found-bug
                                            :data-schema [:map [:n :int]]}]
                          :message        "scan"})
                       (transition {:event :found-bug :target :scanning :type :internal}
                         (script {:expr (fn [env data]
                                          (swap! captured conj (:_event data))
                                          nil)}))
                       (transition {:event :llm.idle :target :done}))
                     (final {:id :done})))
        t        (new-llm-test-env {:statechart chart' :backend backend})
        t        (await-config! t :done 3000)]
    (assertions
      "chart reaches :done"
      (dct/in? t :done) => true
      "captured 3 :found-bug events"
      (count @captured) => 3
      "in order"
      (mapv #(get-in % [:data :n]) @captured) => [1 2 3])))

;; ---------------------------------------------------------------------------
;; #3: Real tool call (round-trip through registry)
;; ---------------------------------------------------------------------------

(defn- write-tmp-file! [content]
  (let [f (java.io.File/createTempFile "llm-conv-test" ".txt")]
    (spit f content)
    (.deleteOnExit f)
    (.getAbsolutePath f)))

(specification "real-tool call: dispatched through the registry, hidden from chart"
  (let [path     (write-tmp-file! "secret-contents")
        backend  (mock-backend
                   [(tool-use-response
                      [{:id "r1" :name "fs_read" :input {:path path}}])
                    (tool-use-response
                      [{:id "e1" :name "event__done" :input {}}])
                    (end-turn-response "ok")])
        registry (builtin/new-builtin-registry)
        seen     (atom [])
        chart    (chart/statechart
                   {:initial :wrap}
                   (state {:id :wrap :initial :work}
                     (state {:id :work}
                       (h/llm-conversation
                         {:id             "rw"
                          :real-tools     [:fs/read]
                          :allowed-events [{:event :done :data-schema [:map]}]
                          :message        "go"})
                       (transition {:event :done :target :finished}
                         (script {:expr (fn [_ d] (swap! seen conj (:_event d)) nil)})))
                     (final {:id :finished})))
        t        (new-llm-test-env {:statechart chart :backend backend :tool-registry registry})
        t        (await-config! t :finished 3000)
        ;; Inspect the messages stored on the worker (after end_turn it's :awaiting-user or already gone)
        request2 (-> backend :call-log deref second :messages)]
    (assertions
      "chart finished"
      (dct/in? t :finished) => true
      "chart saw exactly the :done event (no :fs/read)"
      (count @seen) => 1
      "second request includes the tool_result with file contents"
      (some (fn [m]
              (some (fn [b]
                      (and (= :tool_result (:type b))
                        (str/includes? (or (:content b) "") "secret-contents")))
                (:content m)))
        request2)
      => true)))

;; ---------------------------------------------------------------------------
;; #3b: real-tools selector — absent = expose everything in the registry
;; ---------------------------------------------------------------------------

(defn- last-request-tool-names
  "Return the set of tool `name` strings declared on the most recent request the
   mock backend received."
  [backend]
  (->> @(:call-log backend) last :tools (mapv :name) set))

(specification "real-tools selector: absent (nil) exposes every tool registered in the registry"
  (let [backend  (mock-backend [(tool-use-response [{:id "e" :name "event__done" :input {}}])
                                (end-turn-response "ok")])
        registry (builtin/new-builtin-registry)
        chart    (chart/statechart
                   {:initial :wrap}
                   (state {:id :wrap :initial :work}
                     (state {:id :work}
                       (h/llm-conversation
                         {:id             "all"
                          :system         "go"
                          ;; :real-tools intentionally omitted
                          :allowed-events [{:event :done :data-schema [:map]}]
                          :message        "go"})
                       (transition {:event :done :target :finished}))
                     (final {:id :finished})))
        t        (new-llm-test-env {:statechart chart :backend backend :tool-registry registry})
        _        (await-config! t :finished 3000)]
    (assertions
      "every builtin tool name made it into the request alongside the event tool"
      (last-request-tool-names backend)
      => (->> (tp/all-tools registry)
           (map #(:name (tp/tool->anthropic-tool-def %)))
           (cons "event__done")
           set))))

(specification "real-tools selector: an explicit selector vector is a whitelist"
  (let [backend  (mock-backend [(tool-use-response [{:id "e" :name "event__done" :input {}}])
                                (end-turn-response "ok")])
        registry (builtin/new-builtin-registry)
        chart    (chart/statechart
                   {:initial :wrap}
                   (state {:id :wrap :initial :work}
                     (state {:id :work}
                       (h/llm-conversation
                         {:id             "subset"
                          :system         "go"
                          :real-tools     [:fs/read :fs/grep]
                          :allowed-events [{:event :done :data-schema [:map]}]
                          :message        "go"})
                       (transition {:event :done :target :finished}))
                     (final {:id :finished})))
        t        (new-llm-test-env {:statechart chart :backend backend :tool-registry registry})
        _        (await-config! t :finished 3000)]
    (assertions
      "only the whitelisted real tools + the event tool"
      (last-request-tool-names backend)
      => #{"fs_read" "fs_grep" "event__done"})))

;; ---------------------------------------------------------------------------
;; #3c: prompt caching flows from flat authoring keys through to the Request
;; ---------------------------------------------------------------------------

(specification "flat cache-control flags reach the Request"
  (let [backend   (mock-backend [(tool-use-response [{:id "e" :name "event__done" :input {}}])
                                 (end-turn-response "ok")])
        registry  (builtin/new-builtin-registry)
        chart     (chart/statechart
                    {:initial :wrap}
                    (state {:id :wrap :initial :work}
                      (state {:id :work}
                        (h/llm-conversation
                          {:id                   "cached"
                           :system               "stable system prompt"
                           :real-tools           [:fs/read :fs/grep]
                           :system-cache-control {:type :ephemeral}
                           :tools-cache-control  {:type :ephemeral}
                           :allowed-events       [{:event :done :data-schema [:map]}]
                           :message              "go"})
                        (transition {:event :done :target :finished}))
                      (final {:id :finished})))
        t         (new-llm-test-env {:statechart chart :backend backend :tool-registry registry})
        _         (await-config! t :finished 3000)
        first-req (-> backend :call-log deref first)]
    (assertions
      ":system-cache-control was forwarded onto the Request"
      (:system-cache-control first-req)
      => {:type :ephemeral}

      ":tools-cache-control stamps the LAST tool def (so the prefix-through-end is cached)"
      (-> first-req :tools last :cache-control)
      => {:type :ephemeral}

      "earlier tool defs are NOT stamped (Anthropic caches the prefix only)"
      (every? nil? (mapv :cache-control (drop-last (:tools first-req))))
      => true)))

;; ---------------------------------------------------------------------------
;; #3d: auto-cache defaults
;; ---------------------------------------------------------------------------

(defn- run-cache-chart!
  "Spin up a tiny one-turn chart whose conversation flat opts are `params-extra`
   merged over a stable base, and return the first Request that landed on the
   mock backend."
  [params-extra]
  (let [backend  (mock-backend [(tool-use-response [{:id "e" :name "event__done" :input {}}])
                                (end-turn-response "ok")])
        registry (builtin/new-builtin-registry)
        base     {:system         "stable system prompt"
                  :real-tools     [:fs/read :fs/grep]
                  :allowed-events [{:event :done :data-schema [:map]}]
                  :message        "go"}
        chart    (chart/statechart
                   {:initial :wrap}
                   (state {:id :wrap :initial :work}
                     (state {:id :work}
                       (h/llm-conversation
                         (assoc (merge base params-extra) :id "auto"))
                       (transition {:event :done :target :finished}))
                     (final {:id :finished})))
        t        (new-llm-test-env {:statechart chart :backend backend :tool-registry registry})
        _        (await-config! t :finished 3000)]
    (-> backend :call-log deref first)))

(specification "auto-cache defaults"
  (component "no cache flags set → ephemeral defaults are applied"
    (let [req (run-cache-chart! {})]
      (assertions
        "system-cache-control defaulted to ephemeral"
        (:system-cache-control req) => {:type :ephemeral}

        "last tool stamped ephemeral via tools-cache-control default"
        (-> req :tools last :cache-control) => {:type :ephemeral}

        "earlier tools NOT stamped (prefix-up-to-marker rule)"
        (every? nil? (mapv :cache-control (drop-last (:tools req)))) => true)))

  (component ":auto-cache? false fully opts out"
    (let [req (run-cache-chart! {:auto-cache? false})]
      (assertions
        "no system-cache-control"
        (:system-cache-control req) => nil

        "no tool-level cache-control either"
        (every? nil? (mapv :cache-control (:tools req))) => true)))

  (component "explicit :system-cache-control overrides the auto-default"
    (let [req (run-cache-chart! {:system-cache-control {:type :ephemeral :ttl :1h}})]
      (assertions
        "explicit value wins"
        (:system-cache-control req) => {:type :ephemeral :ttl :1h})))

  (component "false on an individual marker disables just that one"
    (let [req (run-cache-chart! {:system-cache-control false})]
      (assertions
        "system disabled"
        (:system-cache-control req) => nil

        "tools STILL get the auto-default"
        (-> req :tools last :cache-control) => {:type :ephemeral}))))

;; ---------------------------------------------------------------------------
;; #3e: rolling MESSAGE-level cache breakpoints (task 003)
;; ---------------------------------------------------------------------------

(defn- total-cache-markers
  "Count cache_control markers a Request map carries across system + tools +
   messages (placement-level, pre-wire). Mirrors Anthropic's 4-breakpoint cap."
  [req]
  (+ (if (:system-cache-control req) 1 0)
     (count (filter :cache-control (:tools req)))
     (count (filter :cache-control (:messages req)))))

(defn- message-marker-indices
  "Indices of `:messages` carrying a `:cache-control` marker."
  [req]
  (->> (:messages req)
    (map-indexed (fn [i m] (when (:cache-control m) i)))
    (filter some?)
    vec))

(defn- run-multi-turn-cache-chart!
  "Drive a single conversation through N tool_use turns then an end_turn, using a
   real (chart-invisible) tool so the transcript grows turn-over-turn. Returns the
   vector of Request maps the mock backend recorded, one per turn."
  [n-tool-turns params-extra]
  (let [tool-turn (tool-use-response [{:id "u" :name "test_noop" :input {}}])
        backend   (mock-backend (into (vec (repeat n-tool-turns tool-turn))
                                  [(end-turn-response "ok")]))
        registry  (tp/new-registry [(->AlwaysOkTool)])
        base      {:system         "stable system prompt"
                   :real-tools     [:test/noop]
                   :message        "go"}
        chart     (chart/statechart
                    {:initial :wrap}
                    (state {:id :wrap :initial :work}
                      (state {:id :work}
                        (h/llm-conversation
                          (assoc (merge base params-extra) :id "rolling"))
                        (transition {:event :llm.idle :target :finished}))
                      (final {:id :finished})))
        t         (new-llm-test-env {:statechart chart :backend backend :tool-registry registry})
        _         (await-config! t :finished 4000)]
    (-> backend :call-log deref vec)))

(specification "rolling message breakpoints across a multi-turn conversation"
  (let [reqs (run-multi-turn-cache-chart! 3 {})]
    (assertions
      "the backend saw >= 3 turns"
      (>= (count reqs) 3) => true

      "the FIRST turn (only the inbound user msg) carries NO message marker"
      (message-marker-indices (first reqs)) => []

      "later turns place a marker, and never on the newest (trailing) message"
      (every? (fn [req]
                (let [idxs (message-marker-indices req)
                      last-idx (dec (count (:messages req)))]
                  (or (empty? idxs)
                    (and (not (some #{last-idx} idxs))
                      ;; at least one stable message exists past turn 1
                      (<= (apply max idxs) (dec last-idx))))))
        reqs)
      => true

      "the message-marker index ADVANCES turn-over-turn as the transcript grows"
      (let [maxes (->> reqs
                    (map message-marker-indices)
                    (filter seq)
                    (map (fn [idxs] (apply max idxs))))]
        (= maxes (sort maxes))) => true

      "at least one later turn actually placed a message marker"
      (boolean (some (comp seq message-marker-indices) reqs)) => true)))

(specification "message + system + tools markers never exceed the 4-cap"
  (let [reqs (run-multi-turn-cache-chart! 3 {})]
    (assertions
      "every turn stays within Anthropic's 4 breakpoints"
      (every? #(<= (total-cache-markers %) 4) reqs) => true)))

(specification "budget priority: system+tools consume budget first, messages get the remainder"
  ;; auto-cache on => system (1) + last-tool (1) = 2 used, leaving 2 for messages.
  ;; A {:tail 5} message strategy WANTS many markers but may only take the remainder.
  (let [reqs (run-multi-turn-cache-chart! 4 {:message-cache-control {:strategy {:tail 5}}})]
    (assertions
      "system marker present on every turn"
      (every? :system-cache-control reqs) => true

      "last tool marker present on every turn"
      (every? #(-> % :tools last :cache-control) reqs) => true

      "no turn ever exceeds 4 total markers"
      (every? #(<= (total-cache-markers %) 4) reqs) => true

      "message markers are capped at the remaining budget (4 - 2 = 2)"
      (every? #(<= (count (message-marker-indices %)) 2) reqs) => true

      "a later turn with enough stable messages uses the FULL remaining budget"
      (some #(= 2 (count (message-marker-indices %))) reqs) => true)))

(specification "message-cache-control knob enables/disables message markers"
  (component ":message-cache-control false disables message markers but keeps system/tools"
    (let [reqs (run-multi-turn-cache-chart! 3 {:message-cache-control false})]
      (assertions
        "no message marker on any turn"
        (every? (comp empty? message-marker-indices) reqs) => true

        "system marker still present (auto-cache default)"
        (every? :system-cache-control reqs) => true

        "tools marker still present"
        (every? #(-> % :tools last :cache-control) reqs) => true)))

  (component ":message-cache-control default (nil) → message markers ARE placed under auto-cache"
    (let [reqs (run-multi-turn-cache-chart! 3 {})]
      (assertions
        "at least one turn carries a message marker"
        (boolean (some (comp seq message-marker-indices) reqs)) => true)))

  (component "explicit :ttl flows onto the message marker"
    (let [reqs (run-multi-turn-cache-chart! 3 {:message-cache-control {:ttl :1h}})
          marked (->> reqs
                   (mapcat :messages)
                   (keep :cache-control)
                   first)]
      (assertions
        "message marker carries the 1h ttl"
        marked => {:type :ephemeral :ttl :1h}))))

(specification ":auto-cache? false disables ALL markers including messages (regression)"
  (let [reqs (run-multi-turn-cache-chart! 3 {:auto-cache? false})]
    (assertions
      "no system marker"
      (every? (comp nil? :system-cache-control) reqs) => true

      "no tool markers"
      (every? (fn [req] (every? nil? (map :cache-control (:tools req)))) reqs) => true

      "no message markers"
      (every? (comp empty? message-marker-indices) reqs) => true

      "total markers are zero on every turn"
      (every? #(zero? (total-cache-markers %)) reqs) => true)))

;; ---------------------------------------------------------------------------
;; #4: Bad input twice -> fatal error
;; ---------------------------------------------------------------------------

(specification "bad input twice on same tool_use_id triggers :error.llm.tool-validation"
  (let [;; The LLM keeps producing bad input (different ids each time mimicking re-tries).
        backend  (mock-backend
                   [(tool-use-response [{:id "x1" :name "event__pick" :input {}}])
                    (tool-use-response [{:id "x1" :name "event__pick" :input {}}])])
        err-seen (atom nil)
        chart    (chart/statechart
                   {:initial :wrap}
                   (state {:id :wrap :initial :work}
                     (state {:id :work}
                       (h/llm-conversation
                         {:id             "p"
                          :allowed-events [{:event       :pick
                                            :data-schema [:map [:choice :string]]}]
                          :message        "pick one"})
                       (transition {:event :error.llm.tool-validation :target :failed}
                         (script {:expr (fn [_ d]
                                          (reset! err-seen (:_event d))
                                          nil)})))
                     (final {:id :failed})))
        t        (new-llm-test-env {:statechart chart :backend backend})
        t        (await-config! t :failed 3000)]
    (assertions
      "chart reaches :failed"
      (dct/in? t :failed) => true
      "error event carries :reason :tool-validation"
      (get-in @err-seen [:data :reason]) => :tool-validation)))

;; ---------------------------------------------------------------------------
;; #5: Bad-then-good single retry recovery
;; ---------------------------------------------------------------------------

(specification "bad input then good input recovers and chart sees the good event"
  (let [backend (mock-backend
                  [(tool-use-response [{:id "y1" :name "event__pick" :input {}}])
                   (tool-use-response [{:id "y2" :name "event__pick" :input {:choice "alpha"}}])
                   (end-turn-response "ok")])
        seen    (atom nil)
        chart   (chart/statechart
                  {:initial :wrap}
                  (state {:id :wrap :initial :work}
                    (state {:id :work}
                      (h/llm-conversation
                        {:id             "p"
                         :allowed-events [{:event       :pick
                                           :data-schema [:map [:choice :string]]}]
                         :message        "pick one"})
                      (transition {:event :pick :target :done}
                        (script {:expr (fn [_ d] (reset! seen (:_event d)) nil)})))
                    (final {:id :done})))
        t       (new-llm-test-env {:statechart chart :backend backend})
        t       (await-config! t :done 3000)]
    (assertions
      "chart reaches :done"
      (dct/in? t :done) => true
      "chart saw the good :pick event"
      (get-in @seen [:data :choice]) => "alpha")))

;; ---------------------------------------------------------------------------
;; #6: Stop on state exit — worker future is done within a small grace period
;; ---------------------------------------------------------------------------

(specification "state exit stops the worker"
  (let [backend (mock-backend
                  [(end-turn-response "ready")
                   ;; If a second send-turn happens it would throw — that's fine.
                   ])
        proc    (llmc/new-processor {:backend backend :tool-registry (tp/new-registry)})
        chart   (chart/statechart
                  {:initial :wrap}
                  (state {:id :wrap :initial :bound}
                    (state {:id :bound}
                      (h/llm-conversation
                        {:id      "main"
                         :message "hi"})
                      (transition {:event :leave :target :done}))
                    (final {:id :done})))
        t       (-> (dct/new-testing-env {:statechart chart} proc)
                  (dct/start!))]
    (await-config! t :bound 2000)
    ;; Worker should be live now
    (let [sid         (:session-id t)
          info-before (llmc/worker-info proc sid "main")]
      (dct/run-events! t :leave)
      ;; Give the worker time to observe :dying
      (Thread/sleep 350)
      (assertions
        "worker existed during binding"
        (some? info-before) => true
        "chart reached :done"
        (dct/in? t :done) => true
        "after exit, no worker is registered for the invokeid"
        (llmc/worker-info proc sid "main") => nil))))

;; ---------------------------------------------------------------------------
;; #7: Idempotent re-entry — re-starting a worker stops the old one
;; ---------------------------------------------------------------------------

(specification "re-entry stops old worker and starts a new one"
  (let [backend (mock-backend
                  [(end-turn-response "a")
                   (end-turn-response "b")])
        proc    (llmc/new-processor {:backend backend :tool-registry (tp/new-registry)})
        env-map {:env {::sc/event-queue {}                  ;; placeholder, we'll use direct call via real env below
                       }}
        ;; To exercise idempotency directly, call start-invocation! twice with the same key
        ;; using a real env from a tiny chart.
        chart   (chart/statechart
                  {:initial :s}
                  (state {:id :s}))
        t       (-> (dct/new-testing-env {:statechart chart} proc)
                  (dct/start!))
        env     (:env t)
        sid     (:session-id t)
        ;; Build a processing env with a session id for env-ns/session-id to work.
        penv    (assoc env ::sc/vwmem (volatile! {::sc/session-id sid}))
        params  {:initial-user-message "x"}]
    (sp/start-invocation! proc penv {:invokeid "k1" :type :llm-conversation :params params})
    (let [first-info (llmc/worker-info proc sid "k1")]
      (sp/start-invocation! proc penv {:invokeid "k1" :type :llm-conversation :params params})
      (Thread/sleep 100)
      (let [second-info (llmc/worker-info proc sid "k1")]
        (assertions
          "second start replaced the entry"
          (identical? (:worker-state first-info) (:worker-state second-info)) => false
          "first worker is now :dying"
          @(:worker-state first-info) => :dying)))
    ;; cleanup
    (sp/stop-invocation! proc penv {:invokeid "k1" :type :llm-conversation})))

;; ---------------------------------------------------------------------------
;; #8: tell-llm mid-binding — sub-state pushes a user message
;; ---------------------------------------------------------------------------

(specification "tell-llm posts a user message into the active conversation"
  (let [backend (mock-backend
                  [(end-turn-response "ready")
                   (end-turn-response "got it")])
        chart   (chart/statechart
                  {:initial :wrap}
                  (state {:id :wrap :initial :bound}
                    (state {:id :bound :initial :a}
                      (h/llm-conversation
                        {:id      "main"
                         :message "hi"})
                      (transition {:event :llm.idle :target :a-saw-idle :type :internal})
                      (state {:id :a})
                      (state {:id :a-saw-idle}
                        (on-entry {}
                          (h/tell-llm {:expr (fn [_ _] "tell me more")}))
                        (transition {:event :llm.idle :target :done})))
                    (final {:id :done})))
        proc    (llmc/new-processor {:backend backend :tool-registry (tp/new-registry)})
        t       (-> (dct/new-testing-env {:statechart chart} proc)
                  (dct/start!))
        t       (await-config! t :done 3000)]
    (assertions
      "chart reached :done after second idle"
      (dct/in? t :done) => true
      "backend received exactly 2 turns"
      (count @(:call-log backend)) => 2
      "second turn's messages include the 'tell me more' user message"
      (->> @(:call-log backend) second :messages
        (mapcat :content)
        (some (fn [b] (and (= :text (:type b))
                        (= "tell me more" (:text b))))))
      => true)))

;; ---------------------------------------------------------------------------
;; #8: Per-invocation budgets — :max-turns and :max-conversation-duration-ms
;; ---------------------------------------------------------------------------

(specification ":max-turns budget fires :error.llm.max-turns"
  ;; The model keeps emitting tool_use forever (infinite loop). Without a
  ;; budget the worker would never stop; :max-turns 3 makes it self-cancel.
  ;; NB: under R1 an event-tool turn ends the turn (parks :awaiting-user),
  ;; so the looping tool must be a REAL tool that posts no chart event —
  ;; only then does the model keep emitting tool_use until the budget bites.
  (let [always-tool (tool-use-response
                      [{:id "u1" :name "test_noop" :input {}}])
        backend     (mock-backend [always-tool always-tool always-tool always-tool])
        registry    (tp/new-registry [(->AlwaysOkTool)])
        err-seen    (atom nil)
        chart       (chart/statechart
                      {:initial :wrap}
                      (state {:id :wrap :initial :work}
                        (state {:id :work}
                          (h/llm-conversation
                            {:id         "p"
                             :max-turns  3
                             :real-tools [:test/noop]
                             :message    "go"})
                          ;; Catch-all per-family.
                          (transition {:event :error.llm.* :target :failed}
                            (script {:expr (fn [_ d] (reset! err-seen (:_event d)) nil)})))
                        (final {:id :failed})))
        t           (new-llm-test-env {:statechart chart :backend backend :tool-registry registry})
        t           (await-config! t :failed 3000)]
    (assertions
      "chart reached :failed"
      (dct/in? t :failed) => true
      "error event was :error.llm.max-turns"
      (some-> @err-seen :name) => :error.llm.max-turns
      "carries the :limit"
      (get-in @err-seen [:data :limit]) => 3)))

(specification ":on-end-turn-event data carries inline :text when no artifact store (fallback)"
  ;; With no `:artifact-store` on the env (the default test env), there is no blob
  ;; to point at, so the conversation falls back to delivering the full text inline.
  (let [backend (mock-backend [(end-turn-response "the answer is 42")])
        seen    (atom nil)
        chart   (chart/statechart
                  {:initial :wrap}
                  (state {:id :wrap :initial :work}
                    (state {:id :work}
                      (h/llm-conversation
                        {:id      "advisor"
                         :message "go"})
                      (transition {:event :llm.idle :target :done}
                        (script {:expr (fn [_ d] (reset! seen (:_event d)) nil)})))
                    (final {:id :done})))
        t       (new-llm-test-env {:statechart chart :backend backend})
        t       (await-config! t :done 3000)]
    (assertions
      "chart reached :done"
      (dct/in? t :done) => true
      ":on-end-turn-event data has the assistant's final text inline"
      (get-in @seen [:data :text]) => "the answer is 42"
      "and the speaker's invokeid"
      (get-in @seen [:data :from]) => "advisor"
      "and no :output-ref handle (none could be written without a store)"
      (contains? (:data @seen) :output-ref) => false)))

(specification ":on-end-turn-event delivers an :output-ref handle (not inline text) when a store is present"
  ;; WITH an artifact store, the full assistant text is externalized to
  ;; nodes/<node-id>/<visit>/turns/<turn>/output.edn and the idle event carries
  ;; ONLY the :output-ref locator + a ≤80-char :io/snippet — never the full text —
  ;; so working memory, checkpoints, and the transcript stay tiny. Dereferencing
  ;; the handle reproduces the full {:text :from} map.
  (let [dir     (str (java.nio.file.Files/createTempDirectory "llmconv-output"
                       (into-array java.nio.file.attribute.FileAttribute [])))
        store   (disk/new-artifact-store dir)
        backend (mock-backend [(end-turn-response "the answer is 42")])
        seen    (atom nil)
        chart   (chart/statechart
                  {:initial :wrap}
                  (state {:id :wrap :initial :work}
                    (state {:id :work}
                      (h/llm-conversation
                        {:id      "advisor"
                         :message "go"})
                      (transition {:event :llm.idle :target :done}
                        (script {:expr (fn [_ d] (reset! seen (:_event d)) nil)})))
                    (final {:id :done})))
        t       (new-llm-test-env {:statechart     chart
                                   :backend        backend
                                   :session-dir    dir
                                   :artifact-store store})
        _       (await-config! t :done 3000)
        ed      (:data @seen)
        ref     (:output-ref ed)
        blob    (some-> ref (->> (proto/read-artifact store :test)) (->> (edn/read-string)))]
    (assertions
      "chart reached :done"
      (dct/in? t :done) => true
      "the idle event carries an :output-ref locator into the captured-io tree"
      (str/ends-with? (str ref) "/output.edn") => true
      "the idle event does NOT carry the full text inline"
      (contains? ed :text) => false
      "but carries a ≤80-char :io/snippet for human correlation"
      (:io/snippet ed) => "the answer is 42"
      "and the speaker's invokeid"
      (:from ed) => "advisor"
      "dereferencing the handle reproduces the full assistant text"
      (:text blob) => "the answer is 42"
      "the dereferenced blob also carries the speaker's invokeid"
      (:from blob) => "advisor")))

;; ---------------------------------------------------------------------------
;; R1: event-tool inside a :tool_use turn fires on-end-turn-event (glm-class)
;; ---------------------------------------------------------------------------

(specification "R1: event-tool in a :tool_use turn fires on-end-turn-event exactly once"
  ;; glm-class models batch the terminating event-tool into a :tool_use
  ;; response and never emit a separate :end_turn. The worker must still
  ;; fire on-end-turn-event (:llm.idle) and park in :awaiting-user.
  (let [idle-count (atom 0)
        seen       (atom nil)
        backend    (mock-backend
                     [(tool-use-response
                        [{:id    "e1" :name "event__done"
                          :input {}}])])
        chart      (chart/statechart
                     {:initial :wrap}
                     (state {:id :wrap :initial :work}
                       (state {:id :work}
                         (h/llm-conversation
                           {:id             "glm"
                            :real-tools     []
                            :allowed-events [{:event :done}]
                            :message        "go"})
                         (transition {:event :llm.idle :target :finished}
                           (script {:expr (fn [_ d]
                                            (swap! idle-count inc)
                                            (reset! seen (:_event d))
                                            nil)})))
                       (state {:id :finished})))
        t          (new-llm-test-env {:statechart chart :backend backend})
        t          (await-config! t :finished 3000)
        ;; Give any stray duplicate event time to land.
        _          (do (Thread/sleep 150) (dct/drain! t))]
    (assertions
      "chart reached :finished via :llm.idle"
      (dct/in? t :finished) => true
      "on-end-turn-event fired exactly once"
      @idle-count => 1
      "the idle event carries the speaker invokeid"
      (get-in @seen [:data :from]) => "glm"
      "backend was called exactly once (no continuation turn)"
      (count @(:call-log backend)) => 1)))

(specification "R1 de-dupe: event-tool turn then a stray :end_turn does NOT double-post"
  ;; The event-tool :tool_use turn parks the worker in :awaiting-user, so a
  ;; second canned :end_turn response is never consumed and on-end-turn-event
  ;; fires exactly once.
  (let [idle-count (atom 0)
        backend    (mock-backend
                     [(tool-use-response
                        [{:id "e1" :name "event__done" :input {}}])
                      (end-turn-response "stray")])
        chart      (chart/statechart
                     {:initial :wrap}
                     (state {:id :wrap :initial :work}
                       (state {:id :work}
                         (h/llm-conversation
                           {:id             "glm"
                            :real-tools     []
                            :allowed-events [{:event :done}]
                            :message        "go"})
                         (transition {:event :llm.idle :target :finished}
                           (script {:expr (fn [_ _]
                                            (swap! idle-count inc)
                                            nil)})))
                       (state {:id :finished})))
        t          (new-llm-test-env {:statechart chart :backend backend})
        t          (await-config! t :finished 3000)
        _          (do (Thread/sleep 200) (dct/drain! t))]
    (assertions
      "chart reached :finished"
      (dct/in? t :finished) => true
      "on-end-turn-event fired exactly once (no double-post)"
      @idle-count => 1
      "the stray :end_turn response was never consumed"
      (count @(:call-log backend)) => 1)))

;; ---------------------------------------------------------------------------
;; #9: :target routing for :llm.user-message (multi-LLM team pattern)
;; ---------------------------------------------------------------------------

(specification ":llm.user-message with :target reaches only the matching invocation"
  ;; Two LLM invocations live concurrently under one parent state. After the
  ;; initial turns settle, send a targeted user-message to "advisor"; assert
  ;; that ONLY advisor sees turn 2 (its backend was called a second time).
  (let [main-backend    (mock-backend [(end-turn-response "main idle")])
        advisor-backend (mock-backend [(end-turn-response "first")
                                       (end-turn-response "second")])
        ;; Selector backend routes per-conversation via the :model field —
        ;; charts use it as a per-invocation tag so we don't need two processors.
        ;; Mandatory-aliases model: chart nodes name models by alias keyword.
        ;; The alias targets carry distinct model ids ("main"/"advisor") that
        ;; the selector backend uses as per-invocation routing tags.
        aliases         {:main    [{:provider :openai :model "main"}]
                         :advisor [{:provider :openai :model "advisor"}]}
        selector        (reify llm/LLMBackend
                          (send-turn [_ request]
                            (p/do!
                              (case (:model request)
                                "main" (p/await! (llm/send-turn main-backend request))
                                "advisor" (p/await! (llm/send-turn advisor-backend request))))))
        chart           (chart/statechart
                          {:initial :work}
                          (state {:id :work}
                            (h/llm-conversation
                              {:id      "main"
                               :model   :main
                               :message "hello-main"})
                            (h/llm-conversation
                              {:id      "advisor"
                               :model   :advisor
                               :message "hello-advisor"})))
        proc            (llmc/new-processor {:backend       selector
                                             :aliases       aliases
                                             :tool-registry (tp/new-registry)})
        t               (-> (dct/new-testing-env {:statechart chart} proc)
                          (dct/start!))]
    ;; Wait for both initial turns to land and the workers to reach
    ;; :awaiting-user (poll, don't fixed-sleep: the turns complete on worker
    ;; threads, off the pump).
    (await-pred! t #(and (>= (count @(:call-log main-backend)) 1)
                         (>= (count @(:call-log advisor-backend)) 1))
      3000)
    (let [queue (::sc/event-queue (:env t))
          sid   (:session-id t)]
      (sp/send! queue (:env t)
        {:target sid :source-session-id sid
         :event  :llm.user-message
         :data   {:text "for advisor only" :target "advisor"}}))
    ;; Wait until the targeted second turn actually reaches advisor's backend.
    (await-pred! t #(>= (count @(:call-log advisor-backend)) 2) 3000)
    (assertions
      "main backend was called exactly once (initial turn only)"
      (count @(:call-log main-backend)) => 1
      "advisor backend was called twice (initial + targeted user-message)"
      (count @(:call-log advisor-backend)) => 2
      "advisor's second-turn messages include the targeted text"
      (let [msgs (->> @(:call-log advisor-backend) second :messages
                   (mapcat :content)
                   (filter #(= :text (:type %)))
                   (map :text))]
        (boolean (some #{"for advisor only"} msgs))) => true)))

;; ---------------------------------------------------------------------------
;; #10: Invocation ids may be keywords; routing and :from normalize to string
;; ---------------------------------------------------------------------------

(specification "keyword :id is accepted and normalized to string in routing/from"
  ;; Chart-author writes :id :researcher (keyword). The :on-end-turn-event
  ;; :from field should be the string "researcher", and a :target keyword
  ;; in tell-other-llm should still match.
  (let [backend (mock-backend [(end-turn-response "ok")])
        seen    (atom nil)
        chart   (chart/statechart
                  {:initial :wrap}
                  (state {:id :wrap :initial :work}
                    (state {:id :work}
                      (h/llm-conversation
                        {:id      :researcher             ;; <-- keyword
                         :message "go"})
                      (transition {:event :llm.idle :target :done}
                        (script {:expr (fn [_ d] (reset! seen (:_event d)) nil)})))
                    (final {:id :done})))
        t       (new-llm-test-env {:statechart chart :backend backend})
        t       (await-config! t :done 3000)]
    (assertions
      "chart finished"
      (dct/in? t :done) => true
      ":from is the canonical string form"
      (get-in @seen [:data :from]) => "researcher")))

(specification "->id-str normalizes keywords and strings"
  (assertions
    "keyword loses the colon"
    (escapement.invocation.llm-conversation/->id-str :foo) => "foo"
    "namespaced keyword keeps just the name"
    (escapement.invocation.llm-conversation/->id-str :a/foo) => "foo"
    "string passes through"
    (escapement.invocation.llm-conversation/->id-str "foo") => "foo"
    "nil stays nil"
    (escapement.invocation.llm-conversation/->id-str nil) => nil))

;; ---------------------------------------------------------------------------
;; Enriched transcript content (text/thinking/tool_use + tool-result)
;; ---------------------------------------------------------------------------

(specification "transcript :llm/response carries assistant content blocks"
  (let [captured     (atom [])
        backend      (mock-backend
                       [{:stop-reason :tool_use
                         :content     [{:type :text :text "thinking out loud"}
                                       {:type :thinking :thinking "deeper thought"}
                                       {:type  :tool_use :id "u1" :name "event__done"
                                        :input {:n 7}}]
                         :usage       {:input-tokens 1 :output-tokens 1}
                         :model       "mock"}
                        (end-turn-response "ok")])
        chart        (chart/statechart
                       {:initial :wrap}
                       (state {:id :wrap :initial :work}
                         (state {:id :work}
                           (h/llm-conversation
                             {:id             "trans"
                              :system         "long-and-static system prompt"
                              :real-tools     []
                              :allowed-events [{:event       :done
                                                :data-schema [:map [:n :int]]}]
                              :message        "go"})
                           (transition {:event :done :target :finished}))
                         (final {:id :finished})))
        t            (new-llm-test-env
                       {:statechart    chart
                        :backend       backend
                        :transcript-fn (fn [ev] (swap! captured conj ev))})
        _            (await-config! t :finished 3000)
        responses    (filter #(= :llm/response (:event %)) @captured)
        tool-results (filter #(= :llm/tool-result (:event %)) @captured)
        requests     (filter #(= :llm/request (:event %)) @captured)
        first-resp   (first responses)]
    (assertions
      "at least one :llm/response captured"
      (boolean (seq responses)) => true
      ":content vector is present"
      (vector? (get-in first-resp [:data :content])) => true
      "the three block types are surfaced (text, thinking, tool_use)"
      (set (mapv :type (get-in first-resp [:data :content])))
      => #{:text :thinking :tool_use}
      "tool_use block carries :input"
      (->> (get-in first-resp [:data :content])
        (some (fn [b] (when (= :tool_use (:type b)) (:input b)))))
      => {:n 7}
      ":invokeid is included on the response"
      (string? (get-in first-resp [:data :invokeid])) => true
      ":llm/request carries :user-blocks and :system-preview"
      (boolean (some (fn [r]
                       (and (vector? (get-in r [:data :user-blocks]))
                         (string? (get-in r [:data :system-preview]))))
                 requests))
      => true
      ":llm/tool-result was emitted for the event-tool dispatch"
      (boolean (seq tool-results)) => true
      "tool-result carries :tool, :is-error, :content-preview, :invokeid"
      (let [tr (first tool-results)]
        (and (= :done (get-in tr [:data :tool]))
          (false? (get-in tr [:data :is-error]))
          (= "ok" (get-in tr [:data :content-preview]))
          (string? (get-in tr [:data :invokeid]))))
      => true)))

(specification "R3 transcript: relative :fs/write surfaces :resolved-path in :llm/tool-result data"
  (let [session  (str (java.nio.file.Files/createTempDirectory
                        "llm-conv-r3" (make-array java.nio.file.attribute.FileAttribute 0)))
        captured (atom [])
        registry (builtin/new-builtin-registry)
        ;; CLI path: base-dir comes from the registry metadata.
        _        (alter-meta! registry assoc :escapement/base-dir session)
        backend  (mock-backend
                   [(tool-use-response
                      [{:id    "w1" :name "fs_write"
                        :input {:path "out/r3.txt" :content "hi-from-r3"}}])
                    (tool-use-response
                      [{:id "e1" :name "event__done" :input {}}])
                    (end-turn-response "ok")])
        chart    (chart/statechart
                   {:initial :wrap}
                   (state {:id :wrap :initial :work}
                     (state {:id :work}
                       (h/llm-conversation
                         {:id             "r3w"
                          :real-tools     [:fs/write]
                          :allowed-events [{:event :done :data-schema [:map]}]
                          :message        "go"})
                       (transition {:event :done :target :finished}))
                     (final {:id :finished})))
        t        (new-llm-test-env
                   {:statechart    chart
                    :backend       backend
                    :tool-registry registry
                    :transcript-fn (fn [ev] (swap! captured conj ev))})
        _        (await-config! t :finished 3000)
        tr       (->> @captured
                   (filter #(= :llm/tool-result (:event %)))
                   (filter #(= :fs/write (get-in % [:data :tool])))
                   first)
        resolved (get-in tr [:data :resolved-path])
        expected (.getAbsolutePath (java.io.File. session "out/r3.txt"))]
    (assertions
      "a :fs/write tool-result was captured"
      (some? tr) => true
      "transcript carries the resolved absolute path"
      resolved => expected
      "the file actually landed under the session dir"
      (.exists (java.io.File. session "out/r3.txt")) => true
      "and not under the process cwd"
      (.exists (java.io.File. "out/r3.txt")) => false)))

(specification "captured I/O: a worker run writes full request/response/tool-result blobs + seed and stamps :io/ref"
  (let [session-dir (str (java.nio.file.Files/createTempDirectory "cap-sess"
                           (into-array java.nio.file.attribute.FileAttribute [])))
        store       (disk/new-artifact-store session-dir)
        captured    (atom [])
        backend     (mock-backend
                      [(tool-use-response [{:id "u1" :name "event__ok" :input {:msg "hello"}}])
                       (end-turn-response "bye")])
        chart       (chart/statechart
                      {:initial :work}
                      (state {:id :work :initial :running}
                        (state {:id :running}
                          (h/llm-conversation
                            {:id             "main"
                             :system         "do it"
                             :real-tools     []
                             :allowed-events [{:event :ok :data-schema [:map [:msg :string]]}]
                             :message        "go"})
                          (transition {:event :ok :target :done}))
                        (final {:id :done})))
        t           (new-llm-test-env {:statechart     chart
                                       :backend        backend
                                       :session-dir    session-dir
                                       :artifact-store store
                                       :transcript-fn  (fn [ev] (swap! captured conj ev))})
        _           (await-config! t :done 3000)
        paths       (set (map :artifact/path (proto/list-artifacts store :dcch.test/session)))
        req-ev      (first (filter #(= :llm/request (:event %)) @captured))
        resp-ev     (first (filter #(= :llm/response (:event %)) @captured))
        tr-ev       (first (filter #(= :llm/tool-result (:event %)) @captured))
        req-ref     (get-in req-ev [:data :io/ref])]
    (assertions
      "a replayable seed.edn was captured for the invocation"
      (boolean (some #(str/ends-with? % "/seed.edn") paths)) => true
      "the turn's full request blob was captured"
      (boolean (some #(str/ends-with? % "/turns/0/request.edn") paths)) => true
      "the turn's full response blob was captured"
      (boolean (some #(str/ends-with? % "/turns/0/response.edn") paths)) => true
      "the tool-result was captured under tool-results/<tool_use_id>"
      (boolean (some #(str/includes? % "/turns/0/tool-results/u1") paths)) => true
      ":llm/request carries an :io/ref to its blob"
      (string? req-ref) => true
      ":llm/response carries an :io/ref"
      (string? (get-in resp-ev [:data :io/ref])) => true
      ":llm/tool-result carries an :io/ref"
      (string? (get-in tr-ev [:data :io/ref])) => true
      "the referenced request blob round-trips to the full request map (messages intact)"
      (vector? (:messages (edn/read-string (proto/read-artifact store :dcch.test/session req-ref))))
      => true)))

(specification "oversized text/thinking blocks are reduced to an inline snippet"
  ;; New contract (io-refactor-plan.md §0): the JSONL no longer carries full (or 8192-truncated)
  ;; payloads — only an ≤80-char human-correlation snippet. The full value is externalized to the
  ;; artifact store (proven separately in escapement.capture-test). With no artifact-store injected
  ;; here, capture is a no-op and we assert only the inline snippet behavior.
  (let [big      (apply str (repeat 5000 "x"))
        captured (atom [])
        backend  (mock-backend
                   [{:stop-reason :tool_use
                     :content     [{:type :text :text big}
                                   {:type :tool_use :id "u1" :name "event__done" :input {}}]
                     :usage       {:input-tokens 1 :output-tokens 1}
                     :model       "mock"}
                    (end-turn-response "ok")])
        chart    (chart/statechart
                   {:initial :wrap}
                   (state {:id :wrap :initial :work}
                     (state {:id :work}
                       (h/llm-conversation
                         {:id             "trunc"
                          :allowed-events [{:event :done :data-schema [:map]}]
                          :message        "go"})
                       (transition {:event :done :target :finished}))
                     (final {:id :finished})))
        t        (new-llm-test-env
                   {:statechart    chart :backend backend
                    :transcript-fn (fn [ev] (swap! captured conj ev))})
        _        (await-config! t :finished 3000)
        resp     (first (filter #(= :llm/response (:event %)) @captured))
        text     (->> (get-in resp [:data :content])
                   (some (fn [b] (when (= :text (:type b)) (:text b)))))]
    (assertions
      "the inline text is capped at the 80-char snippet length, not the full payload"
      (<= (count text) 80) => true
      "the snippet ends with the overflow ellipsis"
      (clojure.string/ends-with? text "…") => true
      "the full payload is far larger than the inline snippet"
      (< (count text) (count big)) => true)))

;; ---------------------------------------------------------------------------
;; Declarative :needs wiring (params->policy / candidate-models /
;; the :llm/model-policy-empty transcript event). The decision core
;; (catalog/satisfies-policy?) is covered in catalog_test.clj; this covers
;; the invocation-layer glue that reads :needs from params, filters
;; the auto-fallback list, and surfaces the empty-result event.
;; ---------------------------------------------------------------------------

(specification "params->policy canonicalizes the :needs gate"
  (assertions
    "no key → nil"
    (#'llmc/params->policy {}) => nil
    "empty :needs → nil (admits everything; no clause expressed)"
    (#'llmc/params->policy {:needs {}}) => nil
    ":needs bare value → :require clause"
    (#'llmc/params->policy {:needs {:vision? true}})
    => {:require {:vision? true} :min {} :max {}}
    ":needs [:>= n] → :min clause"
    (#'llmc/params->policy {:needs {:context-tokens [:>= 200000]}})
    => {:require {} :min {:context-tokens 200000} :max {}}
    ":needs [:<= n] → :max clause"
    (#'llmc/params->policy {:needs {:max-output-tokens [:<= 64000]}})
    => {:require {} :min {} :max {:max-output-tokens 64000}}))

;; R6 (mandatory-aliases): the eligibility gate now applies at TARGET
;; granularity inside `resolve-candidates` over alias-flattened candidates —
;; objective facts per provider+model, subjective scores by ALIAS keyword.
;; (Replaces the removed string-list `candidate-models` gate.)
(specification "resolve-candidates filters candidate TARGETS by :needs at target granularity (R6)"
  (let [aliases {:big   [{:provider :openai :model "gpt-5"}]              ; 400k window
                 :small [{:provider :openai :model "gpt-4o-mini"}]        ; 128k window
                 :mix   [{:provider :openai :model "gpt-4o-mini"}         ; ineligible
                         {:provider :anthropic :model "claude-opus-4-7"}]} ; 1M, eligible
        policy  {:require {} :min {:context-tokens 200000} :max {}}
        resolve (fn [prefs strict?]
                  (#'llmc/resolve-candidates {} (atom {}) prefs aliases policy {} strict?))]
    (assertions
      "an eligible alias keeps its target"
      (mapv :model (:candidates (resolve [:big] false))) => ["gpt-5"]
      "a mixed alias drops the ineligible target, keeps the eligible one (author order)"
      (mapv :model (:candidates (resolve [:mix] false))) => ["claude-opus-4-7"]
      "the surviving target carries its source :alias"
      (mapv :alias (:candidates (resolve [:mix] false))) => [:mix]
      "an all-ineligible alias: fail-open yields the UNFILTERED candidate"
      (mapv :model (:candidates (resolve [:small] false))) => ["gpt-4o-mini"]
      "an all-ineligible alias under strict → :eligibility-empty (no candidates)"
      (some? (:eligibility-empty (resolve [:small] true))) => true)))

(specification "resolve-candidates reads subjective scores by ALIAS keyword (R6)"
  (let [aliases {:gpt [{:provider :openai :model "gpt-5"}]}
        policy  {:require {} :min {:clojure 8} :max {}}
        resolve (fn [ratings]
                  (#'llmc/resolve-candidates {} (atom {}) [:gpt] aliases policy ratings false))]
    (assertions
      "alias-keyed rating satisfying the policy keeps the target"
      (mapv :model (:candidates (resolve {:gpt {:clojure 9}}))) => ["gpt-5"]
      "alias-keyed rating below the floor → fail-open keeps unfiltered candidate but flags the gap"
      (mapv :model (:candidates (resolve {:gpt {:clojure 3}}))) => ["gpt-5"])))

;; ---------------------------------------------------------------------------
;; Categorized backend errors → finer :error.llm.<category> chart events,
;; with full back-compat for uncategorized throwables.
;; ---------------------------------------------------------------------------

(defn- run-error-chart!
  "Run a one-turn chart against `backend` (which will throw). Returns the
   `:_event` map the chart received on the catch-all :error.llm.* transition."
  [backend]
  (let [err-seen (atom nil)
        captured (atom [])
        chart    (chart/statechart
                   {:initial :wrap}
                   (state {:id :wrap :initial :work}
                     (state {:id :work}
                       (h/llm-conversation
                         {:id         "p"
                          :message    "go"
                          ;; These specs assert the category→event
                          ;; mapping, not recovery. Disable the default
                          ;; transient retry so a thrown rate-limited/
                          ;; timeout/etc. fails fast deterministically
                          ;; instead of backing off past the timeout.
                          :resilience {:max-retries 0}})
                       (transition {:event :error.llm.* :target :failed}
                         (script {:expr (fn [_ d]
                                          (reset! err-seen (:_event d))
                                          nil)})))
                     (final {:id :failed})))
        t        (new-llm-test-env {:statechart    chart
                                    :backend       backend
                                    :transcript-fn (fn [ev] (swap! captured conj ev))})
        t        (await-config! t :failed 3000)]
    {:event @err-seen :in-failed? (dct/in? t :failed) :transcript @captured}))

(specification "categorized backend error → :error.llm.rate-limited"
  (let [{:keys [event in-failed? transcript]}
        (run-error-chart!
          (throwing-backend #(llm/llm-error :rate-limited "429 slow down"
                               {:status 429})))]
    (assertions
      "chart reached :failed"
      in-failed? => true
      "the categorized event name is :error.llm.rate-limited"
      (:name event) => :error.llm.rate-limited
      ":reason on the event data is the category"
      (get-in event [:data :reason]) => :rate-limited
      ":category is carried for observability"
      (get-in event [:data :category]) => :rate-limited
      ":llm/error transcript carries reason + category"
      (let [te (first (filter #(= :llm/error (:event %)) transcript))]
        [(get-in te [:data :reason]) (get-in te [:data :category])])
      => [:rate-limited :rate-limited]
      ":llm/model-down transcript carries the category"
      (->> transcript
        (filter #(= :llm/model-down (:event %)))
        first :data :category)
      => :rate-limited)))

(specification "UNCATEGORIZED backend throwable still yields :error.llm.backend (back-compat)"
  (let [{:keys [event in-failed? transcript]}
        (run-error-chart!
          (throwing-backend #(ex-info "kaboom" {:status 500})))]
    (assertions
      "chart reached :failed"
      in-failed? => true
      "the legacy event name is unchanged"
      (:name event) => :error.llm.backend
      ":reason stays :backend exactly as before"
      (get-in event [:data :reason]) => :backend
      "additive :category key is present and nil for uncategorized"
      (contains? (:data event) :category) => true
      (get-in event [:data :category]) => nil
      ":llm/error transcript reason is still :backend"
      (->> transcript (filter #(= :llm/error (:event %))) first :data :reason)
      => :backend)))

(specification "try-models! surfaces :llm/model-policy-empty when the gate excludes every candidate (fail-open)"
  (let [captured (atom [])
        backend  (mock-backend [(end-turn-response "ok")])
        result   (#'llmc/try-models!
                   {:backend         backend
                    :transcript-fn   (fn [ev] (swap! captured conj ev))
                    :worker-state    (atom :running)
                    :model-status    (atom {})
                    :aliases         {:small [{:provider :openai :model "gpt-4o-mini"}]}
                    :preferences     [:small]
                    :catalog-ratings {}
                    :parent-ctx      {:invokeid "iv" :parent-session-id "sess-1"}}
                   {:needs {:context-tokens [:>= 999999999]}}
                   [{:role :user :content [{:type :text :text "hi"}]}]
                   [])
        ev       (first (filter #(= :llm/model-policy-empty (:event %)) @captured))]
    (assertions
      "the event was emitted"
      (some? ev) => true
      "it names the invocation and session it belongs to"
      (select-keys (:data ev) [:invokeid :session-id]) => {:invokeid "iv" :session-id "sess-1"}
      "it carries the resolved (canonical) policy"
      (get-in ev [:data :policy]) => {:require {} :min {:context-tokens 999999999} :max {}}
      "strict? is false by default"
      (get-in ev [:data :strict?]) => false
      "the turn still completes via the unfiltered candidate (fail-open)"
      (some? (:ok result)) => true
      (:model-used result) => "gpt-4o-mini")))

(specification "try-models! fail-closed: :eligibility-strict? true → :eligibility-empty (no turn issued)"
  (let [captured (atom [])
        backend  (mock-backend [(end-turn-response "ok")])
        result   (#'llmc/try-models!
                   {:backend             backend
                    :transcript-fn       (fn [ev] (swap! captured conj ev))
                    :worker-state        (atom :running)
                    :model-status        (atom {})
                    :aliases             {:small [{:provider :openai :model "gpt-4o-mini"}]}
                    :preferences         [:small]
                    :catalog-ratings     {}
                    :eligibility-strict? true
                    :parent-ctx          {:invokeid "iv" :parent-session-id "sess-1"}}
                   {:needs {:context-tokens [:>= 999999999]}}
                   [{:role :user :content [{:type :text :text "hi"}]}]
                   [])
        ev       (first (filter #(= :llm/model-policy-empty (:event %)) @captured))]
    (assertions
      "the gap event still records strict?=true"
      (get-in ev [:data :strict?]) => true
      "and names the invocation and session it belongs to"
      (select-keys (:data ev) [:invokeid :session-id]) => {:invokeid "iv" :session-id "sess-1"}
      "no turn was issued (fail-closed)"
      (:ok result) => nil
      "the :eligibility-empty shape is returned for the caller to fail the node"
      (some? (:eligibility-empty result)) => true
      "the payload key is now :candidates (the excluded tagged targets), not :default-models"
      (mapv :model (get-in result [:eligibility-empty :candidates])) => ["gpt-4o-mini"])))

(specification "try-models! ratings come from the injected context value, keyed by ALIAS (two tables, one process)"
  (let [aliases {:mini [{:provider :openai :model "gpt-4o-mini"}]
                 :opus [{:provider :anthropic :model "claude-opus-4-1"}]}
        run (fn [ratings]
              (let [backend (mock-backend [(end-turn-response "ok")])]
                (#'llmc/try-models!
                  {:backend         backend
                   :transcript-fn   (fn [_] nil)
                   :worker-state    (atom :running)
                   :model-status    (atom {})
                   :aliases         aliases
                   :preferences     [:mini :opus]
                   :catalog-ratings ratings
                   :parent-ctx      {:invokeid "iv"}}
                  {:needs {:clojure [:>= 8]}}
                  [{:role :user :content [{:type :text :text "hi"}]}]
                  [])))]
    (assertions
      "alias-keyed ratings favoring :mini → gpt-4o-mini is the model used"
      (:model-used (run {:mini {:clojure 9}})) => "gpt-4o-mini"
      "a DIFFERENT injected ratings table in the same process → different pick"
      (:model-used (run {:opus {:clojure 9}})) => "claude-opus-4-1")))

;; ---------------------------------------------------------------------------
;; Resilience: unbounded :max_tokens continuation + transient-error retry
;; ---------------------------------------------------------------------------

(defn max-tokens-response
  "An assistant turn the API forcibly truncated at the output cap."
  [text]
  {:stop-reason :max_tokens
   :content     [{:type :text :text text}]
   :usage       {:input-tokens 2 :output-tokens 2}
   :model       "mock"})

(defn- drive-ctx [backend captured]
  {:backend        backend
   :transcript-fn  (fn [ev] (swap! captured conj ev))
   :worker-state   (atom :running)
   :model-status   (atom {})
   :default-models ["mock"]
   :parent-ctx     {:invokeid "iv"}})

(defn- flaky-backend
  "Throws `(throw-fn)` for the first `n-fail` calls, then returns `resp`.
   Returns `[backend counter-atom]`."
  [n-fail throw-fn resp]
  (let [counter (atom 0)]
    [(reify llm/LLMBackend
       (send-turn [_ _]
         (p/do!
           (if (<= (swap! counter inc) n-fail) (throw (throw-fn)) resp))))
     counter]))

(specification "resilience + continuation pure helpers"
  (assertions
    "params->resilience: defaults, per-key override keeps the rest"
    (#'llmc/params->resilience nil)
    => {:max-retries 3 :backoff-ms 500
        :latency {:first-token-ms nil :fallback nil}
        :overrun {:max-output-tokens nil :max-retries 0 :on-exhausted :truncate
                  :temperature-bump nil :temperature-max 1.0}
        :continuation {:enabled? true :max-segments 64 :max-chars 2000000}}
    (#'llmc/params->resilience {:resilience {:max-retries 0}})
    => {:max-retries 0 :backoff-ms 500
        :latency {:first-token-ms nil :fallback nil}
        :overrun {:max-output-tokens nil :max-retries 0 :on-exhausted :truncate
                  :temperature-bump nil :temperature-max 1.0}
        :continuation {:enabled? true :max-segments 64 :max-chars 2000000}}
    "a nested override keeps that group's other defaults (a shallow merge dropped them)"
    (:continuation (#'llmc/params->resilience {:resilience {:continuation {:max-segments 3}}}))
    => {:enabled? true :max-segments 3 :max-chars 2000000}
    "and the same holds for the other resilience groups"
    (:overrun (#'llmc/params->resilience {:resilience {:overrun {:max-retries 2}}}))
    => {:max-output-tokens nil :max-retries 2 :on-exhausted :truncate
        :temperature-bump nil :temperature-max 1.0}
    "merge-segment-content stitches text across a truncation boundary"
    (#'llmc/merge-segment-content [{:type :text :text "Hel"}]
      [{:type :text :text "lo"}])
    => [{:type :text :text "Hello"}]
    "non-text boundary just appends"
    (#'llmc/merge-segment-content [{:type :text :text "a"}]
      [{:type :tool_use :id "i" :name "n" :input {}}])
    => [{:type :text :text "a"} {:type :tool_use :id "i" :name "n" :input {}}]
    "empty continuation yields the accumulator unchanged"
    (#'llmc/merge-segment-content [{:type :text :text "a"}] []) => [{:type :text :text "a"}]
    "merge-with-usage sums numeric fields"
    (#'llmc/merge-with-usage {:input-tokens 2 :output-tokens 3}
      {:input-tokens 1 :output-tokens 4})
    => {:input-tokens 3 :output-tokens 7}))

(specification "drive-turn!: unbounded :max_tokens continuation stitches one terminal Response"
  (let [captured (atom [])
        backend  (mock-backend [(max-tokens-response "Hel")
                                (max-tokens-response "lo wor")
                                (end-turn-response "ld")])
        result   (#'llmc/drive-turn! (drive-ctx backend captured)
                   {} [{:role :user :content [{:type :text :text "hi"}]}] [])]
    (assertions
      "the merged turn is terminal, not truncated"
      (get-in result [:ok :stop-reason]) => :end_turn
      "text from every segment is stitched into one block"
      (->> (get-in result [:ok :content]) (filter #(= :text (:type %))) (map :text) (apply str))
      => "Hello world"
      "usage is summed across all three segments (2+2+1)"
      (get-in result [:ok :usage :output-tokens]) => 5
      "a :llm/continuation transcript event fired per continuation"
      (count (filter #(= :llm/continuation (:event %)) @captured)) => 2)))

(specification "drive-turn!: a no-forward-progress continuation aborts instead of looping"
  (let [captured (atom [])
        backend  (mock-backend [(max-tokens-response "X")
                                {:stop-reason :max_tokens :content []
                                 :usage       {} :model "mock"}])
        result   (#'llmc/drive-turn! (drive-ctx backend captured)
                   {} [{:role :user :content [{:type :text :text "hi"}]}] [])]
    (assertions
      "stuck model surfaces :no-progress (handler maps it to :error.llm.unexpected-stop)"
      (boolean (:no-progress result)) => true
      (contains? result :ok) => false)))

(specification "drive-turn!: a provider that RESTARTS instead of continuing is caught"
  ;; Verified live 2026-09-07: DeepSeek and opencode-go resume a prefilled
  ;; partial turn; OpenRouter and z.ai silently start the message over. Merging
  ;; a restart onto the accumulation produces a document containing its own
  ;; prefix twice — no error, plausible transcript. That is the bug this
  ;; guards, and the pre-existing :no-progress guard cannot see it, because a
  ;; restart is never exactly equal to what came before.

  (let [prefix   "The quick brown fox jumps over the lazy dog and keeps running for a while"
        captured (atom [])
        backend  (mock-backend [(max-tokens-response prefix)
                                ;; the provider ignores the prefill and starts again
                                (end-turn-response (str prefix " and then some more"))])
        result   (#'llmc/drive-turn! (drive-ctx backend captured)
                   {} [{:role :user :content [{:type :text :text "hi"}]}] [])]
    (assertions
      "the restart is not stitched — the turn aborts instead"
      (contains? result :ok) => false

      "reported through the existing terminal path, with its own detail"
      (:detail result) => :continuation-restarted

      "the content kept is what we had, NOT the duplicated merge"
      (->> (get-in result [:no-progress :content]) (filter #(= :text (:type %))) (map :text) (apply str))
      => prefix

      "and it is observable in the transcript"
      (count (filter #(= :llm/continuation-restarted (:event %)) @captured)) => 1))

  (component "a legitimate resume is never mistaken for a restart"
    (let [captured (atom [])
          backend  (mock-backend [(max-tokens-response "Hel")
                                  (end-turn-response "lo world")])
          result   (#'llmc/drive-turn! (drive-ctx backend captured)
                     {} [{:role :user :content [{:type :text :text "hi"}]}] [])]
      (assertions
        "the segments stitch as before"
        (->> (get-in result [:ok :content]) (filter #(= :text (:type %))) (map :text) (apply str))
        => "Hello world"

        "no restart was reported"
        (count (filter #(= :llm/continuation-restarted (:event %)) @captured)) => 0)))

  (component "the detector itself"
    (let [blocks (fn [t] [{:type :text :text t}])
          long-a (apply str (repeat 10 "abcdefghij"))]
      (assertions
        "a segment repeating the accumulated opening is a restart"
        (#'llmc/continuation-restarted? (blocks long-a) (blocks (str long-a " more"))) => true

        "a genuine resume is not"
        (#'llmc/continuation-restarted? (blocks long-a) (blocks " and then more")) => false

        "a short accumulation cannot false-positive on a coincidence"
        (#'llmc/continuation-restarted? (blocks "Hel") (blocks "lo world")) => false

        "nothing accumulated yet, nothing to restart"
        (#'llmc/continuation-restarted? nil (blocks long-a)) => false))))

(specification "drive-turn!: continuation is not attempted where it is KNOWN not to work"
  ;; Evidence-only, per provider. Ollama answers a trailing assistant message
  ;; with a hard 400 ("Expected last role User or Tool (or Assistant with
  ;; prefix True)"), the claude CLI accepts only type:user messages, and the
  ;; Responses wire starts a new message. Everything else keeps today's
  ;; behaviour — notably Anthropic, whose prefill support is documented and was
  ;; simply not verifiable here.

  (let [captured (atom [])
        truncated (assoc-in (max-tokens-response "half a document")
                    [:backend-metadata :prefill-unsupported?] true)
        backend  (mock-backend [truncated (end-turn-response "should never be asked for")])
        result   (#'llmc/drive-turn! (drive-ctx backend captured)
                   {} [{:role :user :content [{:type :text :text "hi"}]}] [])]
    (assertions
      "the turn stops at the truncation instead of spending a doomed call"
      (:detail result) => :continuation-unsupported

      "the partial content is kept, not discarded"
      (->> (get-in result [:no-progress :content]) (filter #(= :text (:type %))) (map :text) (apply str))
      => "half a document"

      "and it is observable"
      (count (filter #(= :llm/continuation-unsupported (:event %)) @captured)) => 1

      "no continuation was requested"
      (count (filter #(= :llm/continuation (:event %)) @captured)) => 0))

  (component "an untagged backend continues exactly as before"
    (let [captured (atom [])
          backend  (mock-backend [(max-tokens-response "Hel") (end-turn-response "lo world")])
          result   (#'llmc/drive-turn! (drive-ctx backend captured)
                     {} [{:role :user :content [{:type :text :text "hi"}]}] [])]
      (assertions
        "stitched as before"
        (->> (get-in result [:ok :content]) (filter #(= :text (:type %))) (map :text) (apply str))
        => "Hello world"))))

(defrecord AlwaysTruncatingBackend [n counter]
  ;; A model that hits the output cap on EVERY segment. Each segment is genuine
  ;; new content, so forward progress is real every round and the no-progress
  ;; guard never trips — the exact shape that used to grow the accumulation
  ;; until the JVM died with an OutOfMemoryError in the runner.
  llm/LLMBackend
  (send-turn [_ _]
    (p/do!
      (swap! counter inc)
      {:stop-reason :max_tokens
       ;; DISTINCT text per segment: identical repeated content would (rightly)
       ;; be classified as a restart by `continuation-restarted?`, which is a
       ;; different failure. This is the honest shape — real new content every
       ;; round, real forward progress, and still unbounded.
       :content     [{:type :text :text (apply str (repeat n (char (+ 97 (mod @counter 26)))))}]
       :usage       {:input-tokens 1 :output-tokens 1}
       :model       "mock"})))

(specification "drive-turn!: a model that truncates every segment is bounded, not fatal"
  ;; Regression: with only the forward-progress guard this looped forever,
  ;; growing the stitched content each round, and killed the runner with an
  ;; OutOfMemoryError that said nothing about continuation. Reachable with any
  ;; model under a tight output cap, not just adversarially.

  (component "the segment ceiling stops it"
    (let [captured (atom [])
          counter  (atom 0)
          result   (#'llmc/drive-turn! (drive-ctx (->AlwaysTruncatingBackend 8 counter) captured)
                     {:resilience {:continuation {:max-segments 5 :max-chars 1000000}}}
                     [{:role :user :content [{:type :text :text "hi"}]}] [])]
      (assertions
        "it terminates at all"
        (some? result) => true

        "through the terminal path, with a named detail"
        (:detail result) => :continuation-limit

        "after exactly the configured number of segments"
        @counter => 5

        "everything stitched so far is kept — the caller still gets what it paid for"
        (count (->> (get-in result [:no-progress :content])
                 (filter #(= :text (:type %))) (map :text) (apply str))) => 40

        "and the limit is observable"
        (count (filter #(= :llm/continuation-limit (:event %)) @captured)) => 1)))

  (component "the size ceiling stops it — the axis that actually OOMed"
    (let [captured (atom [])
          counter  (atom 0)
          result   (#'llmc/drive-turn! (drive-ctx (->AlwaysTruncatingBackend 500 counter) captured)
                     {:resilience {:continuation {:max-segments 1000 :max-chars 2000}}}
                     [{:role :user :content [{:type :text :text "hi"}]}] [])]
      (assertions
        "a few huge segments trip the char ceiling long before the segment count"
        (:detail result) => :continuation-limit

        "stopped as soon as the accumulation reached the ceiling"
        @counter => 4)))

  (component "the DEFAULT ceilings terminate it — this is the OOM regression"
    ;; No resilience params at all: the shape that killed the runner.
    (let [captured (atom [])
          counter  (atom 0)
          result   (#'llmc/drive-turn! (drive-ctx (->AlwaysTruncatingBackend 16 counter) captured)
                     {} [{:role :user :content [{:type :text :text "hi"}]}] [])]
      (assertions
        "it terminates on the built-in defaults, with no configuration"
        (:detail result) => :continuation-limit

        "at the default segment ceiling"
        @counter => 64)))

  (component "a well-behaved run is unaffected by the defaults"
    (let [captured (atom [])
          backend  (mock-backend [(max-tokens-response "Hel") (end-turn-response "lo world")])
          result   (#'llmc/drive-turn! (drive-ctx backend captured)
                     {} [{:role :user :content [{:type :text :text "hi"}]}] [])]
      (assertions
        "still stitches normally under the default ceilings"
        (->> (get-in result [:ok :content]) (filter #(= :text (:type %))) (map :text) (apply str))
        => "Hello world"

        "and no limit event fires"
        (count (filter #(= :llm/continuation-limit (:event %)) @captured)) => 0))))

(defrecord CountingTruncatingBackend [counter]
  ;; Always truncates, and counts the calls. Call COUNT is the whole point: the
  ;; cost of each configuration is what distinguishes them.
  llm/LLMBackend
  (send-turn [_ _]
    (p/do!
      (let [n (swap! counter inc)]
        {:stop-reason :max_tokens
         ;; distinct per call — identical text would trip the restart guard
         :content     [{:type :text :text (str "segment-" n " ")}]
         :usage       {:input-tokens 1 :output-tokens 1}
         :model       "mock"}))))

(specification "continuation off and bounded reruns are independent"
  ;; These two were once welded together: continuation was skipped ONLY when
  ;; `:overrun :max-retries` was positive, so a caller who wanted "do not
  ;; continue a truncated turn" had to buy a full extra generation on every
  ;; truncation. Measured, not theorised — the rerun fires with no
  ;; `:max-output-tokens` ceiling configured. All three combinations must now be
  ;; selectable on their own, and the call count is what proves it.

  (component "continuation OFF, no reruns — one call, and that is all"
    (let [counter (atom 0)
          result  (#'llmc/drive-turn! (drive-ctx (->CountingTruncatingBackend counter) (atom []))
                    {:resilience {:continuation {:enabled? false}}}
                    [{:role :user :content [{:type :text :text "hi"}]}] [])]
      (assertions
        "exactly one call — no continuation, and no rerun it never asked for"
        @counter => 1

        "the truncated turn is returned as-is"
        (get-in result [:ok :stop-reason]) => :max_tokens

        "with its partial content intact"
        (->> (get-in result [:ok :content]) (filter #(= :text (:type %))) (map :text) (apply str))
        => "segment-1 ")))

  (component "continuation OFF, WITH reruns — the rerun is still available"
    ;; Deliberately preserved: a second attempt can land a complete answer where
    ;; the first was cut off, so this is a retry with a real success rate, not a
    ;; no-op. A caller trading correctness against cost should choose it.
    (let [counter (atom 0)]
      (#'llmc/drive-turn! (drive-ctx (->CountingTruncatingBackend counter) (atom []))
        {:resilience {:overrun {:max-retries 1 :on-exhausted :truncate}}}
        [{:role :user :content [{:type :text :text "hi"}]}] [])
      (assertions
        "the initial call plus one rerun"
        @counter => 2)))

  (component "continuation ON — the default, unchanged"
    (let [counter (atom 0)
          result  (#'llmc/drive-turn! (drive-ctx (->CountingTruncatingBackend counter) (atom []))
                    {:resilience {:continuation {:max-segments 3}}}
                    [{:role :user :content [{:type :text :text "hi"}]}] [])]
      (assertions
        "it continues, stitching until the ceiling"
        @counter => 3

        "and the segments are stitched, not discarded"
        (->> (get-in result [:no-progress :content]) (filter #(= :text (:type %))) (map :text) (apply str))
        => "segment-1 segment-2 segment-3 ")))

  (component "turning continuation off costs nothing extra"
    ;; The bug, stated as a comparison: the same intent, at two different prices.
    (let [off-only (atom 0)
          via-overrun (atom 0)]
      (#'llmc/drive-turn! (drive-ctx (->CountingTruncatingBackend off-only) (atom []))
        {:resilience {:continuation {:enabled? false}}}
        [{:role :user :content [{:type :text :text "hi"}]}] [])
      (#'llmc/drive-turn! (drive-ctx (->CountingTruncatingBackend via-overrun) (atom []))
        {:resilience {:overrun {:max-retries 1 :on-exhausted :truncate}}}
        [{:role :user :content [{:type :text :text "hi"}]}] [])
      (assertions
        "the explicit off-switch spends one generation"
        @off-only => 1

        "reaching the same suppression through :overrun spends two"
        @via-overrun => 2))))

(defrecord AlwaysFailingBackend []
  llm/LLMBackend
  (send-turn [_ _]
    (p/do! (throw (llm/llm-error :overloaded "HTTP 503 upstream is overloaded" {})))))

(defn- error-ctx
  "A worker ctx complete enough to drive ONE turn through
   `handle-running-turn!`, which is where `:llm/error` is emitted."
  [backend captured]
  {:backend        backend
   :transcript-fn  (fn [ev] (swap! captured conj ev))
   :worker-state   (atom :running)
   :model-status   (atom {})
   :default-models ["mock"]
   :messages-atom  (atom [])
   :retry-counts   (atom {})
   :turn-count     (atom 1)
   :params         {:resilience {:max-retries 0 :backoff-ms 0}}
   :parent-ctx     {:invokeid "design-node" :parent-session-id "sess-1"}})

(specification "every invocation-scoped event carries its :invokeid"
  ;; `:llm/error` was the ONE llm event emitted without one, while its six
  ;; siblings (:llm/start :llm/request :llm/retry :llm/model-down
  ;; :llm/worker-exit :llm/response) all carried it. A host tap filtering on
  ;; :invokeid — the documented way to attribute an event to an invocation —
  ;; therefore received every event about a failing turn EXCEPT the one saying
  ;; what went wrong, so the failure reason, the category and the
  ;; `:partial-usage` of the failed turn were all unreachable.

  (component "a failing turn's error event is attributable"
    (let [captured (atom [])
          _        (#'llmc/handle-running-turn!
                     (error-ctx (->AlwaysFailingBackend) captured)
                     (fn [_ _] nil) :on-end)
          errs     (filter #(= :llm/error (:event %)) @captured)]
      (assertions
        "the error event was emitted"
        (count errs) => 1

        "and it names the invocation it belongs to"
        (get-in (first errs) [:data :invokeid]) => "design-node"

        "and the session, like its siblings"
        (get-in (first errs) [:data :session-id]) => "sess-1"

        "the diagnosis rides with it — a 503 must not read as anything else"
        (get-in (first errs) [:data :category]) => :overloaded)))

  (component "no invocation-scoped event in a failing run is left unattributable"
    ;; The general invariant, not just the one event the failing run happened to
    ;; expose. Run-level events (`:runner/*`, `:checkpoint/*`) are excluded by
    ;; construction — they belong to no invocation and none is emitted here.
    (let [captured (atom [])
          _        (#'llmc/handle-running-turn!
                     (error-ctx (->AlwaysFailingBackend) captured)
                     (fn [_ _] nil) :on-end)
          orphans  (->> @captured
                     (remove #(get-in % [:data :invokeid]))
                     (mapv :event))]
      (assertions
        "every emitted event is attributable to the invocation"
        orphans => []

        "and the run did emit something, so this is not vacuous"
        (boolean (seq @captured)) => true))))

(specification "try-models!: transient category is retried (bounded) then succeeds"
  (let [captured (atom [])
        [backend cnt] (flaky-backend 2 #(llm/llm-error :rate-limited "429" {})
                        (end-turn-response "ok"))
        result   (#'llmc/try-models!
                   {:backend        backend
                    :transcript-fn  (fn [ev] (swap! captured conj ev))
                    :worker-state   (atom :running)
                    :model-status   (atom {})
                    :default-models ["mock"]
                    :parent-ctx     {:invokeid "iv"}}
                   {:resilience {:max-retries 3 :backoff-ms 1}}
                   [{:role :user :content [{:type :text :text "hi"}]}]
                   [])]
    (assertions
      "succeeds after the bounded retries"
      (get-in result [:ok :stop-reason]) => :end_turn
      "two failures + one success = three calls"
      @cnt => 3
      "each retry emitted a :llm/retry transcript event"
      (count (filter #(= :llm/retry (:event %)) @captured)) => 2)))

(specification "try-models!: terminal category fails fast and is never retried"
  (let [captured (atom [])
        [backend cnt] (flaky-backend 99 #(llm/llm-error :auth "401" {})
                        (end-turn-response "never"))
        result   (#'llmc/try-models!
                   {:backend        backend
                    :transcript-fn  (fn [ev] (swap! captured conj ev))
                    :worker-state   (atom :running)
                    :model-status   (atom {})
                    :default-models ["mock"]
                    :parent-ctx     {:invokeid "iv"}}
                   {:resilience {:max-retries 3 :backoff-ms 1}}
                   [{:role :user :content [{:type :text :text "hi"}]}]
                   [])]
    (assertions
      "exhausted immediately (auth is terminal)"
      (boolean (:exhausted result)) => true
      "called exactly once — no retry"
      @cnt => 1
      "no :llm/retry transcript event"
      (count (filter #(= :llm/retry (:event %)) @captured)) => 0)))

;; ---------------------------------------------------------------------------
;; #11: :verdict-schema wrap-up inference
;; ---------------------------------------------------------------------------

(defn- verdict-tool-use-response
  "Build a tool_use response whose only block is a submit_verdict tool_use."
  [input]
  {:stop-reason :tool_use
   :content     [{:type :tool_use :id "v1" :name "submit_verdict" :input input}]
   :usage       {:input-tokens 1 :output-tokens 1}
   :model       "mock"})

(specification ":verdict-schema triggers a forced submit_verdict inference at idle"
  (let [verdict-payload {:status :ok :note "all-clear"}
        backend         (mock-backend
                          [(end-turn-response "done")
                           (verdict-tool-use-response verdict-payload)])
        seen-idle       (atom nil)
        captured        (atom [])
        chart           (chart/statechart
                          {:initial :wrap}
                          (state {:id :wrap :initial :work}
                            (state {:id :work}
                              (h/llm-conversation
                                {:id             "judge"
                                 :message        "go"
                                 :verdict-schema [:map
                                                  [:status :keyword]
                                                  [:note :string]]})
                              (transition {:event :llm.idle :target :done}
                                (script {:expr (fn [_ d] (reset! seen-idle (:_event d)) nil)})))
                            (final {:id :done})))
        t               (new-llm-test-env
                          {:statechart    chart
                           :backend       backend
                           :transcript-fn (fn [ev] (swap! captured conj ev))})
        t               (await-config! t :done 3000)
        log             @(:call-log backend)
        base-request    (first log)
        last-request    (last log)
        nudge-text      @#'llmc/wrap-up-nudge-text
        last-user-msg   (peek (vec (:messages last-request)))]
    (assertions
      "chart reached :done"
      (dct/in? t :done) => true
      "backend was called exactly twice (turn + wrap-up forced inference)"
      (count log) => 2
      "wrap-up request forced submit_verdict tool-choice (keyword :type per ToolChoice schema)"
      (:tool-choice last-request) => {:type :tool :name "submit_verdict"}
      "wrap-up tool-choice passes ToolChoice schema validation"
      (nil? (llm-types/validate-request
              (assoc last-request :model "mock" :messages [] :max-tokens 100)))
      => true
      "wrap-up request tools list contains only submit_verdict"
      (mapv :name (:tools last-request)) => ["submit_verdict"]
      "wrap-up request ends with a framework-owned user-message nudge"
      (:role last-user-msg) => :user
      (-> last-user-msg :content first :type) => :text
      (-> last-user-msg :content first :text) => nudge-text
      "base work-loop request does NOT contain the wrap-up nudge"
      (boolean
        (some (fn [m]
                (some #(and (= :text (:type %))
                         (= nudge-text (:text %)))
                  (:content m)))
          (:messages base-request)))
      => false
      ":on-end-turn-event data carries the validated :verdict"
      (get-in @seen-idle [:data :verdict]) => verdict-payload
      ":llm/verdict transcript event was emitted"
      (boolean (some #(= :llm/verdict (:event %)) @captured)) => true)))

(specification "verdict JSON shape coerces to schema (string-enum → keyword)"
  ;; Models can only emit JSON-typed values in tool_use input — strings,
  ;; numbers, lists of those, never keywords. When the chart's
  ;; verdict-schema uses keyword shapes (e.g. `[:enum :a :b]`), the
  ;; wrap-up must decode through Malli's json-transformer before
  ;; validating, otherwise an LLM that "correctly" returned the enum
  ;; would still fail validation. This regresses the matrix-team live
  ;; smoke failure.
  (let [keyword-schema [:map
                        [:status [:enum :proposed-new-version :done :stuck]]
                        [:summary :string]]
        json-payload   {:status "proposed-new-version" :summary "ready"}
        backend        (mock-backend
                         [(end-turn-response "ok")
                          (verdict-tool-use-response json-payload)])
        seen-idle      (atom nil)
        chart          (chart/statechart
                         {:initial :wrap}
                         (state {:id :wrap :initial :work}
                           (state {:id :work}
                             (h/llm-conversation
                               {:id             "judge"
                                :message        "go"
                                :verdict-schema keyword-schema})
                             (transition {:event :llm.idle :target :done}
                               (script {:expr (fn [_ d] (reset! seen-idle (:_event d)) nil)})))
                           (final {:id :done})))
        t              (new-llm-test-env {:statechart chart :backend backend})
        t              (await-config! t :done 3000)]
    (assertions
      "chart reached :done despite LLM returning string-shaped enum"
      (dct/in? t :done) => true
      "validated :verdict on idle event has the enum coerced to a keyword"
      (get-in @seen-idle [:data :verdict :status]) => :proposed-new-version
      "non-enum string field passes through unchanged"
      (get-in @seen-idle [:data :verdict :summary]) => "ready")))

(specification "nil :verdict-schema is identical to today's behavior"
  (let [backend   (mock-backend [(end-turn-response "free text")])
        seen-idle (atom nil)
        chart     (chart/statechart
                    {:initial :wrap}
                    (state {:id :wrap :initial :work}
                      (state {:id :work}
                        (h/llm-conversation
                          {:id      "free"
                           :message "go"})
                        (transition {:event :llm.idle :target :done}
                          (script {:expr (fn [_ d] (reset! seen-idle (:_event d)) nil)})))
                      (final {:id :done})))
        t         (new-llm-test-env {:statechart chart :backend backend})
        t         (await-config! t :done 3000)]
    (assertions
      "chart reached :done"
      (dct/in? t :done) => true
      "backend was called exactly once (no wrap-up inference)"
      (count @(:call-log backend)) => 1
      "and submit_verdict is NOT offered — the tool only appears when a schema declares it"
      (mapv :name (:tools (first @(:call-log backend)))) => []
      ":on-end-turn-event data carries the free text but no :verdict"
      (get-in @seen-idle [:data :text]) => "free text"
      "no :verdict key on idle event"
      (contains? (:data @seen-idle) :verdict) => false)))

(specification "verdict validation failure posts :error.llm.verdict-validation"
  (let [;; Wrap-up returns a payload that violates the schema (missing :note).
        backend  (mock-backend
                   [(end-turn-response "done")
                    (verdict-tool-use-response {:status :wrong-shape})])
        seen-err (atom nil)
        chart    (chart/statechart
                   {:initial :wrap}
                   (state {:id :wrap :initial :work}
                     (state {:id :work}
                       (h/llm-conversation
                         {:id             "judge"
                          :message        "go"
                          :verdict-schema [:map
                                           [:status :keyword]
                                           [:note :string]]})
                       (transition {:event :error.llm.verdict-validation :target :failed}
                         (script {:expr (fn [_ d] (reset! seen-err (:_event d)) nil)})))
                     (final {:id :failed})))
        t        (new-llm-test-env {:statechart chart :backend backend})
        t        (await-config! t :failed 3000)]
    (assertions
      "chart reached :failed via :error.llm.verdict-validation"
      (dct/in? t :failed) => true
      "error data includes :reason :verdict-validation"
      (get-in @seen-err [:data :reason]) => :verdict-validation
      "error data includes humanized :errors"
      (string? (get-in @seen-err [:data :errors])) => true
      "error data is ATTRIBUTED to the originating invoke, exactly as :llm.idle
       is. Without this an error rail keyed on invoke-id silently misses the
       event; a phase teardown then lands a stale error on the NEXT phase. The
       asymmetry — :from asserted on every success event and on no error event —
       is how the bug survived."
      (get-in @seen-err [:data :from]) => "judge")))

(specification "verdict-schema also fires on glm batched-event-tool turn-end"
  ;; The glm-class path posts an end-turn event from the :tool_use branch
  ;; when an event-tool was fired. The wrap-up should run there too.
  (let [verdict       {:done? true}
        backend       (mock-backend
                        [(tool-use-response
                           [{:id "e1" :name "event__finish" :input {}}])
                         (verdict-tool-use-response verdict)])
        seen          (atom nil)
        chart         (chart/statechart
                        {:initial :wrap}
                        (state {:id :wrap :initial :work}
                          (state {:id :work}
                            (h/llm-conversation
                              {:id             "glm"
                               :message        "go"
                               :allowed-events [{:event :finish :data-schema [:map]}]
                               :verdict-schema [:map [:done? :boolean]]})
                            (transition {:event :llm.idle :target :done}
                              (script {:expr (fn [_ d] (reset! seen (:_event d)) nil)})))
                          (final {:id :done})))
        t             (new-llm-test-env {:statechart chart :backend backend})
        t             (await-config! t :done 3000)
        log           @(:call-log backend)
        base-request  (first log)
        last-request  (last log)
        nudge-text    @#'llmc/wrap-up-nudge-text
        last-user-msg (peek (vec (:messages last-request)))]
    (assertions
      "chart reached :done"
      (dct/in? t :done) => true
      "second backend call was the verdict wrap-up"
      (count log) => 2
      ":verdict was attached to the idle event"
      (get-in @seen [:data :verdict]) => verdict
      "glm-class wrap-up request also ends with the framework-owned nudge"
      (-> last-user-msg :content first :text) => nudge-text
      "glm-class base work-loop request does NOT contain the nudge"
      (boolean
        (some (fn [m]
                (some #(and (= :text (:type %))
                         (= nudge-text (:text %)))
                  (:content m)))
          (:messages base-request)))
      => false)))


;; ---------------------------------------------------------------------------
;; Model identity: requested vs reported ids on the transcript
;; ---------------------------------------------------------------------------

(defn- events-named [transcript event-kw]
  (filterv #(= event-kw (:event %)) transcript))

(defn- run-aliased-chart!
  "Run a one-turn chart whose worker resolves `:primary` → provider :a /
   model \"asked-for\" and answers with `backend`. Returns the captured
   transcript once the chart has parked on :llm.idle (or `max-ms` elapsed)."
  [backend]
  (let [captured  (atom [])
        chart     (chart/statechart
                    {:initial :wrap}
                    (state {:id :wrap :initial :work}
                      (state {:id :work}
                        (h/llm-conversation {:id "p" :message "go"})
                        (transition {:event :llm.idle :target :done})
                        (transition {:event :error.llm.* :target :failed}))
                      (final {:id :done})
                      (final {:id :failed})))
        processor (llmc/new-processor
                    {:backend       backend
                     :tool-registry (tp/new-registry)
                     :transcript-fn (fn [ev] (swap! captured conj ev))
                     :aliases       {:primary [{:provider :a :model "asked-for"}]}
                     :preferences   [:primary]})
        t         (-> (dct/new-testing-env {:statechart chart} processor)
                    (dct/start!))
        t         (await-config! t :done 3000)]
    {:in-done? (dct/in? t :done) :transcript @captured}))

(specification "a silent model substitution is recorded on the transcript"
  ;; Providers substitute silently (a retired id answered by its successor)
  ;; and return a normal 200. The transcript keeps BOTH ids on the response
  ;; row and emits a dedicated :llm/model-substituted row so anything pricing
  ;; or comparing the run by the id it ASKED for can see it was wrong.

  (component "the provider answers with a different model than requested"
    (let [{:keys [in-done? transcript]}
          (run-aliased-chart!
            (mock-backend [(assoc (end-turn-response "ok") :model "answered-with")]))
          resp (first (events-named transcript :llm/response))
          subs (events-named transcript :llm/model-substituted)]
      (assertions
        "the turn still completes — a substitution is not an error"
        in-done? => true

        "the :llm/response row keeps the id the provider reported"
        (get-in resp [:data :model]) => "answered-with"

        "AND the id that was asked for, never collapsed into one field"
        (get-in resp [:data :model-requested]) => "asked-for"

        "and flags the mismatch"
        (get-in resp [:data :model-substituted?]) => true

        "exactly one :llm/model-substituted row is emitted"
        (count subs) => 1

        "carrying both ids"
        (select-keys (:data (first subs)) [:requested :reported])
        => {:requested "asked-for" :reported "answered-with"}

        "and attributable to the invocation and its session"
        (select-keys (:data (first subs)) [:invokeid :session-id])
        => {:invokeid "p" :session-id :dcch.test/session})))

  (component "the provider answers with the model requested"
    (let [{:keys [in-done? transcript]}
          (run-aliased-chart!
            (mock-backend [(assoc (end-turn-response "ok") :model "asked-for")]))
          resp (first (events-named transcript :llm/response))]
      (assertions
        "the turn completes"
        in-done? => true

        "the response row still records what was asked for"
        (get-in resp [:data :model-requested]) => "asked-for"

        "nothing is flagged"
        (contains? (:data resp) :model-substituted?) => false

        "and no substitution row is emitted"
        (events-named transcript :llm/model-substituted) => []))))

;; ---------------------------------------------------------------------------
;; :partial-usage — what a turn burned before it failed or was cancelled
;; ---------------------------------------------------------------------------

(defrecord FailingStreamBackend [usages throw-fn]
  ;; Streams a delta per entry in `usages` — each carrying the running
  ;; cumulative `:usage`, exactly as the real streaming backends do — and
  ;; THEN dies with `(throw-fn)`.
  llm/LLMBackend
  (send-turn [_ _] (p/do! (end-turn-response "unreachable")))
  llm/StreamingLLMBackend
  (stream-turn [_ _ on-delta]
    (p/do!
      (doseq [u usages]
        (on-delta {:type :text-delta :text "tok" :usage u}))
      (throw (throw-fn)))))

(defn- streaming-error-ctx
  "`error-ctx` with streaming on, so the delta-sink (and therefore the
   partial-usage accounting) is engaged."
  [backend captured]
  (assoc (error-ctx backend captured)
    :params {:stream? true :resilience {:max-retries 0 :backoff-ms 0}}))

(specification "a turn that did not complete still reports what it burned"
  ;; The running usage from a streamed turn's deltas used to die with the
  ;; stream. A cancelled run is exactly when someone wants to know what it
  ;; cost, so the LAST cumulative usage seen rides on the failure rows.

  (component "a streamed turn that errors → :llm/error carries :partial-usage"
    (let [captured (atom [])
          backend  (->FailingStreamBackend
                     [{:input-tokens 10 :output-tokens 1}
                      {:input-tokens 10 :output-tokens 4}]
                     (fn [] (llm/llm-error :overloaded "503" {})))
          _        (#'llmc/handle-running-turn!
                     (streaming-error-ctx backend captured)
                     (fn [_ _] nil) :on-end)
          err      (first (events-named @captured :llm/error))]
      (assertions
        "the error row was emitted"
        (some? err) => true

        "it carries the usage seen on the wire before the failure"
        (get-in err [:data :partial-usage]) => {:input-tokens 10 :output-tokens 4}

        "and is still attributable"
        (get-in err [:data :invokeid]) => "design-node")))

  (component "a streamed turn that is cancelled → :llm/worker-exit carries :partial-usage"
    (let [captured (atom [])
          backend  (->FailingStreamBackend
                     [{:input-tokens 10 :output-tokens 4}]
                     (fn [] (InterruptedException. "cancelled")))
          _        (#'llmc/handle-running-turn!
                     (streaming-error-ctx backend captured)
                     (fn [_ _] nil) :on-end)
          exit     (first (events-named @captured :llm/worker-exit))]
      (assertions
        "the worker-exit row was emitted for the mid-turn interrupt"
        (get-in exit [:data :reason]) => :interrupted-mid-turn

        "it carries the usage burned before the cancel"
        (get-in exit [:data :partial-usage]) => {:input-tokens 10 :output-tokens 4}

        "and is attributable to the invocation and its session"
        (select-keys (:data exit) [:invokeid :session-id])
        => {:invokeid "design-node" :session-id "sess-1"})))

  (component "nothing is invented when nothing was seen"
    ;; The non-streaming backend fails before any usage reaches the wire;
    ;; the error row must not claim otherwise.
    (let [captured (atom [])
          _        (#'llmc/handle-running-turn!
                     (error-ctx (->AlwaysFailingBackend) captured)
                     (fn [_ _] nil) :on-end)
          err      (first (events-named @captured :llm/error))]
      (assertions
        "the error row was emitted"
        (some? err) => true

        "with no :partial-usage key at all"
        (contains? (:data err) :partial-usage) => false))))

;; ---------------------------------------------------------------------------
;; :invokeid on the resolution-failure and budget events
;; ---------------------------------------------------------------------------

(specification "resolution failures are attributable :llm/error rows"
  ;; These fail BEFORE a request is built (nothing ever reaches a backend),
  ;; so the :llm/error row is the only evidence the invocation produced.

  (component "fail-closed :needs gate → :eligibility-empty-strict"
    (let [captured (atom [])
          ctx      (assoc (error-ctx (mock-backend [(end-turn-response "never")]) captured)
                     :aliases             {:small [{:provider :openai :model "gpt-4o-mini"}]}
                     :preferences         [:small]
                     :catalog-ratings     {}
                     :eligibility-strict? true
                     :params              {:needs {:context-tokens [:>= 999999999]}})
          _        (#'llmc/handle-running-turn! ctx (fn [_ _] nil) :on-end)
          err      (first (events-named @captured :llm/error))]
      (assertions
        "the error row is the strict eligibility failure"
        (select-keys (:data err) [:reason :detail])
        => {:reason :invalid-request :detail :eligibility-empty-strict}

        "and is attributable to the invocation and its session"
        (select-keys (:data err) [:invokeid :session-id])
        => {:invokeid "design-node" :session-id "sess-1"})))

  (component "a keyword :model naming no configured alias → :unknown-alias"
    (let [captured (atom [])
          ctx      (assoc (error-ctx (mock-backend [(end-turn-response "never")]) captured)
                     :aliases {:known [{:provider :a :model "m"}]}
                     :params  {:model :nope})
          _        (#'llmc/handle-running-turn! ctx (fn [_ _] nil) :on-end)
          err      (first (events-named @captured :llm/error))]
      (assertions
        "the error row is the unknown-alias failure, naming the alias"
        (select-keys (:data err) [:reason :detail :alias :known])
        => {:reason :invalid-request :detail :unknown-alias :alias :nope :known [:known]}

        "and is attributable to the invocation and its session"
        (select-keys (:data err) [:invokeid :session-id])
        => {:invokeid "design-node" :session-id "sess-1"})))

  (component "a STRING :model → :string-model"
    (let [captured (atom [])
          ctx      (assoc (error-ctx (mock-backend [(end-turn-response "never")]) captured)
                     :params {:model "some-string"})
          _        (#'llmc/handle-running-turn! ctx (fn [_ _] nil) :on-end)
          err      (first (events-named @captured :llm/error))]
      (assertions
        "the error row is the string-model failure, carrying the offending string"
        (select-keys (:data err) [:reason :detail :model])
        => {:reason :invalid-request :detail :string-model :model "some-string"}

        "and is attributable to the invocation and its session"
        (select-keys (:data err) [:invokeid :session-id])
        => {:invokeid "design-node" :session-id "sess-1"}))))

(defn- run-budget-chart!
  "Run the :max-turns 2 looping-tool chart from the max-turns spec with a
   `:budget-extender`. `n-responses` tool_use turns are canned. Returns the
   captured transcript, the :error.llm.* event the chart saw, and the calls
   the extender received."
  [budget-extender n-responses]
  (let [always-tool (tool-use-response [{:id "u1" :name "test_noop" :input {}}])
        backend     (mock-backend (vec (repeat n-responses always-tool)))
        registry    (tp/new-registry [(->AlwaysOkTool)])
        captured    (atom [])
        err-seen    (atom nil)
        chart       (chart/statechart
                      {:initial :wrap}
                      (state {:id :wrap :initial :work}
                        (state {:id :work}
                          (h/llm-conversation
                            {:id              "p"
                             :max-turns       2
                             :budget-extender budget-extender
                             :real-tools      [:test/noop]
                             :message         "go"})
                          (transition {:event :error.llm.* :target :failed}
                            (script {:expr (fn [_ d] (reset! err-seen (:_event d)) nil)})))
                        (final {:id :failed})))
        t           (new-llm-test-env {:statechart    chart
                                       :backend       backend
                                       :tool-registry registry
                                       :transcript-fn (fn [ev] (swap! captured conj ev))})
        t           (await-config! t :failed 3000)]
    {:in-failed? (dct/in? t :failed)
     :event      @err-seen
     :transcript @captured
     :n-calls    (count @(:call-log backend))}))

(specification ":budget-extender at the turn cap"
  ;; :max-turns is a SOFT cap when a :budget-extender is supplied: the worker
  ;; asks it, once, each time the cap is reached. Either answer is recorded
  ;; on the transcript, attributed to the invocation.

  (component "an extender that grants more turns → :llm/budget-extended, and the run continues"
    (let [calls    (atom [])
          extender (fn [{:keys [max-turns] :as call}]
                     (swap! calls conj call)
                     ;; Grant once (2 → 4); decline the second time.
                     (when (= 2 max-turns) 4))
          {:keys [in-failed? event transcript n-calls]} (run-budget-chart! extender 4)
          ext      (events-named transcript :llm/budget-extended)]
      (assertions
        "the extender was consulted at the original cap and again at the raised one"
        (mapv #(select-keys % [:turn-count :max-turns]) @calls)
        => [{:turn-count 2 :max-turns 2} {:turn-count 4 :max-turns 4}]

        "the extender's call carries the conversation so far and the elapsed time"
        (every? #(and (vector? (:messages %)) (number? (:elapsed-ms %))) @calls) => true

        "exactly one :llm/budget-extended row was emitted"
        (count ext) => 1

        "recording the raise"
        (select-keys (:data (first ext)) [:from :to :turns]) => {:from 2 :to 4 :turns 2}

        "and attributable to the invocation and its session"
        (select-keys (:data (first ext)) [:invokeid :session-id])
        => {:invokeid "p" :session-id :dcch.test/session}

        "the model was driven for all four turns — the raised cap was honoured"
        n-calls => 4

        "then the (raised) cap bit: chart reached :failed on :error.llm.max-turns with the raised limit"
        [in-failed? (:name event) (get-in event [:data :limit])] => [true :error.llm.max-turns 4])))

  (component "an extender that throws → :llm/budget-extender-error, and the cap stands"
    (let [extender (fn [_] (throw (ex-info "extender exploded" {})))
          {:keys [in-failed? event transcript n-calls]} (run-budget-chart! extender 2)
          errs     (events-named transcript :llm/budget-extender-error)]
      (assertions
        "exactly one :llm/budget-extender-error row was emitted"
        (count errs) => 1

        "carrying the throwable's message"
        (get-in (first errs) [:data :message]) => "extender exploded"

        "and attributable to the invocation and its session"
        (select-keys (:data (first errs)) [:invokeid :session-id])
        => {:invokeid "p" :session-id :dcch.test/session}

        "no :llm/budget-extended row was emitted"
        (events-named transcript :llm/budget-extended) => []

        "the original cap stood: two turns, then :error.llm.max-turns with the original limit"
        [n-calls in-failed? (:name event) (get-in event [:data :limit])]
        => [2 true :error.llm.max-turns 2]

        "and the :max-turns :llm/error row is attributable to the invocation"
        (let [err (first (filter #(= :max-turns (get-in % [:data :reason]))
                                 (events-named transcript :llm/error)))]
          (select-keys (:data err) [:reason :limit :invokeid :session-id]))
        => {:reason :max-turns :limit 2 :invokeid "p" :session-id :dcch.test/session}))))

(specification ":max-conversation-duration-ms budget — the :timeout :llm/error row is attributable"
  ;; A looping real tool with a 1 ms wall-clock budget: the first turn always
  ;; exhausts it, so the worker emits :llm/error {:reason :timeout} and dies.
  ;; Like every sibling error row it must carry :invokeid/:session-id.
  (let [always-tool (tool-use-response [{:id "u1" :name "test_noop" :input {}}])
        backend     (mock-backend (vec (repeat 4 always-tool)))
        registry    (tp/new-registry [(->AlwaysOkTool)])
        captured    (atom [])
        err-seen    (atom nil)
        chart       (chart/statechart
                      {:initial :wrap}
                      (state {:id :wrap :initial :work}
                        (state {:id :work}
                          (h/llm-conversation
                            {:id                           "p"
                             :max-turns                    50
                             :max-conversation-duration-ms 1
                             :real-tools                   [:test/noop]
                             :message                      "go"})
                          (transition {:event :error.llm.* :target :failed}
                            (script {:expr (fn [_ d] (reset! err-seen (:_event d)) nil)})))
                        (final {:id :failed})))
        t           (new-llm-test-env {:statechart    chart
                                       :backend       backend
                                       :tool-registry registry
                                       :transcript-fn (fn [ev] (swap! captured conj ev))})
        t           (await-config! t :failed 3000)
        err         (first (filter #(= :timeout (get-in % [:data :reason]))
                                   (events-named @captured :llm/error)))]
    (assertions
      "chart reached :failed on :error.llm.timeout"
      [(dct/in? t :failed) (:name @err-seen)] => [true :error.llm.timeout]
      "the :timeout :llm/error row carries the limit and the invocation identity"
      (select-keys (:data err) [:limit-ms :invokeid :session-id])
      => {:limit-ms 1 :invokeid "p" :session-id :dcch.test/session})))

;; ---------------------------------------------------------------------------
;; #11b: the verdict tool rides the OPENING turn (one request, not two)
;;
;; Measured on z.ai's Anthropic-compat endpoint, 8 trials each, identical
;; prompt/model/schema:
;;   tool retro-fitted onto a 2nd turn : tool_use 5/8  (~62%)
;;   tool offered on the 1st turn      : tool_use 8/8  (100%; text alongside 8/8)
;; Forcing :tool-choice is NOT a fix — that endpoint ignores tool_choice (4/8).
;; So the turn STRUCTURE is the fix, and these specs pin it.
;; ---------------------------------------------------------------------------

(defn- text+verdict-response
  "One assistant message carrying BOTH prose text and a submit_verdict tool_use —
   the shape offering the tool up front is designed to produce."
  ([input] (text+verdict-response "prose answer" input "v1"))
  ([text input] (text+verdict-response text input "v1"))
  ([text input id]
   {:stop-reason :tool_use
    :content     [{:type :text :text text}
                  {:type :tool_use :id id :name "submit_verdict" :input input}]
    :usage       {:input-tokens 1 :output-tokens 1}
    :model       "mock"}))

(specification "submit_verdict is offered on the OPENING turn, unforced, alongside the node's own tools"
  (let [backend (mock-backend [(text+verdict-response {:status :ok :note "n"})])
        chart   (chart/statechart
                  {:initial :wrap}
                  (state {:id :wrap :initial :work}
                    (state {:id :work}
                      (h/llm-conversation
                        {:id             "judge"
                         :message        "go"
                         :allowed-events [{:event :finish :data-schema [:map]}]
                         :verdict-schema [:map [:status :keyword] [:note :string]]})
                      (transition {:event :llm.idle :target :done}))
                    (final {:id :done})))
        t       (new-llm-test-env {:statechart chart :backend backend})
        t       (await-config! t :done 3000)
        log     @(:call-log backend)
        req1    (first log)]
    (assertions
      "chart reached :done"
      (dct/in? t :done) => true
      "the VERY FIRST request already carries submit_verdict, alongside the node's event tools.
       Before this fix `:tools` on turn 1 was empty and the verdict tool was retro-fitted onto a
       follow-up turn the model had no reason to comply with."
      (set (mapv :name (:tools req1))) => #{"event__finish" "submit_verdict"}
      "the working turn does NOT force tool-choice — z.ai's Anthropic-compat endpoint ignores
       tool_choice entirely (measured 4/8, no better than unforced), so forcing buys nothing and
       would suppress the real tools"
      (:tool-choice req1) => nil
      "ONE request for the whole organ turn — the wrap-up inference, which re-sent the entire
       conversation, never ran"
      (count log) => 1)))

(specification "a verdict submitted during the working turn ends the turn and skips the wrap-up"
  (let [verdict   {:status :ok :note "all-clear"}
        backend   (mock-backend [(text+verdict-response "here is my prose" verdict)])
        seen-idle (atom nil)
        captured  (atom [])
        chart     (chart/statechart
                    {:initial :wrap}
                    (state {:id :wrap :initial :work}
                      (state {:id :work}
                        (h/llm-conversation
                          {:id             "judge"
                           :message        "go"
                           :verdict-schema [:map [:status :keyword] [:note :string]]})
                        (transition {:event :llm.idle :target :done}
                          (script {:expr (fn [_ d] (reset! seen-idle (:_event d)) nil)})))
                      (final {:id :done})))
        t         (new-llm-test-env {:statechart    chart
                                     :backend       backend
                                     :transcript-fn (fn [ev] (swap! captured conj ev))})
        t         (await-config! t :done 3000)
        log       @(:call-log backend)
        nudge     @#'llmc/wrap-up-nudge-text]
    (assertions
      "chart reached :done"
      (dct/in? t :done) => true
      "exactly one backend call"
      (count log) => 1
      "the idle event carries the validated verdict"
      (get-in @seen-idle [:data :verdict]) => verdict
      "AND the prose text from the same message — callers want both, and prompts already ask
       for both"
      (get-in @seen-idle [:data :text]) => "here is my prose"
      "the wrap-up nudge was never sent to any request"
      (boolean
        (some (fn [req]
                (some (fn [m] (some #(= nudge (:text %)) (:content m))) (:messages req)))
          log))
      => false
      ":llm/verdict transcript event is labelled with the path that produced it"
      (->> @captured (filter #(= :llm/verdict (:event %))) first :data :source)
      => :turn-tool-call)))

(specification "prose-only turn still falls back to the wrap-up inference"
  ;; The fallback is KEPT on purpose: it costs nothing when unused (it does not
  ;; run at all if the turn already carried a verdict) and it is the only thing
  ;; that rescues a model which answered in prose only.
  (let [verdict   {:status :ok :note "recovered"}
        backend   (mock-backend [(end-turn-response "prose only, no tool call")
                                 (verdict-tool-use-response verdict)])
        seen-idle (atom nil)
        captured  (atom [])
        chart     (chart/statechart
                    {:initial :wrap}
                    (state {:id :wrap :initial :work}
                      (state {:id :work}
                        (h/llm-conversation
                          {:id             "judge"
                           :message        "go"
                           :verdict-schema [:map [:status :keyword] [:note :string]]})
                        (transition {:event :llm.idle :target :done}
                          (script {:expr (fn [_ d] (reset! seen-idle (:_event d)) nil)})))
                      (final {:id :done})))
        t         (new-llm-test-env {:statechart    chart
                                     :backend       backend
                                     :transcript-fn (fn [ev] (swap! captured conj ev))})
        t         (await-config! t :done 3000)
        log       @(:call-log backend)]
    (assertions
      "chart reached :done — the fallback rescued the turn"
      (dct/in? t :done) => true
      "turn 1 DID offer the tool (the model simply declined it)"
      (mapv :name (:tools (first log))) => ["submit_verdict"]
      "turn 2 is the fallback: submit_verdict alone, forced"
      (:tool-choice (second log)) => {:type :tool :name "submit_verdict"}
      "verdict still lands on the idle event"
      (get-in @seen-idle [:data :verdict]) => verdict
      ":llm/verdict is labelled as the fallback path, so a run's cost profile is auditable
       from the transcript alone"
      (->> @captured (filter #(= :llm/verdict (:event %))) first :data :source)
      => :wrap-up-inference)))

(specification "an invalid up-front verdict gets one corrective retry inside the turn"
  ;; Unlike the wrap-up inference (which has no room to correct), the model is
  ;; still mid-turn here: hand back the humanized errors as a tool_result and
  ;; let it fix them.
  (let [good      {:status :ok :note "fixed"}
        backend   (mock-backend [(text+verdict-response "try 1" {:status :ok} "v1")
                                 (text+verdict-response "try 2" good "v2")])
        seen-idle (atom nil)
        captured  (atom [])
        chart     (chart/statechart
                    {:initial :wrap}
                    (state {:id :wrap :initial :work}
                      (state {:id :work}
                        (h/llm-conversation
                          {:id             "judge"
                           :message        "go"
                           :verdict-schema [:map [:status :keyword] [:note :string]]})
                        (transition {:event :llm.idle :target :done}
                          (script {:expr (fn [_ d] (reset! seen-idle (:_event d)) nil)})))
                      (final {:id :done})))
        t         (new-llm-test-env {:statechart    chart
                                     :backend       backend
                                     :transcript-fn (fn [ev] (swap! captured conj ev))})
        t         (await-config! t :done 3000)]
    (assertions
      "chart reached :done on the corrected attempt"
      (dct/in? t :done) => true
      "the corrected verdict is what reaches the chart"
      (get-in @seen-idle [:data :verdict]) => good
      "the bad attempt came back as an is-error tool_result carrying the schema errors"
      (boolean
        (some #(and (= :llm/tool-result (:event %))
                 (= "submit_verdict" (get-in % [:data :tool]))
                 (true? (get-in % [:data :is-error])))
          @captured))
      => true)))

(specification "two invalid up-front verdicts post :error.llm.verdict-validation"
  (let [backend  (mock-backend [(text+verdict-response "try 1" {:status :ok} "v1")
                                (text+verdict-response "try 2" {:status :ok} "v2")])
        seen-err (atom nil)
        chart    (chart/statechart
                   {:initial :wrap}
                   (state {:id :wrap :initial :work}
                     (state {:id :work}
                       (h/llm-conversation
                         {:id             "judge"
                          :message        "go"
                          :verdict-schema [:map [:status :keyword] [:note :string]]})
                       (transition {:event :error.llm.verdict-validation :target :failed}
                         (script {:expr (fn [_ d] (reset! seen-err (:_event d)) nil)})))
                     (final {:id :failed})))
        t        (new-llm-test-env {:statechart chart :backend backend})
        t        (await-config! t :failed 3000)]
    (assertions
      "chart reached :failed via :error.llm.verdict-validation — NOT the generic
       :error.llm.tool-validation the other tool kinds raise"
      (dct/in? t :failed) => true
      "error data names the verdict reason"
      (get-in @seen-err [:data :reason]) => :verdict-validation
      "error data carries the humanized schema errors"
      (string? (get-in @seen-err [:data :errors])) => true
      "and is attributed to the originating invoke, like every other :error.llm.*"
      (get-in @seen-err [:data :from]) => "judge")))

(specification "submit_verdict batched with an event-tool: the verdict wins, no wrap-up runs"
  (let [verdict   {:done? true}
        backend   (mock-backend
                    [{:stop-reason :tool_use
                      :content     [{:type :text :text "both"}
                                    {:type :tool_use :id "e1" :name "event__finish" :input {}}
                                    {:type :tool_use :id "v1" :name "submit_verdict"
                                     :input {:done? true}}]
                      :usage       {:input-tokens 1 :output-tokens 1}
                      :model       "mock"}])
        seen-idle (atom nil)
        chart     (chart/statechart
                    {:initial :wrap}
                    (state {:id :wrap :initial :work}
                      (state {:id :work}
                        (h/llm-conversation
                          {:id             "glm"
                           :message        "go"
                           :allowed-events [{:event :finish :data-schema [:map]}]
                           :verdict-schema [:map [:done? :boolean]]})
                        (transition {:event :llm.idle :target :done}
                          (script {:expr (fn [_ d] (reset! seen-idle (:_event d)) nil)})))
                      (final {:id :done})))
        t         (new-llm-test-env {:statechart chart :backend backend})
        t         (await-config! t :done 3000)]
    (assertions
      "chart reached :done"
      (dct/in? t :done) => true
      "one request — the glm-class batched turn no longer pays for a wrap-up either"
      (count @(:call-log backend)) => 1
      "verdict attached to the idle event"
      (get-in @seen-idle [:data :verdict]) => verdict)))

(specification "node-tool-defs replays the verdict tool with the rest of the palette"
  (assertions
    "a node declaring :verdict-schema replays WITH submit_verdict — otherwise a refined
     replay would drive a turn whose tool palette differs from the one captured"
    (mapv :name (llmc/node-tool-defs nil {:allowed-events [{:event :finish :data-schema [:map]}]
                                          :verdict-schema [:map [:done? :boolean]]}))
    => ["event__finish" "submit_verdict"]
    "a node without one is unchanged"
    (mapv :name (llmc/node-tool-defs nil {:allowed-events [{:event :finish :data-schema [:map]}]}))
    => ["event__finish"]))

(specification "a verdict batched with unfinished real-tool work is DEFERRED, not accepted"
  ;; The verdict is the turn terminator. Accepting one that arrived alongside a
  ;; real tool call whose result the model has not seen would end the turn early
  ;; and silently truncate the model's own plan — an organ that emitted one
  ;; fs_write plus a verdict would stop after that single write. Hand it back
  ;; instead; the turn was continuing anyway to deliver the tool result.
  (let [good      {:status :ok :note "actually done"}
        backend   (mock-backend
                    [{:stop-reason :tool_use
                      :content     [{:type :tool_use :id "t1" :name "test_noop" :input {}}
                                    {:type :tool_use :id "v1" :name "submit_verdict"
                                     :input {:status :ok :note "premature"}}]
                      :usage       {:input-tokens 1 :output-tokens 1}
                      :model       "mock"}
                     (text+verdict-response "now I am done" good "v2")])
        registry  (tp/new-registry [(->AlwaysOkTool)])
        seen-idle (atom nil)
        chart     (chart/statechart
                    {:initial :wrap}
                    (state {:id :wrap :initial :work}
                      (state {:id :work}
                        (h/llm-conversation
                          {:id             "worker"
                           :message        "go"
                           :real-tools     [:test/noop]
                           :verdict-schema [:map [:status :keyword] [:note :string]]})
                        (transition {:event :llm.idle :target :done}
                          (script {:expr (fn [_ d] (reset! seen-idle (:_event d)) nil)})))
                      (final {:id :done})))
        t         (new-llm-test-env {:statechart    chart
                                     :backend       backend
                                     :tool-registry registry})
        t         (await-config! t :done 3000)
        log       @(:call-log backend)]
    (assertions
      "chart reached :done — the turn was NOT cut short by the premature verdict"
      (dct/in? t :done) => true
      "the tool result WAS delivered, so the model got its second turn"
      (count log) => 2
      "the premature verdict was refused, not silently recorded"
      (get-in @seen-idle [:data :verdict]) => good
      "the refusal explains itself in the tool_result the model receives"
      (let [msgs (:messages (second log))]
        (boolean
          (some (fn [m] (some #(= @#'llmc/verdict-deferred-text (:content %)) (:content m)))
            msgs)))
      => true)))