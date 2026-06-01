(ns escapement.ui.model.session
  "RAD attributes describing an Escapement session, matching the read-surface resolver output
   (`escapement.ui.resolvers`). All attributes are virtual (no `ao/schema`) — the values are
   produced by the existing Pathom 2 resolvers, not by a RAD storage adapter."
  (:require
    [com.fulcrologic.rad.attributes :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.statecharts :as-alias sc]))

(defattr id ::sc/session-id :string
  {ao/identity? true
   ao/label     "Session"})

(defattr statechart-src ::sc/statechart-src :keyword
  {ao/identities #{::sc/session-id}
   ao/label      "Chart"})

;; `:session/started-at`/`:session/ended-at` are epoch millis (the transcript's `:ts`, from
;; `now-ms`), so they are `:int`, not `:instant` — the store never emits a java instant.
(defattr started-at :session/started-at :int
  {ao/identities #{::sc/session-id}
   ao/label      "Started (ms)"})

(defattr ended-at :session/ended-at :int
  {ao/identities #{::sc/session-id}
   ao/label      "Ended (ms)"})

(defattr status :session/status :keyword
  {ao/identities #{::sc/session-id}
   ao/label      "Status"})

(defattr event-count :session/event-count :int
  {ao/identities #{::sc/session-id}
   ao/label      "Events"})

(defattr resume? :session/resume? :boolean
  {ao/identities #{::sc/session-id}
   ao/label      "Resumed?"})

(def attributes
  [id statechart-src started-at ended-at status event-count resume?])
