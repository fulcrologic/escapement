(ns escapement.tui.pager-render-test
  "Faithful artifact-pager rendering (task 001): embedded CSI/SGR/OSC escapes are
   neutralized to inert literal text (#1), a lone ESC does not eat the following
   char (#5), and `wrap-line` wraps by DISPLAY width on GRAPHEME-CLUSTER
   boundaries so emoji/CJK lines never overrun the target column (#4 JLine side).

   Control bytes are built with `(char N)` — a literal ESC won't survive in
   source (see `escapement.examples.stress.edge`)."
  (:require
    [clojure.string :as str]
    [escapement.tui.compositor :as cmp]
    [escapement.tui.inspector :as insp]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(def ESC (str (char 27)))                                   ;; \e
(def BEL (str (char 7)))                                    ;; \a
(def ZWJ (str (char 0x200D)))

;; visible-string helpers
(def family (str "👨" ZWJ "👩" ZWJ "👧")) ;; 👨‍👩‍👧
(def skin   (str "👋🏽"))               ;; 👋🏽 wave + skin tone
(def cjk    "日本語テキスト")

(defn- has-esc? [s] (str/includes? (str s) ESC))

(specification "sanitize-content — ESC sequences become inert literal text (#1/#5)"
  (component "CSI / SGR is neutralized (no raw ESC survives)"
    (let [out (cmp/sanitize-content (str "red" ESC "[31m" "TEXT" ESC "[0m" "end"))]
      (assertions
        "no raw ESC byte remains"          (has-esc? out) => false
        "the visible text is preserved"    (str/includes? out "red") => true
        (str/includes? out "TEXT")         => true
        (str/includes? out "end")          => true
        "the escape shows as inert ^[ literal" (str/includes? out "^[[31m") => true)))

  (component "cursor-move CSI is neutralized"
    (let [out (cmp/sanitize-content (str "A" ESC "[2A" "B"))]
      (assertions
        "no raw ESC"                       (has-esc? out) => false
        "both letters survive"             (str/includes? out "A") => true
        (str/includes? out "B")            => true)))

  (component "OSC title-set is neutralized (no title hijack)"
    (let [out (cmp/sanitize-content (str "x" ESC "]0;PWNED" BEL "y"))]
      (assertions
        "no raw ESC"                       (has-esc? out) => false
        "no raw BEL"                       (str/includes? out BEL) => false
        "surrounding text preserved"       (str/includes? out "x") => true
        (str/includes? out "y")            => true
        "the OSC payload is shown as literal text" (str/includes? out "PWNED") => true)))

  (component "OSC terminated by ST (ESC \\) is neutralized"
    (let [out (cmp/sanitize-content (str "p" ESC "]2;hi" ESC "\\" "q"))]
      (assertions
        "no raw ESC"                       (has-esc? out) => false
        "text on both sides survives"      (str/includes? out "p") => true
        (str/includes? out "q")            => true)))

  (component "lone ESC does NOT eat the following char (#5)"
    (let [out (cmp/sanitize-content (str "before" ESC "X" "after"))]
      (assertions
        "no raw ESC"                       (has-esc? out) => false
        "the char after a lone ESC is kept" (str/includes? out "X") => true
        "shown as ^[ placeholder + X"      (str/includes? out "^[X") => true
        "trailing text preserved"          (str/includes? out "after") => true)))

  (component "other C0 controls become a visible space; CJK is untouched"
    (let [out (cmp/sanitize-content (str "a" (char 0) (char 1) cjk))]
      (assertions
        "no raw NUL/SOH"                   (str/includes? out (str (char 0))) => false
        "CJK passes through verbatim"      (str/includes? out cjk) => true))))

(specification "grapheme-clusters — keeps ZWJ/skin-tone/CJK intact"
  (assertions
    "a ZWJ family is ONE cluster"
    (count (cmp/grapheme-clusters family)) => 1
    "wave + skin-tone is ONE cluster"
    (count (cmp/grapheme-clusters skin)) => 1
    "CJK splits into one cluster per ideograph"
    (count (cmp/grapheme-clusters cjk)) => (count cjk)
    "round-trips losslessly"
    (str/join (cmp/grapheme-clusters (str family skin cjk))) => (str family skin cjk)))

(let [wrap-line @#'insp/wrap-line]
  (specification "wrap-line — display-width + grapheme-aware (#4)"
    (component "no physical row exceeds the target DISPLAY width"
      (let [w     8
            input (str "hello world " cjk " " family " " skin
                    " supercalifragilisticexpialidocious")
            rows  (wrap-line w input)]
        (assertions
          "every wrapped row fits the column budget"
          (every? #(<= (cmp/display-width %) w) rows) => true
          "produced more than one row"
          (> (count rows) 1) => true)))

    (component "a ZWJ family is never split across a wrap boundary"
      ;; A ZWJ family is ONE grapheme cluster but several wide codepoints
      ;; (display-width ~6). The wrapper keeps the cluster whole — it must never
      ;; orphan a ZWJ at a row edge — even though a cluster wider than the
      ;; column budget is, by design, placed un-split (the documented residual).
      (let [w    (* 2 (cmp/display-width family))           ;; room for two families
            rows (wrap-line w (str family " " family " " family))]
        (assertions
          "no row begins with an orphaned ZWJ (clusters stay intact)"
          (every? #(not (str/starts-with? % ZWJ)) rows) => true
          "no row ends with a dangling ZWJ"
          (every? #(not (str/ends-with? % ZWJ)) rows) => true
          "the families round-trip (none lost in wrapping)"
          (= 3 (count (re-seq (re-pattern (java.util.regex.Pattern/quote family))
                        (str/join rows)))) => true)))

    (component "a single overlong no-space word is hard-split by clusters"
      (let [rows (wrap-line 5 (apply str (repeat 40 cjk)))]
        (assertions
          "every row within budget"
          (every? #(<= (cmp/display-width %) 5) rows) => true
          "content preserved across the split"
          (str/join rows) => (str/replace (apply str (repeat 40 cjk)) #"\s" ""))))

    (component "a short line passes through unwrapped"
      (assertions
        (wrap-line 80 "short line") => ["short line"]))))
