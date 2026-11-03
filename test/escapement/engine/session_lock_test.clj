(ns escapement.engine.session-lock-test
  (:require
    [babashka.process :as process]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final]]
    [escapement.runner :as runner]
    [escapement.engine.session-lock :as lock]
    [fulcro-spec.core :refer [=> assertions component specification]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir []
  (str (Files/createTempDirectory "session-lock-test" (into-array FileAttribute []))))

(defn- locked-error
  "Run `f`, returning the ex-data of the session-locked error it throws, or `:no-throw`."
  [f]
  (try (f) :no-throw (catch Exception e (or (ex-data e) {:message (.getMessage e)}))))

(specification "session-lock/acquire!"
  (component "acquires, and a second acquisition of the same session fails clearly"
    (let [dir (tmp-dir)
          h   (lock/acquire! dir :s1)]
      (try
        (let [err (locked-error #(lock/acquire! dir :s1))]
          (assertions
            "first acquisition returns a handle"
            (some? h) => true
            "lock file exists beside the checkpoint file"
            (.exists (lock/lock-file dir :s1)) => true
            "second acquisition throws (not :no-throw)"
            (= :no-throw err) => false
            "the error is flagged as a session lock conflict"
            (:escapement/session-locked err) => true
            "and names the session, the checkpoint dir and the lock file"
            (:session-id err) => ":s1"
            (:checkpoint-dir err) => dir
            (str/ends-with? (str (:lock-file err)) "s1.lock") => true))
        (finally (lock/release! h)))))

  (component "releasing allows re-acquisition"
    (let [dir (tmp-dir)
          h1  (lock/acquire! dir :s1)]
      (lock/release! h1)
      (let [h2 (lock/acquire! dir :s1)]
        (assertions
          "the lock can be taken again after release"
          (some? h2) => true)
        (lock/release! h2)
        (assertions
          "release is idempotent"
          (do (lock/release! h2) (lock/release! nil) :ok) => :ok))))

  (component "different sessions and different dirs do not contend"
    (let [dir-a (tmp-dir)
          dir-b (tmp-dir)
          a1    (lock/acquire! dir-a :s1)
          a2    (lock/acquire! dir-a :s2)                    ; same dir, other session
          b1    (lock/acquire! dir-b :s1)]                   ; same session id, other dir
      (assertions
        "a second session in the same checkpoint dir locks independently"
        (some? a2) => true
        "the same session id under a different checkpoint dir locks independently"
        (some? b1) => true)
      (lock/release! a1) (lock/release! a2) (lock/release! b1)))

  (component ":lock? false is an opt-out that takes no lock"
    (let [dir (tmp-dir)
          h   (lock/acquire! dir :s1 {:lock? false})]
      (assertions
        "returns nil rather than a handle"
        h => nil
        "no lock file is created"
        (.exists (lock/lock-file dir :s1)) => false
        "and a real lock can still be taken (nothing was held)"
        (some? (doto (lock/acquire! dir :s1) (lock/release!))) => true))))

;; ---------------------------------------------------------------------------
;; CRASH RELEASE — exercised across a REAL subprocess, killed with SIGKILL.
;; ---------------------------------------------------------------------------
;; This is the property the whole design rests on: an OS advisory lock is dropped
;; by the KERNEL when the holder dies, so `kill -9` can never leave a session
;; permanently locked. Asserting that in-process would prove nothing (in-process
;; we always release explicitly), so we shell out to a real `bb` that takes the
;; lock and parks, SIGKILL it, and then prove this process can take the lock.

(def ^:private holder-script
  "(require '[escapement.engine.session-lock :as lock])
   (let [[dir sid] *command-line-args*]
     (lock/acquire! dir sid)
     (println \"HELD\")
     (flush)
     (Thread/sleep 600000))")

(specification "session-lock survives a SIGKILLed holder (no stale lock)"
  (let [dir     (tmp-dir)
        script  (io/file dir "holder.clj")
        _       (spit script holder-script)
        ;; Plain `bb <script>` from the repo root, so the child picks up bb.edn's
        ;; :paths/:deps and can require the same namespace this test does.
        proc    (process/process ["bb" (.getPath script) dir "crashy"]
                  {:out :stream :err :inherit})
        rdr     (io/reader (:out proc))
        ;; Blocks until the child reports it holds the lock (or its stdout closes).
        held?   (= "HELD" (.readLine rdr))
        blocked (when held? (locked-error #(lock/acquire! dir "crashy")))]
    (assertions
      "the subprocess acquired the lock"
      held? => true
      "while it is alive, this process cannot take the same session's lock"
      (:escapement/session-locked blocked) => true)
    ;; SIGKILL — no chance to run any cleanup, no shutdown hook, no finally.
    (.destroyForcibly ^Process (:proc proc))
    @proc
    (let [h (loop [tries 100]
              (let [r (try (lock/acquire! dir "crashy") (catch Exception _ nil))]
                (cond
                  r r
                  (pos? tries) (do (Thread/sleep 25) (recur (dec tries)))
                  :else nil)))]
      (assertions
        "after the holder is killed the kernel has released the lock — it is NOT stale"
        (some? h) => true)
      (lock/release! h))))

;; ---------------------------------------------------------------------------
;; RUNNER INTEGRATION
;; ---------------------------------------------------------------------------

(def ^:private trivial-chart
  (chart/statechart {:initial :done}
    (final {:id :done})))

(defn- run-opts [dir]
  {:chart           trivial-chart
   :session-id      :locked-session
   :transcript-path (str dir "/transcript.jsonl")
   :checkpoint-dir  dir})

(specification "runner/run! takes the session lock"
  (component "a run refuses to start while the session is locked"
    (let [dir (tmp-dir)
          h   (lock/acquire! dir :locked-session)
          err (locked-error #(runner/run! (run-opts dir)))]
      (lock/release! h)
      (assertions
        "run! throws the session-locked error rather than clobbering the session"
        (:escapement/session-locked err) => true)))

  (component "the lock is released when the run finishes, so a later run succeeds"
    (let [dir (tmp-dir)
          r1  (runner/run! (run-opts dir))
          r2  (runner/run! (assoc (run-opts dir) :resume? true))]
      (assertions
        "first run completes"
        (:status r1) => :done
        "a second, sequential run of the same session is not blocked"
        (:status r2) => :done
        "and the lock is free afterwards"
        (some? (doto (lock/acquire! dir :locked-session) (lock/release!))) => true)))

  (component ":lock? false skips the lock entirely"
    (let [dir (tmp-dir)
          h   (lock/acquire! dir :locked-session)
          r   (runner/run! (assoc (run-opts dir) :lock? false))]
      (lock/release! h)
      (assertions
        "the run proceeds even though the session is locked (advanced opt-out)"
        (:status r) => :done))))
