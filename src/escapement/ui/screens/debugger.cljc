(ns escapement.ui.screens.debugger
  "Debugger — the live single-step control panel for the running Escapement engine.

   Reads the shared live debug snapshot (`escapement.ui.control/live-ident`): pause state, step
   budget, the active statechart configuration, and the queue of pending (not-yet-delivered) events.
   Four buttons fire the server control mutations (pause / step / continue / arm-pause-on-next-
   external) and then reload that snapshot, so the panel always reflects the engine.

   Pure Fulcro data flow — NO React hooks. Live data is loaded into the singleton `[:component/id
   ::live]` entity that this panel queries via a link query (so the same entity is shared with the
   ChartView). When no live run is attached the resolvers return `not-found`, so the snapshot keys
   are simply absent and we render a 'no live session' notice.

   CLJC + host-neutral DOM (dom-server on CLJ, dom on CLJS) so it renders under headless tests."
  (:require
    #?(:clj  [com.fulcrologic.fulcro.dom-server :as dom]
       :cljs [com.fulcrologic.fulcro.dom :as dom])
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [escapement.ui.control :as control]))

(defn- live-snapshot
  "Pull the shared live snapshot map out of `props` (read via the `::live` link query)."
  [props]
  (get props [:component/id :escapement.ui.control/live]))

(defn- ui-config-list
  "Render the active configuration `state-ids` (a coll of keywords) as a list, or a placeholder."
  [state-ids]
  (if (seq state-ids)
    (dom/ul {:data-rad-type "config-list"}
      (mapv (fn [sid]
              (dom/li {:key (str sid) :data-rad-type "config-state"} (str sid)))
        (sort-by str state-ids)))
    (dom/div {:data-rad-type "empty"} "No active states")))

(defn- ui-pending-events
  "Render the `events` (each `{:event/name :event/target :event/external? …}`) as a table."
  [events]
  (if (seq events)
    (dom/table {:data-rad-type "pending-events" :className "ui celled compact table"}
      (dom/thead {}
        (dom/tr {}
          (dom/th {} "Name")
          (dom/th {} "Target")
          (dom/th {} "External?")))
      (dom/tbody {}
        (mapv (fn [{:event/keys [name target external?]}]
                (dom/tr {:key (str name "|" target)}
                  (dom/td {} (str name))
                  (dom/td {} (str target))
                  (dom/td {} (if external? "yes" "no"))))
          events)))
    (dom/div {:data-rad-type "empty"} "No pending events")))

(defsc Debugger [this props]
  {:query         [{[:component/id :escapement.ui.control/live]
                    [:session/paused?
                     :session/step-budget
                     :session/live-configuration
                     {:session/pending-events [:event/name :event/data :event/target
                                               :event/external? :event/delivery-time]}]}]
   :ident             (fn [] [:component/id ::Debugger])
   :initial-state     {}
   :route/segment     "debugger"
   ;; Class lifecycle (NOT a React hook): load the shared live snapshot when the panel mounts so the
   ;; status/configuration/pending-events appear without user interaction.
   :componentDidMount (fn [this] (control/refresh-live! this))}
  (let [{:session/keys [paused? step-budget live-configuration pending-events]} (live-snapshot props)
        ;; A live run is attached iff any live datum resolved (paused? is a boolean; nil = not-found).
        live? (some? paused?)]
    (dom/div {:data-rad-type "debugger" :className "ui segment"}
      (dom/h2 {:className "ui header"} "Debugger")
      (dom/div {:data-rad-type "controls" :className "ui buttons"}
        (dom/button {:className "ui button" :data-rad-type "btn-pause"
                     :onClick   (fn [] (control/pause! this))} "Pause")
        (dom/button {:className "ui primary button" :data-rad-type "btn-step"
                     :onClick   (fn [] (control/step! this))} "Step")
        (dom/button {:className "ui button" :data-rad-type "btn-continue"
                     :onClick   (fn [] (control/continue! this))} "Continue")
        (dom/button {:className "ui button" :data-rad-type "btn-arm"
                     :onClick   (fn [] (control/arm! this))} "Arm pause-on-next-external"))
      (dom/button {:className "ui basic button" :data-rad-type "btn-refresh"
                   :style     {:marginLeft "0.5em"}
                   :onClick   (fn [] (control/refresh-live! this))} "Refresh")
      (if-not live?
        (dom/div {:data-rad-type "no-live-session" :className "ui info message"
                  :style         {:marginTop "1em"}}
          "No live session attached (read-only or not yet started).")
        (comp/fragment
          (dom/div {:data-rad-type "status" :className "ui list" :style {:marginTop "1em"}}
            (dom/div {:className "item"}
              (dom/strong {} "Paused: ") (if paused? "yes" "no"))
            (dom/div {:className "item"}
              (dom/strong {} "Step budget: ") (str (or step-budget 0))))
          (dom/h3 {:className "ui header"} "Active configuration")
          (ui-config-list live-configuration)
          (dom/h3 {:className "ui header"} "Pending events")
          (ui-pending-events pending-events))))))
