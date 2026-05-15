(ns escapement.tui-test
  (:require
   [escapement.tui :as tui]
   [fulcro-spec.core :refer [specification assertions =>]]))

(specification "format-event one-line summaries"
               (assertions
                "runner/event-processed renders as [chart] line"
                (some? (re-find #"\[chart\].*:done.*\[:run :working\].*\[:run :finished\]"
                                (tui/format-event {:event :runner/event-processed
                                                   :data  {:event-name    :done
                                                           :config-before [:run :working]
                                                           :config-after  [:run :finished]}})))
                => true

                "human-input/start renders with [human] tag"
                (.startsWith ^String (tui/format-event {:event :human-input/start :data {:kind :text}})
                             "[human]")
                => true

                "llm/response renders with the invocation tag"
                (.startsWith ^String (tui/format-event {:event :llm/response
                                                        :data  {:invokeid "writer"
                                                                :stop-reason :end_turn
                                                                :usage {:input-tokens 1 :output-tokens 1}
                                                                :content [{:type :text :text "hello"}]}})
                             "[writer]")
                => true

                "checkpoint/written is suppressed (returns nil)"
                (tui/format-event {:event :checkpoint/written :data {:session-id "x"}})
                => nil

                "tick is suppressed"
                (tui/format-event {:event :runner/tick :data {:i 0}}) => nil

                "runner/quiescent heartbeat is suppressed"
                (tui/format-event {:event :runner/quiescent :data {:live-invocations 1}}) => nil))

(specification "interactive-terminal? returns boolean"
               (assertions
                "returns boolean (false in non-TTY test environment)"
                (boolean? (tui/interactive-terminal?)) => true))

(specification "start! returns a disabled handle when no TTY"
               (let [h (tui/start! {:chart-sym 'x/y :session-short "abcd1234"})]
                 (assertions
                  "non-TTY environment → handle is disabled"
                  (:enabled? h) => false
                  "event! is a no-op (does not throw)"
                  (do (tui/event! h {:event :runner/started :data {}}) :ok) => :ok
                  "stop! is a no-op (does not throw)"
                  (do (tui/stop! h) :ok) => :ok)))

;; ---------------------------------------------------------------------------
;; Synthetic enabled-handle tests — verify the new unified scrollback,
;; modal/inspector coexistence, and color allocation.
;; ---------------------------------------------------------------------------

(defn- mk-handle
  "Build a minimal enabled handle for testing without a real terminal."
  [{:keys [inspector? debug?]
    :or   {inspector? true debug? false}}]
  (let [state (atom {:config          []
                     :scrollback      []
                     :scroll-offset   0
                     :cursor-idx      nil
                     :invokeid-colors {}
                     :next-color-idx  0
                     :modal           nil
                     :term-h          24 :term-w 80
                     :debug-overlay   {:open?       false
                                       :view        :invocations
                                       :cursor      0
                                       :events      []
                                       :invocations []
                                       :focus       nil
                                       :pager       nil}})]
    {:enabled?         true
     :inspector?       inspector?
     :debug?           debug?
     :state            state
     :lock             (Object.)
     :terminal         nil
     :raw-mode?        (atom false)
     :cursor-shown?    (atom false)
     :sync-output?     (atom false)
     :debug-controller nil
     :debug-config     nil
     :env              (atom nil)
     :stopped?         (atom false)
     :chart-sym        "x/y"
     :session-short    "abcd"
     :session-id       (atom nil)
     :queue            (atom nil)}))

(specification "unified scrollback contains one entry per content block"
               (let [h (mk-handle {})]
                 (tui/event! h {:event :llm/start :ts 1
                                :data  {:invokeid "writer" :session-id "s1"}})
                 (tui/event! h {:event :llm/user-message :ts 2
                                :data  {:invokeid "writer" :text "hi"}})
                 (tui/event! h {:event :llm/response :ts 3
                                :data  {:invokeid "writer"
                                        :stop-reason :tool_use
                                        :usage {:input-tokens 1 :output-tokens 1}
                                        :content [{:type :text :text "thinking out loud"}
                                                  {:type :tool_use :id "u1" :name "event__ok"
                                                   :input {:msg "go"}}]}})
                 (tui/event! h {:event :runner/event-processed :ts 4
                                :data  {:event-name :step
                                        :config-before [:a] :config-after [:b]}})
                 (tui/event! h {:event :llm/tool-result :ts 5
                                :data  {:invokeid "writer"
                                        :tool :ok :is-error false :content-preview "ok"}})
                 (let [sb (:scrollback @(:state h))
                       sources (mapv :source sb)
                       glyphs  (mapv :glyph sb)]
                   (assertions
                    "scrollback has 7 entries (start, user, text, tool_use, resp-tail, chart, tool-result)"
                    (count sb) => 7
                    "sources interleave invokeid 'writer' with :chart"
                    sources => ["writer" "writer" "writer" "writer" "writer" :chart "writer"]
                    "tool_use glyph is the gear; assistant text is the left-triangle; tool-result the return-arrow"
                    (set glyphs) => #{\· \▸ \◂ \⚙ \↩}))))

(specification "color allocator gives distinct codes to distinct invokeids"
               (let [h (mk-handle {})]
                 (tui/event! h {:event :llm/start :ts 1
                                :data  {:invokeid "writer"}})
                 (tui/event! h {:event :llm/start :ts 2
                                :data  {:invokeid "critic"}})
                 (let [colors (:invokeid-colors @(:state h))]
                   (assertions
                    "both invokeids got colors"
                    (count colors) => 2
                    "colors are different"
                    (apply not= (vals colors)) => true))))

(specification "pressing ? while a modal is up opens the inspector without resolving the modal"
               (let [h (mk-handle {:inspector? true :debug? false})
                     ask! (resolve 'escapement.tui/ask!)]
                 ;; Raise a modal on a worker thread so we can observe it.
                 (let [worker (future (try (ask! h {:kind :text :prompt "y/n"})
                                           (catch Throwable t t)))]
                   (Thread/sleep 50)
                   (assertions
                    "modal is up"
                    (some? (:modal @(:state h))) => true
                    "inspector starts closed"
                    (get-in @(:state h) [:debug-overlay :open?]) => false)
                   ;; Simulate the `?` key path that input-loop! would take when
                   ;; modal is active: toggle the inspector open.
                   (swap! (:state h) update-in [:debug-overlay :open?] not)
                   (assertions
                    "inspector is now open"
                    (get-in @(:state h) [:debug-overlay :open?]) => true
                    "modal is STILL up — was not resolved"
                    (some? (:modal @(:state h))) => true
                    "worker has not returned yet"
                    (deref worker 50 :still-waiting) => :still-waiting)
                   ;; Deliver to let the worker finish.
                   (deliver (:promise (:modal @(:state h))) "ok")
                   (assertions
                    "worker returns once promise is delivered"
                    (deref worker 1000 :timeout) => "ok"))))
