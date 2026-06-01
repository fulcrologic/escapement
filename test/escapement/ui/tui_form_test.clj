(ns escapement.ui.tui-form-test
  "Headless integration test that MOUNTS a RAD statechart form via the fulcro-tui rendering plugin so
   the form-container + field renderers actually paint to a string-terminal and are asserted.

   The Escapement explorer is read-only (reports only — no RAD form is ever mounted), so the form +
   attributes defined HERE are TEST-ONLY: they exist solely to exercise
   `escapement.ui.rendering.tui.form` (the `render-element` defmethods for :form-container /
   :form-controls / :form-body-container) and `escapement.ui.rendering.tui.field` (the
   `fr/render-field` defmethods for :string / :int / :keyword(:enum) / :boolean). They are NOT part of
   the shipped explorer UI.

   The form is created (`scform/create!` → tempid → blank entity, no server round-trip required) under a
   tiny routing chart whose single `form-route-state` mounts it. A `string-terminal` is attached and the
   helpers POLL the painted screen until the form appears (the statechart event loop + create flow are
   asynchronous). JVM-only (RAD + fulcro-tui stack); excluded from `bb test` (see runner's
   `jvm-only-namespaces`) and run via `clojure -M:ui-test`."
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.headless.loopback-remotes :as lb]
    [com.fulcrologic.fulcro.tui.application :as tui-app]
    [com.fulcrologic.fulcro.tui.terminal :as term]
    [com.fulcrologic.guardrails.config :as grc]
    [com.fulcrologic.rad.application :as rad-app]
    [com.fulcrologic.rad.attributes :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.rad.form-options :as fo]
    [com.fulcrologic.rad.statechart.form :as scform :refer [defsc-form]]
    [com.fulcrologic.statecharts.chart :refer [statechart]]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.integration.fulcro.routing :as scr]
    [escapement.ui.rendering.tui.plugin :as tui-plugin]
    [escapement.ui.rendering.tui.root :as tui-root]
    [fulcro-spec.core :refer [=> assertions specification]]
    [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; TEST-ONLY RAD attributes + form
;;
;; A tiny "widget" entity exercising four field types so the corresponding `fr/render-field`
;; defmethods fire: :string (render-string-field), :int (render-int-field), :enum
;; (render-enum-field), :boolean (render-boolean-field). All attributes are virtual (no ao/schema);
;; the form is created client-side (tempid), so no storage/resolver is needed.
;; ---------------------------------------------------------------------------

(defattr widget-id :widget/id :uuid
  {ao/identity? true
   ao/label     "Widget"})

(defattr widget-name :widget/name :string
  {ao/identities #{:widget/id}
   ao/required?  true
   ao/label      "Widget Name"})

(defattr widget-quantity :widget/quantity :int
  {ao/identities #{:widget/id}
   ao/label      "Quantity"})

(defattr widget-status :widget/status :enum
  {ao/identities        #{:widget/id}
   ao/label             "Status"
   ao/enumerated-values #{:widget.status/draft :widget.status/active :widget.status/retired}
   ao/enumerated-labels {:widget.status/draft   "Draft"
                         :widget.status/active  "Active"
                         :widget.status/retired "Retired"}})

(defattr widget-active? :widget/active? :boolean
  {ao/identities #{:widget/id}
   ao/label      "Active?"})

(defsc-form WidgetForm [this props]
  {fo/id             widget-id
   fo/title          "Edit Widget"
   fo/attributes     [widget-name widget-quantity widget-status widget-active?]
   fo/default-values {:widget/name     "Sprocket"
                      :widget/quantity 7
                      :widget/status   :widget.status/active
                      :widget/active?  true}
   fo/layout         [[:widget/name]
                      [:widget/quantity]
                      [:widget/status]
                      [:widget/active?]]})

(def routing-chart
  "A minimal routing chart whose single route state mounts `WidgetForm` (test-only)."
  (statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :state/root :routing/root tui-root/Routes}
        (scform/form-route-state {:route/target WidgetForm :route/params #{:id}})))))

;; ---------------------------------------------------------------------------
;; Screen helpers (poll the painted terminal — mount/create is async)
;; ---------------------------------------------------------------------------

(defn- screen-text
  "Returns the painted screen of `app` as a single newline-joined string (trailing blanks trimmed)."
  [app]
  (tui-app/render! app)
  (str/join "\n" (mapv str/trimr (tui-app/screen-of app))))

(defn- wait-for
  "Renders `app` and returns the screen text once it matches `re`, polling up to ~20s. Returns the
   last screen on timeout (so the assertion fails with the real screen, not a hang)."
  [app re]
  (loop [n 0]
    (let [screen (screen-text app)]
      (if (or (re-find re screen) (>= n 80))
        screen
        (do (Thread/sleep 250) (recur (inc n)))))))

;; The `:ui-test` alias runs guardrails in `:runtime` mode (`-Dguardrails.enabled=true`). The
;; fulcro-tui engine's render/place/paint fns are heavily `>defn`-instrumented over LARGE data
;; structures; conforming those on every engine call makes a single `render!` take SECONDS-to-minutes
;; (which, compounded by the polling `wait-for`, looks like an infinite hang). Excluding the two hot
;; engine namespaces from runtime checks drops a render from minutes to ~25ms while leaving guardrails
;; on for everything else. Done once, lazily, the first time an app is built. (Mirrors
;; `escapement.ui.tui-render-test`.)
(defonce ^:private engine-checks-excluded
  (do
    (grc/exclude-checks! 'com.fulcrologic.fulcro.tui.engine)
    (grc/exclude-checks! 'com.fulcrologic.fulcro.tui.application)
    true))

(defn- test-app
  "Builds a headless client app that mounts the TUI RAD render plugin + statechart engine + the tiny
   routing chart, attaches a `string-terminal`, and returns it. The loopback remote returns `{}` for any
   EQL — the form is CREATED (tempid), so no server data is needed to paint it."
  []
  engine-checks-excluded
  (let [app (tui-app/application
              {:root-class tui-root/Root
               :remotes    {:remote (lb/sync-remote (fn [_eql] {}))}})]
    (rad-app/install-ui-controls! app tui-plugin/all-controls)
    (scf/install-fulcro-statecharts! app {:event-loop? true})
    (scr/start! app routing-chart)
    (tui-app/attach! app (term/string-terminal {:rows 40 :cols 120}))
    app))

(specification "form mount: WidgetForm renders the form-container + field renderers"
  (log/with-merged-config {:min-level :error}
    (let [app    (test-app)
          ;; Create a blank Widget (tempid) — mounts WidgetForm via the form-route-state. The
          ;; fo/default-values seed the field cells so we can assert real, known values.
          _      (scform/create! app WidgetForm)
          ;; Poll for the seeded :string value, NOT the title: the title paints immediately when the
          ;; form-container mounts, but the `start-create-expr` default-values merge (which fills every
          ;; field cell) lands a beat later via the statechart event loop. Waiting on "Sprocket"
          ;; guarantees the create flow has fully seeded the entity before we assert.
          screen (wait-for app #"Sprocket")]
      (assertions
        "renders the form-container frame with its title (render-element :form-container)"
        (boolean (re-find #"Edit Widget" screen)) => true
        "renders the form-controls Save/Undo/Cancel action buttons (render-element :form-controls)"
        (and (boolean (re-find #"Save" screen))
          (boolean (re-find #"Undo" screen))
          (boolean (re-find #"Cancel" screen))) => true
        "renders the :string field label, marking it required (render-string-field)"
        (boolean (re-find #"Widget Name\*" screen)) => true
        "renders the :string field's seeded value cell from default-values"
        (boolean (re-find #"Sprocket" screen)) => true
        "renders the :int field label + its seeded value cell (render-int-field)"
        (and (boolean (re-find #"Quantity" screen)) (boolean (re-find #"\b7\b" screen))) => true
        "renders the :enum field label + its current-label picker button (render-enum-field)"
        (and (boolean (re-find #"Status" screen)) (boolean (re-find #"Active ▾" screen))) => true
        "renders the :boolean field as an [x]/[ ] toggle showing the seeded true value (render-boolean-field)"
        (boolean (re-find #"\[x\] Yes" screen)) => true))))
