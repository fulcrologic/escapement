(ns escapement.chart.helpers-test
  (:require
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :refer [state transition final]]
   [escapement.chart.helpers :as h]
   [escapement.engine.testing :as dct]
   [escapement.invocation.human-input :as hi]
   [escapement.invocation.llm-conversation :as llmc]
   [escapement.llm.protocol :as llm]
   [escapement.tools.protocol :as tp]
   [fulcro-spec.core :refer [specification assertions =>]])
  (:import
   (java.util.concurrent LinkedBlockingDeque)))

(defrecord MockBackend [responses call-log]
  llm/LLMBackend
  (send-turn [_ request]
    (swap! call-log conj request)
    (let [r (.pollFirst ^LinkedBlockingDeque responses)]
      (when (nil? r)
        (throw (ex-info "Mock backend out of canned responses" {})))
      r)))

(defn- mock-backend [responses]
  (let [q (LinkedBlockingDeque.)]
    (doseq [r responses] (.add q r))
    (->MockBackend q (atom []))))

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
  (prompt-text     [_ _] (:text @answers))
  (prompt-select   [_ _] (:select @answers))
  (prompt-multi    [_ _] (:multi @answers))
  (prompt-confirm  [_ _] (:confirm @answers))
  (start-progress  [_ _] (atom {}))
  (update-progress [_ _ _ _] nil)
  (end-progress    [_ _] nil)
  (custom-render   [_ f env data] (f env data)))

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
  ;; Round-trip:
  ;;   turn 1: LLM calls event__question_ask_choice  (tool_use)
  ;;   chart routes to :ask-choice; stub renderer returns :b
  ;;   turn 2: synthetic tool_result "ok" → LLM returns :end_turn (worker
  ;;           parks in :awaiting-user)
  ;;   chart tell-llm's "User chose: :b" → :llm.user-message wakes worker
  ;;   turn 3: LLM returns :end_turn → :llm.idle → :exit-transitions fires
               (let [backend  (mock-backend
                               [(tool-use "u1" "event__question_ask_choice"
                                          {:question "pick"
                                           :options  [{:label "A" :value :a}
                                                      {:label "B" :value :b}]})
                                (end-turn "ok")
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
                     llm-proc  (llmc/new-processor {:backend       backend
                                                    :tool-registry (tp/new-registry)})
                     hi-proc   (hi/new-processor {:renderer renderer})
                     t         (-> (dct/new-testing-env {:statechart chart} llm-proc hi-proc)
                                   (dct/start!))
                     t         (await-state! t :done 5000)]
                 (assertions
                  "chart reached :done"
                  (dct/in? t :done) => true
                  "LLM was called three times (ask + tool-result + answer turn)"
                  (count @(:call-log backend)) => 3
                  "the third LLM turn carried 'User chose: :b' as a new user message"
                  (let [third-req (nth @(:call-log backend) 2)
                        msgs      (:messages third-req)
                        last-user (last (filter #(= :user (:role %)) msgs))
                        text      (->> (:content last-user)
                                       (filter #(= :text (:type %)))
                                       (map :text)
                                       (clojure.string/join ""))]
                    (.contains ^String text "User chose")) => true)))
