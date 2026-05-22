(ns load-test
  "Concurrency load test for the Escapement runner.

  Drives the `hello` chart through `escapement.lib/run` in N parallel
  threads against a *streaming mock backend* (no real API). Instruments the
  working-memory store to measure checkpoint write cost, and compares the
  real disk-backed store against an in-memory store to isolate how much of
  the cost is checkpoint I/O.

  Run:  bb bench/load_test.clj [N] [store=disk|mem] [tokens-per-turn] [token-sleep-ms]"
  (:require
    [clojure.string :as str]
    [com.fulcrologic.statecharts.protocols :as sp]
    [taoensso.timbre :as log]
    [escapement.engine.store]
    [escapement.examples.hello :as hello]
    [escapement.lib :as lib]
    [escapement.llm.protocol :as llm]
    [escapement.tools.protocol :as tp])
  (:import
    (java.util.concurrent Executors TimeUnit)
    (java.util.concurrent.atomic AtomicLong)))

;; ---------------------------------------------------------------------------
;; Streaming mock backend — stateless; decides from the request shape.
;;   - first turn (no tool_result present)  -> stream `tokens` text deltas,
;;     then return a tool_use for event__done.
;;   - after tool_result present            -> end_turn.
;; ---------------------------------------------------------------------------

(defn- has-tool-result? [request]
  (boolean
    (some (fn [m]
            (some #(= :tool_result (:type %)) (let [c (:content m)]
                                                (if (sequential? c) c []))))
      (:messages request))))

(defrecord StreamMock [tokens token-sleep-ms delta-count]
  llm/LLMBackend
  (send-turn [this request] (llm/stream-turn this request (fn [_] nil)))
  llm/StreamingLLMBackend
  (stream-turn [_ request on-delta]
    (if (has-tool-result? request)
      {:stop-reason :end_turn
       :content     [{:type :text :text "done"}]
       :usage       {} :model "mock"}
      (do
        (dotimes [_ tokens]
          (on-delta {:type :text-delta :text "tok "})
          (.incrementAndGet ^AtomicLong delta-count)
          (when (pos? token-sleep-ms)
            (Thread/sleep (long token-sleep-ms))))
        {:stop-reason :tool_use
         :content     [{:type :tool_use :id "u1" :name "event__done"
                        :input {:greeting "hi"}}]
         :usage       {} :model "mock"}))))

;; ---------------------------------------------------------------------------
;; Instrumented stores
;; ---------------------------------------------------------------------------

(defn instrumented-store
  "Wrap a store, accumulating save count + total save nanos + max wmem size."
  [inner save-count save-nanos]
  (reify sp/WorkingMemoryStore
    (get-working-memory [_ env sid] (sp/get-working-memory inner env sid))
    (save-working-memory! [_ env sid wmem]
      (let [t0 (System/nanoTime)
            r  (sp/save-working-memory! inner env sid wmem)]
        (.incrementAndGet ^AtomicLong save-count)
        (.addAndGet ^AtomicLong save-nanos (- (System/nanoTime) t0))
        r))
    (delete-working-memory! [_ env sid] (sp/delete-working-memory! inner env sid))))

(defn mem-store
  "In-memory-only store: single shared cache atom, no disk. Mirrors the
   FileBackedStore cache semantics minus the EDN-serialize + atomic-rename."
  []
  (let [cache (atom {})]
    (reify sp/WorkingMemoryStore
      (get-working-memory [_ _ sid] (get @cache sid))
      (save-working-memory! [_ _ sid wmem] (swap! cache assoc sid wmem) nil)
      (delete-working-memory! [_ _ sid] (swap! cache dissoc sid) nil))))

;; ---------------------------------------------------------------------------
;; Driver
;; ---------------------------------------------------------------------------

(defn run-load [{:keys [n store-kind tokens token-sleep-ms quiet?]}]
  (let [delta-count (AtomicLong. 0)
        save-count  (AtomicLong. 0)
        save-nanos  (AtomicLong. 0)
        backend     (->StreamMock tokens token-sleep-ms delta-count)
        ckpt-root   (str "/tmp/escapement-load-" (System/currentTimeMillis))
        inner-for   (fn [sid]
                      (case store-kind
                        :mem  (mem-store)
                        :disk (escapement.engine.store/new-store (str ckpt-root "/" sid))))
        thread-peak (AtomicLong. 0)
        pool        (Executors/newFixedThreadPool (min n 512))
        tasks       (mapv
                      (fn [i]
                        (fn []
                          (lib/run
                            {:chart           hello/agent
                             :session-id      (keyword (str "load-" i))
                             :credentials     [{:provider :mock}]
                             :tool-registry   (tp/new-registry)
                             :backend         backend
                             :store           (instrumented-store (inner-for i) save-count save-nanos)
                             :checkpoint-dir  (str ckpt-root "/ck-" i)
                             :transcript-path (str ckpt-root "/tx-" i ".jsonl")
                             :quiet?          (boolean quiet?)})))
                      (range n))
        rt          (Runtime/getRuntime)
        sampling?   (atom true)
        sampler     (doto (Thread.
                            (fn [] (while @sampling?
                                     (let [tc (Thread/activeCount)]
                                       (loop [] (let [p (.get thread-peak)]
                                                  (when (and (> tc p) (not (.compareAndSet thread-peak p tc))) (recur)))))
                                     (Thread/sleep 3))))
                      (.setDaemon true) (.start))
        t0          (System/nanoTime)]
    (.invokeAll pool (map (fn [f] (reify java.util.concurrent.Callable (call [_] (f)))) tasks))
    (.shutdown pool)
    (.awaitTermination pool 5 TimeUnit/MINUTES)
    (reset! sampling? false)
    (let [wall-ms (/ (- (System/nanoTime) t0) 1e6)
          saves   (.get save-count)
          heap-mb (/ (- (.totalMemory rt) (.freeMemory rt)) 1048576.0)]
      {:n              n
       :store          store-kind
       :tokens         tokens
       :wall-ms        (long wall-ms)
       :sessions/sec   (format "%.1f" (/ n (/ wall-ms 1000.0)))
       :checkpoints    saves
       :ckpt/sess      (format "%.1f" (double (/ saves (max 1 n))))
       :avg-save-us    (format "%.1f" (/ (.get save-nanos) (max 1 saves) 1000.0))
       :total-save-ms  (long (/ (.get save-nanos) 1e6))
       :deltas         (.get delta-count)
       :thread-peak    (.get thread-peak)
       :heap-mb-after  (format "%.0f" heap-mb)})))

(defn -main [& args]
  (log/set-min-level! :warn)            ; server pattern: set ONCE, not per-run
  (let [n     (Integer/parseInt (or (first args) "100"))
        sk    (keyword (or (second args) "disk"))
        toks  (Integer/parseInt (or (nth args 2 nil) "50"))
        tslp  (Integer/parseInt (or (nth args 3 nil) "0"))
        quiet (= "quiet" (nth args 4 nil))
        res   (run-load {:n n :store-kind sk :tokens toks :token-sleep-ms tslp :quiet? quiet})]
    (println "\n=== load result ===")
    (doseq [[k v] res] (println (format "%-16s %s" (str k) v)))))

(apply -main *command-line-args*)
