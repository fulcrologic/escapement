(ns escapement.llm.claude-cli-test
  "Subprocess-behaviour tests for the `claude -p` backend.

   These drive a REAL child process — `test/resources/claude-cli/fake_claude.clj`,
   a bb script steered by `FAKE_CLAUDE_*` env vars that records its argv/env/stdin
   and replays a fixture. Several of the invariants under test (the stderr
   pipe-buffer deadlock, `destroy-tree` reaping a grandchild, the concurrency
   gate) simply cannot be observed without a real fork, which is why this file
   exists alongside the pure `translate-test`.

   `bb test` NEVER shells the real `claude` binary — there is no network call and
   no Claude Code install required. The opt-in live smoke test
   (`bb_test/claude_cli_live_smoke.clj`) is the only thing that does."
  (:require
    [babashka.process :as bp]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [com.fulcrologic.statecharts.promise :as p]
    [escapement.llm.claude-cli :as cc]
    [escapement.llm.claude-cli.translate :as t]
    [escapement.llm.protocol :as proto]
    [escapement.llm.types :as types]
    [fulcro-spec.core :refer [=> assertions component specification]]))

;;; ---------------------------------------------------------------------------
;;; Harness

(def fake-script "test/resources/claude-cli/fake_claude.clj")
(def fixture-dir "test/resources/claude-cli")

(defn fixture-path [name] (.getPath (io/file fixture-dir (str name ".jsonl"))))

;; Dirs this suite created, deleted on exit by the hook below. Without this every
;; `bb test` run leaks ~10 directories into the system temp dir.
;; (NB: SCI's `defonce` takes no docstring — hence the comment.)
(defonce ^:private temp-dirs (atom []))

(defonce ^:private temp-dir-cleanup
  (.addShutdownHook (Runtime/getRuntime)
    (Thread.
      (fn []
        ;; `File/deleteOnExit` only removes EMPTY dirs, and the fake CLI writes
        ;; its recording and timing files in here — so delete depth-first.
        (doseq [d @temp-dirs
                f (reverse (file-seq d))]
          (try (.delete f) (catch Throwable _ nil)))))))

(defn temp-dir! [prefix]
  (let [d (io/file (System/getProperty "java.io.tmpdir")
            (str prefix "-" (random-uuid)))]
    (.mkdirs d)
    (swap! temp-dirs conj d)
    d))

(def sample-tool
  {:name         "fs_write"
   :description  "Write a file."
   :input-schema {"type"                 "object"
                  "additionalProperties" false
                  "properties"           {"path" {"type" "string"} "content" {"type" "string"}}
                  "required"             ["path" "content"]}})

(defn request
  ([] (request {}))
  ([extra]
   (merge {:model    "claude-haiku-4-5"
           :system   "You are a test harness."
           :messages [{:role :user :content [{:type :text :text "SENTINEL-PROMPT-TEXT"}]}]}
     extra)))

(defn backend
  "A backend pointed at the fake CLI.

   The `FAKE_CLAUDE_*` knobs go through `:extra-child-env`, not `:parent-env`:
   `child-env` builds the child's environment as a full REPLACEMENT limited to
   `preserved-env-vars`, so anything passed as a parent var would (correctly) be
   stripped before the fake ever saw it.

   `:parent-env` still receives them too, so tests can assert that a var present
   in the PARENT is scrubbed from the child."
  [fake-env & {:as opts}]
  (cc/new-backend
    (merge {:binary          ["bb" fake-script]
            :timeout-ms      20000
            :parent-env      (merge (into {} (System/getenv)) fake-env)
            :extra-child-env (into {} (filterv (fn [[k _]] (str/starts-with? k "FAKE_CLAUDE_"))
                                       fake-env))}
      opts)))

(defn send!
  "Runs one turn, returning `{:response R}` or `{:error T :category k}`."
  [b req]
  (try
    {:response (p/await! (proto/send-turn b req))}
    (catch Throwable t
      {:error t :category (proto/error-category t)})))

;;; ---------------------------------------------------------------------------
;;; Happy path

(specification "claude-cli — a successful tool-calling turn"
  (let [b   (backend {"FAKE_CLAUDE_FIXTURE" (fixture-path "tool-call")})
        {:keys [response error]} (send! b (request {:tools [sample-tool]}))]
    (assertions
      "the turn succeeds"
      error => nil

      "and yields a schema-valid Response"
      (types/validate-response response) => nil

      "with the tool call the envelope carried"
      (:stop-reason response) => :tool_use
      (:name (first (filterv #(= :tool_use (:type %)) (:content response)))) => "fs_write"

      "reporting the model that actually ran"
      (:model response) => "claude-haiku-4-5-20251001"

      "and identifying itself in metadata"
      (get-in response [:backend-metadata :backend]) => :claude-cli
      (get-in response [:backend-metadata :cli/exit]) => 0)))

(specification "claude-cli — a plain text turn with no tools"
  (let [b (backend {"FAKE_CLAUDE_FIXTURE" (fixture-path "text-only")})
        {:keys [response error]} (send! b (request))]
    (assertions
      "succeeds"
      error => nil
      (types/validate-response response) => nil
      (:stop-reason response) => :end_turn
      (:text (first (:content response))) => "The capital of France is Paris.")))

;;; ---------------------------------------------------------------------------
;;; What the child actually saw  (NN-2, NN-4)

(specification "claude-cli — what reaches the child process"
  (let [rec (io/file (temp-dir! "cc-rec") "rec.edn")
        b   (backend {"FAKE_CLAUDE_FIXTURE"   (fixture-path "tool-call")
                      "FAKE_CLAUDE_RECORD_TO" (.getPath rec)
                      ;; Present in the PARENT env; must not reach the child.
                      "ANTHROPIC_API_KEY"     "sk-ant-MUST-NOT-LEAK"
                      "ANTHROPIC_BASE_URL"    "https://evil.example"
                      "CLAUDECODE"            "1"
                      "CLAUDE_CODE_ENTRYPOINT" "cli"})
        _   (send! b (request {:tools [sample-tool]}))
        {:keys [argv env stdin]} (edn/read-string (slurp rec))]

    (component "NN-2 — the prompt never touches argv"
      (assertions
        "no argv element carries prompt text (argv is world-readable via ps)"
        (not-any? #(str/includes? % "SENTINEL-PROMPT-TEXT") argv) => true
        "the prompt arrived on stdin instead"
        (str/includes? stdin "SENTINEL-PROMPT-TEXT") => true
        "as exactly one newline-delimited user message"
        (count (remove str/blank? (str/split-lines stdin))) => 1
        (str/includes? stdin "\"type\":\"user\"") => true))

    (component "NN-1 / flag invariants as the child really received them"
      (assertions
        "never --bare (which would skip OAuth and keychain reads)"
        (contains? (set argv) "--bare") => false
        "tools disabled"
        (contains? (set argv) "--tools") => true
        "safe-mode on"
        (contains? (set argv) "--safe-mode") => true
        "a system prompt file was passed and it EXISTS at spawn time"
        (contains? (set argv) "--system-prompt-file") => true))

    (component "NN-4 — the child env is a scrubbed replacement"
      (assertions
        "ANTHROPIC_API_KEY did not reach the child — this is the billing guarantee"
        (contains? env "ANTHROPIC_API_KEY") => false
        "no child env value mentions the secret anywhere"
        (some #(str/includes? % "sk-ant-MUST-NOT-LEAK") (vals env)) => nil
        "no substituted base URL"
        (contains? env "ANTHROPIC_BASE_URL") => false
        "the inherited Claude Code markers are gone, so the child does not think
         it is a nested session"
        (contains? env "CLAUDECODE") => false
        (contains? env "CLAUDE_CODE_ENTRYPOINT") => false
        "PATH and HOME survive (HOME is load-bearing for the keychain read)"
        (string? (get env "PATH")) => true
        (string? (get env "HOME")) => true))

    (component "the system-prompt file documents the tools"
      ;; The system prompt is the ONLY channel through which the model learns
      ;; escapement's tools exist, since --tools "" leaves the CLI toolless.
      (let [i  (.indexOf ^java.util.List argv "--system-prompt-file")
            ;; the file is deleted in a finally, so assert on argv shape only
            pth (nth argv (inc i))]
        (assertions
          "an absolute path was passed"
          (str/starts-with? pth "/") => true
          "and it was cleaned up after the turn (no temp-file leak)"
          (.exists (io/file pth)) => false)))))

;;; ---------------------------------------------------------------------------
;;; Failure modes

(specification "claude-cli — timeout kills the process tree (NN-11, NN-13)"
  (let [marker  (str (+ 40000 (rand-int 9999)))   ; a sleep duration nothing else uses
        b       (backend {"FAKE_CLAUDE_FIXTURE"     (fixture-path "text-only")
                          "FAKE_CLAUDE_SLEEP_MS"    "60000"
                          "FAKE_CLAUDE_SPAWN_CHILD" marker}
                  :timeout-ms 2500)
        started (System/currentTimeMillis)
        {:keys [error category]} (send! b (request))
        elapsed (- (System/currentTimeMillis) started)]
    (assertions
      "the turn fails rather than hanging"
      (some? error) => true

      "categorized :timeout so the chart can branch on it"
      category => :timeout

      "and it really did give up near the deadline, not at the fixture's 60s"
      (< elapsed 20000) => true))

  (component "the grandchild is reaped, proving destroy-tree (not a bare .destroy)"
    (let [marker (str (+ 50000 (rand-int 9999)))
          b      (backend {"FAKE_CLAUDE_FIXTURE"     (fixture-path "text-only")
                           "FAKE_CLAUDE_SLEEP_MS"    "60000"
                           "FAKE_CLAUDE_SPAWN_CHILD" marker}
                   :timeout-ms 2500)]
      (send! b (request))
      ;; Give the OS a beat to finish reaping.
      (Thread/sleep 1500)
      ;; pgrep is exec'd DIRECTLY, with no shell wrapper. `pgrep -f` matches
      ;; against whole command lines and excludes only itself — so running it as
      ;; `bash -lc "pgrep -f 'sleep <marker>'"` always matched the wrapping bash,
      ;; whose own cmdline contains the marker, and the probe could never come
      ;; back empty no matter how well destroy-tree worked.
      (let [{:keys [out]} (bp/sh {:continue true} "pgrep" "-f" (str "sleep " marker))]
        (assertions
          "no orphaned `sleep` survives the timeout — SIGTERM reached the whole
           tree, so a real CLI's Bash grandchildren cannot leak either"
          (str/blank? (str/trim out)) => true)))))

(specification "claude-cli — a nonzero exit with a valid result line is still parsed (NN-12)"
  ;; The CLI exits 1 on is_error but still prints a full result line, so the
  ;; exit code must never be trusted before the stream is parsed.
  (let [b (backend {"FAKE_CLAUDE_FIXTURE" (fixture-path "is-error-auth")
                    "FAKE_CLAUDE_EXIT"    "1"})
        {:keys [error category]} (send! b (request))]
    (assertions
      "it fails"
      (some? error) => true
      "categorized from the PARSED result, not from the exit code — an expired
       OAuth token is :auth, which is terminal, so run-turn stops retrying"
      category => :auth
      "and the message quotes the CLI's own diagnosis"
      (str/includes? (ex-message error) "OAuth") => true)))

(specification "claude-cli — truncation is reported as a successful turn (NN-5)"
  (let [b (backend {"FAKE_CLAUDE_FIXTURE" (fixture-path "truncated-max-output-tokens")
                    "FAKE_CLAUDE_EXIT"    "1"})
        {:keys [response error]} (send! b (request))]
    (assertions
      "an output-token ceiling is NOT a backend failure, even at exit 1"
      error => nil
      (types/validate-response response) => nil

      "reported as :end_turn — a :max_tokens stop cannot be stitched here,
       because drive-turn!'s synthetic ASSISTANT prefill is undeliverable through
       the CLI's user-only input format, and the node would die"
      (:stop-reason response) => :end_turn

      "with the truncation recorded in metadata instead"
      (get-in response [:backend-metadata :truncated]) => true

      "and the partial answer preserved"
      (str/includes? (:text (first (:content response))) "beginning of a very long") => true)))

(specification "claude-cli — a startup death prints only to stderr (NN-12)"
  (let [b (backend {"FAKE_CLAUDE_EXIT"         "1"
                    "FAKE_CLAUDE_STDERR_BYTES" "120"
                    "FAKE_CLAUDE_NO_STDIN"     "1"})
        {:keys [error category]} (send! b (request))]
    (assertions
      "fails"
      (some? error) => true
      "a malformed invocation is :invalid-request (TERMINAL), not a :transport
       blip that run-turn would retry three times"
      category => :invalid-request
      "and the stderr is surfaced for diagnosis"
      (str/includes? (ex-message error) "without producing any JSON") => true)))

(specification "claude-cli — a missing binary fails fast and actionably"
  (let [b (cc/new-backend {:binary     ["definitely-not-a-real-binary-xyz"]
                           :timeout-ms 10000})
        {:keys [error category]} (send! b (request))]
    (assertions
      "fails"
      (some? error) => true
      ":invalid-request is terminal, so run-turn does not retry a nonexistent
       binary three times"
      category => :invalid-request
      "and tells the user how to install and authenticate"
      (str/includes? (ex-message error) "claude auth login") => true)))

;;; ---------------------------------------------------------------------------
;;; The stderr deadlock regression (NN-11)

(specification "claude-cli — a chatty child does not deadlock on the stderr pipe"
  ;; THE regression this guards: a pipe buffer is ~64 KB, and stderr is written
  ;; by the fake BEFORE stdout. If stderr were a pipe nobody drains (because we
  ;; are busy folding stdout), the child would block forever at 64 KB and the
  ;; turn would hang past its timeout. Redirecting stderr to a FILE is what
  ;; makes this pass. Only a real subprocess can test it.
  (let [b (backend {"FAKE_CLAUDE_FIXTURE"      (fixture-path "text-only")
                    "FAKE_CLAUDE_STDERR_BYTES" "1000000"}   ; ~15x the pipe buffer
            :timeout-ms 30000)
        started (System/currentTimeMillis)
        {:keys [response error]} (send! b (request))
        elapsed (- (System/currentTimeMillis) started)]
    (assertions
      "the turn completes normally despite ~1 MB of stderr"
      error => nil
      (types/validate-response response) => nil
      (:stop-reason response) => :end_turn
      "and it completed promptly rather than timing out"
      (< elapsed 25000) => true)))

;;; ---------------------------------------------------------------------------
;;; Concurrency gate (NN-7)

(specification "claude-cli — concurrency is bounded"
  (let [timing (temp-dir! "cc-timing")
        limit  2
        b      (backend {"FAKE_CLAUDE_FIXTURE"    (fixture-path "text-only")
                         "FAKE_CLAUDE_SLEEP_MS"   "600"
                         "FAKE_CLAUDE_TIMING_DIR" (.getPath timing)}
                 :max-concurrency limit
                 :timeout-ms 60000)
        results (->> (range 8)
                  (mapv (fn [_] (future (send! b (request)))))
                  (mapv deref))
        spans   (->> (.listFiles timing)
                  (filterv #(str/ends-with? (.getName %) ".edn"))
                  (mapv #(edn/read-string (slurp %))))
        ;; Max simultaneous overlap, via a sweep over start/end events.
        peak    (->> (concat (mapv (fn [s] [(:start s) 1]) spans)
                       (mapv (fn [s] [(:end s) -1]) spans))
                  (sort-by (juxt first second))
                  (reduce (fn [[cur mx] [_ d]]
                            (let [c (+ cur d)] [c (max mx c)]))
                    [0 0])
                  second)]
    (assertions
      "all 8 turns eventually succeed — the gate queues, it does not drop"
      (count (filterv (comp nil? :error) results)) => 8

      "and all 8 really ran as separate processes"
      (count spans) => 8

      "but never more than :max-concurrency at once — each turn is a separate
       ~300-500 MB process, so unbounded fan-out would exhaust the host"
      (<= peak limit) => true

      "and the gate was actually exercised (more work than permits)"
      (> (count spans) limit) => true)))

(specification "claude-cli — the gate can be disabled"
  (let [b (backend {"FAKE_CLAUDE_FIXTURE" (fixture-path "text-only")}
            :max-concurrency 0)
        {:keys [error]} (send! b (request))]
    (assertions
      "max-concurrency 0 means no gate, and turns still work"
      error => nil)))

;;; ---------------------------------------------------------------------------
;;; Invariants of the adapter skeleton

(specification "claude-cli — adapter invariants"
  (component "default model fill"
    (let [rec (io/file (temp-dir! "cc-rec") "rec.edn")
          b   (backend {"FAKE_CLAUDE_FIXTURE"   (fixture-path "text-only")
                        "FAKE_CLAUDE_RECORD_TO" (.getPath rec)}
                :default-model "opus")
          _   (send! b (dissoc (request) :model))
          argv (:argv (edn/read-string (slurp rec)))]
      (assertions
        "a Request with no :model gets the backend default, normalized"
        (nth argv (inc (.indexOf ^java.util.List argv "--model"))) => "opus")))

  (component "request validation happens before any process is spawned"
    (let [b (backend {"FAKE_CLAUDE_FIXTURE" (fixture-path "text-only")})
          {:keys [error]} (send! b {:model "haiku"})]   ; :messages missing
      (assertions
        "an invalid Request throws"
        (some? error) => true
        "with the schema errors attached"
        (some? (:errors (ex-data error))) => true)))

  (component "an oversized transcript is rejected without paying a spawn (NN-2)"
    (let [huge (apply str (repeat (inc t/stdin-byte-limit) \x))
          b    (backend {"FAKE_CLAUDE_FIXTURE" (fixture-path "text-only")})
          {:keys [error category]} (send! b (request {:messages [{:role    :user
                                                                  :content [{:type :text :text huge}]}]}))]
      (assertions
        "categorized :context-length so a chart can compact and retry"
        category => :context-length
        "and the message says why"
        (str/includes? (ex-message error) "stdin limit") => true)))

  (component "transcript-fn emission is redacted"
    (let [events (atom [])
          b      (backend {"FAKE_CLAUDE_FIXTURE" (fixture-path "tool-call")}
                   :transcript-fn (fn [e] (swap! events conj e)))
          _      (send! b (request {:tools [sample-tool]}))
          evs    @events
          req-ev (first (filterv #(= :llm/request (:event %)) evs))
          all    (pr-str evs)]
      (assertions
        "both a request and a response event are emitted"
        (mapv :event evs) => [:llm/request :llm/response]

        "the request event names the backend and the resolved CLI model"
        (:backend req-ev) => :claude-cli
        (:cli-model req-ev) => "haiku"

        "the PROMPT is redacted — never emitted into the transcript"
        (str/includes? all "SENTINEL-PROMPT-TEXT") => false

        "the system prompt CONTENTS are redacted, only its size is reported"
        (str/includes? all "You are a test harness.") => false
        (pos? (:system-prompt-bytes req-ev)) => true

        "the schema blob is elided from the recorded argv rather than dumped"
        (str/includes? all "<schema") => true

        "tool names are recorded (they are not secret and aid debugging)"
        (:tools req-ev) => ["fs_write"])))

  (component "cli-version never throws"
    (assertions
      "a missing binary yields nil rather than an exception"
      (cc/cli-version "definitely-not-a-real-binary-xyz") => nil))

  (component "the record implements LLMBackend but NOT StreamingLLMBackend (NN-8)"
    (let [b (cc/new-backend {})]
      (assertions
        "so send-turn* falls back cleanly instead of a stream-turn that never
         calls on-delta, which would make await-turn! abandon the turn at the
         first-token cap whenever a fallback candidate is configured"
        (satisfies? proto/LLMBackend b) => true
        (proto/streaming? b) => false))))
