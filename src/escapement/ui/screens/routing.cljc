(ns escapement.ui.screens.routing
  "Routing statechart for the Escapement explorer. One routes region holds the three reports.
   `SessionsReport` is the landing route; `EventsReport`/`ArtifactsReport` take a `::sc/session-id`
   route param (the selected session), which becomes their session-id control and thus the resolver
   `:params`. Installation/startup is wired by a later host-specific task — this ns only exports
   the `routing-chart` var."
  (:require
    [com.fulcrologic.rad.statechart.report :as report]
    [com.fulcrologic.statecharts :as-alias sc]
    [com.fulcrologic.statecharts.chart :refer [statechart]]
    [com.fulcrologic.statecharts.integration.fulcro.routing :as scr]
    [escapement.ui.screens.chart-view :refer [ChartView]]
    [escapement.ui.screens.debugger :refer [Debugger]]
    [escapement.ui.screens.root :refer [Routes]]
    [escapement.ui.screens.session-detail :refer [ArtifactsReport EventsReport]]
    [escapement.ui.screens.sessions-report :refer [SessionsReport]]))

(def routing-chart
  "The explorer's routing statechart: a single routes region whose root is `Routes`. SessionsReport
   is the initial/landing route; the two detail reports carry the `::sc/session-id` route param."
  (statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :state/root :routing/root Routes}
        (report/report-route-state {:route/target SessionsReport})
        (report/report-route-state {:route/target EventsReport
                                    :route/params #{::sc/session-id}})
        (report/report-route-state {:route/target ArtifactsReport
                                    :route/params #{::sc/session-id}})
        ;; Plain (non-report) live-debug screens.
        (scr/rstate {:route/target Debugger})
        (scr/rstate {:route/target ChartView})))))
