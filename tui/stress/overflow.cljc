(ns stress.overflow
  "TUI render stress — HEADER OVERFLOW tier (model-free).

   Purpose: blow past the header's sibling-strip width so you can see how the
   phase tracker (line 3 of the header) handles a chart with a HUGE number of
   sibling states. The strip is a sliding window centered on the current state
   (`◉ …`) that grows symmetrically until it would overflow the header width,
   then drops a `…` ellipsis on each overflowing edge (see
   `escapement.tui.phase/sibling-strip` and `tui/opentui/src/ui/Header.tsx`).

   Structure: a single compound `:run` with `steps` children (default 80), each
   given a deliberately LONG id so the strip fills fast. The chart walks forward
   one state at a time via `send-after` and then PARKS on the middle state (no
   outgoing transition, not final). Because the active state sits in the middle
   of a large sibling set, the strip shows `… · left · ◉ middle · right · …` —
   overflow on BOTH edges. The siblings to the right are real states that are
   simply never entered, so they render as `upcoming`.

   It never finishes on its own (it parks). Quit with Ctrl-C / `q`. This needs
   NO model and NO backend — it is pure statechart timing.

   Run (JLine, the default in-process TUI):

     bb -m escapement.cli run stress.overflow/agent

   Run (OpenTUI sidecar; needs a real TTY + bun):

     bb -m escapement.cli run stress.overflow/agent --tui=opentui

   Tuning: the sibling COUNT is the chart topology, so it is a compile-time
   constant, not a --param. Edit the `steps` / `step-ms` / `park` defs below to
   change the number of states, the walk speed, or where it parks."
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state transition]]
    [com.fulcrologic.statecharts.convenience :refer [send-after]]))

;; Compile-time constants — the chart topology (how many sibling states exist)
;; is fixed at build time. Edit these to change the stress level.
(def steps 80)
(def step-ms 120)
(def park (quot steps 2))

(defn step-id
  "Long, descriptive id so each sibling eats lots of columns."
  [i]
  (keyword (format "phase-%03d-render-stress-segment" i)))

(defn step-state
  "One sibling state. Steps before the park walk forward on a timer; the park
   state and everything after it have no outgoing transition (park = the one we
   stop on; the rest are never entered but exist to fill the strip's right side)."
  [i]
  (let [walking? (< i park)]
    (apply state {:id (step-id i)}
      (when walking?
        [(send-after {:id    (keyword (str "walk-timer-" i))
                      :event :walk/next
                      :delay step-ms})
         (transition {:event :walk/next :target (step-id (inc i))})]))))

(def agent
  (apply chart/statechart
    {:initial :run}
    (apply state {:id :run :initial (step-id 0)}
      (map step-state (range steps)))
    []))
