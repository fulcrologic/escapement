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
    "classifies an output locator"
    (r/captured-kind "nodes/writer/0/turns/2/output.edn") => :output
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
               :transcript/node-id :writer :transcript/visit 0 :transcript/turn 1}
              {:artifact/class :captured-io :artifact/path "nodes/writer/0/turns/1/output.edn"
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
      (:turn/tool-result-refs (second (:invocation/turns visit0))) => []
      "wires a turn's output-ref when one was captured"
      (:turn/output-ref (second (:invocation/turns visit0))) => "nodes/writer/0/turns/1/output.edn"
      "a turn with no captured output has a nil output-ref"
      (:turn/output-ref (first (:invocation/turns visit0))) => nil
      "the visit's invocation-level output-ref is the last turn that captured one"
      (:invocation/output-ref visit0) => "nodes/writer/0/turns/1/output.edn")))

(specification "remove-functions"
  (assertions
    "replaces a function value with the :fn placeholder"
    (r/remove-functions {:id :a :expr (fn [_] 1)}) => {:id :a :expr :fn}
    "recurses into nested structures"
    (r/remove-functions {:s {:cond inc}}) => {:s {:cond :fn}}
    "leaves non-function data untouched"
    (r/remove-functions {:id :a :n 3 :v [1 2]}) => {:id :a :n 3 :v [1 2]}))

(def ^:private recon-artifacts
  [{:artifact/class :captured-io :artifact/path "nodes/w/0/seed.edn"
    :transcript/node-id :w :transcript/visit 0}
   {:artifact/class :captured-io :artifact/path "nodes/w/0/turns/0/request.edn"
    :transcript/node-id :w :transcript/visit 0 :transcript/turn 0}
   {:artifact/class :captured-io :artifact/path "nodes/w/0/turns/0/response.edn"
    :transcript/node-id :w :transcript/visit 0 :transcript/turn 0}
   {:artifact/class :captured-io :artifact/path "nodes/w/0/turns/1/request.edn"
    :transcript/node-id :w :transcript/visit 0 :transcript/turn 1}
   {:artifact/class :captured-io :artifact/path "nodes/w/0/turns/1/output.edn"
    :transcript/node-id :w :transcript/visit 0 :transcript/turn 1}])

(def ^:private recon-events
  [{:transcript/seq 1 :transcript/kind :llm/start :transcript/ts 100
    :transcript/node-id :w :transcript/visit 0}
   {:transcript/seq 5 :transcript/kind :llm/event-posted :transcript/ts 150
    :transcript/node-id :w :transcript/visit 0 :transcript/turn 0
    :transcript/data {:invokeid "w" :event-name :count/tick :event-data {:n 1}}}
   {:transcript/seq 9 :transcript/kind :llm/event-posted :transcript/ts 200
    :transcript/node-id :w :transcript/visit 0 :transcript/turn 1
    :transcript/data {:invokeid "w" :event-name :count/done :event-data {:total 2}}}])

(specification "reconstruct-invocation"
  (let [inv      (r/reconstruct-invocation {:session-id "s1" :node-id :w :visit 0
                                            :artifacts recon-artifacts :events recon-events})
        timeline (:invocation/timeline inv)]
    (assertions
      "carries the invocation ident"
      (:llm.conversation/invocation-id inv) => ["s1" :w 0]
      "reports the turn count from the captured artifacts"
      (:invocation/turn-count inv) => 2
      "reports started-at from the (stamped) :llm/start event"
      (:invocation/started-at inv) => 100
      "surfaces the invocation's final output-ref handle"
      (:llm.conversation/output-ref inv) => "nodes/w/0/turns/1/output.edn"
      "interleaves each turn with the events it fired, ordered turn-then-fired"
      (mapv :timeline/kind timeline) => [:turn :fired-event :turn :fired-event]
      "the turn entries appear in turn order"
      (->> timeline (filter #(= :turn (:timeline/kind %))) (mapv :transcript/turn)) => [0 1]
      "a turn entry carries its request ref"
      (:turn/request-ref (first timeline)) => "nodes/w/0/turns/0/request.edn"
      "the fired-event after turn 0 is the event that turn sent"
      (select-keys (second timeline) [:event/name :event/data])
      => {:event/name :count/tick :event/data {:n 1}}
      "the fired-event after turn 1 is that turn's event"
      (select-keys (nth timeline 3) [:event/name :event/data])
      => {:event/name :count/done :event/data {:total 2}}
      "returns nil for a (node-id, visit) with no captured artifacts"
      (r/reconstruct-invocation {:session-id "s1" :node-id :nope :visit 0
                                 :artifacts recon-artifacts :events recon-events}) => nil)))

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

(defn- read-counting-store
  "A read store backed by `recon-artifacts`/`recon-events` whose `read-artifact` bumps `reads` and
   serves `nodes/w/0/turns/1/output.edn` as a captured output blob. Lets a test prove the
   `:llm.conversation/output` resolver is lazy (the blob is read only when that key is queried)."
  [reads]
  (reify
    proto/SessionIndex
    (list-sessions [_] [])
    proto/TranscriptStore
    (append-event! [_ _ _] nil)
    (read-events [_ _ _] recon-events)
    proto/ArtifactStore
    (write-artifact! [_ _ _ _ _] nil)
    (list-artifacts [_ _] recon-artifacts)
    (read-artifact [_ _ path]
      (swap! reads inc)
      (get {"nodes/w/0/turns/1/output.edn"
            (pr-str {:text "done" :verdict {:ok true} :from "w"})}
        path))))

(specification "invocation-transcript-resolver + lazy output handle"
  (let [reads (atom 0)
        ctx   {:escapement/store (read-counting-store reads)}
        id    [:llm.conversation/invocation-id ["s1" :w 0]]]
    (component "reconstruction over the parser"
      (let [res (get (r/process ctx [{id [:llm.conversation/output-ref :invocation/turn-count
                                          {:invocation/timeline [:timeline/kind]}]}]) id)]
        (assertions
          "reconstructs the invocation timeline"
          (mapv :timeline/kind (:invocation/timeline res)) => [:turn :fired-event :turn :fired-event]
          "surfaces the output-ref handle"
          (:llm.conversation/output-ref res) => "nodes/w/0/turns/1/output.edn"
          "without dereferencing the output blob (the handle is cheap)"
          @reads => 0)))
    (component "the output value is resolved lazily, only when queried"
      (let [res (get (r/process ctx [{id [:llm.conversation/output]}]) id)]
        (assertions
          "dereferences the handle to the full captured output map"
          (:llm.conversation/output res) => {:text "done" :verdict {:ok true} :from "w"}
          "reading the blob exactly once"
          @reads => 1)))))
