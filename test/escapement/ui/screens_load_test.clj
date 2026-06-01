(ns escapement.ui.screens-load-test
  "Minimal load/smoke test for the shared CLJC explorer UI layer. Proves the new model/report/
   routing/root namespaces compile and load under the :ui-test alias, that each report carries the
   expected `ro/source-attribute`, and that the global report-source resolvers return rows shaped
   for the reports' columns (reusing the `resolvers-test` fixtures)."
  (:require
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.rad.report-options :as ro]
    [com.fulcrologic.statecharts :as-alias sc]
    [escapement.ui.model.artifact :as artifact]
    [escapement.ui.model.session :as session]
    [escapement.ui.model.transcript-event :as event]
    [escapement.ui.resolvers :as r]
    [escapement.ui.resolvers-test :as rt]
    [escapement.ui.screens.root :as root]
    [escapement.ui.screens.routing :as routing]
    [escapement.ui.screens.session-detail :as detail]
    [escapement.ui.screens.sessions-report :as sessions]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(specification "Explorer UI namespaces load and expose the expected component classes"
  (assertions
    "SessionsReport is a Fulcro component class"
    (comp/component-class? sessions/SessionsReport) => true
    "EventsReport is a Fulcro component class"
    (comp/component-class? detail/EventsReport) => true
    "ArtifactsReport is a Fulcro component class"
    (comp/component-class? detail/ArtifactsReport) => true
    "Root and Routes are component classes"
    (comp/component-class? root/Root) => true
    (comp/component-class? root/Routes) => true
    "routing-chart is a compiled statechart (a map)"
    (map? routing/routing-chart) => true
    "model attribute vectors are non-empty"
    (every? seq [session/attributes event/attributes artifact/attributes]) => true))

(specification "Reports carry the expected source-attributes and row-pks"
  (assertions
    "SessionsReport sources :escapement/all-sessions"
    (comp/component-options sessions/SessionsReport ro/source-attribute) => :escapement/all-sessions
    "EventsReport sources the global :escapement/session-events"
    (comp/component-options detail/EventsReport ro/source-attribute) => :escapement/session-events
    "ArtifactsReport sources the global :escapement/session-artifacts"
    (comp/component-options detail/ArtifactsReport ro/source-attribute) => :escapement/session-artifacts
    "EventsReport row-pk is :transcript/seq (avoids a tuple row-pk)"
    (:com.fulcrologic.rad.attributes/qualified-key
      (comp/component-options detail/EventsReport ro/row-pk)) => :transcript/seq
    "ArtifactsReport row-pk is :artifact/path"
    (:com.fulcrologic.rad.attributes/qualified-key
      (comp/component-options detail/ArtifactsReport ro/row-pk)) => :artifact/path
    "SessionsReport row-pk is ::sc/session-id"
    (:com.fulcrologic.rad.attributes/qualified-key
      (comp/component-options sessions/SessionsReport ro/row-pk)) => ::sc/session-id))

(specification "Report-source resolvers return rows shaped for the report columns"
  (let [store (rt/stub-store rt/fixture)
        ctx   {:escapement/store store}]
    (component "SessionsReport source (:escapement/all-sessions)"
      (let [rows (:escapement/all-sessions
                   (r/process ctx [{:escapement/all-sessions
                                    [::sc/session-id :session/status :session/started-at]}]))]
        (assertions
          "returns every session, most-recently-started first"
          (mapv ::sc/session-id rows) => ["s1" "s2"]
          "rows carry the SessionsReport column keys"
          (every? #(contains? % :session/status) rows) => true)))
    (component "EventsReport source (:escapement/session-events), session id via params"
      (let [rows (:escapement/session-events
                   (r/process ctx [{(list :escapement/session-events {::sc/session-id "s1" :limit 2})
                                    [:transcript/seq :transcript/id :transcript/kind :io/snippet]}]))]
        (assertions
          "limit + session-id param page the events for that session"
          (mapv :transcript/seq rows) => [0 1]
          "each row carries the tuple ident [sid seq]"
          (:transcript/id (first rows)) => ["s1" 0])))
    (component "ArtifactsReport source (:escapement/session-artifacts), session id via params"
      (let [rows (:escapement/session-artifacts
                   (r/process ctx [{(list :escapement/session-artifacts {::sc/session-id "s1"})
                                    [:artifact/path :artifact/id :artifact/class]}]))]
        (assertions
          "returns the session's artifacts with their [sid path] ident"
          (mapv :artifact/id rows) => [["s1" "artifacts/report.md"] ["s1" "nodes/w/0/turns/0/request.edn"]])))
    (component "missing session id yields no rows (not an error)"
      (assertions
        "events source returns [] when no session id supplied"
        (:escapement/session-events (r/process ctx [{:escapement/session-events [:transcript/seq]}])) => []))))
