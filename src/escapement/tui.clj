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
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.config :as ecfg]
    [escapement.debug.controller :as dbg]
    [escapement.invocation.human-input :as hi]
    [escapement.tui.theme :as theme]
    [escapement.tui.compositor :as cmp]
    [escapement.tui.util :as util]
    [escapement.tui.live :as live]
    [escapement.tui.log :as log]
    [escapement.tui.phase :as phase]
    [escapement.tui.transcript :as transcript]
    [escapement.tui.inspector :as inspector]
    [com.fulcrologic.statecharts.promise :as p])
  (:import
    (java.io Reader)
    (org.jline.terminal Terminal TerminalBuilder)
    (org.jline.utils NonBlockingReader)))

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

;; ANSI SGR/positioning primitives — extracted to escapement.tui.theme +
;; escapement.tui.compositor. Re-aliased here so the rest of this facade (and
;; tests referencing private vars like `#'escapement.tui/move-to-s`) keep working.
(def ^:private CSI theme/CSI)
(def ^:private ESC-CHAR theme/ESC-CHAR)                     ;; \e — SCI rejects \033 char literals
(def ^:private esc theme/esc)
(def ^:private clear-screen-s (str (esc "2J") (esc "H")))
(def ^:private clear-eol-s cmp/clear-eol-s)
;; Alternate screen buffer — full-screen TUI convention. Entering switches the
;; terminal to a fresh blank screen; leaving restores the prior contents (so
;; the user's shell scrollback isn't polluted with our paint).
(def ^:private alt-screen-on-s (esc "?1049h"))
(def ^:private alt-screen-off-s (esc "?1049l"))
;; Synchronized Output (Mode 2026). When the terminal supports it, any writes
;; between BSU/ESU are buffered and rendered atomically — true per-frame double
;; buffering. Supported by iTerm2 (recent), kitty, wezterm, foot, ghostty,
;; contour. Apple Terminal does NOT support it; we detect at startup.
(def ^:private sync-output-begin-s (esc "?2026h"))
(def ^:private sync-output-end-s (esc "?2026l"))
(def ^:private sync-output-query-s (esc "?2026$p"))
(def ^:private hide-cursor-s (esc "?25l"))
;; Under tmux-256color, terminfo `cnorm` is `\e[34h\e[?25h` — the
;; `\e[34h` ("Normal Cursor Visibility Mode") is what the multiplexer's
;; per-pane state watches for; `\e[?25h` alone is a no-op. The extra
;; code is harmless on terminals that don't recognise it, so we always
;; send the union, exactly like terminfo would.
(def ^:private show-cursor-s (str (esc "34h") (esc "?25h")))
(def ^:private reverse-on-s cmp/reverse-on-s)
(def ^:private reset-attrs-s theme/reset-attrs-s)
(def ^:private move-to-s cmp/move-to-s)

(defn- emit! [s]
  (binding [*out* *err*]
    (print s)
    (flush)))

;; ---------------------------------------------------------------------------
;; Event → one-line summary
;; ---------------------------------------------------------------------------

;; Whitespace/truncation helpers extracted to escapement.tui.compositor;
;; re-exported here (collapse-ws is public — tests use `tui/collapse-ws`).
(def collapse-ws cmp/collapse-ws)
(def ^:private truncate cmp/truncate)

(def ^:private short-invokeid util/short-invokeid)
(def ^:private ts->hms util/ts->hms)

;; Per-role palette + fixed role colors extracted to escapement.tui.theme.
(def ^:private invokeid-palette theme/invokeid-palette)
(def ^:private chart-color theme/chart-color)              ;; bright black / dim grey
(def ^:private human-color theme/human-color)              ;; bright white
(def ^:private error-color theme/error-color)              ;; red
(def ^:private debug-color theme/debug-color)              ;; dim

;; ---------------------------------------------------------------------------
;; Semantic color theme — capability-aware (256 → 16 → none)
;;
;; All color in the redesign routes through this one switch so NO_COLOR /
;; non-tty / dumb terminals degrade in a single place. A theme is a map of
;; semantic keys → SGR code strings (the digits between `\e[` and `m`, e.g.
;; "38;5;110" or "36"; "" means "no color"). `paint` wraps a body in the SGR
;; + reset, or returns it unchanged when the code is empty.
;; ---------------------------------------------------------------------------

;; The semantic theme (capability detection, theme maps, paint/status-color,
;; role-hue allocation) lives in escapement.tui.theme. Re-exported here so
;; external call sites (`escapement.tui/paint`, `tui/theme-for`, examples,
;; human-input) and the rest of this facade keep their existing names.
(def color-capability theme/color-capability)
(def ^:private theme-256 theme/theme-256)
(def ^:private theme-16 theme/theme-16)
(def ^:private theme-keys theme/theme-keys)
(def ^:private theme-none theme/theme-none)
(def theme-for theme/theme-for)
(def sgr-wrap theme/sgr-wrap)
(def paint theme/paint)
(def theme-color? theme/theme-color?)
(def status-color theme/status-color)

;; ---------------------------------------------------------------------------
;; Pane / box compositor + responsive layout (task 002)
;;
;; Draws bordered panes into the frame StringBuilder at arbitrary
;; (row, col, w, h) rectangles. The content renderers (LIVE / LOG / phase
;; tracker) and the frame integrator build on these primitives. Nothing here
;; touches `render-frame!`.
;;
;; The #1 alignment risk is wide-glyph width: box-drawing, shimmer, and CJK
;; glyphs occupy two terminal columns. `display-width` counts true columns
;; (SGR escapes are zero-width); `truncate-display` pads/clips to an exact
;; column count without ever splitting an escape sequence.
;; ---------------------------------------------------------------------------

;; The pane/box compositor + responsive layout lives in
;; escapement.tui.compositor. Re-exported here so external call sites
;; (tui/display-width, tui/layout) and the rest of this facade keep their
;; existing names; the private draw-box var deref in tests still works.
(def display-width cmp/display-width)
(def truncate-display cmp/truncate-display)
(def ^:private draw-box cmp/draw-box)
(def ^:private header-h cmp/header-h)
(def ^:private footer-h cmp/footer-h)
(def ^:private narrow-threshold cmp/narrow-threshold)
(def ^:private live-min-w cmp/live-min-w)
(def layout cmp/layout)

(defn- entries-for
  "Return a vector of scrollback entries (one per logical line) for transcript
   event `ev`. Each entry is `{:source <str|:chart|:human|:debug|:error|invokeid-str>
                               :glyph <ch> :summary <one-line> :ev ev}`.
   Returns nil/empty when the event has no scrollback relevance."
  [{:keys [event data] :as ev}]
  (let [iid (some-> (:invokeid data) str)
        src (or iid
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
      :runner/started [{:source  :debug :glyph \· :ev ev
                        :summary (str "runner started session=" (:session-id data)
                                   " chart=" (:chart-id data))}]
      :runner/start-config [{:source  :debug :glyph \· :ev ev
                             :summary (str "start config=" (pr-str (:config data)))}]
      :runner/event-processed [{:source  :chart :glyph \· :ev ev
                                :summary (str (:event-name data)
                                           "  " (pr-str (:config-before data))
                                           " → " (pr-str (:config-after data)))}]
      :runner/done [{:source  :debug :glyph \· :ev ev
                     :summary (str "done final=" (pr-str (:final-config data)))}]
      :runner/aborted [{:source  :error :glyph \⚠ :ev ev
                        :summary (str "aborted " (:reason data))}]
      :runner/error [{:source  :error :glyph \⚠ :ev ev
                      :summary (str "ERROR " (truncate (:message data) 200))}]

      :llm/start [{:source  src :glyph \· :ev ev
                   :summary (str "invocation start session=" (:session-id data))}]
      :llm/worker-exit [{:source  src :glyph \· :ev ev
                         :summary (str "invocation exit reason=" (:reason data))}]

      :llm/user-message
      [{:source  src :glyph \▸ :ev ev
        :summary (truncate (or (:text data) "") 240)}]

      :llm/request
      ;; The user message was already shown via :llm/user-message; we render
      ;; :llm/request as a low-noise meta line only when there's a model tag.
      [{:source  src :glyph \· :ev ev
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
                         :text {:source  src :glyph \◂ :ev ev :block b
                                :summary (truncate (str (:text b)) 240)}
                         :thinking {:source  src :glyph \… :ev ev :block b
                                    :summary (truncate (str (:thinking b)) 240)}
                         :tool_use {:source  src :glyph \⚙ :ev ev :block b
                                    :summary (str (:name b) " "
                                               (truncate (pr-str (or (:input b) {})) 200))})))
            tail   {:source  src :glyph (if (= :end_turn stop) \✓ \·) :ev ev
                    :summary (str "resp stop=" stop
                               (when-let [m (:model data)] (str " model=" m))
                               " tokens=in:" (:input-tokens usage "?")
                               "/out:" (:output-tokens usage "?")
                               (when-let [tps (:output-tps data)] (str " " tps "t/s"))
                               (when-let [ms (:elapsed-ms data)] (str " " ms "ms")))}]
        (conj entries tail))

      :llm/tool-result
      [{:source  src :glyph \↩ :ev ev
        :summary (str (:tool data)
                   (when (:is-error data) " (ERROR)")
                   "  " (truncate (str (:content-preview data)) 200))}]

      :llm/context-warning
      [{:source  src :glyph \⚠ :ev ev
        :summary (str "context " (long (* 100 (:used-frac data 0))) "%")}]

      :llm/error
      [{:source  :error :glyph \⚠ :ev ev
        :summary (str "llm error " (truncate (:message data) 200))}]

      :llm/model-down
      [{:source  :error :glyph \⚠ :ev ev
        :summary (str "model-down " (or (:model data) "<default>")
                   " — " (truncate (:message data) 120))}]

      :llm/model-policy-empty
      [{:source  :error :glyph \⚠ :ev ev
        :summary (str "model policy " (pr-str (:policy data)) " filter empty"
                   (when (:strict? data) " (strict: node failed)"))}]

      :human-input/start
      [{:source  :human :glyph \? :ev ev
        :summary (str "prompt kind=" (:kind data)
                   (when-let [p (:prompt data)] (str " : " (truncate p 200))))}]

      :human-input/answer
      [{:source  :human :glyph \! :ev ev
        :summary (str "answer kind=" (:kind data)
                   (when (contains? data :answer)
                     (str " = " (truncate (pr-str (:answer data)) 200))))}]

      :human-input/cancelled
      [{:source :human :glyph \⚠ :ev ev :summary "cancelled"}]

      :human-input/error
      [{:source  :error :glyph \⚠ :ev ev
        :summary (str "human ERROR " (truncate (:message data) 200))}]

      :checkpoint/written nil
      :runner/tick nil

      :debug/awaiting-quit
      [{:source  :debug :glyph \· :ev ev
        :summary (or (:msg data) "Press Ctrl-C to quit.")}]
      :debug/awaiting-step
      [{:source  :debug :glyph \· :ev ev
        :summary (str "PAUSED on event=" (:event-name data)
                   (when (:external? data) " (external)"))}]

      ;; default
      [{:source  src :glyph \· :ev ev
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

(declare chart-from-env stop! color-for role-sgr role-sgr-themed event!* live-agg request-render!)

;; ---------------------------------------------------------------------------
;; Internal state
;; ---------------------------------------------------------------------------

(defrecord ^:private TuiHandle
  [enabled?
   state                                                    ;; atom: {:config [], :scrollback [], :scroll-offset, :modal, :term-h, :term-w}
   lock                                                     ;; rendering lock
   terminal                                                 ;; JLine Terminal (or nil)
   raw-mode?                                                ;; atom bool
   input-thread                                             ;; Thread (or nil)
   session-id                                               ;; promise/atom
   queue                                                    ;; promise/atom: the runner's event queue
   chart-sym
   session-short
   cursor-shown?                                            ;; atom bool — tracks last-emitted state, so we only
   ;; emit hide/show ANSI on actual transitions
   sync-output?                                             ;; atom bool — terminal supports Mode 2026 atomic frames
   inspector?                                               ;; bool — overlay always available (true whenever TUI enabled)
   debug?                                                   ;; bool — debug controller features (pause/step/continue) enabled?
   debug-controller                                         ;; atom (escapement.debug.controller) or nil
   debug-config                                             ;; map (.escapement.edn merged) or nil
   env                                                      ;; atom holding the chart's env (for inspector reads)
   stopped?                                                 ;; atom bool — guards stop! against double-invocation
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
    (:modal s) "H"
    (:pending-modal s) "H?"
    (and (:debug-controller h)
      (dbg/paused? (:debug-controller h))) "P"
    :else "R"))

;; Inspector overlay views + transcript builders now live in
;; escapement.tui.inspector / escapement.tui.transcript; tiny shared helpers
;; (session-dir/list-artifacts/pretty) live in escapement.tui.util. Re-exported
;; here so the facade body / external call sites / tests keep resolving the
;; `escapement.tui/…` names.
(def ^:private list-artifacts util/list-artifacts)
(def ^:private session-dir-from-env util/session-dir-from-env)
(def ^:private pretty util/pretty)
(def ^:private open-pager! inspector/open-pager!)
(def ^:private close-pager! inspector/close-pager!)
(def ^:private current-event-rows inspector/current-event-rows)
(def ^:private render-overlay! inspector/render-overlay!)
(def ^:private view-row-count inspector/view-row-count)
(def ^:private open-artifact-file! inspector/open-artifact-file!)
(def ^:private focus-invocation! inspector/focus-invocation!)
(def ^:private open-focused-artifact! inspector/open-focused-artifact!)
(def ^:private open-event-detail! inspector/open-event-detail!)
(def ^:private open-invocation-transcript! inspector/open-invocation-transcript!)
(def ^:private invocation-transcript-text transcript/invocation-transcript-text)
(def ^:private fmt-transcript-event transcript/fmt-transcript-event)

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

;; ---------------------------------------------------------------------------
;; Live streaming-token region
;; ---------------------------------------------------------------------------

;; LIVE pane renderers + live-aggregation primitives now live in
;; escapement.tui.live; re-exported here so the facade body / external call
;; sites / tests keep resolving the `escapement.tui/…` names.
(def ^:private live-max-groups live/live-max-groups)
(def ^:private live-group-children live/live-group-children)
(def ^:private status-rank live/status-rank)
(def ^:private live-count live/live-count)
(def ^:private live-tps live/live-tps)
(def ^:private live-status live/live-status)
(def ^:private short-session live/short-session)
(def ^:private live-agg live/live-agg)
(def ^:private live-display-lines live/live-display-lines)
(def completion-bar live/completion-bar)
(def shimmer live/shimmer)
(def live-pane-lines live/live-pane-lines)


;; ---------------------------------------------------------------------------
;; LOG pane renderer (task 004)
;;
;; Renders the event scrollback as `:body-lines` for the LOG pane: role-colored
;; log lines (the role token wears the SAME hue the LIVE pane uses, via
;; `role-sgr`), plus a `{:pos :total}` scroll indicator for the pane title.
;; Pure-ish over the TUI state map + an explicit interior height and the LOG
;; pane's OWN scroll offset (separate from the inspector pager and the LIVE
;; pane). Does NOT draw the box (002's `draw-box`) and does NOT touch
;; `render-frame!` (006 wires this in via `draw-box` with the returned `:scroll`
;; as the title indicator).
;; ---------------------------------------------------------------------------

(def log-pane-lines log/log-pane-lines) ;; renderer moved to escapement.tui.log

;; ===========================================================================
;; Phase tracker + header strip (task 005)
;; ---------------------------------------------------------------------------
;; Pure, defensive walk over the statechart value stashed by `attach-env!`
;; (see `chart-from-env`). The chart is a plain map produced by
;; `com.fulcrologic.statecharts.chart/statechart`; we read its data shape
;; directly (no static require of the statechart ns — keeps this add-on
;; dependency-light and SCI-safe):
;;
;;   chart                                  ;; a map
;;   ├ :com.fulcrologic.statecharts/elements-by-id  → {id → element}
;;   ├ :com.fulcrologic.statecharts/ids-in-document-order → [id …]
;;   └ each element: {:id :node-type :children [child-id…] :parent id}
;;       :node-type ∈ #{:statechart :state :parallel :final
;;                      :initial :transition :on-entry …}  (root id = :ROOT)
;;
;; `:children` mixes real state children with synthetic :initial/:transition
;; nodes — always filter to state node-types. The active configuration is
;; `(:config s)`: a vector of active state ids (leaves + their compound
;; ancestors, as recorded by the runner).
;; ===========================================================================

;; Phase tracker + header strip + chart-walk helpers moved to
;; escapement.tui.phase; re-exported here for the facade body / tests.
(def chart-from-env phase/chart-from-env)
(def phase-model phase/phase-model)
(def sibling-strip phase/sibling-strip)
(def header-lines phase/header-lines)
;; private header helper re-exported via its var so existing specs that deref
;; `#'escapement.tui/session-tps-sum` keep resolving it.
(def ^:private session-tps-sum @#'phase/session-tps-sum)

;; ---------------------------------------------------------------------------
;; Focus / maximize / footer composition (task 006)
;; ---------------------------------------------------------------------------

(defn cycle-focus
  "Cycle the focused pane keyword (:live ↔ :log)."
  [focus]
  (if (= focus :live) :log :live))

(defn clamp-scroll
  "Clamp a scroll offset into `[0, (max 0 (- total visible))]`. `nil`/negative
   ⇒ 0. Used by the focused-pane scroll keys so neither pane scrolls past either
   end."
  [offset total visible]
  (let [hi (max 0 (- (or total 0) (or visible 0)))]
    (-> (or offset 0) (max 0) (min hi))))

(defn footer-text
  "Contextual footer string for the current focus / maximized / debug / narrow
   state. `focus` ∈ {:live :log}; the rest are booleans."
  [{:keys [focus maximized? debug? narrow?]}]
  (let [pane (if (= focus :live) "LIVE" "LOG")
        other (if (= focus :live) "LOG" "LIVE")
        live? (= focus :live)
        ctrl  (when debug? " · s/c/p/P ctrl")
        viz   (when debug? " · v viz")]
    (str " " pane (when maximized? " (max)")
      (if live? " · j/k select" " · ⇅ scroll")
      " · Enter transcript"
      (if maximized?
        (str " · Esc restore split · Tab → " other)
        (str " · m maximize · Tab → " other))
      " · ? inspector · a artifacts"
      ctrl viz
      (when-not maximized? " · Esc interrupt")
      " · Ctrl-C quit")))

(defn dispatch-key
  "Pure key-dispatch helper for the dashboard (no overlay, no modal). Given the
   logical key `k` (`:enter`, `:tab`, `:esc`, `:m`, `:j`/`:k`/`:g`/`:G` as
   `[:char \\c]`, `:up`/`:down`/etc.) and the relevant context map, returns the
   ACTION keyword the input loop should perform. Context:
     `{:focus :live|:log  :maximized? bool  :overlay-open? bool}`
   Actions:
     :open-transcript  — Enter: drill into the selected row's transcript.
     :maximize         — m: toggle maximize of the focused pane.
     :live-cursor-down/:live-cursor-up/:live-cursor-top/:live-cursor-bottom
                       — j/k/g/G while LIVE focused (move the selection cursor).
     :scroll-down/:scroll-up/:scroll-top/:scroll-bottom
                       — j/k/g/G while LOG focused (scroll the pane).
     :focus-cycle      — Tab.
     :restore-split    — Esc while maximized.
     :interrupt        — Esc otherwise.
     nil               — not handled here."
  [k {:keys [focus maximized?]}]
  (let [live? (= focus :live)]
    (cond
      (= k :enter)        :open-transcript
      (= k :tab)          :focus-cycle
      (= k [:char \m])    :maximize
      (= k :esc)          (if maximized? :restore-split :interrupt)
      (or (= k :down) (= k [:char \j])) (if live? :live-cursor-down :scroll-down)
      (or (= k :up)   (= k [:char \k])) (if live? :live-cursor-up   :scroll-up)
      (= k [:char \g])    (if live? :live-cursor-top :scroll-top)
      (= k [:char \G])    (if live? :live-cursor-bottom :scroll-bottom)
      :else nil)))

(defn clamp-live-cursor
  "Clamp a LIVE selection cursor into `[0, (max 0 (dec n))]` where `n` is the
   visible-row count. `nil`/negative ⇒ 0; empty pane ⇒ 0."
  [cursor n]
  (let [n (max 0 (or n 0))]
    (if (zero? n) 0 (-> (or cursor 0) (max 0) (min (dec n))))))

(defn paused-banner
  "Pure: the themed PAUSED status banner shown on the legacy host's status row
   while the debug controller has the chart paused. `theme` is a `theme-for`
   map; `ind` is the leading `[P] ` status-indicator prefix (may be \"\").

   Renders an amber (`:status/waiting`) accent `PAUSED` label followed by dim
   key hints (`s=step  c=continue  p=pause  P=arm  ?=inspector`). Honors
   `:none`/NO_COLOR — when the theme has no color, `paint` is a no-op and the
   result is plain text with zero escapes."
  [theme ind]
  (str " " (or ind "")
    (theme/paint theme :status/waiting "PAUSED")
    "  "
    (theme/paint theme :border-dim
      "s=step  c=continue  p=pause  P=arm  ?=inspector")))

(defn viz-entry
  "Pure: build a scrollback ENTRY map for a viz launcher message. Carries a
   `:source :viz` (so the themed LOG pane role-colors the line + status glyph)
   and a `:summary` for the LOG pane, plus a PLAIN `:line` for the legacy raw
   host (whose `truncate`/`collapse-ws` would strip embedded SGR — so the legacy
   line stays plain, escape-free text). `:glyph` marks the kind: `◆` info / `✗`
   error. The themed launcher line (LOG pane) is built by `log-entry->line` from
   `:source`/`:glyph`/`:summary`; this entry contributes ZERO escapes itself, so
   NO_COLOR is honored automatically."
  [_theme msg & {:keys [error?]}]
  {:source  :viz
   :glyph   (if error? \✗ \◆)
   :summary (str msg)
   :line    (str "[viz] " msg)
   :ev      nil})

(defn- focused-pane-metrics
  "Returns `[scroll-key total visible]` for the currently focused pane, used to
   clamp scroll keys. `total` = scrollable line/entry count; `visible` = pane
   interior height."
  [h s]
  (let [term-w (:term-w s 80)
        term-h (:term-h s 24)
        focus  (:focus s :log)
        lay    (layout {:term-w term-w :term-h term-h
                       :focus focus :maximized? (:maximized? s)})
        rect   (or (:body lay) (get lay focus))
        ih     (max 1 (- (:h rect 3) 2))]
    (if (= focus :live)
      [:live-scroll (count (live-pane-lines s theme-none (- (:w rect 42) 2) 0)) ih]
      [:log-scroll (count (:scrollback s)) ih])))

(defn- scroll-focused!
  "Mutate the focused pane's scroll offset by a logical direction, clamped.
   `dir` ∈ {:up :down :page-up :page-down :top :bottom}. LIVE and LOG use
   opposite offset conventions (LIVE offset 0 = top, LOG offset 0 = bottom/tail);
   this normalizes so :up always reveals older/earlier content in both."
  [state h dir]
  (swap! state
    (fn [s]
      (let [[k total visible] (focused-pane-metrics h s)
            log?  (= k :log-scroll)
            page  (max 1 (dec visible))
            cur   (get s k 0)
            ;; logical step in the OFFSET space: for LOG, :up = +N; for LIVE,
            ;; :up = -N (scroll toward the top of the list).
            step  (fn [n] (if log? (+ cur n) (- cur n)))
            nxt   (case dir
                    :up        (step 1)
                    :down      (step -1)
                    :page-up   (step page)
                    :page-down (step (- page))
                    :top       (if log? (max 0 (- total visible)) 0)
                    :bottom    (if log? 0 (max 0 (- total visible))))]
        (assoc s k (clamp-scroll nxt total visible))))))

(defn- live-row-count
  "Visible LIVE-row count = length of the live-row index for the current state."
  [s]
  (count (live/live-row-index s)))

(defn- move-live-cursor!
  "Move the LIVE selection cursor by a logical direction, clamped to the visible
   row count. `dir` ∈ {:up :down :top :bottom}."
  [state dir]
  (swap! state
    (fn [s]
      (let [n   (live-row-count s)
            cur (clamp-live-cursor (:live-cursor s) n)
            nxt (case dir
                  :down   (inc cur)
                  :up     (dec cur)
                  :top    0
                  :bottom (dec n))]
        (assoc s :live-cursor (clamp-live-cursor nxt n))))))

(defn- open-live-transcript!
  "Enter on the selected LIVE row: resolve its invokeid via `live/live-row-index`
   and open that invocation's live-updating transcript overlay. No-op if there's
   no selectable row."
  [h state]
  (let [s   @state
        idx (live/live-row-index s)
        cur (clamp-live-cursor (:live-cursor s) (count idx))]
    (when-let [iid (:invokeid (nth idx cur nil))]
      (inspector/open-transcript-overlay! h state iid))))

(defn- open-log-transcript!
  "Enter on the LOG pane: open the transcript for the selected scrollback line's
   invocation when attributable; otherwise no-op. Attribution is via the
   selected entry's `:invokeid` (falling back to the tail entry)."
  [h state]
  (let [s     @state
        sb    (:scrollback s)
        idx   (or (:cursor-idx s) (dec (count sb)))
        entry (nth (vec sb) idx nil)
        iid   (some-> (get-in entry [:ev :data :invokeid]) str)]
    (when iid
      (inspector/open-transcript-overlay! h state iid))))

(defn- render-mission-control!
  "New framed two-pane mission-control body: header strip + LIVE/LOG panes (or
   one maximized / narrow pane) + contextual footer. Assumes no inspector
   overlay is open (the legacy full-screen path handles that). Modals still
   render on their own row by the caller."
  [^StringBuilder buf h s theme term-w term-h]
  (let [focus      (:focus s :log)
        maximized? (:maximized? s)
        lay        (layout {:term-w term-w :term-h term-h
                            :focus focus :maximized? maximized?})
        narrow?    (= :narrow (:mode lay))
        ;; --- header strip ---
        hrect      (:header lay)
        hiw        (- (:w hrect) 2)
        hlines     (header-lines h s theme hiw)
        _          (draw-box buf {:row (:row hrect) :col (:col hrect)
                                  :w (:w hrect) :h (:h hrect)
                                  :theme theme :body-lines hlines})
        draw-pane  (fn [pane rect]
                     (when rect
                       (let [iw     (- (:w rect) 2)
                             ih     (- (:h rect) 2)
                             focus? (= pane focus)]
                         (if (= pane :live)
                           (let [all   (live-pane-lines s theme iw 0
                                         {:focus? focus? :cursor (:live-cursor s)})
                                 total (count all)
                                 off   (clamp-scroll (:live-scroll s) total ih)
                                 lines (take ih (drop off all))
                                 pos   (min total (+ off ih))]
                             (draw-box buf {:row (:row rect) :col (:col rect)
                                            :w (:w rect) :h (:h rect)
                                            :title "LIVE" :theme theme
                                            :focus? focus?
                                            :scroll (when focus? {:pos pos :total total})
                                            :body-lines lines}))
                           (let [{:keys [lines scroll]}
                                 (log-pane-lines s theme iw ih (:log-scroll s)
                                   (:cursor-idx s))]
                             (draw-box buf {:row (:row rect) :col (:col rect)
                                            :w (:w rect) :h (:h rect)
                                            :title "LOG" :theme theme
                                            :focus? focus?
                                            :scroll scroll
                                            :body-lines lines})))))) ]
    ;; --- body panes ---
    (cond
      (or maximized? narrow?)
      (draw-pane focus (:body lay))
      :else
      (do (draw-pane :live (:live lay))
          (draw-pane :log (:log lay))))
    ;; --- footer ---
    (let [frect (:footer lay)
          ftxt  (footer-text {:focus focus :maximized? maximized?
                              :debug? (:debug? h) :narrow? narrow?})]
      (.append buf (move-to-s (:row frect) 1))
      (.append buf (sgr-wrap (get theme :timestamp) (truncate-display ftxt term-w)))
      (.append buf clear-eol-s))
    buf))

(defn- render-overlay-fullscreen!
  "Render the inspector/transcript overlay FULLSCREEN beneath the mission-control
   header strip. Keeps the framed header (breadcrumb + run status + LLMs/act/t/s)
   so the user still sees the whole run's state, but the LIVE/LOG panes are
   hidden — the overlay owns the entire body region from just below the header
   down to the modal row. Mirrors the header geometry of
   `render-mission-control!` so the strip lines up identically."
  [^StringBuilder buf h s theme term-w term-h]
  (let [lay    (layout {:term-w term-w :term-h term-h
                        :focus (:focus s :log) :maximized? (:maximized? s)})
        hrect  (:header lay)
        hiw    (- (:w hrect) 2)
        hlines (header-lines h s theme hiw)]
    (draw-box buf {:row (:row hrect) :col (:col hrect)
                   :w (:w hrect) :h (:h hrect)
                   :theme theme :body-lines hlines})
    (let [r0 (+ (:row hrect) (:h hrect))   ;; first row below the header strip
          r1 (- term-h 2)]                 ;; leave the modal row (term-h-1) free
      (render-overlay! buf h s r0 r1 term-w))
    buf))

(defn- live-active?
  "True when any live session is mid-flight (:streaming/:waiting). Used by the
   render ticker to keep the shimmer animating while work is in progress."
  [s]
  (boolean
    (some (fn [g] (some #(#{:streaming :waiting} (:status %)) (vals (:sessions g))))
      (vals (:live s)))))

(defn- render-frame!
  [{:keys [state lock terminal] :as h}]
  (locking lock
    (let [term-h        (if terminal (.getHeight ^Terminal terminal) 24)
          term-w        (if terminal (.getWidth ^Terminal terminal) 80)
          ;; Restore an auto-suspended overlay once the modal has cleared.
          ;; Also bump :tick per frame so the LIVE-pane shimmer animates (003).
          _             (swap! state
                          (fn [s]
                            (let [ov (:debug-overlay s)]
                              (cond-> (-> (assoc s :term-h term-h :term-w term-w
                                            ;; This frame consumes any pending repaint
                                            ;; request — see `request-render!` / the
                                            ;; render ticker started in `start!`.
                                            :render-dirty false)
                                        (update :tick (fnil inc 0)))
                                (and (nil? (:modal s)) (:suspended? ov))
                                (assoc :debug-overlay
                                       (assoc ov :open? true :suspended? false))))))
          s             @state
          theme         (theme-for (color-capability (interactive-terminal?)))
          ind           (when (:inspector? h) (str "[" (status-indicator h s) "] "))
          paused?       (and (:debug? h) (:debug-controller h)
                          (dbg/paused? (:debug-controller h))
                          (nil? (:modal s))
                          (not (get-in s [:debug-overlay :open?])))
          header        (str " escapement · " (:chart-sym h) " · " (:session-short h))
          ;; The raw `states: […]` host line is dropped while the inspector
          ;; overlay is open — that config info now lives inside the themed
          ;; Chart/Status views. Keep it on the non-overlay legacy/paused host.
          status        (cond
                          paused?
                          (paused-banner theme ind)
                          (get-in s [:debug-overlay :open?])
                          ""
                          :else
                          (str " " (or ind "") "states: " (pr-str (:config s []))))
          ;; Inspector overlay opens OVER a live modal — user can drill into
          ;; scrollback, hit Esc to close, and then answer the modal.
          overlay-open? (and (:inspector? h) (get-in s [:debug-overlay :open?]))
          ;; Live streaming-token rows sit between the status line and the
          ;; scrollback; they steal height from the scrollback region only
          ;; while something is actually streaming.
          live-lines    (live-display-lines s term-w)
          live-n        (count live-lines)
          vis           (when-not overlay-open? (visible-scrollback s (- term-h live-n)))
          lines         (:slice vis)
          slice-start   (:start vis 0)
          cursor-idx    (:cursor-idx s)
          modal         (:modal s)
          help          (cond
                          (:debug? h)
                          " Esc=interrupt  Ctrl-C=quit  ?=inspector  s/c/p/P=ctrl  v=viz"
                          (:inspector? h)
                          " Esc=interrupt  Ctrl-C=quit  ?=inspector  PgUp/PgDn=scroll"
                          :else
                          " Esc=interrupt   Ctrl-C=quit   PgUp/PgDn=scroll")
          ;; Mission-control body only when the inspector overlay is closed AND
          ;; not paused (both want the full-screen legacy host). The modal row
          ;; (term-h-1) is shared by both paths.
          mc?           (and (not overlay-open?) (not paused?))
          buf           (StringBuilder.)]
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
      ;; Two body-rendering paths share the modal row + atomic flush below:
      ;;  * mission-control (mc?): framed header strip + LIVE/LOG panes + footer.
      ;;  * legacy full-screen: header/status/live/scrollback (also the overlay
      ;;    and PAUSED-banner host — they need the whole body region).
      (cond
        mc?
        (render-mission-control! buf h s theme term-w term-h)

        ;; Inspector/transcript overlay → fullscreen below the framed header
        ;; strip (LIVE/LOG hidden). Paused host (overlay-open? is false then)
        ;; still uses the legacy path below.
        overlay-open?
        (render-overlay-fullscreen! buf h s theme term-w term-h)

        :else
        (do
          (.append buf (move-to-s 1 1))
          (.append buf (truncate header term-w))
          (.append buf clear-eol-s)
          (.append buf (move-to-s 2 1))
          ;; The paused banner carries SGR escapes (themed accent + dim hints);
          ;; `truncate`/`collapse-ws` would strip ESC bytes, so emit it raw
          ;; (it's short + single-line by construction). All other status lines
          ;; are plain text and safe to truncate to term-w.
          (.append buf (if paused? status (truncate status term-w)))
          (.append buf clear-eol-s)
          ;; live region: rows 3 .. (2 + live-n), drawn only while streaming.
          (doseq [[i ln] (map-indexed vector live-lines)]
            (.append buf (move-to-s (+ 3 i) 1))
            (.append buf ln)                                ;; pre-truncated + colored
            (.append buf clear-eol-s))
          ;; scrollback region: rows (3 + live-n) .. (term-h - 2)
          (let [first-row (+ 3 live-n)
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
                ;; previous frame doesn't linger when scrollback shrinks (e.g.
                ;; after dedup collapse, after resize, or on initial render).
                (doseq [row (range (+ first-row written) (inc last-row))]
                  (.append buf (move-to-s row 1))
                  (.append buf clear-eol-s)))))))
      ;; modal area: row (term-h - 1)
      (let [modal-row (max 1 (dec term-h))]
        (.append buf (move-to-s modal-row 1))
        (.append buf clear-eol-s)                           ;; baseline: row is blank if no modal
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
      ;; help: last row (mission-control draws its own contextual footer there).
      (when-not mc?
        (.append buf (move-to-s term-h 1))
        (.append buf (truncate help term-w))
        (.append buf clear-eol-s))
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

(def ^:private esc-seq-timeout-ms
  "How long to wait for the byte after an ESC before concluding the user
   pressed a bare Escape. An escape *sequence* (arrow keys, terminal
   device-report replies like the Mode-2026 DECRPM `\\e[?2026;2$y`) delivers
   its bytes within a few ms; a real Escape is followed by nothing. 50ms is
   imperceptible to a human yet comfortably covers inter-byte latency over
   tmux/ssh. This MUST be > 0: with a `.ready`-only check, a sequence whose
   tail hadn't yet buffered was misread as a bare ESC and (with a modal up)
   cancelled the prompt — the TUI would flash open and immediately exit."
  50)

(defn- drain-csi-tail!
  "Consume the remainder of a CSI escape sequence whose final byte we did not
   already read. `b` is the byte after `ESC [` that was not a recognized final.
   Pulls bytes via `read!` (see `key-from-bytes`) up to and including the
   sequence's final byte (0x40–0x7E) so no trailing bytes (e.g. the `;2$y` of a
   DECRPM reply) leak back into the stream as spurious keystrokes."
  [read! b]
  (when-not (<= 0x40 b 0x7e)
    (loop [n 0]
      (when (< n 64)
        (let [x (read! esc-seq-timeout-ms)]
          (when (and (>= x 0) (not (<= 0x40 x 0x7e)))
            (recur (inc n))))))))

(defn- key-from-bytes
  "Decode one logical key from a byte source. `read!` is `(fn [timeout-ms] ->
   int)` returning the next byte: a non-positive `timeout-ms` blocks for the
   byte, a positive one waits up to that many ms and returns a negative value
   (EOF -1 / timeout -2) if none arrives. Factored out of the JLine Reader so
   the escape-sequence parsing is pure and unit-testable under bb without a
   live terminal. Return contract matches `read-key`.

   ESC disambiguation uses a bounded inter-byte wait (`esc-seq-timeout-ms`)
   rather than `.ready`: an escape *sequence* whose tail has not yet buffered
   must not be misclassified as a bare Escape — that misread, with a modal up,
   cancelled the prompt and made the TUI flash open then immediately exit."
  [read!]
  (let [c (read! 0)]
    (cond
      (= c -1) :eof
      (= c 3) :ctrl-c
      (= c 9) :tab
      (or (= c 8) (= c 127)) :backspace
      (or (= c 10) (= c 13)) :enter
      (= c 32) :space
      (= c 27)
      ;; ESC: bare Escape, or the introducer of a CSI (`ESC [`) / SS3
      ;; (`ESC O`) sequence. Wait briefly for the next byte; a negative
      ;; result (-1 EOF or -2 READ_EXPIRED timeout) means a real Escape.
      (let [b1 (read! esc-seq-timeout-ms)]
        (cond
          (neg? b1) :esc

          (= b1 91)                                         ;; CSI: ESC [
          (let [b2 (read! esc-seq-timeout-ms)]
            (case (int b2)
              65 :up
              66 :down
              67 :right
              68 :left
              72 :home
              70 :end
              ;; PgUp/PgDn arrive as ESC[5~ / ESC[6~; any other
              ;; parameterized/private CSI (incl. device-report replies)
              ;; is consumed through its final byte and ignored.
              (let [k (case (int b2) 53 :pgup 54 :pgdn :other)]
                (drain-csi-tail! read! b2)
                k)))

          (= b1 79)                                         ;; SS3: ESC O (application cursor keys)
          (let [b2 (read! esc-seq-timeout-ms)]
            (case (int b2) 65 :up 66 :down 67 :right 68 :left :other))

          :else :esc))
      (and (>= c 32) (< c 127)) [:char (char c)]
      :else :other)))

(defn- read-key
  "Block reading a single logical key from JLine `rdr`. Returns:
     :up :down :left :right :pgup :pgdn :home :end
     :esc :enter :ctrl-c :backspace :tab :space :eof
     [:char ch]  for any other printable character.

   Thin adapter: delegates the actual decode to `key-from-bytes`, supplying a
   `read!` backed by the reader (blocking for the first byte, timed for the
   inter-byte ESC disambiguation)."
  [^NonBlockingReader rdr]
  (key-from-bytes
    (fn [timeout-ms]
      (if (pos? timeout-ms)
        (.read rdr (long timeout-ms))
        (.read rdr)))))

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
  (let [m    (:modal @state)
        kind (:kind m)]
    (case kind
      :text
      (case k
        :enter (complete-modal! state (:buffer m ""))
        :esc (complete-modal! state ::cancelled)
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
        :enter (let [b (:buffer m "")
                     v (cond
                         (clojure.string/blank? b) (boolean (:default m))
                         (re-matches #"(?i)y(es)?" b) true
                         (re-matches #"(?i)no?" b) false
                         :else (boolean (:default m)))]
                 (complete-modal! state v))
        :esc (complete-modal! state ::cancelled)
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
          :up (swap! state update-in [:modal :cursor]
                (fn [i] (mod (dec (or i 0)) n)))
          :down (swap! state update-in [:modal :cursor]
                  (fn [i] (mod (inc (or i 0)) n)))
          :left (swap! state update-in [:modal :cursor]
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
          :esc (complete-modal! state ::cancelled)
          nil))

      nil)))

(defn- detect-sync-output!
  "Issue a DECRQM query for Mode 2026 and try to read the response. Returns
   true if the terminal indicates support (response values 1-4), false on
   timeout or an indication of no support. Must run AFTER raw mode is entered
   AND BEFORE the main input loop starts consuming bytes from the reader."
  [^NonBlockingReader rdr]
  (try
    (emit! sync-output-query-s)
    ;; Drain up to ~120 ms looking for `\e[?2026;<n>$y`. Some terminals never
    ;; reply at all — that's the timeout path, return false.
    (let [deadline (+ (System/currentTimeMillis) 150)
          sb       (StringBuilder.)]
      (loop []
        ;; Read with a short per-byte timeout (block on bytes rather than
        ;; busy-polling `.ready`) until the `$y` terminator arrives, 32 chars
        ;; accumulate, or the deadline passes. Consuming the reply to
        ;; completion HERE is what keeps its leading ESC out of the main input
        ;; loop, where it would be misread as the user pressing Escape.
        (when-not (or (>= (System/currentTimeMillis) deadline)
                    (>= (.length sb) 32)
                    (str/includes? (.toString sb) "$y"))
          (let [c (.read rdr (long 20))]
            (when (>= c 0) (.append sb (char c)))
            (recur))))
      (let [s (.toString sb)]
        (if-let [[_ n] (re-find #"\x1b\[\?2026;(\d+)\$y" s)]
          (boolean (#{"1" "2" "3" "4"} n))
          false)))
    (catch Throwable _ false)))

(defn- append-scrollback!
  "Append a single status line to the TUI scrollback. Trimmed at 2000 lines.
   `line` may be a string (system message) or a `{:line :ev}` map entry."
  [state line]
  (swap! state update :scrollback
    (fn [v]
      (let [entry (if (map? line) line {:line (str line) :ev nil})
            v'    (conj (or v []) entry)
            n     (count v')]
        (if (> n 2000) (subvec v' (- n 2000)) v')))))

(defn- do-visualize!
  "On the first `v` press, start a tiny httpkit-backed viz server that renders
   the chart to SVG once and pushes a config update to the browser via SSE on
   every state change. Subsequent presses re-log the URL so the user can grab
   it again. Server lifetime is tied to the TUI state atom under `:viz-server`
   and torn down by `stop!`."
  [h]
  (let [state  (:state h)
        s      @state
        theme  (theme-for (color-capability (interactive-terminal?)))
        ;; Themed viz logger: paints the `[viz]` tag + dim body, sources the
        ;; entry as `:viz` so the LOG pane role-colors it too. NO_COLOR-safe.
        vlog!  (fn [msg & {:keys [error?]}]
                 (append-scrollback! state (viz-entry theme msg :error? error?)))]
    (if-let [server (:viz-server s)]
      (vlog! (str "already running: " (:url server)))
      (let [env   (some-> (:env h) deref)
            chart (chart-from-env env)
            sdir  (session-dir-from-env env)]
        (cond
          (nil? chart)
          (vlog! "no chart attached to TUI handle (debug bug)" :error? true)

          (nil? sdir)
          (vlog! "no session-dir on env — cannot write chart.svg" :error? true)

          :else
          (let [start! (try (requiring-resolve 'escapement.debug.viz-server/start!)
                            (catch Throwable t
                              (vlog! (str "cannot load viz-server ns: " (.getMessage t))
                                :error? true)
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
                    (vlog! (str "failed: " (:error r)
                             " (d2 source still at " sdir "/chart.d2)")
                      :error? true)

                    (:url r)
                    (do (swap! state assoc :viz-server r)
                        (vlog! (str "live: " (:url r)
                                 " (SVG also at " (:svg-path r) ")"))
                        (let [viewer (ecfg/viewer-for-url (:debug-config h))]
                          (when (string? viewer)
                            (try
                              (let [cmd (ecfg/expand-command viewer (:url r))]
                                (.exec (Runtime/getRuntime) ^"[Ljava.lang.String;"
                                  (into-array String ["sh" "-c" cmd]))
                                (vlog! (str "launched: " cmd)))
                              (catch Throwable t
                                (vlog! (str "auto-open failed: "
                                         (.getMessage t)
                                         " — open " (:url r) " manually")
                                  :error? true))))))

                    :else
                    (vlog! (str "result: " (pr-str r)))))
                (catch Throwable t
                  (vlog! (str "threw: " (.getMessage t)) :error? true))))))))))

(defn- copy-to-clipboard!
  "Copy `text` to the terminal clipboard via OSC 52 (emitted on the same output
   stream as every other escape) and flash a `✓ copied` confirmation in the
   Artifacts view."
  [h text]
  (when (seq (str text))
    (emit! (util/osc52-seq text))
    (swap! (:state h) assoc-in [:debug-overlay :copied] (str text))))

(defn- handle-debug-key!
  "Dispatch a key while the debug overlay is open. Pager keys take precedence
   when the pager is up."
  [h k]
  (let [state (:state h)
        s     @state
        ov    (:debug-overlay s)
        pager (:pager ov)]
    (if pager
      ;; Pager scroll keys. Any UPWARD scroll detaches auto-follow (`:follow?
      ;; false`) so the user can read back through a streaming transcript; `G`/End
      ;; re-arm follow (snap to bottom + track new tokens). DOWNWARD scroll just
      ;; moves the offset — render re-arms follow once the offset reaches bottom.
      (let [detach! (fn [f] (swap! state update :debug-overlay
                              (fn [ov] (-> ov
                                         (update-in [:pager :offset] f)
                                         (assoc-in [:pager :follow?] false)))))
            up10  #(max 0 (- (or % 0) 10))
            up1   #(max 0 (dec (or % 0)))]
        (case k
          :esc (close-pager! state)
          :pgdn (swap! state update-in [:debug-overlay :pager :offset] (fnil + 0) 10)
          :space (swap! state update-in [:debug-overlay :pager :offset] (fnil + 0) 10)
          :pgup (detach! up10)
          :down (swap! state update-in [:debug-overlay :pager :offset] (fnil inc 0))
          :up (detach! up1)
          (cond
            (= k [:char \b]) (detach! up10)
            (= k [:char \j]) (swap! state update-in [:debug-overlay :pager :offset] (fnil inc 0))
            (= k [:char \k]) (detach! up1)
            (= k [:char \g]) (detach! (constantly 0))
            (= k [:char \G]) (swap! state update :debug-overlay
                               #(-> % (assoc-in [:pager :follow?] true)
                                  (assoc-in [:pager :offset]
                                    (max 0 (dec (count (get-in % [:pager :lines])))))))
            :else nil)))
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
        (or (= k [:char \4]) (= k [:char \a]))
        (swap! state update :debug-overlay
          merge {:view :artifacts :cursor 0 :focus nil :copied nil})

        ;; Artifacts view: copy the selected file's path (y) / the dir path (Y).
        (and (= :artifacts (:view ov)) (= k [:char \y]))
        (when-let [p (:path (inspector/artifacts-selection h s))]
          (copy-to-clipboard! h p))
        (and (= :artifacts (:view ov)) (= k [:char \Y]))
        (when-let [d (:dir (inspector/artifacts-selection h s))]
          (copy-to-clipboard! h d))

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
            ;; Enter on the list → show that invocation's full transcript.
            (open-invocation-transcript! h state (:cursor ov 0)))
          :chart (open-event-detail! h state (:cursor ov 0))
          :artifacts (inspector/open-selected-artifact-info! h state)
          nil)

        (= k [:char \o])
        (case (:view ov)
          :invocations
          (if (:focus ov)
            (open-focused-artifact! h state)
            ;; o on the list → drill into the artifact list for this invocation.
            (focus-invocation! state (:invocations ov) (:cursor ov 0)))
          :artifacts (inspector/open-selected-artifact-info! h state)
          nil)

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
    (let [rdr ^NonBlockingReader (.reader ^Terminal terminal)]
      (reset! sync-output? (detect-sync-output! rdr))
      ;; A render after detection so the first 2026-wrapped frame appears.
      (render-frame! h)
      (loop []
        (let [k (read-key rdr)]
          (cond
            (= :eof k)
            :stop

            (= :ctrl-c k)
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

            ;; No modal: chart-level keybindings (mission-control focus model).
            :else
            (case k
              ;; Page/arrow scroll the FOCUSED pane (j/k/g/G handled below via
              ;; dispatch-key so LIVE moves the selection cursor instead).
              :pgup (do (scroll-focused! state h :page-up) (render-frame! h) (recur))
              :pgdn (do (scroll-focused! state h :page-down) (render-frame! h) (recur))
              :home (do (scroll-focused! state h :top) (render-frame! h) (recur))
              :end  (do (scroll-focused! state h :bottom) (render-frame! h) (recur))
              (let [s   @state
                    act (dispatch-key k {:focus (:focus s :log)
                                         :maximized? (:maximized? s)})]
                (case act
                  :focus-cycle   (do (swap! state update :focus cycle-focus)
                                     (render-frame! h) (recur))
                  ;; Enter opens the selected row's transcript (LIVE) / line's
                  ;; invocation (LOG) — it NO LONGER maximizes.
                  :open-transcript (do (if (= :live (:focus s :log))
                                         (open-live-transcript! h state)
                                         (open-log-transcript! h state))
                                       (render-frame! h) (recur))
                  ;; m maximizes the focused pane (the binding Enter used to have).
                  :maximize      (do (swap! state update :maximized? not)
                                     (render-frame! h) (recur))
                  :restore-split (do (swap! state assoc :maximized? false)
                                     (render-frame! h) (recur))
                  :interrupt     (do (send-ui-event! h :ui.interrupt) (recur))
                  ;; Cursor moves during a live stream must NOT take the render
                  ;; lock synchronously — that contends with the per-token delta
                  ;; flood (each token marks dirty) and made keypresses lag ~2s.
                  ;; Just swap the cursor + mark dirty; the ticker repaints.
                  :live-cursor-down   (do (move-live-cursor! state :down) (request-render! h) (recur))
                  :live-cursor-up     (do (move-live-cursor! state :up) (request-render! h) (recur))
                  :live-cursor-top    (do (move-live-cursor! state :top) (request-render! h) (recur))
                  :live-cursor-bottom (do (move-live-cursor! state :bottom) (request-render! h) (recur))
                  :scroll-down   (do (scroll-focused! state h :down) (render-frame! h) (recur))
                  :scroll-up     (do (scroll-focused! state h :up) (render-frame! h) (recur))
                  :scroll-top    (do (scroll-focused! state h :top) (render-frame! h) (recur))
                  :scroll-bottom (do (scroll-focused! state h :bottom) (render-frame! h) (recur))
                  ;; not handled by dispatch-key — fall through to misc keys.
                  (cond
                ;; Inspector top-level key (always active when no modal).
                (and (:inspector? h) (= k [:char \?]))
                (do (swap! state update-in [:debug-overlay :open?] not)
                    (render-frame! h) (recur))

                ;; `a` opens the inspector straight to the session-wide Artifacts
                ;; view (dir + sizes + select/open/copy).
                (and (:inspector? h) (= k [:char \a]))
                (do (swap! state update :debug-overlay merge
                      {:open? true :view :artifacts :cursor 0 :focus nil :copied nil})
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

                :else (recur)))))))))
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
          state    (atom {:config          []
                          :start-ts        (System/currentTimeMillis)  ;; session-start stamp for the header clock
                          :scrollback      []
                          :scroll-offset   0
                          :cursor-idx      nil              ;; nil = no selection; index into scrollback
                          :focus           :log             ;; mission-control: focused pane (:live | :log)
                          :maximized?      false            ;; focused pane fills the body
                          :live-scroll     0                ;; LIVE pane scroll offset (lines)
                          :live-cursor     0                ;; LIVE pane selection cursor (index into visible rows)
                          :log-scroll      0                ;; LOG pane scroll offset (entries up from tail)
                          :tick            0                ;; per-frame counter for the shimmer
                          :render-dirty    false            ;; coalesced-repaint flag — see request-render! / render ticker
                          :live            {}               ;; invokeid → live streaming token counter
                          :invokeid-colors {}               ;; invokeid → SGR code string
                          :next-color-idx  0
                          :modal           nil
                          :term-h          (.getHeight terminal)
                          :term-w          (.getWidth terminal)
                          :debug-overlay   {:open?       false
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
                     true                                   ;; inspector? — always on when TUI is enabled
                     (boolean debug?) debug-controller debug-config
                     (atom nil) (atom false))
          t        (Thread. ^Runnable (fn [] (input-loop! h)) "tui-input")
          _        (.setDaemon t true)
          h        (assoc h :input-thread t)
          ;; Render ticker: the ONLY thread that repaints during streaming.
          ;; Producer threads (per-token :llm/delta) just mark state dirty via
          ;; request-render!; this loop coalesces those into bounded ~30fps
          ;; frames. While work is in flight it also repaints unconditionally so
          ;; the shimmer animates even between tokens. Decoupling rendering from
          ;; token arrival is what keeps streaming throughput at the model's true
          ;; rate instead of the render rate.
          ticker   (Thread. ^Runnable
                     (fn []
                       (loop []
                         (when-not @(:stopped? h)
                           (try
                             (Thread/sleep 33)
                             (let [s @state]
                               (when (or (:render-dirty s) (live-active? s))
                                 (render-frame! h)))
                             (catch InterruptedException _ nil)
                             (catch Throwable _ nil))
                           (recur))))
                     "tui-render")
          _        (.setDaemon ticker true)]
      ;; Enter alt screen buffer; user's prior terminal contents are preserved
      ;; and restored on stop!. Clear once and hide cursor (cursor stays hidden
      ;; unless a text/confirm modal is open).
      (emit! (str alt-screen-on-s clear-screen-s hide-cursor-s))
      (render-frame! h)
      (.start t)
      (.start ticker)
      ;; JLine installs its own SIGINT handler on system terminals, which
      ;; swallows the signal — JVM shutdown hooks are NOT invoked on Ctrl-C
      ;; otherwise. Install our own handler via sun.misc.Signal (the
      ;; SignalHandler interface has no inner classes, so reify works
      ;; under bb/SCI). Doing this AFTER (.build) means we overwrite
      ;; whatever JLine just installed.
      (try
        (sun.misc.Signal/handle
          (sun.misc.Signal. "INT")
          (reify sun.misc.SignalHandler
            (handle [_ _signal]
              (try (stop! h) (catch Throwable _ nil))
              (System/exit 130))))
        (catch Throwable _ nil))
      ;; Belt for non-INT exit paths (SIGTERM, normal System/exit): the JVM
      ;; shutdown hook still runs stop! so the terminal is restored.
      (try
        (.addShutdownHook (Runtime/getRuntime)
          (Thread. ^Runnable
            (fn [] (try (stop! h)
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

;; chart-from-env now lives in escapement.tui.phase (re-exported above);
;; `attach-env!` writes the same `:escapement.tui/chart` env-meta key.

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
      (let [d        (:data ev)
            invokeid (some-> (:invokeid d) str)
            ts       (or (:ts ev) (System/currentTimeMillis))
            entry    {:invokeid   invokeid
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

(defn- fold-live-event
  "Fold one transcript event into the `:live` map (invokeid → live counter).
   Returns the updated map. Tracks streaming progress per invocation:

     :llm/start         — register the invocation (shows as `0 tok` while the
                          model is thinking before the first token).
     :llm/delta         — accumulate chunk/char counts, running token usage
                          (if the provider streamed it), kind (text/thinking),
                          first/last timestamps for the t/s rate.
     :llm/response,
     :llm/worker-exit   — the turn/worker finished; drop it from the live panel.

   Keyed by invokeid so parallel + multiplexed child sessions (which all share
   the env's single transcript-fn) each get their own row."
  [live {:keys [event data ts]}]
  (let [;; The in-flight partial `:text` feeds ONLY the live transcript overlay's
        ;; streaming tail (real scrollback replaces it on :llm/response). Naively
        ;; appending every delta is O(N^2) string copying as tokens flood in —
        ;; the dominant cost behind cursor lag during a stream. Cap it to a
        ;; bounded tail so each append is O(1)-ish; the overlay only shows the
        ;; most recent slice anyway.
        cap-text live/cap-tail
        iid (some-> (:invokeid data) str)
        ;; Parallel multiplex children share one invokeid (every judge1 child is
        ;; "judge1"); the session-id disambiguates them. Nested as
        ;; invokeid → {:sessions {session-id → counter}} so the panel can show a
        ;; per-role group with its concurrent sessions indented beneath.
        sid (or (some-> (:session-id data) str) iid)
        ts  (or ts (System/currentTimeMillis))
        p   [iid :sessions sid]]
    (if (nil? iid)
      live
      (case event
        :llm/start
        (assoc-in live p {:chunks 0 :chars 0 :first-ts ts :last-ts ts
                          :status :waiting :text "" :model (:model data)
                          :provider (:provider data) :session sid})

        :llm/delta
        (let [cur    (or (get-in live p) {:chunks 0 :chars 0 :first-ts ts :text "" :session sid})
              toks   (get-in data [:usage :output-tokens])
              ;; t/s must measure generation (first→last delta), NOT request
              ;; latency. The :llm/start handler stamps :first-ts at dispatch
              ;; (status :waiting), which folds time-to-first-token (model load,
              ;; prompt eval, any queue wait) into the rate. Re-anchor :first-ts
              ;; to the FIRST delta so live-tps reflects true token throughput.
              first? (zero? (long (or (:chunks cur) 0)))]
          (assoc-in live p
            (cond-> (-> cur
                      (update :chunks inc)
                      (update :chars + (count (or (:text data) "")))
                      (update :text (fn [t] (cap-text (str t (or (:text data) "")))))
                      (assoc :last-ts ts
                             :status  :streaming
                             :model   (:model data)
                             :provider (:provider data)
                             :session sid
                             :kind    (if (= :thinking-delta (:type data))
                                        :thinking :text)))
              first? (assoc :first-ts ts)
              toks   (assoc :tokens toks))))

        ;; Turn finalized → it is now in the scrollback transcript; keep the row
        ;; visible as `done` but clear the in-flight partial so it isn't shown
        ;; twice. A subsequent turn's deltas flip it back to `streaming`.
        :llm/response
        (assoc-in live p (-> (or (get-in live p) {:session sid})
                           (assoc :status :done :text "" :last-ts ts
                                  :reason (:stop-reason data))
                           ;; A non-streaming turn emits no :llm/delta, so the
                           ;; model/provider were never stamped on the entry —
                           ;; the response is the first event that carries them.
                           ;; Apply them here (keeping any already set) so the
                           ;; LIVE `provider/model` column is populated on done
                           ;; rows, not just streaming ones.
                           (cond->
                             (:model data)    (assoc :model (:model data))
                             (:provider data) (assoc :provider (:provider data)))
                           ;; the LLM's TRUE generation rate (output tokens over
                           ;; the turn's wall-clock), computed in
                           ;; llm_conversation; prefer it over the TUI's
                           ;; delta-arrival estimate which gets stretched by
                           ;; concurrency/queueing.
                           (cond->
                             (:output-tps data) (assoc :real-tps (:output-tps data))
                             (:elapsed-ms data)  (assoc :elapsed-ms (:elapsed-ms data)))
                           ;; A non-streaming turn emits no :llm/delta, so
                           ;; :tokens was never folded from a delta's usage and
                           ;; :chunks stayed 0 — live-count would render 0 tok.
                           ;; The response's usage carries the authoritative
                           ;; output-tokens; apply it here (also as the final
                           ;; count for streaming turns).
                           (cond->
                             (get-in data [:usage :output-tokens])
                             (assoc :tokens (get-in data [:usage :output-tokens])))))

        (:llm/error :llm/model-down)
        (assoc-in live p (-> (or (get-in live p) {:session sid})
                           (assoc :status :error :last-ts ts
                                  :reason (or (:message data) (:model data)))))

        :llm/worker-exit
        (assoc-in live p (-> (or (get-in live p) {:session sid})
                           (assoc :status (if (= :error (:status (get-in live p))) :error :done)
                                  :reason (or (:reason data) (:reason (get-in live p)))
                                  :text "" :last-ts ts)))

        live))))

;; ---------------------------------------------------------------------------
;; Color allocator + entry → rendered line
;; ---------------------------------------------------------------------------

;; The per-role hue allocator (ansi-supported?/allocate-color/color-for/
;; role-sgr/role-sgr-themed) lives in escapement.tui.theme. Re-exported here so
;; the facade body and external call sites keep their existing names.
(def ^:private ansi-supported? theme/ansi-supported?)
(def ^:private allocate-color theme/allocate-color)
(def ^:private color-for theme/color-for)
(def role-sgr theme/role-sgr)
(def role-sgr-themed theme/role-sgr-themed)

(defn- entry->rendered-line
  "Build the displayable line for a scrollback entry, with timestamp, source
   tag, glyph, summary, and optional ANSI color."
  [s {:keys [source glyph summary ev]}]
  (let [ts   (ts->hms (:ts ev))
        tag  (cond
               (string? source) (short-invokeid source)
               (keyword? source) (name source)
               :else "?")
        code (color-for s source)
        body (str ts " [" tag "] " (or glyph \·) " " (str summary))]
    (if code
      (str (esc (str code "m")) body reset-attrs-s)
      body)))

(defn- request-render!
  "Mark the UI dirty so the next render-ticker frame repaints. Cheap and
   non-blocking — callers on hot/producer threads (e.g. per-token :llm/delta)
   must use this instead of `render-frame!`, which takes the render lock and
   does a full repaint. Coalescing repaints onto the ticker keeps token
   delivery off the render critical path."
  [h]
  (swap! (:state h) assoc :render-dirty true)
  nil)

(defn event!
  "Transcript-fn subscriber. Folds the event into the scrollback and updates
   the status line when the event carries a new chart configuration."
  [h ev]
  (when (:enabled? h)
    ;; Streaming deltas are high-frequency and must NEVER hit the scrollback
    ;; (entries-for has no case for them → they'd spam the default branch).
    ;; Fold them into the live panel and mark dirty — the render ticker repaints
    ;; at a bounded rate. Rendering synchronously here (the old behavior) put a
    ;; full-screen repaint + render-lock on EVERY token of EVERY concurrent
    ;; stream, serializing producers on the terminal and throttling throughput
    ;; to the render rate (~2 t/s under contention) instead of the model's true
    ;; ~120 t/s. Coalescing decouples token delivery from rendering.
    (if (= :llm/delta (:event ev))
      (do (swap! (:state h)
            (fn [s] (-> s (update :live fold-live-event ev) (assoc :render-dirty true))))
          nil)
      (do (event!* h ev)
          (request-render! h))))
  nil)

(defn- event!*
  "Non-delta transcript-event handling: scrollback + inspector + live-panel
   lifecycle. Split out of `event!` so the hot `:llm/delta` path stays tiny."
  [h ev]
  (when (:enabled? h)
    (let [entries   (entries-for ev)
          cfg       (get-in ev [:data :config-after])
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
                     cfg (assoc :config cfg)
                     start-cfg (assoc :config start-cfg))
                s' (if (seq entries)
                     (update s' :scrollback
                       (fn [v]
                         (let [v0 (or v [])
                               v2 (reduce (fn [vv e]
                                            (conj vv {:line    (entry->rendered-line s' e)
                                                      :ev      (:ev e)
                                                      :source  (:source e)
                                                      :glyph   (:glyph e)
                                                      :block   (:block e)
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
                update-invocation-history ev)

              ;; Keep the live status panel current for the non-delta lifecycle
              ;; events (deltas are folded on the fast path in `event!`).
              (#{:llm/start :llm/response :llm/error :llm/model-down :llm/worker-exit}
                (:event ev))
              (update :live fold-live-event ev)))))))
  ;; NOTE: no synchronous `render-frame!` here. This runs INLINE on the
  ;; runner-loop / LLM-worker threads (the transcript tap is called on the
  ;; emitting thread — see runner.clj), so painting here serializes the
  ;; statechart behind a locked full-screen repaint + flushed stderr write
  ;; for every event. Instead the caller (`event!`) marks the UI dirty via
  ;; `request-render!` and the 30fps render ticker coalesces the repaint off
  ;; the agent's critical path — same fast-path treatment as `:llm/delta`.
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

(defn- run-tput-cnorm!
  "Shell out to `tput cnorm` to restore the cursor. Necessary because
   different `TERM`s need different escape sequences (e.g. tmux-256color
   needs `\\e[34h\\e[?25h`, xterm-256color needs `\\e[?12l\\e[?25h`).
   Terminfo knows; we read it via tput so we don't have to maintain a
   table of TERM→bytes. Output is inherited (writes go straight to the
   controlling tty) and the call is fire-and-forget."
  []
  (try
    (-> (ProcessBuilder.
          ^"[Ljava.lang.String;"
          (into-array String ["sh" "-c" "tput cnorm 2>/dev/null"]))
      (.inheritIO)
      (.start)
      (.waitFor))
    (catch Throwable _ nil)))

(defn stop!
  "Restore the terminal. Idempotent — safe to call repeatedly and from
   both the normal exit path and a process shutdown hook (Ctrl-C, SIGTERM).

   Restoration runs in a `finally` so the user gets their cursor and
   alt-screen state back even if viz-server teardown or thread interrupt
   throws past its catch. We emit our ANSI restore sequence while JLine
   still owns the tty AND shell out to `tput cnorm` afterwards — the
   `tput` call uses terminfo, which is the only thing that reliably
   wakes up tmux's per-pane cursor visibility tracking."
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
        (try (emit! (str reset-attrs-s alt-screen-off-s "\n"))
             (catch Throwable _ nil))
        (try
          (when (and (:terminal h) @(:raw-mode? h))
            (when-let [^Terminal term (:terminal h)]
              (.close term)))
          (catch Throwable _ nil))
        (run-tput-cnorm!))))
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
      (p/do! (ask! handle {:kind :text :prompt (or prompt "?") :buffer ""}))
      (hi/prompt-text (hi/stdin-renderer) {:prompt prompt})))
  (prompt-select [_ {:keys [prompt options] :as opts}]
    (if (:enabled? handle)
      (p/do! (ask! handle {:kind    :select :prompt (or prompt "Select:")
                           :options (vec options) :cursor 0}))
      (hi/prompt-select (hi/stdin-renderer) opts)))
  (prompt-multi [_ {:keys [prompt options] :as opts}]
    (if (:enabled? handle)
      (p/do! (ask! handle {:kind    :multi-select :prompt (or prompt "Select any:")
                           :options (vec options) :cursor 0 :checked #{}}))
      (hi/prompt-multi (hi/stdin-renderer) opts)))
  (prompt-confirm [_ {:keys [prompt default] :as opts}]
    (if (:enabled? handle)
      (p/do! (ask! handle {:kind   :confirm :prompt (or prompt "Confirm?")
                           :buffer "" :default (boolean default)}))
      (hi/prompt-confirm (hi/stdin-renderer) opts)))
  (start-progress [_ opts] (atom {:pct 0 :prompt (:prompt opts)}))
  (update-progress [_ handle' pct label]
    (swap! handle' assoc :pct pct :label label))
  (end-progress [_ _] nil)
  (custom-render [_ f env data] (p/do! (f env data))))

(defn ->renderer
  "Build a `HumanRenderer` that pops modals into the bottom region of this TUI.
   When the TUI is disabled (non-TTY), delegates to the stdin fallback."
  [h]
  (->TuiRenderer h))
