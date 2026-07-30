#!/usr/bin/env bb
;; Live smoke test for the openai-codex backend (ChatGPT subscription via OAuth).
;;
;; PREREQUISITES:
;;   Run `escapement login codex` once first (saves ~/.escapement/openai-auth.json).
;;
;; USAGE:
;;   bb bb_test/codex_live_smoke.clj
;;   CODEX_SMOKE_MODEL=gpt-5.2-codex bb bb_test/codex_live_smoke.clj
;;
;; Two checks against the codex backend:
;;   1. a bare `send-turn` (does the wire contract hold end to end?)
;;   2. the `hello` chart, driven through the real CLI front-end.
;;
;; Exits 0 on success, 1 on failure.

(require '[babashka.classpath :refer [add-classpath]])
(add-classpath "src:demos")

(require
  '[babashka.process :as bp]
  '[clojure.java.io :as io]
  '[clojure.string :as str]
  '[com.fulcrologic.statecharts.promise :as p]
  '[escapement.llm.openai-codex :as codex]
  '[escapement.llm.protocol :as proto]
  '[escapement.llm.types :as types])

(def failures (atom []))

(defn check! [label ok? detail]
  (if ok?
    (println (str "  PASS  " label))
    (do (println (str "  FAIL  " label " — " detail))
        (swap! failures conj label))))

(def model
  "Default deliberately NOT the codex backend's own `:default-model`
   (`gpt-5.1-codex`). Probed 2026-07-29 against a ChatGPT-account token, every
   model `Guide.adoc` documents for this backend now fails with
   `\"The '<id>' model is not supported when using Codex with a ChatGPT account.\"`
   — `gpt-5.1-codex`, `gpt-5.2-codex`, `gpt-5.1-codex-mini`, `gpt-5.1`. Since
   ChatGPT-account auth is the ONLY mode this backend has, that made the
   documented default unusable. `gpt-5.6-sol` (the GPT-5.6 flagship) is verified
   working; `bb_test/codex_models_probe.clj` re-verifies the whole set."
  (or (System/getenv "CODEX_SMOKE_MODEL") "gpt-5.6-sol"))
(def backend (codex/new-backend {:default-model model}))

;;; ---------------------------------------------------------------------------
;;; 1. Bare send-turn

(println (str "[1/2] bare send-turn against " model))
(try
  (let [resp (p/await!
               (proto/send-turn backend
                 {:model    model
                  :system   "You are a terse assistant. Answer with a single word."
                  :messages [{:role :user :content [{:type :text :text "What is 2+2? One word."}]}]}))]
    (check! "response is schema-valid" (nil? (types/validate-response resp))
      (pr-str (types/validate-response resp)))
    (check! "content is non-empty" (seq (:content resp)) "empty content")
    (check! "answer conveys 4 (as a digit or the word)"
      (let [txt (str/lower-case (str/join " " (mapv :text (:content resp))))]
        (or (str/includes? txt "4") (str/includes? txt "four")))
      (pr-str (:content resp)))
    (println "   usage:" (pr-str (:usage resp))))
  (catch Throwable t
    (check! "bare send-turn did not throw" false
      (str (proto/error-category t) ": " (ex-message t)))))

;;; ---------------------------------------------------------------------------
;;; 2. The hello chart, end to end
;;;
;;; Driven as a SUBPROCESS rather than an in-process `runner/run!`: the engine's
;;; `:llm-conversation` invocation processor is wired up by `escapement.cli`, not
;;; by `runner/run!` alone, so a hand-rolled harness dies with "Cannot start
;;; invocation. No processor for :llm-conversation". (This script previously did
;;; exactly that, and additionally named a `hello-chart` var that does not
;;; exist — the example exposes `agent`.)
;;;
;;; It runs in a throwaway directory carrying its own `.escapement.edn`, because
;;; `:llm/preferences` decides the model for a node that pins none; inheriting
;;; the ambient repo config would make the chart request whatever that prefers.

(println "\n[2/2] hello chart end-to-end, through the real CLI front-end")
(try
  (let [repo (System/getProperty "user.dir")
        dir  (io/file (System/getProperty "java.io.tmpdir")
               (str "escapement-codex-smoke-" (random-uuid)))]
    (.mkdirs dir)
    (spit (io/file dir ".escapement.edn")
      (pr-str {:llm/credentials [{:provider :codex}]
               :llm/aliases     {:codex-smoke [{:provider :codex :model model}]}
               :llm/preferences [:codex-smoke]}))
    (let [{:keys [exit out err]}
          (bp/sh ["bb" "--config" (str (io/file repo "bb.edn"))
                  "-m" "escapement.cli" "run" "escapement.examples.hello/agent"
                  "--log-level" "warn"]
            {:dir (str dir) :continue true})
          combined (str out "\n" err)]
      (check! "the CLI run exited 0" (zero? exit) (str "exit " exit "\n" combined))
      (check! "the chart reached :finished"
        (str/includes? combined "finished")
        (str "final-config line not :finished — " (str/trim combined)))
      (let [tx (->> (file-seq dir)
                 (filterv #(= "transcript.jsonl" (.getName %)))
                 first)]
        (check! "a transcript was written" (some? tx) "no transcript.jsonl found")
        (when tx
          (let [txt (slurp tx)]
            (check! "escapement executed the event tool itself"
              (str/includes? txt "llm/tool-result")
              "no llm/tool-result event in the transcript")
            (check! "the response carried a tool_use block"
              (str/includes? txt "tool_use")
              "no tool_use block in the transcript"))))
      (println "   dir:" (str dir))))
  (catch Throwable t
    (check! "hello chart did not throw" false (ex-message t))))

;;; ---------------------------------------------------------------------------

(println)
(if (empty? @failures)
  (do (println "PASS: all live codex checks green") (System/exit 0))
  (do (println (str "FAIL: " (count @failures) " check(s) failed: " (pr-str @failures)))
      (System/exit 1)))
