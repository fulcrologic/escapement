(ns escapement.tui.theme
  "Pure color/theme layer for the terminal UI (terminal-UI add-on; required only
   by the `escapement.tui` facade + sibling modules — never by engine core).

   Owns the ANSI SGR primitives (`esc`, `sgr-wrap`, `reset-attrs-s`,
   `ESC-CHAR`), the capability detector (`color-capability`), the semantic theme
   maps + `theme-for`/`paint`/`status-color`/`theme-color?`, and the per-role
   hue allocator (`allocate-color`/`color-for`/`role-sgr`/`role-sgr-themed`).

   Everything here is a pure function of its arguments (the role allocator takes
   the TUI state map `s` as a parameter and returns updated state — it keeps NO
   module-local mutable state). No dependency on the TUI state atom, JLine, or
   any JVM-only API; bb/SCI-safe (note: `\\033` char literals are rejected by
   SCI, hence `ESC-CHAR = (char 27)`)."
  (:require
    [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; ANSI SGR primitives
;; ---------------------------------------------------------------------------

(def CSI "\033[")
(def ESC-CHAR (char 27))                                    ;; \e — SCI rejects \033 char literals
(defn esc [s] (str CSI s))
(def reset-attrs-s (esc "0m"))

(defn sgr-wrap
  "Wrap `body` in the SGR escape for `code` (digits, e.g. \"36\" / \"38;5;71\")
   plus a reset. When `code` is nil or empty, returns `body` unchanged (no-op).
   This is the single primitive every colorizer in the file should funnel
   through."
  [code body]
  (if (or (nil? code) (= "" code))
    (str body)
    (str (esc (str code "m")) body reset-attrs-s)))

;; ---------------------------------------------------------------------------
;; Per-role palette (legacy invokeid hue allocation)
;; ---------------------------------------------------------------------------

;; ANSI palette for invokeid color allocation. Round-robin in this order.
;; SGR codes — start with `(esc "...m")` and reset with reset-attrs-s.
(def invokeid-palette
  ["36"                                                     ;; cyan
   "35"                                                     ;; magenta
   "33"                                                     ;; yellow
   "32"                                                     ;; green
   "34"                                                     ;; blue
   "91"                                                     ;; bright red
   "96"                                                     ;; bright cyan
   "95"                                                     ;; bright magenta
   "93"                                                     ;; bright yellow
   "92"                                                     ;; bright green
   ])

(def chart-color "90")                                      ;; bright black / dim grey
(def human-color "97")                                      ;; bright white
(def error-color "31")                                      ;; red
(def debug-color "90")                                      ;; dim

;; ---------------------------------------------------------------------------
;; Semantic color theme — capability-aware (256 → 16 → none)
;;
;; All color in the redesign routes through this one switch so NO_COLOR /
;; non-tty / dumb terminals degrade in a single place. A theme is a map of
;; semantic keys → SGR code strings (the digits between `\e[` and `m`, e.g.
;; "38;5;110" or "36"; "" means "no color"). `paint` wraps a body in the SGR
;; + reset, or returns it unchanged when the code is empty.
;; ---------------------------------------------------------------------------

(defn color-capability
  "Pure capability detector. Returns one of `:truecolor` `:256` `:16` `:none`
   from the environment and a `tty?` flag passed in by the caller (so this stays
   JVM/SCI-safe — no `System/console` call here).

   Rules (conservative):
   * `NO_COLOR` set (any value, per the no-color.org convention) ⇒ `:none`.
   * not a tty ⇒ `:none`.
   * `TERM` is nil / \"\" / \"dumb\" ⇒ `:none`.
   * `COLORTERM` truecolor/24bit ⇒ `:truecolor`.
   * `TERM` matching `*-256color* / *-direct*` ⇒ `:256`.
   * otherwise ⇒ `:16`.

   Two arities: `(color-capability tty?)` reads env via `System/getenv`;
   `(color-capability {:keys [no-color term colorterm]} tty?)` is the pure core
   used by tests (pass an explicit env map)."
  ([tty?]
   (color-capability {:no-color  (System/getenv "NO_COLOR")
                      :term      (System/getenv "TERM")
                      :colorterm (System/getenv "COLORTERM")}
     tty?))
  ([{:keys [no-color term colorterm]} tty?]
   (let [term (when term (str/lower-case term))
         ct   (when colorterm (str/lower-case colorterm))]
     (cond
       (some? no-color) :none
       (not tty?) :none
       (or (nil? term) (= "" term) (= "dumb" term)) :none
       (and ct (or (= "truecolor" ct) (= "24bit" ct))) :truecolor
       (or (str/includes? term "256color")
         (str/includes? term "-direct")) :256
       :else :16))))

;; 256-color (and truecolor; we map truecolor onto the same tasteful 256 ramp —
;; a low-saturation slate/teal palette). Foreground = "38;5;N".
(def theme-256
  {:border-dim       "38;5;240"                              ;; slate grey
   :border-focus     "38;5;111"                              ;; bright steel blue
   :title            "1;38;5;231"                            ;; bold near-white
   :chart-name       "1;38;5;231"                            ;; bold near-white
   :session-id       "38;5;244"                              ;; dim grey
   :timestamp        "38;5;244"                              ;; dim grey
   :metric           "38;5;150"                              ;; soft green accent
   :phase-current    "1;38;5;117"                            ;; bold sky-blue accent
   :phase-done       "38;5;108"                              ;; dim green
   :phase-upcoming   "38;5;240"                              ;; dim grey
   :status/streaming "38;5;51"                               ;; bright cyan
   :status/done      "38;5;71"                               ;; green
   :status/waiting   "38;5;179"                              ;; yellow/amber
   :status/error     "38;5;167"                              ;; red
   :status/idle      "38;5;240"                              ;; dim grey
   :bar-filled       "38;5;71"                               ;; green
   :bar-empty        "38;5;238"                              ;; very dim grey
   ;; transcript direction tags — bold fg + colored bg so SENT vs REPLY pop
   :sent-tag         "1;38;5;231;48;5;24"                    ;; bold white on steel-blue bg
   :reply-tag        "1;38;5;231;48;5;65"})                  ;; bold white on muted-green bg

;; 16-color fallback — standard/bright SGR foreground codes only.
(def theme-16
  {:border-dim       "90"                                    ;; bright black (grey)
   :border-focus     "94"                                    ;; bright blue
   :title            "1;97"                                  ;; bold bright white
   :chart-name       "1;97"                                  ;; bold bright white
   :session-id       "90"                                    ;; grey
   :timestamp        "90"                                    ;; grey
   :metric           "32"                                    ;; green
   :phase-current    "1;96"                                  ;; bold bright cyan
   :phase-done       "32"                                    ;; green
   :phase-upcoming   "90"                                    ;; grey
   :status/streaming "96"                                    ;; bright cyan
   :status/done      "32"                                    ;; green
   :status/waiting   "33"                                    ;; yellow
   :status/error     "31"                                    ;; red
   :status/idle      "90"                                    ;; grey
   :bar-filled       "32"                                    ;; green
   :bar-empty        "90"                                    ;; grey
   ;; transcript direction tags — bold white fg on a colored bg
   :sent-tag         "1;97;44"                               ;; bold white on blue bg
   :reply-tag        "1;97;42"})                             ;; bold white on green bg

(def theme-keys
  "Every semantic key a theme must define."
  (keys theme-256))

(def theme-none
  "All semantic keys mapped to the empty SGR code (no color)."
  (zipmap theme-keys (repeat "")))

(defn theme-for
  "Construct the semantic theme map for a given capability keyword
   (`:truecolor` `:256` `:16` `:none`). `:none` ⇒ every key is the empty
   string (so `paint` becomes a no-op). `:truecolor` reuses the 256 ramp."
  [capability]
  (case capability
    (:truecolor :256) theme-256
    :16 theme-16
    :none theme-none
    ;; unknown ⇒ safest = no color
    theme-none))

(defn paint
  "Wrap `body` in the SGR for semantic theme key `k`, or return `body` unchanged
   when the theme has no color for that key (`:none` capability, unknown key)."
  [theme k body]
  (sgr-wrap (get theme k) body))

(defn theme-color?
  "True when `theme` actually emits color (i.e. not the `:none`/NO_COLOR theme).
   Used to gate the legacy role-hue path (`role-sgr`, which is TERM-gated, not
   theme-gated) so a `:none` theme — e.g. under `NO_COLOR` or non-tty — emits
   ZERO escape codes everywhere, including role-colored LIVE rows / LOG lines."
  [theme]
  (boolean (seq (get theme :border-dim ""))))

(defn status-color
  "SGR code string for a live/status keyword, resolved through `theme`:
   streaming→cyan, done→green, waiting→yellow, error→red, idle/exit→dim.
   Unknown statuses fall back to idle (dim). Returns \"\" under `:none`."
  [theme status]
  (let [k (case status
            :streaming :status/streaming
            :done      :status/done
            :waiting   :status/waiting
            :error     :status/error
            (:idle :exit) :status/idle
            :status/idle)]
    (get theme k "")))

;; ---------------------------------------------------------------------------
;; Per-role hue allocation (operates on the TUI state map `s`, purely)
;; ---------------------------------------------------------------------------

(defn ansi-supported?
  "Cheap guard so colored output degrades on dumb terminals."
  []
  (let [t (System/getenv "TERM")]
    (not (or (nil? t) (= "dumb" t) (= "" t)))))

(defn allocate-color
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

(defn color-for
  "Looks up the SGR code (digits, e.g. `\"36\"`) for the given source. For
   invokeid string sources this reads from the allocator; for the well-known
   keyword sources, returns a fixed code. Returns nil when colors are not
   supported (caller will skip the wrap)."
  [s source]
  (when (ansi-supported?)
    (cond
      (string? source) (get-in s [:invokeid-colors source])
      (= :chart source) chart-color
      (= :human source) human-color
      (= :error source) error-color
      (= :debug source) debug-color
      :else nil)))

(defn role-sgr
  "Return the per-role hue as an SGR code string (digits, e.g. \"36\") for the
   given source. `s` is the TUI state, `source` is an invokeid string or a
   well-known keyword (`:chart` `:human` `:error` `:debug`). This is the same
   hue `color-for` uses for the LIVE rows, exposed so the LOG pane can color a
   log line by its role and the eye links agent ↔ log line.

   Returns \"\" when no color is allocated / colors unsupported, so it composes
   directly with `sgr-wrap`/`paint` (both treat \"\" as a no-op)."
  [s source]
  (or (color-for s source) ""))

(defn role-sgr-themed
  "Like `role-sgr` but honors `theme`: returns \"\" under a `:none` theme so the
   whole frame is escape-free under NO_COLOR / non-tty. (`role-sgr` itself only
   gates on TERM, independent of capability.)"
  [theme s source]
  (if (theme-color? theme) (role-sgr s source) ""))
