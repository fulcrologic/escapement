(ns escapement.tui.log-test
  "Direct-module specs for the extracted LOG renderer (escapement.tui.log)."
  (:require
    [clojure.string :as str]
    [escapement.tui.log :as log]
    [escapement.tui.theme :as theme]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def ^:private none (theme/theme-for :none))

(defn- mk-scrollback [n]
  (mapv (fn [i] {:source :runner :glyph \· :summary (str "line " i)
                 :ev {:ts (* i 1000)}})
    (range n)))

(specification "log/log-pane-lines — slice math, clamping, pos/total"
  (let [s {:scrollback (mk-scrollback 10)}
        r (log/log-pane-lines s none 40 4 0)]
    (assertions
      "fits interior-h rows"
      (count (:lines r)) => 4
      "tail shows newest at bottom"
      (boolean (re-find #"line 9" (last (:lines r)))) => true
      "pos/total at live tail"
      (:scroll r) => {:pos 10 :total 10}
      "offset past top clamps to oldest window"
      (:scroll (log/log-pane-lines s none 40 4 999)) => {:pos 4 :total 10}
      "negative offset clamps to 0 (== tail)"
      (log/log-pane-lines s none 40 4 -5) => (log/log-pane-lines s none 40 4 0)
      "empty scrollback → no lines, 0/0"
      (log/log-pane-lines {:scrollback []} none 40 5 0)
      => {:lines [] :scroll {:pos 0 :total 0}})))

(specification "log/log-pane-lines — width-fit lines"
  (let [r (log/log-pane-lines {:scrollback (mk-scrollback 3)} none 40 5 0)]
    (assertions
      "each line exactly interior-w wide under :none"
      (every? #(= 40 (count %)) (:lines r)) => true)))
