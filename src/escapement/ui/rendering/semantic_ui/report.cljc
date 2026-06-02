(ns escapement.ui.rendering.semantic-ui.report
  "Semantic-UI report renderers for the RAD statechart engine (fulcro-rad-statecharts 0.1.5, RAD
   1.6.24).

   Structure copied verbatim from the 1.6.24 *headless* report renderer
   (`com.fulcrologic.rad.rendering.headless.report`) — the SAME `rr/render-report`,
   `rr/render-body`, `rr/render-row`, `rr/render-controls`, `rr/render-header`, `rr/render-footer`
   `:default` defmethods — because the statechart `defsc-report` renders exclusively through those
   `report-render` multimethods (its `render-layout` does NOT consult the `…report/style->layout`
   controls map). The bodies emit Semantic-UI-classed markup (`ui selectable table`, `ui buttons`,
   `ui top attached compact segment`, `ui pagination menu`) so the SPA renders STYLED.

   Why not reuse `fulcro-rad-semantic-ui`'s report `defn`s (`render-table-report-layout`, …)? They
   were written against RAD 1.6.18's legacy report state machine: they `require`
   `com.fulcrologic.rad.report` / `…rad.control` and call `report/control-renderer`,
   `report/current-rows`, `control/component-controls`, etc. — the UISM variants the statechart stack
   replaces with `com.fulcrologic.rad.statechart.{report,control}`. They are also never reached
   because the statechart report dispatches via the multimethods above, not the controls map. So the
   1.6.18→1.6.24 adaptation here is: keep Semantic-UI's CSS class vocabulary, drive it from the
   statechart `report`/`control` helpers."
  (:require
    #?(:cljs [com.fulcrologic.fulcro.dom :as dom]
       :clj  [com.fulcrologic.fulcro.dom-server :as dom])
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.rad.form-options :as fo]
    [com.fulcrologic.rad.options-util :refer [?!]]
    [com.fulcrologic.rad.report-options :as ro]
    [com.fulcrologic.rad.report-render :as rr]
    [com.fulcrologic.rad.statechart.control :as control]
    [com.fulcrologic.rad.statechart.form :as form]
    [com.fulcrologic.rad.statechart.report :as report]))

(defn- render-report-controls
  "Renders the control bar: input control rows on the left, action buttons on the right, each via the
   `statechart.control/render-control` multimethod. Laid out with flexbox (NOT a CSS float): a
   right-floated button group collapses its parent segment, letting the following full-width `ui form`
   paint ON TOP of the buttons and swallow their clicks. The flex row keeps the form and buttons as
   side-by-side, non-overlapping siblings so the buttons stay clickable."
  [report-instance _options]
  (let [{:keys [action-layout input-layout]} (control/standard-control-layout report-instance)
        controls (control/component-controls report-instance)]
    (dom/div {:className "ui top attached compact segment"}
      (dom/div {:style {:display         "flex"
                        :alignItems      "flex-end"
                        :justifyContent  "space-between"
                        :gap             "1em"}}
        ;; Inputs on the left (grows to fill, pushing the buttons to the right). Empty spacer when none.
        (if (seq input-layout)
          (dom/div {:className "ui form" :style {:flex "1 1 auto" :margin 0}}
            (mapv (fn [row]
                    (dom/div {:key (str row) :className "fields"}
                      (mapv (fn [control-key]
                              (when (keyword? control-key)
                                (let [{:keys [type] :or {type :string}} (get controls control-key)
                                      style (or (:style (get controls control-key)) :default)]
                                  (control/render-control type style report-instance control-key))))
                        row)))
              input-layout))
          (dom/div {:style {:flex "1 1 auto"}}))
        ;; Action buttons on the right.
        (dom/div {:className "ui buttons" :style {:flex "0 0 auto"}}
          (mapv (fn [control-key]
                  (let [{:keys [type] :or {type :button}} (get controls control-key)
                        style (or (:style (get controls control-key)) :default)]
                    (control/render-control type style report-instance control-key)))
            action-layout))))))

(defn- render-column-headings
  "Renders the `<thead>` row of column headings (clicking a heading sorts that column)."
  [report-instance options]
  (let [heading-descriptors (report/column-heading-descriptors report-instance options)]
    (dom/thead nil
      (dom/tr nil
        (mapv (fn [{:keys [label column help]}]
                (let [qk (str (ao/qualified-key column))]
                  (dom/th {:key     qk
                           :title   (or help "")
                           :onClick (fn [_] (report/sort-rows! report-instance column))}
                    label)))
          heading-descriptors)))))

(defn- row-form-link
  "Resolves a `ro/form-links` entry for `qualified-key`, returning `{:edit-form :entity-id}` or nil."
  [_report-instance options row-props qualified-key]
  (let [form-links (or (get options ro/form-links)
                     (let [row-class (ro/BodyItem options)]
                       (when row-class (get (comp/component-options row-class) ro/form-links))))
        cls        (rc/registry-key->class (get form-links qualified-key))
        id-key     (some-> cls (comp/component-options fo/id) ao/qualified-key)]
    (when cls
      {:edit-form cls :entity-id (get row-props id-key)})))

(defn- render-report-row
  "Renders one `<tr>` of cells (form-link cells become `<a>`) plus a trailing actions cell of
   Semantic-UI buttons when the report declares `ro/row-actions`."
  [report-instance options row-props idx]
  (let [columns     (ro/columns options)
        row-actions (ro/row-actions options)
        highlighted? (= idx (report/currently-selected-row report-instance))]
    (dom/tr {:key       (str "row-" idx)
             :className (when highlighted? "active")
             :onClick   (fn [_] (report/select-row! report-instance idx))}
      (mapv (fn [col-attr]
              (let [qualified-key (ao/qualified-key col-attr)
                    cell-text     (report/formatted-column-value report-instance row-props col-attr)]
                (dom/td {:key (str qualified-key)}
                  (if-let [{:keys [edit-form entity-id]} (row-form-link report-instance options row-props qualified-key)]
                    (dom/a {:onClick (fn [_] (form/edit! (comp/any->app report-instance) edit-form entity-id))}
                      cell-text)
                    (dom/span nil cell-text)))))
        columns)
      (when (seq row-actions)
        (dom/td {:className "collapsing"}
          (dom/div {:className "ui buttons"}
            (mapv (fn [{:keys [label action disabled?]}]
                    (let [label-str (?! label report-instance row-props)]
                      (dom/button {:className "ui tiny button"
                                   :key       (str label-str)
                                   :disabled  (boolean (?! disabled? report-instance row-props))
                                   :onClick   (fn [evt]
                                                #?(:cljs (.stopPropagation evt))
                                                (when action (action report-instance row-props)))}
                        label-str)))
              row-actions)))))))

(defn- render-pagination
  "Renders a Semantic-UI pagination menu when the report paginates beyond one page."
  [report-instance _options]
  (let [current (report/current-page report-instance)
        total   (report/page-count report-instance)]
    (when (> total 1)
      (dom/div {:className "ui pagination menu"}
        (dom/a {:className (str "icon item" (when (= current 1) " disabled"))
                :onClick   (fn [_] (report/prior-page! report-instance))}
          "‹")
        (dom/div {:className "item"} (str "Page " current " of " total))
        (dom/a {:className (str "icon item" (when (>= current total) " disabled"))
                :onClick   (fn [_] (report/next-page! report-instance))}
          "›")))))

;; -- Controls-map entry points (for `all-controls` parity) --------------------
;; The statechart report path renders via the multimethods below, not these; they exist so the
;; legacy `…report/style->layout` / `…row-style->row-layout` / `…control-style->control` map has
;; Semantic-UI leaves. Signatures match the controls-map contract (see plugin-rendering.md).

(defn render-report-layout
  "Controls-map report layout entry: `(fn [report-instance])`. Delegates to the SUI report body."
  [report-instance]
  (let [options (comp/component-options report-instance)]
    (rr/render-report report-instance options)))

(defn render-table-row
  "Controls-map row entry: `(fn [report-instance row-class row-props])`."
  [report-instance _row-class row-props]
  (render-report-row report-instance (comp/component-options report-instance) row-props
    (or (:row-index (meta row-props)) (::report/idx (comp/get-computed row-props)) 0)))

(defn render-standard-controls
  "Controls-map control-bar entry: `(fn [report-instance])`."
  [report-instance]
  (render-report-controls report-instance (comp/component-options report-instance)))

;; -- Multimethod registrations (copied arities from headless.report) ----------

(defmethod rr/render-report :default [report-instance options]
  (let [title    (ro/title options)
        loading? (report/loading? report-instance)]
    (dom/div {:className "ui container"}
      (when title
        (dom/h2 {:className "ui header"} (?! title report-instance)))
      (when loading?
        (dom/div {:className "ui active inline loader"}))
      (rr/render-controls report-instance options)
      (rr/render-body report-instance options)
      (rr/render-footer report-instance options))))

(defmethod rr/render-body :default [report-instance options]
  (let [rows (report/current-rows report-instance)]
    (dom/table {:className "ui selectable celled attached table"}
      (render-column-headings report-instance options)
      (dom/tbody nil
        (if (seq rows)
          (into []
            (map-indexed (fn [idx row-props]
                           (rr/render-row report-instance options
                             (with-meta row-props {:row-index idx}))))
            rows)
          (dom/tr nil
            (dom/td {:colSpan (count (ro/columns options))} "No rows")))))))

(defmethod rr/render-row :default [report-instance options row-props]
  (render-report-row report-instance options row-props
    (or (:row-index (meta row-props)) 0)))

(defmethod rr/render-controls :default [report-instance options]
  (render-report-controls report-instance options))

(defmethod rr/render-header :default [report-instance options]
  (render-column-headings report-instance options))

(defmethod rr/render-footer :default [report-instance options]
  (render-pagination report-instance options))
