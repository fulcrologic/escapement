(ns escapement.llm.claude-cli
  "LLMBackend that drives the **Claude Code CLI** (`claude -p`) as a plain
   one-turn model, so turns bill against a Claude Max/Pro **subscription**
   instead of a metered API key.

   Usage:
     (require '[escapement.llm.claude-cli :as cc])
     (cc/new-backend {:default-model \"sonnet\"})

   Authentication is the CLI's own: run `claude auth login` (interactive) or
   `claude setup-token` (long-lived, for headless use) once. This backend never
   mints, stores, or reads an OAuth token itself — it shells the real binary,
   which is a documented product surface for headless use, and lets the CLI do
   its own keychain read.

   ## Shape

   One CLI process per `send-turn`, **stateless** — no `--resume`, no session
   reuse. (`:conversation/id` is a chart-author parameter that nothing in the
   framework ever populates, so a session-keyed design would share one nil key
   across every concurrent worker; `bb haiku` runs ~8 at once.)

   All of the CLI's built-in tools are disabled and Escapement keeps executing
   every tool itself, so a chart behaves identically on `:claude-cli` and
   `:anthropic`. Delegate mode (the CLI keeping its own tools as a sub-agent)
   and an MCP bridge are deliberately out of scope — they would break
   tool-execution symmetry across backends.

   ## Silently dropped

   `:temperature`, `:top-p`, `:top-k`, `:stop-sequences`, `:metadata`,
   `:max-tokens` and every `:cache-control` marker have no CLI surface and are
   dropped with one warning (see `translate/dropped-request-keys`). Notably
   `:overrun :temperature-bump` (used by `bb haiku`) becomes a no-op, so an
   overrun rerun produces identical output.

   Because the CLI retries internally on rate-limit/overload, charts using this
   backend usually want `:resilience {:max-retries 1}` so the two retry layers
   do not multiply.

   ## Not streaming

   This record intentionally implements only `LLMBackend`, never
   `StreamingLLMBackend`. A `defrecord` cannot conditionally satisfy a
   protocol, and a `stream-turn` that never calls `on-delta` would make
   `await-turn!` abandon the turn at the first-token cap whenever a fallback
   candidate is configured. The cost is no live token display, and
   `:latency :first-token-ms` degrading to a total-response cap — already
   documented as acceptable in `escapement.llm`."
  (:require
    [babashka.process :as bp]
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
    [com.fulcrologic.statecharts.promise :as p]
    [escapement.llm.claude-cli.translate :as t]
    [escapement.llm.protocol :as proto]
    [escapement.llm.types :as types]
    [taoensso.timbre :as log])
  (:import
    (java.io File)
    (java.nio.file Files)
    (java.nio.file.attribute PosixFilePermissions)
    (java.util.concurrent Semaphore TimeUnit)))

(def ^:const default-timeout-ms
  "Generous by design: the CLI absorbs rate-limit and overload backoff
   internally and can legitimately sit for minutes before producing its first
   byte."
  600000)

(def ^:const default-max-concurrency
  "Each turn is a separate ~300-500 MB process with a real cold start, so
   unbounded fan-out will exhaust a laptop long before it exhausts the
   subscription."
  3)

(def ^:const sigterm-grace-ms
  "How long to let the CLI wind down after SIGTERM before `destroyForcibly`.
   On SIGTERM it aborts the turn and kills its own child tree, exiting 143."
  3000)

(def ^:const max-argv-schema-bytes
  "`--json-schema` rides on argv, and `ARG_MAX` is ~1 MiB. A tool set whose
   schemas exceed this falls back to the fenced-JSON envelope (carried in the
   system-prompt FILE, which has no size limit) instead of failing to exec."
  200000)

;;; ---------------------------------------------------------------------------
;;; Temp files

(defn- write-private-temp-file!
  "Writes `content` to a fresh temp file with `0600` permissions and returns the
   `File`. The system prompt is prompt-derived material, so it must not be
   world-readable on a shared host."
  [prefix content]
  (let [f (File/createTempFile prefix ".txt")]
    (try
      (Files/setPosixFilePermissions (.toPath f) (PosixFilePermissions/fromString "rw-------"))
      (catch Throwable _
        ;; Non-POSIX filesystem; fall back to the JDK's own best effort.
        (doto f (.setReadable false false) (.setReadable true true))))
    (spit f content)
    f))

(defn- delete-quietly! [^File f]
  (when f (try (.delete f) (catch Throwable _ nil))))

(defn- slurp-safe [f]
  (try (slurp f) (catch Throwable _ "")))

;;; ---------------------------------------------------------------------------
;;; Concurrency gate

(defn- acquire-slot!
  "Blocks up to `wait-ms` for a concurrency permit. Throws `:overloaded` when
   the gate stays full — a categorized, retryable failure, which is the honest
   description of \"this host is already running as many CLI processes as it
   is allowed to\"."
  [^Semaphore sem wait-ms]
  (when sem
    (when-not (.tryAcquire sem wait-ms TimeUnit/MILLISECONDS)
      (throw (proto/llm-error :overloaded
               (str "claude-cli concurrency gate full (" (.availablePermits sem)
                 " permits free) after waiting " wait-ms "ms"))))))

;;; ---------------------------------------------------------------------------
;;; Invocation

(defn- redacted-argv
  "argv for the transcript with the `--json-schema` payload elided.

   Nothing prompt-derived is ever on argv (see `translate/build-argv`), but the
   schema blob is large and noisy, and the system-prompt FILE's contents must
   never be emitted — only its size."
  [argv]
  (loop [in argv out []]
    (if-let [a (first in)]
      (if (= "--json-schema" a)
        (recur (drop 2 in) (conj out a (str "<schema " (count (str (second in))) " bytes>")))
        (recur (rest in) (conj out a)))
      out)))

(defn- run-cli!
  "Spawns the CLI, folds its stdout, and returns
   `{:acc A :exit N :stderr S :timed-out? B}`.

   Subprocess hygiene, every clause a found failure mode:
   - `:shutdown bp/destroy-tree` so a JVM/bb exit cannot orphan the child tree.
   - **stderr to a FILE.** A chatty child deadlocks at the 64 KB pipe buffer if
     nobody drains it, and we are busy folding stdout.
   - **stdout `:pipe`, drained continuously** by this thread as we fold. Never
     `:out :string`: that spawns a reader that blocks until EOF, and a
     grandchild holding the pipe open hangs the read past the timeout with no
     way to kill it.
   - **stdin written on its own thread.** The payload can approach 10 MB, which
     would fill the pipe and deadlock against a child that streams stdout
     before finishing its read.
   - A watchdog enforces the wall clock with `bp/destroy-tree` (SIGTERM to the
     CLI **and every descendant** → the CLI aborts the turn and exits 143),
     escalating to `.destroyForcibly` if it does not go. `destroy-tree` rather
     than a bare `.destroy` because the CLI is a Node process that spawns its
     own children, and SIGTERMing only the direct child would orphan them."
  [{:keys [argv env stdin timeout-ms]}]
  (let [err-file (File/createTempFile "esc-claude-cli-err" ".log")
        timed-out (atom false)
        acc      (atom (t/stream-acc-init))]
    (try
      (let [proc     (bp/process argv {:in       :pipe
                                       :out      :pipe
                                       :err      :write :err-file err-file
                                       :env      env
                                       :shutdown bp/destroy-tree})
            ^Process p (:proc proc)
            writer   (future
                       (try
                         (with-open [w (io/writer (:in proc))]
                           (.write w ^String stdin)
                           (.write w "\n")
                           (.flush w))
                         (catch Throwable e
                           ;; The child can legitimately exit before reading all
                           ;; of stdin (a startup error); that is not our failure.
                           (log/debug "[claude-cli] stdin write ended early:" (ex-message e)))))
            watchdog (future
                       (try
                         (when-not (.waitFor p timeout-ms TimeUnit/MILLISECONDS)
                           (reset! timed-out true)
                           (log/warn "[claude-cli] turn exceeded" timeout-ms
                             "ms — SIGTERM to the CLI and its descendants")
                           (try (bp/destroy-tree proc) (catch Throwable _ nil))
                           (when-not (.waitFor p sigterm-grace-ms TimeUnit/MILLISECONDS)
                             (log/warn "[claude-cli] child ignored SIGTERM — destroying forcibly")
                             (try (.destroyForcibly p) (catch Throwable _ nil))))
                         (catch Throwable _ nil)))]
        (with-open [rdr (io/reader (:out proc))]
          (loop []
            (when-let [line (.readLine rdr)]
              (t/process-stream-line! acc line)
              (recur))))
        (.waitFor p)
        (future-cancel watchdog)
        @writer
        {:acc        @acc
         :exit       (.exitValue p)
         :stderr     (try (slurp err-file) (catch Throwable _ ""))
         :timed-out? @timed-out})
      (catch java.io.IOException e
        ;; exec itself failed — a missing binary surfaces here on some hosts
        ;; rather than as exit 127.
        {:acc @acc :exit 127 :timed-out? false
         :stderr (str (slurp-safe err-file) "\n" (ex-message e))})
      (finally
        (delete-quietly! err-file)))))

;;; ---------------------------------------------------------------------------
;;; Turn

(defn- effort-for
  "Maps the Request's `:thinking` onto the CLI's `--effort`, the nearest thing
   the CLI exposes to a thinking budget. An explicit backend `:effort` wins."
  [request opts]
  (or (:effort opts)
    (when (= :enabled (get-in request [:thinking :type])) "high")))

(defn- send-turn*
  [opts request]
  (let [request       (cond-> request
                        (and (nil? (:model request)) (:default-model opts))
                        (assoc :model (:default-model opts)))
        _             (when-let [err (types/validate-request request)]
                        (throw (ex-info "Invalid LLM request" {:errors err :request request})))
        transcript-fn (:transcript-fn opts)
        {:keys [messages tools system tool-choice]} request
        _             (t/warn-dropped! request (:warned-dropped opts))
        stdin         (t/transcript->stdin messages)
        stdin-bytes   (count (.getBytes ^String stdin "UTF-8"))
        _             (when (> stdin-bytes t/stdin-byte-limit)
                        ;; Pre-checked so we never pay a process launch to learn it.
                        (throw (proto/llm-error :context-length
                                 (str "Rendered transcript is " stdin-bytes
                                   " bytes, over the claude CLI's ~" t/stdin-byte-limit
                                   "-byte stdin limit. Compact the conversation."))))
        mechanism     (t/envelope-mechanism tools)
        envelope      (when (= :json-schema mechanism) (t/envelope-schema tools tool-choice))
        ;; A schema too large for argv degrades to the fenced envelope rather
        ;; than failing to exec.
        oversized?    (and envelope (> (count (json/generate-string envelope)) max-argv-schema-bytes))
        mechanism     (if oversized? :fenced-json mechanism)
        envelope      (when-not oversized? envelope)
        _             (when oversized?
                        (log/warn "[claude-cli] tool schemas exceed" max-argv-schema-bytes
                          "bytes — using the fenced-JSON envelope to stay under ARG_MAX"))
        sys-text      (t/system-prompt-text system tools mechanism)
        sp-file       (write-private-temp-file! "esc-claude-cli-sys" sys-text)
        session-id    (str (random-uuid))
        argv          (t/build-argv {:binary             (:binary opts)
                                     :model              (t/normalize-model (:model request))
                                     :system-prompt-file (.getAbsolutePath sp-file)
                                     :json-schema        envelope
                                     :session-id         session-id
                                     :effort             (effort-for request opts)
                                     :max-budget-usd     (:max-budget-usd opts)})
        env           (t/child-env (or (:parent-env opts) (System/getenv))
                        (:extra-child-env opts))
        sem           (:semaphore opts)
        timeout-ms    (or (:timeout-ms opts) default-timeout-ms)]
    (try
      (when transcript-fn
        (transcript-fn {:event               :llm/request
                        :backend             :claude-cli
                        :model               (:model request)
                        :cli-model           (t/normalize-model (:model request))
                        :mechanism           mechanism
                        :session-id          session-id
                        ;; Redacted: no prompt text, no system-prompt contents.
                        :argv                (redacted-argv argv)
                        :stdin-bytes         stdin-bytes
                        :system-prompt-bytes (count sys-text)
                        :tools               (mapv :name tools)}))
      (acquire-slot! sem timeout-ms)
      (let [{:keys [acc exit stderr timed-out?]}
            (try
              (run-cli! {:argv argv :env env :stdin stdin :timeout-ms timeout-ms})
              (finally (when sem (.release ^Semaphore sem))))
            result   (:result acc)
            failed?  (or timed-out?
                       (nil? result)
                       (and (:is_error result) (not (t/truncated-result? result))))
            _        (when failed?
                       (let [{:keys [category message]}
                             (t/categorize-failure {:exit        exit
                                                    :result      result
                                                    :stderr      stderr
                                                    :api-retries (:api-retries acc)
                                                    :timed-out?  timed-out?
                                                    :binary      (:binary opts)})]
                         (throw (proto/llm-error category message
                                  {:data {:exit exit :cli/subtype (:subtype result)}}))))
            response (t/stream-acc-finalize acc
                       {:mechanism     mechanism
                        :request-model (:model request)
                        :tool-names    (into #{} (map :name) tools)
                        :exit          exit})]
        (when (get-in response [:backend-metadata :truncated])
          (log/warn "[claude-cli] the CLI hit an output-token ceiling; reporting :end_turn with"
            ":truncated true (a :max_tokens stop cannot be stitched on this backend)")
          (when transcript-fn
            (transcript-fn {:event   :llm/warning
                            :backend :claude-cli
                            :detail  :truncated
                            :message (get-in response [:backend-metadata :truncation-detail])})))
        (when transcript-fn
          (transcript-fn {:event :llm/response :backend :claude-cli :response response}))
        (when-let [err (types/validate-response response)]
          (throw (ex-info "claude-cli produced an invalid response"
                   {:errors err :response response})))
        response)
      (finally
        (delete-quietly! sp-file)))))

(defrecord ClaudeCliBackend [opts]
  proto/LLMBackend
  (send-turn [_ request]
    (p/do! (send-turn* opts request))))

(>defn new-backend
  "Constructs a Claude Code CLI backend.

Optional opts:
- `:default-model`     — model used when the Request omits `:model` (default `\"sonnet\"`).
                         Escapement model ids are family-mapped onto the CLI's own
                         aliases by `translate/normalize-model`, because the CLI
                         rejects ids absent from its registry (`claude-sonnet-4-7`
                         is one such). The Response reports the model that actually ran.
- `:binary`            — the executable. A string, or a **vector** so tests can point
                         at `[\"bb\" \"path/to/fake_claude.clj\"]` (default `\"claude\"`).
- `:timeout-ms`        — wall clock per turn (default 600000).
- `:max-concurrency`   — simultaneous CLI processes (default 3). Set 0/nil to disable
                         the gate entirely.
- `:effort`            — `--effort` level (`low`|`medium`|`high`|`xhigh`|`max`).
                         Defaults to `\"high\"` when the Request enables `:thinking`.
- `:max-budget-usd`    — `--max-budget-usd` ceiling.
- `:parent-env`        — env map to derive the child env from (default `(System/getenv)`).
- `:extra-child-env`   — extra vars the child should see, on top of the scrubbed
                         preserve-list. Filtered against `translate/scrubbed-env-vars`,
                         so it can never reintroduce `ANTHROPIC_API_KEY` and move
                         billing off the subscription.
- `:transcript-fn`     — `(fn [event])` called with `:llm/request` / `:llm/response` /
                         `:llm/warning`. Prompt text, system-prompt contents, and the
                         schema blob are redacted before emission."
  ([] [=> :any] (new-backend {}))
  ([opts]
   [:map => :any]
   (let [n (get opts :max-concurrency default-max-concurrency)]
     (->ClaudeCliBackend
       (cond-> (assoc opts
                 :default-model (or (:default-model opts) "sonnet")
                 ;; One dropped-params warning per backend, not per turn.
                 :warned-dropped (atom false))
         (and n (pos? n)) (assoc :semaphore (Semaphore. n true)))))))

(>defn cli-version
  "Returns the CLI's version string, or nil when the binary is absent or fails.
   Used by `escapement info`; never throws."
  ([] [=> :any] (cli-version "claude"))
  ([binary]
   [:any => :any]
   (try
     (let [{:keys [exit out]} (bp/sh (into (if (sequential? binary) (vec binary) [binary])
                                       ["--version"]))]
       (when (zero? exit) (str/trim out)))
     (catch Throwable _ nil))))
