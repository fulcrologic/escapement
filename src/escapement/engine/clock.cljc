(ns escapement.engine.clock
  "The time source a run reads: when a delayed event (`send` with a delay, a retry backoff, a
   \"wait two days\" timer) is due, and how the runner waits for it.

   The event queue (`escapement.engine.queue`) takes a clock: it stamps each delayed send's due
   time from `now-ms` and asks `now-ms` which events are due. The runner waits through `idle!`.
   With no clock the wall clock is used, exactly as before.

   Two implementations ship:

     * `wall-clock`   — `System/currentTimeMillis`, and `idle!` really sleeps.
     * `manual-clock` — a test clock whose time only moves when the test says so (`advance!`,
                        `set-ms!`), or, with `:auto-advance? true`, jumps straight to the next due
                        time whenever the runner would otherwise wait for it. A chart that waits two
                        days is then tested in milliseconds, with delayed events still delivered in
                        due-time order.

   Any host may implement `Clock` itself."
  (:require
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn ?]]))

(defprotocol Clock
  (now-ms [clock]
    "Current time on this clock, in epoch milliseconds.")
  (idle! [clock wake-at-ms max-wait-ms]
    "Wait for time to pass, returning when this clock reaches `wake-at-ms` (epoch ms on this clock;
     nil = no due time) or after at most `max-wait-ms` of real time, whichever comes first. A
     virtual clock may move its own time forward here instead of waiting. Returns nil."))

(defn- real-sleep! [ms]
  #?(:clj  (when (pos? ms) (Thread/sleep (long ms)))
     :cljs nil))

(defrecord WallClock []
  Clock
  (now-ms [_]
    #?(:clj  (System/currentTimeMillis)
       :cljs (.getTime (js/Date.))))
  (idle! [this wake-at-ms max-wait-ms]
    (let [until-wake (when wake-at-ms (- wake-at-ms (now-ms this)))]
      (real-sleep! (if until-wake (max 0 (min max-wait-ms until-wake)) max-wait-ms))
      nil)))

(def ^:private the-wall-clock (->WallClock))

(>defn wall-clock
  "The real clock (the default everywhere)."
  []
  [=> any?]
  the-wall-clock)

(defrecord ManualClock [now auto-advance?]
  Clock
  (now-ms [_] @now)
  (idle! [_ wake-at-ms max-wait-ms]
    (if (and auto-advance? wake-at-ms)
      ;; Jump to the due time (never backwards). Real time does not pass.
      (swap! now max wake-at-ms)
      ;; Time moves only when the test moves it; give other threads (a test advancing the
      ;; clock, an invocation finishing) real time to act.
      (real-sleep! max-wait-ms))
    nil))

(>defn manual-clock
  "A test clock starting at `:start-ms` (default 0). Time moves only through `advance!` / `set-ms!`,
   or, when `:auto-advance?` is true, by jumping to the next due time whenever the runner would wait
   for a delayed event. Thread-safe."
  ([] [=> any?] (manual-clock {}))
  ([{:keys [start-ms auto-advance?] :or {start-ms 0}}]
   [[:map [:start-ms {:optional true} :int] [:auto-advance? {:optional true} :boolean]] => any?]
   (->ManualClock (atom (long start-ms)) (boolean auto-advance?))))

(>defn advance!
  "Move a `manual-clock` forward by `ms`. Returns the new time."
  [clock ms]
  [any? :int => :int]
  (swap! (:now clock) + ms))

(>defn set-ms!
  "Set a `manual-clock` to `ms` (epoch ms). Returns the new time."
  [clock ms]
  [any? :int => :int]
  (reset! (:now clock) ms))

(>defn auto-advance?
  "True when `clock` is a manual clock that jumps to due times on its own."
  [clock]
  [any? => :boolean]
  (boolean (and (instance? ManualClock clock) (:auto-advance? clock))))

(>defn clock-or-wall
  "`clock`, or the wall clock when nil."
  [clock]
  [(? any?) => any?]
  (or clock the-wall-clock))
