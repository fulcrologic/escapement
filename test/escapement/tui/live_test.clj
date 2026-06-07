(ns escapement.tui.live-test
  "Direct-module specs for the extracted LIVE renderer (escapement.tui.live).
   The facade re-exports these same fns, exercised more fully in
   escapement.tui-test; here we assert the module resolves and behaves on its
   own (no facade dependency)."
  (:require
    [escapement.tui.live :as live]
    [escapement.tui.theme :as theme]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def ^:private none (theme/theme-for :none))

(specification "live/completion-bar — honest fill fraction, exact width"
  (assertions
    "6/30 over 10 cells → 2 filled"
    (count (filter #(= \█ %) (live/completion-bar none 6 30 10))) => 2
    "full → all filled"
    (count (filter #(= \█ %) (live/completion-bar none 6 6 10))) => 10
    "exact display width under :none"
    (count (live/completion-bar none 6 30 10)) => 10
    "zero width → empty"
    (live/completion-bar none 6 30 0) => ""))

(specification "live/shimmer — one bright cell, deterministic, wraps by width"
  (assertions
    "exactly one bright cell"
    (count (filter #(= \▰ %) (live/shimmer none 5 0))) => 1
    "same tick → same output"
    (live/shimmer none 5 3) => (live/shimmer none 5 3)
    "advances with tick"
    (not= (live/shimmer none 5 0) (live/shimmer none 5 1)) => true
    "wraps modulo width"
    (live/shimmer none 5 5) => (live/shimmer none 5 0)))

(specification "live/live-pane-lines — empty live map → no lines; offset slices"
  (let [s {:live {"poet" {:sessions
                          {"s1" {:status :streaming :tokens 5 :first-ts 0 :last-ts 1000
                                 :session "poet" :model "m"}}}}}
        lines (live/live-pane-lines s none 60)]
    (assertions
      "no live activity → empty"
      (live/live-pane-lines {:live {}} none 60) => []
      "offset drops leading lines"
      (live/live-pane-lines s none 60 1) => (vec (drop 1 lines)))))

(specification "live/live-row-index — visible-row → invokeid/session, in lockstep with the pane"
  (let [;; one single-session group + one multi-session group (3 kids)
        s {:live {"poet"  {:sessions {"poet" {:status :streaming :tokens 5
                                              :first-ts 0 :last-ts 1000
                                              :session "poet" :model "m"}}}
                  "judge" {:sessions {"j1" {:status :streaming :tokens 3 :last-ts 900
                                            :session "judge.1" :model "m"}
                                      "j2" {:status :waiting :tokens 1 :last-ts 800
                                            :session "judge.2" :model "m"}
                                      "j3" {:status :done :tokens 9 :last-ts 700
                                            :session "judge.3" :model "m"}}}}}
        idx   (live/live-row-index s)
        lines (live/live-pane-lines s none 60)]
    (assertions
      "row count matches the rendered pane row count exactly"
      (count idx) => (count lines)
      "single-session group → one :session row pointing at the lone session"
      (some #(and (= "poet" (:invokeid %)) (= :session (:kind %))) idx) => true
      "multi-session group emits a :group header row for the invokeid"
      (some #(and (= "judge" (:invokeid %)) (= :group (:kind %))) idx) => true
      "the :group header opens the representative (most in-flight) session"
      (:session (first (filter #(= :group (:kind %)) idx))) => "judge.1"
      "each child session is selectable by its own session id"
      (set (map :session (filter #(and (= "judge" (:invokeid %)) (= :session (:kind %))) idx)))
      => #{"judge.1" "judge.2" "judge.3"}
      "empty live → empty index"
      (live/live-row-index {:live {}}) => [])))

(specification "live/live-pane-lines — cursor highlight only when focused"
  (let [s {:live {"poet" {:sessions
                          {"poet" {:status :streaming :tokens 5 :first-ts 0 :last-ts 1000
                                   :session "poet" :model "m"}}}}}]
    (assertions
      "no opts → no reverse-video escape"
      (boolean (re-find #"\[7m" (first (live/live-pane-lines s none 60 0 nil)))) => false
      "focused + cursor 0 → row 0 carries the reverse-video escape"
      (boolean (re-find #"\[7m" (first (live/live-pane-lines s none 60 0 {:focus? true :cursor 0})))) => true
      "cursor present but not focused → no highlight"
      (boolean (re-find #"\[7m" (first (live/live-pane-lines s none 60 0 {:focus? false :cursor 0})))) => false)))

(specification "live/live-agg + live-tps — aggregation primitives"
  (assertions
    "tps = tokens / (last-first secs)"
    (live/live-tps {:tokens 30 :first-ts 0 :last-ts 1000}) => 30.0
    "agg sums tokens + counts active"
    (select-keys
      (live/live-agg {"a" {:status :streaming :tokens 10}
                      "b" {:status :done :tokens 20}})
      [:tokens :n :n-active :n-done])
    => {:tokens 30 :n 2 :n-active 1 :n-done 1}))

(specification "live/cap-tail — bounded in-flight partial (BUG 1A)"
  (assertions
    "short text passes through unchanged"
    (live/cap-tail "hello") => "hello"
    "over-cap text keeps only the trailing tail"
    (let [big (apply str (repeat (* 2 live/live-partial-tail-chars) "x"))
          out (live/cap-tail big)]
      [(count out) (= out (subs big (- (count big) live/live-partial-tail-chars)))])
    => [live/live-partial-tail-chars true]
    "exactly-cap text is untouched"
    (count (live/cap-tail (apply str (repeat live/live-partial-tail-chars "y"))))
    => live/live-partial-tail-chars))

(specification "live/invokeid-live? — still-streaming predicate (BUG 2B)"
  (let [s {:live {"judge1" {:sessions {"a" {:status :done}
                                       "b" {:status :streaming}}}
                  "host"   {:sessions {"h" {:status :done}}}
                  "planner"{:sessions {"p" {:status :waiting}}}}}]
    (assertions
      "live when any session is streaming"
      (live/invokeid-live? s "judge1") => true
      "live when any session is waiting"
      (live/invokeid-live? s "planner") => true
      "not live when all sessions are done"
      (live/invokeid-live? s "host") => false
      "unknown invokeid is not live"
      (live/invokeid-live? s "nope") => false
      "nil invokeid is not live"
      (live/invokeid-live? s nil) => false)))
