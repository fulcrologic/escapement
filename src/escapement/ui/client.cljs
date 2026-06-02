(ns escapement.ui.client
  "Browser SPA entry point for the Escapement read surface.

   Mounts the statechart-driven RAD explorer (`escapement.ui.screens.*`) against the escapement
   server's read-only `/api`. The `:remote` posts transit EQL to that endpoint — the same resolvers
   the TUI/CLI use; when this SPA is served by `--api-server` the origin matches (no CORS dance), and
   under `shadow watch` the server's permissive CORS headers cover the differing dev origin.

   Rendering uses the in-repo Semantic-UI adapter (`escapement.ui.rendering.semantic-ui.plugin`):
   requiring it installs the 1.6.24 render multimethods (report/field/form-structure/control) that
   emit Semantic-UI-classed DOM, and `init` also installs its `all-controls` map. The Semantic/
   Fomantic CSS is linked from `index.html`.

   Fulcro Inspect is installed via `ido` (see fulcro-inspect README); the body is elided from release
   builds by Closure DCE, so shipping this in the served release costs nothing in production."
  (:require
    [com.fulcrologic.devtools.common.target :refer [ido]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.networking.http-remote :as net]
    [com.fulcrologic.rad.statechart.application :as rad-app]
    [com.fulcrologic.statecharts.integration.fulcro.routing :as scr]
    ;; Required for side effects: registers the Semantic-UI RAD render multimethods via defmethod.
    [escapement.ui.rendering.semantic-ui.plugin :as sui]
    [escapement.ui.screens.root :refer [Root]]
    [escapement.ui.screens.routing :refer [routing-chart]]
    [escapement.ui.screens.sessions-report :refer [SessionsReport]]
    [fulcro.inspect.tool :as it]
    [taoensso.timbre :as log]))

(defonce app
  ;; The `:remote` posts transit EQL to the escapement server's read-only `/api`. Same origin when
  ;; served by `--api-server`; permissive CORS covers the dev origin under `shadow watch`.
  (rad-app/fulcro-rad-app
    {:remotes {:remote (net/fulcro-http-remote {:url "/api"})}}))

(defn ^:export refresh
  "shadow `:after-load` hook — re-mount after hot reload."
  []
  (app/mount! app Root "app" {:initialize-state? false}))

(defn ^:export init
  "Module `:init-fn`. Boots the RAD explorer: installs initial state + statecharts, starts routing,
   installs URL sync, mounts the SPA, and lands on the SessionsReport. Installs Fulcro Inspect
   (dev-only; DCE-elided from release)."
  []
  (log/info "Starting Escapement UI")
  (app/set-root! app Root {:initialize-state? true})
  ;; Install the Semantic-UI controls map (the defmethod half is installed by the `sui` require).
  (rad-app/install-ui-controls! app sui/all-controls)
  (rad-app/install-statecharts! app {:event-loop? true})
  (rad-app/start-routing! app routing-chart)
  (rad-app/install-url-sync! app)
  (app/mount! app Root "app" {:initialize-state? false})
  ;; Land on the sessions explorer (run-on-mount? in the report + initial route in the chart make
  ;; this the default; route explicitly so the landing screen is deterministic).
  (scr/route-to! app SessionsReport {})
  (ido (it/add-fulcro-inspect! app)))
