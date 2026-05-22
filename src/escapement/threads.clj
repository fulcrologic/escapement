(ns escapement.threads
  "Thread-creation seam. Returns *unstarted* threads so callers control start
   ordering (e.g. register-then-start to avoid races).

   By default — and always under Babashka/SCI — these are named platform daemon
   threads, identical to the previous inline `(Thread. runnable name)` usage.

   When running on a JVM started with `-Descapement.virtual-threads=true`, the
   threads are virtual (Loom). The `Thread/ofVirtual` interop lives inside a
   branch guarded by that system property, which is never set under bb, so SCI
   never evaluates it.")

(defn virtual-threads?
  "True only on a JVM explicitly opted in via -Descapement.virtual-threads=true.
   Always false under bb/SCI (the property is never set there)."
  []
  (= "true" (System/getProperty "escapement.virtual-threads")))

(defn unstarted-daemon
  "Return an UNSTARTED daemon thread named `name` running `^Runnable runnable`.
   Virtual when opted in (JVM only), else a platform daemon thread. Caller must
   call `.start`."
  ^Thread [^String name ^Runnable runnable]
  (if (virtual-threads?)
    (.unstarted (.name (Thread/ofVirtual) name) runnable)
    (doto (Thread. runnable name) (.setDaemon true))))
