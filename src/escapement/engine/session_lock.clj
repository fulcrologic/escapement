(ns escapement.engine.session-lock
  "Single-writer lock for a session's durable state.

  WHY THIS EXISTS
  ---------------
  Two Escapement runner processes pointed at the same session used to corrupt it
  SILENTLY:

    * `escapement.engine.store/FileBackedStore` is last-write-wins with a
      per-process in-memory cache, so two writers clobber each other's
      checkpoints with no error and no way to tell afterwards.
    * `escapement.transcript` is single-writer PER PROCESS only: each process
      seeds its `:seq` counter from `last-recorded-seq` at open time, so two
      processes emit colliding sequence numbers into the same JSONL timeline.

  Both failures produce a plausible-looking but wrong session. This namespace
  turns them into an immediate, actionable error.

  MECHANISM — OS ADVISORY LOCK, NOT A PID FILE
  --------------------------------------------
  We take an exclusive `java.nio.channels.FileLock` on a per-session lock file
  via `FileChannel/tryLock`. This is an OS-level advisory lock, which is the
  whole point: **the kernel drops it when the holding process dies**, including
  `kill -9`, a segfault, or an OOM kill. A stale lock is therefore impossible,
  so a crashed run never locks the user out of their own session and never
  requires a manual `rm`. Given that surviving `kill -9` IS Escapement's value
  proposition, a lock that outlives a crash would be worse than no lock at all.

  Verified working under Babashka/SCI (bb's native image exposes
  `FileChannel`, `FileLock` acquisition, and `ProcessHandle/current`). One SCI
  caveat drove the implementation: **`FileLock.release` is NOT on SCI's allowed
  method list** (`Method release on class sun.nio.ch.FileLockImpl not allowed!`),
  so we release by CLOSING THE CHANNEL, which the JDK specifies as releasing any
  lock held on it. `java.nio.channels.OverlappingFileLockException` is likewise
  not resolvable as a class name under SCI, so the same-JVM overlap case is
  caught as a plain `Exception` (see `acquire!`).

  The lock file's CONTENT is diagnostics only — never the lock itself. We write
  the holder's pid and start time so the error message can name the offending
  process; correctness never depends on that content being present, fresh, or
  even readable.

  GRANULARITY — (checkpoint-dir, session-id)
  ------------------------------------------
  The lock file is `<checkpoint-dir>/<session-slug>.lock`. That pair is exactly
  the identity of the durable state being written: the checkpoint file is
  `<checkpoint-dir>/<session-slug>.edn`, and the CLI derives BOTH the checkpoint
  dir and the transcript path from the same session dir, so one lock covers both
  writers. Deliberately NOT locked at coarser or finer grain:

    * Not the whole checkpoint dir — running two DIFFERENT sessions that happen
      to share a checkpoint dir is legitimate and must keep working.
    * Not a read-write lock — readers (`escapement.storage.disk-read`, the
      web-UI resolvers, `escapement open`) take NO lock at all. They are
      read-only; blocking them would make the live debugger useless.

  WHO TAKES IT
  ------------
  Only `escapement.runner/run!`, once, for the session it pumps. That placement
  keeps every legitimate concurrency case working:

    * Multiplex / child sessions (`:multi-session?`) are spawned by the
      invocation processor inside ONE `run!` in ONE process; they never call
      `run!` themselves, so they take no lock and cannot deadlock the parent.
    * `--api-server` runs in the SAME process as the runner and never acquires
      a lock, so it cannot self-deadlock.
    * The replay/fork path (`escapement.replay.chart`) runs into a NEW fork
      session id under a NEW fork checkpoint dir, so it never contends with the
      source session it forked from.
    * Tests create many sessions in per-test tmp dirs — distinct lock files,
      no contention."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn ?]])
  (:import
    (java.nio ByteBuffer)
    (java.nio.channels FileChannel)
    (java.nio.file Paths StandardOpenOption)))

(defn- session-slug
  "Filesystem-safe name for `session-id`, matching `escapement.engine.store`'s slug so the lock file
   sits beside the checkpoint file it guards (`<slug>.lock` next to `<slug>.edn`)."
  [session-id]
  (if (keyword? session-id) (subs (str session-id) 1) (str session-id)))

(>defn lock-file
  "The lock file guarding `session-id`'s durable state under `checkpoint-dir`."
  [checkpoint-dir session-id]
  [:string [:or :keyword :string :uuid :symbol] => any?]
  (io/file checkpoint-dir (str (session-slug session-id) ".lock")))

(defn- current-pid
  "This process's OS pid, or nil if unavailable. Diagnostics only."
  []
  (try (.pid (java.lang.ProcessHandle/current)) (catch Throwable _ nil)))

(defn- read-holder-info
  "Best-effort read of the diagnostics blob a holder wrote into `file`. Returns a map or nil.
   NEVER used for locking decisions — the kernel lock is the sole authority; this only enriches
   the error message. Any failure (missing, empty, torn, garbage) yields nil."
  [file]
  (try
    (let [s (slurp file)]
      (when (seq (.trim ^String s))
        (let [m (edn/read-string s)]
          (when (map? m) m))))
    (catch Throwable _ nil)))

(defn- ^java.nio.file.Path as-path [f] (.toPath (io/file f)))

(>defn acquire!
  "Take the exclusive single-writer lock for `session-id`'s durable state under `checkpoint-dir`.

   Returns an opaque handle to pass to `release!`, or nil when `:lock?` is false.

   Throws `ex-info` (`:escapement/session-locked` in the data) when another process — or another
   `run!` inside THIS process — already holds it. The message names the session, the checkpoint dir,
   and (when the diagnostics blob is readable) the holding pid.

   `opts`:
     * `:lock?` (default true) — set false to skip locking entirely. The escape hatch exists for
       hosts that already guarantee single-writer access by other means (e.g. an embedder holding
       its own external lease, or a deliberate forensic read-modify session). It defaults to ON;
       turning it off restores the old silent-corruption failure mode, so it is documented as
       advanced-use-only."
  ([checkpoint-dir session-id]
   [:string [:or :keyword :string :uuid :symbol] => [:maybe :map]]
   (acquire! checkpoint-dir session-id {}))
  ([checkpoint-dir session-id {:keys [lock?] :or {lock? true}}]
   [:string [:or :keyword :string :uuid :symbol] :map => [:maybe :map]]
   (when lock?
     (let [f (lock-file checkpoint-dir session-id)]
       (io/make-parents f)
       (let [channel (FileChannel/open (as-path f)
                       (into-array StandardOpenOption [StandardOpenOption/CREATE
                                                       StandardOpenOption/WRITE]))
             ;; `tryLock` returns nil when ANOTHER process holds the lock, but THROWS
             ;; OverlappingFileLockException when this same JVM already holds it (JVM file locks are
             ;; per-JVM, not per-channel). That class is not resolvable under SCI, so we catch
             ;; Exception and treat "no lock" and "overlap" identically — both mean "someone else is
             ;; already writing this session", which is exactly what we want to report.
             lock    (try
                       (.tryLock channel)
                       (catch Exception _ nil))]
         (if lock
           (do
             ;; Diagnostics for whoever collides with us next. Written INSIDE the lock, truncating
             ;; any previous holder's stale blob. Failure here is non-fatal: we hold the real lock.
             (try
               (.truncate channel 0)
               (.write channel (ByteBuffer/wrap
                                 (.getBytes (pr-str {:pid        (current-pid)
                                                     :session-id (str session-id)
                                                     :acquired-at (str (java.time.Instant/now))})
                                   "UTF-8")))
               (.force channel true)
               (catch Throwable _ nil))
             {:channel channel :file f :session-id session-id})
           (let [_      (try (.close channel) (catch Throwable _ nil))
                 holder (read-holder-info f)]
             (throw (ex-info
                      (str "Session " session-id " is already locked by another Escapement process"
                        (when-let [pid (:pid holder)] (str " (pid " pid ")"))
                        ". Only one process may WRITE a session at a time — a second writer would "
                        "silently clobber checkpoints and corrupt the transcript ordering.\n"
                        "  checkpoint dir: " checkpoint-dir "\n"
                        "  lock file:      " (.getPath f) "\n"
                        "If you are sure no other run is active, the lock is held by a live process "
                        "(the OS releases it automatically when a process dies, so it is never "
                        "stale) — find it with: ps -p " (or (:pid holder) "<pid>"))
                      (cond-> {:escapement/session-locked true
                               :session-id                (str session-id)
                               :checkpoint-dir            checkpoint-dir
                               :lock-file                 (.getPath f)}
                        holder (assoc :holder holder)))))))))))

(>defn release!
  "Release a lock `handle` from `acquire!`. Safe on nil (the `:lock? false` case) and safe to call
   twice. Releasing = CLOSING the channel: `FileLock.release` is blocked under SCI, and closing a
   channel releases any lock held on it (the kernel also does this for us on process death, which is
   why a crash can never leave a stale lock).

   The lock FILE is intentionally left on disk. Deleting it would be a race (another process may
   already have opened that inode and be locking through it), and an unlocked leftover file is inert."
  [handle]
  [(? :map) => :nil]
  (when-let [ch (:channel handle)]
    (try (.close ^FileChannel ch) (catch Throwable _ nil)))
  nil)
