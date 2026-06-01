(ns escapement.ui.screens.chart-view
  "ChartView — embeds the statecharts visualizer to draw the live Escapement chart with the running
   session's active states highlighted.

   The visualizer (`com.fulcrologic.statecharts.visualization.visualizer`) is a
   `comp/computed-factory`: the chart map and the active-configuration set go in the COMPUTED (2nd)
   arg, so we feed it a MAP (`:chart/definition`, fns already stripped to `:fn` by the server) and a
   SET of active state ids — this avoids the statecharts-Fulcro session lookup entirely.

   Data flow (pure Fulcro, NO React hooks):
     * On mount we `merge` an empty `viz/Visualizer` placeholder (it is a singleton ident) and
       `df/load!` the chart definition into this component, plus refresh the shared live snapshot.
     * The chart-def is stored under this component's `:ui/chart-def`.
     * The active configuration is read from the SHARED `[:component/id ::live]` entity
       (`:session/live-configuration`) that the Debugger also drives — so stepping in the Debugger
       updates the highlight here, and a 'Refresh' button reloads it from this screen too.

   CLJC: the visualizer is CLJS-only, so the actual factory call is guarded to `:cljs`; the CLJ
   branch renders a placeholder so the ns still loads/renders under headless CLJ tests."
  (:require
    #?(:clj  [com.fulcrologic.fulcro.dom-server :as dom]
       :cljs [com.fulcrologic.fulcro.dom :as dom])
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [escapement.ui.control :as control]
    #?(:cljs [com.fulcrologic.statecharts.visualization.visualizer :as viz])))

;; A CLJC-safe handle on the visualizer's query + factory. Under CLJ (headless) there is no
;; visualizer, so the query degrades to nil and rendering uses a placeholder.
(def visualizer-query
  "The visualizer component's query (nil on CLJ where the visualizer is unavailable)."
  #?(:cljs (comp/get-query viz/Visualizer)
     :clj  nil))

(defn- mount-load!
  "On mount: install an empty visualizer placeholder under `:ui/visualizer`, load the live chart
   definition into `:ui/chart-def`, and refresh the shared live snapshot (for the configuration)."
  [this]
  #?(:cljs
     (merge/merge-component! this viz/Visualizer {}
       :replace (conj (comp/get-ident this) :ui/visualizer)))
  (df/load! this :escapement/chart nil
    {:target (conj (comp/get-ident this) :ui/chart-def)})
  (control/refresh-live! this))

(defn- ui-visualizer*
  "Render the visualizer for `chart-def` (a `:chart/definition` map) highlighting `config` (a coll of
   active state ids), using the queried `visualizer-props`. CLJS-only; CLJ renders a placeholder."
  [visualizer-props chart-def config]
  #?(:cljs
     (if (and visualizer-props (map? chart-def) (seq chart-def))
       (viz/ui-visualizer visualizer-props {:chart                 chart-def
                                            :current-configuration (set config)})
       (dom/div {:data-rad-type "empty"} "Chart definition not loaded"))
     :clj
     (dom/div {:data-rad-type "viz-placeholder"}
       (if (seq chart-def) "[visualizer: cljs-only]" "Chart definition not loaded"))))

(defsc ChartView [this {:ui/keys [visualizer chart-def] :as props}]
  {:query             (fn [] [{:ui/visualizer visualizer-query}
                              {:ui/chart-def [:chart/definition]}
                              {[:component/id :escapement.ui.control/live] [:session/live-configuration]}])
   :ident             (fn [] [:component/id ::ChartView])
   :initial-state     (fn [_] {})
   :route/segment     "chart"
   ;; Class lifecycle (NOT a React hook): seed the visualizer + load the chart and live config.
   :componentDidMount (fn [this] (mount-load! this))}
  (let [definition (:chart/definition chart-def)
        config     (get-in props [[:component/id :escapement.ui.control/live]
                                  :session/live-configuration])]
    (dom/div {:data-rad-type "chart-view" :className "ui segment"}
      (dom/div {:data-rad-type "chart-toolbar" :style {:marginBottom "0.5em"}}
        (dom/h2 {:className "ui header" :style {:display "inline-block" :marginRight "1em"}} "Chart")
        (dom/button {:className "ui basic button" :data-rad-type "btn-refresh"
                     :onClick   (fn [] (mount-load! this))} "Refresh"))
      (ui-visualizer* visualizer definition config))))
