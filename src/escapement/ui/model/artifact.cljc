(ns escapement.ui.model.artifact
  "RAD attributes for a single session artifact, matching `artifacts-resolver` /
   `session-artifacts-report-resolver` output. Virtual attributes (no `ao/schema`).

   Natural identity is the tuple `[session-id path]` (`:artifact/id`). The ArtifactsReport uses
   `:artifact/path` (unique within a session) as its `ro/row-pk` to avoid a tuple row-pk."
  (:require
    [com.fulcrologic.rad.attributes :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.statecharts :as-alias sc]))

;; Tuple identity [session-id path]; modeled for completeness, not used as the report row-pk.
(defattr id :artifact/id :tuple
  {ao/identity? true
   ao/label     "Artifact"})

;; Stable per-row PK within one session — the ArtifactsReport's `ro/row-pk`.
(defattr path :artifact/path :string
  {ao/identity? true
   ao/label     "Path"})

(defattr session-id ::sc/session-id :string
  {ao/identities #{:artifact/path}
   ao/label      "Session"})

(defattr size :artifact/size :int
  {ao/identities #{:artifact/path}
   ao/label      "Size"})

(defattr content-type :artifact/content-type :string
  {ao/identities #{:artifact/path}
   ao/label      "Content Type"})

(defattr class* :artifact/class :keyword
  {ao/identities #{:artifact/path}
   ao/label      "Class"})

(defattr node-id :transcript/node-id :keyword
  {ao/identities #{:artifact/path}
   ao/label      "Node"})

(defattr visit :transcript/visit :int
  {ao/identities #{:artifact/path}
   ao/label      "Visit"})

(defattr turn :transcript/turn :int
  {ao/identities #{:artifact/path}
   ao/label      "Turn"})

;; Lazy full content (resolves by the [:artifact/id [sid path]] ident via `artifact-content-resolver`).
(defattr content :artifact/content :string
  {ao/identities #{:artifact/id}
   ao/label      "Content"})

(def attributes
  [id path session-id size content-type class* node-id visit turn content])
