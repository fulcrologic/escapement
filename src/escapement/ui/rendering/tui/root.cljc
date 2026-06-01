(ns escapement.ui.rendering.tui.root
  "Terminal (fulcro-tui) root + routing outlet for the Escapement explorer.

   The shared `escapement.ui.screens.root` renders the explorer to DOM (browser + headless-DOM
   tests). The fulcro-tui engine paints fulcro-tui *element* maps (`vbox`/`hbox`/`text`), not DOM
   hiccup, so the terminal target needs its own root that frames the routed content with TUI
   elements. The routed REPORTS themselves are rendered by the fulcro-tui RAD plugin
   (`escapement.ui.rendering.tui.plugin`), so only this thin frame is TUI-specific.

   `Routes` here is the routing outlet pinned by `escapement.ui.rendering.tui.routing/routing-chart`
   (`:routing/root`). The routing system rewrites this class's dynamic query so `:ui/current-route`
   becomes a join to the active route's component; `scr/ui-current-subroute` then renders the active
   report here as TUI elements (via the fulcro-tui RAD plugin)."
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.tui.elements :as e]
    [com.fulcrologic.statecharts :as-alias sc]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.integration.fulcro.routing :as scr]))

(defsc Routes [this _props]
  {:query                   [:ui/current-route]
   :ident                   (fn [] [:component/id ::Routes])
   :preserve-dynamic-query? true
   :initial-state           {}}
  (scr/ui-current-subroute this comp/factory))

(def ui-routes (comp/factory Routes))

(def ^:private nav-targets
  "Top-nav report targets as `[registry-key label]`. Registry keywords (not class refs) avoid
   require cycles with the report namespaces."
  [[:escapement.ui.screens.sessions-report/SessionsReport "Sessions"]
   [:escapement.ui.screens.session-detail/EventsReport     "Events"]
   [:escapement.ui.screens.session-detail/ArtifactsReport  "Artifacts"]])

(defn- nav-bar
  "A row of TUI buttons routing between the explorer's top-level reports."
  [this]
  (e/hbox {:height 1}
    (e/text {:bold true :color :bright-cyan} "Escapement Explorer  ")
    (mapv (fn [[target label]]
            (let [bid (keyword "nav" (name target))]
              (e/button {:id          bid
                         :color       :bright-blue
                         :highlight   (e/focused? bid)
                         :on-activate (fn [] (scr/route-to! this target {}))}
                (str " " label " "))))
      nav-targets)))

(defsc Root [this {:ui/keys [routes]}]
  {:query         [{:ui/routes (comp/get-query Routes)}
                   [::sc/session-id '_]]
   :initial-state {:ui/routes {}}}
  (e/vbox {:padding 1 :border? true :color :cyan :grow 1}
    (nav-bar this)
    (e/line {})
    (if (seq (scf/current-configuration this scr/session-id))
      (ui-routes routes)
      (e/text "Starting…"))))
