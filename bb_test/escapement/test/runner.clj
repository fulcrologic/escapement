(ns escapement.test.runner
  "Babashka test runner. Discovers every `*_test.clj` under `test/`, requires it,
   and hands the namespaces to `clojure.test/run-tests`. Exits non-zero on any
   failure or error.

   A small set of tests are JVM-only (they load the Fulcro RAD stack, which the bb
   runtime cannot — see CLAUDE.md `:ui-test` notes). Those live under `test/` for the
   `:ui-test` JVM alias to find, but are skipped here so `bb test` stays green. They
   run via `clojure -M:ui-test` instead.

   Output is quiet by default — a dot per passing assertion and full detail only on
   failures/errors (kaocha's `documentation`-style noise floor, but bb-native; we
   never reintroduce kaocha, see CLAUDE.md house rules). Set `ESCAPEMENT_TEST_VERBOSE`
   to restore clojure.test's default chatter (and the raw fulcro-spec event EDN)."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :as t]
   [taoensso.timbre :as timbre]))

(defn- verbose? []
  (some-> (System/getenv "ESCAPEMENT_TEST_VERBOSE") str/blank? not))

(defn quiet-reporting!
  "Tame test output to just dots + failures.

   fulcro-spec reports its structure (`:begin-behavior`, `:begin-specification`, …)
   through `clojure.test/report`, whose `:default` method `prn`s anything it doesn't
   recognise — that's the EDN-event spew. We swallow it via a no-op `:default`, drop
   the per-namespace `Testing …` banner, and print a dot per pass. `:fail`/`:error`/
   `:summary` keep clojure.test's own (informative) methods, so failure blocks —
   including the fulcro-spec behavior context — render exactly as before."
  []
  (defmethod t/report :default [_] nil)         ;; swallow fulcro-spec :begin-/:end- events
  (defmethod t/report :begin-test-ns [_] nil)   ;; drop the "Testing <ns>" banners
  (defmethod t/report :pass [_]
    (t/with-test-out
      (t/inc-report-counter :pass)
      (print ".")
      (flush))))

(defn quiet-logging!
  "Statecharts logs at DEBUG via timbre; raise the floor so test output is signal-only."
  []
  (timbre/set-min-level! :warn))

(def jvm-only-namespaces
  "Test namespaces that require the JVM-only Fulcro RAD stack and must NOT load under bb.
   Excluded from `bb test`; exercised via `clojure -M:ui-test`."
  #{'escapement.ui.screens-load-test
    'escapement.ui.tui-render-test
    'escapement.ui.web-render-test
    'escapement.ui.control-test
    'escapement.ui.tui-form-test})

(defn- path->ns [^java.io.File f root]
  (let [pfx (str root "/")
        rel (-> (.getPath f) (str/replace-first (re-pattern (str "^" pfx)) ""))]
    (-> rel
        (str/replace #"\.cljc?$" "")
        (str/replace "/" ".")
        (str/replace "_" "-")
        symbol)))

(defn- test-files [root]
  (->> (fs/glob root "**/*_test.{clj,cljc}")
       (map fs/file)
       (sort-by #(.getPath %))))

(defn- discover [paths]
  (mapcat (fn [root]
            (map #(vector root %) (test-files root)))
          paths))

(defn run-paths!
  "Run every *_test.{clj,cljc} under each root in `:paths`."
  ([] (run-paths! {:paths ["test"]}))
  ([{:keys [paths] :or {paths ["test"]}}]
   (when-not (verbose?)
     (quiet-logging!)
     (quiet-reporting!))
   (let [pairs (discover paths)
         nses  (into []
                 (remove jvm-only-namespaces)
                 (map (fn [[root f]] (path->ns f root)) pairs))]
     (println (str "Loading " (count nses) " test namespaces from " (vec paths) "…"))
     (doseq [n nses]
       (require n))
     (let [{:keys [fail error]} (apply t/run-tests nses)
           bad (+ (or fail 0) (or error 0))]
       (System/exit (if (zero? bad) 0 1))))))

(defn run!
  "Default entry: discover and run everything under `test/`."
  ([] (run! nil))
  ([_opts] (run-paths! {:paths ["test"]})))
