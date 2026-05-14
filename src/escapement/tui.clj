(ns escapement.tui
  "Persistent terminal UI for interactive chart runs. Subscribes to the runner's
  transcript via `event!`, owns a header / status line / scrollback / modal
  region layout, and exposes a `HumanRenderer` implementation that pops modals
  in the bottom region.

  Design notes:
  * The TUI runs on a single background thread (the input reader). Rendering
    happens on whichever thread calls `event!` or modal methods, serialized by
    a single lock.
  * Esc sends a `:ui.interrupt` event to the attached session — the chart
    decides what to do with it. Ctrl-C posts `:ui.quit` (chart should treat as
    fatal cancel) and then exits the input loop.
  * Always emits to *err* so chart stdout stays clean (a convention from the
    bb-tui skill).
  * No colors per project convention; reverse-video for selection highlights.
  * `start!` returns a handle even when no TTY is attached — all methods become
    no-ops so the calling code path stays uniform; the renderer falls through
    to stdin behavior in that case."
  (:require
   [clojure.string :as str]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.protocols :as sp]
   [escapement.invocation.human-input :as hi])
  (:import
   (java.io Reader)
   (org.jline.terminal Terminal TerminalBuilder)))

;; ---------------------------------------------------------------------------
;; TTY detection
;; ---------------------------------------------------------------------------

(def ^:private bb-tty?
  ;; Runtime-resolved so this namespace loads under JVM Clojure (where
  ;; `babashka.terminal` isn't on the classpath) as well as under bb (where it
  ;; is). When unavailable, falls back to `(System/console)` which reports
  ;; whether *either* direction is a real tty — coarser, but adequate.
  (try
    (require 'babashka.terminal)
    (resolve 'babashka.terminal/tty?)
    (catch Throwable _ nil)))

(defn interactive-terminal?
  "True when both stdin and stdout are attached to a real terminal. Uses
   babashka's bundled `babashka.terminal/tty?` helper when available; falls
   back to `(System/console)` under plain JVM Clojure."
  []
  (boolean
   (if bb-tty?
     (and (bb-tty? :stdin) (bb-tty? :stdout))
     (some? (System/console)))))

;; ---------------------------------------------------------------------------
;; ANSI primitives — written to *err*, no colors.
;; ---------------------------------------------------------------------------

(def ^:private CSI "\033[")
(defn- esc [s] (str CSI s))
(def ^:private clear-screen-s (str (esc "2J") (esc "H")))
(def ^:private clear-eol-s    (esc "K"))
;; Alternate screen buffer — full-screen TUI convention. Entering switches the
;; terminal to a fresh blank screen; leaving restores the prior contents (so
;; the user's shell scrollback isn't polluted with our paint).
(def ^:private alt-screen-on-s  (esc "?1049h"))
(def ^:private alt-screen-off-s (esc "?1049l"))
;; Synchronized Output (Mode 2026). When the terminal supports it, any writes
;; between BSU/ESU are buffered and rendered atomically — true per-frame double
;; buffering. Supported by iTerm2 (recent), kitty, wezterm, foot, ghostty,
;; contour. Apple Terminal does NOT support it; we detect at startup.
(def ^:private sync-output-begin-s (esc "?2026h"))
(def ^:private sync-output-end-s   (esc "?2026l"))
(def ^:private sync-output-query-s (esc "?2026$p"))
(def ^:private hide-cursor-s  (esc "?25l"))
(def ^:private show-cursor-s  (esc "?25h"))
(def ^:private reverse-on-s   (esc "7m"))
(def ^:private reset-attrs-s  (esc "0m"))
(defn- move-to-s [row col] (esc (str row ";" col "H")))

(defn- emit! [s]
  (binding [*out* *err*]
    (print s)
    (flush)))

;; ---------------------------------------------------------------------------
;; Event → one-line summary
;; ---------------------------------------------------------------------------

(defn- truncate [s n]
  (let [s (str s)]
    (if (<= (count s) n) s (str (subs s 0 (max 0 (- n 1))) "…"))))

(defn format-event
  "Public for tests. Turn a transcript event map into a single-line string."
  [{:keys [event data]}]
  (case event
    :runner/started          (str "[runner] started session=" (:session-id data) " chart=" (:chart-id data))
    :runner/start-config     (str "[runner] start config=" (:config data))
    :runner/event-processed  (str "[chart] event=" (:event-name data) " config=" (:config-after data))
    :runner/quiescent        nil ;; heartbeat — useful in JSONL, pure noise in TUI
    :runner/done             (str "[runner] done final=" (:final-config data))
    :runner/aborted          (str "[runner] aborted " (:reason data))
    :runner/error            (str "[runner] ERROR " (truncate (:message data) 200))
    :llm/request             (str "[llm/req] n-messages=" (:n-messages data))
    :llm/response            (str "[llm/resp] stop=" (:stop-reason data)
                                  " tokens=in:" (get-in data [:usage :input-tokens] "?")
                                  "/out:" (get-in data [:usage :output-tokens] "?"))
    :llm/context-warning     (str "[llm/warn] context " (long (* 100 (:used-frac data 0))) "%")
    :llm/error               (str "[llm/err] " (truncate (:message data) 200))
    :human-input/start       (str "[human] prompt kind=" (:kind data))
    :human-input/answer      (str "[human] answer kind=" (:kind data))
    :human-input/cancelled   "[human] cancelled"
    :human-input/error       (str "[human] ERROR " (truncate (:message data) 200))
    :checkpoint/written      nil ;; suppress
    :runner/tick             nil ;; suppress
    ;; default
    (str "[" (name (or event :unknown)) "] "
         (truncate (pr-str (or data {})) 200))))

;; ---------------------------------------------------------------------------
;; Internal state
;; ---------------------------------------------------------------------------

(defrecord ^:private TuiHandle
           [enabled?
            state          ;; atom: {:config [], :scrollback [], :scroll-offset, :modal, :term-h, :term-w}
            lock           ;; rendering lock
            terminal       ;; JLine Terminal (or nil)
            raw-mode?      ;; atom bool
            input-thread   ;; Thread (or nil)
            session-id     ;; promise/atom
            queue          ;; promise/atom: the runner's event queue
            chart-sym
            session-short
            cursor-shown?  ;; atom bool — tracks last-emitted state, so we only
                           ;; emit hide/show ANSI on actual transitions
            sync-output?   ;; atom bool — terminal supports Mode 2026 atomic frames
            ])

;; ---------------------------------------------------------------------------
;; Rendering
;; ---------------------------------------------------------------------------

(defn- visible-scrollback
  "Take the slice of scrollback that fits on screen given term-h and the
   layout: 1 header + 1 status + N scrollback + 2 modal/help."
  [{:keys [scrollback scroll-offset]} term-h]
  (let [room (max 5 (- term-h 4))]
    (let [n     (count scrollback)
          end   (max 0 (- n scroll-offset))
          start (max 0 (- end room))]
      (subvec scrollback start end))))

(defn- render-frame!
  [{:keys [state lock terminal] :as h}]
  (locking lock
    (let [term-h (if terminal (.getHeight ^Terminal terminal) 24)
          term-w (if terminal (.getWidth  ^Terminal terminal) 80)
          s      (swap! state assoc :term-h term-h :term-w term-w)
          header (str " escapement · " (:chart-sym h) " · " (:session-short h))
          status (str " states: " (pr-str (:config s [])))
          lines  (visible-scrollback s term-h)
          modal  (:modal s)
          help   " Esc=interrupt   Ctrl-C=quit   PgUp/PgDn=scroll"
          buf    (StringBuilder.)]
      ;; No full-screen clear per frame — that's the source of the flicker.
      ;; Each row is rewritten with clear-eol, and we explicitly blank any
      ;; rows in the scrollback region that don't have content this frame.
      ;; Cursor visibility is only toggled when it actually needs to change
      ;; (cursor-shown? tracks the last-emitted state).
      (let [want-cursor? (boolean (and modal (#{:text :confirm} (:kind modal))))
            shown?       @(:cursor-shown? h)]
        (when (not= want-cursor? shown?)
          (.append buf (if want-cursor? show-cursor-s hide-cursor-s))
          (reset! (:cursor-shown? h) want-cursor?)))
      (.append buf (move-to-s 1 1))
      (.append buf (truncate header term-w))
      (.append buf clear-eol-s)
      (.append buf (move-to-s 2 1))
      (.append buf (truncate status term-w))
      (.append buf clear-eol-s)
      ;; scrollback region: rows 3 .. (term-h - 2)
      (let [first-row 3
            last-row  (- term-h 2)
            written   (count lines)]
        (doseq [[i line] (map-indexed vector lines)]
          (let [row (+ first-row i)]
            (when (<= row last-row)
              (.append buf (move-to-s row 1))
              (.append buf (truncate line term-w))
              (.append buf clear-eol-s))))
        ;; Blank the rest of the scrollback region so stale content from a
        ;; previous frame doesn't linger when scrollback shrinks (e.g. after
        ;; dedup collapse, after terminal resize, or on initial render).
        (doseq [row (range (+ first-row written) (inc last-row))]
          (.append buf (move-to-s row 1))
          (.append buf clear-eol-s)))
      ;; modal area: row (term-h - 1)
      (let [modal-row (max 1 (dec term-h))]
        (.append buf (move-to-s modal-row 1))
        (.append buf clear-eol-s) ;; baseline: row is blank if no modal
        (cond
          (nil? modal) nil

          (= :text (:kind modal))
          (let [prompt (str " ▸ " (:prompt modal) " ")
                buffer (:buffer modal "")]
            (.append buf (truncate (str prompt buffer) term-w))
            (.append buf clear-eol-s)
            ;; Position cursor at end of buffer; will be shown explicitly below.
            (.append buf (move-to-s modal-row
                                    (inc (+ (count prompt) (count buffer))))))

          (= :confirm (:kind modal))
          (let [prompt (str " ▸ " (:prompt modal)
                            (if (:default modal) " [Y/n] " " [y/N] "))
                buffer (:buffer modal "")]
            (.append buf (truncate (str prompt buffer) term-w))
            (.append buf clear-eol-s)
            (.append buf (move-to-s modal-row
                                    (inc (+ (count prompt) (count buffer))))))

          (or (= :select (:kind modal)) (= :multi-select (:kind modal)))
          (let [multi?   (= :multi-select (:kind modal))
                options  (:options modal)
                idx      (:cursor modal 0)
                checked  (:checked modal #{})
                head     (str " ▸ " (:prompt modal)
                              (if multi? "  (Space=toggle, Enter=submit)" "  (Enter=select)"))
                ;; Inline rendering of options on the same row, comma-separated,
                ;; with reverse-video on the cursor and brackets on checked items.
                opt-strs (map-indexed
                          (fn [i o]
                            (let [label (str (if (and multi? (checked i)) "[x] " "")
                                             (:label o))]
                              (if (= i idx)
                                (str reverse-on-s label reset-attrs-s)
                                label)))
                          options)
                line     (str head "  " (clojure.string/join "  " opt-strs))]
            (.append buf (truncate line term-w))
            (.append buf clear-eol-s))

          :else
          (do (.append buf (truncate (str " ▸ " (pr-str modal)) term-w))
              (.append buf clear-eol-s))))
      ;; help: last row
      (.append buf (move-to-s term-h 1))
      (.append buf (truncate help term-w))
      (.append buf clear-eol-s)
      ;; If the terminal supports Mode 2026, wrap the whole frame in BSU/ESU
      ;; so the writes apply atomically — true per-frame double-buffering.
      (if @(:sync-output? h)
        (emit! (str sync-output-begin-s buf sync-output-end-s))
        (emit! (str buf)))
      nil)))

;; ---------------------------------------------------------------------------
;; Input loop (Esc → :ui.interrupt event, Ctrl-C → :ui.quit + close)
;; ---------------------------------------------------------------------------

(defn- send-ui-event!
  "Send a chart event into the runner's queue, targeted at the attached
   session. No-op if the queue / session-id haven't been attached yet."
  [{:keys [queue session-id] :as h} event-kw]
  (let [q   @queue
        sid @session-id]
    (when (and q sid)
      (try
        (sp/send! q {} {:target            sid
                        :source-session-id sid
                        :event             event-kw})
        (catch Throwable _ nil)))))

(defn- read-key
  "Block reading a single logical key from `rdr`. Returns:
     :up :down :left :right :pgup :pgdn :home :end
     :esc :enter :ctrl-c :backspace :tab :space :eof
     [:char ch]  for any other printable character."
  [^Reader rdr]
  (let [c (.read rdr)]
    (cond
      (= c -1) :eof
      (= c 3)  :ctrl-c
      (= c 9)  :tab
      (or (= c 8) (= c 127)) :backspace
      (or (= c 10) (= c 13)) :enter
      (= c 32) :space
      (= c 27)
      (if (.ready rdr)
        (let [b1 (.read rdr)]
          (if (and (= b1 91) (.ready rdr))
            (let [b2 (.read rdr)]
              (case (int b2)
                65 :up
                66 :down
                67 :right
                68 :left
                ;; PgUp/PgDn arrive as ESC[5~ / ESC[6~ — consume the trailing ~
                53 (do (when (.ready rdr) (.read rdr)) :pgup)
                54 (do (when (.ready rdr) (.read rdr)) :pgdn)
                72 :home
                70 :end
                :other))
            :esc))
        :esc)
      (and (>= c 32) (< c 127)) [:char (char c)]
      :else :other)))

(defn- complete-modal!
  "Deliver the modal's promise with `value` and clear the modal."
  [state value]
  (let [m (:modal @state)]
    (swap! state assoc :modal nil)
    (when (:promise m)
      (deliver (:promise m) value))))

(defn- handle-modal-key
  "Mutate the modal state in response to a key while a modal is active.
   When the modal is completed (Enter or Esc), delivers its promise."
  [state k]
  (let [m (:modal @state)
        kind (:kind m)]
    (case kind
      :text
      (case k
        :enter     (complete-modal! state (:buffer m ""))
        :esc       (complete-modal! state ::cancelled)
        :backspace (swap! state update-in [:modal :buffer]
                          (fn [b] (if (and b (seq b)) (subs b 0 (dec (count b))) (or b ""))))
        (cond
          (and (vector? k) (= :char (first k)))
          (swap! state update-in [:modal :buffer] (fnil str "") (str (second k)))
          (= :space k)
          (swap! state update-in [:modal :buffer] (fnil str "") " ")
          :else nil))

      :confirm
      (case k
        :enter     (let [b (:buffer m "")
                         v (cond
                             (clojure.string/blank? b) (boolean (:default m))
                             (re-matches #"(?i)y(es)?" b) true
                             (re-matches #"(?i)no?" b)   false
                             :else                       (boolean (:default m)))]
                     (complete-modal! state v))
        :esc       (complete-modal! state ::cancelled)
        :backspace (swap! state update-in [:modal :buffer]
                          (fn [b] (if (and b (seq b)) (subs b 0 (dec (count b))) (or b ""))))
        (cond
          (and (vector? k) (= :char (first k)))
          (swap! state update-in [:modal :buffer] (fnil str "") (str (second k)))
          :else nil))

      (:select :multi-select)
      (let [multi? (= :multi-select kind)
            n      (count (:options m))]
        (case k
          :up    (swap! state update-in [:modal :cursor]
                        (fn [i] (mod (dec (or i 0)) n)))
          :down  (swap! state update-in [:modal :cursor]
                        (fn [i] (mod (inc (or i 0)) n)))
          :left  (swap! state update-in [:modal :cursor]
                        (fn [i] (mod (dec (or i 0)) n)))
          :right (swap! state update-in [:modal :cursor]
                        (fn [i] (mod (inc (or i 0)) n)))
          :space (if multi?
                   (swap! state update-in [:modal :checked]
                          (fn [s] (let [i (or (:cursor m) 0)
                                        s (or s #{})]
                                    (if (s i) (disj s i) (conj s i)))))
                   nil)
          :enter (cond
                   multi?
                   (let [m' (:modal @state)]
                     (complete-modal! state
                                      (mapv (fn [i] (:value (nth (:options m') i)))
                                            (sort (or (:checked m') #{})))))
                   :else
                   (complete-modal! state
                                    (:value (nth (:options m) (or (:cursor m) 0)))))
          :esc   (complete-modal! state ::cancelled)
          nil))

      nil)))

(defn- detect-sync-output!
  "Issue a DECRQM query for Mode 2026 and try to read the response. Returns
   true if the terminal indicates support (response values 1-4), false on
   timeout or an indication of no support. Must run AFTER raw mode is entered
   AND BEFORE the main input loop starts consuming bytes from the reader."
  [^Reader rdr]
  (try
    (emit! sync-output-query-s)
    ;; Drain up to ~120 ms looking for `\e[?2026;<n>$y`. Some terminals never
    ;; reply at all — that's the timeout path, return false.
    (let [deadline (+ (System/currentTimeMillis) 120)
          sb       (StringBuilder.)]
      (loop []
        (cond
          (or (>= (System/currentTimeMillis) deadline) (>= (.length sb) 32))
          nil

          (.ready rdr)
          (do (.append sb (char (.read rdr))) (recur))

          :else
          (do (Thread/sleep 5) (recur))))
      (let [s (.toString sb)]
        (if-let [[_ n] (re-find #"\[\?2026;(\d+)\$y" s)]
          (boolean (#{"1" "2" "3" "4"} n))
          false)))
    (catch Throwable _ false)))

(defn- input-loop!
  [{:keys [terminal raw-mode? state sync-output?] :as h}]
  (try
    (.enterRawMode ^Terminal terminal)
    (reset! raw-mode? true)
    (let [rdr ^Reader (.reader ^Terminal terminal)]
      (reset! sync-output? (detect-sync-output! rdr))
      ;; A render after detection so the first 2026-wrapped frame appears.
      (render-frame! h)
      (loop []
        (let [k (read-key rdr)]
          (cond
            (= :eof k)
            :stop

            (= :ctrl-c k)
            (do (send-ui-event! h :ui.quit) :stop)

            ;; Modal active: forward keys to the modal handler. Esc cancels the
            ;; modal (does NOT also send :ui.interrupt — chart-level Esc is only
            ;; available when no modal is open).
            (:modal @state)
            (do (handle-modal-key state k)
                (render-frame! h)
                (recur))

            ;; No modal: chart-level keybindings.
            :else
            (case k
              :esc  (do (send-ui-event! h :ui.interrupt) (recur))
              :pgup (do (swap! state update :scroll-offset (fnil + 0) 10)
                        (render-frame! h)
                        (recur))
              :pgdn (do (swap! state update :scroll-offset
                               #(max 0 (- (or % 0) 10)))
                        (render-frame! h)
                        (recur))
              :home (do (swap! state assoc :scroll-offset
                               (max 0 (- (count (:scrollback @state))
                                         (max 5 (- (:term-h @state 24) 4)))))
                        (render-frame! h)
                        (recur))
              :end  (do (swap! state assoc :scroll-offset 0)
                        (render-frame! h)
                        (recur))
              (recur))))))
    (catch InterruptedException _ nil)
    (catch Throwable _ nil)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn start!
  "Start the TUI. Returns a handle that can be passed to `event!`, `renderer`,
   `attach-session!`, and `stop!`. If the current terminal is non-interactive,
   returns a disabled handle (every method becomes a no-op).

   `opts`:
    * `:chart-sym`     — display label for the header
    * `:session-short` — short session id for the header"
  [{:keys [chart-sym session-short]}]
  (if-not (interactive-terminal?)
    (->TuiHandle false (atom {}) (Object.) nil (atom false) nil (atom nil) (atom nil)
                 (str chart-sym) (str session-short) (atom false) (atom false))
    (let [terminal (-> (TerminalBuilder/builder)
                       (.system true)
                       (.build))
          state    (atom {:config         []
                          :scrollback     []
                          :scroll-offset  0
                          :modal          nil
                          :term-h         (.getHeight terminal)
                          :term-w         (.getWidth terminal)})
          h        (->TuiHandle true state (Object.) terminal (atom false) nil
                                (atom nil) (atom nil)
                                (str chart-sym) (str session-short)
                                (atom false) (atom false))
          t        (Thread. ^Runnable (fn [] (input-loop! h)) "tui-input")
          _        (.setDaemon t true)
          h        (assoc h :input-thread t)]
      ;; Enter alt screen buffer; user's prior terminal contents are preserved
      ;; and restored on stop!. Clear once and hide cursor (cursor stays hidden
      ;; unless a text/confirm modal is open).
      (emit! (str alt-screen-on-s clear-screen-s hide-cursor-s))
      (render-frame! h)
      (.start t)
      h)))

(defn attach-session!
  "Tell the TUI which session-id and event-queue to target when sending UI
   events (Esc → :ui.interrupt, Ctrl-C → :ui.quit). Must be called before the
   input thread can post events; called by the CLI between env construction
   and chart start."
  [h session-id queue]
  (when (:enabled? h)
    (reset! (:session-id h) session-id)
    (reset! (:queue h) queue))
  h)

(defn event!
  "Transcript-fn subscriber. Folds the event into the scrollback and updates
   the status line when the event carries a new chart configuration."
  [h ev]
  (when (:enabled? h)
    (let [line (format-event ev)
          cfg  (get-in ev [:data :config-after])
          start-cfg (when (= (:event ev) :runner/start-config)
                      (get-in ev [:data :config]))]
      (swap! (:state h)
             (fn [s]
               (cond-> s
                 line      (update :scrollback
                                   (fn [v]
                                     (let [last-line (peek v)
                                           ;; Collapse consecutive duplicates
                                           ;; into a "(xN)" suffix to keep noise
                                           ;; from drowning the screen.
                                           v' (cond
                                                (= line last-line)
                                                (conj (pop v) (str line " (x2)"))

                                                (and last-line
                                                     (re-find #" \(x(\d+)\)$" (str last-line))
                                                     (= (clojure.string/replace last-line
                                                                                #" \(x\d+\)$" "")
                                                        line))
                                                (conj (pop v)
                                                      (clojure.string/replace
                                                       last-line
                                                       #" \(x(\d+)\)$"
                                                       (fn [[_ n]]
                                                         (str " (x" (inc (Long/parseLong n)) ")"))))

                                                :else
                                                (conj v line))
                                           n  (count v')]
                                       (if (> n 2000)
                                         (subvec v' (- n 2000))
                                         v'))))
                 cfg       (assoc :config cfg)
                 start-cfg (assoc :config start-cfg))))
      (render-frame! h)))
  nil)

(defn stop!
  "Restore the terminal. Idempotent."
  [h]
  (when (:enabled? h)
    (try
      (when-let [^Thread t (:input-thread h)] (.interrupt t))
      (catch Throwable _ nil))
    (try
      (when (and (:terminal h) @(:raw-mode? h))
        (when-let [^Terminal term (:terminal h)]
          (.close term)))
      (catch Throwable _ nil))
    ;; Leave alt screen (restores prior terminal contents), reset attributes,
    ;; show the cursor. Newline at the end keeps the next shell prompt on its
    ;; own line.
    (emit! (str reset-attrs-s show-cursor-s alt-screen-off-s)))
  h)

;; ---------------------------------------------------------------------------
;; HumanRenderer impl — pops modals into the bottom region
;; ---------------------------------------------------------------------------

(defn- ask!
  "Set the modal state, redraw, and block until the input thread delivers
   the modal's promise. Throws on cancel so the human-input worker can
   propagate it as :on-cancel-event."
  [{:keys [state] :as h} modal-base]
  (let [p     (promise)
        modal (assoc modal-base :promise p)]
    (swap! state assoc :modal modal)
    (render-frame! h)
    (let [v @p]
      ;; Render once more so the modal area is cleared.
      (render-frame! h)
      (if (= v ::cancelled)
        (throw (ex-info "User cancelled the prompt" {:reason :cancelled}))
        v))))

(defrecord ^:private TuiRenderer [handle]
  hi/HumanRenderer
  (prompt-text [_ {:keys [prompt]}]
    (if (:enabled? handle)
      (ask! handle {:kind :text :prompt (or prompt "?") :buffer ""})
      (hi/prompt-text (hi/stdin-renderer) {:prompt prompt})))
  (prompt-select [_ {:keys [prompt options] :as opts}]
    (if (:enabled? handle)
      (ask! handle {:kind :select :prompt (or prompt "Select:")
                    :options (vec options) :cursor 0})
      (hi/prompt-select (hi/stdin-renderer) opts)))
  (prompt-multi [_ {:keys [prompt options] :as opts}]
    (if (:enabled? handle)
      (ask! handle {:kind :multi-select :prompt (or prompt "Select any:")
                    :options (vec options) :cursor 0 :checked #{}})
      (hi/prompt-multi (hi/stdin-renderer) opts)))
  (prompt-confirm [_ {:keys [prompt default] :as opts}]
    (if (:enabled? handle)
      (ask! handle {:kind :confirm :prompt (or prompt "Confirm?")
                    :buffer "" :default (boolean default)})
      (hi/prompt-confirm (hi/stdin-renderer) opts)))
  (start-progress [_ opts] (atom {:pct 0 :prompt (:prompt opts)}))
  (update-progress [_ handle' pct label]
    (swap! handle' assoc :pct pct :label label))
  (end-progress [_ _] nil)
  (custom-render [_ f env data] (f env data)))

(defn ->renderer
  "Build a `HumanRenderer` that pops modals into the bottom region of this TUI.
   When the TUI is disabled (non-TTY), delegates to the stdin fallback."
  [h]
  (->TuiRenderer h))
