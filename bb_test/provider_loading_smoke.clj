(ns provider-loading-smoke
  "Run one case per fresh JVM via bb provider-loading-smoke. No provider calls
   or credential reads: constructors only; the auth case resolves without calling."
  (:require
    [clojure.test :refer [deftest is run-tests]]
    [escapement.llm.providers :as providers]))

(def cases
  {"api" ['escapement.llm.api #(providers/build-api-backend {}) record?]
   "openai" ['escapement.llm.openai #(providers/build-openai-backend {}) record?]
   "codex" ['escapement.llm.openai-codex
            #(providers/build-codex-backend {:allow-stored-auth? false}) record?]
   "claude-cli" ['escapement.llm.claude-cli
                 #(providers/build-claude-cli-backend {}) record?]
   "multi" ['escapement.llm.multi #(providers/build-multi-backend {:routes []}) record?]
   "quirks" ['escapement.llm.model-quirks providers/opencode-go-quirks vector?]
   "auth" ['escapement.llm.openai-codex.auth
           #(requiring-resolve 'escapement.llm.openai-codex.auth/load-auth!)
           #(and (var? %) (bound? %) (fn? @%))]})

(deftest concurrent-cold-loading
  (let [[target load! valid?] (or (get cases (first *command-line-args*))
                              (throw (ex-info "Unknown smoke case" {})))
        ready (atom 0)
        start (promise)]
    (is (nil? (find-ns target)) (str target " must be cold"))
    (let [workers (mapv (fn [_]
                          (future
                            (swap! ready inc)
                            @start
                            (try (load!) (catch Throwable t t))))
                    (range 8))]
      (try
        (let [deadline (+ (System/currentTimeMillis) 10000)]
          (loop []
            (when (and (< @ready 8) (< (System/currentTimeMillis) deadline))
              (Thread/sleep 1)
              (recur))))
        (is (= 8 @ready) "all eight workers must be waiting before release")
        (deliver start true)
        (doseq [worker workers]
          (let [result (deref worker 60000 ::timeout)]
            (is (valid? result) (str target ": " result))))
        (finally
          (deliver start true)
          (doseq [worker workers] (future-cancel worker)))))))

(let [{:keys [fail error]} (run-tests 'provider-loading-smoke)]
  (shutdown-agents)
  (System/exit (if (zero? (+ fail error)) 0 1)))
