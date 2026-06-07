(ns escapement.tui.transcript
  "Per-invocation transcript: a pure SENT/REPLY block model + a themed chat
   renderer for the inspector drill-in (terminal-UI add-on).

   The transcript reads like a chat log with two unmistakable lanes:

     ▸ SENT  — what WE transmit to the model (system prompt, user messages,
                tool results). Dim/neutral gutter + label, dim body. Outgoing,
                secondary.
     ◂ REPLY — the model's response (assistant text / thinking / tool_use).
                Label in the invocation's role hue, body normal/bright, glyph
                status-colored (streaming cyan / done green / error red). The
                model speaking, visually primary.

   Two layers:
     1. `transcript-blocks` — PURE: turns an invocation's buffered events into
        a vector of {:dir :ts :label :sublabel :role :meta :body :collapsible?}
        maps in chronological order, including the in-flight (streaming) reply.
        Defensive: missing fields never throw.
     2. `transcript-lines` — themed renderer: formats the blocks into
        width-correct body-lines for the overlay interior (per-block header,
        dim hairline before each REPLY, blank-line rhythm, 2-space indented
        wrapped body, collapsed-system preview, streaming `▏` cursor). Every
        line runs through `truncate-display` so no control char / over-width
        line can bleed past the box border.

   The existing `invocation-transcript-text` (the inspector pager seam) now
   builds blocks and renders them via `transcript-lines`; the `:live-invokeid`
   live-rebuild in the inspector re-runs it each frame so streamed tokens grow
   the reply block live. `fmt-transcript-event` is retained (back-compat)."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [escapement.tui.compositor :as cmp]
    [escapement.tui.live :as live]
    [escapement.tui.markdown :as markdown]
    [escapement.tui.theme :as theme]
    [escapement.tui.util :as util]))

;; ---------------------------------------------------------------------------
;; Blob readers (best-effort; the inline event snippets are the fallback)
;; ---------------------------------------------------------------------------

;; Captured blobs are content-addressed/append-only (a turn's request.edn /
;; response.edn never mutates once written), so parsing them is safe to cache.
;; Without this the inspector pager re-slurp+re-parsed every blob of every turn
;; on EVERY render frame (each scroll keypress triggers a full re-read) — the
;; ~10s/keypress lag on a finished transcript. Bounded so a long session can't
;; grow it without limit; eviction is coarse (clear-on-overflow) but the working
;; set per inspector view is small.
(def ^:private blob-cache-max 512)
(def ^:private blob-cache (atom {}))

(defn- cached-blob
  "Parse a captured blob via `parse-fn`, memoized by [sdir io-ref kind]. Returns
   the parsed value (nil on missing/unreadable blob; also cached to avoid
   re-attempting a missing file every frame)."
  [kind sdir io-ref parse-fn]
  (if-not (and sdir io-ref)
    nil
    (let [k [sdir io-ref kind]
          c @blob-cache]
      (if (contains? c k)
        (get c k)
        (let [v (try (parse-fn (edn/read-string (slurp (str sdir "/" io-ref))))
                     (catch Throwable _ nil))]
          (swap! blob-cache
            (fn [m] (assoc (if (> (count m) blob-cache-max) {} m) k v)))
          v)))))

(defn- read-blob-system
  "Best-effort read of the full system prompt from a captured `request.edn`
   blob. Returns the system string or nil."
  [sdir io-ref]
  (cached-blob :system sdir io-ref :system))

(defn- read-blob-content
  "Best-effort read of the FULL assistant content blocks from a captured
   `response.edn` blob. Returns the content vector or nil."
  [sdir io-ref]
  (cached-blob :content sdir io-ref
    (fn [data]
      (cond (vector? data) data
            (map? data)     (:content data)
            :else           nil))))

(defn- read-blob-text
  "Best-effort read of a captured blob's full text. Returns the string or nil."
  [sdir io-ref]
  (cached-blob :text sdir io-ref
    (fn [data]
      (cond (string? data) data
            (map? data)    (or (:content data) (:text data))
            :else          nil))))

;; ---------------------------------------------------------------------------
;; Pure block model
;;
;; Each event maps to one or more blocks:
;;   :llm/request      → one SENT :label "system" block (collapsible, :chars N)
;;   :llm/user-message → one SENT :label "user"   block (full body)
;;   :llm/tool-result  → one SENT :label "tool"   block (truncated preview;
;;                        sublabel "<tool> → N results" when derivable)
;;   :llm/response     → one REPLY block per content block:
;;                        :text     → REPLY :label "assistant", body = prose,
;;                                    meta {:stop :in :out :tps}
;;                        :thinking → REPLY :label "assistant" :sublabel "thinking"
;;                        :tool_use → REPLY :label "assistant"
;;                                    :sublabel "<tool>(args…)" (accent line)
;;   in-flight reply   → one REPLY :label "assistant" :meta{:streaming? true …}
;;   :llm/error        → one REPLY :label "error"
;;
;; `:dir` is :sent | :reply. SENT = system/user/tool; REPLY = assistant/error.
;; ---------------------------------------------------------------------------

(defn- collapse-args
  "One-line, length-capped rendering of tool_use args for the accent line."
  [s]
  (let [s (cmp/collapse-ws s)]
    (if (> (count s) 60) (str (subs s 0 59) "…") s)))

(defn- response-blocks
  "Turn one :llm/response event into a vector of REPLY blocks (one per content
   block). `meta` (stop/in/out/tps) is attached to the FIRST emitted block so
   the reply header shows it once."
  [sdir invokeid {:keys [data ts]}]
  (let [hms     (util/ts->hms ts)
        usage   (:usage data)
        meta    {:stop (:stop-reason data)
                 :in   (:input-tokens usage)
                 :out  (:output-tokens usage)
                 :tps  (:output-tps data)}
        content (or (read-blob-content sdir (:io/ref data)) (:content data))
        blocks  (->> content
                  (filter #(#{:text :thinking :tool_use} (:type %)))
                  (map-indexed
                    (fn [i b]
                      (case (:type b)
                        :text
                        {:dir :reply :ts hms :label "assistant" :sublabel nil
                         :role invokeid :body (str (:text b)) :meta {}}
                        :thinking
                        {:dir :reply :ts hms :label "assistant" :sublabel "thinking"
                         :role invokeid :body (str (:thinking b)) :meta {}}
                        :tool_use
                        (let [args (util/pretty (or (:input b) {}))]
                          {:dir :reply :ts hms :label "assistant"
                           :sublabel (str (:name b) "(" (collapse-args args) ")")
                           :role invokeid :body "" :meta {}}))))
                  vec)
        blocks  (if (seq blocks)
                  (update-in (vec blocks) [0 :meta] merge meta)
                  ;; empty content (e.g. pure stop) → one bare reply header
                  [{:dir :reply :ts hms :label "assistant" :sublabel nil
                    :role invokeid :body "" :meta meta}])]
    blocks))

(defn transcript-blocks
  "PURE: build the SENT/REPLY block vector for invocation `invokeid` from the
   TUI handle `h` (reads `@(:state h)` + `@(:env h)`). Chronological. Includes
   the in-flight streaming reply (when present) as a `:meta{:streaming? true}`
   block whose body grows each frame.

   Each block:
     {:dir :sent|:reply
      :ts  \"hh:mm:ss\"
      :label \"system\"|\"user\"|\"tool\"|\"assistant\"|\"error\"
      :sublabel nil|\"thinking\"|\"<tool>(args…)\"|\"<tool> → N results\"
      :role <invokeid>                ; for role-hued REPLY labels
      :meta {:stop kw :in N :out M :tps n :streaming? bool :chars N}
      :body  <string>
      :collapsible? bool}             ; true for the system block (+ :chars)

   Defensive: tolerates missing/blob-less events; never throws."
  [h invokeid]
  (let [s     (some-> (:state h) deref)
        env   (some-> (:env h) deref)
        sdir  (util/session-dir-from-env env)
        ;; One transcript event yields several scrollback entries sharing the
        ;; same `:ev`; dedup so a turn isn't rendered once per content block.
        evs   (->> (:scrollback s)
                (keep (fn [e]
                        (when (= invokeid (some-> (:source e) str)) (:ev e))))
                (filter map?)
                (distinct)
                vec)
        first-req (first (filter #(= :llm/request (:event %)) evs))
        system    (or (read-blob-system sdir (get-in first-req [:data :io/ref]))
                    (get-in first-req [:data :system-preview])
                    (get-in first-req [:data :system])
                    nil)
        system-ts (util/ts->hms (:ts first-req))
        sys-block (when system
                    {:dir :sent :ts system-ts :label "system" :sublabel nil
                     :role invokeid :meta {:chars (count (str system))}
                     :body (str system) :collapsible? true})
        live      (live/live-agg (get-in s [:live invokeid :sessions]))
        body-blocks
        (->> evs
          (mapcat
            (fn [{:keys [event data ts] :as ev}]
              (let [hms (util/ts->hms ts)]
                (case event
                  :llm/user-message
                  [{:dir :sent :ts hms :label "user" :sublabel nil :role invokeid
                    :meta {} :body (str (:text data))}]

                  :llm/tool-result
                  (let [full (or (read-blob-text sdir (:io/ref data))
                               (:content-preview data) "")]
                    [{:dir :sent :ts hms :label "tool"
                      :sublabel (str (:tool data)
                                  (when (:is-error data) " (ERROR)"))
                      :role invokeid :meta {}
                      :body (str full)}])

                  :llm/response
                  (response-blocks sdir invokeid ev)

                  :llm/error
                  [{:dir :reply :ts hms :label "error" :sublabel nil
                    :role invokeid :meta {} :body (str (:message data))}]

                  ;; start/request/delta/retry/etc. fold into headers — skip.
                  [])))))
        ;; In-flight reply: deltas streamed for the current (not-yet-finalized)
        ;; turn. Rendered as a growing :streaming? block.
        live-txt  (:text live)
        pending   (when (and live-txt (not (str/blank? live-txt)))
                    [{:dir :reply :ts (util/ts->hms (System/currentTimeMillis))
                      :label "assistant" :sublabel nil :role invokeid
                      :meta {:streaming? true
                             :out (:tokens live)
                             :tps (:output-tps live)}
                      :body (str live-txt)}])]
    (vec (concat (when sys-block [sys-block]) body-blocks pending))))

;; ---------------------------------------------------------------------------
;; Display-width word-wrap (SGR-agnostic; wraps on TRUE columns)
;; ---------------------------------------------------------------------------

(defn wrap-display
  "Word-wrap plain `s` (no SGR) to `width` terminal columns using
   `display-width`, preserving a fixed leading `indent` on every physical line.
   A word wider than the line is hard char-split. Returns a vector of lines
   (each without trailing pad — the caller pads via `truncate-display`). Never
   returns an empty vector for non-blank input."
  [width indent s]
  (let [width  (max 1 width)
        iw     (cmp/display-width indent)
        avail  (max 1 (- width iw))
        s      (str/trimr (str s))]
    (if (str/blank? s)
      []
      (vec
        (mapcat
          (fn [logical]
            (let [words (remove str/blank? (str/split logical #"\s+"))]
              (if (empty? words)
                [indent]
                (loop [ws words, cur "", out []]
                  (if-let [w (first ws)]
                    (let [ww (cmp/display-width w)]
                      (cond
                        ;; word longer than a whole line → hard split it
                        (> ww avail)
                        (let [room (max 1 (- avail (cmp/display-width cur)))
                              ;; take `room` codepoints (best-effort; ascii-safe)
                              here (subs w 0 (min room (count w)))
                              left (subs w (count here))]
                          (if (str/blank? cur)
                            (recur (cons left (rest ws)) "" (conj out (str indent here)))
                            (recur ws "" (conj out (str indent cur)))))
                        ;; fits on current line
                        (<= (+ (cmp/display-width cur) (if (str/blank? cur) 0 1) ww) avail)
                        (recur (rest ws)
                          (if (str/blank? cur) w (str cur " " w)) out)
                        ;; flush and wrap
                        :else
                        (recur ws "" (conj out (str indent cur)))))
                    (conj out (str indent cur)))))))
          (str/split-lines s))))))

;; ---------------------------------------------------------------------------
;; Themed chat renderer
;; ---------------------------------------------------------------------------

(def ^:private sent-glyph "▸")
(def ^:private reply-glyph "◂")
(def ^:private cursor-glyph "▏")
(def ^:private body-indent "  ")

;; Markdown rendering is ~0.25ms/line — cheap once, but the inspector rebuilds a
;; live transcript's lines EVERY render frame (~30fps) while streaming. A
;; finalized turn's body never changes, so re-rendering all prior turns each
;; frame is pure waste (and stalls input — the "can't page while streaming" lag).
;; Cache rendered body-lines per [interior-width theme-marker body]; only the
;; growing streaming tail misses each frame. Bounded (clear-on-overflow).
(def ^:private body-cache-max 4096)
(def ^:private body-cache (atom {}))

(defn- render-body-md
  "Markdown-render `body` to a vector of body-lines (already indented + emitted
   to `iw` columns), memoized by [iw theme-marker body]. `emit` is the per-line
   themed truncate-pad fn so the cached value is render-ready."
  [theme iw body emit]
  (let [k [iw (get theme :border-dim) body]
        c @body-cache]
    (if-let [hit (find c k)]
      (val hit)
      (let [md    (markdown/render (str body) theme (- iw (cmp/display-width body-indent)))
            lines (mapv (fn [l] (emit (str body-indent l))) md)]
        (swap! body-cache
          (fn [m] (assoc (if (> (count m) body-cache-max) {} m) k lines)))
        lines))))

(defn- fmt-reply-meta
  "Build the trailing meta segment for a REPLY header:
   `· <stop> · in:N out:M · <t/s>`  or streaming `· ◂ streaming · out:N · <t/s>`."
  [{:keys [stop in out tps streaming?]}]
  (let [parts (cond
                streaming?
                (->> [(str reply-glyph " streaming")
                      (when out (str "out:" out))
                      (when tps (format "%.1f t/s" (double tps)))]
                  (remove nil?))
                :else
                (->> [(when stop (name stop))
                      (when (or in out)
                        (str "in:" (or in "?") " out:" (or out "?")))
                      (when tps (format "%.1f t/s" (double tps)))]
                  (remove nil?)))]
    (when (seq parts)
      (str " · " (str/join " · " parts)))))

(defn- emit-line
  "Optionally SGR-wrap `s` with theme key `k`, then truncate/pad to exactly
   `iw` columns. Under a `:none` theme `paint` is a no-op (zero escapes)."
  [theme k iw s]
  (cmp/truncate-display (theme/paint theme k s) iw))

(defn- header-line
  "Render a block's header line (already padded to `iw`). SENT lanes are dim;
   REPLY lanes carry the role hue on the label + status-colored glyph."
  [theme s-state block iw]
  (let [{:keys [dir ts label sublabel role meta collapsible?]} block
        sent?  (= dir :sent)
        glyph  (if sent? sent-glyph reply-glyph)
        lane   (if sent? "SENT " "REPLY")
        ;; label: dim for SENT, role-hue for REPLY
        rolesgr (theme/role-sgr-themed theme s-state role)
        sys-suffix (when (and collapsible? (:chars meta))
                     (str " · " (:chars meta) " chars"))
        meta-seg   (when (= dir :reply) (fmt-reply-meta meta))
        sub-seg    (when sublabel (str " · " sublabel))
        ;; Direction TAG: bold fg on a colored background so the eye instantly
        ;; catches SENT (blue) vs REPLY (green). One contiguous reverse/bg field
        ;; ` ▸ SENT  ` / ` ◂ REPLY ` so the highlight reads as a solid label.
        ;; Under :none the tag code is "" ⇒ plain text, still readable.
        tag-k (if sent? :sent-tag :reply-tag)
        tag   (theme/paint theme tag-k (str " " glyph " " lane " "))
        ts*   (theme/paint theme :timestamp ts)
        lbl   (theme/sgr-wrap (if sent? (get theme :phase-upcoming "") rolesgr) label)
        sub*  (theme/paint theme :timestamp (str sub-seg sys-suffix))
        mta*  (theme/paint theme :metric (str meta-seg))
        line  (str tag " " ts* " · " lbl sub* mta*)]
    (cmp/truncate-display line iw)))

(defn transcript-lines
  "Render `blocks` into themed body-lines for an overlay interior `interior-w`
   columns wide. Implements the two-lane chat scheme:
     - per-block header line (SENT dim / REPLY role-hue + status glyph)
     - a dim hairline rule immediately before each REPLY
     - one blank line between turns
     - 2-space indented, display-width-wrapped body (never exceeds interior-w)
     - EVERY block (system included) renders its FULL body, wrapped across as
       many lines as needed — transcripts are always complete, never truncated
       to a preview (`:expanded?` is retained but a no-op; bodies are always full)
     - in-flight reply gets a `▏` cursor at the end of its growing body

   `opts`: {:expanded? bool   ; legacy/no-op — bodies are always full
            :state    <tui state map>}  ; for role-hue lookup
   Every emitted line is width-correct (run through `truncate-display`) so no
   control char / over-width content can bleed past the border."
  [blocks theme interior-w opts]
  (let [iw       (max 1 interior-w)
        expanded? (boolean (or (:expanded? opts)
                             (some-> (:expanded opts) (contains? :system))))
        s-state  (:state opts)
        hairline (emit-line theme :border-dim iw (apply str (repeat iw "─")))
        blank    (cmp/truncate-display "" iw)
        _        expanded?                                  ;; legacy flag; bodies are ALWAYS full now
        emit     (fn [s] (emit-line theme nil iw s))
        body-of  (fn [block]
                   (let [{:keys [meta body]} block]
                     (if (:streaming? meta)
                       ;; Live streaming block: body grows each frame, so render
                       ;; fresh (NO cache — caching would fill with dead keys) and
                       ;; append the `▏` cursor to the last line BEFORE padding.
                       (let [md    (markdown/render (str body) theme
                                     (- iw (cmp/display-width body-indent)))
                             md    (if (seq md)
                                     (update (vec md) (dec (count md)) str cursor-glyph)
                                     md)]
                         (mapv #(emit (str body-indent %)) md))
                       ;; Finalized turn — bodies never change, so memoize the
                       ;; rendered lines (the "can't page while streaming" lag was
                       ;; re-rendering every prior turn each frame).
                       (render-body-md theme iw (str body) emit))))]
    (->> blocks
      (map-indexed
        (fn [i block]
          (let [reply? (= (:dir block) :reply)
                lead   (if (zero? i) [] [blank])
                rule   (when reply? [hairline])
                hdr    [(header-line theme s-state block iw)]
                body   (body-of block)]
            (concat lead rule hdr body))))
      (apply concat)
      vec)))

;; ---------------------------------------------------------------------------
;; Inspector pager seam
;;
;; The inspector pager rebuilds `:lines` each frame by `str/split-lines`-ing
;; the string this returns, then re-wraps each line via its own (count-based)
;; `wrap-line` and pads with `truncate-display`. That re-wrap would corrupt SGR
;; escapes, so the SEAM renders the PLAIN (escape-free, theme `:none`) variant —
;; the glyph + lane + indent + hairlines carry direction with zero color (this
;; is exactly the NO_COLOR acceptance form, and survives the double-wrap). The
;; fully-themed/colored variant is `transcript-lines` (used directly by the live
;; drill-in in task 007, which passes `interior-w` and skips the re-wrap).
;; ---------------------------------------------------------------------------

(defn invocation-transcript-text
  "Build the full per-invocation transcript text for the inspector pager from
   the SENT/REPLY block model. Plain (escape-free) so it survives the pager's
   re-wrap; the colored renderer is `transcript-lines`. Backward-compatible
   2-arity (the inspector calls `(invocation-transcript-text h invokeid)`)."
  [h invokeid]
  (let [s        (some-> (:state h) deref)
        term-w   (:term-w s 80)
        interior (max 20 (- term-w 4))                         ;; box+pager margins
        blocks   (transcript-blocks h invokeid)
        none     (theme/theme-for :none)
        header   (let [env  (some-> (:env h) deref)
                       sdir (util/session-dir-from-env env)
                       resp (filter #(= :reply (:dir %)) blocks)
                       models (->> (:scrollback s)
                                (keep #(get-in % [:ev :data :model]))
                                distinct (remove nil?))
                       live (live/live-agg (get-in s [:live invokeid :sessions]))
                       models (if (and (empty? models) (:model live))
                                [(:model live)] models)]
                   (str " " invokeid " · " (if (seq models)
                                             (str/join ", " models) "—")
                     "  ·  " (count resp) " replies"))
        lines    (transcript-lines blocks none interior {:expanded? false})]
    (if (seq blocks)
      (str/join "\n" (cons header (cons "" lines)))
      (str header "\n\n(no turns recorded in the live buffer)"))))

(defn invocation-transcript-colored-lines
  "Like `invocation-transcript-text` but returns a vector of THEMED, already
   width-correct lines (interior `interior-w` columns) for the inspector pager
   to draw WITHOUT re-wrapping. The SENT/REPLY direction tags carry their
   background/bold styling here; `transcript-lines` guarantees no bleed (every
   line is exactly `interior-w` after `truncate-display`). Honors NO_COLOR/non-tty
   via `theme` (a `:none` theme ⇒ zero escapes, still width-correct). The header
   string (model · N replies) is themed dim and width-padded too."
  [h invokeid theme interior-w]
  (let [s        (some-> (:state h) deref)
        iw       (max 1 interior-w)
        blocks   (transcript-blocks h invokeid)
        env      (some-> (:env h) deref)
        models   (->> (:scrollback s)
                   (keep #(get-in % [:ev :data :model]))
                   distinct (remove nil?))
        live     (live/live-agg (get-in s [:live invokeid :sessions]))
        models   (if (and (empty? models) (:model live)) [(:model live)] models)
        resp     (filter #(= :reply (:dir %)) blocks)
        header   (cmp/truncate-display
                   (theme/paint theme :timestamp
                     (str " " invokeid " · "
                       (if (seq models) (str/join ", " models) "—")
                       "  ·  " (count resp) " replies"))
                   iw)
        blank    (cmp/truncate-display "" iw)
        lines    (transcript-lines blocks theme iw {:state s})]
    (if (seq blocks)
      (into [header blank] lines)
      [header blank (cmp/truncate-display "(no turns recorded in the live buffer)" iw)])))

;; ---------------------------------------------------------------------------
;; Back-compat single-event formatter (retained; used by tests / call sites)
;; ---------------------------------------------------------------------------

(defn- indent-block
  "Indent every line of `s` by `pad`. Blank/nil → empty string."
  [pad s]
  (let [s (str/trimr (str s))]
    (if (str/blank? s)
      ""
      (->> (str/split-lines s) (map #(str pad %)) (str/join "\n")))))

(defn fmt-transcript-event
  "Render one transcript event as a labeled, indented section (back-compat;
   plain text). Returns a string (may be empty for non-turn events)."
  [sdir {:keys [event data ts]}]
  (let [hms (util/ts->hms ts)]
    (case event
      :llm/user-message
      (str "▸ SENT  " hms " · user\n" (indent-block "  " (:text data)))

      :llm/response
      (let [stop  (:stop-reason data)
            usage (:usage data)
            tps   (:output-tps data)
            head  (str "◂ REPLY " hms " · assistant"
                    (when stop (str " · " (name stop)))
                    " · in:" (:input-tokens usage "?") " out:" (:output-tokens usage "?")
                    (when tps (str " · " tps " t/s")))
            content (or (read-blob-content sdir (:io/ref data)) (:content data))
            blocks (for [b content
                         :let [t (:type b)]
                         :when (#{:text :thinking :tool_use} t)]
                     (case t
                       :text     (indent-block "  " (:text b))
                       :thinking (str "  · thinking\n" (indent-block "  " (:thinking b)))
                       :tool_use (str "  → " (:name b) "\n"
                                   (indent-block "  " (util/pretty (or (:input b) {}))))))]
        (str head "\n" (str/join "\n\n" (remove str/blank? blocks))))

      :llm/tool-result
      (str "▸ SENT  " hms " · tool · " (:tool data)
        (when (:is-error data) " (ERROR)") "\n"
        (indent-block "  " (or (read-blob-text sdir (:io/ref data))
                             (:content-preview data))))

      :llm/error
      (str "◂ REPLY " hms " · error\n" (indent-block "  " (:message data)))

      "")))
