(ns escapement.debug.replay-dispatch-wiring-test
  "C1 wiring test: the llm-conversation worker's tool dispatch
   (`#'handle-tool-use-block`) must, on a forked branch (a `:replay-ctx` present),
   route through `escapement.debug.replay/replay-aware-dispatch` — serving a
   matching captured result WITHOUT running the tool and tagging the
   `:llm/tool-result` event `:replay/source \"captured\"`, else running live and
   tagging `\"live\"`. Before this, the worker always called `tp/dispatch` and no
   `:replay/source` was ever emitted."
  (:require
    [escapement.capture :as capture]
    [escapement.debug.replay :as dreplay]
    [escapement.invocation.llm-conversation :as llmc]
    [escapement.protocols :as proto]
    [escapement.replay :as replay]
    [escapement.storage.memory :as mem]
    [escapement.tools.protocol :as tp]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(defrecord CountingEcho [calls]
  tp/Tool
  (tool-name [_] :test/echo)
  (description [_] "Echo :msg; counts live invocations.")
  (input-schema [_] [:map {:closed true} [:msg :string]])
  (invoke [_ {:keys [msg]}] (swap! calls inc) {:result (str "live:" msg) :is-error false}))

(defn- parent-with-captured-echo
  "A memory store standing in for the PARENT session: one captured :test/echo
   tool-result at coords {node :talk, visit 0, turn 0} with input {:msg \"hi\"}."
  [session-id]
  (let [store (mem/new-store)
        cap   {:store store :session-id session-id :node-id :talk :visit 0}]
    ;; Capture the blob (writes nodes/talk/0/turns/0/tool-results/u1.edn) and append
    ;; the matching transcript event the index scans for.
    (let [{:io/keys [ref]} (capture/capture-blob! cap 0 "tool-results/u1"
                             "captured-result" "captured-result")]
      (proto/append-event! store session-id
        {:event :llm/tool-result
         :data  {:tool_use_id "u1" :tool :test/echo :input {:msg "hi"} :io/ref ref}}))
    store))

(defn- run-block
  "Invoke the private `handle-tool-use-block` with a replay-ctx and capture the
   emitted :llm/tool-result event. Returns {:event … :calls <live-call-count>}."
  [{:keys [input parent-id parent-store calls]}]
  (let [events (atom [])
        reg    (tp/new-registry [(->CountingEcho calls)])
        ctx    {:tool-registry     reg
                :name->tool-kw     {"echo" :test/echo}
                :name->event-entry {}
                :name->region-tool {}
                :tool-reply-queue  nil
                :worker-state      (atom :running)
                :retry-counts      (atom {})
                :transcript-fn     (fn [e] (swap! events conj e))
                :capture           {:store (mem/new-store) :session-id "branch" :node-id :talk :visit 0}
                :turn              0
                :replay-ctx        {:replay        {:index  (replay/build-tool-result-index parent-store parent-id)
                                                    :source parent-id}
                                    :parent-store  parent-store
                                    :policy        {:mode :replay-then-live}
                                    :tool-registry reg}}
        block  {:id "u2" :name "echo" :input input}]
    (#'llmc/handle-tool-use-block ctx {:invokeid "writer"} block)
    {:event (first (filter #(= :llm/tool-result (:event %)) @events))
     :calls @calls}))

(specification "branch worker routes tool dispatch through replay-aware-dispatch"
  (let [parent-id "p1"
        pstore    (parent-with-captured-echo parent-id)]

    (component "MATCH ⇒ captured result served, tool NOT run, event tagged captured"
      (let [{:keys [event calls]} (run-block {:input {:msg "hi"} :parent-id parent-id
                                              :parent-store pstore :calls (atom 0)})]
        (assertions
          "the live tool was never invoked"
          calls => 0
          "the tool-result event is flagged :replay/source \"captured\""
          (:replay/source (:data event)) => "captured"
          "and carries the captured content's snippet (not a live result)"
          (:content-preview (:data event)) => "captured-result")))

    (component "MISS ⇒ tool runs live, event tagged live"
      (let [{:keys [event calls]} (run-block {:input {:msg "bye"} :parent-id parent-id
                                              :parent-store pstore :calls (atom 0)})]
        (assertions
          "the live tool ran exactly once"
          calls => 1
          "the tool-result event is flagged :replay/source \"live\""
          (:replay/source (:data event)) => "live"
          "and carries the LIVE result"
          (:content-preview (:data event)) => "live:bye")))))
