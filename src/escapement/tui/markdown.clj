(ns escapement.tui.markdown
  "Lightweight Markdown → themed terminal lines for the transcript body
   (terminal-UI add-on). PURE: no state atom, JLine, or JVM-only API; bb/SCI-safe.

   `render` turns a markdown string into a vector of already-styled, display-width
   correct lines (≤ `width` terminal columns each, SGR escapes are zero-width via
   `compositor/display-width`). The caller prefixes its own body-indent and pads
   each line to the box interior via `truncate-display`.

   Supported block constructs (line-oriented — each source line is its own block,
   so poem/verse line-breaks are preserved):
     # / ## / ###…  headings (md-h1/h2/h3)
     ``` lang        fenced code block → per-line generic syntax highlight
     >  quote        blockquote (italic, dim-green, `▏` gutter)
     - / * / 1.      list items (colored bullet + hanging indent)
     ---             horizontal rule
     (paragraph)     inline: **bold** *italic* _em_ `code` [text](url)

   Inline + code highlighting are best-effort/cosmetic: a line is the unit, so
   multi-line strings/comments inside code don't carry across lines. Under a
   `:none` theme every `paint` is a no-op ⇒ plain, still width-correct output."
  (:require
    [clojure.string :as str]
    [escapement.tui.compositor :as cmp]
    [escapement.tui.theme :as theme]))

;; ---------------------------------------------------------------------------
;; Inline span parsing → styled word tokens
;; ---------------------------------------------------------------------------

(defn- paint* [theme k s] (if k (theme/paint theme k s) (str s)))

(defn- inline-spans
  "Parse inline markdown in `s` into a vector of {:text :code} spans where
   `:code` is a theme key (or nil for plain). Order-preserving; non-nesting."
  [s]
  (let [s (str s)
        n (count s)]
    (loop [i 0, plain (StringBuilder.), out []]
      (let [flush (fn [out] (if (pos? (.length plain))
                              (let [v (conj out {:text (.toString plain) :code nil})]
                                (.setLength plain 0) v)
                              out))]
        (if (>= i n)
          (flush out)
          (let [delim (fn [open close code]
                        ;; returns [span end-idx] when a balanced delim closes, else nil
                        (let [ol (count open)]
                          (when (and (= open (subs s i (min n (+ i ol))))
                                  (not= open (subs s (+ i ol) (min n (+ i ol ol)))))
                            (let [e (str/index-of s close (+ i ol))]
                              (when (and e (> e (+ i ol -1)))
                                [{:text (subs s (+ i ol) e) :code code} (+ e (count close))])))))
                ;; link [text](url)
                link  (when (= \[ (nth s i))
                        (let [rb (str/index-of s "]" i)]
                          (when (and rb (< (inc rb) n) (= \( (nth s (inc rb))))
                            (let [rp (str/index-of s ")" rb)]
                              (when rp [{:text (subs s (inc i) rb) :code :md-link} (inc rp)])))))
                hit   (or link
                        (delim "**" "**" :md-bold)
                        (delim "__" "__" :md-bold)
                        (delim "`" "`" :md-code)
                        (delim "*" "*" :md-italic)
                        (delim "_" "_" :md-italic))]
            (if hit
              (let [[span e] hit]
                (recur e (StringBuilder.) (conj (flush out) span)))
              (do (.append plain (nth s i))
                  (recur (inc i) plain out)))))))))

(defn- spans->words
  "Flatten styled spans into word tokens {:w :code} (split on whitespace, code
   carried). Leading/trailing whitespace dropped — wrapping re-inserts spaces."
  [spans]
  (vec
    (mapcat (fn [{:keys [text code]}]
              (->> (str/split (str text) #"\s+")
                (remove str/blank?)
                (map (fn [w] {:w w :code code}))))
      spans)))

(defn- wrap-words
  "Greedy word-wrap styled tokens to `width` display columns. Each word is
   re-emitted with its own SGR (so a styled span surviving a line break stays
   styled). Returns a vector of styled lines. A word wider than the line is
   hard-split on display columns."
  [theme width words]
  (let [width (max 1 width)]
    (if (empty? words)
      []
      (loop [ws words, cur "", curw 0, out []]
        (if-let [{:keys [w code]} (first ws)]
          (let [ww (cmp/display-width w)]
            (cond
              ;; word longer than a whole line → hard char-split
              (> ww width)
              (let [room (max 1 (- width curw (if (zero? curw) 0 1)))
                    here (subs w 0 (min room (count w)))
                    left (subs w (count here))
                    piece (paint* theme code here)]
                (if (zero? curw)
                  (recur (cons {:w left :code code} (rest ws)) "" 0 (conj out piece))
                  (recur ws "" 0 (conj out cur))))
              ;; fits on current line
              (<= (+ curw (if (zero? curw) 0 1) ww) width)
              (recur (rest ws)
                (str cur (when (pos? curw) " ") (paint* theme code w))
                (+ curw (if (zero? curw) 0 1) ww) out)
              ;; flush + wrap
              :else
              (recur ws "" 0 (conj out cur))))
          (if (pos? (count cur)) (conj out cur) out))))))

(defn- inline-line
  "Render one inline-markdown source line to styled, wrapped lines at `width`
   columns, each continuation prefixed with `hang` (a plain indent string)."
  [theme width hang s]
  (let [hw    (cmp/display-width hang)
        words (spans->words (inline-spans s))
        lines (wrap-words theme (max 1 (- width hw)) words)]
    (if (empty? lines)
      [""]
      (vec (map #(str hang %) lines)))))

;; ---------------------------------------------------------------------------
;; Block renderer
;; ---------------------------------------------------------------------------

(def ^:private fence-re #"^\s*```+\s*(\S+)?\s*$")
(def ^:private heading-re #"^(#{1,6})\s+(.*)$")
(def ^:private rule-re #"^\s*([-*_])(?:\s*\1){2,}\s*$")
(def ^:private quote-re #"^\s*>\s?(.*)$")
(def ^:private list-re #"^(\s*)([-*+]|\d+[.)])\s+(.*)$")

(defn- markdown-lang?
  "True when a fence info-string marks the block as Markdown — models often wrap
   their whole reply in a ```markdown … ``` fence, which we render as markdown
   (not as a literal code block) so the document still renders."
  [lang]
  (and lang (#{"markdown" "md" "mkd" "mdown"} (str/lower-case lang))))

(declare render)

(defn- code-block-lines
  "Render a fenced code block's buffered `buf` lines as plain (NON-highlighted)
   code: a dim `▏` gutter + the code text in `:code-plain`, clipped to `width`.
   Per the user's request there is no per-token syntax highlighting."
  [theme width buf]
  (let [bar (theme/paint theme :code-fence "  ▏ ")]
    (mapv (fn [l]
            (cmp/truncate-display (str bar (paint* theme :code-plain l)) width))
      buf)))

(defn render
  "Render markdown `md` to a vector of themed, display-width-correct lines at
   `width` terminal columns. `theme` is a semantic theme map (`:none` ⇒ plain).

   Fenced code blocks are rendered as plain dim-gutter code with NO syntax
   highlighting; a ```markdown/```md fence renders its body AS markdown."
  [md theme width]
  (let [width (max 4 width)
        src   (str/split-lines (str md))]
    (loop [ls src, out [], fence nil]
      (if-let [line (first ls)]
        (cond
          ;; inside a fenced block: accumulate until the closing fence
          fence
          (if (re-matches fence-re line)
            ;; close: render the buffer (markdown-lang → recurse; else plain code)
            (let [{:keys [lang buf]} fence
                  rendered (if (markdown-lang? lang)
                             (render (str/join "\n" buf) theme width)
                             (code-block-lines theme width buf))]
              (recur (rest ls) (into out rendered) nil))
            (recur (rest ls) out (update fence :buf conj line)))

          ;; open a fenced block — buffer its body, render on close
          (re-matches fence-re line)
          (recur (rest ls) out {:lang (nth (re-matches fence-re line) 1) :buf []})

          ;; heading
          (re-matches heading-re line)
          (let [[_ hashes txt] (re-matches heading-re line)
                k (case (count hashes) 1 :md-h1 2 :md-h2 :md-h3)]
            (recur (rest ls)
              (into out (map #(theme/paint theme k %)
                          (inline-line theme width "" txt)))
              nil))

          ;; horizontal rule
          (re-matches rule-re line)
          (recur (rest ls)
            (conj out (theme/paint theme :md-rule
                        (cmp/truncate-display (apply str (repeat width "─")) width)))
            nil)

          ;; blockquote
          (re-matches quote-re line)
          (let [[_ txt] (re-matches quote-re line)
                bar (theme/paint theme :md-rule "▏ ")]
            (recur (rest ls)
              (into out (map #(str bar (theme/paint theme :md-quote %))
                          (inline-line theme (max 1 (- width 2)) "" txt)))
              nil))

          ;; list item
          (re-matches list-re line)
          (let [[_ lead marker txt] (re-matches list-re line)
                bullet (if (re-matches #"\d+[.)]" marker) marker "•")
                pre    (str lead (theme/paint theme :md-bullet bullet) " ")
                hang   (apply str (repeat (+ (count lead) (count bullet) 1) " "))
                lines  (inline-line theme width hang txt)]
            (recur (rest ls)
              (into out (map-indexed
                          (fn [i ln]
                            (if (zero? i)
                              (str pre (subs ln (min (count ln) (count hang))))
                              ln))
                          lines))
              nil))

          ;; blank line
          (str/blank? line)
          (recur (rest ls) (conj out "") nil)

          ;; paragraph line
          :else
          (recur (rest ls) (into out (inline-line theme width "" line)) nil))
        ;; end of input: flush an unterminated fence (a still-streaming reply
        ;; whose closing ``` hasn't arrived yet) so its body still renders.
        (if fence
          (into out (if (markdown-lang? (:lang fence))
                      (render (str/join "\n" (:buf fence)) theme width)
                      (code-block-lines theme width (:buf fence))))
          out)))))
