(ns escapement.ui.screens.root
  "Root and the routing outlet for the Escapement explorer. `Routes` is the statechart routing
   root (`:routing/root`); it renders whichever report is the active route via
   `scr/ui-current-subroute`. `Root` frames the routed content with a simple nav.

   CLJC and host-neutral: rendering uses `com.fulcrologic.fulcro.dom` (dom-server on CLJ, dom on
   CLJS), which the shipped headless RAD plugin also uses — so this renders under headless tests and
   in the browser. The TUI render plugin is a separate later task; nav targets are registry
   keywords (not class refs) to keep this ns free of require cycles with the report namespaces."
  (:require
    #?(:clj  [com.fulcrologic.fulcro.dom-server :as dom]
       :cljs [com.fulcrologic.fulcro.dom :as dom])
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.statecharts :as-alias sc]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.integration.fulcro.routing :as scr]))

(defsc Routes [this _props]
  {:query                   [:ui/current-route]
   :ident                   (fn [] [:component/id ::Routes])
   :preserve-dynamic-query? true
   :initial-state           {}}
  (dom/div {:data-rad-type "route-container"}
    (scr/ui-current-subroute this comp/factory)))

(def ui-routes (comp/factory Routes))

(def ^:private nav-targets
  "Top-nav report targets as `[registry-key label]`. Registry keywords (not class refs) avoid
   require cycles with the report namespaces."
  [[:escapement.ui.screens.sessions-report/SessionsReport "Sessions"]
   [:escapement.ui.screens.session-detail/EventsReport     "Events"]
   [:escapement.ui.screens.session-detail/ArtifactsReport  "Artifacts"]
   [:escapement.ui.screens.debugger/Debugger              "Debugger"]
   [:escapement.ui.screens.chart-view/ChartView           "Chart"]])

(defn- nav-bar
  "A row of buttons routing between the explorer's top-level reports."
  [this]
  (dom/nav {:data-rad-type "nav-bar"}
    (dom/span {:data-rad-type "app-title"} "Escapement Explorer")
    (mapv (fn [[target label]]
            (dom/button {:key           (str target)
                         :data-rad-type "nav-button"
                         :data-label    label
                         :onClick       (fn [] (scr/route-to! this target {}))}
              label))
      nav-targets)))

(defsc Root [this {:ui/keys [routes]}]
  {:query         [{:ui/routes (comp/get-query Routes)}
                   [::sc/session-id '_]]
   :initial-state {:ui/routes {}}}
  (dom/div {:data-rad-type "app-root"}
    (nav-bar this)
    (dom/div {:data-rad-type "main-content"}
      (if (seq (scf/current-configuration this scr/session-id))
        (ui-routes routes)
        (dom/div {:data-rad-type "loading"} "Starting…")))))
