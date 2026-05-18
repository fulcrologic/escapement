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
;; Under tmux-256color, terminfo `cnorm` is `\e[34h\e[?25h` — the
;; `\e[34h` ("Normal Cursor Visibility Mode") is what the multiplexer's
;; per-pane state watches for; `\e[?25h` alone is a no-op. The extra
;; code is harmless on terminals that don't recognise it, so we always
;; send the union, exactly like terminfo would.
(def ^:private show-cursor-s  (str (esc "34h") (esc "?25h")))
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

(defn- short-invokeid
  "Strip a namespace-style prefix from `id` and cap at ~10 chars for the
   `[<invokeid>]` source tag."
  [id]
  (when id
    (let [s (str id)
          s (or (last (str/split s #"[/.]")) s)]
      (truncate s 10))))

(defn- ts->hms
  "Format a unix-ms timestamp as HH:MM:SS in the local timezone."
  [ts]
  (let [ts (or ts (System/currentTimeMillis))
        fmt (java.text.SimpleDateFormat. "HH:mm:ss")]
    (.format fmt (java.util.Date. ^long ts))))

;; ANSI palette for invokeid color allocation. Round-robin in this order.
;; SGR codes — start with `(esc "...m")` and reset with reset-attrs-s.
(def ^:private invokeid-palette
  ["36" ;; cyan
   "35" ;; magenta
   "33" ;; yellow
   "32" ;; green
   "34" ;; blue
   "91" ;; bright red
   "96" ;; bright cyan
   "95" ;; bright magenta
   "93" ;; bright yellow
   "92" ;; bright green
   ])

(def ^:private chart-color  "90") ;; bright black / dim grey
(def ^:private human-color  "97") ;; bright white
(def ^:private error-color  "31") ;; red
(def ^:private debug-color  "90") ;; dim

(defn- entries-for
  "Return a vector of scrollback entries (one per logical line) for transcript
   event `ev`. Each entry is `{:source <str|:chart|:human|:debug|:error|invokeid-str>
                               :glyph <ch> :summary <one-line> :ev ev}`.
   Returns nil/empty when the event has no scrollback relevance."
  [{:keys [event data] :as ev}]
  (let [iid    (some-> (:invokeid data) str)
        src    (or iid
                   (case event
                     (:human-input/start :human-input/answer
                                         :human-input/cancelled :human-input/error
                                         :human-input/validation-failed
                                         :human-input/interrupted)
                     :human

                     (:llm/error :llm/model-down :llm/model-policy-empty
                                 :runner/error :runner/aborted)
                     :error

                     (:debug/awaiting-quit :debug/awaiting-step
                                           :runner/started :runner/start-config :runner/done)
                     :debug

                     :chart))]
    (case event
      :runner/started        [{:source :debug :glyph \· :ev ev
                               :summary (str "runner started session=" (:session-id data)
                                             " chart=" (:chart-id data))}]
      :runner/start-config   [{:source :debug :glyph \· :ev ev
                               :summary (str "start config=" (pr-str (:config data)))}]
      :runner/event-processed [{:source :chart :glyph \· :ev ev
                                :summary (str (:event-name data)
                                              "  " (pr-str (:config-before data))
                                              " → " (pr-str (:config-after data)))}]
      :runner/quiescent      nil
      :runner/done           [{:source :debug :glyph \· :ev ev
                               :summary (str "done final=" (pr-str (:final-config data)))}]
      :runner/aborted        [{:source :error :glyph \⚠ :ev ev
                               :summary (str "aborted " (:reason data))}]
      :runner/error          [{:source :error :glyph \⚠ :ev ev
                               :summary (str "ERROR " (truncate (:message data) 200))}]

      :llm/start             [{:source src :glyph \· :ev ev
                               :summary (str "invocation start session=" (:session-id data))}]
      :llm/worker-exit       [{:source src :glyph \· :ev ev
                               :summary (str "invocation exit reason=" (:reason data))}]

      :llm/user-message
      [{:source src :glyph \▸ :ev ev
        :summary (truncate (or (:text data) "") 240)}]

      :llm/request
      ;; The user message was already shown via :llm/user-message; we render
      ;; :llm/request as a low-noise meta line only when there's a model tag.
      [{:source src :glyph \· :ev ev
        :summary (str "req "
                      (when-let [m (:model data)] (str "model=" m " "))
                      "n-messages=" (:n-messages data))}]

      :llm/response
      (let [blocks (:content data)
            stop   (:stop-reason data)
            usage  (:usage data)
            entries
            (vec
             (for [b blocks
                   :let [t (:type b)]
                   :when (#{:text :thinking :tool_use} t)]
               (case t
                 :text     {:source src :glyph \◂ :ev ev :block b
                            :summary (truncate (str (:text b)) 240)}
                 :thinking {:source src :glyph \… :ev ev :block b
                            :summary (truncate (str (:thinking b)) 240)}
                 :tool_use {:source src :glyph \⚙ :ev ev :block b
                            :summary (str (:name b) " "
                                          (truncate (pr-str (or (:input b) {})) 200))})))
            tail {:source src :glyph (if (= :end_turn stop) \✓ \·) :ev ev
                  :summary (str "resp stop=" stop
                                (when-let [m (:model data)] (str " model=" m))
                                " tokens=in:" (:input-tokens usage "?")
                                "/out:" (:output-tokens usage "?"))}]
        (conj entries tail))

      :llm/tool-result
      [{:source src :glyph \↩ :ev ev
        :summary (str (:tool data)
                      (when (:is-error data) " (ERROR)")
                      "  " (truncate (str (:content-preview data)) 200))}]

      :llm/context-warning
      [{:source src :glyph \⚠ :ev ev
        :summary (str "context " (long (* 100 (:used-frac data 0))) "%")}]

      :llm/error
      [{:source :error :glyph \⚠ :ev ev
        :summary (str "llm error " (truncate (:message data) 200))}]

      :llm/model-down
      [{:source :error :glyph \⚠ :ev ev
        :summary (str "model-down " (or (:model data) "<default>")
                      " — " (truncate (:message data) 120))}]

      :llm/model-policy-empty
      [{:source :error :glyph \⚠ :ev ev
        :summary (str "model policy " (pr-str (:policy data)) " filter empty")}]

      :human-input/start
      [{:source :human :glyph \? :ev ev
        :summary (str "prompt kind=" (:kind data)
                      (when-let [p (:prompt data)] (str " : " (truncate p 200))))}]

      :human-input/answer
      [{:source :human :glyph \! :ev ev
        :summary (str "answer kind=" (:kind data)
                      (when (contains? data :answer)
                        (str " = " (truncate (pr-str (:answer data)) 200))))}]

      :human-input/cancelled
      [{:source :human :glyph \⚠ :ev ev :summary "cancelled"}]

      :human-input/error
      [{:source :error :glyph \⚠ :ev ev
        :summary (str "human ERROR " (truncate (:message data) 200))}]

      :checkpoint/written nil
      :runner/tick        nil

      :debug/awaiting-quit
      [{:source :debug :glyph \· :ev ev
        :summary (or (:msg data) "Press Ctrl-C to quit.")}]
      :debug/awaiting-step
      [{:source :debug :glyph \· :ev ev
        :summary (str "PAUSED on event=" (:event-name data)
                      (when (:external? data) " (external)"))}]

      ;; default
      [{:source src :glyph \· :ev ev
        :summary (str (name (or event :unknown)) " "
                      (truncate (pr-str (or data {})) 200))}])))

(defn format-event
  "Public for tests. Render `ev` as a single one-line string `<source> <summary>`.
   Multi-block events return the FIRST entry's summary only (use `entries-for`
   to get the full expansion). Returns nil when the event is suppressed."
  [ev]
  (let [es (entries-for ev)
        e  (first es)]
    (when e
      (let [src (:source e)
            tag (cond
                  (string? src) src
                  (keyword? src) (name src)
                  :else (str src))]
        (str "[" tag "] " (:summary e))))))

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
            inspector?     ;; bool — overlay always available (true whenever TUI enabled)
            debug?         ;; bool — debug controller features (pause/step/continue) enabled?
            debug-controller ;; atom (escapement.debug.controller) or nil
            debug-config   ;; map (.escapement.edn merged) or nil
            env            ;; atom holding the chart's env (for inspector reads)
            stopped?       ;; atom bool — guards stop! against double-invocation
            ])

;; ---------------------------------------------------------------------------
;; Debug overlay — inspector views
;; ---------------------------------------------------------------------------

(defn- status-indicator
  "Returns the short status prefix: `H` if a human-input modal is up, `H?` when
   a modal is pending while the inspector is open, `P` if the controller is
   paused, otherwise `R`."
  [h s]
  (cond
    (:modal s)                              "H"
    (:pending-modal s)                      "H?"
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

(defn- entry-pager-text
  "Build full-text pager content for a scrollback entry `e` (with embedded
   `:ev`). Used when the user hits Enter on the main scrollback cursor."
  [{:keys [ev] :as _e}]
  (let [{:keys [event data]} ev
        hdr (str (ts->hms (:ts ev)) "  " (name (or event :?)))]
    (case event
      :llm/response
      ;; If the entry has a specific :block, render just that block.
      (let [b (:block _e)]
        (if b
          (case (:type b)
            :text     (str hdr "  (text)\n\n" (:text b))
            :thinking (str hdr "  (thinking)\n\n" (:thinking b))
            :tool_use (str hdr "  (tool_use " (:name b) ")\n\nINPUT:\n"
                           (pretty (:input b)))
            (str hdr "\n\n" (pretty b)))
          (str hdr "\n\n" (pretty data))))

      :llm/tool-result
      (str hdr "  tool=" (:tool data)
           (when (:is-error data) "  (ERROR)")
           "\n\n" (or (:content-preview data) ""))

      :llm/user-message
      (str hdr "\n\n" (or (:text data) ""))

      :llm/request
      (str hdr "\n\n" (pretty data))

      :human-input/start
      (str hdr "  kind=" (pr-str (:kind data))
           (when-let [p (:prompt data)] (str "\n\n" p)))

      :human-input/answer
      (str hdr "  kind=" (pr-str (:kind data))
           "\n\n" (pretty (:answer data)))

      ;; default — pretty the whole event
      (str hdr "\n\n" (pretty ev)))))

(defn- render-overlay!
  "Renders the inspector overlay into the scrollback region. Row range
   `[r0..r1]` is inclusive."
  [^StringBuilder buf h s r0 r1 term-w]
  (let [ov     (:debug-overlay s)
        view   (:view ov)
        cursor (:cursor ov 0)
        env    (some-> (:env h) deref)
        events (:events ov)
        tabs   "[1]Invocations [2]Chart [3]Status"
        pending-suffix (if (:pending-modal s)
                         "  Esc=back (1 prompt waiting)"
                         "")
        title  (str " ── inspector "
                    (case view
                      :invocations  tabs
                      :chart        tabs
                      :status       tabs
                      "")
                    "  (j/k g/G Enter=drill o=open Esc/h=back  s/c/p/P=ctrl v=viz)"
                    pending-suffix
                    " ── ")]
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
   layout: 1 header + 1 status + N scrollback + 2 modal/help.
   Returns `{:slice <vec of entries> :start <abs idx of slice[0]>}`."
  [{:keys [scrollback scroll-offset]} term-h]
  (let [room  (max 5 (- term-h 4))
        n     (count scrollback)
        end   (max 0 (- n scroll-offset))
        start (max 0 (- end room))]
    {:slice (subvec scrollback start end)
     :start start}))

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
          ind    (when (:inspector? h) (str "[" (status-indicator h s) "] "))
          paused?  (and (:debug? h) (:debug-controller h)
                        (dbg/paused? (:debug-controller h))
                        (nil? (:modal s))
                        (not (get-in s [:debug-overlay :open?])))
          header (str " escapement · " (:chart-sym h) " · " (:session-short h))
          status (if paused?
                   (str " " ind "PAUSED — s=step c=continue ?=inspector")
                   (str " " (or ind "") "states: " (pr-str (:config s []))))
          ;; Inspector overlay opens OVER a live modal — user can drill into
          ;; scrollback, hit Esc to close, and then answer the modal.
          overlay-open? (and (:inspector? h) (get-in s [:debug-overlay :open?]))
          vis    (when-not overlay-open? (visible-scrollback s term-h))
          lines  (:slice vis)
          slice-start (:start vis 0)
          cursor-idx  (:cursor-idx s)
          modal  (:modal s)
          help   (cond
                   (:debug? h)
                   " Esc=interrupt  Ctrl-C=quit  ?=inspector  s/c/p/P=ctrl  v=viz"
                   (:inspector? h)
                   " Esc=interrupt  Ctrl-C=quit  ?=inspector  PgUp/PgDn=scroll"
                   :else
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
            (doseq [[i entry] (map-indexed vector lines)]
              (let [row     (+ first-row i)
                    abs-idx (+ slice-start i)
                    sel?    (and cursor-idx (= abs-idx cursor-idx))
                    line    (if (map? entry) (:line entry) (str entry))]
                (when (<= row last-row)
                  (.append buf (move-to-s row 1))
                  (when sel? (.append buf reverse-on-s))
                  (.append buf (truncate line term-w))
                  (when sel? (.append buf reset-attrs-s))
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
        :invocations  (count (:invocations ov))
        :chart        (count (current-event-rows (:events ov)))
        0))))

(defn- append-scrollback!
  "Append a single status line to the TUI scrollback. Trimmed at 2000 lines.
   `line` may be a string (system message) or a `{:line :ev}` map entry."
  [state line]
  (swap! state update :scrollback
         (fn [v]
           (let [entry (if (map? line) line {:line (str line) :ev nil})
                 v' (conj (or v []) entry)
                 n  (count v')]
             (if (> n 2000) (subvec v' (- n 2000)) v')))))

(defn- do-visualize!
  "On the first `v` press, start a tiny httpkit-backed viz server that renders
   the chart to SVG once and pushes a config update to the browser via SSE on
   every state change. Subsequent presses re-log the URL so the user can grab
   it again. Server lifetime is tied to the TUI state atom under `:viz-server`
   and torn down by `stop!`."
  [h]
  (let [state (:state h)
        s     @state]
    (if-let [server (:viz-server s)]
      (append-scrollback! state (str "[viz] already running: " (:url server)))
      (let [env    (some-> (:env h) deref)
            chart  (chart-from-env env)
            sdir   (session-dir-from-env env)]
        (cond
          (nil? chart)
          (append-scrollback! state "[viz] no chart attached to TUI handle (debug bug)")

          (nil? sdir)
          (append-scrollback! state "[viz] no session-dir on env — cannot write chart.svg")

          :else
          (let [start! (try (requiring-resolve 'escapement.debug.viz-server/start!)
                            (catch Throwable t
                              (append-scrollback!
                               state (str "[viz] cannot load viz-server ns: " (.getMessage t)))
                              nil))]
            (when start!
              (try
                (let [r (start! {:chart       chart
                                 :state-atom  state
                                 :session-dir sdir
                                 :config      (:debug-config h)
                                 :title       (str (:chart-sym h)
                                                   " · " (:session-short h))})]
                  (cond
                    (:error r)
                    (append-scrollback!
                     state (str "[viz] failed: " (:error r)
                                " (d2 source still at " sdir "/chart.d2)"))

                    (:url r)
                    (do (swap! state assoc :viz-server r)
                        (append-scrollback!
                         state (str "[viz] live: " (:url r)
                                    " (SVG also at " (:svg-path r) ")"))
                        (let [viewer (ecfg/viewer-for-url (:debug-config h))]
                          (when (string? viewer)
                            (try
                              (let [cmd (ecfg/expand-command viewer (:url r))]
                                (.exec (Runtime/getRuntime) ^"[Ljava.lang.String;"
                                       (into-array String ["sh" "-c" cmd]))
                                (append-scrollback!
                                 state (str "[viz] launched: " cmd)))
                              (catch Throwable t
                                (append-scrollback!
                                 state (str "[viz] auto-open failed: "
                                            (.getMessage t)
                                            " — open " (:url r) " manually")))))))

                    :else
                    (append-scrollback! state (str "[viz] result: " (pr-str r)))))
                (catch Throwable t
                  (append-scrollback!
                   state (str "[viz] threw: " (.getMessage t))))))))))))

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
          ;; Close overlay; if a human-input modal is pending, promote it now.
          (swap! state
                 (fn [s]
                   (let [pm (:pending-modal s)]
                     (cond-> (assoc-in s [:debug-overlay :open?] false)
                       pm (-> (assoc :modal pm) (dissoc :pending-modal)))))))

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
          :chart        (open-event-detail! h state (:cursor ov 0))
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
            ;; Force-quit. Send :ui.quit on the off-chance the chart wants
            ;; to react, but don't wait: the runner's quiescent loop drops
            ;; events without transitions, so cooperative shutdown isn't
            ;; reliable. Restore the terminal directly, then System/exit so
            ;; any shutdown hooks (transcript flush, etc.) still run.
            (do (send-ui-event! h :ui.quit)
                (stop! h)
                (System/exit 130))

            ;; Modal active: a small set of keys is intercepted FIRST so the
            ;; user can still pop the inspector OVER the modal and scroll the
            ;; scrollback. The modal itself stays parked until they Esc the
            ;; inspector and answer it.
            (:modal @state)
            (cond
              (and (:inspector? h) (= k [:char \?]))
              (do (swap! state update-in [:debug-overlay :open?] not)
                  (render-frame! h) (recur))

              ;; If the inspector is up over the modal, route keys to the
              ;; inspector — modal does NOT consume them.
              (and (:inspector? h) (get-in @state [:debug-overlay :open?]))
              (do (handle-debug-key! h k) (render-frame! h) (recur))

              (= k :pgup)
              (do (swap! state update :scroll-offset (fnil + 0) 10)
                  (render-frame! h) (recur))

              (= k :pgdn)
              (do (swap! state update :scroll-offset
                         #(max 0 (- (or % 0) 10)))
                  (render-frame! h) (recur))

              :else
              (do (handle-modal-key state k)
                  (render-frame! h)
                  (recur)))

            ;; Inspector overlay key dispatch (no modal active).
            (and (:inspector? h)
                 (get-in @state [:debug-overlay :open?]))
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
              :end  (do (swap! state assoc :scroll-offset 0
                               :cursor-idx nil)
                        (render-frame! h)
                        (recur))
              :up   (do (swap! state
                               (fn [s]
                                 (let [n (count (:scrollback s))
                                       cur (or (:cursor-idx s) n)]
                                   (assoc s :cursor-idx (max 0 (dec cur))))))
                        (render-frame! h) (recur))
              :down (do (swap! state
                               (fn [s]
                                 (let [n (count (:scrollback s))
                                       cur (or (:cursor-idx s) (dec n))]
                                   (assoc s :cursor-idx (min (dec n) (inc cur))))))
                        (render-frame! h) (recur))
              :enter (do
                       (let [s @state
                             idx (:cursor-idx s)
                             entry (when idx (nth (:scrollback s) idx nil))]
                         (when (and entry (:ev entry))
                           (swap! state assoc-in [:debug-overlay :open?] true)
                           (open-pager! state
                                        (str (name (or (:event (:ev entry)) :event)))
                                        (entry-pager-text entry))))
                       (render-frame! h) (recur))
              (cond
                (= k [:char \j])
                (do (swap! state
                           (fn [s]
                             (let [n (count (:scrollback s))
                                   cur (or (:cursor-idx s) (dec n))]
                               (assoc s :cursor-idx (min (dec n) (inc cur))))))
                    (render-frame! h) (recur))
                (= k [:char \k])
                (do (swap! state
                           (fn [s]
                             (let [n (count (:scrollback s))
                                   cur (or (:cursor-idx s) n)]
                               (assoc s :cursor-idx (max 0 (dec cur))))))
                    (render-frame! h) (recur))
                (= k [:char \g])
                (do (swap! state assoc :cursor-idx 0) (render-frame! h) (recur))
                (= k [:char \G])
                (do (swap! state
                           (fn [s] (assoc s :cursor-idx
                                          (max 0 (dec (count (:scrollback s)))))))
                    (render-frame! h) (recur))
                ;; Inspector top-level key (always active when no modal).
                (and (:inspector? h) (= k [:char \?]))
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
                 false (boolean debug?) debug-controller debug-config (atom nil) (atom false))
    (let [terminal (-> (TerminalBuilder/builder)
                       (.system true)
                       (.build))
          state    (atom {:config           []
                          :scrollback       []
                          :scroll-offset    0
                          :cursor-idx       nil ;; nil = no selection; index into scrollback
                          :invokeid-colors  {}  ;; invokeid → SGR code string
                          :next-color-idx   0
                          :modal            nil
                          :term-h           (.getHeight terminal)
                          :term-w           (.getWidth terminal)
                          :debug-overlay    {:open?       false
                                             :suspended?  false
                                             :view        :invocations
                                             :cursor      0
                                             :pane        :list
                                             :events      []
                                             :invocations []
                                             :focus       nil
                                             :pager       nil}})
          h        (->TuiHandle true state (Object.) terminal (atom false) nil
                                (atom nil) (atom nil)
                                (str chart-sym) (str session-short)
                                (atom false) (atom false)
                                true ;; inspector? — always on when TUI is enabled
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
      ;; Always-on safety net: if the process exits via SIGINT/SIGTERM/etc.
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

;; ---------------------------------------------------------------------------
;; Color allocator + entry → rendered line
;; ---------------------------------------------------------------------------

(defn- ansi-supported?
  "Cheap guard so colored output degrades on dumb terminals."
  []
  (let [t (System/getenv "TERM")]
    (not (or (nil? t) (= "dumb" t) (= "" t)))))

(defn- allocate-color
  "Returns updated state with a color (SGR code string) allocated for
   `invokeid`. Round-robin from the palette. Repeat calls return the existing
   allocation. Returns the (possibly-unchanged) state."
  [s invokeid]
  (if (or (nil? invokeid) (get-in s [:invokeid-colors invokeid]))
    s
    (let [idx  (:next-color-idx s 0)
          code (nth invokeid-palette (mod idx (count invokeid-palette)))]
      (-> s
          (assoc-in [:invokeid-colors invokeid] code)
          (assoc :next-color-idx (inc idx))))))

(defn- color-for
  "Looks up the SGR code (digits, e.g. `\"36\"`) for the given source. For
   invokeid string sources this reads from the allocator; for the well-known
   keyword sources, returns a fixed code. Returns nil when colors are not
   supported (caller will skip the wrap)."
  [s source]
  (when (ansi-supported?)
    (cond
      (string? source)  (get-in s [:invokeid-colors source])
      (= :chart source) chart-color
      (= :human source) human-color
      (= :error source) error-color
      (= :debug source) debug-color
      :else             nil)))

(defn- entry->rendered-line
  "Build the displayable line for a scrollback entry, with timestamp, source
   tag, glyph, summary, and optional ANSI color."
  [s {:keys [source glyph summary ev]}]
  (let [ts   (ts->hms (:ts ev))
        tag  (cond
               (string? source)   (short-invokeid source)
               (keyword? source)  (name source)
               :else              "?")
        code (color-for s source)
        body (str ts " [" tag "] " (or glyph \·) " " (str summary))]
    (if code
      (str (esc (str code "m")) body reset-attrs-s)
      body)))

(defn event!
  "Transcript-fn subscriber. Folds the event into the scrollback and updates
   the status line when the event carries a new chart configuration."
  [h ev]
  (when (:enabled? h)
    (let [entries  (entries-for ev)
          cfg      (get-in ev [:data :config-after])
          start-cfg (when (= (:event ev) :runner/start-config)
                      (get-in ev [:data :config]))]
      (swap! (:state h)
             (fn [s]
               (let [;; Allocate colors for any new string-valued (invokeid) sources.
                     s' (reduce (fn [s e]
                                  (let [src (:source e)]
                                    (if (string? src) (allocate-color s src) s)))
                                s entries)
                     s' (cond-> s'
                          cfg       (assoc :config cfg)
                          start-cfg (assoc :config start-cfg))
                     s' (if (seq entries)
                          (update s' :scrollback
                                  (fn [v]
                                    (let [v0 (or v [])
                                          v2 (reduce (fn [vv e]
                                                       (conj vv {:line (entry->rendered-line s' e)
                                                                 :ev   (:ev e)
                                                                 :source (:source e)
                                                                 :glyph (:glyph e)
                                                                 :block (:block e)
                                                                 :summary (:summary e)}))
                                                     v0
                                                     entries)
                                          n  (count v2)]
                                      (if (> n 2000) (subvec v2 (- n 2000)) v2))))
                          s')]
                 (cond-> s'
                   (debug-event-of-interest? ev)
                   (update-in [:debug-overlay :events]
                              (fn [v]
                                (let [v' (conj (or v []) ev)
                                      n  (count v')]
                                  (if (> n 1000) (subvec v' (- n 1000)) v'))))
                   (#{:llm/start :llm/worker-exit} (:event ev))
                   (update-in [:debug-overlay :invocations]
                              update-invocation-history ev)))))
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
  "True when a human-input modal is currently up, OR queued behind the
   inspector overlay. Used by the runner's debug pause-gate so it yields the
   screen while the chart is waiting on user input — debugger keys must not
   steal a Y/N answer, and pause-on-next must not fire while a prompt is
   waiting for the user to close the inspector."
  [h]
  (boolean (and h (:enabled? h)
                (let [s @(:state h)]
                  (or (:modal s) (:pending-modal s))))))

(defn stop!
  "Restore the terminal. Idempotent — safe to call repeatedly and from
   both the normal exit path and a process shutdown hook (Ctrl-C, SIGTERM).

   The restoration runs in a `finally` so the user gets their cursor and
   alt-screen state back even if viz-server teardown or thread interrupt
   throws past its catch. Critically, the restore-emit happens BEFORE
   JLine's `.close`: under tmux (and likely other multiplexers) the
   show-cursor sequence must reach the terminal while JLine still owns
   the tty handle, or the multiplexer's pane state remains 'cursor
   hidden' on the next refresh."
  [h]
  (when (and (:enabled? h)
             (compare-and-set! (:stopped? h) false true))
    (try
      (try
        (when-let [stop-viz (some-> (:state h) deref :viz-server :stop)]
          (stop-viz))
        (catch Throwable _ nil))
      (try
        (when-let [^Thread t (:input-thread h)]
          (when (not= t (Thread/currentThread)) (.interrupt t)))
        (catch Throwable _ nil))
      (finally
        ;; Restore terminal state FIRST, while JLine still holds the tty —
        ;; emitting after .close drops the bytes on tmux's per-pane state.
        (try
          (emit! (str reset-attrs-s show-cursor-s alt-screen-off-s show-cursor-s "\n"))
          (catch Throwable _ nil))
        (try
          (when (and (:terminal h) @(:raw-mode? h))
            (when-let [^Terminal term (:terminal h)]
              (.close term)))
          (catch Throwable _ nil)))))
  h)

;; ---------------------------------------------------------------------------
;; HumanRenderer impl — pops modals into the bottom region
;; ---------------------------------------------------------------------------

(defn- ask!
  "Set the modal state, redraw, and block until the input thread delivers
   the modal's promise. Throws on cancel so the human-input worker can
   propagate it as `:on-cancel-event`."
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
