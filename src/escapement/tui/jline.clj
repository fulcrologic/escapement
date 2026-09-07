(ns escapement.tui.jline
  "Every JLine reference in Escapement, isolated behind seven thin wrappers.

  WHY THIS NAMESPACE EXISTS
  -------------------------
  `escapement.cli` requires `escapement.tui` at the top of its `ns` form, and
  `escapement.tui` used to `:import` JLine directly. Babashka is a GraalVM
  native image: it cannot load Java classes from jars on the classpath and
  bundles no JLine (bb 1.12 knows 520 classes, none of them `org.jline.*`).
  Worse, SCI resolves class names during the ANALYSIS phase — type hints
  included — so a lexical JLine reference is fatal at namespace-LOAD time,
  before `--no-tui` is ever parsed. The result was that `escapement.cli` could
  not be loaded under bb at all:

      Unable to resolve classname: org.jline.terminal.Terminal
        escapement/tui.clj:35:3
        escapement/cli.clj:67:3

  which contradicts the README's `bbin install` + `escapement run` flow and the
  \"Runs under Babashka\" promise. CI never caught it: `bb sanity` / `bb test`
  do not load `escapement.cli`, and `bb jvm-smoke` runs on the JVM, where JLine
  resolves normally.

  Confining the interop here lets `escapement.tui` load anywhere. It reaches
  these fns through `requiring-resolve` (see the JLine section of that
  namespace), so this file is loaded only when a real TUI is actually started —
  which is JVM-only territory anyway. Type hints stay put, so the JVM keeps the
  reflection-free implementation it had before.

  Keep this namespace free of Escapement dependencies: it must stay a leaf."
  (:import
    (org.jline.terminal Terminal TerminalBuilder)
    (org.jline.utils NonBlockingReader)))

(defn system-terminal
  "Build and return a JLine `Terminal` bound to the real system TTY."
  ^Terminal []
  (-> (TerminalBuilder/builder)
    (.system true)
    (.build)))

(defn height
  "Terminal height in rows."
  [^Terminal terminal]
  (.getHeight terminal))

(defn width
  "Terminal width in columns."
  [^Terminal terminal]
  (.getWidth terminal))

(defn enter-raw-mode!
  "Put `terminal` into raw mode so single keystrokes arrive unbuffered."
  [^Terminal terminal]
  (.enterRawMode terminal))

(defn reader
  "The terminal's `NonBlockingReader`."
  ^NonBlockingReader [^Terminal terminal]
  (.reader terminal))

(defn close!
  "Close `terminal`, restoring the pre-raw-mode tty settings."
  [^Terminal terminal]
  (.close terminal))

(defn read-char
  "Read one character. Blocks indefinitely with no `timeout-ms`; with one, waits
   at most that long and returns JLine's timeout sentinel."
  ([^NonBlockingReader rdr] (.read rdr))
  ([^NonBlockingReader rdr timeout-ms] (.read rdr (long timeout-ms))))
