(ns escapement.tui.live
  "LIVE pane content renderer for the mission-control dashboard. Pure over the
   TUI state map `s` + a semantic theme map: produces pre-colored,
   `display-width`-correct body-lines for the LIVE pane interior. Does NOT draw
   the box (the compositor's `draw-box` does that) and does NOT touch the
   facade's render loop / state atom.

   Also exports the live-aggregation primitives (`status-rank`, `live-count`,
   `live-tps`, `live-agg`, `live-status`, `short-session`) that the header /
   phase tracker (`escapement.tui.phase`) and the facade's transcript/legacy
   renderers reuse."
  (:require
    [clojure.string :as str]
    [escapement.tui.theme :as theme]
    [escapement.tui.compositor :as cmp]
    [escapement.tui.util :as util]))

(def live-max-groups
  "Cap on how many invocation GROUPS (one per invokeid/role) get rendered."
  4)

(def live-group-children
  "Cap on how many concurrent sessions are shown indented under one group
   before they collapse into a `└ …+N more` summary."
  6)

(def live-partial-tail-chars
  "Max chars retained in a live entry's in-flight `:text` partial. The partial
   feeds ONLY the streaming-transcript overlay's tail; capping it makes per-delta
   appends O(1)-ish instead of O(N^2) string growth as tokens flood in."
  4096)

(defn cap-tail
  "Trim a string to its last `live-partial-tail-chars` characters."
  [^String t]
  (let [n (count t)]
    (if (> n live-partial-tail-chars)
      (subs t (- n live-partial-tail-chars))
      t)))

(def status-rank
  {:streaming 0 :waiting 1 :error 2 :done 3})

(defn live-count
  "Best available token count for a live entry: the provider's running
   `:output-tokens` usage if it streamed any, else the raw text-delta chunk
   count as a proxy (one chunk ≈ one token for the local OpenAI-compat path)."
  [v]
  (or (:tokens v) (:chunks v) 0))

(defn live-tps
  "Tokens/sec for a live entry. Prefers the LLM's TRUE generation rate
   (`:real-tps`, output tokens over the turn's wall-clock, set on finalize from
   the `:llm/response` event). Falls back to a first→last delta-arrival estimate
   while a session is still streaming (no final rate yet). The delta estimate is
   stretched low by concurrency/queueing, so the real rate is strongly
   preferred once known."
  [v]
  (or (:real-tps v)
    (let [secs (/ (max 1 (- (or (:last-ts v) 0) (or (:first-ts v) 0))) 1000.0)]
      (if (pos? secs) (/ (live-count v) secs) 0.0))))

(defn live-status
  "Per-status [rank glyph label] for the live panel. Lower rank sorts first so
   in-flight invocations stay on top and finished ones sink below."
  [v]
  (case (:status v)
    :streaming [0 (if (= :thinking (:kind v)) \… \◂) "streaming"]
    :waiting   [1 \◷ "waiting"]
    :error     [2 \✗ "error"]
    :done      [3 \✓ "done"]
    [3 \· (or (some-> (:status v) name) "—")]))

(defn short-session
  "Short label for a child session: drop the `multiplex.` prefix and leading
   colon so `multiplex.judges-r1.5` → `judges-r1.5`. Capped for the panel."
  [sid]
  (-> (str sid)
    (str/replace #"^:" "")
    (str/replace #"^multiplex\." "")
    (cmp/truncate 16)))

(defn live-agg
  "Aggregate one invokeid group's sessions into a summary used by the panel
   header and the transcript pager: total tokens, the most in-flight status,
   active/total counts, latest activity, a representative model, and the
   in-flight partial text of the most active session."
  [sessions]
  (let [vs      (vals sessions)
        best    (->> vs (sort-by #(get status-rank (:status %) 3)) first)
        tokens  (reduce + 0 (map live-count vs))
        last-ts (reduce max 0 (map #(or (:last-ts %) 0) vs))
        ;; aggregate throughput = sum of the children's real per-session rates
        ;; (the LLM-reported generation speed each session actually ran at), so
        ;; the header reflects the combined token-generation rate rather than a
        ;; wall-clock figure deflated by sequential queueing.
        tps     (reduce + 0.0 (map live-tps vs))]
    {:tokens   tokens
     :tps      tps
     :status   (:status best)
     :n        (count vs)
     :n-active (count (filter #(#{:streaming :waiting} (:status %)) vs))
     :n-done   (count (filter #(= :done (:status %)) vs))
     :last-ts  last-ts
     :model    (some :model vs)
     :text     (:text best)}))

(defn invokeid-live?
  "True when the invocation `invokeid` is still actively streaming/waiting in the
   TUI state `s` (i.e. at least one of its sessions has status :streaming or
   :waiting). Used to decide whether a transcript pager must rebuild per render
   frame (live) or can reuse its already-built lines (finished). A nil/unknown
   invokeid is not live."
  [s invokeid]
  (boolean
    (when invokeid
      (let [sessions (get-in s [:live (str invokeid) :sessions])]
        (some #(#{:streaming :waiting} (:status %)) (vals sessions))))))

(defn live-display-lines
  "Hierarchical live region. One GROUP per invokeid/role (planner, judge1, …),
   sorted so in-flight groups stay on top. A role with a single session renders
   as one flat line; a role with concurrent sessions (the multiplex children)
   renders a group header with aggregate tokens + active count, then each
   session indented beneath (├/└), capped with a `…+N more` roll-up. Colored to
   the role's scrollback color. Empty when nothing has run yet."
  [s term-w]
  (let [groups (->> (:live s)
                 (map (fn [[iid sess-map]]
                        (let [sessions (:sessions sess-map)]
                          (assoc (live-agg sessions) :iid iid :sessions sessions))))
                 (sort-by (fn [g] [(get status-rank (:status g) 3) (- (:last-ts g))])))]
    (if (empty? groups)
      []
      (let [colorize  (fn [code body]
                        (if code
                          (str (theme/esc (str code "m")) (cmp/truncate body term-w) theme/reset-attrs-s)
                          (cmp/truncate body term-w)))
            n-active  (reduce + 0 (map :n-active groups))
            n-total   (reduce + 0 (map :n groups))
            n-roles   (count groups)
            hdr       (cmp/truncate (str " ── live · " n-active " active · " n-total
                                  " LLMs · " n-roles " roles ──") term-w)
            group->lines
            (fn [{:keys [iid sessions n] :as g}]
              (let [code (theme/color-for s iid)]
                (if (<= n 1)
                  ;; single session → one flat line (planner / host / a lone poet
                  ;; or the muse). When that lone session is a multiplex child
                  ;; (e.g. the secret Muse, which runs inside ONE poet's session),
                  ;; prefix the invokeid with the child label so you can see WHICH
                  ;; poet it served — `poets.2.muse` — without exposing it to the
                  ;; judges (they only ever see haiku text + a bare poet index).
                  (let [v (first (vals sessions))
                        [_ glyph label] (live-status v)
                        ;; `:session` is `(str session-id)`, and the id is a
                        ;; keyword, so the stored value is `:multiplex.poets.2`
                        ;; (leading colon). Strip it before testing — same
                        ;; normalization `short-session` does.
                        sid   (str/replace (str (:session v)) #"^:" "")
                        iid*  (or (util/short-invokeid iid) "?")
                        lbl   (if (str/starts-with? sid "multiplex.")
                                (str (short-session sid) "." iid*)
                                iid*)]
                    [(colorize code
                       (format "  %-16s %s %-9s %5d tok  %5.1f t/s  %s"
                         lbl glyph label
                         (long (live-count v)) (double (live-tps v)) (or (:model v) "")))])
                  ;; multiple concurrent sessions → group header + indented kids
                  (let [head (colorize code
                               (format "  %-13s ◇ %5d tok  (%d/%d active)  %s"
                                 (or (util/short-invokeid iid) "?")
                                 (long (:tokens g)) (:n-active g) n (or (:model g) "")))
                        kids (->> (vals sessions)
                               (sort-by (fn [v] [(get status-rank (:status v) 3)
                                                 (- (or (:last-ts v) 0))]))
                               (take live-group-children))
                        more (- n (count kids))
                        kid-lines
                        (map-indexed
                          (fn [i v]
                            (let [last? (and (zero? more) (= i (dec (count kids))))
                                  [_ glyph label] (live-status v)]
                              (colorize code
                                (format "    %s %-13s %s %-9s %5d tok  %5.1f t/s"
                                  (if last? "└" "├")
                                  (short-session (:session v)) glyph label
                                  (long (live-count v)) (double (live-tps v))))))
                          kids)]
                    (concat [head] kid-lines
                      (when (pos? more)
                        [(colorize code (str "    └ …+" more " more sessions"))]))))))]
        (vec (concat [hdr] (mapcat group->lines (take live-max-groups groups))))))))

(defn- live-groups
  "Shared ordered group list used by both `live-pane-lines` and `live-row-index`
   so the visible rows and the row→target index stay in lockstep. One entry per
   invokeid/role, sorted so in-flight groups stay on top."
  [s]
  (->> (:live s)
    (map (fn [[iid sess-map]]
           (let [sessions (:sessions sess-map)]
             (assoc (live-agg sessions) :iid iid :sessions sessions))))
    (sort-by (fn [g] [(get status-rank (:status g) 3) (- (:last-ts g))]))))

(defn live-row-index
  "Parallel to `live-pane-lines`: returns a vector of one entry per VISIBLE LIVE
   row, mapping a cursor index to what Enter should open. Each entry is
   `{:invokeid <iid> :session <sid|nil> :kind :group|:session|:more}`:
   * a single-session group           → one `:session` row (the lone session).
   * a multi-session group header      → one `:group` row; Enter opens the
     group's representative (most in-flight) session — `:session` set to the
     best session's id, `:kind :group`.
   * each indented child session       → a `:session` row (its session id).
   * a `…+N more` roll-up              → a `:more` row pointing at the group's
     representative (so Enter still drills into something sensible).
   The ordering exactly mirrors `live-pane-lines` (same `live-groups`,
   `live-max-groups`, `live-group-children`, `…+N more` rules), so a row index
   from the rendered pane indexes this vector 1:1."
  [s]
  (let [best-session
        (fn [sessions]
          (->> (vals sessions)
            (sort-by (fn [v] [(get status-rank (:status v) 3)
                              (- (or (:last-ts v) 0))]))
            first
            :session))
        group->rows
        (fn [{:keys [iid sessions n]}]
          (if (<= n 1)
            [{:invokeid iid :session (:session (first (vals sessions))) :kind :session}]
            (let [rep  (best-session sessions)
                  kids (->> (vals sessions)
                         (sort-by (fn [v] [(get status-rank (:status v) 3)
                                           (- (or (:last-ts v) 0))]))
                         (take live-group-children))
                  more (- n (count kids))]
              (vec (concat
                     [{:invokeid iid :session rep :kind :group}]
                     (map (fn [v] {:invokeid iid :session (:session v) :kind :session}) kids)
                     (when (pos? more)
                       [{:invokeid iid :session rep :kind :more}]))))))]
    (vec (mapcat group->rows (take live-max-groups (live-groups s))))))

;; ---------------------------------------------------------------------------
;; LIVE pane renderer
;;
;; Produces a vector of pre-colored, `display-width`-correct body-lines for the
;; LIVE pane interior. Does NOT draw the box (compositor's `draw-box` does that).
;;
;; Honest bars: only group headers (a role with >1 session) get a `done/total`
;; completion bar. A single streaming LLM has no known token total, so it never
;; gets a bar — instead it shows an indeterminate shimmer that advances by a
;; frame counter (deterministic, never wall-clock RNG).
;; ---------------------------------------------------------------------------

(defn completion-bar
  "Render a determinate completion bar of `width` cells filling `done/total`.
   Filled cells use the theme's `:bar-filled` (green / role hue), empty cells
   use `:bar-empty` (dim). Returns a pre-colored string of exactly `width`
   display columns (when uncolored). `width<=0` ⇒ \"\"; `total<=0` ⇒ all-empty.
   `filled-code` (optional SGR digits) overrides `:bar-filled` so a role hue can
   tint the fill instead of green."
  ([theme done total width] (completion-bar theme done total width nil))
  ([theme done total width filled-code]
   (if (<= width 0)
     ""
     (let [done   (max 0 (or done 0))
           total  (max 0 (or total 0))
           frac   (if (pos? total) (/ (double (min done total)) total) 0.0)
           nfill  (long (Math/floor (* frac width)))
           nfill  (min width (max 0 nfill))
           nempty (- width nfill)
           fc     (or filled-code (get theme :bar-filled ""))
           ec     (get theme :bar-empty "")]
       (str (theme/sgr-wrap fc (apply str (repeat nfill "█")))
         (theme/sgr-wrap ec (apply str (repeat nempty "░"))))))))

(def ^:private shimmer-cells
  "Indeterminate-progress cells: one bright block sliding through dim ones."
  ["▰" "▱"])

(defn shimmer
  "Deterministic indeterminate-progress shimmer of `width` cells. The single
   filled cell `▰` slides by one per `tick` (a frame counter — NO wall-clock /
   RNG, so it's reproducible). Remaining cells are dim `▱`. Returns a pre-colored
   string of exactly `width` display columns (uncolored). `width<=0` ⇒ \"\".
   The bright cell uses `:status/streaming`, the dim cells `:bar-empty`."
  [theme width tick]
  (if (<= width 0)
    ""
    (let [pos (mod (long (or tick 0)) width)
          fc  (get theme :status/streaming "")
          ec  (get theme :bar-empty "")]
      (apply str
        (for [i (range width)]
          (if (= i pos)
            (theme/sgr-wrap fc (first shimmer-cells))
            (theme/sgr-wrap ec (second shimmer-cells))))))))

(def ^:private live-bar-width
  "Default completion-/shimmer-bar width; shrinks on narrow panes via
   `live-bar-width-for`."
  10)

(defn- live-bar-width-for
  "Responsive bar width: `live-bar-width` cells, reduced on narrow interiors so
   the bar never crowds out the role name / metrics. Minimum 4."
  [interior-w]
  (-> (cond
        (>= interior-w 50) live-bar-width
        (>= interior-w 36) 8
        (>= interior-w 28) 6
        :else 4)
    (min (max 4 (- interior-w 24)))
    (max 4)))

(defn- live-tick
  "Per-group frame counter for the shimmer. Derived from the group's latest
   activity timestamp (`:last-ts`) and the global `:tick` on the state, so the
   shimmer advances deterministically as deltas arrive (no wall-clock RNG)."
  [s g]
  (+ (long (or (:tick s) 0))
    (quot (long (or (:last-ts g) 0)) 100)))

(defn live-pane-lines
  "Pre-colored, `display-width`-correct body-lines for the LIVE pane interior of
   width `interior-w`. Returns the FULL list (no slicing) — the integrator /
   `draw-box` clips to the pane height; pass an `offset` (4-arity) to scroll: it
   drops the first `offset` lines.

   Layout per group (sorted so in-flight stays on top):
   * group header (role with >1 session): role name (role hue) · ◇ status ·
     `done/total done` · completion bar (`done/total`) · tokens (no t/s — the
     summed per-session rate is misleading). Children render
     indented `├`/`└` rows: session · glyph status · tok · t/s · NO bar, capped
     with a `…+N more` roll-up.
   * single-session role (host / lone planner): NO bar — a streaming shimmer
     while streaming, else status glyph · status · tok · t/s.

   `theme` is a semantic theme map (`theme-for`); `s` is the TUI state map
   (`:live`, optional `:tick`). Colors: role hue via `role-sgr`, status glyph via
   `status-color`, numbers via `:metric`. All lines `truncate-display`-fit."
  ([s theme interior-w] (live-pane-lines s theme interior-w 0 nil))
  ([s theme interior-w offset] (live-pane-lines s theme interior-w offset nil))
  ([s theme interior-w offset opts]
   (let [iw     (max 1 interior-w)
         metric (fn [body] (theme/paint theme :metric body))
         fit    (fn [line] (cmp/truncate-display line iw))
         ;; uniform right-hand metric tail (tok + t/s) so those columns line up
         ;; across header / single / child rows. `left-fit` pads each row's
         ;; left block to a fixed display width; the tail then starts at the
         ;; same column everywhere.
         ;; model column: shown on each INDIVIDUAL session row (single + child),
         ;; since concurrent children can run different models. The group header
         ;; reserves a BLANK column (it aggregates rows whose models may differ),
         ;; keeping the tok/t-s columns aligned across header / single / child.
         ;; Responsive: dropped entirely on narrow panes so the role name /
         ;; completion bar never get crowded out (model-w 0 ⇒ no column).
         model-w  (cond (>= iw 100) 24 (>= iw 88) 18 (>= iw 76) 12 :else 0)
         tail-w   (+ 22 (if (pos? model-w) (+ model-w 2) 0))
         lw       (max 1 (- iw tail-w))
         left-fit (fn [body] (cmp/truncate-display body lw))
         modelcol (fn [m]
                    (if (pos? model-w)
                      (metric (format (str "  %-" model-w "s")
                                (cmp/truncate (or m "") model-w)))
                      ""))
         ;; `provider/model` label for a session row, e.g. "ollama/gemma3:1b".
         ;; Falls back to model-only when no provider (backend-default pick),
         ;; and "" when neither is known yet (a still-waiting row).
         pm       (fn [v]
                    (let [m  (:model v)
                          pv (:provider v)
                          ps (when pv (if (keyword? pv) (name pv) (str pv)))]
                      (cond (and ps m) (str ps "/" m)
                            m          m
                            :else      "")))
         mtail    (fn [tok tps]
                    (metric (format "  %5d tok  %5.1f t/s" (long tok) (double tps))))
         ;; selection cursor: highlight the row at `:cursor` when LIVE focused
         hl?     (boolean (:focus? opts))
         cursor  (when hl? (:cursor opts))
         hilite  (fn [idx line]
                   (if (and cursor (= idx cursor))
                     (str cmp/reverse-on-s line theme/reset-attrs-s)
                     line))
         groups (live-groups s)
         group->lines
         (fn [{:keys [iid sessions n status] :as g}]
           (let [rcode (theme/role-sgr-themed theme s iid)
                 name  (or (util/short-invokeid iid) "?")]
             (if (<= n 1)
               ;; single session → NO bar; shimmer while streaming
               (let [v        (first (vals sessions))
                     st       (:status v)
                     [_ glyph label] (live-status v)
                     bw       (live-bar-width-for iw)
                     gl       (theme/sgr-wrap (theme/status-color theme st) (str glyph))
                     prog     (if (= :streaming st)
                                (shimmer theme bw (live-tick s g))
                                (theme/sgr-wrap (theme/status-color theme st) label))
                     left     (str " " (theme/sgr-wrap rcode (format "%-12s" name))
                                " " gl " " prog)
                     line     (str (left-fit left) (modelcol (pm v))
                                (mtail (live-count v) (live-tps v)))]
                 [(fit line)])
               ;; multiple concurrent sessions → group header + bar + kids
               (let [done     (:n-done g)
                     bw       (live-bar-width-for iw)
                     bar      (completion-bar theme done n bw)
                     gl       (theme/sgr-wrap (theme/status-color theme status) "◇")
                     ;; bold the parent/aggregate row so it visually separates
                     ;; from its indented children (bold + the role hue).
                     bcode    (if (str/blank? rcode) "1" (str "1;" rcode))
                     head-left (str " " (theme/sgr-wrap bcode (format "%-12s" name))
                                 " " gl " "
                                 (theme/sgr-wrap bcode (format "%d/%d done " done n))
                                 bar)
                     head     (str (left-fit head-left)
                                (modelcol nil)
                                (theme/sgr-wrap bcode
                                  (format "  %5d tok" (long (:tokens g)))))
                     kids     (->> (vals sessions)
                                (sort-by (fn [v] [(get status-rank (:status v) 3)
                                                  (- (or (:last-ts v) 0))]))
                                (take live-group-children))
                     more     (- n (count kids))
                     kid-lines
                     (map-indexed
                       (fn [i v]
                         (let [last? (and (zero? more) (= i (dec (count kids))))
                               st    (:status v)
                               [_ glyph label] (live-status v)
                               tee   (theme/sgr-wrap rcode (if last? "└" "├"))
                               kgl   (theme/sgr-wrap (theme/status-color theme st) (str glyph))]
                           (fit (str (left-fit
                                       (str "  " tee " "
                                         (theme/sgr-wrap rcode (format "%-13s" (short-session (:session v))))
                                         " " kgl " "
                                         (theme/paint theme (case st
                                                        :streaming :status/streaming
                                                        :done :status/done
                                                        :waiting :status/waiting
                                                        :error :status/error
                                                        :status/idle)
                                           (format "%-9s" label))))
                                  (modelcol (pm v))
                                  (mtail (live-count v) (live-tps v))))))
                       kids)]
                 (concat [(fit head)] kid-lines
                   (when (pos? more)
                     [(fit (str "  " (theme/sgr-wrap rcode "└")
                             (metric (str " …+" more " more sessions"))))]))))))
         all    (->> (mapcat group->lines (take live-max-groups groups))
                   (map-indexed hilite)
                   vec)
         off    (max 0 (or offset 0))]
     (if (pos? off) (vec (drop off all)) all))))
