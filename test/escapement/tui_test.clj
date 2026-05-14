(ns escapement.tui-test
  (:require
   [escapement.tui :as tui]
   [fulcro-spec.core :refer [specification assertions =>]]))

(specification "format-event one-line summaries"
               (assertions
                "llm/response surfaces stop-reason + tokens"
                (tui/format-event {:event :llm/response
                                   :data  {:stop-reason :tool_use
                                           :usage       {:input-tokens 100 :output-tokens 17}}})
                => "[llm/resp] stop=:tool_use tokens=in:100/out:17"

                "runner/event-processed surfaces config-before → config-after"
                (tui/format-event {:event :runner/event-processed
                                   :data  {:event-name    :done
                                           :config-before [:run :working]
                                           :config-after  [:run :finished]}})
                => "[chart] :done  [:run :working] → [:run :finished]"

                "human-input/start shows kind"
                (tui/format-event {:event :human-input/start :data {:kind :text}})
                => "[human] prompt kind=:text"

                "checkpoint/written is suppressed (returns nil)"
                (tui/format-event {:event :checkpoint/written :data {:session-id "x"}})
                => nil

                "tick is suppressed"
                (tui/format-event {:event :runner/tick :data {:i 0}}) => nil

                "runner/quiescent heartbeat is suppressed"
                (tui/format-event {:event :runner/quiescent :data {:live-invocations 1}}) => nil

                "unknown events fall back to a generic format"
                (.startsWith ^String (tui/format-event {:event :mystery :data {:a 1}})
                             "[mystery]")
                => true))

(specification "interactive-terminal? returns boolean"
  ;; We can't force a TTY in tests; just confirm it's boolean and false here.
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
