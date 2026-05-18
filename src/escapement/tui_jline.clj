(ns escapement.tui-jline
  "JVM/jline-only terminal construction, isolated from `escapement.tui` so
   that namespace (and the `cli` chain that requires it) loads under bb,
   which has no jline on its classpath. `escapement.tui` pulls this in via
   `requiring-resolve` only on the interactive-TTY path, which is never
   reached under bb (no real terminal) — so bb never loads or analyzes the
   jline class literal below."
  (:import
   (org.jline.terminal TerminalBuilder)))

(defn make-terminal
  "Build a system jline `Terminal`. JVM-only; callers must guard with
   `escapement.tui/interactive-terminal?` before invoking."
  []
  (-> (TerminalBuilder/builder)
      (.system true)
      (.build)))
