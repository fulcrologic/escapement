(ns escapement.lib.event-sink-test
  "Unit tests for the pure normalization adapter `escapement.lib.event-sink`.

  Feeds representative raw transcript rows for every event family
  (run / chart / LLM / tool) through the adapter and asserts:
   * the exact normalized public event maps,
   * the correlation triple (:session-id / :run-id / :invokeid) on every
     emitted event, sourced from :runner/started,
   * the synthesized tool call/result/validation-failure split,
   * unmapped rows are dropped (no spurious events),
   * every emitted event conforms to the closed public schema."
  (:require
    [escapement.lib.event-sink :as es]
    [fulcro-spec.core :refer [=> assertions specification]]))

;; --- representative raw transcript fixtures (one per family) ---------------

(def started-row
  {:event :runner/started
   :data  {:session-id "sid-1" :chart-id "::chart" :resume? false
           :run-id     "11111111-2222-3333-4444-555555555555"}})

(defn all-conform? [events]
  (every? es/valid-event? events))

(specification "correlation triple captured from :runner/started"
  (let [a (es/make-adapter)
        [ev] ((:feed a) started-row)]
    (assertions
      ":runner/started normalizes to :run-started"
      (:type ev) => :run-started
      ":session-id captured (stringified)"
      (:session-id ev) => "sid-1"
      ":run-id captured (stringified)"
      (:run-id ev) => "11111111-2222-3333-4444-555555555555"
      "chart-id + resume? carried"
      (select-keys ev [:chart-id :resume?]) => {:chart-id "::chart" :resume? false}
      "conforms to public schema"
      (es/valid-event? ev) => true
      "context now holds the correlation pair"
      (select-keys ((:ctx a)) [:session-id :run-id])
      => {:session-id "sid-1"
          :run-id     "11111111-2222-3333-4444-555555555555"})))

(specification "run lifecycle family"
  (let [a    (es/make-adapter)
        _    ((:feed a) started-row)
        feed #((:feed a) %)]
    (assertions
      ":runner/resumed -> :run-resumed"
      (map :type (feed {:event :runner/resumed :data {:config [:a]}}))
      => [:run-resumed]
      ":runner/start-config -> :chart-config"
      (map :type (feed {:event :runner/start-config :data {:config [:a]}}))
      => [:chart-config]
      ":runner/done -> :run-done with final-config"
      (let [[e] (feed {:event :runner/done :data {:final-config [:done]}})]
        [(:type e) (:final-config e) (:run-id e)])
      => [:run-done [:done] "11111111-2222-3333-4444-555555555555"]
      ":runner/aborted -> :run-aborted"
      (map :type (feed {:event :runner/aborted :data {:reason :max-iterations}}))
      => [:run-aborted]
      ":runner/error -> :run-error"
      (let [[e] (feed {:event :runner/error :data {:message "boom"}})]
        [(:type e) (:message e)])
      => [:run-error "boom"]
      "all emitted run events conform"
      (all-conform? (concat
                      (feed {:event :runner/resumed :data {:config [:a]}})
                      (feed {:event :runner/done :data {:final-config []}})))
      => true)))

(specification "chart lifecycle family"
  (let [a    (es/make-adapter)
        _    ((:feed a) started-row)
        feed #((:feed a) %)
        [ce] (feed {:event :runner/event-processed
                    :ts    1
                    :data  {:event-name    :go
                            :config-before [:idle]
                            :config-after  [:done]
                            :event-data    {:x 1}}})
        [cp] (feed {:event :checkpoint/written
                    :data  {:session-id "sid-1"}})]
    (assertions
      ":runner/event-processed -> :chart-event with normalized config keys"
      (dissoc ce :session-id :run-id)
      => {:type          :chart-event :event-name :go
          :config-before [:idle] :config-after [:done] :event-data {:x 1}}
      ":checkpoint/written -> :chart-checkpoint"
      (select-keys cp [:type :checkpoint-session-id])
      => {:type :chart-checkpoint :checkpoint-session-id "sid-1"}
      "both conform + carry run-id"
      [(es/valid-event? ce) (es/valid-event? cp) (:run-id ce)]
      => [true true "11111111-2222-3333-4444-555555555555"])))

(specification "LLM lifecycle family"
  (let [a    (es/make-adapter)
        _    ((:feed a) started-row)
        feed #((:feed a) %)
        [req] (feed {:event :llm/request :ts 1
                     :data  {:n-messages 3 :model "gpt-x" :invokeid :inv1}})
        [del] (feed {:event :llm/delta :ts 2
                     :data  {:text "hi" :model "gpt-x" :invokeid :inv1}})
        [res] (feed {:event :llm/response :ts 3
                     :data  {:stop-reason :end_turn :n-blocks 1
                             :usage       {:in 10} :model "gpt-x" :invokeid :inv1}})
        [rty] (feed {:event :llm/retry :ts 4
                     :data  {:model   "gpt-x" :category :rate-limited
                             :attempt 1 :invokeid :inv1}})
        [cont] (feed {:event :llm/continuation :ts 5
                      :data  {:segment 2 :usage {:in 20} :invokeid :inv1}})
        [err] (feed {:event :llm/error :ts 6
                     :data  {:reason :unexpected-stop :invokeid :inv1}})
        fb   (feed {:event :llm/error :ts 7
                    :data  {:reason   :exhausted :category :overloaded
                            :attempts 3 :model "gpt-x" :invokeid :inv1}})]
    (assertions
      ":llm/request -> :llm-request, invokeid threaded"
      (select-keys req [:type :model :n-messages :invokeid])
      => {:type :llm-request :model "gpt-x" :n-messages 3 :invokeid :inv1}
      ":llm/delta -> :text-delta, raw delta keeps payload sans corr keys"
      [(:type del) (:model del) (:invokeid del) (:delta del)]
      => [:text-delta "gpt-x" :inv1 {:text "hi"}]
      ":llm/response -> :llm-response"
      (select-keys res [:type :stop-reason :n-blocks :usage])
      => {:type :llm-response :stop-reason :end_turn :n-blocks 1 :usage {:in 10}}
      ":llm/retry (non-tool) -> :llm-retry"
      (select-keys rty [:type :category :attempt])
      => {:type :llm-retry :category :rate-limited :attempt 1}
      ":llm/continuation -> :llm-continuation"
      (select-keys cont [:type :segment :usage])
      => {:type :llm-continuation :segment 2 :usage {:in 20}}
      ":llm/error (plain) -> :llm-error"
      (select-keys err [:type :reason]) => {:type :llm-error :reason :unexpected-stop}
      ":llm/error (categorized + attempts) -> :llm-fallback then :llm-error"
      (map :type fb) => [:llm-fallback :llm-error]
      "fallback carries from-model + category"
      (select-keys (first fb) [:type :from-model :category])
      => {:type :llm-fallback :from-model "gpt-x" :category :overloaded}
      "every LLM event conforms"
      (all-conform? [req del res rty cont err (first fb) (second fb)]) => true
      "run-id threaded onto LLM events"
      (every? #(= "11111111-2222-3333-4444-555555555555" (:run-id %))
        [req del res rty cont err])
      => true)))

(specification "tool lifecycle synthesized from :llm/tool-result + errors"
  (let [a         (es/make-adapter)
        _         ((:feed a) started-row)
        feed      #((:feed a) %)
        tr-events (feed {:event :llm/tool-result :ts 10
                         :data  {:tool_use_id     "tu-1"
                                 :tool            :search
                                 :input           {:q "x"}
                                 :is-error        false
                                 :content-preview "..."
                                 :invokeid        :inv9}})
        [call result] tr-events
        ;; a tool-attributed error: same invokeid as a seen tool result
        vf        (feed {:event :llm/error :ts 11
                         :data  {:reason :bad-tool-use :invokeid :inv9}})
        ;; explicit bad-tool-use reason without a prior pending tool also splits
        b         (es/make-adapter)
        _         ((:feed b) started-row)
        vf2       (feed {:event :llm/retry :ts 12
                         :data  {:category :bad-tool-use :reason :bad-tool-use
                                 :invokeid :inv9}})]
    (assertions
      ":llm/tool-result yields a correlated call + result pair"
      (map :type tr-events) => [:tool-call :tool-result]
      "call carries tool-use-id / tool / input + correlation"
      (select-keys call [:type :tool-use-id :tool :input :invokeid])
      => {:type  :tool-call :tool-use-id "tu-1" :tool :search
          :input {:q "x"} :invokeid :inv9}
      "result carries is-error + content-preview, same correlation"
      (select-keys result [:type :tool-use-id :tool :is-error :invokeid])
      => {:type     :tool-result :tool-use-id "tu-1" :tool :search
          :is-error false :invokeid :inv9}
      "call + result share session-id/run-id"
      (= (select-keys call [:session-id :run-id])
        (select-keys result [:session-id :run-id])
        {:session-id "sid-1"
         :run-id     "11111111-2222-3333-4444-555555555555"})
      => true
      "tool-attributed :llm/error -> :tool-validation-failure linked by invokeid"
      (select-keys (first vf) [:type :tool :invokeid])
      => {:type :tool-validation-failure :tool :search :invokeid :inv9}
      ":llm/retry with bad-tool-use reason -> :tool-validation-failure"
      (:type (first vf2)) => :tool-validation-failure
      "all synthesized tool events conform to public schema"
      (all-conform? (concat tr-events vf vf2)) => true)))

(specification "unmapped/internal rows are dropped (no spurious events)"
  (let [a    (es/make-adapter)
        _    ((:feed a) started-row)
        feed #((:feed a) %)]
    (assertions
      ":runner/tick dropped"
      (feed {:event :runner/tick :data {:i 1}}) => []
      ":llm/start dropped"
      (feed {:event :llm/start :data {:invokeid :inv1 :session-id "sid-1"}}) => []
      ":llm/status dropped"
      (feed {:event :llm/status :data {}}) => []
      "unknown event dropped"
      (feed {:event :totally/unknown :data {}}) => [])))

(specification "run-id absent when :runner/started has none (CLI path)"
  (let [a (es/make-adapter)
        [ev] ((:feed a) {:event :runner/started
                         :data  {:session-id "sid-2" :chart-id "::c"
                                 :resume?    false}})]
    (assertions
      ":run-id is nil but event still conforms"
      (:run-id ev) => nil
      (:session-id ev) => "sid-2"
      (es/valid-event? ev) => true)))

(specification "ordered facade sequence (task-008 contract)"
  (let [a    (es/make-adapter)
        feed #((:feed a) %)
        seq* (concat
               (feed started-row)
               (feed {:event :llm/request :data {:n-messages 1 :invokeid :i}})
               (feed {:event :llm/delta :data {:text "a" :invokeid :i}})
               (feed {:event :llm/response :data {:stop-reason :end_turn
                                                  :n-blocks    1 :invokeid :i}})
               (feed {:event :runner/done :data {:final-config []}}))]
    (assertions
      "normalized stream is :run-started -> :llm-request -> :text-delta -> :llm-response -> :run-done"
      (map :type seq*)
      => [:run-started :llm-request :text-delta :llm-response :run-done]
      "every event in the stream carries the stable run-id"
      (every? #(= "11111111-2222-3333-4444-555555555555" (:run-id %)) seq*)
      => true
      "every event conforms to the public schema"
      (all-conform? seq*) => true)))
