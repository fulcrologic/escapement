(ns escapement.tui.phase
  "Phase tracker + header strip for the mission-control dashboard. A pure,
   defensive walk over the statechart value stashed by the facade's
   `attach-env!` (under the `:escapement.tui/chart` env-meta key) plus the header
   strip composition (title · clock · breadcrumb · sibling strip · live
   metrics). Pure over `(h, s, theme, width, now-ms)` — does NOT draw the box
   (the compositor's `draw-box` does that) and does NOT touch the facade's
   render loop / state atom.

   Chart data shape (read directly, no static require of the statechart ns —
   keeps this add-on dependency-light and SCI-safe):

     chart                                  ;; a map
     ├ :com.fulcrologic.statecharts/elements-by-id  → {id → element}
     ├ :com.fulcrologic.statecharts/ids-in-document-order → [id …]
     └ each element: {:id :node-type :children [child-id…] :parent id}
         :node-type ∈ #{:statechart :state :parallel :final
                        :initial :transition :on-entry …}  (root id = :ROOT)

   `:children` mixes real state children with synthetic :initial/:transition
   nodes — always filter to state node-types. The active configuration is
   `(:config s)`: a vector of active state ids (leaves + their compound
   ancestors, as recorded by the runner)."
  (:require
    [clojure.string :as str]
    [escapement.tui.theme :as theme]
    [escapement.tui.compositor :as cmp]
    [escapement.tui.live :as live]))

(defn chart-from-env
  "Returns the chart value stashed by the facade's `attach-env!` (or nil if not
   stashed). Reads the `:escapement.tui/chart` env-meta key written there."
  [env]
  (some-> env meta :escapement.tui/chart))

(def ^:private sc-elements-by-id-k :com.fulcrologic.statecharts/elements-by-id)

(def ^:private sc-state-node-types #{:state :parallel :final})

(defn- chart-element
  "Element map for `id` in `chart`, or nil."
  [chart id]
  (get-in chart [sc-elements-by-id-k id]))

(defn- chart-parent
  "Parent id of `id` (or nil)."
  [chart id]
  (:parent (chart-element chart id)))

(defn- chart-state-children
  "Document-order vector of `id`'s child STATE ids (filters out synthetic
   :initial/:transition/handler children)."
  [chart id]
  (->> (:children (chart-element chart id))
    (filterv (fn [cid]
               (let [el (chart-element chart cid)]
                 (and (contains? sc-state-node-types (:node-type el))
                   ;; the synthetic <initial> pseudo-state is :node-type :state
                   ;; with :initial? true — not a real sibling phase.
                   (not (:initial? el))))))))

(defn- chart-introspectable?
  "True when `chart` carries the elements-by-id index we walk."
  [chart]
  (boolean (and (map? chart) (map? (get chart sc-elements-by-id-k)))))

(defn- ancestor-path
  "Vector of ids from the chart root (exclusive of :ROOT) down to and
   including `id`, using parent links. Stops at :ROOT/nil."
  [chart id]
  (loop [cur id acc ()]
    (cond
      (or (nil? cur) (= :ROOT cur)) (vec acc)
      :else (recur (chart-parent chart cur) (cons cur acc)))))

(defn- active-leaves
  "Active leaf state ids from the config: ids in `config` that are NOT an
   ancestor of any other id in `config` (i.e. the deepest active states).
   Falls back to the raw config when the chart can't disambiguate."
  [chart config]
  (let [config (vec (distinct config))]
    (if-not (chart-introspectable? chart)
      config
      (let [ancestors (into #{}
                        (mapcat (fn [id] (butlast (ancestor-path chart id))))
                        config)]
        (vec (remove ancestors config))))))

(defn phase-model
  "PURE model of the chart's active phase, for the header phase tracker.
   `chart` is the statechart value (or nil); `config` is the active
   configuration (vector of active state ids, e.g. `(:config s)`).

   Returns a map:
     :breadcrumb — vector of state ids from the root branch to the active
                   compound state (e.g. [:run :judging-r1]); [] when unknown.
     :siblings   — document-order vector of {:id :state :current? bool} for
                   the children of the active compound parent, with the active
                   one flagged; nil when not derivable / parallel.
     :current    — the active leaf/state id the strip centers on (or nil).
     :parallel?  — true when the active config spans multiple parallel
                   regions; in that case :siblings is nil and :leaves is set.
     :leaves     — (parallel only) comma-join-ready vector of active leaf ids.
     :fallback?  — true when the chart is nil/unintrospectable; the renderer
                   then produces today's `states: [...]` line from :raw-config.
     :raw-config — the original config vector (always present)."
  [chart config]
  (let [config (vec config)
        base   {:breadcrumb [] :siblings nil :current nil
                :parallel? false :leaves nil :fallback? false
                :raw-config config}]
    (cond
      (not (chart-introspectable? chart))
      (assoc base :fallback? true :current (last config))

      (empty? config)
      base

      :else
      (let [leaves (active-leaves chart config)]
        (if (> (count leaves) 1)
          ;; Parallel / multi-region: breadcrumb to lowest common ancestor,
          ;; list the leaves, drop the linear strip.
          (let [paths (map #(ancestor-path chart %) leaves)
                lcp   (loop [acc [] cols (apply map vector paths)]
                        (let [col (first cols)]
                          (if (and col (apply = col))
                            (recur (conj acc (first col)) (rest cols))
                            acc)))]
            (assoc base
              :parallel? true
              :breadcrumb lcp
              :leaves leaves))
          ;; Linear: one active branch. Center the strip on the deepest active
          ;; state and show its siblings (children of its parent).
          (let [current (or (first leaves) (last config))
                path    (ancestor-path chart current)
                parent  (chart-parent chart current)
                sibs    (when parent (chart-state-children chart parent))]
            (assoc base
              :current current
              :breadcrumb path
              :siblings (when (seq sibs)
                          (mapv (fn [sid]
                                  {:id sid :state sid :current? (= sid current)})
                            sibs)))))))))

(defn- phase-label
  "Short display label for a state id."
  [id]
  (-> (str id) (str/replace #"^:" "")))

(defn sibling-strip
  "Format `phase-model`'s `:siblings` as a single sliding-window line, fit to
   `width` display columns, centered on the current sibling (`◉`), with `…`
   on each overflowing side. Completed siblings (before current in document
   order) use theme `:phase-done`; upcoming use `:phase-upcoming`; the current
   one uses `:phase-current` and is prefixed with `◉`. Returns \"\" when there
   are no siblings or width<=0."
  [model width theme]
  (let [sibs (:siblings model)]
    (if (or (empty? sibs) (<= width 0))
      ""
      (let [n      (count sibs)
            cur-i  (or (first (keep-indexed (fn [i s] (when (:current? s) i)) sibs)) 0)
            sep    " · "
            ;; Grow a symmetric window around the current index until adding
            ;; the next sibling would overflow the width budget.
            piece  (fn [i]
                     (let [s   (nth sibs i)
                           lbl (phase-label (:id s))
                           txt (if (:current? s) (str "◉ " lbl) lbl)
                           key (cond (:current? s) :phase-current
                                     (< i cur-i)   :phase-done
                                     :else         :phase-upcoming)]
                       {:plain txt :painted (theme/paint theme key txt)}))
            fits?  (fn [lo hi left-ell? right-ell?]
                     (let [items   (map piece (range lo (inc hi)))
                           plains  (map :plain items)
                           joined  (str/join sep plains)
                           ell-l   (if left-ell?  (str "… " ) "")
                           ell-r   (if right-ell? (str " …") "")]
                       (<= (cmp/display-width (str ell-l joined ell-r)) width)))]
        (loop [lo cur-i hi cur-i]
          (let [le? (> lo 0)
                re? (< hi (dec n))
                ;; Try expanding left then right alternately.
                grow-l (and le? (fits? (dec lo) hi true re?))
                grow-r (and re? (fits? lo (inc hi) le? true))]
            (cond
              grow-l (recur (dec lo) hi)
              grow-r (recur lo (inc hi))
              :else
              (let [items   (map piece (range lo (inc hi)))
                    joined  (str/join sep (map :painted items))
                    ell-l   (if (> lo 0)        (theme/paint theme :phase-upcoming "… ") "")
                    ell-r   (if (< hi (dec n))  (theme/paint theme :phase-upcoming " …") "")]
                (cmp/truncate-display (str ell-l joined ell-r) width)))))))))

(defn- session-tps-sum
  "Aggregate tokens/sec across all ACTIVE (:streaming/:waiting) sessions in
   the live map: the sum of each active session's `live-tps`."
  [s]
  (->> (vals (:live s))
    (mapcat (comp vals :sessions))
    (filter #(#{:streaming :waiting} (:status %)))
    (map live/live-tps)
    (reduce + 0.0)))

(defn- live-active-count
  "Number of active (:streaming/:waiting) sessions across all groups."
  [s]
  (->> (vals (:live s))
    (mapcat (comp vals :sessions))
    (filter #(#{:streaming :waiting} (:status %)))
    count))

(defn- live-total-count
  "Total number of sessions tracked across all groups."
  [s]
  (->> (vals (:live s)) (mapcat (comp vals :sessions)) count))

(defn- elapsed-clock
  "Format elapsed ms as M:SS (or H:MM:SS past an hour)."
  [ms]
  (let [secs (quot (max 0 (long ms)) 1000)
        h    (quot secs 3600)
        m    (quot (mod secs 3600) 60)
        sec  (mod secs 60)]
    (if (pos? h)
      (format "%d:%02d:%02d" h m sec)
      (format "%d:%02d" m sec))))

(defn header-lines
  "Build the three header-strip body lines (interior content, NOT bordered —
   the frame integrator draws them into the header box). `h` is the TUI handle,
   `s` its dereferenced state, `theme` a theme map, `width` the interior width.

   Returns a vector of exactly THREE strings, each fit to `width` display
   columns:
     1. `escapement · <chart>` (bold) `· <session>` (dim)   ◷ <elapsed> (right)
     2. breadcrumb `▶ run › judging-r1`  ·····  N LLMs · N act · <agg> t/s (right)
     3. the sibling strip (linear), comma-joined leaves (parallel), or the
        `states: [...]` fallback line.

   Elapsed is derived from `(:start-ts s)` (a unix-ms session-start stamp set
   at TUI start; see `start!`) against `now-ms` (a 5th arg, defaulting to
   wall-clock so the model stays testable)."
  ([h s theme width] (header-lines h s theme width (System/currentTimeMillis)))
  ([h s theme width now-ms]
   (let [w        (max 0 width)
         ;; --- line 1: title + clock ---
         chart-nm (str (:chart-sym h))
         sess     (str (:session-short h))
         elapsed  (elapsed-clock (- now-ms (long (or (:start-ts s) now-ms))))
         clock    (theme/paint theme :timestamp (str "◷ " elapsed))
         left1    (str (theme/paint theme :chart-name (str "escapement · " chart-nm))
                    " " (theme/paint theme :session-id (str "· " sess)))
         line1    (let [lw (cmp/display-width left1)
                        rw (cmp/display-width clock)
                        gap (max 1 (- w lw rw))]
                    (cmp/truncate-display
                      (if (>= (+ lw rw 1) w)
                        left1
                        (str left1 (apply str (repeat gap \space)) clock))
                      w))
         model    (phase-model (chart-from-env (some-> (:env h) deref)) (:config s []))
         ;; --- line 2: breadcrumb + metrics ---
         crumb    (let [ids (:breadcrumb model)]
                    (cond
                      (:parallel? model)
                      (str "▶ " (str/join " › " (map phase-label ids))
                        (when (seq ids) " › ") "⫶ ("
                        (count (:leaves model)) " regions)")
                      (seq ids)
                      (str "▶ " (str/join " › " (map phase-label ids)))
                      :else "▶ —"))
         crumb-p  (theme/paint theme :phase-current crumb)
         n-llm    (live-total-count s)
         n-act    (live-active-count s)
         agg      (session-tps-sum s)
         metrics  (theme/paint theme :metric
                    (format "%d LLMs · %d act · %.0f t/s" n-llm n-act agg))
         line2    (let [lw (cmp/display-width crumb-p)
                        rw (cmp/display-width metrics)
                        gap (max 1 (- w lw rw))]
                    (cmp/truncate-display
                      (if (>= (+ lw rw 1) w)
                        crumb-p
                        (str crumb-p (apply str (repeat gap \space)) metrics))
                      w))
         ;; --- line 3: sibling strip / leaves / fallback ---
         line3    (cond
                    (:fallback? model)
                    (cmp/truncate-display (str "states: " (pr-str (:raw-config model))) w)

                    (:parallel? model)
                    (cmp/truncate-display
                      (str/join " · "
                        (map (fn [lid]
                               (theme/paint theme :phase-current (str "◉ " (phase-label lid))))
                          (:leaves model)))
                      w)

                    (:siblings model)
                    (sibling-strip model w theme)

                    :else
                    (cmp/truncate-display (str "states: " (pr-str (:raw-config model))) w))]
     [line1 line2 line3])))
