(ns escapement.debug.controller
  "Shared pause/step controller for the debug TUI overlay. The runner consults
   this controller before processing each event; the TUI mutates it in
   response to keystrokes (`s` step, `c` continue, `p` pause).

   The controller is a plain atom holding:

     {:mode        :run | :paused
      :step-budget non-negative integer  ;; events the runner may process
                                         ;; before re-checking mode
      :gate        promise               ;; runner parks on this when paused;
                                         ;; release! delivers and replaces it
      :pause-on-next-external? boolean   ;; one-shot: next external send
                                         ;; flips :mode to :paused}

   Babashka-compatible: only `clojure.core` + promises."
  (:refer-clojure :exclude [step]))

(defn new-controller
  "Returns a fresh controller atom. `:initial-pause?` (default false) starts
   the controller in `:paused` mode."
  ([] (new-controller {}))
  ([{:keys [initial-pause?]}]
   (atom {:mode                    (if initial-pause? :paused :run)
          :step-budget             0
          :gate                    (promise)
          :pause-on-next-external? false})))

(defn paused?
  "True when the controller is currently halting event processing."
  [controller]
  (and (= :paused (:mode @controller))
    (zero? (:step-budget @controller))))

(defn await-release!
  "Blocks the calling thread until the controller is allowed to process the
   next event. Returns immediately if not paused."
  [controller]
  (when (paused? controller)
    (let [gate (:gate @controller)]
      @gate)))

(defn- fresh-gate!
  "Replaces the controller's gate with a new unrealized promise so the next
   pause cycle has something to park on."
  [controller]
  (swap! controller assoc :gate (promise)))

(defn continue!
  "Switch the controller to `:run` and release any parked runner thread."
  [controller]
  (let [{:keys [gate]} @controller]
    (swap! controller assoc :mode :run :step-budget 0)
    (when (and gate (not (realized? gate)))
      (deliver gate :continue))
    (fresh-gate! controller)))

(defn step!
  "Allow exactly one more event to be processed, then re-pause. Releases any
   parked runner thread so it picks up the budgeted event."
  [controller]
  (let [{:keys [gate]} @controller]
    (swap! controller assoc :mode :paused :step-budget 1)
    (when (and gate (not (realized? gate)))
      (deliver gate :step))
    (fresh-gate! controller)))

(defn pause!
  "Switch the controller to `:paused`. The runner will halt on its next event."
  [controller]
  (swap! controller assoc :mode :paused :step-budget 0))

(defn arm-pause-on-next-external!
  "One-shot: the next event tagged `:external? true` flips the controller to
   `:paused` before processing."
  [controller]
  (swap! controller assoc :pause-on-next-external? true))

(defn consume-step-budget!
  "Decrements the step budget by one (clamped at 0). Called by the runner
   immediately after it has chosen to process an event under a step budget."
  [controller]
  (swap! controller update :step-budget (fn [n] (max 0 (dec (or n 0))))))

(defn maybe-arm-from-external!
  "If the controller is armed for `pause-on-next-external?` and `event-meta`
   indicates an external event, transitions to `:paused` and clears the arm.
   Returns true when it pauses."
  [controller event-meta]
  (when (and (:pause-on-next-external? @controller)
          (:external? event-meta))
    (swap! controller assoc :mode :paused :step-budget 0
      :pause-on-next-external? false)
    true))
