(ns escapement.threads-test
  (:require
    [escapement.threads :as threads]
    [fulcro-spec.core :refer [=> assertions specification component]]))

(def ^:private prop "escapement.virtual-threads")

(defn- with-clean-state
  "Run `f` with the programmatic override cleared and the system property
   removed, restoring both afterwards so tests don't leak into each other."
  [f]
  (let [prior-prop (System/getProperty prop)]
    (threads/set-virtual-threads! nil)
    (System/clearProperty prop)
    (try
      (f)
      (finally
        (threads/set-virtual-threads! nil)
        (if prior-prop
          (System/setProperty prop prior-prop)
          (System/clearProperty prop))))))

(specification "virtual-threads? resolution precedence"
  (with-clean-state
    (fn []
      (component "programmatic override wins over the system property"
        (System/setProperty prop "false")
        (threads/set-virtual-threads! true)
        (assertions
          "override true beats property false" (threads/virtual-threads?) => true)
        (threads/set-virtual-threads! false)
        (System/setProperty prop "true")
        (assertions
          "override false beats property true" (threads/virtual-threads?) => false))

      (component "system property is honored when no override is set"
        (threads/set-virtual-threads! nil)
        (System/setProperty prop "true")
        (assertions
          "property \"true\" => virtual" (threads/virtual-threads?) => true)
        (System/setProperty prop "false")
        (assertions
          "property \"false\" => platform" (threads/virtual-threads?) => false))

      (component "falls back to runtime auto-detect when nothing is set"
        (threads/set-virtual-threads! nil)
        (System/clearProperty prop)
        (assertions
          "auto matches the cached capability probe"
          (threads/virtual-threads?) => @threads/virtual-threads-available?))

      (component "an unrecognized property value falls through to auto-detect"
        (System/setProperty prop "maybe")
        (assertions
          "non true/false value => auto"
          (threads/virtual-threads?) => @threads/virtual-threads-available?)))))

(specification "set-virtual-threads! return value"
  (with-clean-state
    (fn []
      (assertions
        "returns the new override value"
        (threads/set-virtual-threads! true) => true
        (threads/set-virtual-threads! false) => false
        (threads/set-virtual-threads! nil) => nil))))

(specification "unstarted-daemon"
  (with-clean-state
    (fn []
      (component "forced off => named, unstarted platform daemon thread"
        (threads/set-virtual-threads! false)
        (let [t (threads/unstarted-daemon "esc-test-platform" (fn [] nil))]
          (assertions
            "named as requested" (.getName t) => "esc-test-platform"
            "is a daemon"        (.isDaemon t) => true
            "is not virtual"     (.isVirtual t) => false
            "is unstarted"       (.isAlive t) => false)))

      (component "forced on => named, unstarted virtual thread (on capable hosts)"
        (threads/set-virtual-threads! true)
        (let [t (threads/unstarted-daemon "esc-test-virtual" (fn [] nil))]
          (assertions
            "named as requested" (.getName t) => "esc-test-virtual"
            "is unstarted"       (.isAlive t) => false
            "virtualness matches the host capability probe"
            (.isVirtual t) => @threads/virtual-threads-available?)))

      (component "the returned thread actually runs when started"
        (threads/set-virtual-threads! false)
        (let [ran (promise)
              t   (threads/unstarted-daemon "esc-test-run" (fn [] (deliver ran :ok)))]
          (.start t)
          (assertions
            "runnable executed on the worker thread"
            (deref ran 2000 :timeout) => :ok))))))
