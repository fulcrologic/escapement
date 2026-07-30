#!/usr/bin/env bb
;; Live smoke test for the :claude-cli backend (subscription-billed `claude -p`).
;;
;; OPT-IN. Gated on CLAUDE_AVAILABLE=1 and skips cleanly (exit 0) when unset.
;; The gate keeps the historical name AND its reason: a child `claude -p`
;; launched from a process tree that already has an interactive Claude Code
;; session can contend on a session lock, so this must never run unattended
;; inside `bb test`.
;;
;; PREREQUISITES:
;;   - `claude` on PATH, authenticated against a Max/Pro subscription
;;     (`claude auth login`, or `claude setup-token` for headless use).
;;
;; USAGE:
;;   CLAUDE_AVAILABLE=1 bb bb_test/claude_cli_live_smoke.clj
;;
;; Three checks, all with --model haiku and tiny prompts:
;;   1. a bare `send-turn` (does the wire contract hold end to end?)
;;   2. the `hello` chart (does the engine drive it?)
;;   3. a TWO-TURN loop with one real event tool — proving the agentic loop
;;      ADVANCES. This is the exact thing the deleted 2026-05 `claude-p`
;;      adapter could not do (it returned flat text, never tool_use blocks),
;;      and it is the reason this backend exists in this shape.
;;
;; Exits 0 on success (or skip), 1 on failure.

(require '[babashka.classpath :refer [add-classpath]])
(add-classpath "src:demos")

(require
  '[babashka.process :as bp]
  '[clojure.java.io :as io]
  '[clojure.string :as str]
  '[com.fulcrologic.statecharts.promise :as p]
  '[escapement.llm.claude-cli :as cc]
  '[escapement.llm.protocol :as proto]
  '[escapement.llm.types :as types])

(when-not (= "1" (System/getenv "CLAUDE_AVAILABLE"))
  (println "SKIP: set CLAUDE_AVAILABLE=1 to run the live claude-cli smoke test.")
  (System/exit 0))

(def failures (atom []))

(defn check! [label ok? detail]
  (if ok?
    (println (str "  PASS  " label))
    (do (println (str "  FAIL  " label " — " detail))
        (swap! failures conj label))))

(def backend (cc/new-backend {:default-model   "haiku"
                              :timeout-ms      300000
                              :max-concurrency 2}))

(when-let [v (cc/cli-version)]
  (println "claude CLI:" v))

;;; ---------------------------------------------------------------------------
;;; 1. Bare send-turn

(println "\n[1/3] bare send-turn")
(try
  (let [resp (p/await!
               (proto/send-turn backend
                 {:model    "haiku"
                  :system   "You are a terse assistant. Answer with a single word."
                  :messages [{:role :user :content [{:type :text :text "What is 2+2? One word."}]}]}))]
    (check! "response is schema-valid" (nil? (types/validate-response resp))
      (pr-str (types/validate-response resp)))
    (check! "stop-reason is :end_turn" (= :end_turn (:stop-reason resp)) (pr-str (:stop-reason resp)))
    (check! "content is non-empty" (seq (:content resp)) "empty content")
    (check! "answer conveys 4 (as a digit or the word)"
      (let [txt (str/lower-case (str/join " " (mapv :text (:content resp))))]
        (or (str/includes? txt "4") (str/includes? txt "four")))
      (pr-str (:content resp)))
    (check! "model reports the model that actually ran, not the alias"
      (str/starts-with? (str (:model resp)) "claude-") (pr-str (:model resp)))
    (check! "no thinking block leaked into the response (NN-9)"
      (empty? (filterv #(#{:thinking :redacted_thinking} (:type %)) (:content resp)))
      (pr-str (mapv :type (:content resp))))
    (check! "usage input-tokens is plausible, not the 2-4x aggregate (NN-6)"
      (let [n (get-in resp [:usage :input-tokens])] (and (pos? n) (< n 20000)))
      (pr-str (:usage resp)))
    (println "   usage:" (pr-str (:usage resp))))
  (catch Throwable t
    (check! "bare send-turn did not throw" false (str (proto/error-category t) ": " (ex-message t)))))

;;; ---------------------------------------------------------------------------
;;; 2. Tool call — does the model actually produce a tool_use block?

(println "\n[2/3] a single forced tool call")
(def echo-tool
  {:name         "record_answer"
   :description  "Record the final answer. Call this exactly once."
   :input-schema {"type"                 "object"
                  "additionalProperties" false
                  "properties"           {"answer" {"type"        "string"
                                                    "description" "The answer text."}}
                  "required"             ["answer"]}})

(try
  (let [resp (p/await!
               (proto/send-turn backend
                 {:model       "haiku"
                  :system      "You record answers using the provided tool."
                  :messages    [{:role    :user
                                 :content [{:type :text
                                            :text "The capital of France is Paris. Record that answer."}]}]
                  :tools       [echo-tool]
                  :tool-choice {:type :tool :name "record_answer"}}))
        tus  (filterv #(= :tool_use (:type %)) (:content resp))]
    (check! "response is schema-valid" (nil? (types/validate-response resp))
      (pr-str (types/validate-response resp)))
    (check! "a tool_use block came back — the envelope round-tripped"
      (seq tus) (pr-str (:content resp)))
    (check! "stop-reason is :tool_use" (= :tool_use (:stop-reason resp)) (pr-str (:stop-reason resp)))
    (check! "the tool name round-tripped byte-exact"
      (= "record_answer" (:name (first tus))) (pr-str (mapv :name tus)))
    (check! "the tool id is globally unique-looking (NN-10)"
      (str/starts-with? (str (:id (first tus))) "toolu_cc_") (pr-str (:id (first tus))))
    (check! "the input validates against the tool's own schema"
      (string? (get-in (first tus) [:input :answer])) (pr-str (:input (first tus))))
    (println "   tool call:" (pr-str (select-keys (first tus) [:name :input]))))
  (catch Throwable t
    (check! "forced tool call did not throw" false (str (proto/error-category t) ": " (ex-message t)))))

;;; ---------------------------------------------------------------------------
;;; 3. TWO-TURN loop — the regression the deleted adapter failed
;;;
;;; Turn 1 offers a tool and expects a tool_use. We then feed the tool_result
;;; back and expect turn 2 to make PROGRESS (a different, final answer rather
;;; than an identical repeat). This is exactly what "charts that depend on event
;;; tools never advanced" meant, so it is asserted directly.

(println "\n[3/3] two-turn loop with a real tool result")
(def lookup-tool
  {:name         "lookup_population"
   :description  "Look up the population of a city. Returns a number."
   :input-schema {"type"                 "object"
                  "additionalProperties" false
                  "properties"           {"city" {"type" "string"}}
                  "required"             ["city"]}})

(try
  (let [sys   "You answer questions by calling tools when one is available. Be terse."
        msg1  [{:role    :user
                :content [{:type :text :text "What is the population of Paris? Use the tool."}]}]
        resp1 (p/await! (proto/send-turn backend
                          {:model "haiku" :system sys :messages msg1 :tools [lookup-tool]}))
        tu    (first (filterv #(= :tool_use (:type %)) (:content resp1)))]
    (check! "turn 1 produced a tool_use block" (some? tu) (pr-str (:content resp1)))

    (when tu
      ;; Feed the result back exactly as the engine would.
      (let [msgs2 (into msg1
                    [{:role :assistant :content (:content resp1)}
                     {:role    :user
                      :content [{:type        :tool_result
                                 :tool_use_id (:id tu)
                                 :content     "2148000"}]}])
            resp2 (p/await! (proto/send-turn backend
                              {:model "haiku" :system sys :messages msgs2 :tools [lookup-tool]}))
            text2 (str/join " " (mapv :text (filterv #(= :text (:type %)) (:content resp2))))]
        (check! "turn 2 is schema-valid" (nil? (types/validate-response resp2))
          (pr-str (types/validate-response resp2)))
        (check! "THE LOOP ADVANCED: turn 2 used the tool result instead of
                 re-issuing the same call"
          (str/includes? text2 "2,148,000")
          (str "turn-2 content: " (pr-str (:content resp2))))
        (check! "turn 2 did not just repeat turn 1's tool call verbatim"
          (not= (:content resp1) (:content resp2)) "identical content")
        (println "   turn 2:" (pr-str text2)))))
  (catch Throwable t
    (check! "two-turn loop did not throw" false (str (proto/error-category t) ": " (ex-message t)))))

;;; ---------------------------------------------------------------------------
;;; 4. The hello chart, driven by the engine

(println "\n[bonus] hello chart end-to-end, through the real CLI front-end")
;; Driven as a SUBPROCESS rather than an in-process `runner/run!`: the engine's
;; `:llm-conversation` invocation processor is wired up by `escapement.cli`, not
;; by `runner/run!` alone, so a hand-rolled harness dies with "Cannot start
;; invocation. No processor for :llm-conversation".
;;
;; It runs in a throwaway directory carrying its own `.escapement.edn`, because
;; `:llm/preferences` — not `--model` — decides which model a node with no pinned
;; `:model` asks for. Inheriting THIS repo's config would make the chart request
;; codex model ids, which the `claude` CLI correctly rejects.
(try
  (let [repo (System/getProperty "user.dir")
        dir  (io/file (System/getProperty "java.io.tmpdir")
               (str "escapement-claude-cli-smoke-" (random-uuid)))]
    (.mkdirs dir)
    (spit (io/file dir ".escapement.edn")
      (pr-str {:llm/credentials [{:provider :claude-cli}]
               :llm/aliases     {:subs-haiku [{:provider :claude-cli :model "haiku"}]}
               :llm/preferences [:subs-haiku]}))
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
      ;; The tool_use id prefix proves the block came from THIS backend's
      ;; translation and that ESCAPEMENT (not the CLI) executed the tool.
      (let [tx (->> (file-seq dir)
                 (filterv #(= "transcript.jsonl" (.getName %)))
                 first)
            txt (when tx (slurp tx))]
        (check! "a transcript was written" (some? txt) "no transcript.jsonl found")
        (when txt
          (check! "escapement executed the event tool itself (toolu_cc_ id round-tripped
                   through llm/tool-result)"
            (and (str/includes? txt "llm/tool-result")
              (str/includes? txt "toolu_cc_"))
            "no claude-cli-minted tool_use id reached the tool executor")
          (check! "the response carried a tool_use block"
            (str/includes? txt "tool_use") "no tool_use block in the transcript")
          (check! "the transcript attributes the turn to claude-cli"
            (str/includes? txt "claude-cli") "provider not recorded")))
      (println "   dir:" (str dir))))
  (catch Throwable t
    (check! "hello chart did not throw" false (ex-message t))))

;;; ---------------------------------------------------------------------------

(println)
(if (empty? @failures)
  (do (println "PASS: all live claude-cli checks green") (System/exit 0))
  (do (println (str "FAIL: " (count @failures) " check(s) failed: " (pr-str @failures)))
      (System/exit 1)))
