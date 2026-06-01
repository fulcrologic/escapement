(ns escapement.ui.screens.sessions-report
  "SessionsReport — the explorer's landing report. Lists every Escapement session
   (`:escapement/all-sessions`, most-recently-started first) and drills into one session's
   events via a row action that routes to the EventsReport, passing the clicked session id as
   the `::sc/session-id` route param."
  (:require
    [com.fulcrologic.rad.report-options :as ro]
    [com.fulcrologic.rad.statechart.control :as control]
    [com.fulcrologic.rad.statechart.report :as report]
    [com.fulcrologic.statecharts :as-alias sc]
    [com.fulcrologic.statecharts.integration.fulcro.routing :as scr]
    [escapement.ui.model.session :as session]))

(report/defsc-report SessionsReport [this props]
  {ro/title            "Sessions"
   ro/source-attribute :escapement/all-sessions
   ro/row-pk           session/id
   ro/columns          [session/status session/started-at session/ended-at
                        session/event-count session/statechart-src]

   ro/column-headings  {::sc/session-id     "Session"
                        ::sc/statechart-src "Chart"
                        :session/status     "Status"
                        :session/started-at "Started (ms)"
                        :session/ended-at   "Ended (ms)"
                        :session/event-count "Events"}

   ;; Drill-in: route to the EventsReport for the clicked session, supplying the session id as the
   ;; report's `::sc/session-id` route param (which becomes its session-id control, then the
   ;; resolver `:params`). The events screen also links to artifacts for the same session.
   ro/row-actions      [{:label  "Events"
                         :action (fn [this {sid ::sc/session-id}]
                                   (scr/route-to! this
                                     :escapement.ui.screens.session-detail/EventsReport
                                     {::sc/session-id sid}))}
                        {:label  "Artifacts"
                         :action (fn [this {sid ::sc/session-id}]
                                   (scr/route-to! this
                                     :escapement.ui.screens.session-detail/ArtifactsReport
                                     {::sc/session-id sid}))}]

   ro/controls         {::refresh {:type   :button
                                   :label  "Refresh"
                                   :local? true
                                   :action (fn [this] (control/run! this))}}
   ro/control-layout   {:action-buttons [::refresh]}

   ro/run-on-mount?    true
   ro/route            "sessions"})
