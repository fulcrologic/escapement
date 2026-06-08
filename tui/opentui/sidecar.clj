(ns opentui.sidecar
  "Spawn / supervise the OpenTUI Bun sidecar process for `--tui=opentui` (spec R4).

   This is an `escapement.ui.*` add-on: the CLI reaches it only via
   `requiring-resolve`, so the architecture boundary stays intact (core never
   statically requires it, and a plain run never loads it).

   Responsibilities:
     * Pick a free TCP port for the api-server's WS push when one isn't given.
     * Build the WS back-channel handler seam (`{:control fn :answer fn}`) that
       `escapement.ui.server/start!` dispatches inbound `control`/`answer` frames
       to — wiring control ops to the debug controller / runner queue and answers
       to the `RemoteUiRenderer` delivery registry.
     * Spawn the Bun sidecar (`bun run <entry>`) with the real TTY inherited so it
       owns stdin/stdout for rendering + keys; the agent runs headless.
     * Supervise: the parent blocks until the sidecar exits, then tears the run
       down. If the sidecar dies abnormally the parent restores the terminal
       (cooked mode, cursor, alt-screen off) via `tput`/ANSI so the user's shell
       isn't left broken.

   The wire URL passed to the sidecar is `ws://127.0.0.1:<port>/ws`
   (`OPENTUI_WS_URL` env), matching the sidecar default (task 005)."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.fulcrologic.statecharts.protocols :as sp]
   [escapement.debug.controller :as dbg]
   [escapement.debug.control-handle :as ctrl-handle]
   [escapement.ui.remote-renderer :as remote])
  (:import
   [java.lang ProcessBuilder ProcessBuilder$Redirect]
   [java.net ServerSocket]))

;; ---------------------------------------------------------------------------
;; Port + entry discovery
;; ---------------------------------------------------------------------------

(defn free-port
  "Bind a server socket to port 0, read the OS-assigned port, close it, and return
   the number. There's an inherent (tiny) TOCTOU race before the api-server binds,
   acceptable for a single local sidecar."
  []
  (with-open [s (ServerSocket. 0)]
    (.getLocalPort s)))

(defn- find-bun []
  (or (System/getenv "BUN_BIN")
      (let [candidates ["bun"
                        (str (System/getenv "HOME") "/.bun/bin/bun")
                        "/usr/local/bin/bun"
                        "/opt/homebrew/bin/bun"]]
        (some (fn [c]
                (let [f (io/file c)]
                  (when (or (.isAbsolute f) (.exists f)) c)))
              candidates))
      "bun"))

(defn- repo-root
  "Walk up from cwd looking for the `tui/opentui/` dir so the sidecar entry
   resolves regardless of where the CLI was launched."
  []
  (loop [d (.getAbsoluteFile (io/file (System/getProperty "user.dir")))]
    (cond
      (nil? d) nil
      (.isDirectory (io/file d "tui" "opentui")) d
      :else (recur (.getParentFile d)))))

(defn sidecar-entry
  "Resolve the absolute path of the Bun sidecar entry (`tui/tui/opentui/src/main.tsx`).
   Returns nil when no `tui/opentui/` workspace is found."
  []
  (when-let [root (or (some-> (System/getenv "OPENTUI_DIR") io/file .getAbsoluteFile)
                      (some-> (repo-root) (io/file "tui" "opentui")))]
    (let [entry (io/file root "src" "main.tsx")]
      (when (.exists entry) (.getAbsolutePath entry)))))

;; ---------------------------------------------------------------------------
;; Back-channel handler seam (control + answer)
;; ---------------------------------------------------------------------------

(defn- send-ui-event!
  "Post a chart event into the live runner queue (via the shared control handle),
   targeted at the running session. No-op until the env is ready / handle filled."
  [control-handle event-kw]
  (when-let [{:keys [queue session-id]} (ctrl-handle/live control-handle)]
    (when (and queue session-id)
      (try
        (sp/send! queue {} {:target            session-id
                            :source-session-id session-id
                            :event             event-kw})
        (catch Throwable _ nil)))))

(defn make-ws-handlers
  "Build the `{:control fn :answer fn}` seam passed to `server/start! :ws-handlers`.

   `control-handle` is the shared `escapement.debug.control-handle` filled on
   `on-env-ready`; `controller` is the debug controller atom (may be nil).

   `control` dispatches op→action per `docs/opentui-wire.md` §6:
     pause|step|continue|arm → debug controller; ui-interrupt|ui-quit → chart
     events `:ui.interrupt` / `:ui.quit`.
   `answer` resolves/cancels the parked human-input prompt via the
   `RemoteUiRenderer` delivery registry (§5.2).

   `publish-debug!` (optional, nil-safe) is called after every controller op
   with the current `{:paused :step-budget :config}` snapshot so the sidecar's
   PAUSED banner / Debugger view reflect live state without polling
   (`docs/opentui-wire.md` §6 forward push)."
  [{:keys [control-handle controller on-quit publish-debug! on-answered]}]
  (let [push-debug! (fn []
                      (when (and controller publish-debug!)
                        (try
                          (publish-debug!
                           {:paused      (dbg/paused? controller)
                            :step-budget (long (or (:step-budget @controller) 0))
                            :config      (:config (ctrl-handle/live control-handle))})
                          (catch Throwable _ nil))))]
  {:control
   (fn [{:keys [op n]}]
     (try
       (case (some-> op str/lower-case)
         "pause"    (when controller (dbg/pause! controller) (push-debug!))
         "step"     (when controller
                      (dotimes [_ (max 1 (or (some-> n long) 1))] (dbg/step! controller))
                      (push-debug!))
         "continue" (when controller (dbg/continue! controller) (push-debug!))
         "arm"      (when controller (dbg/arm-pause-on-next-external! controller) (push-debug!))
         "ui-interrupt" (send-ui-event! control-handle :ui.interrupt)
         "ui-quit"  (do (send-ui-event! control-handle :ui.quit)
                        (when on-quit (on-quit)))
         nil)
       (catch Throwable _ nil)))
   :answer
   (fn [{:keys [prompt-id value cancelled]}]
     (try
       (if cancelled
         (remote/cancel-answer! prompt-id)
         (remote/deliver-answer! prompt-id value))
       (when on-answered (try (on-answered) (catch Throwable _ nil)))
       (catch Throwable _ nil)))}))

;; ---------------------------------------------------------------------------
;; Terminal restore (crash safety)
;; ---------------------------------------------------------------------------

(def ^:private esc (str (char 27)))

(defn restore-terminal!
  "Best-effort: bring the controlling terminal back to a sane state after the
   sidecar (which owned raw mode / alt-screen / cursor) has gone. Emit the ANSI
   restore sequence to the real tty AND shell out to terminfo `tput cnorm`/`sgr0`
   (mirrors `escapement.tui/stop!`, the only thing that reliably wakes tmux's
   per-pane cursor tracking). Idempotent + fire-and-forget."
  []
  (try
    ;; Straight to /dev/tty. Beyond attrs/alt-screen/cursor we also disable the
    ;; modes OpenTUI turns ON (kitty keyboard protocol, mouse tracking, focus +
    ;; bracketed-paste reporting): if the sidecar died abnormally WITHOUT a
    ;; graceful renderer.destroy(), leaving the kitty keyboard protocol enabled
    ;; breaks keyboard handling desktop-wide (e.g. Hyprland window switching).
    (let [seq (str esc "[<u"                                ; pop kitty keyboard protocol
                   esc "[?1000l" esc "[?1002l" esc "[?1003l" esc "[?1006l" ; mouse tracking off
                   esc "[?1004l"                            ; focus reporting off
                   esc "[?2004l"                            ; bracketed paste off
                   esc "[0m" esc "[?1049l" esc "[?25h")]    ; attrs reset, leave alt-screen, show cursor
      (spit "/dev/tty" seq))
    (catch Throwable _ nil))
  (try
    (-> (ProcessBuilder. ^"[Ljava.lang.String;"
                         (into-array String ["sh" "-c" "stty sane </dev/tty >/dev/tty 2>/dev/null; tput cnorm 2>/dev/null; tput sgr0 2>/dev/null"]))
        (.inheritIO)
        (.start)
        (.waitFor))
    (catch Throwable _ nil)))

;; ---------------------------------------------------------------------------
;; Spawn + supervise
;; ---------------------------------------------------------------------------

(defn spawn!
  "Launch the Bun sidecar. `opts`:
     * `:entry`       — absolute path to the sidecar entry (`tui/opentui/src/main.tsx`).
     * `:port`        — api-server/WS port the sidecar connects to (live mode).
     * `:session-id`  — active session id (passed via env for context).
     * `:session-dir` — shared session dir (artifacts read directly off disk).
     * `:chart-sym`   — the loaded chart symbol; exported as `OPENTUI_CHART` so
                        the header strip shows the chart name (not `session`).
     * `:session-short` — short session id; exported as `OPENTUI_SESSION_SHORT`
                        for the header's `chart · <session>` line.
     * `:replay-file` — REPLAY MODE: absolute path to a wire-envelope JSONL file
                        (from `escapement.ui.replay-source/session-dir->wire-file`).
                        When set, the sidecar reads frames off disk (read-only) and
                        does NOT connect to a live WS — `OPENTUI_REPLAY` is exported
                        and the live `OPENTUI_WS_URL`/`OPENTUI_WS_PORT` env vars are
                        omitted (the TS transport prefers `OPENTUI_REPLAY`; see
                        `tui/opentui/src/transport/index.ts`).
     * `:replay-timing` — replay pacing: `instant|paced|wallclock` (default
                        `instant`); exported as `OPENTUI_REPLAY_TIMING`. Ignored in
                        live mode.
     * `:replay-loop?` — when true, export `OPENTUI_REPLAY_LOOP=1` (replay only).

   Live and replay modes are mutually exclusive: a `:replay-file` selects replay.
   The child inherits the real TTY (stdin/stdout/stderr) so it owns rendering +
   keys. Returns the `java.lang.Process`."
  [{:keys [entry port session-id session-dir chart-sym session-short
           replay-file replay-timing replay-loop?]}]
  (let [replay? (some? replay-file)
        bun     (find-bun)
        pb      (ProcessBuilder. ^"[Ljava.lang.String;"
                                 (into-array String [bun "run" entry]))
        ;; Run with cwd = the tui/opentui/ project dir (entry is tui/opentui/src/main.tsx).
        ;; Bun resolves `bunfig.toml` relative to cwd; that bunfig sets the
        ;; `@opentui/solid/preload` that compiles Solid JSX. Without this cwd the
        ;; preload never loads and Bun falls back to React's automatic JSX runtime
        ;; (`Cannot find module 'react/jsx-dev-runtime'`).
        ot-dir  (some-> (io/file entry) .getParentFile .getParentFile)
        env     (.environment pb)]
    (when (and ot-dir (.isDirectory ot-dir))
      (.directory pb ot-dir))
    (if replay?
      ;; --- Replay mode: read frames off disk, no live WS back-channel. ---
      (do
        (.put env "OPENTUI_REPLAY" (str replay-file))
        (.put env "OPENTUI_REPLAY_TIMING" (or (some-> replay-timing str) "instant"))
        (when replay-loop? (.put env "OPENTUI_REPLAY_LOOP" "1"))
        ;; Defensive: ensure no stale live WS env leaks into the child and makes
        ;; the transport try to dial a non-existent endpoint. (OPENTUI_REPLAY
        ;; already wins in index.ts, but keep the env unambiguous.)
        (.remove env "OPENTUI_WS_URL")
        (.remove env "OPENTUI_WS_PORT"))
      ;; --- Live mode (unchanged default): connect to the api-server WS push. ---
      (do
        (.put env "OPENTUI_WS_URL" (str "ws://127.0.0.1:" port "/ws"))
        (.put env "OPENTUI_WS_PORT" (str port))))
    (when session-id (.put env "ESCAPEMENT_SESSION_ID" (str session-id)))
    ;; Header strip parity (task 004): chart name + short session id. The sidecar
    ;; reads OPENTUI_CHART / OPENTUI_SESSION_SHORT in main.tsx (chartNameFromEnv /
    ;; sessionShortFromEnv) for header line 1.
    (when chart-sym (.put env "OPENTUI_CHART" (str chart-sym)))
    (when session-short (.put env "OPENTUI_SESSION_SHORT" (str session-short)))
    ;; Export an ABSOLUTE session dir: the sidecar's cwd is tui/opentui/ (set above),
    ;; but `session-dir` is relative to the AGENT's cwd (repo root). Resolve it
    ;; here (in the agent) so the sidecar's artifact reads hit the right path.
    (when session-dir
      (.put env "ESCAPEMENT_SESSION_DIR"
        (.getAbsolutePath (io/file (str session-dir)))))
    ;; Inherit the real tty: the sidecar owns the terminal for rendering + keys.
    (.redirectInput pb ProcessBuilder$Redirect/INHERIT)
    (.redirectOutput pb ProcessBuilder$Redirect/INHERIT)
    (.redirectError pb ProcessBuilder$Redirect/INHERIT)
    (.start pb)))

(defn supervise!
  "Block until `proc` exits. On any exit run `on-exit` with the exit code, then —
   regardless of code — restore the terminal (the sidecar owned raw mode). Returns
   the exit code. `cancel-all!`s any parked prompts so no worker hangs forever."
  [proc {:keys [on-exit]}]
  (let [code (try (.waitFor proc) (catch InterruptedException _ -1))]
    (try (remote/cancel-all!) (catch Throwable _ nil))
    (when on-exit (try (on-exit code) (catch Throwable _ nil)))
    (restore-terminal!)
    code))

(defn destroy!
  "Terminate the sidecar (used when the agent finishes first / on teardown).
   Sends SIGTERM, waits briefly, then SIGKILL. Always restores the terminal."
  [proc]
  (try
    (when (and proc (.isAlive proc))
      (.destroy proc)
      (when-not (.waitFor proc 2 java.util.concurrent.TimeUnit/SECONDS)
        (.destroyForcibly proc)))
    (catch Throwable _ nil))
  (restore-terminal!))
