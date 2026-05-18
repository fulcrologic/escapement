#!/usr/bin/env bb
;; Live smoke test for the openai-codex backend.
;;
;; PREREQUISITES:
;;   Run `escapement login codex` once first (saves ~/.escapement/openai-auth.json).
;;
;; USAGE:
;;   bb bb_test/codex_live_smoke.clj
;;
;; Runs the `hello` chart against the codex backend.
;; Prints the session id and expects the chart to reach :finished
;; with a non-empty greeting in the data model.
;; Exits 0 on success, 1 on failure.

(require '[babashka.classpath :refer [add-classpath]])
(add-classpath "src:demos")

(require '[escapement.llm.openai-codex :as codex])
(require '[escapement.runner :as runner])

(def backend (codex/new-backend {:default-model "gpt-5.1-codex"}))

(println "Running hello chart against openai-codex backend...")

(let [session   (str (java.util.UUID/randomUUID))
      work-dir  "/tmp/escapement-codex-smoke"
      _         (.mkdirs (java.io.File. work-dir))
      [chart _] (runner/load-chart-with-meta 'escapement.examples.hello/hello-chart)
      summary   (try
                  (runner/run!
                   {:chart           chart
                    :session-id      (keyword "session" session)
                    :transcript-path (str work-dir "/" session "/transcript.jsonl")
                    :checkpoint-dir  (str work-dir "/" session "/checkpoints")
                    :session-dir     (str work-dir "/" session)
                    :backend         backend
                    :tool-registry   nil
                    :human-renderer  nil
                    :initial-data    {}
                    :resume?         false
                    :trace?          false})
                  (catch Throwable t
                    {:_error (.getMessage t)}))]
  (if (:_error summary)
    (do
      (println "FAIL:" (:_error summary))
      (System/exit 1))
    (let [config (:final-config summary)
          state  (:final-state summary)]
      (println "session:      " session)
      (println "final-state:  " state)
      (println "final-config: " config)
      (if (= :finished state)
        (do (println "PASS: chart reached :finished") (System/exit 0))
        (do (println "FAIL: expected :finished, got" state) (System/exit 1))))))
