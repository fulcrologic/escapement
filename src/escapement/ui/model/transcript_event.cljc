(ns escapement.ui.model.transcript-event
  "RAD attributes for a single transcript event within a session, matching `transcript-resolver`
   / `session-events-report-resolver` output. Virtual attributes (no `ao/schema`).

   The natural identity is the tuple `[session-id seq]` (`:transcript/id`). Because tuple identities
   are awkward as a report `row-pk`, the EventsReport uses `:transcript/seq` (unique within a
   session) as its `ro/row-pk` instead."
  (:require
    [com.fulcrologic.rad.attributes :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.statecharts :as-alias sc]))

;; Tuple identity [session-id seq]; not used as the report row-pk (see ns docstring), but modeled
;; so the ident produced by the resolver has a home in the attribute model.
(defattr id :transcript/id :tuple
  {ao/identity? true
   ao/label     "Event"})

;; Stable per-row PK within one session — the EventsReport's `ro/row-pk`.
(defattr seq* :transcript/seq :int
  {ao/identity? true
   ao/label     "Seq"})

(defattr session-id ::sc/session-id :string
  {ao/identities #{:transcript/seq}
   ao/label      "Session"})

(defattr ts :transcript/ts :int
  {ao/identities #{:transcript/seq}
   ao/label      "Time (ms)"})

(defattr kind :transcript/kind :keyword
  {ao/identities #{:transcript/seq}
   ao/label      "Kind"})

(defattr data :transcript/data :edn
  {ao/identities #{:transcript/seq}
   ao/label      "Data"})

(defattr io-ref :io/ref :string
  {ao/identities #{:transcript/seq}
   ao/label      "I/O Ref"})

(defattr snippet :io/snippet :string
  {ao/identities #{:transcript/seq}
   ao/label      "Snippet"})

(def attributes
  [id seq* session-id ts kind data io-ref snippet])
