#!/usr/bin/env bb
;; Stand-in for the `claude` binary, driven entirely by env vars so
;; `escapement.llm.claude-cli` can be exercised as a REAL subprocess without a
;; network call or a Claude Code install. `bb test` never shells the real CLI.
;;
;;   FAKE_CLAUDE_FIXTURE       path to a .jsonl fixture to replay on stdout
;;   FAKE_CLAUDE_EXIT          exit code (default 0)
;;   FAKE_CLAUDE_SLEEP_MS      sleep this long BEFORE writing anything
;;   FAKE_CLAUDE_STDERR_BYTES  write N bytes to stderr (the 64 KB pipe-deadlock probe)
;;   FAKE_CLAUDE_RECORD_TO     write {:argv :env :stdin} EDN here for assertions
;;   FAKE_CLAUDE_SPAWN_CHILD   spawn a `sleep <value>` grandchild (destroy-tree probe)
;;   FAKE_CLAUDE_TIMING_DIR    write {:start ms :end ms} EDN per invocation (concurrency probe)
;;   FAKE_CLAUDE_NO_STDIN      do not drain stdin
(require '[babashka.process :as bp] '[clojure.java.io :as io])

(def env (into {} (System/getenv)))
(defn- ev [k] (get env k))

(when-let [n (ev "FAKE_CLAUDE_STDERR_BYTES")]
  ;; Written BEFORE stdout so a caller that does not drain stderr deadlocks here.
  (binding [*out* *err*]
    (print (apply str (repeat (parse-long n) \x)))
    (flush)))

(when-let [marker (ev "FAKE_CLAUDE_SPAWN_CHILD")]
  ;; A grandchild with a UNIQUE sleep duration, so the test can pgrep for
  ;; exactly this one and prove destroy-tree reaped it.
  (bp/process ["sleep" marker] {:out :inherit :err :inherit}))

(def started-ms (System/currentTimeMillis))

(def stdin-text
  (if (ev "FAKE_CLAUDE_NO_STDIN") "" (slurp *in*)))

(when-let [p (ev "FAKE_CLAUDE_RECORD_TO")]
  (spit p (pr-str {:argv  (vec *command-line-args*)
                   :env   env
                   :stdin stdin-text})))

(when-let [ms (ev "FAKE_CLAUDE_SLEEP_MS")]
  (Thread/sleep (parse-long ms)))

(when-let [f (ev "FAKE_CLAUDE_FIXTURE")]
  (with-open [r (io/reader f)]
    (doseq [line (line-seq r)]
      (println line)
      (flush))))

(when-let [d (ev "FAKE_CLAUDE_TIMING_DIR")]
  (.mkdirs (io/file d))
  (spit (io/file d (str (random-uuid) ".edn"))
        (pr-str {:start started-ms :end (System/currentTimeMillis)})))

(System/exit (parse-long (or (ev "FAKE_CLAUDE_EXIT") "0")))
