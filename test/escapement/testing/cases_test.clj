(ns escapement.testing.cases-test
  "Test cases as data: scripted AI, pretend tools, outside events at fake times on
   a test clock (a two-day wait takes no real time), and readable failures."
  (:require
    [escapement.chart.data :as data]
    [escapement.testing.cases :as cases]
    [escapement.testing.tools]
    [escapement.tools.protocol]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(def steps
  {:store-email  (fn [_ d] {:email (data/event-data d)})
   :prompt       (fn [_ d] (str "Classify this email: " (get-in d [:email :subject])))
   :confident?   (fn [_ d] (>= (get-in d [:_event :data :verdict :confidence] 0) 0.9))
   :save-verdict (fn [_ d] {:category (get-in d [:_event :data :verdict :category])})
   :approved?    (fn [_ d] (true? (get-in d [:_event :data :approved?])))
   :send-reply   (fn [env d] {:sent (:result (data/tool! env :email/send {:category (:category d)}))})})

(def shape
  {:initial :inbox
   :data    {:category :unknown}
   :states
   [{:id :inbox :on [{:event :email/received :target :classify :actions [:store-email]}]}
    {:id :classify
     :ai {:id "classify" :message {:step :prompt}
          :verdict-schema [:map [:category :keyword] [:confidence number?]]}
     :retry {:max 2 :backoff-ms 3600000 :on-exhausted :failed}
     :on [{:event :llm.idle :guard :confident? :target :approve :actions [:save-verdict]}
          {:event :llm.idle :target :review}]}
    {:id :approve
     :wait {:timeout-ms (* 2 24 3600000) :on-timeout :expired}
     :on [{:event :approval/answered :guard :approved? :target :reply}
          {:event :approval/answered :target :skipped}]}
    {:id :reply :entry [:send-reply] :on [{:target :done}]}
    {:id :review :type :final}
    {:id :skipped :type :final}
    {:id :expired :type :final}
    {:id :failed :type :final}
    {:id :done :type :final}]})

(def compiled (data/compile shape {:steps steps :chart-id :wf.t/v1}))

(def happy
  {:name "a sure billing email is answered after approval"
   :start "2026-11-23T09:00:00Z"
   :ai {"classify" [{:verdict {:category :billing :confidence 0.95}}]}
   :tools {:email/send {:reply "sent"}}
   :events [{:event :email/received :data {:subject "Refund please"}}
            {:event :approval/answered :data {:approved? true} :at-ms 3600000}]
   :expect {:status :done :final #{:done} :path [:inbox :classify :approve :reply :done]
            :never #{:failed :review} :data {[:category] :billing} :tool-calls {:email/send 1}
            :max-ai-calls 1 :ai-request-contains {"classify" "Refund please"}
            :min-elapsed-fake-ms 3600000}})

(specification "test cases: running a case"
  (assertions "the shape compiles" (:ok? compiled) => true)
  (let [r (cases/run-case compiled happy)]
    (assertions
      "the happy path passes"
      (:failures r) => []
      (:pass? r) => true
      "it reports the path, the data and the tool calls"
      (:path r) => [:inbox :classify :approve :reply :done]
      (get-in r [:data :category]) => :billing
      (mapv :tool (:tool-calls r)) => [:email/send]
      "fake time passed, real time didn't"
      (:elapsed-fake-ms r) => 3600000
      (< (:elapsed-ms r) 20000) => true
      "metrics come along"
      (get-in r [:metrics :ai-calls]) => 1))
  (component "a two-day wait with no answer times out instantly"
    (let [r (cases/run-case compiled
              {:ai {"classify" [{:verdict {:category :bug :confidence 0.99}}]}
               :events [{:event :email/received :data {:subject "Crash"}}]
               :expect {:final #{:expired} :min-elapsed-fake-ms (* 2 24 3600000)}})]
      (assertions
        (:failures r) => []
        (:elapsed-fake-ms r) => (* 2 24 3600000)
        (< (:elapsed-ms r) 20000) => true)))
  (component "retry backoff runs on the test clock"
    (let [r (cases/run-case compiled
              {:ai {"classify" [{:fail :auth} {:verdict {:category :bug :confidence 0.5}}]}
               :events [{:event :email/received :data {:subject "?"}}]
               :expect {:final #{:review} :min-elapsed-fake-ms 3600000}})]
      (assertions
        (:failures r) => []
        (some #{:classify.retry-wait} (:path r)) => :classify.retry-wait)))
  (component "a run parked on an outside event reports :waiting"
    (let [r (cases/run-case compiled {:expect {:status :waiting :final #{:inbox}}})]
      (assertions (:failures r) => [] (:status r) => :waiting)))
  (component "failed expectations are listed, readably"
    (let [r (cases/run-case compiled
              (assoc happy :expect {:status :waiting :final #{:skipped} :path [:review]
                                    :never #{:reply} :data {:category :bug} :tool-calls {:email/send 0}
                                    :ai-request-contains {"classify" "nope"}}))]
      (assertions
        (:pass? r) => false
        (set (map :expect (:failures r)))
        => #{:status :final :path :never :data :tool-calls :ai-request-contains}
        (every? (comp string? :message) (:failures r)) => true)))
  (component "a computed delay ({:step ...}) runs on the test clock"
    (let [c (data/compile {:initial :cool
                           :states [{:id :cool :on [{:after {:step :delay} :target :done}]}
                                    {:id :done :type :final}]}
              {:steps {:delay (fn [_ d] (:hours d))}})
          r (cases/run-case c {:initial-data {:hours 18000000}
                               :expect {:final #{:done} :min-elapsed-fake-ms 18000000}})]
      (assertions
        (:ok? c) => true
        (:failures r) => []
        (:elapsed-fake-ms r) => 18000000)))
  (component "run-cases sums a suite"
    (assertions
      (select-keys (cases/run-cases compiled [happy (assoc happy :expect {:final #{:review}})])
        [:passed :failed]) => {:passed 1 :failed 1})))

(def calling
  {:initial :check
   :states
   [{:id :check
     :call {:tool :li/status :input {:who "p-52"} :timeout-ms 10000}
     :retry {:max 2 :backoff-ms 60000 :on-exhausted :failed}
     :on [{:event :call.done :target :ok}
          {:event :call.error :guard :gone? :target :next}]}
    {:id :ok :type :final}
    {:id :next :type :final}
    {:id :failed :type :final}]})

(def calling-steps {:gone? (fn [_ d] (= :not-found (get-in d [:_event :data :error :code])))})

(specification "test cases: retries only retry retryable errors"
  (let [c (data/compile calling {:steps calling-steps})
        run (fn [replies & [retry]]
              (cases/run-case (if retry
                                (data/compile (assoc-in calling [:states 0 :retry] retry) {:steps calling-steps})
                                c)
                {:tools {:li/status {:replies replies}}}))]
    (assertions
      "the shape builds" (:problems c) => []
      "a non-retryable error goes straight to the state's own error arrow"
      (:final (run [{:is-error true :result "404" :error {:code :not-found :retryable? false}}])) => [:next]
      (:tool-counts (run [{:is-error true :result "404" :error {:code :not-found :retryable? false}}])) => {:li/status 1}
      "a retryable error is retried, then succeeds"
      (:final (run [{:is-error true :result "429" :error {:code :rate-limited :retryable? true}} {:result "ok"}])) => [:ok]
      "a retryable error out of attempts goes to :on-exhausted"
      (:final (run [{:is-error true :result "503" :error {:code :unavailable}}])) => [:failed]
      (:tool-counts (run [{:is-error true :result "503" :error {:code :unavailable}}])) => {:li/status 3}
      "an error without retry information is retried as before"
      (:tool-counts (run [{:is-error true :result "boom"}])) => {:li/status 3}
      "an outcome-unknown call is never retried"
      (:tool-counts (run [{:is-error true :result "?" :outcome :unknown}])) => {:li/status 1}
      (:final (run [{:is-error true :result "?" :outcome :unknown}])) => [:failed]
      ":retry :on limits retries to those codes"
      (:tool-counts (run [{:is-error true :result "x" :error {:code :boom}}]
                      {:max 2 :backoff-ms 10 :on #{:rate-limited} :on-exhausted :failed})) => {:li/status 1}
      (:tool-counts (run [{:is-error true :result "x" :error {:code :rate-limited}}]
                      {:max 2 :backoff-ms 10 :on #{:rate-limited} :on-exhausted :failed})) => {:li/status 3}
      "a bad :on is a problem"
      (set (map :code (:problems (data/compile (assoc-in calling [:states 0 :retry :on] "x") {:steps calling-steps}))))
      => #{:bad-retry})))

(specification "pretend tools keep structured reply keys"
  (let [reg (escapement.testing.tools/pretend-registry
              {:x/y {:reply {:is-error true :result {:n 1} :error {:code :limit} :value 3 :outcome :unknown}}})]
    (assertions
      (escapement.tools.protocol/invoke (escapement.tools.protocol/lookup reg :x/y) {})
      => {:is-error true :result "{:n 1}" :error {:code :limit} :value 3 :outcome :unknown})))
