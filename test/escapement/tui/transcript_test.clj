(ns escapement.tui.transcript-test
  "Unit specs for the SENT/REPLY transcript block model + themed chat renderer
   (escapement.tui.transcript, task 006). Covers: chronological SENT/REPLY
   classification, system collapsible?/chars, streaming? on the pending reply,
   reply meta carry-through; and the renderer's width-correctness (no \\n / no
   bleed on multiline bodies), hairline-before-reply, collapsed-system preview,
   the expand flag, and the streaming cursor."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [escapement.tui.compositor :as cmp]
    [escapement.tui.theme :as theme]
    [escapement.tui.transcript :as transcript]
    [fulcro-spec.core :refer [=> assertions specification component]]))

(def ^:private ESC (str (char 27)))                          ;; SGR escape lead

(defn- has-esc? [s] (str/includes? (str s) ESC))

(defn- mk-handle
  "Minimal TUI handle: a synthetic single-invocation scrollback (request/system,
   one user turn, one assistant response) plus optional in-flight live text."
  [invokeid & {:keys [live-text live-tokens]}]
  {:state (atom
            (cond->
              {:scrollback
               [{:source invokeid
                 :ev {:event :llm/request :ts 1000
                      :data {:system-preview "You are a helpful assistant. Be terse."
                             :model "gemma3:1b"}}}
                {:source invokeid
                 :ev {:event :llm/user-message :ts 2000
                      :data {:text "Write a haiku about snow."}}}
                {:source invokeid
                 :ev {:event :llm/tool-result :ts 2500
                      :data {:tool "get_haiku" :content-preview "{\"haiku\":[\"a\"]}"}}}
                {:source invokeid
                 :ev {:event :llm/response :ts 3000
                      :data {:stop-reason :end_turn
                             :usage {:input-tokens 12 :output-tokens 17}
                             :output-tps 42.0 :model "gemma3:1b"
                             :content [{:type :text :text "First snow falling soft on the silent winter field a hush over all"}]}}}]
               :term-w 80}
              live-text
              (assoc-in [:live invokeid :sessions]
                {:s1 {:status :streaming :text live-text
                      :tokens (or live-tokens 5) :model "gemma3:1b"}})))
   :env (atom {:escapement/session-dir "/tmp/does-not-exist-xyz"})})

(specification "transcript-blocks — pure SENT/REPLY model"
  (let [h      (mk-handle "poet-1")
        blocks (transcript/transcript-blocks h "poet-1")]
    (component "classification + order"
      (assertions
        "system/user/tool are SENT, assistant is REPLY, in chronological order"
        (mapv (juxt :dir :label) blocks)
        => [[:sent "system"] [:sent "user"] [:sent "tool"] [:reply "assistant"]]))
    (component "system block"
      (let [sys (first blocks)]
        (assertions
          "is collapsible with a char count"
          (:collapsible? sys) => true
          (number? (:chars (:meta sys))) => true
          (pos? (:chars (:meta sys))) => true)))
    (component "reply meta"
      (let [rep (last blocks)]
        (assertions
          "carries stop reason + token in/out + t/s"
          (get-in rep [:meta :stop]) => :end_turn
          (get-in rep [:meta :in])   => 12
          (get-in rep [:meta :out])  => 17
          (get-in rep [:meta :tps])  => 42.0
          "not streaming for a finalized response"
          (boolean (get-in rep [:meta :streaming?])) => false))))
  (component "in-flight reply"
    (let [h      (mk-handle "poet-2" :live-text "Line 1: the opening" :live-tokens 14)
          blocks (transcript/transcript-blocks h "poet-2")
          stream (last blocks)]
      (assertions
        "the pending reply is a streaming? REPLY block with the growing body"
        (:dir stream) => :reply
        (get-in stream [:meta :streaming?]) => true
        (str/includes? (:body stream) "the opening") => true)))
  (component "defensive"
    (assertions
      "missing/blank handle never throws; yields a vector"
      (vector? (transcript/transcript-blocks {:state (atom {})} "x")) => true)))

(defn- no-bleed?
  "Every line has no newline and display-width exactly = iw."
  [lines iw]
  (every? (fn [ln]
            (and (not (str/includes? ln "\n"))
              (= iw (cmp/display-width ln))))
    lines))

(specification "transcript-lines — themed renderer (no-bleed, hairline, collapse)"
  (let [h      (mk-handle "poet-1")
        blocks (transcript/transcript-blocks h "poet-1")
        none   (theme/theme-for :none)
        iw     50
        lines  (transcript/transcript-lines blocks none iw {:expanded? false})]
    (component "width correctness (no bleed, no embedded newlines)"
      (assertions
        "every emitted line is exactly iw columns and newline-free"
        (no-bleed? lines iw) => true
        "long body wraps across multiple lines"
        (> (count lines) (count blocks)) => true))
    (component "structure"
      (let [text (str/join "\n" (mapv str/trimr lines))]
        (assertions
          "SENT and REPLY headers are present"
          (str/includes? text "SENT") => true
          (str/includes? text "REPLY") => true
          "a hairline rule of ─ precedes the reply"
          (str/includes? text "─────") => true)))
    (component "system block renders its FULL body (never a preview)"
      (let [text (str/join "\n" lines)]
        (assertions
          "system header still advertises a char count (info only)"
          (str/includes? text "chars") => true)))
    (component "NO_COLOR ⇒ zero escapes"
      (assertions
        "no SGR escape under the :none theme"
        (boolean (some has-esc? lines)) => false)))
  (component "expand flag shows full system body"
    (let [h      (mk-handle "poet-1")
          blocks (transcript/transcript-blocks h "poet-1")
          none   (theme/theme-for :none)
          collapsed (transcript/transcript-lines blocks none 50 {:expanded? false})
          expanded  (transcript/transcript-lines blocks none 50 {:expanded? true})]
      (assertions
        "expanding the system block yields at least as many lines"
        (>= (count expanded) (count collapsed)) => true)))
  (component "streaming cursor on the in-flight body"
    (let [h      (mk-handle "poet-2" :live-text "tokens arriving now into the buffer")
          blocks (transcript/transcript-blocks h "poet-2")
          none   (theme/theme-for :none)
          lines  (transcript/transcript-lines blocks none 60 {:expanded? false})
          text   (str/join "\n" lines)]
      (assertions
        "a ▏ cursor appears in the rendered stream"
        (str/includes? text "▏") => true
        "still width-correct with the cursor"
        (no-bleed? lines 60) => true))))

(specification "themed (colored) renderer emits escapes"
  (let [h      (mk-handle "poet-1")
        blocks (transcript/transcript-blocks h "poet-1")
        th     (theme/theme-for :256)
        lines  (transcript/transcript-lines blocks th 60 {:expanded? false})]
    (assertions
      "under a color theme, headers carry SGR escapes"
      (boolean (some has-esc? lines)) => true
      "and remain width-correct (escapes are zero-width)"
      (no-bleed? lines 60) => true)))

(defn- mk-long-system-handle
  "Handle whose system prompt is long + multi-line, so we can prove the FULL
   body renders (wrapped), not a 1-line preview."
  [invokeid system]
  {:state (atom
            {:scrollback
             [{:source invokeid
               :ev {:event :llm/request :ts 1000
                    :data {:system-preview system :model "gemma3:1b"}}}
              {:source invokeid
               :ev {:event :llm/response :ts 3000
                    :data {:stop-reason :end_turn
                           :usage {:input-tokens 9 :output-tokens 3}
                           :content [{:type :text :text "ok"}]}}}]
             :term-w 80})
   :env (atom {:escapement/session-dir "/tmp/does-not-exist-xyz"})})

(specification "ISSUE 1 — full system body, never truncated to a preview"
  (let [;; long multi-line system prompt; every distinct sentinel must appear
        system  (str "SYSLINE-ALPHA the first directive sentence here. "
                  "SYSLINE-BETA a second very long instruction that should "
                  "wrap across several physical lines because it exceeds the "
                  "interior width by a comfortable margin and keeps going. "
                  "SYSLINE-GAMMA a third paragraph.\n"
                  "SYSLINE-DELTA on its own logical line.\n"
                  "SYSLINE-OMEGA the very last line of the system prompt.")
        h       (mk-long-system-handle "poet-9" system)
        blocks  (transcript/transcript-blocks h "poet-9")
        none    (theme/theme-for :none)
        iw      50
        lines   (transcript/transcript-lines blocks none iw {:expanded? false})
        text    (str/join "\n" lines)]
    (assertions
      "the FULL system body is present (first AND last sentinel), not a preview"
      (str/includes? text "SYSLINE-ALPHA") => true
      (str/includes? text "SYSLINE-DELTA") => true
      (str/includes? text "SYSLINE-OMEGA") => true
      "long body wraps across many lines (full content, wrapped)"
      (> (count lines) 8) => true
      "still width-correct (no bleed) and escape-free under :none"
      (no-bleed? lines iw) => true
      (boolean (some has-esc? lines)) => false)))

(specification "ISSUE 2 — SENT/REPLY direction tags stand out"
  (let [h       (mk-handle "poet-1")
        blocks  (transcript/transcript-blocks h "poet-1")
        none    (theme/theme-for :none)
        th      (theme/theme-for :256)
        ;; isolate the header lines (those carrying SENT / REPLY)
        sent-256  (first (filter #(str/includes? % "SENT")
                           (transcript/transcript-lines blocks th 70 {})))
        reply-256 (first (filter #(str/includes? % "REPLY")
                           (transcript/transcript-lines blocks th 70 {})))
        sent-none  (first (filter #(str/includes? % "SENT")
                            (transcript/transcript-lines blocks none 70 {})))
        reply-none (first (filter #(str/includes? % "REPLY")
                            (transcript/transcript-lines blocks none 70 {})))]
    (assertions
      "under :256 the SENT label carries SGR styling (bold/background)"
      (has-esc? sent-256) => true
      (has-esc? reply-256) => true
      "SENT and REPLY use DISTINCT tag styling (different bg codes)"
      (= (:sent-tag th) (:reply-tag th)) => false
      "under :none zero escapes, but the SENT/REPLY text remains"
      (has-esc? sent-none) => false
      (has-esc? reply-none) => false
      (str/includes? sent-none "SENT") => true
      (str/includes? reply-none "REPLY") => true)))

(specification "wrap-display — display-width word wrap"
  (assertions
    "wraps long text without exceeding width (visible cols)"
    (every? #(<= (cmp/display-width %) 20)
      (transcript/wrap-display 20 "  "
        "the quick brown fox jumps over the lazy dog repeatedly today")) => true
    "blank input yields no lines"
    (transcript/wrap-display 20 "  " "   ") => []
    "preserves the leading indent on each physical line"
    (every? #(str/starts-with? % "  ")
      (transcript/wrap-display 20 "  " "alpha beta gamma delta epsilon zeta eta")) => true))

(specification "back-compat fmt-transcript-event"
  (assertions
    "folds non-turn events to empty string"
    (transcript/fmt-transcript-event nil {:event :llm/start :ts 0 :data {}}) => ""
    "renders a user-message as a SENT turn"
    (str/includes?
      (transcript/fmt-transcript-event nil {:event :llm/user-message :ts 0
                                            :data {:text "hello"}})
      "hello") => true))

(specification "invocation-transcript-text — inspector pager seam (plain)"
  (let [h   (mk-handle "poet-1")
        txt (transcript/invocation-transcript-text h "poet-1")]
    (assertions
      "non-empty string"
      (and (string? txt) (pos? (count txt))) => true
      "contains both lanes and the body content"
      (str/includes? txt "SENT") => true
      (str/includes? txt "REPLY") => true
      (str/includes? txt "haiku") => true
      "is escape-free (survives the pager re-wrap)"
      (has-esc? txt) => false)))

(specification "blob reader cache — content-addressed blobs read once (BUG 2C)"
  (let [dir   (str (System/getProperty "java.io.tmpdir") "/esc-blob-cache-test")
        _     (.mkdirs (io/file dir))
        ref   "turns/0/response.edn"
        f     (io/file (str dir "/" ref))
        _     (.mkdirs (.getParentFile f))
        read! @#'transcript/read-blob-text]
    (reset! @#'transcript/blob-cache {})
    (spit f (pr-str "FIRST"))
    (assertions
      "first read returns the on-disk content"
      (read! dir ref) => "FIRST")
    ;; Mutate the file underneath — a memoized reader must NOT see the change
    ;; (blobs are append-only/content-addressed, so caching the first parse is
    ;; correct and the per-frame disk re-read is eliminated).
    (spit f (pr-str "SECOND"))
    (assertions
      "second read is served from cache (no disk re-read)"
      (read! dir ref) => "FIRST"
      "a missing blob caches nil rather than re-attempting"
      (read! dir "turns/9/missing.edn") => nil)))
