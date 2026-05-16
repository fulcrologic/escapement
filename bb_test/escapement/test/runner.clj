(ns escapement.test.runner
  "Babashka test runner. Discovers every `*_test.clj` under `test/`, requires it,
   and hands the namespaces to `clojure.test/run-tests`. Exits non-zero on any
   failure or error."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :as t]))

(defn- path->ns [^java.io.File f]
  (let [rel (-> (.getPath f) (str/replace-first #"^test/" ""))]
    (-> rel
        (str/replace #"\.cljc?$" "")
        (str/replace "/" ".")
        (str/replace "_" "-")
        symbol)))

(defn- test-files []
  (->> (fs/glob "test" "**/*_test.{clj,cljc}")
       (map fs/file)
       (sort-by #(.getPath %))))

(defn run!
  ([] (run! nil))
  ([_opts]
   (let [files (test-files)
         nses  (mapv path->ns files)]
     (println (str "Loading " (count nses) " test namespaces…"))
     (doseq [n nses]
       (require n))
     (let [{:keys [fail error]} (apply t/run-tests nses)
           bad (+ (or fail 0) (or error 0))]
       (System/exit (if (zero? bad) 0 1))))))
