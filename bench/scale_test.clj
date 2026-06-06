(ns scale-test
  "Local scale test. Runs ONE arm at ONE concurrency C, streams a realistic
  mock LLM (TTFT + per-token delay), and reports peak RSS / threads / CPU /
  latency. Run it before and after a change to compare; bump C until latency
  inflates or the process runs out of memory. See bench/README.md.

  Arms (identical mock timing; only the orchestration differs):
    sc-ckpt  — real escapement.lib/run + disk FileBackedStore (production)
    sc-mem   — real escapement.lib/run + in-memory store (no checkpoint I/O)
    hand     — hand-written K-turn loop, NO statechart / queue / checkpoint

  Usage:
    bb bench/scale_test.clj <arm> <C> <turns> <ttft-ms> <tokens> <tok-ms> <qsleep> <state-kb>
  e.g.
    bb bench/scale_test.clj sc-ckpt 500 6 400 150 20 50 0

  The 8th positional arg `state-kb` (default 0) seeds each session's data-model
  with an opaque ~state-kb KB payload so every checkpoint snapshot is realistically
  large; the chart also GROWS state by one chunk per turn, exposing the
  full-snapshot O(N^2) write cost. Can also be set via the SCALE_STATE_KB env.

  Thread model:
    SCALE_VT=1  — drive sessions on a virtual-thread-per-task executor AND set
                  -Descapement.virtual-threads=true so escapement's own worker
                  threads (transcript-writer, llm-conv worker) are virtual too.
                  This is the Loom arm (JVM only; bb stays platform threads).
    default     — fixed platform thread pool of size C."
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :refer [final script state transition]]
    [com.fulcrologic.statecharts.promise :as p]
    [com.fulcrologic.statecharts.protocols :as sp]
    [clojure.string :as str]
    [escapement.chart.helpers :as h]
    [escapement.engine.store :as store]
    [escapement.lib :as lib]
    [escapement.llm.protocol :as llm]
    [escapement.tools.protocol :as tp]
    [taoensso.timbre :as log])
  (:import
    (java.util.concurrent Executors TimeUnit)
    (java.util.concurrent.atomic AtomicLong)))

;; ---------------------------------------------------------------------------
;; Realistic streaming mock backend
;; ---------------------------------------------------------------------------

(defn- stream! [on-delta {:keys [ttft-ms tokens tok-ms]} delta-count]
  (when (pos? ttft-ms) (Thread/sleep (long ttft-ms)))
  (dotimes [_ tokens]
    (when on-delta (on-delta {:type :text-delta :text "tok "}))
    (.incrementAndGet ^AtomicLong delta-count)
    (when (pos? tok-ms) (Thread/sleep (long tok-ms)))))

(defrecord RealisticMock [timing delta-count]
  llm/LLMBackend
  (send-turn [this request] (llm/stream-turn this request (fn [_] nil)))
  llm/StreamingLLMBackend
  (stream-turn [_ _request on-delta]
    (stream! on-delta timing delta-count)
    ;; Always ask the chart to continue; the chart's turn counter decides when
    ;; to stop (so the loop is driven by statechart transitions, not the model).
    (p/do!
      {:stop-reason :tool_use
       :content     [{:type :tool_use :id "u" :name "event__continue" :input {}}]
       :usage       {:input-tokens 1 :output-tokens 1} :model "mock"})))

;; ---------------------------------------------------------------------------
;; Stateful K-turn loop chart (real states / transitions / data-model assigns)
;;   :turn   -> llm-conversation (one streamed turn) --:continue--> :decide
;;   :decide -> eventless: n>=K -> :finished ; n<K -> :turn
;; ---------------------------------------------------------------------------

;; A fixed ~1 KB chunk; conj'd into the data-model each turn so snapshots grow.
(def ^:private chunk-1kb (apply str (repeat 1024 \x)))

(defn make-chart [turns]
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :turn}
      (state {:id :turn}
        (h/llm-conversation
          {:id                   "conv"
           :system               "loop"
           :stream?              true
           :real-tools           []
           :allowed-events       [{:event :continue :data-schema [:map]}]
           :initial-user-message "go"})
        (transition {:event :continue :target :decide}
          (script {:expr (fn [_env data]
                           [(ops/assign :n (inc (long (:n data 0))))
                            ;; grow state by one chunk per turn (O(N^2) snapshots)
                            (ops/assign :grow (conj (vec (:grow data)) chunk-1kb))])})))
      (state {:id :decide}
        (transition {:cond   (fn [_env data] (>= (long (:n data 0)) (long turns)))
                     :target :finished})
        (transition {:cond   (fn [_env data] (< (long (:n data 0)) (long turns)))
                     :target :turn}))
      (final {:id :finished}))))

;; ---------------------------------------------------------------------------
;; Arms
;; ---------------------------------------------------------------------------

(defn mem-store []
  (let [cache (atom {})]
    (reify sp/WorkingMemoryStore
      (get-working-memory [_ _ sid] (get @cache sid))
      (save-working-memory! [_ _ sid w] (swap! cache assoc sid w) nil)
      (delete-working-memory! [_ _ sid] (swap! cache dissoc sid) nil))))

;; ~`kb` KB opaque payload seeded into the data-model so every checkpoint
;; snapshot is realistically large.
(defn- big-payload [kb]
  (when (pos? (long kb))
    {:payload (vec (repeat (long kb) chunk-1kb))}))

(defn run-statechart-session [{:keys [chart backend ckpt? ckpt-root i qsleep state-kb]}]
  (lib/run
    (cond-> {:chart              chart
             :session-id         (keyword (str "s" i))
             :credentials        [{:provider :anthropic :api-key "sk-unused"}]
             :tool-registry      (tp/new-registry)
             :backend            backend
             :quiescent-sleep-ms qsleep
             :quiet?             true}
      (some-> state-kb long pos?) (assoc :initial-data (big-payload state-kb))
      (not ckpt?) (assoc :store (mem-store))
      ckpt?       (assoc :checkpoint-dir (str ckpt-root "/ck-" i)
                         :transcript-path (str ckpt-root "/tx-" i ".jsonl")))))

(defn run-hand-session [{:keys [backend turns]}]
  ;; Plain optimized loop: same K turns, same streamed mock, same state
  ;; transitions expressed as code. No statechart, queue, or checkpoint.
  (loop [n 0 messages []]
    (if (>= n (long turns))
      {:n n :messages messages}
      (let [acc      (StringBuilder.)
            on-delta (fn [d] (.append acc ^String (:text d)))
            _resp    @(llm/stream-turn backend {:messages messages} on-delta)
            messages' (conj messages
                        {:role :assistant :content (.toString acc)}
                        {:role :user :content "continue"})]
        (recur (inc n) messages')))))

;; ---------------------------------------------------------------------------
;; /proc sampling (Linux; works for the babashka native binary)
;; ---------------------------------------------------------------------------

(defn- read-proc [path]
  ;; bb's slurp fails on /proc (reported size 0); Files/readAllLines works.
  (java.nio.file.Files/readAllLines
    (.toPath (clojure.java.io/file path))))

(defn- proc-status-kv [k]
  (some (fn [l] (when (str/starts-with? l (str k ":"))
                  (-> l (str/split #"\s+") second parse-long)))
    (read-proc "/proc/self/status")))

(defn- cpu-ticks []
  ;; comm (field 2) may contain spaces/parens; parse after the last ")".
  (let [raw   (first (read-proc "/proc/self/stat"))
        after (subs raw (inc (str/last-index-of raw ")")))
        f     (str/split (str/trim after) #"\s+")]
    ;; post-")" tokens: idx0=state(field3) ... utime=field14 -> idx11, stime->idx12
    (+ (parse-long (nth f 11)) (parse-long (nth f 12)))))

(defn- sampler [stop? peak-rss-kb peak-threads]
  (doto (Thread.
          (fn [] (while @stop?
                   (when-let [r (proc-status-kv "VmRSS")]
                     (swap! peak-rss-kb max r))
                   (when-let [t (proc-status-kv "Threads")]
                     (swap! peak-threads max t))
                   (Thread/sleep 25))))
    (.setDaemon true) (.start)))

;; ---------------------------------------------------------------------------
;; Driver
;; ---------------------------------------------------------------------------

(defn run-arm [{:keys [arm c turns ttft tokens tok-ms qsleep state-kb]}]
  (let [timing      {:ttft-ms ttft :tokens tokens :tok-ms tok-ms}
        delta-count (AtomicLong. 0)
        backend     (->RealisticMock timing delta-count)
        chart       (make-chart turns)
        ckpt-root   (str "/tmp/scale-" (System/currentTimeMillis))
        ckpt?       (= arm "sc-ckpt")
        latencies   (atom [])
        vt?         (= "1" (System/getenv "SCALE_VT"))
        pool        (if vt?
                      (Executors/newVirtualThreadPerTaskExecutor)
                      (Executors/newFixedThreadPool c))
        task        (fn [i]
                      (reify java.util.concurrent.Callable
                        (call [_]
                          (let [t0 (System/nanoTime)]
                            (try
                              (case arm
                                "hand"   (run-hand-session {:backend backend :turns turns})
                                ("sc-ckpt" "sc-mem")
                                (run-statechart-session
                                  {:chart chart :backend backend
                                   :ckpt? ckpt? :ckpt-root ckpt-root :i i
                                   :qsleep qsleep :state-kb state-kb}))
                              (swap! latencies conj (/ (- (System/nanoTime) t0) 1e6))
                              :ok
                              (catch Throwable t {:err (.getMessage t)}))))))
        peak-rss    (atom 0) peak-thr (atom 0) stop? (atom true)
        _           (sampler stop? peak-rss peak-thr)
        cpu0        (cpu-ticks)
        t0          (System/nanoTime)
        results     (mapv #(.get %) (.invokeAll pool (mapv task (range c))))
        wall-ms     (/ (- (System/nanoTime) t0) 1e6)
        cpu-sec     (/ (- (cpu-ticks) cpu0) 100.0)]
    (.shutdown pool)
    (.awaitTermination pool 1 TimeUnit/MINUTES)
    (reset! stop? false)
    (let [errs   (filter map? results)
          oks    (count (filter #(= :ok %) results))
          lats   (sort @latencies)
          nlat   (count lats)
          pct    (fn [p] (if (zero? nlat) 0 (long (nth lats (min (dec nlat) (long (* p nlat)))))))
          turns-done (* oks turns)
          nominal-ms (+ ttft (* tokens tok-ms))]   ; ideal single-turn stream time
      {:arm           arm
       :vt            vt?
       :state-kb      (long (or state-kb 0))
       :C             c
       :ok            oks
       :errors        (count errs)
       :err-sample    (first (map :err errs))
       :wall-ms       (long wall-ms)
       :turns-total   turns-done
       :nominal-turn-ms nominal-ms
       :p50-session-ms (pct 0.50)
       :p99-session-ms (pct 0.99)
       :latency-infl  (when (pos? turns) (format "%.2fx"
                        (/ (double (pct 0.50)) (max 1 (* turns nominal-ms)))))
       :cpu-sec       (format "%.1f" cpu-sec)
       :cpu-ms/turn   (when (pos? turns-done) (format "%.2f" (/ (* cpu-sec 1000.0) turns-done)))
       :peak-rss-mb   (long (/ @peak-rss 1024.0))
       :rss-kb/sess   (when (pos? c) (long (/ @peak-rss c)))
       :peak-threads  @peak-thr
       :deltas        (.get delta-count)})))

(defn -main [& args]
  (log/set-min-level! :warn)
  (let [arm    (or (nth args 0 nil) "sc-mem")
        c      (parse-long (or (nth args 1 nil) "100"))
        turns  (parse-long (or (nth args 2 nil) "6"))
        ttft   (parse-long (or (nth args 3 nil) "400"))
        tokens (parse-long (or (nth args 4 nil) "150"))
        tok-ms (parse-long (or (nth args 5 nil) "20"))
        qsleep (parse-long (or (nth args 6 nil) "50"))
        state-kb (parse-long (or (nth args 7 nil) (System/getenv "SCALE_STATE_KB") "0"))
        res    (run-arm {:arm arm :c c :turns turns :ttft ttft :tokens tokens :tok-ms tok-ms
                         :qsleep qsleep :state-kb state-kb})]
    (println "RESULT" (pr-str res))
    (doseq [[k v] (sort-by key res)] (println (format "%-18s %s" (str k) v)))))

(apply -main *command-line-args*)
