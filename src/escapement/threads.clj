(ns escapement.threads
  "Thread-creation seam. Returns *unstarted* threads so callers control start
   ordering (e.g. register-then-start to avoid races).

   Threads are VIRTUAL (Loom) when the host runtime supports `Thread/ofVirtual`
   (Java 21+, which includes recent Babashka/GraalVM builds) and virtual threads
   have not been explicitly disabled; otherwise they are named platform daemon
   threads, identical to the previous inline
   `(doto (Thread. runnable name) (.setDaemon true))`.

   Whether to use virtual threads is resolved in this order:
     1. a programmatic override set via `set-virtual-threads!` (true/false),
     2. the `escapement.virtual-threads` system property (\"true\"/\"false\"),
     3. AUTO: virtual when the runtime supports them, platform otherwise.

   The auto default lifts the platform thread-per-session ceiling on capable
   hosts (see issue #11) without any flag. It is safe across the host matrix
   because `unstarted-daemon` falls back to a platform daemon whenever virtual
   construction is unavailable or fails — so an embedder on an old (pre-Java-21)
   JVM keeps the previous platform-thread behavior and never crashes.

   Library embedders who cannot set the `-Descapement.virtual-threads` JVM flag
   can force the choice programmatically via `set-virtual-threads!`.")

(defonce ^{:doc "Cached runtime capability probe: realizes to true when this
   host can construct a virtual thread via `Thread/ofVirtual`, false otherwise.
   Wrapped in a delay so the probe runs at most once, on first use."}
  virtual-threads-available?
  (delay
    (try
      (.unstarted (.name (Thread/ofVirtual) "escapement-vt-probe")
                  ^Runnable (fn [] nil))
      true
      (catch Throwable _ false))))

(defonce ^{:doc   "Programmatic override of virtual-thread usage. nil = auto
   (resolve via system property / runtime probe); true/false = force on/off."
           :private true}
  override
  (atom nil))

(defn set-virtual-threads!
  "Programmatically force virtual-thread usage on (`true`), off (`false`), or
   back to auto-detect (`nil`). Intended for library embedders who cannot set
   the `-Descapement.virtual-threads` JVM flag. Returns the new override value."
  [on?]
  (reset! override on?))

(defn virtual-threads?
  "True when new worker threads should be virtual. Resolution order: programmatic
   override > `escapement.virtual-threads` system property > runtime auto-detect
   (see ns docstring). A `true` result still degrades gracefully to a platform
   daemon in `unstarted-daemon` if virtual construction fails."
  []
  (let [o @override]
    (if (some? o)
      (boolean o)
      (case (System/getProperty "escapement.virtual-threads")
        "true"  true
        "false" false
        @virtual-threads-available?))))

(defn unstarted-daemon
  "Return an UNSTARTED thread named `name` running `^Runnable runnable`.
   Virtual when `virtual-threads?` is true and the runtime can build one;
   otherwise (or on any failure) a platform daemon thread. Caller must call
   `.start`."
  ^Thread [^String name ^Runnable runnable]
  (or (when (virtual-threads?)
        (try
          (.unstarted (.name (Thread/ofVirtual) name) runnable)
          (catch Throwable _ nil)))
      (doto (Thread. runnable name) (.setDaemon true))))
