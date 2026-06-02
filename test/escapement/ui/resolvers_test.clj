(ns escapement.ui.resolvers-test
  (:require
    [com.fulcrologic.statecharts :as-alias sc]
    [escapement.protocols :as proto]
    [escapement.ui.resolvers :as r]
    [fulcro-spec.core :refer [=> assertions component specification]]))

;; ---------------------------------------------------------------------------
;; In-memory stub store (isolates resolver logic from disk)
;; ---------------------------------------------------------------------------

(defn stub-store
  "Reify the read protocols from in-memory data. `sessions` is a vector of summaries; `events` and
   `artifacts` are `{session-id [...]}`; `contents` is `{session-id {path content}}`."
  [{:keys [sessions events artifacts contents]}]
  (reify
    proto/SessionIndex
    (list-sessions [_] sessions)
    proto/TranscriptStore
    (append-event! [_ _ _] (throw (ex-info "read-only" {})))
    (read-events [_ sid query]
      (cond->> (get events sid [])
        (:types query)    (filter #(contains? (:types query) (:transcript/kind %)))
        (:from-seq query) (filter #(<= (:from-seq query) (:transcript/seq %)))
        (:limit query)    (take (:limit query))
        true              vec))
    proto/ArtifactStore
    (write-artifact! [_ _ _ _ _] nil)
    (list-artifacts [_ sid] (get artifacts sid []))
    (read-artifact [_ sid path] (get-in contents [sid path]))))

;; ---------------------------------------------------------------------------
;; Pure logic
;; ---------------------------------------------------------------------------

(specification "captured-kind"
  (assertions
    "classifies a seed locator"
    (r/captured-kind "nodes/writer/0/seed.edn") => :seed
    "classifies a request locator"
    (r/captured-kind "nodes/writer/0/turns/2/request.edn") => :request
    "classifies a response locator"
    (r/captured-kind "nodes/writer/0/turns/2/response.edn") => :response
    "classifies a tool-result locator"
    (r/captured-kind "nodes/writer/0/turns/2/tool-results/abc.edn") => :tool-result
    "everything else is :other"
    (r/captured-kind "artifacts/report.md") => :other))

(specification "invocations-from-artifacts"
  (let [arts [{:artifact/class :author :artifact/path "artifacts/report.md"}
              {:artifact/class :captured-io :artifact/path "nodes/writer/0/seed.edn"
               :transcript/node-id :writer :transcript/visit 0}
              {:artifact/class :captured-io :artifact/path "nodes/writer/0/turns/0/request.edn"
               :transcript/node-id :writer :transcript/visit 0 :transcript/turn 0}
              {:artifact/class :captured-io :artifact/path "nodes/writer/0/turns/0/response.edn"
               :transcript/node-id :writer :transcript/visit 0 :transcript/turn 0}
              {:artifact/class :captured-io :artifact/path "nodes/writer/0/turns/0/tool-results/t1.edn"
               :transcript/node-id :writer :transcript/visit 0 :transcript/turn 0}
              {:artifact/class :captured-io :artifact/path "nodes/writer/0/turns/1/request.edn"
               :transcript/node-id :writer :transcript/visit 0 :transcript/turn 1}]
        result (r/invocations-from-artifacts arts)
        node   (first result)
        visit0 (first (:node/visits node))]
    (assertions
      "ignores author files, grouping only captured-I/O by node"
      (mapv :transcript/node-id result) => [:writer]
      "groups visits under the node"
      (mapv :transcript/visit (:node/visits node)) => [0]
      "carries the visit's seed ref"
      (:invocation/seed-ref visit0) => "nodes/writer/0/seed.edn"
      "orders turns within the visit"
      (mapv :transcript/turn (:invocation/turns visit0)) => [0 1]
      "wires request/response refs for a turn"
      (select-keys (first (:invocation/turns visit0)) [:turn/request-ref :turn/response-ref])
      => {:turn/request-ref  "nodes/writer/0/turns/0/request.edn"
          :turn/response-ref "nodes/writer/0/turns/0/response.edn"}
      "collects all tool-result refs for a turn"
      (:turn/tool-result-refs (first (:invocation/turns visit0)))
      => ["nodes/writer/0/turns/0/tool-results/t1.edn"]
      "a turn with no tool calls has an empty tool-result vector"
      (:turn/tool-result-refs (second (:invocation/turns visit0))) => [])))

(specification "remove-functions"
  (assertions
    "replaces a function value with the :fn placeholder"
    (r/remove-functions {:id :a :expr (fn [_] 1)}) => {:id :a :expr :fn}
    "recurses into nested structures"
    (r/remove-functions {:s {:cond inc}}) => {:s {:cond :fn}}
    "leaves non-function data untouched"
    (r/remove-functions {:id :a :n 3 :v [1 2]}) => {:id :a :n 3 :v [1 2]}))

;; ---------------------------------------------------------------------------
;; Resolver integration (over the stub store)
;; ---------------------------------------------------------------------------

(def fixture
  {:sessions  [{::sc/session-id "s1" ::sc/statechart-src :x/y :session/status :done :session/started-at 200}
               {::sc/session-id "s2" ::sc/statechart-src :a/b :session/status :incomplete :session/started-at 100}]
   :events    {"s1" [{:transcript/seq 0 :transcript/kind :runner/started :transcript/data {}}
                     {:transcript/seq 1 :transcript/kind :llm/request :io/ref "nodes/w/0/turns/0/request.edn"}
                     {:transcript/seq 2 :transcript/kind :runner/done :transcript/data {}}]}
   :artifacts {"s1" [{:artifact/class :author :artifact/path "artifacts/report.md" :artifact/size 4}
                     {:artifact/class :captured-io :artifact/path "nodes/w/0/turns/0/request.edn"
                      :transcript/node-id :w :transcript/visit 0 :transcript/turn 0}]}
   :contents  {"s1" {"artifacts/report.md" "# hi"}}})

(specification "read resolvers (over the in-memory stub)"
  (let [store (stub-store fixture)
        ctx   {:escapement/store store :escapement/active-session-id "s1"}]
    (component "active-session (global)"
      (assertions
        "returns the running session id and its ident"
        (r/process ctx [:escapement/active-session-id {:escapement/active-session [::sc/session-id]}])
        => {:escapement/active-session-id "s1"
            :escapement/active-session    {::sc/session-id "s1"}}))
    (component "all-sessions (global)"
      (assertions
        "lists every session"
        (mapv ::sc/session-id (:escapement/all-sessions
                                (r/process ctx [{:escapement/all-sessions [::sc/session-id]}])))
        => ["s1" "s2"]))
    (component "session detail"
      (assertions
        "resolves a single session's fields by ident"
        (get (r/process ctx [{[::sc/session-id "s2"] [:session/status ::sc/statechart-src]}])
          [::sc/session-id "s2"])
        => {:session/status :incomplete ::sc/statechart-src :a/b}))
    (component "transcript paging"
      (let [q (fn [params]
                (get-in (r/process ctx [{[::sc/session-id "s1"]
                                         [{(list :session/events params) [:transcript/seq :transcript/id]}]}])
                  [[::sc/session-id "s1"] :session/events]))]
        (assertions
          "limit caps the events and stamps the ident"
          (mapv :transcript/seq (q {:limit 2})) => [0 1]
          "the event ident is [sid seq]"
          (:transcript/id (first (q {:limit 1}))) => ["s1" 0]
          "type filter selects kinds"
          (mapv :transcript/seq (q {:types [:runner/done]})) => [2])))
    (component "artifacts + lazy content"
      (assertions
        "lists artifacts with their [sid path] ident"
        (mapv :artifact/id (get-in (r/process ctx [{[::sc/session-id "s1"] [{:session/artifacts [:artifact/id]}]}])
                             [[::sc/session-id "s1"] :session/artifacts]))
        => [["s1" "artifacts/report.md"] ["s1" "nodes/w/0/turns/0/request.edn"]]
        "loads one artifact's content by ident"
        (get (r/process ctx [{[:artifact/id ["s1" "artifacts/report.md"]] [:artifact/content]}])
          [:artifact/id ["s1" "artifacts/report.md"]])
        => {:artifact/content "# hi"}))
    (component "node-invocations drill-in"
      (assertions
        "assembles the captured-I/O tree for the session"
        (get-in (r/process ctx [{[::sc/session-id "s1"]
                                 [{:session/node-invocations
                                   [:transcript/node-id
                                    {:node/visits [:transcript/visit
                                                   {:invocation/turns [:turn/request-ref]}]}]}]}])
          [[::sc/session-id "s1"] :session/node-invocations 0 :node/visits 0 :invocation/turns 0
           :turn/request-ref])
        => "nodes/w/0/turns/0/request.edn"))))
