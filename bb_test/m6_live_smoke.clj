(ns m6-live-smoke
  "Live smoke runs for Milestone 6 against z.ai.

  Runs each of the three demo charts (`hello`, `scan`, `parallel-demo`)
  through the real `escapement.runner/run!` with the
  `escapement.llm.api/new-backend` pointed at z.ai. Skips cleanly with
  exit 0 if `ZAI_API_KEY` is not set.

  Each run gets a fresh dir under `/tmp/escapement-m6/<uuid>/` so the
  script is safe to re-run."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [escapement.charts.hello :as hello]
   [escapement.charts.parallel-demo :as pd]
   [escapement.charts.scan :as scan]
   [escapement.llm.api :as api]
   [escapement.runner :as runner]
   [escapement.tools.builtin :as builtin]))

(def model "glm-4.6")
(def base-url "https://api.z.ai/api/anthropic")

(defn- read-jsonl [path]
  (when (.exists (io/file path))
    (with-open [r (io/reader path)]
      (mapv #(json/parse-string % true) (doall (line-seq r))))))

(defn- paths-for [tag]
  (let [root (str "/tmp/escapement-m6/" (java.util.UUID/randomUUID))
        _    (.mkdirs (io/file root))]
    {:root           root
     :transcript     (str root "/" tag ".jsonl")
     :checkpoint-dir (str root "/checkpoints")}))

(defn- make-backend [api-key]
  (api/new-backend {:base-url      base-url
                    :api-key       api-key
                    :default-model model}))

(defn- summarize-transcript [rows]
  (let [counts (frequencies (mapv :event rows))]
    {:total        (count rows)
     :llm-requests (get counts "llm/request" 0)
     :llm-responses (get counts "llm/response" 0)
     :events       counts}))

(defn- assistant-event-tool-calls
  "Return all event-tool name/input pairs seen in transcript rows.

  We look for `:event-tool/fired`-shaped entries (the worker also logs
  `:llm/response` rows but does not currently split out the event-tool calls).
  So we look at `:event` named entries from the runner that mention the
  configuration moving forward."
  [rows]
  (->> rows
       (filter #(= "runner/event-processed" (:event %)))
       (mapv #(get-in % [:data :event-name]))))

(defn- run-chart!
  "Run `chart` against the real backend. Returns a map with `:summary`,
  `:rows` (parsed transcript), `:final-config`."
  [{:keys [chart tag backend initial-data]}]
  (let [{:keys [transcript checkpoint-dir root]} (paths-for tag)
        sid     (keyword "m6" (str tag "-" (subs (str (java.util.UUID/randomUUID)) 0 8)))
        summary (runner/run! {:chart              chart
                              :session-id         sid
                              :transcript-path    transcript
                              :checkpoint-dir     checkpoint-dir
                              :backend            backend
                              :tool-registry      (builtin/new-builtin-registry)
                              :initial-data       initial-data
                              :max-iterations     2000
                              :quiescent-sleep-ms 100})
        rows    (read-jsonl transcript)]
    {:tag          tag
     :root         root
     :transcript   transcript
     :final-config (:final-config summary)
     :rows         rows
     :ev-summary   (summarize-transcript rows)}))

(defn- pass [tag msg]
  (println (str "  [PASS] " tag " — " msg)))
(defn- fail [tag msg]
  (println (str "  [FAIL] " tag " — " msg)))

(defn- check
  "Assertion that prints PASS/FAIL and returns 1 (pass) or 0 (fail)."
  [tag pred? msg]
  (if pred? (do (pass tag msg) 1) (do (fail tag msg) 0)))

(defn- run-hello! [backend]
  (println "\n=== hello (single-region) ===")
  (let [r (run-chart! {:chart hello/agent :tag "hello" :backend backend})
        {:keys [rows ev-summary final-config]} r
        config-strs (set (mapv str final-config))
        finished?   (contains? config-strs ":finished")
        ;; The worker transcript writes its own :llm/request and :llm/response events.
        n-req       (:llm-requests ev-summary)
        n-resp      (:llm-responses ev-summary)
        event-names (set (assistant-event-tool-calls rows))
        passes (+ (check "hello" (.exists (io/file (:transcript r))) "transcript file exists")
                  (check "hello" (pos? n-req) (str "got " n-req " :llm/request events"))
                  (check "hello" (pos? n-resp) (str "got " n-resp " :llm/response events"))
                  (check "hello" finished? (str "final config = " (pr-str final-config)))
                  (check "hello" (contains? event-names "done")
                         (str "saw runner-level :done event (event names: " (pr-str event-names) ")")))]
    (println "  transcript:" (:transcript r))
    {:tag "hello" :passes passes :total 5}))

(defn- run-scan! [backend]
  (println "\n=== scan (real-tool + fan-out) ===")
  (let [r (run-chart! {:chart       scan/agent
                       :tag         "scan"
                       :backend     backend
                       :initial-data {:repo-path "/Users/tonykay/fulcrologic/statechart-agents"}})
        {:keys [rows ev-summary final-config]} r
        ev-names    (assistant-event-tool-calls rows)
        found-bugs  (count (filter #(= "found-bug" %) ev-names))
        scan-cmp    (count (filter #(= "scan-complete" %) ev-names))
        config-strs (set (mapv str final-config))
        finished?   (contains? config-strs ":finished")
        passes (+ (check "scan" (>= found-bugs 1) (str found-bugs " :found-bug events"))
                  (check "scan" (= scan-cmp 1) (str scan-cmp " :scan-complete events (want 1)"))
                  (check "scan" finished? (str "final config = " (pr-str final-config))))]
    (println "  transcript:" (:transcript r))
    ;; Print findings for the human (best effort — they live in the transcript via event data).
    (println "  Findings observed in transcript:")
    (doseq [row rows
            :when (and (= "runner/event-processed" (:event row))
                       (= "found-bug" (get-in row [:data :event-name])))]
      (println "    -" (pr-str (:data row))))
    {:tag "scan" :passes passes :total 3}))

(defn- run-parallel! [backend]
  (println "\n=== parallel-demo (two parallel LLM regions) ===")
  (let [r (run-chart! {:chart       pd/agent
                       :tag         "parallel"
                       :backend     backend
                       :initial-data {:phrase  "Good morning."
                                      :passage "Cats prefer cardboard boxes to expensive toys, much to their owners' chagrin."}})
        {:keys [rows ev-summary final-config]} r
        ev-names    (set (assistant-event-tool-calls rows))
        config-strs (set (mapv str final-config))
        finished?   (contains? config-strs ":finished")
        passes (+ (check "parallel" (contains? ev-names "translated") "saw :translated event")
                  (check "parallel" (contains? ev-names "summarized") "saw :summarized event")
                  (check "parallel" finished? (str "final config = " (pr-str final-config))))]
    (println "  transcript:" (:transcript r))
    {:tag "parallel" :passes passes :total 3}))

(defn -main [& _]
  (let [api-key (System/getenv "ZAI_API_KEY")]
    (when (str/blank? api-key)
      (println "[skip] ZAI_API_KEY is not set — nothing to do. Exit 0.")
      (System/exit 0))
    (println "Running M6 live smoke against" base-url "model" model)
    (let [backend (make-backend api-key)
          results [(try (run-hello! backend) (catch Throwable t {:tag "hello" :passes 0 :total 5 :error (.getMessage t)}))
                   (try (run-scan! backend)  (catch Throwable t {:tag "scan"  :passes 0 :total 3 :error (.getMessage t)}))
                   (try (run-parallel! backend) (catch Throwable t {:tag "parallel" :passes 0 :total 3 :error (.getMessage t)}))]
          total-pass (reduce + (map :passes results))
          total      (reduce + (map :total results))]
      (println "\n=== M6 Live Smoke Summary ===")
      (doseq [r results]
        (println (format "  %-10s %d/%d %s" (:tag r) (:passes r) (:total r)
                         (if (:error r) (str "ERROR: " (:error r)) ""))))
      (println (format "  TOTAL      %d/%d" total-pass total))
      (if (= total-pass total)
        (do (println "PASS") (System/exit 0))
        (do (println "FAIL") (System/exit 1))))))

(when (= *file* (str (System/getProperty "babashka.file")))
  (-main))
