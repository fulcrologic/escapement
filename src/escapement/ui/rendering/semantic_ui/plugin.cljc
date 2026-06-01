(ns escapement.ui.rendering.semantic-ui.plugin
  "Installable Semantic-UI RAD rendering adapter for the statechart-driven explorer (fulcro-rad-
   statecharts 0.1.5, RAD 1.6.24). A HYBRID, exactly like the in-repo TUI plugin:

     1. DEFMETHODS (the real work): requiring `escapement.ui.rendering.semantic-ui.{report,field,form}`
        installs the 1.6.24 multimethods the statechart `defsc-report`/`defsc-form` actually dispatch
        to — `report-render/render-report` (& body/row/controls/header/footer),
        `form-render/render-field`, `…statechart.form/render-element`, and
        `…statechart.control/render-control`. All emit Semantic-UI-classed DOM.

     2. CONTROLS MAP (`all-controls`): the legacy map you install with
        `com.fulcrologic.rad.statechart.application/install-ui-controls!` (or `rad-app/`). The
        statechart report path no longer reads the report/element layouts from here (it uses the
        multimethods above), but the map is provided for parity with the semantic-ui plugin's shape
        and so any code that still consults `…control/type->style->control` /
        `…report/style->layout` finds Semantic-UI entries. The leaves point at this adapter's render
        fns, NOT fulcro-rad-semantic-ui's `defn`s (those bind RAD 1.6.18's legacy UISM helpers — see
        the field/report ns docstrings)."
  (:require
    ;; Required for side effects — installs the defmethods that do the actual rendering:
    [escapement.ui.rendering.semantic-ui.field :as field]
    [escapement.ui.rendering.semantic-ui.form]
    [escapement.ui.rendering.semantic-ui.report :as report]))

(def all-controls
  "The Semantic-UI RAD control set. Install with `install-ui-controls!` before mounting. (Report and
   form-structure rendering happens via the multimethods installed by requiring the sibling nss; this
   map exists for parity + any legacy controls-map consumer.)"
  {:com.fulcrologic.rad.report/style->layout
   {:default report/render-report-layout}

   :com.fulcrologic.rad.report/row-style->row-layout
   {:default report/render-table-row}

   :com.fulcrologic.rad.report/control-style->control
   {:default report/render-standard-controls}

   :com.fulcrologic.rad.control/type->style->control
   {:button  {:default field/render-button-control}
    :string  {:default field/render-string-control
              :search  field/render-string-control}
    :boolean {:default field/render-boolean-control
              :toggle  field/render-boolean-control}}})
