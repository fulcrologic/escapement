(ns claude-p-probe
  "Spike #5: shell to `claude -p --output-format json` and dump JSON shape."
  (:require
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.pprint :as pp]))

(defn claude-on-path? []
  (try
    (let [{:keys [exit]} (p/shell {:out :string :err :string :continue true}
                                  "bash" "-lc" "command -v claude")]
      (zero? exit))
    (catch Throwable _ false)))

(defn -main [& _]
  (if-not (claude-on-path?)
    (do (println "SKIP: claude not on PATH") (System/exit 0))
    (let [_      (println "Running: claude -p --output-format json \"say hi in 3 words\"")
          {:keys [out err exit]}
          (p/shell {:out :string :err :string :continue true}
                   "claude" "-p" "--output-format" "json" "say hi in 3 words")]
      (println "exit =" exit)
      (when (seq err) (println "stderr:" err))
      (if (zero? exit)
        (let [parsed (try (json/parse-string out true) (catch Throwable t (str "parse-failed: " (.getMessage t))))]
          (println)
          (println "==== top-level keys ====")
          (pp/pprint (if (map? parsed) (sort (keys parsed)) parsed))
          (println)
          (println "==== full parsed JSON ====")
          (pp/pprint parsed)
          (println)
          (println "==== raw stdout (first 4000 chars) ====")
          (println (subs out 0 (min 4000 (count out))))
          (spit "spike/claude-p-sample.json" out)
          (println)
          (println "Wrote raw response to spike/claude-p-sample.json"))
        (do
          (println "claude exit non-zero. stdout:")
          (println out))))))

(-main)
