(ns escapement.ui.rendering.tui.routing
  "Terminal routing chart for the Escapement explorer. Identical in shape to the shared
   `escapement.ui.screens.routing/routing-chart`, but its routes region's `:routing/root` is the
   fulcro-tui `Routes` outlet (`escapement.ui.rendering.tui.root/Routes`) rather than the DOM one.

   This split is required: the routing system rewrites the routing root's DYNAMIC query so
   `:ui/current-route` becomes a join to the active route's component, and `scr/ui-current-subroute`
   reads that rewritten query off the routing-root class to render the subroute. That rewrite is keyed
   to the class pinned here, so the TUI outlet (which paints fulcro-tui elements) must be the pinned
   root for the terminal target. The report-route-states (and their `::sc/session-id` route param) are
   otherwise identical to the shared chart."
  (:require
    [com.fulcrologic.rad.statechart.report :as report]
    [com.fulcrologic.statecharts :as-alias sc]
    [com.fulcrologic.statecharts.chart :refer [statechart]]
    [com.fulcrologic.statecharts.integration.fulcro.routing :as scr]
    [escapement.ui.rendering.tui.root :refer [Routes]]
    [escapement.ui.screens.session-detail :refer [ArtifactsReport EventsReport]]
    [escapement.ui.screens.sessions-report :refer [SessionsReport]]))

(def routing-chart
  "The explorer's routing statechart for the terminal target: a single routes region whose root is the
   TUI `Routes` outlet. SessionsReport is the initial/landing route; the two detail reports carry the
   `::sc/session-id` route param."
  (statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :state/root :routing/root Routes}
        (report/report-route-state {:route/target SessionsReport})
        (report/report-route-state {:route/target EventsReport
                                    :route/params #{::sc/session-id}})
        (report/report-route-state {:route/target ArtifactsReport
                                    :route/params #{::sc/session-id}})))))
