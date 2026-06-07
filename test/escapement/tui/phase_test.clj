(ns escapement.tui.phase-test
  "Direct-module specs for the extracted phase tracker (escapement.tui.phase)."
  (:require
    [clojure.string :as str]
    [com.fulcrologic.statecharts.chart :as sc.chart]
    [com.fulcrologic.statecharts.elements :as sc.e]
    [escapement.tui.phase :as phase]
    [escapement.tui.theme :as theme]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def ^:private none (theme/theme-for :none))

(def ^:private linear-chart
  (sc.chart/statechart {}
    (sc.e/state {:id :run}
      (sc.e/state {:id :composing})
      (sc.e/state {:id :judging-r1})
      (sc.e/state {:id :tallying-r1})
      (sc.e/state {:id :summarizing}))))

(specification "phase/phase-model — linear chart breadcrumb + flagged sibling"
  (let [m (phase/phase-model linear-chart [:run :judging-r1])]
    (assertions
      "linear, not fallback"
      [(:fallback? m) (:parallel? m)] => [false false]
      "breadcrumb root→active"
      (:breadcrumb m) => [:run :judging-r1]
      "current = deepest active"
      (:current m) => :judging-r1
      "siblings in document order"
      (mapv :id (:siblings m)) => [:composing :judging-r1 :tallying-r1 :summarizing]
      "only current flagged"
      (mapv :current? (:siblings m)) => [false true false false])))

(specification "phase/phase-model — nil chart degrades to fallback"
  (let [m (phase/phase-model nil [:run :judging-r1])]
    (assertions
      "fallback flagged"  (:fallback? m) => true
      "raw config kept"   (:raw-config m) => [:run :judging-r1])))

(specification "phase/sibling-strip — marks current, width-bounded"
  (let [m   (phase/phase-model linear-chart [:run :judging-r1])
        out (phase/sibling-strip m 40 none)]
    (assertions
      "marks the current sibling"
      (str/includes? out "◉ judging-r1") => true
      "at most the requested width"
      (<= (count out) 40) => true)))

(specification "phase/header-lines — three width-fit lines, fallback states: line"
  (let [h {:chart-sym 'x/y :session-short "sess" :env (atom nil)}
        s {:config [:run :judging-r1] :start-ts 0 :live {}}
        lines (phase/header-lines h s none 80 0)]
    (assertions
      "exactly three lines"
      (count lines) => 3
      "each exactly the requested width under :none"
      (mapv count lines) => [80 80 80]
      "no chart attached → states: fallback on line 3"
      (str/includes? (nth lines 2) "states:") => true)))
