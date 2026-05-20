(ns escapement.chart.helpers-test
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final state transition]]
    [escapement.chart.helpers :as h]
    [escapement.engine.testing :as dct]
    [escapement.invocation.human-input :as hi]
    [escapement.invocation.llm-conversation :as llmc]
    [escapement.llm.protocol :as llm]
    [escapement.tools.protocol :as tp]
    [fulcro-spec.core :refer [=> assertions specification]]))

(defrecord MockBackend [responses call-log]
  llm/LLMBackend
  (send-turn [_ request]
    (swap! call-log conj request)
    (let [r (ffirst (swap-vals! responses (fn [v] (if (seq v) (subvec v 1) v))))]
      (when (nil? r)
        (throw (ex-info "Mock backend out of canned responses" {})))
      r)))

(defn- mock-backend [responses]
  (->MockBackend (atom (vec responses)) (atom [])))

(defn- tool-use [id name input]
  {:stop-reason :tool_use
   :content     [{:type :tool_use :id id :name name :input input}]
   :usage       {:input-tokens 1 :output-tokens 1}
   :model       "mock"})

(defn- end-turn [text]
  {:stop-reason :end_turn
   :content     [{:type :text :text (or text "done")}]
   :usage       {:input-tokens 1 :output-tokens 1}
   :model       "mock"})

(defrecord StubRenderer [answers]
  hi/HumanRenderer
  (prompt-text [_ _] (:text @answers))
  (prompt-select [_ _] (:select @answers))
  (prompt-multi [_ _] (:multi @answers))
  (prompt-confirm [_ _] (:confirm @answers))
  (start-progress [_ _] (atom {}))
  (update-progress [_ _ _ _] nil)
  (end-progress [_ _] nil)
  (custom-render [_ f env data] (f env data)))

(defn- await-state!
  [t state-kw max-ms]
  (let [deadline (+ (System/currentTimeMillis) max-ms)]
    (loop []
      (dct/drain! t)
      (cond
        (dct/in? t state-kw) t
        (>= (System/currentTimeMillis) deadline) t
        :else (do (Thread/sleep 25) (recur))))))

(specification "with-llm-questions produces a runnable compound state"
  (let [s (h/with-llm-questions {:id :bubble :params-fn (fn [_ _] {:system "s"})})]
    (assertions
      "outer state has the requested id"
      (:id s) => :bubble
      "and the expected child ids: invoke + three states"
      (set (map :id (:children s)))
      => #{"bubble/conv" :bubble.converse :bubble.ask-choice :bubble.ask-text})))

(specification "LLM ask-choice round-trips through human-input back to the conversation"
  ;; Round-trip (R1 contract: an event-tool turn ENDS the turn — the worker
  ;; fires :llm.idle and parks in :awaiting-user; it does NOT continue to a
  ;; synthetic tool_result turn):
  ;;   turn 1: LLM calls event__question_ask_choice (tool_use, event-tool).
  ;;           The :question/ask-choice event is posted BEFORE :llm.idle, so
  ;;           the chart leaves :converse for :ask-choice before the idle
  ;;           exit-transition can fire. Worker parks in :awaiting-user.
  ;;   chart routes to :ask-choice; stub renderer returns :b; chart tell-llm's
  ;;           "User chose: :b" → :llm.user-message wakes the worker.
  ;;   turn 2: LLM returns :end_turn → :llm.idle (now in :converse) →
  ;;           :exit-transitions fires → :done.
  (let [backend  (mock-backend
                   [(tool-use "u1" "event__question_ask_choice"
                      {:question "pick"
                       :options  [{:label "A" :value :a}
                                  {:label "B" :value :b}]})
                    (end-turn "ack")])
        renderer (->StubRenderer (atom {:select :b}))
        chart    (chart/statechart
                   {:initial :run}
                   (state {:id :run :initial :work}
                     (h/with-llm-questions
                       {:id        :work
                        :params-fn (fn [_ _]
                                     {:system               "s"
                                      :real-tools           []
                                      :initial-user-message "go"})
                        :exit-transitions
                        [(transition {:event :llm.idle :target :done})]})
                     (final {:id :done})))
        llm-proc (llmc/new-processor {:backend       backend
                                      :tool-registry (tp/new-registry)})
        hi-proc  (hi/new-processor {:renderer renderer})
        t        (-> (dct/new-testing-env {:statechart chart} llm-proc hi-proc)
                   (dct/start!))
        t        (await-state! t :done 5000)]
    (assertions
      "chart reached :done"
      (dct/in? t :done) => true
      "LLM was called twice (ask event-tool turn + answer turn)"
      (count @(:call-log backend)) => 2
      "the second LLM turn carried 'User chose: :b' as a new user message"
      (let [third-req (nth @(:call-log backend) 1)
            msgs      (:messages third-req)
            last-user (last (filter #(= :user (:role %)) msgs))
            text      (->> (:content last-user)
                        (filter #(= :text (:type %)))
                        (map :text)
                        (clojure.string/join ""))]
        (.contains ^String text "User chose")) => true)))

;; ---------------------------------------------------------------------------
;; Artifact helpers — capture-llm-output, render-template, forward-llm-output
;; ---------------------------------------------------------------------------

(specification "render-template substitutes file-backed artifacts"
  (let [tmp (str (java.nio.file.Files/createTempDirectory
                   "art-test" (into-array java.nio.file.attribute.FileAttribute [])))
        env {:escapement/session-dir tmp}]
    ;; Seed two artifact files.
    (clojure.java.io/make-parents (str tmp "/artifacts/x"))
    (spit (str tmp "/artifacts/research") "research-text")
    (spit (str tmp "/artifacts/draft.md") "## the draft\n")
    (assertions
      "simple substitution"
      (h/render-template "Findings: {{research}}" env)
      => "Findings: research-text"
      "filenames with extensions"
      (h/render-template "Draft:\n{{draft.md}}\n--end" env)
      => "Draft:\n## the draft\n\n--end"
      "{{output}} resolves from extras only"
      (h/render-template "Said: {{output}}" env {:output "hello"})
      => "Said: hello")))

(specification "render-template throws on missing artifact"
  (let [tmp (str (java.nio.file.Files/createTempDirectory
                   "art-test" (into-array java.nio.file.attribute.FileAttribute [])))
        env {:escapement/session-dir tmp}]
    (assertions
      "missing artifact is a fail-fast ex-info"
      (try
        (h/render-template "{{nope}}" env)
        :did-not-throw
        (catch clojure.lang.ExceptionInfo e
          (:reason (ex-data e))))
      => :missing-artifact

      "{{output}} without :output in extras also fails"
      (try
        (h/render-template "{{output}}" env)
        :did-not-throw
        (catch clojure.lang.ExceptionInfo e
          (:reason (ex-data e))))
      => :missing-template-key)))

(specification "capture-llm-output writes the assistant text to a file"
  (let [end-text "the answer is 42"
        backend  (mock-backend [(end-turn end-text)])
        chart    (chart/statechart
                   {:initial :run}
                   (state {:id :run :initial :work}
                     (state {:id :work}
                       (h/llm-conversation
                         {:id        "researcher"
                          :params-fn (fn [_ _] {:initial-user-message "go"})})
                       (transition {:event :llm.idle :target :done}
                         (h/capture-llm-output)))
                     (final {:id :done})))
        llm-proc (escapement.invocation.llm-conversation/new-processor
                   {:backend backend :tool-registry (escapement.tools.protocol/new-registry)})
        t        (-> (escapement.engine.testing/new-testing-env {:statechart chart} llm-proc)
                   (escapement.engine.testing/start!))
        t        (await-state! t :done 3000)
        sess-dir (:escapement/session-dir (:env t))
        path     (str sess-dir "/artifacts/researcher")]
    (assertions
      "chart reached :done"
      (escapement.engine.testing/in? t :done) => true
      "artifact file exists at <session-dir>/artifacts/<from>"
      (.exists (clojure.java.io/file path)) => true
      "contents are the assistant's final text"
      (slurp path) => end-text)))

(specification "capture-llm-output with explicit :as filename"
  (let [backend  (mock-backend [(end-turn "draft v1")])
        chart    (chart/statechart
                   {:initial :run}
                   (state {:id :run :initial :work}
                     (state {:id :work}
                       (h/llm-conversation
                         {:id        "writer"
                          :params-fn (fn [_ _] {:initial-user-message "go"})})
                       (transition {:event :llm.idle :target :done}
                         (h/capture-llm-output {:as "draft.md"})))
                     (final {:id :done})))
        llm-proc (escapement.invocation.llm-conversation/new-processor
                   {:backend backend :tool-registry (escapement.tools.protocol/new-registry)})
        t        (-> (escapement.engine.testing/new-testing-env {:statechart chart} llm-proc)
                   (escapement.engine.testing/start!))
        t        (await-state! t :done 3000)
        sess-dir (:escapement/session-dir (:env t))]
    (assertions
      "artifact lands under the requested name (with extension)"
      (slurp (str sess-dir "/artifacts/draft.md")) => "draft v1")))
