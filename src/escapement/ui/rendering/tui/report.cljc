(ns escapement.ui.rendering.tui.report
  "TUI report renderers for the RAD statechart engine.

   * `render-table-report-layout` `(fn [report-instance])` - the whole report: the control bar, a
     header `hbox` of column labels, then a `viewport` of row `hbox`es (via `report/render-row`).
   * `render-table-row` `(fn [report-instance row-class row-props])` - one selectable row. Each row
     is a `button` whose activation either follows a form-link (`form/edit!`) or selects the row
     (`report/select-row!`).
   * `render-standard-controls` `(fn [report-instance])` - the control bar: action buttons and input
     controls, rendered via `control/render-control`."
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.tui.elements :as e :refer [vbox hbox text button line viewport]]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.rad.control :as control]
    [com.fulcrologic.rad.options-util :refer [?!]]
    [com.fulcrologic.rad.report :as-alias report]
    [com.fulcrologic.rad.statechart.form :as form]
    [com.fulcrologic.rad.statechart.report :as screport]))

(def ^:private col-width
  "Display width (in cells) of each report column."
  18)

(defn- column-label
  "Returns the heading label for report `column` on `report-instance`."
  [report-instance {::report/keys [column-heading] ::attr/keys [qualified-key] :as column}]
  (or (?! column-heading report-instance)
    (?! (ao/label column) report-instance)
    (some-> qualified-key name str/capitalize)
    ""))

(defn- sort-state
  "Returns the report's current sort parameters map `{:sort-by k :ascending? bool :sortable-columns set}`."
  [report-instance]
  (get-in (comp/props report-instance) [:ui/parameters ::report/sort]))

(defn- render-column-header
  "Renders one column heading. Columns named in `:sortable-columns` render as a focusable button that
   toggles the report sort (an ▲/▼ arrow marks the active sort column + direction); others render as
   plain header text."
  [report-instance {::attr/keys [qualified-key] :as column}]
  (let [{:keys [sort-by ascending? sortable-columns]} (sort-state report-instance)
        sortable? (contains? (set sortable-columns) qualified-key)
        active?   (= sort-by qualified-key)
        arrow     (cond (not active?) "" ascending? " ▲" :else " ▼")
        label     (str (column-label report-instance column) arrow)]
    (if sortable?
      (let [hid (keyword "sorth" (str (namespace qualified-key) "_" (name qualified-key)))]
        (button {:id          hid :width col-width :bold true
                 :color       (if active? :bright-yellow :bright-cyan)
                 :highlight   (e/focused? hid)
                 :on-activate (fn [] (screport/sort-rows! report-instance column))}
          label))
      (text {:width col-width :bold true :color :bright-cyan} label))))

(defn render-table-row
  "Renders one report row as a focusable hbox of column cells. Activating the row (Enter/Space) opens
   its edit form — a `ro/form-links` entry on ANY column — or, failing that, runs the report's first
   `ro/row-action`. Terminals have no separate row-selection concept, so focus IS the highlight."
  [report-instance _row-class row-props]
  (let [{::report/keys [columns row-actions]} (comp/component-options report-instance)
        {::report/keys [idx]} (comp/get-computed row-props)
        link       (some (fn [c] (screport/form-link report-instance row-props (::attr/qualified-key c)))
                     columns)
        row-id     (keyword "row" (str idx))
        cells      (mapv (fn [column]
                           (text {:width col-width}
                             (str (screport/formatted-column-value report-instance row-props column))))
                     columns)
        activate   (fn []
                     (cond
                       link              (form/edit! report-instance (:edit-form link) (:entity-id link))
                       (seq row-actions) ((:action (first row-actions)) report-instance row-props)))]
    ;; A focusable hbox (NOT a button): an `:id` + `:on-activate` makes any node focusable and
    ;; Enter/Space-activatable, and an hbox lays out the column cells. A `button` is a text leaf
    ;; and would stringify the cell layout.
    (hbox {:id          row-id
           :highlight   (e/focused? row-id)
           :on-activate activate}
      cells)))

(defn render-standard-controls
  "Renders the report control bar: a row of action buttons followed by the input control rows, each
   rendered via `control/render-control`."
  [report-instance]
  (let [{:keys [action-layout input-layout]} (control/standard-control-layout report-instance)]
    (vbox {}
      (when (seq action-layout)
        (hbox {:height 1}
          (mapv (fn [k] (control/render-control report-instance k)) action-layout)))
      (mapv (fn [row]
              (vbox {}
                (mapv (fn [k] (control/render-control report-instance k)) row)))
        input-layout))))

(defn render-page-nav
  "Renders the pagination bar — Prev / Next buttons flanking a `Page X / Y` indicator — when the
   report paginates and has more than one page. Buttons are focusable nodes wired to the statechart's
   `prior-page!`/`next-page!`; a button at the first/last page is shown disabled (dimmed, inert)."
  [report-instance]
  (when (?! (comp/component-options report-instance ::report/paginate?) report-instance)
    (let [page  (screport/current-page report-instance)
          pages (max 1 (screport/page-count report-instance))]
      (when (> pages 1)
        (let [first? (<= page 1)
              last?  (>= page pages)
              nav    (fn [id shortcut label disabled? activate]
                       (button (cond-> {:id          id
                                        :color       (if disabled? :bright-black :cyan)
                                        :highlight   (and (not disabled?) (e/focused? id))
                                        :on-activate (fn [] (when-not disabled? (activate)))}
                                 (not disabled?) (assoc :shortcut shortcut))
                         label))]
          (hbox {:height 1}
            (nav :report/prev-page [:alt "p"] " ◀ Prev " first?
              (fn [] (screport/prior-page! report-instance)))
            (text {:width 14 :bold true :color :bright-cyan}
              (str "  Page " page " / " pages "  "))
            (nav :report/next-page [:alt "n"] " Next ▶ " last?
              (fn [] (screport/next-page! report-instance)))))))))

(defn render-table-report-layout
  "Renders the whole table report: the control bar, a column header row, and a scrolling viewport of
   data rows (each via `report/render-row`)."
  [report-instance]
  (let [{::report/keys [columns]} (comp/component-options report-instance)
        render-controls (screport/control-renderer report-instance)
        rows            (screport/current-rows report-instance)]
    ;; `:grow 1` makes the report fill the vertical space the root frame hands it (rather than just
    ;; its content height), so the rows viewport below — also `:grow 1` — can expand to fill the
    ;; screen and scroll, instead of being pinned to a small fixed height.
    (vbox {:border? true :color :cyan :padding 1 :grow 1}
      (when render-controls
        (render-controls report-instance))
      (render-page-nav report-instance)
      (line {})
      (hbox {:height 1}
        (mapv (fn [c] (render-column-header report-instance c)) columns))
      (line {})
      ;; The rows scroll inside a viewport that hogs the leftover height (`:grow 1`). Arrowing
      ;; through the focusable rows autoscrolls it (viewport-follows-focus); PageUp/PageDown page it.
      (viewport {:id :report-rows :grow 1 :border? true :color :bright-black}
        (vbox {}
          (if (seq rows)
            (map-indexed
              (fn [idx row]
                (screport/render-row report-instance nil
                  (comp/computed row {::report/idx idx
                                      :highlighted? (= idx (screport/currently-selected-row report-instance))})))
              rows)
            (text {:color :bright-black} "No rows.")))))))
