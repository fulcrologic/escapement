(ns escapement.ui.rendering.tui.plugin
  "The installable fulcro-tui RAD rendering plugin. `all-controls` is the map you install with
   `com.fulcrologic.rad.application/install-ui-controls!` (same shape as the semantic-ui plugin's
   `all-controls`); each leaf is a render fn that returns a fulcro-tui element node instead of DOM."
  (:require
    ;; Required for side effects:
    ;;  * com.fulcrologic.rad.report installs the `:default` methods of the report render multimethods
    ;;    (rr/render-report|render-row|render-controls) that bridge to the installed ::rad/controls map
    ;;    (report.impl calls those multimethods but does not install the bridges itself).
    ;;  * escapement.ui.rendering.tui.form registers the form `render-element` defmethods (0.1.5 form
    ;;    structural rendering is defmethod-based, not controls-map-based).
    [com.fulcrologic.rad.report]
    [com.fulcrologic.rad.form]
    [escapement.ui.rendering.tui.field :as field]
    [escapement.ui.rendering.tui.form]
    [escapement.ui.rendering.tui.report :as report]))

(def all-controls
  "The fulcro-tui RAD control set. Install with `rad-application/install-ui-controls!` before mounting.
   Renders RAD reports + form *fields*/controls as fulcro-tui element maps. (Form *structural* elements
   — container/body/ref-container — are rendered by `render-element` defmethods in
   `escapement.ui.rendering.tui.form`, per the 0.1.5 contract, not via this map.)"
  {;; ── Report renderers ──────────────────────────────────────────────────────
   :com.fulcrologic.rad.report/style->layout
   {:default report/render-table-report-layout}

   :com.fulcrologic.rad.report/row-style->row-layout
   {:default report/render-table-row}

   :com.fulcrologic.rad.report/control-style->control
   {:default report/render-standard-controls}

   ;; ── Generic controls (buttons / inputs / toggles / pickers on reports/forms) ──
   :com.fulcrologic.rad.control/type->style->control
   {:button  {:default field/render-button-control}
    :string  {:default field/render-string-control}
    :boolean {:default field/render-boolean-control
              :toggle  field/render-boolean-control}
    :picker  {:default field/render-picker-control}}})
