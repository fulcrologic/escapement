(ns escapement.architecture-boundary-test
  "Architectural guard for the engine/UI decoupling.

   Escapement is structured as a runtime/library *core* plus optional
   presentation add-ons:

     * the web explorer + live debugger (`escapement.ui.*`, Pathom, Fulcro, RAD),
       loaded lazily by `--api-server` via `requiring-resolve`; and
     * the terminal UI (`escapement.tui`, JLine).

   The core must never statically `require` the heavy web/Pathom/RAD layer — that
   is what makes it impossible for the outer UI layers to drag their (large,
   guardrails-1.3.2) dependency tree into the bb/JVM runtime, or to break it. The
   *embeddable* library (everything reachable from `escapement.lib`) must in
   addition stay free of the terminal UI; only the CLI front-end (`cli.clj`) is
   allowed to use the TUI directly.

   This test reads each source file's `ns` form (resolving reader conditionals to
   their :clj branch — the runtime view) and fails if any forbidden namespace is
   statically required. It is a regression guard: lazy `requiring-resolve` bridges
   (as in `cli.clj`'s `--api-server` wiring) are intentionally invisible to it,
   because they do not load at namespace-load time.

   Runs under `bb test` (a normal `test/**/*_test.clj`)."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.walk :as walk]
    [fulcro-spec.core :refer [assertions specification]]))

(def forbidden-prefixes
  "Namespace prefixes that make up the optional presentation/API add-on layer.
   No runtime/library-core namespace may statically require any of these."
  ["escapement.ui"            ; web explorer / resolvers / server / RAD screens
   "com.wsscode"              ; Pathom — the EQL read surface + control mutations
   "com.fulcrologic.fulcro"   ; Fulcro client
   "com.fulcrologic.rad"      ; RAD reports/forms
   "com.cognitect.transit"])  ; the /api transit wire format

(def tui-prefix
  "The terminal-UI add-on. Forbidden in the embeddable library; allowed in `cli.clj`.
   The facade (`escapement.tui`) AND every module under `src/escapement/tui/`
   (`escapement.tui.theme`, `.compositor`, …) make up this add-on tree; the tree
   may require within itself, but no library-core namespace may require any of it."
  "escapement.tui")

(def cli-file "src/escapement/cli.clj")
(def tui-file "src/escapement/tui.clj")
(def tui-dir
  "Directory holding the TUI sub-namespaces (the rest of the add-on tree)."
  "/escapement/tui/")

(defn source-files
  "Returns the sorted paths of every .clj/.cljc file under `src/escapement`."
  []
  (->> (file-seq (io/file "src/escapement"))
    (filter (fn [^java.io.File f] (.isFile f)))
    (map (fn [^java.io.File f] (.getPath f)))
    (filter (fn [p] (re-find #"\.cljc?$" p)))
    sort))

(defn ns-form
  "Returns the `ns` form from the source at `path`, resolving reader conditionals
   to their :clj branch (the bb/JVM runtime view). Returns nil if no `ns` form is
   present. Reads only up to the `ns` form, so later `#js`/cljs-only literals in
   the file body are never parsed."
  [path]
  (with-open [r (java.io.PushbackReader. (io/reader path))]
    (loop []
      (let [f (read {:read-cond :allow :eof ::eof} r)]
        (cond
          (= f ::eof)                      nil
          (and (seq? f) (= 'ns (first f))) f
          :else                            (recur))))))

(defn required-namespaces
  "Returns the set of every namespace symbol (as a string) referenced anywhere in
   the `ns` form at `path` — covering `:require`/`:use`, including symbols inside
   resolved reader conditionals."
  [path]
  (let [acc (atom #{})]
    (some->> (ns-form path)
      (walk/postwalk (fn [x] (when (symbol? x) (swap! acc conj (str x))) x)))
    @acc))

(defn violations
  "Returns the sorted vector of forbidden namespace strings statically required by
   `path`. `extra` is additional forbidden prefixes (the TUI is forbidden for the
   library core but permitted for the CLI front-end)."
  [path extra]
  (let [prefixes (into forbidden-prefixes extra)]
    (->> (required-namespaces path)
      (filter (fn [s] (some (fn [p] (str/starts-with? s p)) prefixes)))
      sort
      vec)))

(defn library-core-file?
  "True for source files that belong to the embeddable runtime/library core —
   i.e. everything except the `escapement.ui.*` web layer, `cli.clj`, and the
   terminal-UI add-on tree (`tui.clj` plus every file under
   `src/escapement/tui/`). The add-on tree is allowed to require within itself
   (facade → modules, module → module), so it is excluded from the core scan."
  [path]
  (and (not (str/includes? path "/escapement/ui/"))
    (not (str/includes? path tui-dir))
    (not= path cli-file)
    (not= path tui-file)))

(specification "Engine/library-core dependency boundary"
  (let [files    (source-files)
        core     (filterv library-core-file? files)
        tui-tree (filterv (fn [p] (or (= p tui-file) (str/includes? p tui-dir)))
                   files)
        ;; map of offending-path -> [forbidden-ns ...]; TUI is forbidden in core.
        core-bad (into (sorted-map)
                   (keep (fn [p] (let [v (violations p [tui-prefix])]
                                   (when (seq v) [p v]))))
                   core)]
    (assertions
      "the source tree is actually scanned (guards against a vacuous pass)"
      (> (count core) 25) => true
      "the engine/runner core stays in the scan and is NOT mistaken for the add-on"
      (and (some #(str/ends-with? % "/escapement/runner.clj") core)
        (some #(str/includes? % "/escapement/engine/") core)) => true
      "no library-core namespace statically requires the web/Pathom/RAD UI or the terminal UI"
      core-bad => {}
      "the terminal-UI add-on tree (facade + tui/ modules) is excluded from core"
      (every? (complement library-core-file?) tui-tree) => true
      "the CLI front-end (cli.clj) does not statically require the web/Pathom/RAD UI"
      (violations cli-file []) => []
      "the CLI front-end (cli.clj) IS permitted to require the terminal UI directly"
      (boolean (some #(str/starts-with? % tui-prefix) (required-namespaces cli-file))) => true)))
