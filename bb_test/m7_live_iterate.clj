(ns m7-live-iterate
  "Live smoke run for Milestone 7: drives the iterate coding-agent chart against
  the real z.ai (Anthropic-compat) backend.

  Sets up a tmp dir under `/tmp/escapement-m7-<uuid>/` with:
    * `spec.md`     — natural-language requirement (`square` returns x*x)
    * `target.clj`  — intentionally-buggy implementation `(defn square [x] (+ x x))`
    * test command  — `clojure -M -e ...` that loads target.clj and verifies
                      `(square 5) == 25` (returns non-zero on mismatch)

  Then runs `escapement.charts.iterate/agent` with `:max-iterations 3`. The
  agent is expected to edit `target.clj` until the test passes, terminating
  with `:final-status :passed`.

  Skips cleanly with exit 0 if `ZAI_API_KEY` is unset. On success, cleans up
  the tmp dir; on failure, leaves it for debugging."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.protocols :as sp]
   [escapement.charts.iterate :as it]
   [escapement.llm.api :as api]
   [escapement.runner :as runner]
   [escapement.tools.builtin :as builtin]))

(def model "glm-4.6")
(def base-url "https://api.z.ai/api/anthropic")

(defn- read-jsonl [path]
  (when (.exists (io/file path))
    (with-open [r (io/reader path)]
      (mapv #(json/parse-string % true) (doall (line-seq r))))))

(defn- paths-for []
  (let [root (str "/tmp/escapement-m7-" (java.util.UUID/randomUUID))
        _    (.mkdirs (io/file root))]
    {:root           root
     :spec           (str root "/spec.md")
     :target         (str root "/target.clj")
     :transcript     (str root "/iterate.jsonl")
     :checkpoint-dir (str root "/checkpoints")}))

(def spec-text
  (str
   "# Square function\n\n"
   "Implement `square` in `target.clj` so it returns the mathematical square of its argument.\n\n"
   "Concretely, the implementation must satisfy `(square 5) == 25`.\n\n"
   "The current (buggy) implementation in `target.clj` uses addition. Fix it to use multiplication.\n"))

(def buggy-target
  (str
   "(ns target)\n\n"
   "(defn square [x]\n"
   "  ;; BUG: should be multiplication, not addition\n"
   "  (+ x x))\n"))

(defn- test-cmd-for [target]
  ;; Loads the target file, calls (square 5), exits non-zero on mismatch.
  (str "clojure -Sdeps '{:deps {}}' -M -e \""
       "(load-file \\\"" target "\\\") "
       "(let [r (target/square 5)] "
       "(if (= 25 r) "
       "(do (println \\\"PASS\\\" r) (System/exit 0)) "
       "(do (println \\\"FAIL got\\\" r \\\"expected 25\\\") (System/exit 1))))"
       "\""))

(defn- backend [api-key]
  (api/new-backend {:base-url      base-url
                    :api-key       api-key
                    :default-model model}))

(defn- summarize-transcript [rows]
  {:total       (count rows)
   :llm-req     (count (filter #(= "llm/request" (:event %)) rows))
   :llm-resp    (count (filter #(= "llm/response" (:event %)) rows))
   :events      (frequencies (mapv :event rows))})

(defn- pass [m] (println (str "  [PASS] " m)))
(defn- fail [m] (println (str "  [FAIL] " m)))

(defn- check [pred msg]
  (if pred (do (pass msg) 1) (do (fail msg) 0)))

(defn- rmrf [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (rmrf c)))
  (.delete f))

(defn -main [& _]
  (let [api-key (System/getenv "ZAI_API_KEY")]
    (when-not api-key
      (println "ZAI_API_KEY is not set — skipping M7 live iterate smoke.")
      (System/exit 0))
    (let [{:keys [root spec target transcript checkpoint-dir]} (paths-for)]
      (println "M7 live iterate smoke")
      (println "  root:" root)
      (spit spec spec-text)
      (spit target buggy-target)
      (let [test-cmd (test-cmd-for target)
            _        (println "  test-cmd:" test-cmd)
            sid      (keyword "m7" (str "iterate-" (subs (str (java.util.UUID/randomUUID)) 0 8)))
            be       (backend api-key)
            initial  {:spec-path      spec
                      :target-path    target
                      :test-cmd       test-cmd
                      :max-iterations 3
                      :max-tokens     512}
            summary  (try
                       (runner/run! {:chart              it/agent
                                     :session-id         sid
                                     :transcript-path    transcript
                                     :checkpoint-dir     checkpoint-dir
                                     :backend            be
                                     :tool-registry      (builtin/new-builtin-registry)
                                     :initial-data       initial
                                     :max-iterations     5000
                                     :quiescent-sleep-ms 100})
                       (catch Throwable t
                         (println "  ERROR running chart:" (.getMessage t))
                         {:final-config nil :final-data {} :error (.getMessage t)}))
            rows     (read-jsonl transcript)
            ev-sum   (summarize-transcript rows)
            final-cfg (set (mapv str (:final-config summary)))
            env      (:env summary)
            wmem     (when env
                       (sp/get-working-memory (::sc/working-memory-store env) env sid))
            data     (or (when (and env wmem)
                           (sp/current-data (::sc/data-model env)
                                            (assoc env
                                                   ::sc/vwmem (volatile! wmem)
                                                   ::sc/context-element-id nil)))
                         {})
            iterations (:iterations data 0)
            status     (:final-status data)
            target-content (try (slurp target) (catch Throwable _ ""))
            multiplies?    (or (str/includes? target-content "(* x x)")
                               (str/includes? target-content "*"))
            passes (+ (check (contains? final-cfg ":finished")
                             (str "chart reached :finished (final-config=" (pr-str (:final-config summary)) ")"))
                      (check (= status :passed)
                             (str ":final-status is :passed (got " (pr-str status) ")"))
                      (check (pos? iterations) (str "iterations=" iterations " (>0)"))
                      (check multiplies? "target.clj contains multiplication")
                      (check (pos? (:llm-req ev-sum)) (str "llm/request count = " (:llm-req ev-sum))))
            total 5
            ok? (= passes total)]
        (println "\n=== Transcript summary ===")
        (println (format "  total rows=%d  llm-requests=%d  llm-responses=%d"
                         (:total ev-sum) (:llm-req ev-sum) (:llm-resp ev-sum)))
        (println (format "  iterations=%d  final-status=%s" iterations status))
        (println "\n=== Final target.clj ===")
        (println (str/trim target-content))
        (println "\n=== Last 20 transcript rows ===")
        (doseq [r (take-last 20 rows)]
          (println " " (:event r) "—" (pr-str (dissoc r :event :ts))))
        (println (format "\nM7 iterate smoke: %d/%d" passes total))
        (cond
          ok? (do (println "PASS")
                  (rmrf (io/file root))
                  (System/exit 0))
          :else (do (println "FAIL (leaving" root "for debugging)")
                    (System/exit 1)))))))

(when (= *file* (str (System/getProperty "babashka.file")))
  (-main))
