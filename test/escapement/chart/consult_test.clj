(ns escapement.chart.consult-test
  "Integration coverage for [[escapement.chart.consult/declare-consultation]].

  The shape under test:
   - An asker `:llm-conversation` calls a region-tool whose owner is the
     consult state.
   - The consult state's handler routes the asker's input to a nested
     specialist `:llm-conversation` as a targeted user-message.
   - The specialist runs to idle; its `:verdict-schema` wrap-up inference
     fires; the `:llm.idle` event carries `:verdict`.
   - The consult state's `:llm.idle` transition matches on `:from`,
     JSON-encodes the verdict, and calls `service/post-reply` so the
     asker's tool_use receives the structured answer as `tool_result`.
   - The asker's next turn sees the tool_result and ends."
  (:require
   [cheshire.core :as json]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :refer [final on-entry parallel state transition]]
   [escapement.chart.consult :as consult]
   [escapement.chart.helpers :as h]
   [escapement.engine.testing :as dct]
   [escapement.invocation.llm-conversation :as llmc]
   [escapement.llm.protocol :as llm]
   [escapement.test-support :as ts]
   [escapement.tools.protocol :as tp]
   [fulcro-spec.core :refer [specification assertions =>]]))

;; ---------------------------------------------------------------------------
;; Mock backend (same shape as service_test).
;; ---------------------------------------------------------------------------

(defrecord MockBackend [responses call-log]
  llm/LLMBackend
  (send-turn [_ request]
    (swap! call-log conj request)
    (let [r (ts/pop-first! responses)]
      (when (nil? r)
        (throw (ex-info "Mock backend out of canned responses"
                        {:n-calls (count @call-log)})))
      r)))

(defn- mock-backend [responses]
  (->MockBackend (ts/queue responses) (atom [])))

(defn- end-turn-response [text]
  {:stop-reason :end_turn
   :content     [{:type :text :text (or text "done")}]
   :usage       {:input-tokens 1 :output-tokens 1}
   :model       "mock"})

(defn- tool-use-response [tool-uses]
  {:stop-reason :tool_use
   :content     (mapv (fn [{:keys [id name input]}]
                        {:type :tool_use :id id :name name :input input})
                      tool-uses)
   :usage       {:input-tokens 1 :output-tokens 1}
   :model       "mock"})

(defn- new-llm-test-env [{:keys [statechart backend]}]
  (let [processor (llmc/new-processor {:backend       backend
                                       :tool-registry (tp/new-registry)
                                       :transcript-fn (fn [_] nil)})]
    (-> (dct/new-testing-env {:statechart statechart} processor)
        (dct/start!))))

(defn- await-config!
  [t state-kw max-ms]
  (let [deadline (+ (System/currentTimeMillis) max-ms)]
    (loop []
      (dct/drain! t)
      (cond
        (dct/in? t state-kw) t
        (>= (System/currentTimeMillis) deadline) t
        :else (do (Thread/sleep 25) (recur))))))

;; ---------------------------------------------------------------------------
;; Happy path: asker → consult → specialist → verdict → asker
;; ---------------------------------------------------------------------------

(specification "consultation happy path: asker LLM calls consult tool, specialist's verdict comes back as tool_result"
               (let [;; Response queue, in FIFO order across BOTH askers and specialist:
        ;; 1. asker call 1: tool_use region__placement_help
        ;; 2. specialist call 1: end_turn (free-text final)
        ;; 3. specialist call 2 (forced wrap-up): submit_verdict tool_use
        ;; 4. asker call 2: end_turn
                     backend (mock-backend
                              [(tool-use-response
                                [{:id    "u1"
                                  :name  "region__placement_help"
                                  :input {:fn-name "wibble" :body "(defn wibble [] :ok)"}}])
                               (end-turn-response "I will recommend core.clj")
                               (tool-use-response
                                [{:id    "v1"
                                  :name  "submit_verdict"
                                  :input {:file "src/foo/core.clj" :rationale "fits with existing helpers"}}])
                               (end-turn-response "got the placement, all set")])
                     chart   (chart/statechart
                              {:initial :run}
                              (state {:id :run :initial :work}
                                     (parallel {:id :work}
                          ;; Asker region.
                                               (state {:id :asker :initial :running}
                                                      (state {:id :running}
                                                             (h/llm-conversation
                                                              {:id        "asker"
                                                               :params-fn (fn [_ _]
                                                                            {:chart-tools          [{:owner :consult-placer}]
                                                                             :initial-user-message "place this function"})})
                                                             (transition {:event :llm.idle :cond (fn [_env data]
                                                                                                   (= "asker" (get-in data [:_event :data :from])))
                                                                          :target :asker-done}))
                                                      (final {:id :asker-done}))
                          ;; Consult region — declared via consult.clj.
                                               (consult/declare-consultation
                                                {:state-id            :consult-placer
                                                 :tool-name           :placement-help
                                                 :description         "Ask the placement specialist where to put a function."
                                                 :specialist-invokeid :placer
                                                 :input-schema        [:map [:fn-name :string] [:body :string]]
                                                 :verdict-schema      [:map [:file :string] [:rationale :string]]
                                                 :system              "You decide where Clojure functions go."}))
                                     (transition {:event :done.state.work :target :finished})
                                     (final {:id :finished})))
                     t       (new-llm-test-env {:statechart chart :backend backend})
                     t       (await-config! t :asker-done 8000)
                     calls   @(:call-log backend)
                     asker-followup (nth calls 3 nil)]
                 (assertions
                  "asker reached :asker-done"
                  (dct/in? t :asker-done) => true

                  "backend was called exactly 4 times (asker, specialist, specialist-wrap-up, asker-followup)"
                  (count calls) => 4

                  "specialist's wrap-up inference forced submit_verdict tool-choice"
                  (-> calls (nth 2) :tool-choice) => {:type :tool :name "submit_verdict"}

                  "asker's follow-up turn carries a tool_result whose content is the JSON-encoded verdict"
                  (let [tr (->> asker-followup
                                :messages
                                (mapcat :content)
                                (filter #(= :tool_result (:type %)))
                                first)]
                    (and (some? tr)
                         (let [parsed (json/parse-string (str (:content tr)) true)]
                           (and (= "src/foo/core.clj" (:file parsed))
                                (= "fits with existing helpers" (:rationale parsed))))))
                  => true)))
