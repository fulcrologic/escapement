(ns escapement.invocation.llm-conversation-test
  (:require
   [clojure.string :as str]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :refer [state transition final on-entry script]]
   [com.fulcrologic.statecharts.protocols :as sp]
   [escapement.chart.helpers :as h]
   [escapement.engine.testing :as dct]
   [escapement.invocation.llm-conversation :as llmc]
   [escapement.llm.protocol :as llm]
   [escapement.tools.builtin :as builtin]
   [escapement.tools.protocol :as tp]
   [fulcro-spec.core :refer [specification behavior component assertions =>]])
  (:import
   (java.util.concurrent LinkedBlockingDeque TimeUnit)))

;; ---------------------------------------------------------------------------
;; Mock LLMBackend
;; ---------------------------------------------------------------------------

(defrecord MockBackend [responses call-log]
  llm/LLMBackend
  (send-turn [_ request]
    (swap! call-log conj request)
    (let [r (.pollFirst ^LinkedBlockingDeque responses)]
      (when (nil? r)
        (throw (ex-info "Mock backend out of canned responses" {:n-calls (count @call-log)})))
      r)))

(defn mock-backend
  "Build a mock backend whose `send-turn` will return canned responses in order."
  [responses]
  (let [q (LinkedBlockingDeque.)]
    (doseq [r responses] (.add q r))
    (->MockBackend q (atom []))))

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
                                              {:id        "main"
                                               :params-fn (fn [_ _]
                                                            {:system               "do it"
                                                             :real-tools           []
                                                             :allowed-events       [{:event       :ok
                                                                                     :data-schema [:map [:msg :string]]}]
                                                             :initial-user-message "go"})})
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

;; ---------------------------------------------------------------------------
;; #2: Fan-out (3 tool_use in one assistant turn)
;; ---------------------------------------------------------------------------

(specification "fan-out: multiple event-tool calls in one assistant message"
               (let [backend (mock-backend
                              [(tool-use-response
                                [{:id "u1" :name "event__found_bug" :input {:n 1}}
                                 {:id "u2" :name "event__found_bug" :input {:n 2}}
                                 {:id "u3" :name "event__found_bug" :input {:n 3}}])
                               (end-turn-response "done")])
                     chart   (chart/statechart
                              {:initial :wrap}
                              (state {:id :wrap :initial :scanning}
                                     (state {:id :scanning}
                                            (h/llm-conversation
                                             {:id        "scan"
                                              :params-fn (fn [_ _]
                                                           {:system               "scan"
                                                            :allowed-events       [{:event       :found-bug
                                                                                    :data-schema [:map [:n :int]]}]
                                                            :initial-user-message "scan"})})
                                            (transition {:event :llm.idle :target :done}))
                                     (final {:id :done})))
        ;; We need to capture the findings during traversal; track via an atom and a script.
                     captured (atom [])
                     chart'   (chart/statechart
                               {:initial :wrap}
                               (state {:id :wrap :initial :scanning}
                                      (state {:id :scanning}
                                             (h/llm-conversation
                                              {:id        "scan"
                                               :params-fn (fn [_ _]
                                                            {:system               "scan"
                                                             :allowed-events       [{:event       :found-bug
                                                                                     :data-schema [:map [:n :int]]}]
                                                             :initial-user-message "scan"})})
                                             (transition {:event :found-bug :target :scanning :type :internal}
                                                         (script {:expr (fn [env data]
                                                                          (swap! captured conj (:_event data))
                                                                          nil)}))
                                             (transition {:event :llm.idle :target :done}))
                                      (final {:id :done})))
                     t       (new-llm-test-env {:statechart chart' :backend backend})
                     t       (await-config! t :done 3000)]
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
                                              {:id        "rw"
                                               :params-fn (fn [_ _]
                                                            {:real-tools           [:fs/read]
                                                             :allowed-events       [{:event :done :data-schema [:map]}]
                                                             :initial-user-message "go"})})
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

(specification "real-tools selector"
               (behavior "absent (nil) exposes EVERY tool registered in the registry"
                         (let [backend  (mock-backend [(tool-use-response [{:id "e" :name "event__done" :input {}}])
                                                       (end-turn-response "ok")])
                               registry (builtin/new-builtin-registry)
                               chart    (chart/statechart
                                         {:initial :wrap}
                                         (state {:id :wrap :initial :work}
                                                (state {:id :work}
                                                       (h/llm-conversation
                                                        {:id        "all"
                                                         :params-fn (fn [_ _]
                                                                      {:system               "go"
                                                    ;; :real-tools intentionally omitted
                                                                       :allowed-events       [{:event :done :data-schema [:map]}]
                                                                       :initial-user-message "go"})})
                                                       (transition {:event :done :target :finished}))
                                                (final {:id :finished})))
                               t        (new-llm-test-env {:statechart chart :backend backend :tool-registry registry})
                               _        (await-config! t :finished 3000)]
                           (assertions
                            "every builtin tool name made it into the request alongside the event tool"
                            (last-request-tool-names backend)
                            => #{"fs_read" "fs_write" "fs_edit" "fs_multi-edit" "fs_glob" "fs_grep"
                                 "shell_run" "repl_eval" "event__done"})))

               (behavior "an explicit selector vector is a whitelist"
                         (let [backend  (mock-backend [(tool-use-response [{:id "e" :name "event__done" :input {}}])
                                                       (end-turn-response "ok")])
                               registry (builtin/new-builtin-registry)
                               chart    (chart/statechart
                                         {:initial :wrap}
                                         (state {:id :wrap :initial :work}
                                                (state {:id :work}
                                                       (h/llm-conversation
                                                        {:id        "subset"
                                                         :params-fn (fn [_ _]
                                                                      {:system               "go"
                                                                       :real-tools           [:fs/read :fs/grep]
                                                                       :allowed-events       [{:event :done :data-schema [:map]}]
                                                                       :initial-user-message "go"})})
                                                       (transition {:event :done :target :finished}))
                                                (final {:id :finished})))
                               t        (new-llm-test-env {:statechart chart :backend backend :tool-registry registry})
                               _        (await-config! t :finished 3000)]
                           (assertions
                            "only the whitelisted real tools + the event tool"
                            (last-request-tool-names backend)
                            => #{"fs_read" "fs_grep" "event__done"}))))

;; ---------------------------------------------------------------------------
;; #3c: prompt caching flows from params-fn through to the Request
;; ---------------------------------------------------------------------------

(specification "params-fn cache-control flags reach the Request"
               (let [backend  (mock-backend [(tool-use-response [{:id "e" :name "event__done" :input {}}])
                                             (end-turn-response "ok")])
                     registry (builtin/new-builtin-registry)
                     chart    (chart/statechart
                               {:initial :wrap}
                               (state {:id :wrap :initial :work}
                                      (state {:id :work}
                                             (h/llm-conversation
                                              {:id        "cached"
                                               :params-fn (fn [_ _]
                                                            {:system               "stable system prompt"
                                                             :real-tools           [:fs/read :fs/grep]
                                                             :system-cache-control {:type :ephemeral}
                                                             :tools-cache-control  {:type :ephemeral}
                                                             :allowed-events       [{:event :done :data-schema [:map]}]
                                                             :initial-user-message "go"})})
                                             (transition {:event :done :target :finished}))
                                      (final {:id :finished})))
                     t        (new-llm-test-env {:statechart chart :backend backend :tool-registry registry})
                     _        (await-config! t :finished 3000)
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
  "Spin up a tiny one-turn chart whose params-fn returns `params-extra` merged
   over a stable base, and return the first Request that landed on the mock
   backend."
  [params-extra]
  (let [backend  (mock-backend [(tool-use-response [{:id "e" :name "event__done" :input {}}])
                                (end-turn-response "ok")])
        registry (builtin/new-builtin-registry)
        base     {:system               "stable system prompt"
                  :real-tools           [:fs/read :fs/grep]
                  :allowed-events       [{:event :done :data-schema [:map]}]
                  :initial-user-message "go"}
        chart    (chart/statechart
                  {:initial :wrap}
                  (state {:id :wrap :initial :work}
                         (state {:id :work}
                                (h/llm-conversation
                                 {:id        "auto"
                                  :params-fn (fn [_ _] (merge base params-extra))})
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

(specification "bad input twice on same tool_use_id triggers :llm.error"
               (let [;; The LLM keeps producing bad input (different ids each time mimicking re-tries).
                     backend (mock-backend
                              [(tool-use-response [{:id "x1" :name "event__pick" :input {}}])
                               (tool-use-response [{:id "x1" :name "event__pick" :input {}}])])
                     err-seen (atom nil)
                     chart    (chart/statechart
                               {:initial :wrap}
                               (state {:id :wrap :initial :work}
                                      (state {:id :work}
                                             (h/llm-conversation
                                              {:id        "p"
                                               :params-fn (fn [_ _]
                                                            {:allowed-events       [{:event       :pick
                                                                                     :data-schema [:map [:choice :string]]}]
                                                             :initial-user-message "pick one"})})
                                             (transition {:event :llm.error :target :failed}
                                                         (script {:expr (fn [_ d]
                                                                          (reset! err-seen (:_event d))
                                                                          nil)})))
                                      (final {:id :failed})))
                     t        (new-llm-test-env {:statechart chart :backend backend})
                     t        (await-config! t :failed 3000)]
                 (assertions
                  "chart reaches :failed"
                  (dct/in? t :failed) => true
                  "error event carries :reason :tool-validation-failed"
                  (get-in @err-seen [:data :reason]) => :tool-validation-failed)))

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
                                             {:id        "p"
                                              :params-fn (fn [_ _]
                                                           {:allowed-events       [{:event       :pick
                                                                                    :data-schema [:map [:choice :string]]}]
                                                            :initial-user-message "pick one"})})
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
                                             {:id        "main"
                                              :params-fn (fn [_ _]
                                                           {:initial-user-message "hi"})})
                                            (transition {:event :leave :target :done}))
                                     (final {:id :done})))
                     t       (-> (dct/new-testing-env {:statechart chart} proc)
                                 (dct/start!))]
                 (await-config! t :bound 2000)
    ;; Worker should be live now
                 (let [sid (:session-id t)
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
                     env-map {:env        {::sc/event-queue {} ;; placeholder, we'll use direct call via real env below
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
                                             {:id        "main"
                                              :params-fn (fn [_ _]
                                                           {:initial-user-message "hi"})})
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
