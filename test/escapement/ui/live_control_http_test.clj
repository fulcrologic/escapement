(ns escapement.ui.live-control-http-test
  "Deterministic, end-to-end proof of the LIVE-control HTTP path under babashka.

   This exercises the FULL `--api-server` control plane the way the browser
   debugger drives it — but with NO socket, NO port, and NO engine thread, so it
   is race-free:

     transit request  →  `escapement.ui.server/make-handler` ring handler
                       →  `escapement.ui.resolvers` Pathom parser
                       →  control mutation / live resolver
                       →  controller / instrumented queue / live wmem store
                       →  transit response.

   It builds the SAME ctx keys `escapement.ui.server/start!` injects
   (`:escapement/store`, `:escapement/active-session-id`, `:escapement/controller`,
   `:escapement/live`) and calls the handler fn directly with a synthesized ring
   request whose `:body` is a transit-encoded EQL, decoding the transit response
   the same way `server.clj` does (`cognitect.transit` json reader/writer). The
   live env is a real `escapement.debug.controller`, a real instrumented queue,
   and a working-memory store seeded so `runtime/current-configuration` returns a
   KNOWN active-state set — wired through a real `escapement.debug.control-handle`.

   Runs under `bb test` (it is a normal `test/**/*_test.clj`, NOT JVM-only)."
  (:require
    [cognitect.transit :as transit]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.debug.control-handle :as ch]
    [escapement.debug.controller :as dbg]
    [escapement.engine.instrumented-queue :as iq]
    [escapement.protocols :as proto]
    [escapement.ui.server :as server]
    [fulcro-spec.core :refer [=> assertions component specification]])
  (:import
    (java.io ByteArrayInputStream ByteArrayOutputStream)))

;; ---------------------------------------------------------------------------
;; Transit helpers — identical wire shape to escapement.ui.server.
;; ---------------------------------------------------------------------------

(defn- ->transit-stream
  "Encode `x` as a transit+json InputStream (a ring request `:body`)."
  [x]
  (let [out (ByteArrayOutputStream.)]
    (transit/write (transit/writer out :json) x)
    (ByteArrayInputStream. (.toByteArray out))))

(defn- <-transit
  "Decode the transit+json `bytes` of a handler response `:body`."
  [bytes]
  (transit/read (transit/reader (ByteArrayInputStream. bytes) :json)))

(defn- post-eql
  "POST `query` (EQL — a read vector or a mutation vector) through the ring
   `handler` as transit, returning the decoded transit response body."
  [handler query]
  (<-transit (:body (handler {:request-method :post :uri "/api" :body (->transit-stream query)}))))

;; ---------------------------------------------------------------------------
;; A deterministic live env: read store + controller + instrumented queue +
;; a working-memory store seeded with a KNOWN configuration for a KNOWN session.
;; ---------------------------------------------------------------------------

(def ^:private session-id "live-sid-1")
(def ^:private known-configuration #{:S/parent :S/active-child})

(defn- read-store
  "A minimal read-only store satisfying the read protocols the resolvers may
   touch. The live-control resolvers do not read it; it only fills the required
   `:escapement/store` ctx key (matching what `server/start!` injects)."
  []
  (reify
    proto/SessionIndex
    (list-sessions [_] [{::sc/session-id session-id :session/status :running}])
    proto/TranscriptStore
    (append-event! [_ _ _] (throw (ex-info "read-only" {})))
    (read-events [_ _ _] [])
    proto/ArtifactStore
    (write-artifact! [_ _ _ _ _] nil)
    (list-artifacts [_ _] [])
    (read-artifact [_ _ _] nil)))

(defn- seeded-wmem-store
  "A `WorkingMemoryStore` whose `get-working-memory` returns `known-configuration`
   for `session-id`, so `runtime/current-configuration` resolves a known set."
  []
  (reify sp/WorkingMemoryStore
    (get-working-memory [_ _ sid]
      (when (= sid session-id) {::sc/configuration known-configuration}))
    (save-working-memory! [_ _ _ _] nil)
    (delete-working-memory! [_ _ _] nil)))

(defn- live-ctx
  "Build the api-server ring handler over a fully-wired LIVE control plane and
   return `{:handler :controller :queue}`. The handle bridges the running env to
   the server exactly as `run!`'s on-env-ready fills it: `{:controller :env
   :session-id :queue}`."
  []
  (let [controller (dbg/new-controller {:initial-pause? true})
        queue      (iq/new-instrumented-queue {:controller controller})
        env        {::sc/working-memory-store (seeded-wmem-store)}
        handle     (ch/fill! (ch/new-handle)
                     {:controller controller
                      :env        env
                      :session-id session-id
                      :queue      queue})
        ctx        {:escapement/store             (read-store)
                    :escapement/active-session-id session-id
                    :escapement/controller        controller
                    :escapement/live              handle}]
    {:handler    (server/make-handler ctx)
     :controller controller
     :queue      queue}))

;; ---------------------------------------------------------------------------
;; The end-to-end HTTP proof.
;; ---------------------------------------------------------------------------

(specification "live-control plane over the full POST /api transit path (bb)"
  (component "a read of :session/paused? reflects the live controller (started paused)"
    (let [{:keys [handler]} (live-ctx)]
      (assertions
        "the transit round-trip reports the run paused at the debug gate"
        (post-eql handler [:session/paused?]) => {:session/paused? true})))

  (component "the escapement.control/step mutation dispatches and drives the controller"
    (let [{:keys [handler controller]} (live-ctx)
          res (post-eql handler `[(escapement.control/step {})])]
      (assertions
        "the mutation response grants a one-event budget (so not paused this instant)"
        (get res `escapement.control/step) => {:debug/paused? false :debug/step-budget 1}
        "the live controller actually carries a step budget of 1"
        (:step-budget @controller) => 1
        "and is no longer halting (the budget releases exactly one event)"
        (dbg/paused? controller) => false)))

  (component "the escapement.control/continue mutation flips the live run to running"
    (let [{:keys [handler controller]} (live-ctx)]
      (assertions
        "before continue, a read shows the run paused"
        (post-eql handler [:session/paused?]) => {:session/paused? true}
        "the continue mutation response reports the run resumed"
        (get (post-eql handler `[(escapement.control/continue {})]) `escapement.control/continue)
        => {:debug/paused? false :debug/step-budget 0}
        "the controller is no longer paused"
        (dbg/paused? controller) => false
        "and a subsequent read of :session/paused? now returns false"
        (post-eql handler [:session/paused?]) => {:session/paused? false})))

  (component "a read of :session/live-configuration returns the seeded active states"
    (let [{:keys [handler]} (live-ctx)
          res (post-eql handler [:session/live-configuration])]
      (assertions
        "the live working-memory configuration round-trips as the known active-state set"
        (set (:session/live-configuration res)) => known-configuration)))

  (component "a read of :session/pending-events reflects the live instrumented queue"
    (let [{:keys [handler queue]} (live-ctx)
          ;; Seed one queued-but-undelivered event directly on the live queue.
          _   (sp/send! queue {} {:event :do-thing :target session-id :data {:k 42}})
          res (post-eql handler [{:session/pending-events [:event/name :event/target :event/data]}])]
      (assertions
        "the queued event surfaces over HTTP with its name/target/data"
        (:session/pending-events res)
        => [{:event/name :do-thing :event/target session-id :event/data {:k 42}}]))))
