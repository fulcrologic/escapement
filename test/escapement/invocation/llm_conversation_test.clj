(ns escapement.invocation.llm-conversation-test
  (:require
    [clojure.string :as str]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final on-entry script state transition]]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.chart.helpers :as h]
    [escapement.engine.testing :as dct]
    [escapement.invocation.llm-conversation :as llmc]
    [escapement.llm.protocol :as llm]
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

(defn- new-llm-test-env
  [{:keys [statechart backend tool-registry transcript-fn]}]
  (let [processor (llmc/new-processor {:backend       backend
                                       :tool-registry (or tool-registry (tp/new-registry))
                                       :transcript-fn (or transcript-fn (fn [_] nil))})]
    (-> (dct/new-testing-env {:statechart statechart} processor)
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

;; ---------------------------------------------------------------------------
;; #1: Happy path, one event-tool fired
;; ---------------------------------------------------------------------------

(specification "effective-max-tokens is purely catalog-driven (models-api.json limit.output)"
  (assertions
    "resolves the model's catalog output cap"
    (llmc/effective-max-tokens "claude-sonnet-4-7") => 64000
    (llmc/effective-max-tokens "claude-3-sonnet-20240229") => 4096
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

(defrecord AlwaysOkTool []
  tp/Tool
  (tool-name [_] :test/noop)
  (description [_] "always succeeds, posts no chart event")
  (input-schema [_] [:map])
  (invoke [_ _] {:result "ok" :is-error false}))

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

(specification ":on-end-turn-event data now carries :text and :from"
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
      ":on-end-turn-event data has the assistant's final text"
      (get-in @seen [:data :text]) => "the answer is 42"
      "and the speaker's invokeid"
      (get-in @seen [:data :from]) => "advisor")))

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
                               :model   "main"
                               :message "hello-main"})
                            (h/llm-conversation
                              {:id      "advisor"
                               :model   "advisor"
                               :message "hello-advisor"})))
        proc            (llmc/new-processor {:backend       selector
                                             :tool-registry (tp/new-registry)})
        t               (-> (dct/new-testing-env {:statechart chart} proc)
                          (dct/start!))]
    ;; Wait for both initial turns and the workers to reach :awaiting-user.
    (Thread/sleep 250)
    (dct/drain! t)
    (let [queue (::sc/event-queue (:env t))
          sid   (:session-id t)]
      (sp/send! queue (:env t)
        {:target sid :source-session-id sid
         :event  :llm.user-message
         :data   {:text "for advisor only" :target "advisor"}}))
    (Thread/sleep 250)
    (dct/drain! t)
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

(specification "transcript truncation caps oversized text/thinking blocks"
  (let [big      (apply str (repeat (+ 100 escapement.invocation.llm-conversation/transcript-block-cap)
                              "x"))
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
      "transcript text was truncated to the cap + marker"
      (clojure.string/ends-with? text
        escapement.invocation.llm-conversation/transcript-truncate-marker)
      => true
      (count text) => (+ escapement.invocation.llm-conversation/transcript-block-cap
                        (count escapement.invocation.llm-conversation/transcript-truncate-marker)))))

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

(specification "candidate-models applies the eligibility gate to the fallback list"
  (let [defaults ["gpt-4o-mini" "claude-sonnet-4-5" "claude-opus-4-1"]]
    (component "auto-fallback list is filtered by :needs (objective fact)"
      (assertions
        ":context-tokens [:>= 200000] drops gpt-4o-mini (128k window)"
        (#'llmc/candidate-models {:needs {:context-tokens [:>= 200000]}}
          defaults (atom {}) {})
        => ["claude-sonnet-4-5" "claude-opus-4-1"]))
    (component "an explicit :model pick is never silently switched"
      (assertions
        "policy is ignored when the user names a model"
        (#'llmc/candidate-models {:model "gpt-4o-mini"
                                  :needs {:context-tokens [:>= 200000]}}
          defaults (atom {}) {})
        => ["gpt-4o-mini"]))
    (component "an unsatisfiable gate falls back to the unfiltered list"
      (assertions
        "so the conversation still runs (fail-open)"
        (#'llmc/candidate-models {:needs {:context-tokens [:>= 999999999]}}
          defaults (atom {}) {})
        => defaults))
    (component ":down models are removed after gate filtering"
      (assertions
        "claude-sonnet-4-5 marked :down → only claude-opus-4-1 survives"
        (#'llmc/candidate-models {:needs {:context-tokens [:>= 200000]}}
          defaults
          (atom {"claude-sonnet-4-5" :down})
          {})
        => ["claude-opus-4-1"]))
    (component "subjective ratings come from the injected catalog-ratings arg"
      (assertions
        "with ratings keeping only claude-sonnet-4-5, gpt-4o-mini/opus drop"
        (#'llmc/candidate-models {:needs {:clojure [:>= 8]}}
          defaults (atom {})
          {"claude-sonnet-4-5" {:clojure 9}})
        => ["claude-sonnet-4-5"]
        "a DIFFERENT injected ratings table → different survivors (same process)"
        (#'llmc/candidate-models {:needs {:clojure [:>= 8]}}
          defaults (atom {})
          {"claude-opus-4-1" {:clojure 10}})
        => ["claude-opus-4-1"]
        "empty injected ratings → subjective clause matches nothing → unfiltered (fail-open)"
        (#'llmc/candidate-models {:needs {:clojure [:>= 8]}}
          defaults (atom {}) {})
        => defaults))
    (component "no gate → default-models verbatim"
      (assertions
        (#'llmc/candidate-models {} defaults (atom {}) {}) => defaults))))

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

(specification "try-models! surfaces :llm/model-policy-empty when the gate excludes every fallback model (fail-open)"
  (let [captured (atom [])
        backend  (mock-backend [(end-turn-response "ok")])
        result   (#'llmc/try-models!
                   {:backend         backend
                    :transcript-fn   (fn [ev] (swap! captured conj ev))
                    :worker-state    (atom :running)
                    :model-status    (atom {})
                    :default-models  ["gpt-4o-mini"]
                    :catalog-ratings {}
                    :parent-ctx      {:invokeid "iv"}}
                   {:needs {:context-tokens [:>= 999999999]}}
                   [{:role :user :content [{:type :text :text "hi"}]}]
                   [])
        ev       (first (filter #(= :llm/model-policy-empty (:event %)) @captured))]
    (assertions
      "the event was emitted"
      (some? ev) => true
      "it carries the resolved (canonical) policy"
      (get-in ev [:data :policy]) => {:require {} :min {:context-tokens 999999999} :max {}}
      "it carries the default-models that all failed the gate"
      (get-in ev [:data :default-models]) => ["gpt-4o-mini"]
      "strict? is false by default"
      (get-in ev [:data :strict?]) => false
      "the turn still completes via the unfiltered fallback model (fail-open)"
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
                    :default-models      ["gpt-4o-mini"]
                    :catalog-ratings     {}
                    :eligibility-strict? true
                    :parent-ctx          {:invokeid "iv"}}
                   {:needs {:context-tokens [:>= 999999999]}}
                   [{:role :user :content [{:type :text :text "hi"}]}]
                   [])
        ev       (first (filter #(= :llm/model-policy-empty (:event %)) @captured))]
    (assertions
      "the gap event still records strict?=true"
      (get-in ev [:data :strict?]) => true
      "no turn was issued (fail-closed)"
      (:ok result) => nil
      "the :eligibility-empty shape is returned for the caller to fail the node"
      (some? (:eligibility-empty result)) => true
      (get-in result [:eligibility-empty :default-models]) => ["gpt-4o-mini"])))

(specification "try-models! ratings come from the injected context value (two tables, one process)"
  (let [run (fn [ratings]
              (let [backend (mock-backend [(end-turn-response "ok")])]
                (#'llmc/try-models!
                  {:backend         backend
                   :transcript-fn   (fn [_] nil)
                   :worker-state    (atom :running)
                   :model-status    (atom {})
                   :default-models  ["gpt-4o-mini" "claude-opus-4-1"]
                   :catalog-ratings ratings
                   :parent-ctx      {:invokeid "iv"}}
                  {:needs {:clojure [:>= 8]}}
                  [{:role :user :content [{:type :text :text "hi"}]}]
                  [])))]
    (assertions
      "ratings favoring gpt-4o-mini → it is the model used"
      (:model-used (run {"gpt-4o-mini" {:clojure 9}})) => "gpt-4o-mini"
      "a DIFFERENT injected ratings table in the same process → different pick"
      (:model-used (run {"claude-opus-4-1" {:clojure 9}})) => "claude-opus-4-1")))

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
    (#'llmc/params->resilience nil) => {:max-retries 3 :backoff-ms 500}
    (#'llmc/params->resilience {:resilience {:max-retries 0}})
    => {:max-retries 0 :backoff-ms 500}
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
      (string? (get-in @seen-err [:data :errors])) => true)))

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

