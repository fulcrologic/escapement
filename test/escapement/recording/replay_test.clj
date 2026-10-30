(ns escapement.recording.replay-test
  "Recording a run's inputs and replaying them against the same or another version
   of its chart: recorded AI answers, pretend tools, reported divergences, and the
   recorded run's files left untouched."
  (:require
    [babashka.fs :as fs]
    [escapement.chart.data :as data]
    [escapement.recording :as rec]
    [escapement.recording.replay :as replay]
    [escapement.testing.cases :as cases]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(def steps
  {:store-email  (fn [_ d] {:email (data/event-data d)})
   :prompt       (fn [_ d] (str "Classify: " (get-in d [:email :subject])))
   :sure?        (fn [_ d] (>= (get-in d [:_event :data :verdict :confidence] 0) 0.9))
   :very-sure?   (fn [_ d] (>= (get-in d [:_event :data :verdict :confidence] 0) 0.99))
   :approved?    (fn [_ d] (true? (get-in d [:_event :data :approved?])))
   :send         (fn [env d] {:sent (:result (data/tool! env :email/send {:to (get-in d [:email :from])}))})})

(defn- shape [guard]
  {:initial :inbox
   :states
   [{:id :inbox :on [{:event :email/received :target :classify :actions [:store-email]}]}
    {:id :classify
     :ai {:id "classify" :message {:step :prompt}
          :verdict-schema [:map [:confidence number?]]}
     :on [{:event :llm.idle :guard guard :target :approve}
          {:event :llm.idle :target :review}
          {:event :error.llm :target :review}]}
    {:id :approve :on [{:event :approval/answered :guard :approved? :target :reply}
                       {:event :approval/answered :target :review}]}
    {:id :reply :entry [:send] :on [{:target :done}]}
    {:id :review :type :final}
    {:id :done :type :final}]})

(def v1 (data/compile (shape :sure?) {:steps steps :chart-id :wf.t/v1}))
(def v2 (data/compile (shape :very-sure?) {:steps steps :chart-id :wf.t/v2}))

(def original-case
  {:initial-data {:mailbox "support"}
   :ai {"classify" [{:verdict {:confidence 0.95}}]}
   :tools {:email/send {:reply "sent"}}
   :events [{:event :email/received :data {:subject "Refund" :from "a@b.c"}}
            {:event :approval/answered :data {:approved? true} :at-ms 60000}]})

(specification "recording: a run records its inputs"
  (let [rows (atom [])
        dir  (str (fs/create-temp-dir))
        r    (cases/run-case v1 original-case {:transcript-tap #(swap! rows conj %) :work-dir dir})
        rec  (rec/from-rows @rows {:session-dir dir})]
    (assertions
      "the original run finished"
      (:final r) => [:done]
      "the start row carries the initial data"
      (:initial-data rec) => {:mailbox "support"}
      "outside events are recorded, in order, with their data"
      (mapv :event (:events rec)) => [:email/received :approval/answered]
      (get-in rec [:events 0 :data :subject]) => "Refund"
      "events the chart raised itself are not"
      (some #{:llm.idle} (map :event (:events rec))) => nil
      "AI answers are recorded per step"
      (count (get-in rec [:ai "classify"])) => 1
      "the path and the end are recorded"
      (:final rec) => [:done]
      (:path rec) => [:inbox :classify :approve :reply :done])
    (component "replaying against the same version"
      (let [before (set (map str (fs/list-dir dir)))
            again  (replay/replay! rec {:chart (:chart v1) :chart-id :wf.t/v1
                                        :tools {:email/send {:reply "pretend"}}})]
        (assertions
          "follows the same path with no divergence"
          (:divergences again) => []
          (:diverged? again) => false
          (:path again) => [:inbox :classify :approve :reply :done]
          "uses the pretend tools it was given"
          (mapv :tool (:tool-calls again)) => [:email/send]
          "and never touches the recorded run's files"
          (set (map str (fs/list-dir dir))) => before
          (= (:transcript-path again) (str (fs/path dir "transcript.jsonl"))) => false)))
    (component "replaying against another version"
      (let [other (replay/replay! rec {:chart (:chart v2) :chart-id :wf.t/v2
                                       :tools {:email/send {:reply "pretend"}}})]
        (assertions
          "reports where it ended differently"
          (:diverged? other) => true
          (first (filter #(= :ended-elsewhere (:kind %)) (:divergences other)))
          => {:kind :ended-elsewhere :recorded [:done] :replayed [:review]})))
    (component "a version that asks an AI step the recording never answered"
      (let [v3 (data/compile (update (shape :sure?) :states
                               (fn [ss] (mapv #(if (= :reply (:id %))
                                                 {:id :reply :ai {:id "polish" :message "polish"}
                                                  :on [{:event :llm.idle :target :done}
                                                       {:event :error.llm :target :review}]}
                                                 %) ss)))
                 {:steps steps})
            r3 (replay/replay! rec {:chart (:chart v3)})]
        (assertions
          "reports the unanswered step"
          (some #(and (= :ai-unanswered (:kind %)) (= "polish" (:step %)) %) (:divergences r3))
          =fn=> some?)))
    (component "replaying with a script instead of the recorded answers"
      (let [s (replay/replay! rec {:chart (:chart v1) :ai :scripted
                                   :script {"classify" [{:verdict {:confidence 0.1}}]}})]
        (assertions
          (:final s) => [:review]
          (:diverged? s) => true)))
    (component "a recording round-trips through EDN"
      (let [f (str (fs/path (fs/create-temp-dir) "rec.edn"))]
        (rec/save! rec f)
        (assertions (rec/load-recording f) => rec)))))
