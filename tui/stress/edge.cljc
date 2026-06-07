(ns stress.edge
  "TUI render stress — EDGE tier (content edge cases).

   Deterministic and MODEL-FREE. On entry it writes a battery of artifacts whose
   BYTES are deliberately pathological, then emits a :artifact/captured line for
   each so the scrollback also carries the tricky names. The artifacts view (`a`)
   renders the files; opening one pages the exact bytes. The point is to see how
   each renderer (JLine + OpenTUI) handles content that breaks naive width math,
   cursor positioning, and escape handling.

   The battery (one artifact per case, plus one combined file):
     * unicode-emoji   — emoji incl. ZWJ sequences + skin-tone modifiers + flags
                         (wide/zero-width grapheme clusters; width != codepoints)
     * cjk             — Chinese/Japanese/Korean (East-Asian WIDE glyphs)
     * rtl             — Arabic/Hebrew (right-to-left + bidi reordering)
     * zero-width      — zero-width space/joiner/non-joiner + combining marks
     * ansi-escapes    — raw ANSI SGR + cursor-move sequences EMBEDDED in content
                         (a renderer that doesn't sanitize will be hijacked)
     * control-chars   — bare CR TAB BS FF VT, NUL, BEL, and a lone ESC
     * box-drawing     — box/line-drawing + block elements (alignment torture)
     * long-no-space   — one ~8000-char 'word' with no break opportunity
     * empty           — a zero-byte artifact (empty render path)
     * mixed           — all of the above interleaved on shared lines

   No backend, no API key, no network:

     bb -m escapement.cli run stress.edge/agent

   NOTE: this writes raw control bytes (NUL, BEL, ESC, etc.) into files on disk;
   that is intentional — the renderers are what we're testing. `cat`-ing these
   files in your own terminal may garble IT; inspect via the TUI artifact pager.
   Control bytes are built programmatically (see ESC/NUL/... below) so the SOURCE
   file stays clean ASCII and parses safely under SCI."
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements
     :refer [final on-entry script state transition]]))

;; --- raw control bytes, built programmatically so the SOURCE stays clean ASCII
;; (embedding literal NUL/ESC/BEL in a .cljc file is fragile for parsers/editors).
(def ^:private ESC  (str (char 27)))
(def ^:private NUL  (str (char 0)))
(def ^:private BEL  (str (char 7)))
(def ^:private BS   (str (char 8)))
(def ^:private VT   (str (char 11)))
(def ^:private ZWSP (str (char 0x200B)))                    ; zero-width space
(def ^:private ZWJ  (str (char 0x200D)))                    ; zero-width joiner
(def ^:private ZWNJ (str (char 0x200C)))                    ; zero-width non-joiner
(def ^:private BOM  (str (char 0xFEFF)))                    ; byte-order mark

;; --- edge-case content (deterministic) ---------------------------------------

(def ^:private unicode-emoji
  (str "Emoji + grapheme clusters (width != codepoint count):\n"
    "plain: 😀 🎉 🚀 🧨 ✨\n"
    "ZWJ family: 👨‍👩‍👧‍👦  (one cluster, many codepoints)\n"
    "skin tone: 👍🏽 👋🏿 🤝🏻\n"
    "flags (regional indicators): 🇺🇸 🇯🇵 🇸🇦 🇮🇱\n"
    "keycap: 1️⃣ 2️⃣ #️⃣\n"
    "mixed with ascii to test alignment | end\n"))

(def ^:private cjk
  (str "East-Asian WIDE glyphs (each ~2 cells):\n"
    "中文：你好，世界。这是一段用于测试渲染宽度的文本。\n"
    "日本語：こんにちは世界。レンダリング幅のテスト。\n"
    "한국어: 안녕하세요 세계. 렌더링 폭 테스트입니다.\n"
    "mixed 半角full全角 ABC中123文 | end\n"))

(def ^:private rtl
  (str "Right-to-left + bidi reordering:\n"
    "Arabic: مرحبا بالعالم هذا نص لاختبار الاتجاه\n"
    "Hebrew: שלום עולם זהו טקסט לבדיקת כיווניות\n"
    "mixed LTR+RTL: hello مرحبا world עולם 123 end\n"))

(def ^:private zero-width
  (str "Zero-width + combining marks (invisible / stacking):\n"
    "ZWSP between>here" ZWSP "<there\n"
    "ZWJ a" ZWJ "b ZWNJ a" ZWNJ "b\n"
    "combining: é à ô ñ  (e-acute a-grave o-circ n-tilde)\n"
    "stacked combining: á̂̃̄̅ (many marks on one base)\n"
    "BOM at line start:" BOM "after-bom\n"))

(def ^:private ansi-escapes
  (str "Embedded ANSI escape sequences (should be shown/neutralized, not obeyed):\n"
    "SGR red bg: " ESC "[41mTHIS SHOULD NOT PAINT YOUR TERMINAL" ESC "[0m normal\n"
    "bold+underline: " ESC "[1;4mstyled?" ESC "[0m\n"
    "cursor up + clear: " ESC "[2A" ESC "[2K (must not move the real cursor)\n"
    "256-color: " ESC "[38;5;201mmagenta?" ESC "[0m\n"
    "OSC title hijack: " ESC "]0;PWNED-TITLE" BEL " after\n"))

(def ^:private control-chars
  (str "Control characters in content (each must not corrupt the layout):\n"
    "carriage-return mid:abc\rXYZ (CR should not overwrite onscreen)\n"
    "tabs:\tcol1\tcol2\tcol3\n"
    "backspace:abc" BS BS "X\n"
    "formfeed:before\fafter  vtab:before" VT "after\n"
    "NUL:before" NUL "after  BEL:before" BEL "after  lone-ESC:before" ESC "after\n"))

(def ^:private box-drawing
  (str "Box-drawing + block elements (alignment torture):\n"
    "┌───────────┬───────────┐\n"
    "│ left cell │ right cell│\n"
    "├───────────┼───────────┤\n"
    "│ 中文 wide │ ascii     │\n"
    "└───────────┴───────────┘\n"
    "blocks: ░▒▓█ ▁▂▃▄▅▆▇█ ▏▎▍▌▋▊▉█\n"
    "braille: ⠁⠂⠄⡀⢀⠠⠐⠈ ⣿⣾⣽⣻⢿⡿\n"))

(def ^:private long-no-space
  (str "One ~8000-char word with no break opportunity:\n"
    (apply str (repeat 8000 \W)) "\n"
    "after the giant word | end\n"))

(def ^:private mixed
  (str "Everything interleaved on shared lines (worst alignment case):\n"
    "row1: 中文 😀 " ESC "[31mred" ESC "[0m مرحبا ┌─┐ é " ZWSP " end\n"
    "row2: 한국어\t日本語\tABC\t123\t😀🇯🇵\n"
    "row3: שלום█▓▒░|tab\there|cr\rOVR|" (apply str (repeat 200 \Z)) "\n"))

(def ^:private artifacts
  (array-map
    "edge-unicode-emoji.txt"  unicode-emoji
    "edge-cjk.txt"            cjk
    "edge-rtl.txt"            rtl
    "edge-zero-width.txt"     zero-width
    "edge-ansi-escapes.txt"   ansi-escapes
    "edge-control-chars.txt"  control-chars
    "edge-box-drawing.txt"    box-drawing
    "edge-long-no-space.txt"  long-no-space
    "edge-empty.txt"          ""
    "edge-mixed.txt"          mixed))

(def ^:private write-edge-artifacts-script
  (script
    {:expr
     (fn [env _data]
       (let [sdir (:escapement/session-dir env)
             tfn  (:escapement/transcript-fn env)]
         (when sdir
           (let [adir (str sdir "/artifacts")]
             (.mkdirs (java.io.File. adir))
             (doseq [[fname content] artifacts]
               (spit (str adir "/" fname) content)
               (when tfn
                 (try (tfn {:event :artifact/captured
                            :data  {:name fname :bytes (count content)}})
                      (catch Throwable _ nil)))))))
       nil)}))

(def agent
  (chart/statechart
    {:initial :run :name "stress-edge"}
    (state {:id :run :initial :emit}
      (state {:id :emit}
        (on-entry {} write-edge-artifacts-script)
        ;; Eventless transition: after on-entry writes everything, finish.
        (transition {:target :finished}))
      (final {:id :finished}))))
