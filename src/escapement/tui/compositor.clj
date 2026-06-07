(ns escapement.tui.compositor
  "Pure pane / box compositor + responsive layout for the terminal UI
   (terminal-UI add-on; required only by the `escapement.tui` facade + sibling
   modules — never by engine core).

   Owns the cursor-positioning primitives (`move-to-s`, `clear-eol-s`,
   `reverse-on-s`), the display-width-aware string helpers (`collapse-ws`,
   `truncate`, `display-width`, `truncate-display`), the bordered-pane writer
   (`draw-box`), and the responsive geometry (`layout`). Everything is a pure
   function of its arguments (or a constant); nothing here touches the TUI state
   atom, JLine, or any JVM-only API. bb/SCI-safe (uses `theme/ESC-CHAR`, since
   SCI rejects `\\033` char literals).

   Color/SGR is delegated to `escapement.tui.theme` (`sgr-wrap`, `theme-none`)."
  (:require
    [clojure.string :as str]
    [escapement.tui.theme :as theme]))

;; ---------------------------------------------------------------------------
;; ANSI cursor / line primitives — written to *err*, positioning only.
;; ---------------------------------------------------------------------------

(def ^:private ESC-CHAR theme/ESC-CHAR)
(def clear-eol-s (theme/esc "K"))
(def reverse-on-s (theme/esc "7m"))
(defn move-to-s [row col] (theme/esc (str row ";" col "H")))

;; ---------------------------------------------------------------------------
;; Whitespace / truncation helpers
;; ---------------------------------------------------------------------------

(defn collapse-ws
  "Collapse all line-breaking / control whitespace (`\\n`, `\\r`, `\\t`, and any
   other C0 control char) plus runs of ordinary whitespace into a single space,
   trimming the ends. Used to flatten multiline streaming token content into a
   one-line preview so it can never inject a cursor-moving char into a pane
   body-line. Public for tests."
  [s]
  (-> (str s)
    (str/replace #"[\x00-\x1f\x7f\s]+" " ")
    (str/trim)))

(defn truncate [s n]
  (let [s (collapse-ws s)]
    (if (<= (count s) n) s (str (subs s 0 (max 0 (- n 1))) "…"))))

;; ---------------------------------------------------------------------------
;; Pane / box compositor + responsive layout
;;
;; Draws bordered panes into the frame StringBuilder at arbitrary
;; (row, col, w, h) rectangles. The content renderers (LIVE / LOG / phase
;; tracker) and the frame integrator build on these primitives.
;;
;; The #1 alignment risk is wide-glyph width: box-drawing, shimmer, and CJK
;; glyphs occupy two terminal columns. `display-width` counts true columns
;; (SGR escapes are zero-width); `truncate-display` pads/clips to an exact
;; column count without ever splitting an escape sequence.
;; ---------------------------------------------------------------------------

(defn- wide-codepoint?
  "True when codepoint `cp` occupies two terminal columns. Covers the common
   East-Asian Wide / Fullwidth ranges plus a handful of wide symbol blocks. We
   intentionally treat the box-drawing + block-element set used by this UI as
   NARROW (1 col) — virtually every monospace terminal renders `─ │ ╭ ▌ █ ◉ ▰`
   in a single cell, and counting them as 2 would misalign our own borders."
  [cp]
  (or
    ;; Hiragana, Katakana, CJK Unified Ideographs, Hangul, etc.
    (<= 0x1100 cp 0x115F)                                   ;; Hangul Jamo
    (<= 0x2E80 cp 0x303E)                                   ;; CJK radicals / Kangxi / punctuation
    (<= 0x3041 cp 0x33FF)                                   ;; Hiragana..CJK compat
    (<= 0x3400 cp 0x4DBF)                                   ;; CJK Ext A
    (<= 0x4E00 cp 0x9FFF)                                   ;; CJK Unified
    (<= 0xA000 cp 0xA4CF)                                   ;; Yi
    (<= 0xAC00 cp 0xD7A3)                                   ;; Hangul syllables
    (<= 0xF900 cp 0xFAFF)                                   ;; CJK compat ideographs
    (<= 0xFE30 cp 0xFE4F)                                   ;; CJK compat forms
    (<= 0xFF00 cp 0xFF60)                                   ;; Fullwidth forms
    (<= 0xFFE0 cp 0xFFE6)                                   ;; Fullwidth signs
    (<= 0x1F300 cp 0x1FAFF)                                 ;; emoji / symbols & pictographs
    (<= 0x20000 cp 0x3FFFD)))                               ;; CJK Ext B+ (supplementary)

(defn display-width
  "Count the terminal columns `s` occupies. SGR escape sequences (`\\e[…m`) are
   zero-width; wide glyphs (CJK / fullwidth / emoji) count as 2; everything else
   counts as 1. The box-drawing + shimmer set this UI uses (`─ │ ╭ ▌ █ ◉ ▰ › ⇅`)
   counts as 1, matching how monospace terminals render them."
  [s]
  (let [s (str s)
        n (.length s)]
    (loop [i 0 w 0]
      (if (>= i n)
        w
        (let [c (.charAt s i)]
          (cond
            ;; ESC — skip a CSI/SGR sequence: \e[ ... <final byte 0x40-0x7E>
            (= c ESC-CHAR)
            (let [j (if (and (< (inc i) n) (= (.charAt s (inc i)) \[))
                      ;; consume until a final byte in @..~ (0x40-0x7E)
                      (loop [k (+ i 2)]
                        (if (>= k n)
                          k
                          (let [d (.charAt s k)]
                            (if (<= 0x40 (int d) 0x7E) (inc k) (recur (inc k))))))
                      ;; lone ESC (or ESC + non-[) — skip just the ESC
                      (inc i))]
              (recur j w))
            ;; surrogate pair → one codepoint, possibly wide
            (and (Character/isHighSurrogate c) (< (inc i) n)
              (Character/isLowSurrogate (.charAt s (inc i))))
            (let [cp (Character/toCodePoint c (.charAt s (inc i)))]
              (recur (+ i 2) (+ w (if (wide-codepoint? cp) 2 1))))
            :else
            (recur (inc i) (+ w (if (wide-codepoint? (int c)) 2 1)))))))))

(defn truncate-display
  "Return `s` adjusted to occupy exactly `n` terminal columns: clipped (with a
   trailing `…`) when wider, space-padded on the right when narrower. Never
   splits an SGR escape sequence — escapes are copied through whole and don't
   count toward width. A wide glyph that would straddle the boundary is dropped
   (its cell is space-padded) rather than half-drawn."
  [s n]
  (if (<= n 0)
    ""
    (let [s   (str s)
          len (.length s)
          sb  (StringBuilder.)]
      ;; Reserve one column for the ellipsis only if we actually overflow.
      (loop [i 0 w 0]
        (if (>= i len)
          ;; consumed all input — pad to n
          (do (dotimes [_ (- n w)] (.append sb \space))
              (.toString sb))
          (let [c (.charAt s i)]
            (cond
              ;; copy SGR/CSI escape through verbatim (zero width)
              (= c ESC-CHAR)
              (let [j (if (and (< (inc i) len) (= (.charAt s (inc i)) \[))
                        (loop [k (+ i 2)]
                          (if (>= k len)
                            k
                            (let [d (.charAt s k)]
                              (if (<= 0x40 (int d) 0x7E) (inc k) (recur (inc k))))))
                        (inc i))]
                (.append sb (.subSequence s i j))
                (recur j w))
              :else
              (let [pair? (and (Character/isHighSurrogate c) (< (inc i) len)
                            (Character/isLowSurrogate (.charAt s (inc i))))
                    cp0   (if pair? (Character/toCodePoint c (.charAt s (inc i))) (int c))
                    ;; Neutralize C0 control chars (\n \r \t and the rest of
                    ;; 0x00-0x1F, plus DEL 0x7F) — except ESC, handled above —
                    ;; to a single space so no caller can inject a cursor-moving
                    ;; char into a composited cell. SGR escapes already bypass
                    ;; this branch and keep their zero-width handling.
                    control? (and (not pair?) (or (< cp0 0x20) (= cp0 0x7F)))
                    c     (if control? \space c)
                    cp    (if control? (int \space) cp0)
                    cw    (if (wide-codepoint? cp) 2 1)
                    nxt   (if pair? (+ i 2) (inc i))]
                ;; Is this glyph the last visible content? (only escapes may
                ;; follow). If so we may use the final column; otherwise we must
                ;; leave one column for a possible ellipsis.
                (let [rest-content?
                      (loop [k nxt]
                        (cond
                          (>= k len) false
                          (= (.charAt s k) ESC-CHAR)
                          (let [j (if (and (< (inc k) len) (= (.charAt s (inc k)) \[))
                                    (loop [m (+ k 2)]
                                      (if (>= m len) m
                                        (let [d (.charAt s m)]
                                          (if (<= 0x40 (int d) 0x7E) (inc m) (recur (inc m))))))
                                    (inc k))]
                            (recur j))
                          :else true))
                      limit (if rest-content? (dec n) n)]
                  (cond
                    ;; fits within the budget (reserving ellipsis col if needed)
                    (<= (+ w cw) limit)
                    (do (.append sb c)
                        (when pair? (.append sb (.charAt s (inc i))))
                        (recur nxt (+ w cw)))
                    ;; overflow — pad up to n-1 then ellipsis, stop.
                    :else
                    (do
                      (dotimes [_ (- (dec n) w)] (.append sb \space))
                      (when (<= 1 n) (.append sb \…))
                      (.toString sb))))))))))))

(defn- box-frame
  "Border glyph set for a box. `focus?` selects the heavy frame so focus reads
   even with color off. Returns a map of the eight pieces."
  [focus?]
  (if focus?
    {:tl "▛" :tr "▜" :bl "▙" :br "▟" :h "▀" :v-left "▌" :v-right "▐" :h-bot "▄"}
    {:tl "╭" :tr "╮" :bl "╰" :br "╯" :h "─" :v-left "│" :v-right "│" :h-bot "─"}))

(defn draw-box
  "Append a bordered pane to StringBuilder `buf` at the given rectangle.

   Opts:
   * `:row` `:col`  1-based top-left corner (col may be > 1 — right pane).
   * `:w` `:h`      total box size in columns/rows (borders included).
   * `:title`       string embedded left-aligned in the top border (optional).
   * `:scroll`      `{:pos N :total M}` → a right-aligned `⇅ N/M` indicator in
                    the top border (optional).
   * `:focus?`      heavy frame + `:border-focus` color when true.
   * `:theme`       semantic theme map (from `theme-for`); colors borders/title.
   * `:body-lines`  seq of strings, one per interior row; clipped to the
                    interior `(w-2)×(h-2)` and `truncate-display`-padded. Rows
                    beyond content are blanked.

   Every interior + border cell is positioned with an absolute `move-to-s`, so
   the box draws correctly at any column offset. Returns `buf`."
  [^StringBuilder buf {:keys [row col w h title scroll focus? theme body-lines]}]
  (when (and (>= w 2) (>= h 2))
    (let [theme    (or theme theme/theme-none)
          {:keys [tl tr bl br v-left v-right] hbar :h} (box-frame focus?)
          bcode    (get theme (if focus? :border-focus :border-dim))
          inner-w  (- w 2)
          ;; --- top border with embedded title + scroll indicator ---
          scroll-s (when (and scroll (:total scroll))
                     (str "⇅ " (:pos scroll) "/" (:total scroll)))
          title-s  (when (and title (seq (str title)))
                     (str " " title " "))
          ;; build the run of horizontal glyphs that fills inner-w columns,
          ;; with the (plain-text) title at the left and scroll at the right.
          tw       (if title-s (display-width title-s) 0)
          sw       (if scroll-s (+ 2 (display-width scroll-s)) 0) ;; spaces around
          fill     (max 0 (- inner-w tw sw))
          top-mid  (str (or title-s "")
                     (apply str (repeat fill hbar))
                     (when scroll-s (str " " scroll-s " ")))
          ;; truncate-display the run to exactly inner-w (defensive)
          top-mid  (truncate-display top-mid inner-w)
          top      (str tl top-mid tr)]
      (.append buf (move-to-s row col))
      (.append buf (theme/sgr-wrap bcode top))
      ;; --- interior rows ---
      (dotimes [i (- h 2)]
        (let [line (nth body-lines i nil)
              cell (truncate-display (or line "") inner-w)
              r    (+ row 1 i)]
          (.append buf (move-to-s r col))
          (.append buf (theme/sgr-wrap bcode v-left))
          (.append buf cell)
          (.append buf (move-to-s r (+ col w -1)))
          (.append buf (theme/sgr-wrap bcode v-right))))
      ;; --- bottom border ---
      (let [{:keys [h-bot]} (box-frame focus?)
            bot (str bl (apply str (repeat inner-w h-bot)) br)]
        (.append buf (move-to-s (+ row h -1) col))
        (.append buf (theme/sgr-wrap bcode bot)))))
  buf)

(def header-h
  "Header strip height: top border + 3 content rows (chart line, breadcrumb,
   sibling/metrics line — `header-lines` returns three) + bottom border = a
   5-row box drawn separately above the body."
  5)

(def footer-h 1)

(def narrow-threshold
  "Below this terminal width, drop the two-pane split and stack a single body
   pane (the legacy single-column behavior)."
  100)

(def live-min-w
  "Minimum width (incl. borders) the LIVE pane should get in a two-pane split."
  40)

(defn layout
  "Compute non-overlapping rectangles that tile a `term-w × term-h` screen for
   the mission-control frame. Returns a map:

     {:mode      :two-pane | :narrow | :maximized
      :term-w N  :term-h N
      :header  {:row :col :w :h}
      :footer  {:row :col :w :h}
      :live    {…}        ;; present unless maximized to :log
      :log     {…}        ;; present unless maximized to :live
      :body    {…}}       ;; present only when :maximized? (the focused pane)

   Rules:
   * Header strip = `header-h` rows at the top; footer = `footer-h` row at the
     bottom; the body fills the rows between.
   * `term-w` < `narrow-threshold` ⇒ `:narrow`: a single full-width body pane.
   * `maximized?` ⇒ `:maximized`: one body rect (= the focused pane) fills the
     body; `:focus` (`:live`/`:log`) selects which key the body is reported as.
   * otherwise `:two-pane`: body split ~50/50 (LIVE left with a `live-min-w`
     floor, LOG gets the remainder)."
  [{:keys [term-w term-h focus maximized?]}]
  (let [term-w   (max 1 (or term-w 80))
        term-h   (max 1 (or term-h 24))
        focus    (or focus :log)
        hh       (min header-h (max 0 (- term-h footer-h)))
        body-row (+ 1 hh)
        body-h   (max 0 (- term-h hh footer-h))
        header   {:row 1 :col 1 :w term-w :h hh}
        footer   {:row term-h :col 1 :w term-w :h footer-h}
        base     {:term-w term-w :term-h term-h :header header :footer footer}]
    (cond
      maximized?
      (let [body {:row body-row :col 1 :w term-w :h body-h}]
        (assoc base :mode :maximized :focus focus :body body
          (if (= focus :live) :live :log) body))

      (< term-w narrow-threshold)
      (let [body {:row body-row :col 1 :w term-w :h body-h}]
        (assoc base :mode :narrow :focus focus :body body
          (if (= focus :live) :live :log) body))

      :else
      (let [live-w (max live-min-w (quot term-w 2))
            ;; never let LIVE eat the whole width; LOG keeps at least live-min-w
            live-w (min live-w (- term-w live-min-w))
            log-col (+ 1 live-w)
            log-w   (- term-w live-w)]
        (assoc base :mode :two-pane :focus focus
          :live {:row body-row :col 1 :w live-w :h body-h}
          :log  {:row body-row :col log-col :w log-w :h body-h})))))
