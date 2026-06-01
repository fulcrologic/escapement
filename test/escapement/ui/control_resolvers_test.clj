(ns escapement.ui.control-resolvers-test
  "Tests the api-server control plane at the resolver/parser level: the control
   mutations dispatch through the Pathom parser (proving `::p/mutate pc/mutate`
   is wired) and drive the controller, and the live resolvers read pause state,
   pending events, and the live configuration from a stub control handle."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.debug.control-handle :as ch]
    [escapement.debug.controller :as dbg]
    [escapement.engine.instrumented-queue :as iq]
    [escapement.ui.resolvers :as r]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(defn- ctx-with
  "Build a Pathom ctx wired with the live `controller` and a control `handle`."
  [controller handle]
  {:escapement/store      nil
   :escapement/controller controller
   :escapement/live       handle})

(specification "control mutations dispatch through the parser and drive the controller"
  (component "escapement.control/pause"
    (let [ctl (dbg/new-controller)
          res (r/process (ctx-with ctl (ch/new-handle))
                `[(escapement.control/pause {})])]
      (assertions
        "the mutation result reports the run paused"
        (get res `escapement.control/pause) => {:debug/paused? true :debug/step-budget 0}
        "the controller is actually paused"
        (dbg/paused? ctl) => true)))

  (component "escapement.control/step"
    (let [ctl (dbg/new-controller {:initial-pause? true})
          res (r/process (ctx-with ctl (ch/new-handle))
                `[(escapement.control/step {})])]
      (assertions
        "the mutation grants a one-event budget (so not paused this instant)"
        (get res `escapement.control/step) => {:debug/paused? false :debug/step-budget 1}
        "the controller carries a step budget of 1"
        (:step-budget @ctl) => 1)))

  (component "escapement.control/continue"
    (let [ctl (dbg/new-controller {:initial-pause? true})
          res (r/process (ctx-with ctl (ch/new-handle))
                `[(escapement.control/continue {})])]
      (assertions
        "the mutation result reports the run resumed"
        (get res `escapement.control/continue) => {:debug/paused? false :debug/step-budget 0}
        "the controller is no longer paused"
        (dbg/paused? ctl) => false)))

  (component "escapement.control/arm-pause-on-next-external"
    (let [ctl (dbg/new-controller)
          res (r/process (ctx-with ctl (ch/new-handle))
                `[(escapement.control/arm-pause-on-next-external {})])]
      (assertions
        "the mutation returns the current (unpaused) status"
        (get res `escapement.control/arm-pause-on-next-external) => {:debug/paused? false :debug/step-budget 0}
        "the controller is armed to pause on the next external event"
        (:pause-on-next-external? @ctl) => true))))

(specification "live resolvers read pause state from the controller"
  (let [ctl (dbg/new-controller {:initial-pause? true})
        ctx (ctx-with ctl (ch/new-handle))]
    (assertions
      "session/paused? reflects the controller"
      (r/process ctx [:session/paused?]) => {:session/paused? true}
      "session/step-budget reflects the controller"
      (r/process ctx [:session/step-budget]) => {:session/step-budget 0})))

(specification "live resolvers are nil-tolerant with no live handle/controller"
  (let [ctx {:escapement/store nil}]
    (assertions
      "session/paused? resolves to nil (the not-found sentinel is elided; no value, no crash) when no controller is attached"
      (:session/paused? (r/process ctx [:session/paused?])) => nil
      "session/pending-events yields an empty list when no live queue exists"
      (r/process ctx [{:session/pending-events [:event/name]}]) => {:session/pending-events []})))

(specification "session/pending-events reads the live instrumented queue"
  (let [ctl    (dbg/new-controller)
        q      (iq/new-instrumented-queue {:controller ctl})
        _      (sp/send! q {} {:event :foo :target :s1 :data {:a 1}})
        handle (ch/fill! (ch/new-handle) {:env {} :session-id :s1 :queue q :controller ctl})
        ctx    (ctx-with ctl handle)
        res    (r/process ctx [{:session/pending-events [:event/name :event/target :event/data]}])]
    (assertions
      "lists the queued-but-undelivered event with name/target/data"
      (:session/pending-events res)
      => [{:event/name :foo :event/target :s1 :event/data {:a 1}}])))

(specification "session/live-configuration reads the live working-memory store"
  ;; Stub a working-memory store that returns a fixed configuration for the
  ;; session, exactly like runtime/current-configuration expects.
  (let [sid    :s1
        store  (reify sp/WorkingMemoryStore
                 (get-working-memory [_ _ _] {::sc/configuration #{:work :idle}})
                 (save-working-memory! [_ _ _ _] nil)
                 (delete-working-memory! [_ _ _] nil))
        env    {::sc/working-memory-store store}
        handle (ch/fill! (ch/new-handle) {:env env :session-id sid :queue nil :controller nil})
        ctx    {:escapement/store nil :escapement/live handle}
        res    (r/process ctx [:session/live-configuration])]
    (assertions
      "returns the active state ids of the live session"
      (set (:session/live-configuration res)) => #{:work :idle})))
