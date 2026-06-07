(ns escapement.tui.inspector
  "Inspector / debug overlay views + transcript pager for the terminal-UI
   add-on. Relocated from `escapement.tui` (task 003) — behavior is preserved;
   the themed `draw-box` overlay + view restyle land in tasks 004/005.

   Seam with the facade (`escapement.tui`):
   * The facade's `render-frame!` calls `(render-overlay! buf h s r0 r1 term-w)`
     to draw the overlay into the scrollback region.
   * The facade's key dispatch (`handle-debug-key!`) calls the open/transition
     helpers here — `open-pager!`, `close-pager!`, `view-row-count`,
     `open-invocation-transcript!`, `open-event-detail!`, `open-focused-artifact!`,
     `focus-invocation!`, `open-artifact-file!` — passing the facade's state atom
     in (the module never reaches back into the facade)."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [escapement.config :as ecfg]
    [escapement.tui.compositor :as cmp]
    [escapement.tui.live :as live]
    [escapement.tui.theme :as theme]
    [escapement.tui.transcript :as transcript]
    [escapement.tui.util :as util]))

;; ---------------------------------------------------------------------------
;; Pager state + helpers
;; ---------------------------------------------------------------------------

(defn pager-lines [s] (or (get-in s [:debug-overlay :pager :lines]) []))

(defn open-pager!
  "Push a pager (title + lines) onto the overlay state."
  [state title text]
  (let [lines (vec (str/split-lines (str text)))]
    (swap! state assoc-in [:debug-overlay :pager]
      {:title title :lines lines :offset 0})))

(defn close-pager! [state]
  (swap! state assoc-in [:debug-overlay :pager] nil))

(defn- fmt-hms
  "Formats a unix-ms timestamp as `HH:mm:ss.mmm` in the local timezone.
   Returns `\"--:--:--.---\"` when ts is nil."
  [ts]
  (if ts
    (let [fmt (java.text.SimpleDateFormat. "HH:mm:ss.SSS")]
      (.format fmt (java.util.Date. ^long ts)))
    "--:--:--.---"))

(defn current-event-rows
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

(defn- wrap-line
  "Word-wrap one logical line to `width` columns, preserving leading
   indentation on continuation rows. A word longer than the line is
   hard-split. Returns a vector of physical lines (never empty)."
  [width s]
  (let [width (max 1 width)
        s     (str s)]
    (if (<= (count s) width)
      [s]
      (let [indent (let [i (apply str (take-while #{\space} s))]
                     (if (< (count i) width) i ""))
            words  (str/split (str/triml s) #"\s+")]
        (loop [ws words, cur indent, out []]
          (if-let [w (first ws)]
            (cond
              ;; a single word too long for any line → hard char-split it
              (> (+ (count indent) (count w)) width)
              (let [room (max 1 (- width (count cur)))
                    here (subs w 0 (min room (count w)))
                    left (subs w (count here))]
                (if (str/blank? (str/trim cur))
                  (recur (cons left (rest ws)) "" (conj out (str cur here)))
                  (recur ws indent (conj out cur))))
              ;; fits on the current line
              (<= (+ (count cur) (if (= cur indent) 0 1) (count w)) width)
              (recur (rest ws) (if (= cur indent) (str cur w) (str cur " " w)) out)
              ;; doesn't fit → flush and wrap
              :else (recur ws indent (conj out cur)))
            (conj out cur)))))))

(defn render-pager-lines
  "Render lines for the pager into `buf` between rows `r0` and `r1`. Long lines
   are word-wrapped to the terminal width so nothing is truncated off-screen."
  [^StringBuilder buf {:keys [title lines offset]} r0 r1 term-w]
  (.append buf (cmp/move-to-s r0 1))
  (.append buf (cmp/truncate (str " ── " title " ── (PgUp/PgDn, Esc=close)") term-w))
  (.append buf cmp/clear-eol-s)
  (let [room  (max 1 (- r1 r0))
        lines (into [] (mapcat #(wrap-line term-w %)) lines)
        start (min (max 0 (or offset 0)) (max 0 (- (count lines) 1)))
        slice (subvec lines start (min (count lines) (+ start room)))]
    (doseq [[i ln] (map-indexed vector slice)]
      (.append buf (cmp/move-to-s (+ r0 1 i) 1))
      (.append buf (cmp/truncate ln term-w))
      (.append buf cmp/clear-eol-s))
    (doseq [row (range (+ r0 1 (count slice)) (inc r1))]
      (.append buf (cmp/move-to-s row 1))
      (.append buf cmp/clear-eol-s))))

(defn entry-pager-text
  "Build full-text pager content for a scrollback entry `e` (with embedded
   `:ev`). Used when the user hits Enter on the main scrollback cursor."
  [{:keys [ev] :as _e}]
  (let [{:keys [event data]} ev
        hdr (str (util/ts->hms (:ts ev)) "  " (name (or event :?)))]
    (case event
      :llm/response
      ;; If the entry has a specific :block, render just that block.
      (let [b (:block _e)]
        (if b
          (case (:type b)
            :text (str hdr "  (text)\n\n" (:text b))
            :thinking (str hdr "  (thinking)\n\n" (:thinking b))
            :tool_use (str hdr "  (tool_use " (:name b) ")\n\nINPUT:\n"
                        (util/pretty (:input b)))
            (str hdr "\n\n" (util/pretty b)))
          (str hdr "\n\n" (util/pretty data))))

      :llm/tool-result
      (str hdr "  tool=" (:tool data)
        (when (:is-error data) "  (ERROR)")
        "\n\n" (or (:content-preview data) ""))

      :llm/user-message
      (str hdr "\n\n" (or (:text data) ""))

      :llm/request
      (str hdr "\n\n" (util/pretty data))

      :human-input/start
      (str hdr "  kind=" (pr-str (:kind data))
        (when-let [p (:prompt data)] (str "\n\n" p)))

      :human-input/answer
      (str hdr "  kind=" (pr-str (:kind data))
        "\n\n" (util/pretty (:answer data)))

      ;; default — pretty the whole event
      (str hdr "\n\n" (util/pretty ev)))))

;; ---------------------------------------------------------------------------
;; Overlay rendering — themed `draw-box` frame (task 004)
;;
;; The legacy raw-scrollback overlay (a `── inspector ── (keys)` rule + raw
;; section dumps) was replaced by a compositor-drawn box over the body region,
;; matching the mission-control dashboard panes: themed border, a left-aligned
;; `inspector · <View>` title, and a right-aligned `⇅ pos/total` scroll
;; indicator. The header strip above (rows 1..2: chart line + states line) stays
;; visible; the overlay takes ONLY the body region `[r0..r1]`. Content lines are
;; unchanged here (tasks 005/006 restyle them); this task just reframes them.
;; ---------------------------------------------------------------------------

(def ^:private overlay-theme
  "Per-process semantic theme for the overlay. Computed once: capability is a
   fixed property of the terminal/env (NO_COLOR / TERM / tty). Honors NO_COLOR
   and non-tty (⇒ `:none` ⇒ zero escapes)."
  (delay
    (let [tty? (boolean
                 (try
                   (require 'babashka.terminal)
                   (when-let [tty? (resolve 'babashka.terminal/tty?)]
                     (and (tty? :stdin) (tty? :stdout)))
                   (catch Throwable _ (some? (System/console)))))]
      (theme/theme-for (theme/color-capability tty?)))))

(defn scroll-window
  "Pure scroll math for a box body. Given the total number of content rows
   `total`, the number of interior rows `room`, and a `keep` index that must
   remain visible (the highlighted/selected row, or the pager offset), return
   `{:start S :pos P :total total}` where:
   * `:start` is the index of the first content row to draw (0-based, clamped so
     a full window is shown when possible and `keep` stays in view);
   * `:pos`   is the 1-based position reported in the `⇅ pos/total` indicator
     (the `keep` row's 1-based index, or `:start`+1 when `keep` is nil);
   * `:total` echoes the row count.
   `room` and `total` are floored at 0; `keep` may be nil."
  [total room keep]
  (let [total (max 0 (or total 0))
        room  (max 0 (or room 0))
        max-start (max 0 (- total room))
        start (cond
                (zero? total) 0
                (nil? keep)   0
                ;; keep visible: scroll down only when keep falls past the window
                (>= keep (+ 0 room)) (min max-start (- (inc keep) room))
                :else 0)
        ;; if keep sits above an already-scrolled window, scroll up to it
        start (if (and keep (< keep start)) keep start)
        start (min start max-start)]
    {:start start
     :total total
     :pos   (cond
              (zero? total) 0
              keep          (inc keep)
              :else         (inc start))}))

(defn selected-invokeid
  "Pure helper: given the overlay state map `ov` (`(:debug-overlay s)`), return
   the invokeid string of the currently-selected Invocations row, or nil.

   Selection only exists in the Invocations LIST view (not the artifact
   drilldown, not Chart/Status). The history list is newest-first; `:cursor` is
   the 0-based index into it. Task 007 calls this to know which invocation's
   transcript Enter should open from the inspector."
  [ov]
  (when (and (= :invocations (:view ov)) (not (:focus ov)))
    (let [hist   (:invocations ov)
          cursor (:cursor ov 0)]
      (some-> (nth (vec hist) cursor nil) :invokeid))))

(defn- status-glyph+kw
  "Map an invocation history row to `[glyph status-kw label]` for the themed
   Invocations rows. Live (not yet ended) ⇒ streaming; finished reasons map to
   done/error/stop. Mirrors the LIVE-pane glyph convention so the eye links the
   inspector to the dashboard."
  [{:keys [ended-ms reason]}]
  (cond
    (nil? ended-ms)             [\◂ :streaming "live"]
    (= reason :stopped)         [\✓ :done "done"]
    (= reason :interrupted)     [\✗ :error "stop"]
    (= reason :error)           [\✗ :error "error"]
    reason                      [\· :done (name reason)]
    :else                       [\✓ :done "done"]))

(defn- invocation->live-agg
  "Aggregate the `:live` sessions for one invokeid into the live-agg summary
   (tokens, model). Returns {} when nothing is tracked for it."
  [s invokeid]
  (if-let [sessions (get-in s [:live invokeid :sessions])]
    (live/live-agg sessions)
    {}))

(defn render-overlay!
  "Renders the inspector overlay into the body region as a themed `draw-box`.
   Row range `[r0..r1]` is inclusive (the box fills it). View switching (1/2/3)
   and j/k/g/G scroll are preserved; the box title shows the active view and a
   `⇅ pos/total` scroll indicator."
  [^StringBuilder buf h s r0 r1 term-w]
  (let [ov             (:debug-overlay s)
        view           (:view ov)
        cursor         (:cursor ov 0)
        env            (some-> (:env h) deref)
        events         (:events ov)
        theme          @overlay-theme
        box-w          (max 2 term-w)
        box-h          (max 2 (inc (- r1 r0)))               ;; r0..r1 inclusive
        interior-h     (max 0 (- box-h 2))
        interior-w     (max 0 (- box-w 2))
        view-name      (case view
                         :invocations "Invocations"
                         :chart       "Chart"
                         :status      "Status"
                         :artifacts   "Artifacts"
                         "Inspector")
        pending-suffix (if (:pending-modal s) " · 1 prompt waiting" "")
        draw!          (fn [title body-lines scroll]
                         (cmp/draw-box buf
                           {:row r0 :col 1 :w box-w :h box-h
                            :title title :scroll scroll
                            :focus? true :theme theme
                            :body-lines body-lines}))]
    (if-let [pager (:pager ov)]
      ;; A transcript pager carries `:live-invokeid`. Rebuild from current state
      ;; every frame ONLY while that invocation is still streaming, so live
      ;; tokens appear as they arrive. Once finished, reuse the lines already
      ;; built once by open-*transcript! — otherwise every scroll keypress would
      ;; re-walk scrollback + re-read every turn's blobs (the ~10s/keypress lag).
      (let [iid    (:live-invokeid pager)
            ;; Transcript pagers (carry `:live-invokeid`) render via the THEMED,
            ;; already-width-correct `transcript-lines` so the SENT/REPLY
            ;; direction tags keep their background/bold styling. Those lines must
            ;; NOT pass through `wrap-line` (it counts escape bytes and would
            ;; re-wrap/corrupt them) — they're already exactly `interior-w` wide.
            ;; Plain pagers (artifacts, events) keep the count-based `wrap-line`.
            transcript? (some? iid)
            live?  (and iid (live/invokeid-live? s iid))
            ;; (Re)build colored, width-correct transcript lines while live (or
            ;; when the cache predates this interior width); cache them so a
            ;; finished transcript reuses them without re-walking blobs per frame.
            pager  (if (and transcript?
                         (or live?
                           (not= interior-w (:rendered-w pager))
                           (nil? (:colored-lines pager))))
                     (let [cl (transcript/invocation-transcript-colored-lines
                                h iid theme interior-w)
                           p  (assoc pager :colored-lines cl :rendered-w interior-w)]
                       (when-let [st (:state h)]
                         (swap! st update-in [:debug-overlay :pager]
                           merge {:colored-lines cl :rendered-w interior-w}))
                       p)
                     pager)
            wrapped (if transcript?
                      (:colored-lines pager)
                      (into [] (mapcat #(wrap-line interior-w %)) (:lines pager)))
            ;; Auto-follow: a transcript pager pins to the bottom (so streamed
            ;; tokens stay in view) UNTIL the user scrolls up (`:follow?` set
            ;; false by the pager keys). `G`/End re-arm follow. The bottom offset
            ;; is `count - interior-h` so the last interior-h lines show.
            follow? (and transcript? (not (false? (:follow? pager))))
            offset  (if follow?
                      (max 0 (- (count wrapped) interior-h))
                      (min (max 0 (or (:offset pager) 0)) (max 0 (dec (count wrapped)))))
            bottom  (max 0 (- (count wrapped) interior-h))
            ;; While following, keep state's `:offset` synced to the visible
            ;; bottom so the first PgUp/k detaches from where the eye is (not 0).
            ;; When a detached pager is scrolled back down to the bottom, re-arm
            ;; follow so new tokens track again.
            _       (when (:state h)
                      (cond
                        (and follow? (not= offset (:offset pager)))
                        (swap! (:state h) assoc-in [:debug-overlay :pager :offset] offset)
                        (and transcript? (not follow?) (>= offset bottom))
                        (swap! (:state h) assoc-in [:debug-overlay :pager :follow?] true)))
            {:keys [start total pos]} (scroll-window (count wrapped) interior-h offset)
            slice   (subvec wrapped start (min (count wrapped) (+ start interior-h)))]
        (draw! (str (:title pager) pending-suffix)
          slice
          {:pos pos :total total}))
      ;; Each view returns {:rows [strings] :hl-offset N} where hl-offset is
      ;; the index into :rows corresponding to cursor=0. That keeps decorative
      ;; header lines from throwing off the selection highlight.
      (let [sdir     (util/session-dir-from-env env)
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
                      arts   (util/list-artifacts sdir invokeid)
                      header [(str " " invokeid "  ── (Esc/h to go back, Enter/o to view) ──")]]
                  (if (seq arts)
                    {:rows        (into header
                                    (mapv (fn [name]
                                            (let [f (io/file (str sdir "/artifacts/" name))]
                                              (format "  %-30s  %sB"
                                                (str/join (take 30 name))
                                                (.length f))))
                                      arts))
                     :hl-offset   (count header)
                     :selectable? true}
                    {:rows      (conj header "  (no artifacts captured for this invocation)")
                     :hl-offset nil :selectable? false}))

                ;; List view: invocation history (newest first). One themed row
                ;; per invocation: role token (role hue) · status glyph (status
                ;; color) · tokens/model (metric/dim). Mirrors LIVE/LOG hues so
                ;; the eye links the inspector to the dashboard.
                :else
                (let [hist (:invocations ov)]
                  (if (seq hist)
                    {:rows        (mapv (fn [{:keys [invokeid] :as row}]
                                          (let [[glyph st label] (status-glyph+kw row)
                                                agg     (invocation->live-agg s invokeid)
                                                model   (or (:model agg) "")
                                                toks    (long (or (:tokens agg) 0))
                                                role    (theme/sgr-wrap
                                                          (theme/role-sgr-themed theme s invokeid)
                                                          (format "%-13s" (or (util/short-invokeid invokeid) "?")))
                                                gly     (theme/sgr-wrap (theme/status-color theme st)
                                                          (str glyph " " (format "%-6s" label)))
                                                tokstr  (theme/paint theme :metric (format "%5d tok" toks))
                                                modstr  (theme/paint theme :timestamp model)]
                                            (str " " role "  " gly "  " tokstr "  " modstr)))
                                    hist)
                     :hl-offset   0
                     :selectable? true}
                    {:rows      [" (no LLM invocations yet)"]
                     :hl-offset nil :selectable? false})))

              :chart
              (let [active (or (:config s) (get-in (last events) [:data :config-after]))
                    erows  (current-event-rows events)
                    label  (fn [k] (theme/paint theme :timestamp k))
                    metric (fn [v] (theme/paint theme :metric v))
                    header [(str " " (label "active states ") (metric (pr-str active)))
                            (str " " (theme/paint theme :session-id
                                       "── recent events (newest first) ──"))]]
                {:rows        (into header
                                (mapv (fn [{:keys [ts event-name config-before config-after]}]
                                        (str "  "
                                          (theme/paint theme :timestamp (fmt-hms ts)) "  "
                                          (format "%-22s" (str event-name)) "  "
                                          (theme/paint theme :session-id (pr-str config-before))
                                          (theme/paint theme :timestamp "  →  ")
                                          (theme/paint theme :metric (pr-str config-after))))
                                  erows))
                 :hl-offset   (count header)
                 :selectable? (seq erows)})

              :status
              (let [c    (:debug-controller h)
                    cs   (when c @c)
                    sdir (util/session-dir-from-env env)
                    abs  (when sdir (try (.getAbsolutePath (io/file sdir))
                                         (catch Throwable _ sdir)))
                    active (or (:config s) (get-in (last events) [:data :config-after]))
                    label  (fn [k] (theme/paint theme :timestamp k))
                    metric (fn [v] (theme/paint theme :metric (str v)))
                    line   (fn [k v] (str " " (label (format "%-16s" k)) (metric v)))]
                {:rows      [(line "active states:"  (pr-str active))
                             (line "mode:"           (or (:mode cs) "n/a"))
                             (line "step-budget:"    (or (:step-budget cs) 0))
                             (line "pause-on-ext?:"  (boolean (:pause-on-next-external? cs)))
                             (line "buffered events:" (count events))
                             (line "session-dir:"    (or abs "—"))
                             (line "artifacts-dir:"  (when abs (str abs "/artifacts/")))]
                 :hl-offset nil :selectable? false})

              :artifacts
              (let [adir   (util/artifacts-dir sdir)
                    abs    (when adir (try (.getAbsolutePath adir)
                                           (catch Throwable _ (str adir))))
                    arts   (util/list-all-artifacts sdir)
                    total  (reduce + 0 (map :size arts))
                    copied (:copied ov)
                    label  (fn [k] (theme/paint theme :timestamp k))
                    metric (fn [v] (theme/paint theme :metric (str v)))
                    header (cond-> [(str " " (label "dir:  ") (metric (or abs "—")))
                                    (str " " (label "size: ")
                                      (metric (str (util/human-size total)
                                                "  (" (count arts) " files)")))
                                    (str " " (theme/paint theme :session-id
                                               "── j/k select · Enter/o open · y copy path · Y copy dir · Esc close ──"))]
                             copied (conj (str " " (theme/paint theme :status/done
                                                     (str "✓ copied: " copied)))))]
                (if (seq arts)
                  {:rows        (into header
                                  (mapv (fn [{:keys [name size]}]
                                          (str "  "
                                            (theme/paint theme :metric
                                              (format "%-40s" (cmp/truncate name 40)))
                                            "  "
                                            (theme/paint theme :timestamp (util/human-size size))))
                                    arts))
                   :hl-offset   (count header)
                   :selectable? true}
                  {:rows      (conj header "  (no artifacts in this session)")
                   :hl-offset nil :selectable? false}))

              {:rows [" (unknown view)"] :hl-offset nil :selectable? false})
            rows     (vec rows)
            hl-row   (when (and selectable? hl-offset)
                       (+ hl-offset cursor))
            {:keys [start total pos]} (scroll-window (count rows) interior-h hl-row)
            slice    (subvec rows start (min (count rows) (+ start interior-h)))
            ;; Pre-style the highlighted row before handing to draw-box: SGR
            ;; escapes survive `truncate-display`, so wrapping the visible cell
            ;; in reverse-video + reset yields a clean full-row highlight inside
            ;; the box interior (no bleed).
            body     (vec
                       (map-indexed
                         (fn [i ln]
                           (let [abs (+ start i)]
                             (if (and hl-row (= abs hl-row))
                               (str cmp/reverse-on-s
                                 (cmp/truncate-display ln interior-w)
                                 theme/reset-attrs-s)
                               ln)))
                         slice))]
        (draw! (str "inspector · " view-name pending-suffix)
          body
          {:pos pos :total total})))))

;; ---------------------------------------------------------------------------
;; Overlay navigation + open/transition helpers (called by the facade's key
;; dispatch). Each takes the facade's state atom in explicitly.
;; ---------------------------------------------------------------------------

(defn view-row-count
  "How many selectable rows the current overlay view has."
  [h s]
  (let [ov  (:debug-overlay s)
        env (some-> (:env h) deref)]
    (cond
      ;; Drilled into a single invocation — selectable rows are its artifacts.
      (and (= :invocations (:view ov)) (:focus ov))
      (count (util/list-artifacts (util/session-dir-from-env env)
               (get-in ov [:focus :invokeid])))

      :else
      (case (:view ov)
        :invocations (count (:invocations ov))
        :chart (count (current-event-rows (:events ov)))
        :artifacts (count (util/list-all-artifacts (util/session-dir-from-env env)))
        0))))

(defn artifacts-selection
  "For the session-wide `:artifacts` view, return
   `{:dir abs-artifacts-dir :path selected-abs-path :name selected-name}` for the
   row at the current cursor. `:path`/`:name` are nil when the list is empty."
  [h s]
  (let [env  (some-> (:env h) deref)
        sdir (util/session-dir-from-env env)
        adir (util/artifacts-dir sdir)
        arts (vec (util/list-all-artifacts sdir))
        sel  (nth arts (get-in s [:debug-overlay :cursor] 0) nil)]
    {:dir  (when adir (try (.getAbsolutePath adir) (catch Throwable _ (str adir))))
     :path (:path sel)
     :name (:name sel)}))

(defn open-artifact-file!
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

(defn focus-invocation!
  "Drill into the invocation at `cursor` in the history list (newest first).
   Sets `:focus {:invokeid ...}` and resets cursor for the artifact list."
  [state hist cursor]
  (when-let [row (nth hist cursor nil)]
    (swap! state update :debug-overlay
      merge {:focus  {:invokeid (:invokeid row)}
             :cursor 0})))

(defn open-focused-artifact!
  "When drilled into an invocation, open the artifact at `cursor`."
  [h state]
  (let [s        @state
        ov       (:debug-overlay s)
        env      (some-> (:env h) deref)
        sdir     (util/session-dir-from-env env)
        invokeid (get-in ov [:focus :invokeid])
        arts     (util/list-artifacts sdir invokeid)
        idx      (:cursor ov 0)]
    (when-let [name (nth arts idx nil)]
      (open-artifact-file! state (:debug-config h)
        (str sdir "/artifacts/" name)
        name))))

(defn open-selected-artifact-info!
  "Open the artifact selected in the session-wide `:artifacts` view (via the
   configured viewer, or the internal pager)."
  [h state]
  (let [{:keys [path name]} (artifacts-selection h @state)]
    (when path
      (open-artifact-file! state (:debug-config h) path name))))

(defn open-event-detail!
  "Drill-in for a chart event row: pretty-print the event into the pager."
  [h state cursor]
  (let [s    @state
        rows (current-event-rows (get-in s [:debug-overlay :events]))]
    (when-let [{:keys [ev event-name]} (nth rows cursor nil)]
      (open-pager! state (str "event " event-name) (util/pretty ev)))))

(defn open-invocation-transcript!
  "Open the per-invocation transcript (system prompt + every turn + tool uses)
   in the pager for the invocation at `cursor` in the history list."
  [h state cursor]
  (let [hist (get-in @state [:debug-overlay :invocations])]
    (when-let [{:keys [invokeid]} (nth hist cursor nil)]
      (open-pager! state (str invokeid " · transcript")
        (transcript/invocation-transcript-text h invokeid))
      ;; Tag the pager so render rebuilds it live (streamed tokens appear as
      ;; they arrive, including the in-flight assistant turn).
      (swap! state assoc-in [:debug-overlay :pager :live-invokeid] invokeid))))

(defn open-transcript-overlay!
  "Open the debug overlay (if not already) and show `invokeid`'s transcript in a
   live-rebuilding pager. Used by the LIVE/LOG-pane Enter drill-in: a row's
   invokeid is resolved by the caller (e.g. `live/live-row-index`) and handed
   here. No-op when `invokeid` is nil. The pager carries `:live-invokeid` so
   render rebuilds it each frame (in-flight sessions stream live)."
  [h state invokeid]
  (when invokeid
    (swap! state assoc-in [:debug-overlay :open?] true)
    (open-pager! state (str invokeid " · transcript")
      (transcript/invocation-transcript-text h invokeid))
    (swap! state assoc-in [:debug-overlay :pager :live-invokeid] invokeid)))
