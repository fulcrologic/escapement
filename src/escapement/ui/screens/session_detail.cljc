(ns escapement.ui.screens.session-detail
  "Per-session detail screens: EventsReport (transcript) and ArtifactsReport. Each is parameterized
   by the selected session id, supplied as the `::sc/session-id` route param (see
   `escapement.ui.screens.routing`). That route param populates the report's `::sc/session-id`
   control, whose value flows into the report load as the resolver `:params`; the global
   `session-events-report-resolver` / `session-artifacts-report-resolver` read it from there.

   Source attributes are the GLOBAL report variants (`:escapement/session-events`,
   `:escapement/session-artifacts`) rather than the ident-rooted `:session/events` /
   `:session/artifacts`, because a RAD report loads its `source-attribute` as a root key with the
   control values as params — an ident-rooted resolver (input `::sc/session-id`) cannot serve that."
  (:require
    [com.fulcrologic.rad.report-options :as ro]
    [com.fulcrologic.rad.statechart.control :as control]
    [com.fulcrologic.rad.statechart.report :as report]
    [com.fulcrologic.statecharts :as-alias sc]
    [com.fulcrologic.statecharts.integration.fulcro.routing :as scr]
    [escapement.ui.model.artifact :as artifact]
    [escapement.ui.model.transcript-event :as event]))

(report/defsc-report EventsReport [this props]
  {ro/title            "Session Events"
   ro/source-attribute :escapement/session-events
   ro/row-pk           event/seq*
   ro/columns          [event/seq* event/ts event/kind event/snippet]

   ro/column-headings  {:transcript/seq     "Seq"
                        :transcript/ts      "Time (ms)"
                        :transcript/kind    "Kind"
                        :io/snippet         "Snippet"}

   ;; The session id arrives as a route param and lands in this control; its value is sent to the
   ;; resolver as `:params {::sc/session-id <sid>}`.
   ro/controls         {::sc/session-id {:type   :string
                                         :label  "Session"
                                         :local? true}
                        ::artifacts     {:type   :button
                                         :label  "Artifacts"
                                         :local? true
                                         :action (fn [this]
                                                   (let [sid (control/current-value this ::sc/session-id)]
                                                     (scr/route-to! this
                                                       :escapement.ui.screens.session-detail/ArtifactsReport
                                                       {::sc/session-id sid})))}
                        ::sessions      {:type   :button
                                         :label  "All Sessions"
                                         :local? true
                                         :action (fn [this]
                                                   (scr/route-to! this
                                                     :escapement.ui.screens.sessions-report/SessionsReport {}))}
                        ::refresh       {:type   :button
                                         :label  "Refresh"
                                         :local? true
                                         :action (fn [this] (control/run! this))}}
   ro/control-layout   {:action-buttons [::sessions ::artifacts ::refresh]}

   ro/run-on-mount?    true
   ro/route            "session-events"})

(report/defsc-report ArtifactsReport [this props]
  {ro/title            "Session Artifacts"
   ro/source-attribute :escapement/session-artifacts
   ro/row-pk           artifact/path
   ro/columns          [artifact/path artifact/size artifact/content-type artifact/class*]

   ro/column-headings  {:artifact/path         "Path"
                        :artifact/size         "Size"
                        :artifact/content-type "Content Type"
                        :artifact/class        "Class"}

   ro/controls         {::sc/session-id {:type   :string
                                         :label  "Session"
                                         :local? true}
                        ::events        {:type   :button
                                         :label  "Events"
                                         :local? true
                                         :action (fn [this]
                                                   (let [sid (control/current-value this ::sc/session-id)]
                                                     (scr/route-to! this
                                                       :escapement.ui.screens.session-detail/EventsReport
                                                       {::sc/session-id sid})))}
                        ::sessions      {:type   :button
                                         :label  "All Sessions"
                                         :local? true
                                         :action (fn [this]
                                                   (scr/route-to! this
                                                     :escapement.ui.screens.sessions-report/SessionsReport {}))}
                        ::refresh       {:type   :button
                                         :label  "Refresh"
                                         :local? true
                                         :action (fn [this] (control/run! this))}}
   ro/control-layout   {:action-buttons [::sessions ::events ::refresh]}

   ro/run-on-mount?    true
   ro/route            "session-artifacts"})
