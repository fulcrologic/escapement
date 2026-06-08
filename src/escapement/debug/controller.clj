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
                                         ;; flips :mode to :paused

      ;; --- LLM turn-level gate (task 005) ----------------------------------
      ;; Independent of the per-event gate above. The LLM worker is a long-lived
      ;; background thread (not a queue event), so it parks on a SEPARATE
      ;; turn-gate before issuing each turn. The per-event gate
      ;; (instrumented-queue) is untouched by these.
      :pause-before-next-llm-turn? boolean ;; one-shot arm: next LLM turn parks
      :turn-gate                   promise ;; worker parks here; release replaces
      :turn-index                  int}    ;; pointer into captured turns
                                           ;; (turn-next!/turn-back! move it)

   Babashka-compatible: only `clojure.core` + promises."
  (:refer-clojure :exclude [step]))

(defn new-controller
  "Returns a fresh controller atom. `:initial-pause?` (default false) starts
   the controller in `:paused` mode."
  ([] (new-controller {}))
  ([{:keys [initial-pause?]}]
   (atom {:mode                        (if initial-pause? :paused :run)
          :step-budget                 0
          :gate                        (promise)
          :pause-on-next-external?     false
          :pause-before-next-llm-turn? false
          :turn-gate                   (promise)
          :turn-index                  0})))

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

;; ---------------------------------------------------------------------------
;; LLM turn-level gate (task 005)
;;
;; A SECOND, independent promise gate that the LLM worker thread parks on at the
;; *turn boundary* (before issuing the next LLM turn). It mirrors the per-event
;; gate discipline above (atom + replaceable promise) but is wholly separate:
;; arming/releasing the turn-gate never touches `:mode`/`:step-budget`/`:gate`,
;; and the per-event gate never touches these keys. The two can be used together.
;; ---------------------------------------------------------------------------

(defn turn-armed?
  "True when the worker should park before issuing its next LLM turn."
  [controller]
  (boolean (:pause-before-next-llm-turn? @controller)))

(defn arm-llm-breakpoint!
  "Arm the one-shot LLM turn breakpoint: the worker will park on the turn-gate
   before issuing its next turn."
  [controller]
  (swap! controller assoc :pause-before-next-llm-turn? true))

(defn- fresh-turn-gate!
  "Replace the controller's turn-gate with a fresh unrealized promise so the
   next turn-pause cycle has something to park on."
  [controller]
  (swap! controller assoc :turn-gate (promise)))

(defn await-turn-release!
  "Called by the LLM worker at a turn boundary. If armed, blocks the worker on
   the turn-gate until `turn-next!` or `continue!` releases it. Returns the
   release value (`:next` / `:continue`) or nil when not armed (no park).

   The arm is cleared on entry (one-shot); `turn-next!` re-arms so the next turn
   pauses again, while `continue!` leaves it cleared so the worker runs free."
  [controller]
  (when (turn-armed? controller)
    (let [gate (:turn-gate @controller)]
      ;; one-shot: clear the arm BEFORE parking so a concurrent `turn-next!`
      ;; that re-arms takes effect on the FOLLOWING turn, not this one.
      (swap! controller assoc :pause-before-next-llm-turn? false)
      @gate)))

(defn turn-next!
  "Release the parked worker for exactly ONE turn, then re-arm so it pauses
   again before the turn after. Advances `:turn-index` by one."
  [controller]
  (let [{:keys [turn-gate]} @controller]
    (swap! controller assoc
      :pause-before-next-llm-turn? true
      :turn-index (inc (or (:turn-index @controller) 0)))
    (when (and turn-gate (not (realized? turn-gate)))
      (deliver turn-gate :next))
    (fresh-turn-gate! controller)))

(defn turn-back!
  "Move the turn pointer to the prior captured turn (clamped at 0). This is a
   pure pointer move — re-issuing from an earlier turn is the override/replay
   path (tasks 003/004); there is NO event-level chart rewind here."
  [controller]
  (swap! controller update :turn-index (fn [n] (max 0 (dec (or n 0))))))

(defn turn-continue!
  "Clear the LLM-turn arm and release any parked worker so it resumes
   free-running (no further turn-level pauses). Distinct from the per-event
   `continue!`; a control surface that wants \"resume everything\" calls both."
  [controller]
  (let [{:keys [turn-gate]} @controller]
    (swap! controller assoc :pause-before-next-llm-turn? false)
    (when (and turn-gate (not (realized? turn-gate)))
      (deliver turn-gate :continue))
    (fresh-turn-gate! controller)))
