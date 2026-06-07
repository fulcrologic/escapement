(ns escapement.tui.inspector-test
  "Unit tests for the themed overlay frame's pure rect/scroll math (task 004).
   `scroll-window` decides which slice of content rows the `draw-box` shows and
   what `⇅ pos/total` reports — it must keep the highlighted/selected row in
   view and never index out of bounds."
  (:require
    [escapement.tui.inspector :as insp]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(specification "scroll-window — rect/scroll math for the overlay box"
  (component "all content fits in the window"
    (assertions
      "start at 0, pos tracks the kept row (1-based), total echoed"
      (insp/scroll-window 5 10 2) => {:start 0 :total 5 :pos 3}
      "nil keep ⇒ pos is start+1"
      (insp/scroll-window 5 10 nil) => {:start 0 :total 5 :pos 1}))

  (component "empty content"
    (assertions
      "zero total ⇒ zero start and pos"
      (insp/scroll-window 0 10 nil) => {:start 0 :total 0 :pos 0}
      "zero total ignores a stray keep"
      (insp/scroll-window 0 10 3) => {:start 0 :total 0 :pos 0}))

  (component "content taller than the window scrolls to keep the row visible"
    (assertions
      "kept row within the first window ⇒ no scroll"
      (insp/scroll-window 40 10 0) => {:start 0 :total 40 :pos 1}
      (insp/scroll-window 40 10 9) => {:start 0 :total 40 :pos 10}
      "kept row just past the window ⇒ scroll down by one"
      (insp/scroll-window 40 10 10) => {:start 1 :total 40 :pos 11}
      "kept row deep in the list ⇒ window ends on the kept row"
      (insp/scroll-window 40 10 30) => {:start 21 :total 40 :pos 31}
      "kept row at the very end clamps to max-start"
      (insp/scroll-window 40 10 39) => {:start 30 :total 40 :pos 40}))

  (component "nil keep with overflow shows the top window"
    (assertions
      (insp/scroll-window 40 10 nil) => {:start 0 :total 40 :pos 1}))

  (component "defensive flooring of negative inputs"
    (assertions
      "negative total/room floored to 0"
      (insp/scroll-window -3 -2 nil) => {:start 0 :total 0 :pos 0}))

  (component "start never exceeds max-start (full window when possible)"
    (assertions
      "pager offset near the end still leaves a full window of rows"
      (let [{:keys [start total]} (insp/scroll-window 12 5 11)]
        [(<= start (- total 5)) (>= start 0)]) => [true true])))

(specification "selected-invokeid — pure cursor→invokeid helper (task 005/007)"
  (let [hist [{:invokeid "judge2"} {:invokeid "judge1"} {:invokeid "planner"}]]
    (component "Invocations LIST view returns the invokeid at :cursor (newest-first)"
      (assertions
        "cursor 0 ⇒ first (newest) row"
        (insp/selected-invokeid {:view :invocations :cursor 0 :invocations hist})
        => "judge2"
        "cursor 2 ⇒ third row"
        (insp/selected-invokeid {:view :invocations :cursor 2 :invocations hist})
        => "planner"
        "missing cursor defaults to 0"
        (insp/selected-invokeid {:view :invocations :invocations hist})
        => "judge2"
        "out-of-range cursor ⇒ nil (no throw)"
        (insp/selected-invokeid {:view :invocations :cursor 9 :invocations hist})
        => nil))
    (component "no selection outside the Invocations list view"
      (assertions
        "Chart view ⇒ nil"
        (insp/selected-invokeid {:view :chart :cursor 0 :invocations hist}) => nil
        "Status view ⇒ nil"
        (insp/selected-invokeid {:view :status :cursor 0 :invocations hist}) => nil
        "artifact drilldown (:focus) ⇒ nil"
        (insp/selected-invokeid {:view :invocations :cursor 0 :focus {:invokeid "judge2"}
                                 :invocations hist}) => nil
        "empty history ⇒ nil"
        (insp/selected-invokeid {:view :invocations :cursor 0 :invocations []}) => nil))))
