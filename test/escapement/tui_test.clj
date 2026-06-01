(ns escapement.tui-test
  (:require
    [escapement.tui :as tui]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(def ^:private key-from-bytes #'escapement.tui/key-from-bytes)

(defn- reader-of
  "Build a `read!` fn (matching `key-from-bytes`'s contract) that yields `ints`
   in order, then -2 (READ_EXPIRED timeout) forever — modeling a terminal that
   delivered exactly these bytes and nothing more. The timeout arg is ignored:
   exhaustion always reads as a timeout, which is precisely the
   `nothing-followed-the-ESC` condition the parser must distinguish."
  [ints]
  (let [remaining (atom (seq ints))]
    (fn [_timeout-ms]
      (if-let [b (first @remaining)]
        (do (swap! remaining rest) b)
        -2))))

(defn- decode [ints] (key-from-bytes (reader-of ints)))

;; Byte landmarks used by the decoder specs.
(def ^:private ESC 27)
(def ^:private LB 91)                                       ;; [
(def ^:private SS3 79)                                      ;; O

(specification "key-from-bytes — single-byte keys"
  (assertions
    "reports EOF when the source is at end-of-stream"
    (decode [-1]) => :eof
    "decodes Ctrl-C"
    (decode [3]) => :ctrl-c
    "decodes Tab"
    (decode [9]) => :tab
    "decodes Backspace from DEL (127)"
    (decode [127]) => :backspace
    "decodes Backspace from BS (8)"
    (decode [8]) => :backspace
    "decodes Enter from CR (13)"
    (decode [13]) => :enter
    "decodes Enter from LF (10)"
    (decode [10]) => :enter
    "decodes Space"
    (decode [32]) => :space
    "decodes a printable character as [:char ch]"
    (decode [(int \a)]) => [:char \a]
    (decode [(int \Z)]) => [:char \Z]))

(specification "key-from-bytes — bare Escape"
  (assertions
    "a lone ESC with nothing following is the Escape key"
    (decode [ESC]) => :esc
    "ESC followed (after the inter-byte wait) by a non-CSI/SS3 byte is still Escape"
    (decode [ESC (int \a)]) => :esc))

(specification "key-from-bytes — CSI arrow / nav sequences"
  (assertions
    "ESC [ A is Up"
    (decode [ESC LB 65]) => :up
    "ESC [ B is Down"
    (decode [ESC LB 66]) => :down
    "ESC [ C is Right"
    (decode [ESC LB 67]) => :right
    "ESC [ D is Left"
    (decode [ESC LB 68]) => :left
    "ESC [ H is Home"
    (decode [ESC LB 72]) => :home
    "ESC [ F is End"
    (decode [ESC LB 70]) => :end
    "ESC [ 5 ~ is PgUp (trailing ~ consumed)"
    (decode [ESC LB 53 126]) => :pgup
    "ESC [ 6 ~ is PgDn (trailing ~ consumed)"
    (decode [ESC LB 54 126]) => :pgdn))

(specification "key-from-bytes — SS3 (application-mode) cursor keys"
  (assertions
    "ESC O A is Up"
    (decode [ESC SS3 65]) => :up
    "ESC O D is Left"
    (decode [ESC SS3 68]) => :left
    "ESC O P (F1) is an unrecognized SS3 → :other"
    (decode [ESC SS3 80]) => :other))

(specification "key-from-bytes — terminal device-report replies are not Escape"
  ;; Regression guard: a Mode-2026 DECRPM reply (`ESC [ ? 2 0 2 6 ; 2 $ y`)
  ;; leaking into the input loop used to be misread as the user pressing
  ;; Escape, which cancelled an open human-input modal and tore the TUI down
  ;; the instant it opened.
  (let [decrpm [ESC LB (int \?) (int \2) (int \0) (int \2) (int \6)
                (int \;) (int \2) (int \$) (int \y)]]
    (assertions
      "the DECRPM reply decodes to :other, never :esc"
      (decode decrpm) => :other)
    (component "when more input follows the reply"
      (let [read! (reader-of (concat decrpm [(int \X)]))]
        (assertions
          "the whole reply is consumed in one decode"
          (key-from-bytes read!) => :other
          "so the very next decode sees the real keystroke that followed it"
          (key-from-bytes read!) => [:char \X])))))

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
                                            :data  {:invokeid    "writer"
                                                    :stop-reason :end_turn
                                                    :usage       {:input-tokens 1 :output-tokens 1}
                                                    :content     [{:type :text :text "hello"}]}})
      "[writer]")
    => true

    "checkpoint/written is suppressed (returns nil)"
    (tui/format-event {:event :checkpoint/written :data {:session-id "x"}})
    => nil

    "tick is suppressed"
    (tui/format-event {:event :runner/tick :data {:i 0}}) => nil))

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
                   :data  {:invokeid    "writer"
                           :stop-reason :tool_use
                           :usage       {:input-tokens 1 :output-tokens 1}
                           :content     [{:type :text :text "thinking out loud"}
                                         {:type  :tool_use :id "u1" :name "event__ok"
                                          :input {:msg "go"}}]}})
    (tui/event! h {:event :runner/event-processed :ts 4
                   :data  {:event-name    :step
                           :config-before [:a] :config-after [:b]}})
    (tui/event! h {:event :llm/tool-result :ts 5
                   :data  {:invokeid "writer"
                           :tool     :ok :is-error false :content-preview "ok"}})
    (let [sb      (:scrollback @(:state h))
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
  (let [h    (mk-handle {:inspector? true :debug? false})
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
