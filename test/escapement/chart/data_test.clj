(ns escapement.chart.data-test
  "Charts built from data (`escapement.chart.data`): building from a shape plus step
   fns, the structural checks, guard-decision rows, retries, wait timeouts and the
   diagram."
  (:require
    [babashka.fs]
    [clojure.string :as str]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.chart.data :as data]
    [escapement.lib :as lib]
    [escapement.llm.scripted :as scripted]
    [escapement.testing.tools :as pt]
    [fulcro-spec.core :refer [=> assertions behavior component specification]]))

;; ---------------------------------------------------------------------------
;; A small triage workflow
;; ---------------------------------------------------------------------------

(def steps
  {:store-email   (fn [_ data] {:email (data/event-data data)})
   :prompt        (fn [_ data] (str "Classify: " (get-in data [:email :subject])))
   :confident?    (fn [_ data] (>= (get-in data [:_event :data :verdict :confidence] 0) 0.9))
   :save-verdict  (fn [_ data] {:category (get-in data [:_event :data :verdict :category])})
   :send-reply    (fn [env data] {:sent (:result (data/tool! env :email/send {:to "c@x" :category (:category data)}))})
   :approved?     (fn [_ data] (true? (get-in data [:_event :data :approved?])))
   :mark-failed   (fn [_ data] {:failed (str (get-in data [:_event :name]))})})

(def triage
  {:id      :triage
   :initial :inbox
   :data    {:category nil}
   :states
   [{:id :inbox :label "Wait for an email"
     :on [{:event :email/received :target :classify :label "email arrives" :actions [:store-email]}]}
    {:id :classify :label "Classify the email"
     :ai {:id "classify" :system "You triage." :message {:step :prompt}
          :verdict-schema [:map [:category :keyword] [:confidence :double]]}
     :on [{:event :llm.idle :guard :confident? :condition "confidence ≥ 0.9"
           :target :approve :actions [:save-verdict] :label "sure"}
          {:event :llm.idle :target :review :label "not sure"}
          {:event :error.llm :target :failed :actions [:mark-failed] :label "AI failed"}]}
    ;; No :timeout-ms here: until the run has a test clock, a pending timer keeps
    ;; the run going on the wall clock (timeouts are covered below with short ones).
    {:id :approve :label "Wait for your OK"
     :wait {}
     :on [{:event :approval/answered :guard :approved? :target :reply :label "approved"}
          {:event :approval/answered :target :skipped :label "skipped"}]}
    {:id :reply :label "Send the reply" :entry [:send-reply] :on [{:target :done}]}
    {:id :review :type :final :label "Needs a person"}
    {:id :skipped :type :final}
    {:id :failed :type :final}
    {:id :done :type :final :label "Done"}]})

(defn- wm-data [r]
  (let [env (:env r)
        wm  (sp/get-working-memory (::sc/working-memory-store env) env (:session-id r))]
    {:config (::sc/configuration wm)
     :data   (sp/current-data (::sc/data-model env)
               (assoc env ::sc/vwmem (volatile! wm) ::sc/context-element-id nil))}))

(defn- run! [chart backend reg & {:as more}]
  (lib/run (merge {:chart         chart
                   :session-id    (keyword (str "data-" (rand-int 1000000000)))
                   :credentials   []
                   :backend       backend
                   :tool-registry reg}
             more)))

(defn- codes [problems] (set (map :code problems)))

(specification "chart-from-data: building"
  (let [{:keys [ok? chart problems chart-id]} (data/compile triage {:steps steps :chart-id :wf.t/v1})]
    (assertions
      "a good shape builds a chart with no errors"
      ok? => true
      (filter #(= :error (:level %)) problems) => []
      chart-id => :wf.t/v1
      "states carry the shape's labels"
      (:diagram/label (chart/element chart :classify)) => "Classify the email"
      "an unlabelled state gets a readable label"
      (:diagram/label (chart/element chart :skipped)) => "Skipped"
      "transitions carry labels and conditions"
      (let [tids (chart/transitions chart :classify)
            ts   (map #(chart/element chart %) tids)]
        (set (keep :diagram/condition ts))) => #{"confidence ≥ 0.9"}))
  (behavior "references resolve against a loaded step namespace symbol too"
    (create-ns 'escapement.chart.data-test.steps-ns)
    (intern 'escapement.chart.data-test.steps-ns 'go? (fn [_ _] true))
    (assertions
      (:ok? (data/compile {:initial :a :states [{:id :a :on [{:event :x :guard :go? :target :b}]}
                                                {:id :b :type :final}]}
              {:steps 'escapement.chart.data-test.steps-ns})) => true)))

(specification "chart-from-data: running"
  (component "the sure path, with an approval"
    (let [{:keys [chart]} (data/compile triage {:steps steps})
          ckpt (str (babashka.fs/create-temp-dir))
          sid  (keyword (str "data-" (rand-int 1000000000)))
          b    (scripted/backend {:script {"classify" [{:verdict {:category :billing :confidence 0.95}}]}})
          reg  (pt/pretend-registry {:email/send {:reply "sent"}})
          rows (atom [])
          base {:chart chart :session-id sid :credentials [] :backend b :tool-registry reg
                :checkpoint-dir ckpt :transcript-path (str ckpt "/t.jsonl")
                :transcript-tap #(swap! rows conj %)}
          r1   (lib/run (assoc base :initial-data (data/initial-data triage {})))
          r2   (lib/run (assoc base :resume? true
                          :resume-events [{:event :email/received :data {:subject "Refund"}}]))
          r3   (lib/run (assoc base :resume? true
                          :resume-events [{:event :approval/answered :data {:approved? true}}]))
          guards (filter #(= :chart/guard (:event %)) @rows)]
      (assertions
        "the run waits for the email, then for the approval"
        (:final-config r1) => [:inbox]
        (:final-config r2) => [:approve]
        "the approval sends the reply and finishes"
        (pt/call-counts reg) => {:email/send 1}
        (:final-config r3) => []
        "guard decisions are logged with their condition and result"
        (first (filter #(= :confident? (get-in % [:data :guard])) guards))
        =fn=> #(and (= "confidence ≥ 0.9" (get-in % [:data :condition]))
                 (true? (get-in % [:data :result]))
                 (= :classify (get-in % [:data :state])))))))

(def retrying
  {:initial :ask
   :states
   [{:id :ask
     :ai {:id "ask" :message "go" :verdict-schema [:map [:ok :boolean]]}
     :retry {:max 2 :backoff-ms 5 :on-exhausted :gave-up}
     :on [{:event :llm.idle :target :ok}]}
    {:id :ok :on [{:event :never :target :ask}]}
    {:id :gave-up :on [{:event :never :target :ask}]}]})

(specification "chart-from-data: retry with backoff"
  (let [{:keys [chart ok? problems]} (data/compile retrying {:steps {}})]
    (assertions "the retry shape builds" ok? => true problems => [])
    (component "two failures then success"
      (let [b (scripted/backend {:script {"ask" [{:fail :auth} {:fail :auth} {:verdict {:ok true}}]}})
            r (run! chart b (pt/pretend-registry {}))]
        (assertions
          "retries until the step succeeds"
          (:final-config r) => [:ok]
          (count (remove :retry? (scripted/calls b))) => 3)))
    (component "failures past :max"
      (let [b (scripted/backend {:script {"ask" [{:fail :auth} {:fail :auth} {:fail :auth}]}})
            r (run! chart b (pt/pretend-registry {}))]
        (assertions
          "gives up after the bounded retries"
          (:final-config r) => [:gave-up]
          (count (remove :retry? (scripted/calls b))) => 3)))))

(specification "chart-from-data: wait timeout and timers"
  (let [shape {:initial :wait
               :states [{:id :wait :wait {:timeout-ms 20 :on-timeout :late}
                         :on [{:event :go :target :done}]}
                        {:id :late :on [{:after 20 :target :later :label "a moment later"}]}
                        {:id :later :on [{:event :never :target :wait}]}
                        {:id :done :type :final}]}
        {:keys [chart ok?]} (data/compile shape {:steps {}})
        r (run! chart (scripted/backend {}) (pt/pretend-registry {}))]
    (assertions
      ok? => true
      "the wait times out, then the timer fires"
      (:final-config r) => [:later])))

(specification "chart-from-data: structural checks"
  (let [problems (fn [shape] (data/validate shape {:steps steps}))]
    (assertions
      "unknown targets and initial"
      (codes (problems {:initial :nope :states [{:id :a :on [{:event :x :target :b}]}]}))
      =fn=> #(every? % [:unknown-initial :unknown-target])
      "unreachable states"
      (codes (problems {:initial :a :states [{:id :a :type :final} {:id :island :type :final}]}))
      => #{:unreachable}
      "stuck states"
      (codes (problems {:initial :a :states [{:id :a :on [{:event :x :target :b}]} {:id :b}]}))
      => #{:stuck}
      "an AI step without an error exit"
      (codes (problems {:initial :a :states [{:id :a :ai {:message "x"} :on [{:event :llm.idle :target :b}]}
                                             {:id :b :type :final}]}))
      => #{:no-error-exit}
      "an outside call without an error exit"
      (codes (problems {:initial :a :states [{:id :a :call {:tool :x/y} :on [{:event :call.done :target :b}]}
                                             {:id :b :type :final}]}))
      => #{:no-error-exit}
      "an ancestor's error exit counts"
      (codes (problems {:initial :p
                        :states [{:id :p :on [{:event :error.llm :target :f}]
                                  :states [{:id :a :ai {:message "x"} :on [{:event :llm.idle :target :f}]}]}
                                 {:id :f :type :final}]}))
      => #{}
      "an unguarded error loop is an unbounded retry"
      (codes (problems {:initial :a :states [{:id :a :ai {:message "x"}
                                              :on [{:event :error.llm :target :a}
                                                   {:event :llm.idle :target :b}]}
                                             {:id :b :type :final}]}))
      => #{:unbounded-retry}
      "an error loop through a timer is unbounded too"
      (codes (problems {:initial :a :states [{:id :a :ai {:message "x"}
                                              :on [{:event :error.llm :target :w}
                                                   {:event :llm.idle :target :b}]}
                                             {:id :w :on [{:after 10 :target :a}]}
                                             {:id :b :type :final}]}))
      =fn=> #(contains? % :unbounded-retry)
      "a guarded loop is fine"
      (codes (problems {:initial :a :states [{:id :a :ai {:message "x"}
                                              :on [{:event :error.llm :guard :approved? :target :a}
                                                   {:event :error.llm :target :b}
                                                   {:event :llm.idle :target :b}]}
                                             {:id :b :type :final}]}))
      => #{}
      "human-input invokes are refused"
      (codes (problems {:initial :a :states [{:id :a :invoke {:type :human-input}
                                              :on [{:event :error :target :b}]}
                                             {:id :b :type :final}]}))
      => #{:human-input}
      "duplicate ids"
      (codes (problems {:initial :a :states [{:id :a :type :final} {:id :a :type :final}]}))
      =fn=> #(contains? % :duplicate-id)
      "a delay that is neither milliseconds nor a step"
      (codes (problems {:initial :a :states [{:id :a :on [{:after "soon" :target :b}]}
                                             {:id :b :type :final}]}))
      => #{:bad-delay}
      "a computed delay is fine"
      (codes (problems {:initial :a :states [{:id :a :on [{:after {:step :approved?} :target :b}]}
                                             {:id :b :type :final}]}))
      => #{}
      "an AI step with unknown keys and no :message (it would never start)"
      (codes (problems {:initial :a :states [{:id :a :ai {:max-turns 2 :prompt "x" :verdict {:c "string"}}
                                              :on [{:event :llm.idle :target :b} {:event :error.llm :target :b}]}
                                             {:id :b :type :final}]}))
      => #{:unknown-key :no-message}
      "an outside call without a tool, or with a misspelt key"
      (codes (problems {:initial :a :states [{:id :a :call {:toll :x/y} :on [{:event :call.done :target :b}
                                                                            {:event :call.error :target :b}]}
                                             {:id :b :type :final}]}))
      => #{:unknown-key :no-tool}
      "a bad retry"
      (codes (problems {:initial :a :states [{:id :a :ai {:message "x"} :retry {:max 0}
                                              :on [{:event :error.llm :target :b} {:event :llm.idle :target :b}]}
                                             {:id :b :type :final}]}))
      => #{:bad-retry}))
  (component "unknown step references"
    (let [ps (data/validate {:initial :a :states [{:id :a :on [{:event :x :guard :confidnet? :target :b}]}
                                                  {:id :b :type :final}]}
               {:steps steps})
          p  (first ps)]
      (assertions
        "are reported, naming the state, with a suggestion"
        (:code p) => :unknown-ref
        (:state p) => :a
        (str/includes? (:message p) "Did you mean :confident?") => true
        "and the shape builds no chart"
        (:chart (data/compile {:initial :a :states [{:id :a :on [{:event :x :guard :confidnet? :target :b}]}
                                                    {:id :b :type :final}]}
                  {:steps steps})) => nil)))
  (component "problems name what they concern and read as sentences"
    (let [ps (data/validate {:initial :a :states [{:id :a :on [{:event :x :target :zz}]}]} {:steps {}})]
      (assertions
        (every? #(and (string? (:message %)) (vector? (:at %))) ps) => true))))

(specification "chart-from-data: diagram"
  (let [d (data/diagram retrying)
        full (data/diagram retrying {:detail :full})]
    (assertions
      "the business view hides the generated retry wait"
      (set (map :id (:nodes d))) => #{:ask :ok :gave-up}
      "the full view shows it"
      (contains? (set (map :id (:nodes full))) :ask.retry-wait) => true
      "nodes carry their kind"
      (:kind (first (filter #(= :ask (:id %)) (:nodes d)))) => :ai
      "error edges are marked"
      (some :error? (:edges d)) => true)))
