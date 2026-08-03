(ns jvm-smoke
  "JVM load smoke test.

   Everything in this project runs under babashka, and `bb test` is the single
   test path — but the *released library* is consumed from Clojars by plain JVM
   Clojure users too. bb (SCI) is more permissive than the JVM reader and
   compiler, so source that is perfectly green under `bb test` can still fail to
   load for a JVM consumer. That is not hypothetical: issue #24 was a reader
   conditional in a `.clj` file, which SCI happily reads and the JVM reader
   rejects outright (`Conditional read not allowed`).

   This script requires every core namespace under a real `clojure -M` JVM and
   fails if any of them cannot be loaded. It catches reader-level breakage,
   macroexpansion failures, and missing/undeclared deps — anything that stops a
   namespace from loading, bb-specific or not.

   Scope: every `.clj`/`.cljc` under `src/escapement`, EXCEPT the
   `escapement.ui.*` web/RAD add-on, which deliberately needs the `:ui-test`
   (Fulcro RAD, guardrails 1.3.2) classpath and is covered by `bb ui-test`
   instead. The `escapement.tui.*` JLine add-on and `escapement.examples.*` DO
   load on the base classpath, so they are included.

   `bb-only-namespaces` lists namespaces that legitimately cannot load on the
   JVM because they depend on something babashka bundles but that is not a
   declared Maven dep. The check on that set is EXACT: a namespace listed there
   which starts loading successfully is also a failure, so the exception list
   cannot silently rot."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]))

(def bb-only-namespaces
  "Namespaces that cannot load under plain JVM Clojure, with the reason.
   Kept exact — see the ns docstring."
  {'escapement.debug.viz-server
   "requires org.httpkit.server, which babashka bundles but which is not a declared Maven dep"})

(defn core-namespaces
  "Every core namespace under src/escapement, excluding the escapement.ui.* add-on."
  []
  (->> (fs/glob "src/escapement" "**.{clj,cljc}")
       (map str)
       (remove #(str/starts-with? % "src/escapement/ui/"))
       (map #(-> %
                 (str/replace #"^src/" "")
                 (str/replace #"\.cljc?$" "")
                 (str/replace "/" ".")
                 (str/replace "_" "-")))
       sort
       vec))

(def ^:private probe-form
  "Read on the JVM side: require each namespace, and report the outcome of every
   one as an EDN map on a line we can pick back out of the output."
  '(let [results (into {}
                       (map (fn [n]
                              [n (try (require n) :ok
                                      (catch Throwable t
                                        (first (clojure.string/split-lines (str (.getMessage t))))))]))
                       NAMESPACES)]
     (println (str "JVM-SMOKE-RESULT " (pr-str results)))))

(defn -main [& _]
  (let [nss  (core-namespaces)
        _    (println (str "[jvm-smoke] requiring " (count nss) " namespaces under a JVM..."))
        form (-> (pr-str probe-form)
                 (str/replace "NAMESPACES" (pr-str (mapv #(list 'quote (symbol %)) nss))))
        res  (p/shell {:out :string :err :string :continue true} "clojure" "-M" "-e" form)
        line (->> (str/split-lines (str (:out res)))
                  (filter #(str/starts-with? % "JVM-SMOKE-RESULT "))
                  first)]
    (when-not line
      (println "[jvm-smoke] FAIL: the JVM probe did not produce a result.")
      (println (:out res))
      (println (:err res))
      (System/exit 1))
    (let [results   (read-string (subs line (count "JVM-SMOKE-RESULT ")))
          ;; A namespace we expect to load but which did not.
          broken    (into (sorted-map)
                          (remove (fn [[n r]] (or (= :ok r) (contains? bb-only-namespaces n))))
                          results)
          ;; A namespace on the bb-only list that has started loading fine —
          ;; the list is stale and should shrink.
          stale     (into (sorted-set)
                          (keep (fn [[n r]] (when (and (= :ok r) (contains? bb-only-namespaces n)) n)))
                          results)]
      (doseq [[n reason] broken]
        (println (str "  FAIL " n " -> " reason)))
      (doseq [n stale]
        (println (str "  STALE " n " now loads on the JVM — remove it from bb-only-namespaces")))
      (if (or (seq broken) (seq stale))
        (do (println (str "[jvm-smoke] " (count broken) " namespace(s) failed to load, "
                          (count stale) " stale exception(s)"))
            (System/exit 1))
        (do (println (str "[jvm-smoke] PASS — all " (- (count nss) (count bb-only-namespaces))
                          " core namespaces load under the JVM ("
                          (count bb-only-namespaces) " known bb-only, skipped)"))
            (System/exit 0))))))

(-main)
