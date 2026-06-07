(ns escapement.tui-test
  (:require
    [clojure.string :as str]
    [com.fulcrologic.statecharts.chart :as sc.chart]
    [com.fulcrologic.statecharts.elements :as sc.e]
    [escapement.tui :as tui]
    [escapement.tui.theme :as theme]
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

;; ---------------------------------------------------------------------------
;; Semantic color theme (task 001)
;; ---------------------------------------------------------------------------

(def ^:private theme-keys-set
  #{:border-dim :border-focus :title :chart-name :session-id :timestamp
    :metric :phase-current :phase-done :phase-upcoming
    :status/streaming :status/done :status/waiting :status/error :status/idle
    :bar-filled :bar-empty})

(specification "color-capability — capability detection"
  (assertions
    "NO_COLOR set ⇒ :none, even on a tty with a 256color TERM"
    (tui/color-capability {:no-color "1" :term "xterm-256color"} true) => :none
    "NO_COLOR set to empty string still counts (presence, not truthiness)"
    (tui/color-capability {:no-color "" :term "xterm-256color"} true) => :none
    "not a tty ⇒ :none"
    (tui/color-capability {:term "xterm-256color"} false) => :none
    "nil TERM ⇒ :none"
    (tui/color-capability {:term nil} true) => :none
    "dumb TERM ⇒ :none"
    (tui/color-capability {:term "dumb"} true) => :none
    "256color TERM on a tty ⇒ :256"
    (tui/color-capability {:term "xterm-256color"} true) => :256
    "screen-256color ⇒ :256"
    (tui/color-capability {:term "screen-256color"} true) => :256
    "-direct TERM ⇒ :256"
    (tui/color-capability {:term "xterm-direct"} true) => :256
    "COLORTERM=truecolor ⇒ :truecolor"
    (tui/color-capability {:term "xterm" :colorterm "truecolor"} true) => :truecolor
    "COLORTERM=24bit ⇒ :truecolor"
    (tui/color-capability {:term "xterm" :colorterm "24bit"} true) => :truecolor
    "plain xterm on a tty ⇒ :16"
    (tui/color-capability {:term "xterm"} true) => :16))

(specification "theme-for — semantic theme construction + fallback"
  (let [t256  (tui/theme-for :256)
        t16   (tui/theme-for :16)
        tnone (tui/theme-for :none)
        ttrue (tui/theme-for :truecolor)]
    (assertions
      ":256 theme defines every required semantic key"
      (every? #(contains? t256 %) theme-keys-set) => true
      ":16 theme defines every required semantic key"
      (every? #(contains? t16 %) theme-keys-set) => true
      ":none theme defines every required semantic key"
      (every? #(contains? tnone %) theme-keys-set) => true
      ":256 codes are all non-empty"
      (every? #(seq (get t256 %)) theme-keys-set) => true
      ":16 codes are all non-empty"
      (every? #(seq (get t16 %)) theme-keys-set) => true
      ":none codes are all empty strings"
      (every? #(= "" (get tnone %)) theme-keys-set) => true
      ":truecolor reuses the 256 ramp"
      (= ttrue t256) => true
      ":256 uses 38;5;N foreground codes"
      (some? (re-find #"38;5;" (get t256 :border-dim))) => true)))

(specification "paint — no-op under :none, wraps under color"
  (let [t256  (tui/theme-for :256)
        tnone (tui/theme-for :none)]
    (assertions
      "paint under :none returns the body unchanged (no escape codes)"
      (tui/paint tnone :title "hello") => "hello"
      "paint under :256 wraps with an SGR escape and reset"
      (.contains ^String (tui/paint t256 :title "hello") "\033[") => true
      (.startsWith ^String (tui/paint t256 :title "hello") "\033[") => true
      (.endsWith ^String (tui/paint t256 :title "hello") "\033[0m") => true
      "the original body survives inside the wrapped string"
      (.contains ^String (tui/paint t256 :title "hello") "hello") => true
      "unknown key under :256 is a no-op (treated as empty)"
      (tui/paint t256 :no-such-key "hi") => "hi")))

(specification "status-color — status keyword → theme SGR code"
  (let [t256  (tui/theme-for :256)
        tnone (tui/theme-for :none)]
    (assertions
      "streaming → the streaming code"
      (tui/status-color t256 :streaming) => (:status/streaming t256)
      "done → the done code"
      (tui/status-color t256 :done) => (:status/done t256)
      "waiting → the waiting code"
      (tui/status-color t256 :waiting) => (:status/waiting t256)
      "error → the error code"
      (tui/status-color t256 :error) => (:status/error t256)
      "idle → the idle code"
      (tui/status-color t256 :idle) => (:status/idle t256)
      "exit also maps to idle (dim)"
      (tui/status-color t256 :exit) => (:status/idle t256)
      "unknown status falls back to idle"
      (tui/status-color t256 :whatever) => (:status/idle t256)
      "all statuses are empty under :none"
      (mapv #(tui/status-color tnone %) [:streaming :done :waiting :error :idle])
      => ["" "" "" "" ""])))

(specification "sgr-wrap — empty/nil code is a no-op"
  (assertions
    "nil code returns body unchanged"
    (tui/sgr-wrap nil "x") => "x"
    "empty code returns body unchanged"
    (tui/sgr-wrap "" "x") => "x"
    "a real code wraps + resets"
    (tui/sgr-wrap "36" "x") => "\033[36mx\033[0m"))

;; ---------------------------------------------------------------------------
;; Pane / box compositor + layout (task 002)
;; ---------------------------------------------------------------------------

(def ^:private draw-box #'escapement.tui/draw-box)

(specification "display-width — column counting"
  (assertions
    "ascii counts one per char"
    (tui/display-width "hello") => 5
    "empty string is zero"
    (tui/display-width "") => 0
    "SGR escape sequences are zero-width"
    (tui/display-width "\033[36mhi\033[0m") => 2
    "a 256-color SGR is zero-width"
    (tui/display-width "\033[38;5;71mabc\033[0m") => 3
    "box-drawing + shimmer glyphs count as 1 each"
    (tui/display-width "╭─╮│╰╯▌▐█░◉▰▱⇅") => 14
    "a CJK glyph counts as 2"
    (tui/display-width "日本") => 4
    "mixed CJK + ascii"
    (tui/display-width "a日b") => 4
    "colored CJK still counts the glyph as 2, escapes as 0"
    (tui/display-width "\033[36m日\033[0m") => 2))

(specification "truncate-display — exact-width padding & clipping"
  (assertions
    "pads a short string with spaces to exact width"
    (tui/truncate-display "ab" 5) => "ab   "
    "display-width of padded result equals n"
    (tui/display-width (tui/truncate-display "ab" 5)) => 5
    "an exact-fit string is unchanged"
    (tui/truncate-display "abcde" 5) => "abcde"
    "an over-long string is clipped with a trailing ellipsis to exact width"
    (tui/display-width (tui/truncate-display "abcdefgh" 5)) => 5
    "the clip ends in an ellipsis"
    (.endsWith ^String (tui/truncate-display "abcdefgh" 5) "…") => true
    "n<=0 yields empty string"
    (tui/truncate-display "abc" 0) => ""
    "does not split an SGR escape — escape copied whole, padded to width"
    (let [r (tui/truncate-display "\033[36mab\033[0m" 5)]
      (tui/display-width r)) => 5
    "padding a colored short string keeps the reset present"
    (.contains ^String (tui/truncate-display "\033[36mab\033[0m" 5) "\033[0m") => true))

(specification "truncate-display — neutralizes control chars (multiline bleed defense)"
  (assertions
    "strips embedded \\n \\r \\t — no control char survives into the cell"
    (let [r (tui/truncate-display "ab\ncd\ref\tgh" 12)]
      (boolean (re-find #"[\n\r\t]" r))) => false
    "result occupies exactly n columns after stripping controls"
    (tui/display-width (tui/truncate-display "ab\ncd\ref\tgh" 12)) => 12
    "control chars become a width-1 space rather than width-0 (exact display width)"
    (tui/truncate-display "a\nb" 3) => "a b"
    "other C0 controls (e.g. \\f, NUL) are neutralized too"
    (boolean (re-find #"[ -]"
               (tui/truncate-display "a bc" 10))) => false
    "SGR escapes are still preserved alongside control-char stripping"
    (.contains ^String (tui/truncate-display "\033[36ma\nb\033[0m" 6) "\033[0m") => true
    (tui/display-width (tui/truncate-display "\033[36ma\nb\033[0m" 6)) => 6))

(specification "collapse-ws / summary builders — multiline content flattened to one line"
  (assertions
    "collapse-ws turns newlines/tabs/runs of whitespace into single spaces, trimmed"
    (tui/collapse-ws "  a\nb\t\tc\r\nd  ") => "a b c d"
    "collapse-ws leaves no control whitespace"
    (boolean (re-find #"[\n\r\t]" (tui/collapse-ws "x\ny\rz\tw"))) => false)
  (component "llm/user-message summary collapses multiline :text"
    (let [s (tui/format-event {:event :llm/user-message
                               :data  {:text "Line 1\nLine 2\nLine 3"}})]
      (assertions
        "no embedded newline survives into the rendered summary line"
        (boolean (re-find #"[\n\r\t]" s)) => false
        "the multiline text is joined with spaces"
        (.contains ^String s "Line 1 Line 2 Line 3") => true)))
  (component "llm/tool-result summary collapses multiline :content-preview"
    (let [s (tui/format-event {:event :llm/tool-result
                               :data  {:tool "t" :content-preview "a\nb\tc\rd"}})]
      (assertions
        "no embedded control whitespace survives"
        (boolean (re-find #"[\n\r\t]" s)) => false))))

(specification "draw-box — borders, title, scroll, column offset"
  (component "light box at column 1"
    (let [buf (StringBuilder.)]
      (draw-box buf {:row 1 :col 1 :w 10 :h 4 :title "X"
                     :body-lines ["hi"]})
      (let [s (.toString buf)]
        (assertions
          "uses the light top-left corner"
          (.contains s "╭") => true
          "uses the light bottom corners"
          (and (.contains s "╰") (.contains s "╯")) => true
          "embeds the title"
          (.contains s "X") => true
          "renders the body content"
          (.contains s "hi") => true))))
  (component "heavy (focus) box at a nonzero column offset"
    (let [buf (StringBuilder.)]
      (draw-box buf {:row 5 :col 40 :w 20 :h 4 :title "LOG" :focus? true
                     :scroll {:pos 312 :total 480}
                     :body-lines ["row"]})
      (let [s (.toString buf)]
        (assertions
          "uses the heavy top-left corner"
          (.contains s "▛") => true
          "uses the heavy bottom corners"
          (and (.contains s "▙") (.contains s "▟")) => true
          "shows the scroll indicator in the top border"
          (.contains s "⇅ 312/480") => true
          "positions a cell at the offset column (move-to col 40)"
          (.contains s (#'escapement.tui/move-to-s 5 40)) => true
          "positions an interior right border at the offset (row 6, col+w-1 = 59)"
          (.contains s (#'escapement.tui/move-to-s 6 59)) => true)))))

(specification "layout — responsive geometry"
  (component "two-pane on a wide terminal"
    (let [l (tui/layout {:term-w 120 :term-h 30 :focus :log})]
      (assertions
        "mode is two-pane"
        (:mode l) => :two-pane
        "header sits at the top, full width"
        (select-keys (:header l) [:row :col :w]) => {:row 1 :col 1 :w 120}
        "footer is the last row, full width"
        (select-keys (:footer l) [:row :col :w]) => {:row 30 :col 1 :w 120}
        "live + log tile the body width with no gap and no overlap"
        (+ (get-in l [:live :w]) (get-in l [:log :w])) => 120
        "log starts immediately after live"
        (get-in l [:log :col]) => (+ 1 (get-in l [:live :w]))
        "both body panes start on the same row below the header"
        (get-in l [:live :row]) => (get-in l [:log :row])
        "body row is below the header"
        (get-in l [:live :row]) => (+ 1 (:h (:header l)))
        "body height fills between header and footer"
        (get-in l [:live :h]) => (- 30 (:h (:header l)) 1))))
  (component "narrow terminal falls back to single column"
    (let [l (tui/layout {:term-w 80 :term-h 24 :focus :log})]
      (assertions
        "mode is narrow"
        (:mode l) => :narrow
        "a single full-width body pane is provided"
        (select-keys (:body l) [:col :w]) => {:col 1 :w 80}
        "no overlap: body row below header, height to footer"
        (:row (:body l)) => (+ 1 (:h (:header l)))
        "the focused-pane key mirrors the body rect"
        (:log l) => (:body l))))
  (component "maximized returns one body rect for the focused pane"
    (let [l (tui/layout {:term-w 120 :term-h 30 :focus :live :maximized? true})]
      (assertions
        "mode is maximized"
        (:mode l) => :maximized
        "body fills the whole width"
        (:w (:body l)) => 120
        "focused (live) key mirrors the body"
        (:live l) => (:body l)
        "non-focused pane is absent"
        (:log l) => nil))))

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

(def ^:private live-tps* #'escapement.tui/live-tps)

(specification "live t/s measures generation rate (first→last delta), not request latency"
  (let [h (mk-handle {})]
    ;; :llm/start stamps a :first-ts at dispatch (status :waiting). A long gap
    ;; before the first token (model load / prompt eval / queue) must NOT drag
    ;; the rate down — :first-ts must re-anchor to the first delta.
    (tui/event! h {:event :llm/start :ts 1000
                   :data  {:invokeid "judge1" :session-id "j"}})
    (tui/event! h {:event :llm/delta :ts 5000        ;; 4s time-to-first-token
                   :data  {:invokeid "judge1" :session-id "j" :text "a"}})
    (tui/event! h {:event :llm/delta :ts 5500
                   :data  {:invokeid "judge1" :session-id "j" :text "b"}})
    (tui/event! h {:event :llm/delta :ts 6000
                   :data  {:invokeid "judge1" :session-id "j" :text "c"}})
    (let [v (get-in @(:state h) [:live "judge1" :sessions "j"])]
      (assertions
        ":first-ts is the first delta (5000), not the :llm/start dispatch (1000)"
        (:first-ts v) => 5000
        ":last-ts is the final delta"
        (:last-ts v) => 6000
        ;; 3 chunks over 1s = 3.0 t/s. With the old (dispatch) anchor it would be
        ;; 3 / 5s = 0.6 t/s — the bug this guards against.
        "live-tps reflects generation rate, excluding time-to-first-token"
        (live-tps* v) => 3.0))))

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
        ask! (resolve 'escapement.tui/ask!)
        ;; `ask!` paints real frames via `render-frame!` → `emit!`, which writes
        ;; ANSI to *err*. Sink that to a throwaway writer (on the worker thread,
        ;; where the painting happens) so the test suite output stays clean.
        sink (java.io.StringWriter.)]
    ;; Raise a modal on a worker thread so we can observe it.
    (let [worker (future (binding [*out* sink *err* sink]
                           (try (ask! h {:kind :text :prompt "y/n"})
                                (catch Throwable t t))))]
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

(specification "role-sgr — per-role hue obtainable as an SGR code"
  ;; Force the color-capability gate ON so the test is deterministic regardless
  ;; of the ambient terminal (CI has no TTY / TERM, so `ansi-supported?` would
  ;; otherwise be false and every SGR code would degrade to ""). The hue→SGR
  ;; mapping under test has nothing to do with the ambient TTY.
  (with-redefs [theme/ansi-supported? (constantly true)]
   (let [h (mk-handle {})]
    (tui/event! h {:event :llm/start :ts 1 :data {:invokeid "writer"}})
    (let [s    @(:state h)
          code (tui/role-sgr s "writer")]
      (assertions
        "an allocated invokeid yields a non-empty SGR code string"
        (and (string? code) (boolean (seq code))) => true
        "the same code the LIVE rows use (from :invokeid-colors)"
        code => (get-in s [:invokeid-colors "writer"])
        "an unknown source yields \"\" (composes as a no-op)"
        (tui/role-sgr s "nope") => ""
        "the code is usable directly by sgr-wrap to color a log line"
        (.contains ^String (tui/sgr-wrap code "04:35 writer ◂ hi") "writer") => true)))))

;; ---------------------------------------------------------------------------
;; LIVE pane renderer (task 003)
;; ---------------------------------------------------------------------------

(def ^:private none (tui/theme-for :none))

(defn- count-filled [s] (count (filter #(= \█ %) s)))
(defn- count-empty [s] (count (filter #(= \░ %) s)))

(specification "completion-bar — fill fraction is honest"
  (assertions
    "6/30 ≈ 20% of 10 cells = 2 filled, 8 empty"
    (count-filled (tui/completion-bar none 6 30 10)) => 2
    (count-empty (tui/completion-bar none 6 30 10)) => 8
    "6/6 fills fully"
    (count-filled (tui/completion-bar none 6 6 10)) => 10
    (count-empty (tui/completion-bar none 6 6 10)) => 0
    "0/30 fills none"
    (count-filled (tui/completion-bar none 0 30 10)) => 0
    "exactly 10 display columns (uncolored)"
    (tui/display-width (tui/completion-bar none 6 30 10)) => 10
    "total<=0 ⇒ all empty (no divide-by-zero)"
    (count-filled (tui/completion-bar none 3 0 10)) => 0
    "width<=0 ⇒ empty string"
    (tui/completion-bar none 6 30 0) => ""))

(specification "shimmer — deterministic, one bright cell, advances by tick"
  (assertions
    "exactly one bright ▰ cell"
    (count (filter #(= \▰ %) (tui/shimmer none 5 0))) => 1
    "rest are dim ▱"
    (count (filter #(= \▱ %) (tui/shimmer none 5 0))) => 4
    "exactly width display columns"
    (tui/display-width (tui/shimmer none 5 0)) => 5
    "deterministic for a given tick"
    (tui/shimmer none 5 3) => (tui/shimmer none 5 3)
    "advancing the tick moves the bright cell"
    (not= (tui/shimmer none 5 0) (tui/shimmer none 5 1)) => true
    "tick wraps modulo width"
    (tui/shimmer none 5 5) => (tui/shimmer none 5 0)))

(specification "live-pane-lines — bars only on multi-session groups"
  (let [multi {:live {"judge1"
                      {:sessions
                       (into {} (for [i (range 30)]
                                  [(str "judge1." i)
                                   {:session (str "judge1." i)
                                    :status  (if (< i 6) :done :streaming)
                                    :tokens  10 :first-ts 0 :last-ts 1000}]))}}}
        single {:live {"host"
                       {:sessions {"host.0" {:session "host.0" :status :streaming
                                             :tokens 100 :first-ts 0 :last-ts 1000}}}}}
        done1  {:live {"host"
                       {:sessions {"host.0" {:session "host.0" :status :done
                                             :tokens 100 :first-ts 0 :last-ts 1000}}}}}
        mlines (tui/live-pane-lines multi none 60)
        slines (tui/live-pane-lines single none 60)
        dlines (tui/live-pane-lines done1 none 60)
        header (first mlines)]
    (assertions
      "multi-session group header shows a done/total bar (6/30)"
      (boolean (re-find #"6/30 done" header)) => true
      "the header bar fills ~20% (2/10 cells)"
      (count-filled header) => 2
      (count-empty header) => 8
      "single streaming session shows NO bar (no █ or ░ glyphs)"
      (count-filled (first slines)) => 0
      (count-empty (first slines)) => 0
      "single streaming session shows a shimmer (a ▰ cell)"
      (boolean (some #(re-find #"▰" %) slines)) => true
      "a single DONE session shows no shimmer and no bar"
      (boolean (some #(re-find #"[▰█░]" %) dlines)) => false
      "child rows carry no bar glyphs"
      (every? #(zero? (+ (count-filled %) (count-empty %))) (rest mlines)) => true
      "every line fits the interior width exactly"
      (every? #(= 60 (tui/display-width %)) mlines) => true)))

(specification "live-pane-lines — child roll-up + offset slicing"
  (let [s {:live {"judge1"
                  {:sessions
                   (into {} (for [i (range 30)]
                              [(str "judge1." i)
                               {:session (str "judge1." i)
                                :status  :streaming
                                :tokens  5 :first-ts 0 :last-ts 1000}]))}}}
        lines (tui/live-pane-lines s none 60)]
    (assertions
      "a `…+N more` roll-up is present when sessions exceed the child cap"
      (boolean (some #(re-find #"more sessions" %) lines)) => true
      "offset drops leading lines (scroll capability)"
      (tui/live-pane-lines s none 60 2) => (vec (drop 2 lines))
      "empty :live ⇒ empty vector"
      (tui/live-pane-lines {:live {}} none 60) => [])))

;; ---------------------------------------------------------------------------
;; LOG pane renderer (task 004)
;; ---------------------------------------------------------------------------

(defn- mk-scrollback
  "Synthetic scrollback of `n` entries, alternating two invokeid roles plus a
   chart line, each with a monotonically increasing :ts so order is stable."
  [n]
  (vec (for [i (range n)]
         {:source  (case (mod i 3) 0 "judge1" 1 "poet-1" :chart)
          :glyph   \·
          :summary (str "line " i)
          :ev      {:ts (+ 1000 i)}})))

(defn- mk-scrollback-strings
  "Synthetic scrollback of `n` entries all from the SAME un-allocated invokeid
   source, so `role-sgr` resolves to \"\" (no SGR) regardless of TERM."
  [n]
  (vec (for [i (range n)]
         {:source  "neverallocated"
          :glyph   \·
          :summary (str "x" i)
          :ev      {:ts (+ 1000 i)}})))

(specification "log-pane-lines — slice math, offset clamping, pos/total"
  (let [sb (mk-scrollback 10)
        s  {:scrollback sb}
        r  (tui/log-pane-lines s none 40 4 0)]
    (assertions
      "returns exactly interior-h lines when scrollback is long enough"
      (count (:lines r)) => 4
      "each line is exactly interior-w display columns"
      (every? #(= 40 (tui/display-width %)) (:lines r)) => true
      "offset 0 tails the newest entries (last visible == last entry)"
      (boolean (re-find #"line 9" (last (:lines r)))) => true
      "pos = bottom index = total when offset 0; total is full count"
      (:scroll r) => {:pos 10 :total 10}))

  (let [sb (mk-scrollback 10)
        s  {:scrollback sb}]
    (assertions
      "offset shifts the window up; bottom line moves back by the offset"
      (boolean (re-find #"line 6" (last (:lines (tui/log-pane-lines s none 40 4 3)))))
      => true
      "offset clamps at the top (cannot scroll past oldest)"
      (:scroll (tui/log-pane-lines s none 40 4 999)) => {:pos 4 :total 10}
      "a huge offset shows the OLDEST window (first line is entry 0)"
      (boolean (re-find #"line 0" (first (:lines (tui/log-pane-lines s none 40 4 999)))))
      => true
      "negative offset clamps to bottom (== offset 0)"
      (tui/log-pane-lines s none 40 4 -5) => (tui/log-pane-lines s none 40 4 0)))

  (assertions
    "short scrollback returns fewer than interior-h lines, no padding rows"
    (count (:lines (tui/log-pane-lines {:scrollback (mk-scrollback 2)} none 40 5 0)))
    => 2
    "empty scrollback ⇒ no lines, 0/0 scroll"
    (tui/log-pane-lines {:scrollback []} none 40 5 0)
    => {:lines [] :scroll {:pos 0 :total 0}}))

(specification "log-pane-lines — role coloring + cursor highlight"
  ;; Force color-capability ON (see role-sgr spec) so role hues are emitted
  ;; deterministically in CI's TTY-less env. The cursor-highlight ([7m) and
  ;; the :none-theme "no color" assertions are independent of this gate.
  (with-redefs [theme/ansi-supported? (constantly true)]
   (let [h  (mk-handle {})]
    ;; allocate a real per-role hue via the live event path
    (tui/event! h {:event :llm/start :ts 1 :data {:invokeid "judge1" :session-id "j"}})
    (let [s    @(:state h)
          t256 (tui/theme-for :256)
          sb   (mk-scrollback 4)
          s'   (assoc s :scrollback sb)
          code (tui/role-sgr s' "judge1")]
      (assertions
        "a role with an allocated hue colors its token (SGR present)"
        (boolean (and (seq code)
                   (some #(str/includes? % code) (:lines (tui/log-pane-lines s' t256 60 4 0)))))
        => true
        "no color under :none theme/state ⇒ no SGR escapes in lines"
        (every? #(not (str/includes? % "["))
          (:lines (tui/log-pane-lines
                    {:scrollback (mk-scrollback-strings 4)} none 60 4 0)))
        => true
        "cursor-idx in the visible window reverse-videos that line"
        (boolean (some #(str/includes? % "[7m")
                   (:lines (tui/log-pane-lines {:scrollback sb} none 60 4 0 2))))
        => true
        "cursor-idx outside the window adds no reverse-video"
        (some #(str/includes? % "[7m")
          (:lines (tui/log-pane-lines {:scrollback (mk-scrollback 20)} none 60 4 0 0)))
        => nil)))))

;; ---------------------------------------------------------------------------
;; Phase tracker + header strip (task 005)
;; ---------------------------------------------------------------------------

(def ^:private session-tps-sum #'escapement.tui/session-tps-sum)

;; A linear chart: ROOT → run(compose, judging-r1, tallying-r1, summarizing),
;; where judging-r1 is atomic. Active config = [:run :judging-r1].
(def ^:private linear-chart
  (sc.chart/statechart {}
    (sc.e/state {:id :run}
      (sc.e/state {:id :composing})
      (sc.e/state {:id :judging-r1})
      (sc.e/state {:id :tallying-r1})
      (sc.e/state {:id :summarizing}))))

;; A deep chart for overflow: run → loop → step-1 … step-9 (atomic).
(def ^:private overflow-chart
  (sc.chart/statechart {}
    (sc.e/state {:id :run}
      (apply sc.e/state {:id :loop}
        (map (fn [n] (sc.e/state {:id (keyword (str "step-" n))}))
          (range 1 10))))))

;; A parallel chart: run → ⫶ regions r-a / r-b / r-c, each atomic-ish.
(def ^:private parallel-chart
  (sc.chart/statechart {}
    (sc.e/state {:id :run}
      (sc.e/parallel {:id :fan}
        (sc.e/state {:id :fetch-data})
        (sc.e/state {:id :poll-status})
        (sc.e/state {:id :render-frame})))))

(specification "phase-model — linear chart: breadcrumb + flagged current sibling"
  (let [m (tui/phase-model linear-chart [:run :judging-r1])]
    (assertions
      "not a fallback / not parallel"
      [(:fallback? m) (:parallel? m)] => [false false]
      "breadcrumb runs root→active branch"
      (:breadcrumb m) => [:run :judging-r1]
      "current is the deepest active state"
      (:current m) => :judging-r1
      "siblings are the document-order children of :run"
      (mapv :id (:siblings m)) => [:composing :judging-r1 :tallying-r1 :summarizing]
      "exactly the current one is flagged"
      (mapv :current? (:siblings m))
      => [false true false false])))

(specification "sibling-strip — overflow centers ◉ with … on the long side"
  (let [m   (tui/phase-model overflow-chart [:run :loop :step-6])
        out (tui/sibling-strip m 30 none)]
    (assertions
      "model centers on step-6"
      (:current m) => :step-6
      "the strip marks the current sibling with ◉"
      (str/includes? out "◉ step-6") => true
      "current is not at an extreme end ⇒ ellipsis on the leading side"
      (str/includes? out "…") => true
      "the strip is at most the requested width"
      (<= (tui/display-width out) 30) => true)))

(specification "phase-model — parallel config: leaves, no linear strip"
  (let [m (tui/phase-model parallel-chart
            [:run :fan :fetch-data :poll-status :render-frame])]
    (assertions
      "flagged parallel"
      (:parallel? m) => true
      "no single sibling sequence"
      (:siblings m) => nil
      "leaves are the active regions"
      (set (:leaves m)) => #{:fetch-data :poll-status :render-frame}
      "breadcrumb is the lowest common parent path"
      (:breadcrumb m) => [:run :fan])))

(specification "phase-model — nil / unintrospectable chart degrades to fallback"
  (let [m  (tui/phase-model nil [:run :judging-r1])
        m2 (tui/phase-model {:not :a-chart} [:x])]
    (assertions
      "nil chart ⇒ fallback flagged"
      (:fallback? m) => true
      "raw config preserved for the states: line"
      (:raw-config m) => [:run :judging-r1]
      "current still set to the deepest config id"
      (:current m) => :judging-r1
      "a map without elements-by-id is also a fallback"
      (:fallback? m2) => true)))

(specification "session-tps-sum — aggregate equals sum of active sessions' t/s"
  ;; Build a :live map with two streaming sessions (known first/last-ts so
  ;; live-tps is deterministic) and one done session (excluded).
  (let [s {:live {"judge1" {:sessions
                            {"s1" {:status :streaming :tokens 10 :first-ts 0 :last-ts 1000}
                             "s2" {:status :streaming :tokens 20 :first-ts 0 :last-ts 1000}}}
                  "poet" {:sessions
                          {"s3" {:status :done :tokens 99 :first-ts 0 :last-ts 1000}}}}}]
    (assertions
      ;; s1: 10 tok / 1s = 10 t/s ; s2: 20 t/s ; done excluded ⇒ 30
      "sum across active sessions only"
      (session-tps-sum s) => 30.0)))

(specification "header-lines — three width-fit lines incl. fallback states: line"
  (let [h (mk-handle {})
        s {:config [:run :judging-r1] :start-ts 0 :live {}}
        ;; no chart attached to the handle ⇒ fallback path on line 3
        [l1 l2 l3] (tui/header-lines h s none 80 0)]
    (assertions
      "three lines"
      (count (tui/header-lines h s none 80 0)) => 3
      "each line is exactly the requested width"
      [(tui/display-width l1) (tui/display-width l2) (tui/display-width l3)]
      => [80 80 80]
      "line 1 carries chart name + clock"
      (and (str/includes? l1 "escapement · x/y") (str/includes? l1 "◷")) => true
      "line 2 carries the breadcrumb + metrics"
      (and (str/includes? l2 "▶") (str/includes? l2 "LLMs")) => true
      "line 3 degrades to a states: line (no chart attached)"
      (str/includes? l3 "states:") => true)))

;; ---------------------------------------------------------------------------
;; Focus / maximize / footer composition (task 006)
;; ---------------------------------------------------------------------------

(specification "cycle-focus toggles between :live and :log"
  (assertions
    ":log → :live" (tui/cycle-focus :log) => :live
    ":live → :log" (tui/cycle-focus :live) => :log))

(specification "clamp-scroll bounds an offset into [0, total-visible]"
  (assertions
    "negative clamps to 0"        (tui/clamp-scroll -5 100 20) => 0
    "nil clamps to 0"             (tui/clamp-scroll nil 100 20) => 0
    "within range passes"         (tui/clamp-scroll 30 100 20) => 30
    "past the top clamps to max"  (tui/clamp-scroll 999 100 20) => 80
    "everything visible ⇒ 0 max"  (tui/clamp-scroll 5 10 50) => 0
    "exactly at max stays"        (tui/clamp-scroll 80 100 20) => 80))

(specification "footer-text is contextual to focus / maximized / debug"
  (let [split-log  (tui/footer-text {:focus :log :maximized? false :debug? false})
        split-live (tui/footer-text {:focus :live :maximized? false :debug? false})
        max-log    (tui/footer-text {:focus :log :maximized? true :debug? false})
        dbg        (tui/footer-text {:focus :log :maximized? false :debug? true})]
    (assertions
      "split LOG names LOG and offers Enter=transcript + m maximize + Esc interrupt"
      (and (str/includes? split-log "LOG")
        (str/includes? split-log "Enter transcript")
        (str/includes? split-log "m maximize")
        (not (str/includes? split-log "Enter maximize"))
        (str/includes? split-log "Tab → LIVE")
        (str/includes? split-log "Esc interrupt")) => true
      "split LIVE points Tab at LOG and shows j/k select"
      (and (str/includes? split-live "Tab → LOG")
        (str/includes? split-live "j/k select")) => true
      "maximized LOG offers Esc restore split, not interrupt"
      (and (str/includes? max-log "(max)")
        (str/includes? max-log "Esc restore split")
        (not (str/includes? max-log "Esc interrupt"))) => true
      "debug adds the s/c/p/P and v hints"
      (and (str/includes? dbg "s/c/p/P") (str/includes? dbg "v viz")) => true
      "Ctrl-C quit is always present"
      (every? #(str/includes? % "Ctrl-C quit") [split-log split-live max-log dbg]) => true)))

(specification "theme-color? / role-sgr-themed — NO_COLOR theme emits zero hue"
  (let [none-theme (tui/theme-for :none)
        col-theme  (tui/theme-for :256)
        s          {:invokeid-colors {"poet-1" "36"} :next-color-idx 1}]
    (assertions
      "the :none theme reports no color"
      (tui/theme-color? none-theme) => false
      "a real theme reports color"
      (tui/theme-color? col-theme) => true
      "role-sgr-themed under :none returns empty (no escape) regardless of TERM"
      (tui/role-sgr-themed none-theme s "poet-1") => "")))

(specification "dispatch-key — Enter=transcript, m=maximize, j/k context-sensitive"
  (assertions
    "Enter → open the transcript (no longer maximizes)"
    (tui/dispatch-key :enter {:focus :live :maximized? false}) => :open-transcript
    "m → maximize the focused pane"
    (tui/dispatch-key [:char \m] {:focus :log :maximized? false}) => :maximize
    "Tab → focus-cycle"
    (tui/dispatch-key :tab {:focus :live :maximized? false}) => :focus-cycle
    "Esc while maximized restores the split"
    (tui/dispatch-key :esc {:focus :log :maximized? true}) => :restore-split
    "Esc otherwise interrupts"
    (tui/dispatch-key :esc {:focus :log :maximized? false}) => :interrupt
    "j while LIVE focused moves the selection cursor"
    (tui/dispatch-key [:char \j] {:focus :live :maximized? false}) => :live-cursor-down
    "k while LIVE focused moves the selection cursor up"
    (tui/dispatch-key [:char \k] {:focus :live :maximized? false}) => :live-cursor-up
    "g/G while LIVE focused jump the cursor"
    (tui/dispatch-key [:char \g] {:focus :live :maximized? false}) => :live-cursor-top
    (tui/dispatch-key [:char \G] {:focus :live :maximized? false}) => :live-cursor-bottom
    "j while LOG focused scrolls the pane (no cursor)"
    (tui/dispatch-key [:char \j] {:focus :log :maximized? false}) => :scroll-down
    (tui/dispatch-key [:char \k] {:focus :log :maximized? false}) => :scroll-up
    "arrow Down/Up follow the same LIVE-vs-LOG rule"
    (tui/dispatch-key :down {:focus :live :maximized? false}) => :live-cursor-down
    (tui/dispatch-key :down {:focus :log :maximized? false}) => :scroll-down
    "unknown key → nil (falls through to misc handlers)"
    (tui/dispatch-key [:char \v] {:focus :log :maximized? false}) => nil))

(specification "clamp-live-cursor — bounds the LIVE selection cursor"
  (assertions
    "nil clamps to 0"            (tui/clamp-live-cursor nil 5) => 0
    "within range passes"       (tui/clamp-live-cursor 3 5) => 3
    "past the end clamps"       (tui/clamp-live-cursor 99 5) => 4
    "negative clamps to 0"      (tui/clamp-live-cursor -2 5) => 0
    "empty pane ⇒ 0"            (tui/clamp-live-cursor 3 0) => 0))

(def ^:private theme-256 (escapement.tui.theme/theme-for :256))
(def ^:private theme-none (escapement.tui.theme/theme-for :none))

(specification "paused-banner — themed PAUSED debug banner (task 008)"
  (assertions
    "carries the PAUSED label + all step/continue/pause/arm hints"
    (let [b (tui/paused-banner theme-256 "[P] ")]
      (and (str/includes? b "PAUSED")
        (str/includes? b "s=step")
        (str/includes? b "c=continue")
        (str/includes? b "p=pause")
        (str/includes? b "P=arm")
        (str/includes? b "?=inspector"))) => true
    "includes the status-indicator prefix verbatim"
    (str/includes? (tui/paused-banner theme-256 "[P] ") "[P]") => true
    "a colored theme emits SGR escape(s)"
    (str/includes? (tui/paused-banner theme-256 "") "[") => true
    "NO_COLOR / :none theme emits ZERO escapes"
    (str/includes? (tui/paused-banner theme-none "[P] ") "") => false
    ":none banner is plain readable text"
    (tui/paused-banner theme-none "[P] ")
    => " [P] PAUSED  s=step  c=continue  p=pause  P=arm  ?=inspector"))

(specification "viz-entry — themed scrollback entry for the v launcher (task 008)"
  (assertions
    "sources the entry as :viz so the LOG pane role-colors it"
    (:source (tui/viz-entry theme-256 "live: http://x")) => :viz
    "info entries get the ◆ glyph"
    (:glyph (tui/viz-entry theme-256 "live: http://x")) => \◆
    "error entries get the ✗ glyph"
    (:glyph (tui/viz-entry theme-256 "boom" :error? true)) => \✗
    "summary carries the plain message body (LOG pane themes it)"
    (:summary (tui/viz-entry theme-256 "live: http://x")) => "live: http://x"
    "legacy :line is PLAIN (escape-free) so truncate/collapse-ws can't mangle it"
    (let [line (:line (tui/viz-entry theme-256 "live: http://x"))]
      (and (str/includes? line "[viz]")
        (str/includes? line "live: http://x")
        (not (str/includes? line "")))) => true
    "the entry itself contributes zero escapes (NO_COLOR-safe by construction)"
    (str/includes? (pr-str (tui/viz-entry theme-256 "x")) "") => false))
