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
   [clojure.java.io :as io]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.protocols :as sp]
   [escapement.config :as ecfg]
   [escapement.debug.controller :as dbg]
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
    :runner/event-processed  (str "[chart] " (:event-name data)
                                  "  " (pr-str (:config-before data))
                                  " → " (pr-str (:config-after data)))
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
    :debug/awaiting-quit     (str "[debug] " (:msg data "Press Ctrl-C to quit."))
    :debug/awaiting-step     (str "[debug] PAUSED on event=" (:event-name data)
                                  (when (:external? data) " (external)"))
    ;; default
    (str "[" (name (or event :unknown)) "] "
         (truncate (pr-str (or data {})) 200))))

(declare chart-from-env stop!)

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
            debug?         ;; bool — debug overlay enabled?
            debug-controller ;; atom (escapement.debug.controller) or nil
            debug-config   ;; map (.escapement.edn merged) or nil
            env            ;; atom holding the chart's env (for inspector reads)
            stopped?       ;; atom bool — guards stop! against double-invocation
            ])

;; ---------------------------------------------------------------------------
;; Debug overlay — inspector views
;; ---------------------------------------------------------------------------

(defn- status-indicator
  "Returns the one-char prefix for the status line: H if a modal (human-input)
   is up, P if the controller is paused, otherwise R."
  [h s]
  (cond
    (:modal s)                              "H"
    (and (:debug-controller h)
         (dbg/paused? (:debug-controller h))) "P"
    :else                                    "R"))

(defn- list-artifacts
  "Returns a vector of artifact filenames in `session-dir`'s artifacts/ that
   match `<invokeid>.*`. Returns [] if the directory doesn't exist."
  [session-dir invokeid]
  (try
    (let [d (io/file (str session-dir "/artifacts"))]
      (if (and (.exists d) (.isDirectory d))
        (let [prefix (str invokeid)]
          (vec (sort (filter #(or (= % prefix)
                                  (str/starts-with? % (str prefix ".")))
                             (.list d)))))
        []))
    (catch Throwable _ [])))

(defn- session-dir-from-env [env]
  (get env :escapement/session-dir))

(defn- pretty
  "Pretty-print `x` to a string."
  [x]
  (try (with-out-str (pp/pprint x))
       (catch Throwable _ (pr-str x))))

(defn- pager-lines [s] (or (get-in s [:debug-overlay :pager :lines]) []))

(defn- open-pager!
  "Push a pager (title + lines) onto the overlay state."
  [state title text]
  (let [lines (vec (str/split-lines (str text)))]
    (swap! state assoc-in [:debug-overlay :pager]
           {:title title :lines lines :offset 0})))

(defn- close-pager! [state]
  (swap! state assoc-in [:debug-overlay :pager] nil))

(defn- fmt-hms
  "Formats a unix-ms timestamp as `HH:mm:ss.mmm` in the local timezone.
   Returns `\"--:--:--.---\"` when ts is nil."
  [ts]
  (if ts
    (let [fmt (java.text.SimpleDateFormat. "HH:mm:ss.SSS")]
      (.format fmt (java.util.Date. ^long ts)))
    "--:--:--.---"))

(defn- current-event-rows
  "Most-recent-first vector of {:ts :event-name :config-before :config-after :ev}."
  [events]
  (vec (reverse
        (mapv (fn [ev]
                {:ts            (:ts ev)
                 :event-name    (get-in ev [:data :event-name] (:event ev))
                 :config-before (get-in ev [:data :config-before])
                 :config-after  (get-in ev [:data :config-after])
                 :ev            ev})
              events))))

(defn- render-pager-lines
  "Render lines for the pager into `buf` between rows `r0` and `r1`."
  [^StringBuilder buf {:keys [title lines offset]} r0 r1 term-w]
  (.append buf (move-to-s r0 1))
  (.append buf (truncate (str " ── " title " ── (PgUp/PgDn, Esc=close)") term-w))
  (.append buf clear-eol-s)
  (let [room  (max 1 (- r1 r0))
        start (min (max 0 (or offset 0)) (max 0 (- (count lines) 1)))
        slice (subvec lines start (min (count lines) (+ start room)))]
    (doseq [[i ln] (map-indexed vector slice)]
      (.append buf (move-to-s (+ r0 1 i) 1))
      (.append buf (truncate ln term-w))
      (.append buf clear-eol-s))
    (doseq [row (range (+ r0 1 (count slice)) (inc r1))]
      (.append buf (move-to-s row 1))
      (.append buf clear-eol-s))))

(defn- render-overlay!
  "Renders the inspector overlay into the scrollback region. Row range
   `[r0..r1]` is inclusive."
  [^StringBuilder buf h s r0 r1 term-w]
  (let [ov     (:debug-overlay s)
        view   (:view ov)
        cursor (:cursor ov 0)
        env    (some-> (:env h) deref)
        events (:events ov)
        title  (str " ── inspector "
                    (case view :invocations "[1]Invocations [2]Chart [3]Status"
                          :chart        "[1]Invocations [2]Chart [3]Status"
                          :status       "[1]Invocations [2]Chart [3]Status"
                          "")
                    "  (j/k g/G Enter=drill o=open Esc/h=back  s/c/p/P=ctrl v=viz) ── ")]
    (.append buf (move-to-s r0 1))
    (.append buf (truncate title term-w))
    (.append buf clear-eol-s)
    (if-let [pager (:pager ov)]
      (render-pager-lines buf pager (inc r0) r1 term-w)
      ;; Each view returns {:rows [strings] :hl-offset N} where hl-offset is
      ;; the index into :rows corresponding to cursor=0. That keeps decorative
      ;; header lines from throwing off the selection highlight.
      (let [body-r0 (inc r0)
            sdir (session-dir-from-env env)
            fmt-time (fn [ms]
                       (when ms
                         (let [age (- (System/currentTimeMillis) ms)
                               s   (quot age 1000)]
                           (cond (< s 60) (str s "s")
                                 (< s 3600) (str (quot s 60) "m" (mod s 60) "s")
                                 :else (str (quot s 3600) "h" (mod (quot s 60) 60) "m")))))
            {:keys [rows hl-offset selectable?]}
            (case view
              :invocations
              (cond
                ;; Drilldown: focus on one invocation's artifacts.
                (:focus ov)
                (let [{:keys [invokeid]} (:focus ov)
                      arts (list-artifacts sdir invokeid)
                      header [(str " " invokeid "  ── (Esc/h to go back, Enter/o to view) ──")]]
                  (if (seq arts)
                    {:rows (into header
                                 (mapv (fn [name]
                                         (let [f (io/file (str sdir "/artifacts/" name))]
                                           (format "  %-30s  %sB"
                                                   (str/join (take 30 name))
                                                   (.length f))))
                                       arts))
                     :hl-offset (count header)
                     :selectable? true}
                    {:rows (conj header "  (no artifacts captured for this invocation)")
                     :hl-offset nil :selectable? false}))

                ;; List view: invocation history (newest first).
                :else
                (let [hist (:invocations ov)]
                  (if (seq hist)
                    {:rows (mapv (fn [{:keys [invokeid started-ms ended-ms reason]}]
                                   (let [arts-n (count (list-artifacts sdir invokeid))
                                         status (cond
                                                  (nil? ended-ms) "live "
                                                  (= reason :stopped) "done "
                                                  (= reason :interrupted) "stop "
                                                  reason (str (name reason) " ")
                                                  :else "done ")
                                         age    (or (fmt-time started-ms) "?")]
                                     (format " %-5s  %s ago  %s  artifacts=%d"
                                             status age invokeid arts-n)))
                                 hist)
                     :hl-offset 0
                     :selectable? true}
                    {:rows [" (no LLM invocations yet)"]
                     :hl-offset nil :selectable? false})))

              :chart
              (let [active (or (:config s) (get-in (last events) [:data :config-after]))
                    erows  (current-event-rows events)
                    header [(str " active config: " (pr-str active))
                            " ── recent events (newest first) ── time         event              before → after"]]
                {:rows (into header
                             (mapv (fn [{:keys [ts event-name config-before config-after]}]
                                     (format "  %s  %-22s  %s  →  %s"
                                             (fmt-hms ts)
                                             (str event-name)
                                             (pr-str config-before)
                                             (pr-str config-after)))
                                   erows))
                 :hl-offset (count header)
                 :selectable? (seq erows)})

              :status
              (let [c (:debug-controller h)
                    cs (when c @c)]
                {:rows [(str " mode:           " (or (:mode cs) "n/a"))
                        (str " step-budget:    " (or (:step-budget cs) 0))
                        (str " pause-on-ext?:  " (boolean (:pause-on-next-external? cs)))
                        (str " buffered events: " (count events))
                        (str " session-dir:    " (session-dir-from-env env))]
                 :hl-offset nil :selectable? false})

              {:rows [" (unknown view)"] :hl-offset nil :selectable? false})
            room (max 1 (- r1 body-r0 -1))
            hl-row (when (and selectable? hl-offset)
                     (+ hl-offset cursor))]
        (doseq [[i ln] (map-indexed vector (take room rows))]
          (let [row (+ body-r0 i)
                hl? (and hl-row (= i hl-row))]
            (.append buf (move-to-s row 1))
            (when hl? (.append buf reverse-on-s))
            (.append buf (truncate ln term-w))
            (when hl? (.append buf reset-attrs-s))
            (.append buf clear-eol-s)))
        (doseq [row (range (+ body-r0 (min room (count rows))) (inc r1))]
          (.append buf (move-to-s row 1))
          (.append buf clear-eol-s))))))

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
          ;; Restore an auto-suspended overlay once the modal has cleared.
          _      (swap! state
                        (fn [s]
                          (let [ov (:debug-overlay s)]
                            (cond-> (assoc s :term-h term-h :term-w term-w)
                              (and (nil? (:modal s)) (:suspended? ov))
                              (assoc :debug-overlay
                                     (assoc ov :open? true :suspended? false))))))
          s      @state
          ind    (when (:debug? h) (str "[" (status-indicator h s) "] "))
          paused?  (and (:debug? h) (:debug-controller h)
                        (dbg/paused? (:debug-controller h))
                        (nil? (:modal s))
                        (not (get-in s [:debug-overlay :open?])))
          header (str " escapement · " (:chart-sym h) " · " (:session-short h))
          status (if paused?
                   (str " " ind "PAUSED — s=step c=continue ?=inspector")
                   (str " " (or ind "") "states: " (pr-str (:config s []))))
          overlay-open? (and (:debug? h) (get-in s [:debug-overlay :open?])
                             (nil? (:modal s)))
          lines  (when-not overlay-open? (visible-scrollback s term-h))
          modal  (:modal s)
          help   (if (:debug? h)
                   " Esc=interrupt  Ctrl-C=quit  ?=inspector  s/c/p/P=ctrl  v=viz"
                   " Esc=interrupt   Ctrl-C=quit   PgUp/PgDn=scroll")
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
            last-row  (- term-h 2)]
        (if overlay-open?
          (render-overlay! buf h s first-row last-row term-w)
          (let [written (count lines)]
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
              (.append buf clear-eol-s)))))
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

(defn- view-row-count
  "How many selectable rows the current overlay view has."
  [h s]
  (let [ov  (:debug-overlay s)
        env (some-> (:env h) deref)]
    (cond
      ;; Drilled into a single invocation — selectable rows are its artifacts.
      (and (= :invocations (:view ov)) (:focus ov))
      (count (list-artifacts (session-dir-from-env env)
                             (get-in ov [:focus :invokeid])))

      :else
      (case (:view ov)
        :invocations (count (:invocations ov))
        :chart       (count (current-event-rows (:events ov)))
        0))))

(defn- append-scrollback!
  "Append a single status line to the TUI scrollback. Trimmed at 2000 lines."
  [state line]
  (swap! state update :scrollback
         (fn [v]
           (let [v' (conj (or v []) (str line))
                 n  (count v')]
             (if (> n 2000) (subvec v' (- n 2000)) v')))))

(defn- do-visualize!
  "Render the current chart + active config to SVG via `escapement.debug.d2`
   and attempt to open it. Always writes a status line to the scrollback so
   the user sees that something happened (success or failure)."
  [h]
  (let [state (:state h)
        s     @state
        env   (some-> (:env h) deref)
        chart (chart-from-env env)
        sdir  (session-dir-from-env env)
        active (:config s)]
    (cond
      (nil? chart)
      (append-scrollback! state "[viz] no chart attached to TUI handle (debug bug)")

      (nil? sdir)
      (append-scrollback! state "[viz] no session-dir on env — cannot write chart.svg")

      :else
      (let [f (try (requiring-resolve 'escapement.debug.d2/render-and-open!)
                   (catch Throwable t
                     (append-scrollback! state (str "[viz] cannot load d2 ns: " (.getMessage t)))
                     nil))]
        (when f
          (try
            (let [r (f chart active sdir (:debug-config h))]
              (append-scrollback!
               state
               (cond
                 (and (:svg-path r) (:error r))
                 (str "[viz] svg written but viewer failed: " (:error r)
                      " — file: " (:svg-path r))

                 (:error r)
                 (str "[viz] d2 failed: " (:error r)
                      " (d2 source still at " sdir "/chart.d2)")

                 (:internal? r)
                 (str "[viz] svg written: " (:svg-path r)
                      " (viewer is :internal — open the .svg yourself)")

                 (:svg-path r)
                 (str "[viz] svg written + viewer launched ("
                      (:viewer-cmd r) "): " (:svg-path r))

                 :else
                 (str "[viz] result: " (pr-str r)))))
            (catch Throwable t
              (append-scrollback! state (str "[viz] threw: " (.getMessage t))))))))))

(defn- open-artifact-file!
  "Open `path` using `(:viewers cfg)`. Falls back to the internal pager when
   the viewer is `:internal`, the viewer command is missing, or the external
   launch errors. `display-name` is used as the pager title."
  [state cfg path display-name]
  (let [path-abs (.getAbsolutePath (io/file path))
        viewer   (ecfg/viewer-for cfg path-abs)]
    (cond
      (= :internal viewer)
      (try (open-pager! state display-name (slurp path-abs))
           (catch Throwable t
             (open-pager! state display-name (str "Failed to read: " (.getMessage t)))))

      (string? viewer)
      (try
        (let [cmd (ecfg/expand-command viewer path-abs)]
          (.exec (Runtime/getRuntime) ^"[Ljava.lang.String;"
                 (into-array String ["sh" "-c" cmd])))
        (catch Throwable t
          (open-pager! state display-name
                       (str "Could not launch viewer: " (.getMessage t)
                            "\n\nFalling back to internal view:\n\n"
                            (try (slurp path-abs) (catch Throwable _ "")))))))))

(defn- focus-invocation!
  "Drill into the invocation at `cursor` in the history list (newest first).
   Sets `:focus {:invokeid ...}` and resets cursor for the artifact list."
  [state hist cursor]
  (when-let [row (nth hist cursor nil)]
    (swap! state update :debug-overlay
           merge {:focus  {:invokeid (:invokeid row)}
                  :cursor 0})))

(defn- open-focused-artifact!
  "When drilled into an invocation, open the artifact at `cursor`."
  [h state]
  (let [s    @state
        ov   (:debug-overlay s)
        env  (some-> (:env h) deref)
        sdir (session-dir-from-env env)
        invokeid (get-in ov [:focus :invokeid])
        arts (list-artifacts sdir invokeid)
        idx  (:cursor ov 0)]
    (when-let [name (nth arts idx nil)]
      (open-artifact-file! state (:debug-config h)
                           (str sdir "/artifacts/" name)
                           name))))

(defn- open-event-detail!
  "Drill-in for a chart event row: pretty-print the event into the pager."
  [h state cursor]
  (let [s    @state
        rows (current-event-rows (get-in s [:debug-overlay :events]))]
    (when-let [{:keys [ev event-name]} (nth rows cursor nil)]
      (open-pager! state (str "event " event-name) (pretty ev)))))

(defn- handle-debug-key!
  "Dispatch a key while the debug overlay is open. Pager keys take precedence
   when the pager is up."
  [h k]
  (let [state (:state h)
        s     @state
        ov    (:debug-overlay s)
        pager (:pager ov)]
    (if pager
      (case k
        :esc       (close-pager! state)
        :pgdn      (swap! state update-in [:debug-overlay :pager :offset] (fnil + 0) 10)
        :space     (swap! state update-in [:debug-overlay :pager :offset] (fnil + 0) 10)
        :pgup      (swap! state update-in [:debug-overlay :pager :offset]
                          #(max 0 (- (or % 0) 10)))
        :down      (swap! state update-in [:debug-overlay :pager :offset] (fnil inc 0))
        :up        (swap! state update-in [:debug-overlay :pager :offset]
                          #(max 0 (dec (or % 0))))
        (cond
          (= k [:char \b]) (swap! state update-in [:debug-overlay :pager :offset]
                                  #(max 0 (- (or % 0) 10)))
          (= k [:char \j]) (swap! state update-in [:debug-overlay :pager :offset] (fnil inc 0))
          (= k [:char \k]) (swap! state update-in [:debug-overlay :pager :offset]
                                  #(max 0 (dec (or % 0))))
          (= k [:char \g]) (swap! state assoc-in [:debug-overlay :pager :offset] 0)
          (= k [:char \G]) (let [n (count (get-in @state [:debug-overlay :pager :lines]))]
                             (swap! state assoc-in [:debug-overlay :pager :offset]
                                    (max 0 (dec n))))
          :else nil))
      (cond
        (= k :esc)
        (cond
          ;; In a drilldown, Esc pops back to the invocation list.
          (and (= :invocations (:view ov)) (:focus ov))
          (swap! state update :debug-overlay merge {:focus nil :cursor 0})
          :else
          (swap! state assoc-in [:debug-overlay :open?] false))

        (= k [:char \?])
        (swap! state update-in [:debug-overlay :open?] not)

        (= k [:char \1]) (swap! state update :debug-overlay
                                merge {:view :invocations :cursor 0 :focus nil})
        (= k [:char \2]) (swap! state update :debug-overlay
                                merge {:view :chart :cursor 0 :focus nil})
        (= k [:char \3]) (swap! state update :debug-overlay
                                merge {:view :status :cursor 0 :focus nil})

        ;; In invocations drilldown, `h` or Backspace pops back to the list.
        (and (= :invocations (:view ov)) (:focus ov)
             (or (= k [:char \h]) (= k :backspace)))
        (swap! state update :debug-overlay merge {:focus nil :cursor 0})

        (or (= k :down) (= k [:char \j]))
        (let [n (max 1 (view-row-count h s))]
          (swap! state update-in [:debug-overlay :cursor]
                 (fn [i] (mod (inc (or i 0)) n))))

        (or (= k :up) (= k [:char \k]))
        (let [n (max 1 (view-row-count h s))]
          (swap! state update-in [:debug-overlay :cursor]
                 (fn [i] (mod (dec (or i 0)) n))))

        (= k [:char \g]) (swap! state assoc-in [:debug-overlay :cursor] 0)
        (= k [:char \G]) (let [n (max 1 (view-row-count h s))]
                           (swap! state assoc-in [:debug-overlay :cursor] (dec n)))

        (= k :enter)
        (case (:view ov)
          :invocations
          (if (:focus ov)
            (open-focused-artifact! h state)
            (focus-invocation! state (:invocations ov) (:cursor ov 0)))
          :chart       (open-event-detail! h state (:cursor ov 0))
          nil)

        (= k [:char \o])
        (when (and (= :invocations (:view ov)) (:focus ov))
          (open-focused-artifact! h state))

        ;; Controller keys also work while overlay is open
        (and (:debug-controller h) (= k [:char \s]))
        (dbg/step! (:debug-controller h))
        (and (:debug-controller h) (= k [:char \c]))
        (dbg/continue! (:debug-controller h))
        (and (:debug-controller h) (= k [:char \p]))
        (dbg/pause! (:debug-controller h))
        (and (:debug-controller h) (= k [:char \P]))
        (dbg/arm-pause-on-next-external! (:debug-controller h))

        (= k [:char \v])
        (do-visualize! h)

        :else nil))))

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

            ;; Debug overlay key dispatch — only when overlay is open AND
            ;; no modal is active. Modal always wins.
            (and (:debug? h)
                 (get-in @state [:debug-overlay :open?])
                 (not (:modal @state)))
            (do (handle-debug-key! h k) (render-frame! h) (recur))

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
              (cond
                ;; Debug-mode top-level keys (always active when no modal).
                (and (:debug? h) (= k [:char \?]))
                (do (swap! state update-in [:debug-overlay :open?] not)
                    (render-frame! h) (recur))

                (and (:debug? h) (:debug-controller h)
                     (= k [:char \s]))
                (do (dbg/step! (:debug-controller h)) (render-frame! h) (recur))

                (and (:debug? h) (:debug-controller h)
                     (= k [:char \c]))
                (do (dbg/continue! (:debug-controller h)) (render-frame! h) (recur))

                (and (:debug? h) (:debug-controller h)
                     (= k [:char \p]))
                (do (dbg/pause! (:debug-controller h)) (render-frame! h) (recur))

                (and (:debug? h) (:debug-controller h)
                     (= k [:char \P]))
                (do (dbg/arm-pause-on-next-external! (:debug-controller h))
                    (render-frame! h) (recur))

                (and (:debug? h) (= k [:char \v]))
                (do (do-visualize! h) (render-frame! h) (recur))

                :else (recur)))))))
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
  [{:keys [chart-sym session-short debug? debug-controller debug-config]}]
  (if-not (interactive-terminal?)
    (->TuiHandle false (atom {}) (Object.) nil (atom false) nil (atom nil) (atom nil)
                 (str chart-sym) (str session-short) (atom false) (atom false)
                 (boolean debug?) debug-controller debug-config (atom nil) (atom false))
    (let [terminal (-> (TerminalBuilder/builder)
                       (.system true)
                       (.build))
          state    (atom {:config         []
                          :scrollback     []
                          :scroll-offset  0
                          :modal          nil
                          :term-h         (.getHeight terminal)
                          :term-w         (.getWidth terminal)
                          :debug-overlay  {:open?       false
                                           :suspended?  false
                                           :view        :invocations
                                           :cursor      0
                                           :pane        :list
                                           :events      []
                                           :invocations []   ;; history (newest first)
                                           :focus       nil  ;; nil or {:invokeid ... :cursor 0}
                                           :pager       nil}})
          h        (->TuiHandle true state (Object.) terminal (atom false) nil
                                (atom nil) (atom nil)
                                (str chart-sym) (str session-short)
                                (atom false) (atom false)
                                (boolean debug?) debug-controller debug-config
                                (atom nil) (atom false))
          t        (Thread. ^Runnable (fn [] (input-loop! h)) "tui-input")
          _        (.setDaemon t true)
          h        (assoc h :input-thread t)]
      ;; Enter alt screen buffer; user's prior terminal contents are preserved
      ;; and restored on stop!. Clear once and hide cursor (cursor stays hidden
      ;; unless a text/confirm modal is open).
      (emit! (str alt-screen-on-s clear-screen-s hide-cursor-s))
      (render-frame! h)
      (.start t)
      ;; Always-on safety net: if the JVM exits via SIGINT/SIGTERM/etc.
      ;; before stop! ran, the shutdown hook still leaves the alt screen
      ;; and resets attributes so the user's terminal isn't left in raw
      ;; mode. stop! is idempotent so it's safe whether or not it already
      ;; ran from the normal exit path.
      (try
        (.addShutdownHook (Runtime/getRuntime)
                          (Thread. ^Runnable (fn [] (try (stop! h)
                                                         (catch Throwable _ nil)))
                                   "tui-shutdown"))
        (catch Throwable _ nil))
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

(defn attach-env!
  "Stash the chart's `env` on the handle so the inspector can read live state
   (worker registry, working memory, session-dir). Safe to call repeatedly.
   Optional `chart` is the statechart value; the visualize action needs it
   because the env only carries the registry, not the chart by reference."
  ([h env] (attach-env! h env nil))
  ([h env chart]
   (when (:enabled? h)
     (reset! (:env h) (cond-> env
                        chart (vary-meta assoc ::chart chart))))
   h))

(defn- chart-from-env
  "Returns the chart value stashed by `attach-env!` (or nil if not stashed)."
  [env]
  (some-> env meta ::chart))

(defn- debug-event-of-interest?
  "True for events the inspector should keep in its ring buffer."
  [ev]
  (let [e (:event ev)]
    (or (= :runner/event-processed e)
        (and (keyword? e) (= "debug" (namespace e))))))

(defn- update-invocation-history
  "Folds an `:llm/start` or `:llm/worker-exit` transcript event into the
   `:invocations` history list. Newest first; capped at 200 entries.

   Each entry: `{:invokeid :session-id :started-ms :ended-ms :reason}`."
  [history ev]
  (let [e (:event ev)]
    (cond
      (= :llm/start e)
      (let [d         (:data ev)
            invokeid  (some-> (:invokeid d) str)
            ts        (or (:ts ev) (System/currentTimeMillis))
            entry     {:invokeid   invokeid
                       :session-id (:session-id d)
                       :started-ms ts
                       :ended-ms   nil
                       :reason     nil}]
        (vec (take 200 (cons entry (or history [])))))

      (= :llm/worker-exit e)
      (let [d        (:data ev)
            invokeid (some-> (:invokeid d) str)
            ts       (or (:ts ev) (System/currentTimeMillis))
            reason   (:reason d)]
        (mapv (fn [row]
                (if (and (= invokeid (:invokeid row))
                         (nil? (:ended-ms row)))
                  (assoc row :ended-ms ts :reason reason)
                  row))
              (or history [])))

      :else history)))

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
                 start-cfg (assoc :config start-cfg)
                 (debug-event-of-interest? ev)
                 (update-in [:debug-overlay :events]
                            (fn [v]
                              (let [v' (conj (or v []) ev)
                                    n  (count v')]
                                (if (> n 1000) (subvec v' (- n 1000)) v'))))
                 (#{:llm/start :llm/worker-exit} (:event ev))
                 (update-in [:debug-overlay :invocations]
                            update-invocation-history ev)
                 ;; Auto-suspend overlay when a human-input modal opens
                 (and (= :human-input/start (:event ev))
                      (get-in s [:debug-overlay :open?]))
                 (assoc-in [:debug-overlay :suspended?] true)
                 (and (= :human-input/start (:event ev))
                      (get-in s [:debug-overlay :open?]))
                 (assoc-in [:debug-overlay :open?] false))))
      (render-frame! h)))
  nil)

(defn await-quit!
  "Blocks until the user terminates the TUI (Ctrl-C, EOF, or the input
   thread otherwise dies). Used in `--debug` after the chart has reached
   final-config so the user can keep inspecting artifacts/state instead of
   the process exiting immediately. Posts a banner to the scrollback so the
   user knows what's happening."
  [h]
  (when (:enabled? h)
    (event! h {:event :debug/awaiting-quit
               :data  {:msg "Chart finished. Inspector still live — press Ctrl-C to quit."}})
    (when-let [^Thread t (:input-thread h)]
      (try (.join t) (catch InterruptedException _ nil)))))

(defn human-input-active?
  "True when a human-input modal is currently up. Used by the runner's debug
   pause-gate so it yields the screen while the chart is waiting on user
   input — debugger keys must not steal a Y/N answer."
  [h]
  (boolean (and h (:enabled? h) (:modal @(:state h)))))

(defn stop!
  "Restore the terminal. Idempotent — safe to call repeatedly and from
   both the normal exit path and a JVM shutdown hook (Ctrl-C, SIGTERM)."
  [h]
  (when (and (:enabled? h)
             (compare-and-set! (:stopped? h) false true))
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
