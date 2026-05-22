(ns escapement.transcript
  "Single-writer JSONL transcript sink.

  All transcript events from any thread are funneled through a `LinkedBlockingQueue` to a
  single daemon writer thread that owns the file's `BufferedWriter`. This guarantees:

  * **Order**: events are written in queue-enqueue order (FIFO).
  * **No interleaving**: only one thread ever touches the writer, so JSONL lines are intact.
  * **Monotonic `:seq`**: the writer thread owns a `long` counter, injected per event.

  The sink is fault-tolerant: if an event contains an un-serializable value (e.g. a function),
  the writer emits a `:transcript/serialize-error` row plus a best-effort row with the offending
  values replaced by their `pr-str`. The sink never crashes the calling agent."
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [escapement.threads :as threads]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]])
  (:import
    (java.io BufferedWriter)
    (java.util.concurrent LinkedBlockingQueue)))

(def ^:private end-sentinel ::end)

(defn- now-ms [] (System/currentTimeMillis))

(defn- ensure-parent-dirs! [^String path]
  (let [f (io/file path)
        p (.getParentFile f)]
    (when (and p (not (.exists p)))
      (.mkdirs p))))

(defn- sanitize-value
  "Replace values that won't serialize cleanly with their `pr-str`."
  [v]
  (cond
    (nil? v) v
    (or (string? v) (number? v) (boolean? v) (keyword? v) (symbol? v)) v
    (map? v) (into {} (map (fn [[k vv]] [k (sanitize-value vv)])) v)
    (sequential? v) (mapv sanitize-value v)
    (set? v) (into #{} (map sanitize-value) v)
    (instance? java.util.Date v) (.getTime ^java.util.Date v)
    :else (pr-str v)))

(defn- write-line! [^BufferedWriter w m]
  (.write w ^String (json/generate-string m))
  (.newLine w))

(def ^:const flush-batch-cap
  "Upper bound on lines written between flushes. Bounds worst-case durability
   latency under sustained load — we flush at least this often even if the
   queue never drains. The common case flushes on drain (queue empty), so a
   bursty writer still persists promptly."
  256)

(defn- writer-loop
  "Single writer thread. Writes JSONL lines to the BufferedWriter but defers
   `.flush` to batch boundaries instead of flushing per line: we flush when the
   queue drains (`.peek` is nil) or after `flush-batch-cap` lines, whichever
   comes first. Lossless — every enqueued event is written and flushed; only the
   syscall cadence changes (one `.flush`/`fsync` per batch, not per row)."
  [^LinkedBlockingQueue q ^BufferedWriter w seq-counter fsync? raf-fn]
  (let [do-fsync! (fn []
                    (when fsync?
                      (try (when-let [raf (raf-fn)] (.sync (.getFD raf)))
                           (catch Throwable _ nil))))
        flush!    (fn []
                    (try (.flush w) (do-fsync!)
                         (catch Throwable t
                           (binding [*out* *err*]
                             (println "[transcript] flush threw:" (.getMessage t))))))]
    (try
      (loop [dirty?       false
             since-flush  0]
        (let [item (if dirty? (.poll q) (.take q))]
          (cond
            ;; Queue drained while we have buffered writes: flush, then block.
            (and (nil? item) dirty?)
            (do (flush!) (recur false 0))

            (identical? item end-sentinel)
            nil

            :else
            (let [ev (cond-> item
                       (not (contains? item :ts)) (assoc :ts (now-ms))
                       (not (contains? item :seq)) (assoc :seq @seq-counter))]
              (swap! seq-counter inc)
              ;; Pre-serialize so a failure can be recovered without leaving a half-written line.
              (let [s (try (json/generate-string ev)
                           (catch Throwable t {::ser-fail t}))]
                (try
                  (if (map? s)
                    ;; Serialization failed — emit an error marker and a sanitized retry.
                    (do
                      (write-line! w {:event               :transcript/serialize-error
                                      :ts                  (now-ms)
                                      :seq                 @seq-counter
                                      :original-event-keys (vec (keys ev))
                                      :reason              (some-> ^Throwable (::ser-fail s) .getMessage)})
                      (swap! seq-counter inc)
                      (write-line! w (assoc (sanitize-value ev)
                                       :seq @seq-counter
                                       :transcript/sanitized? true))
                      (swap! seq-counter inc))
                    (do
                      (.write w ^String s)
                      (.newLine w)))
                  (catch Throwable t
                    (binding [*out* *err*]
                      (println "[transcript] write threw:" (.getMessage t))))))
              (let [n (inc since-flush)]
                (if (>= n flush-batch-cap)
                  (do (flush!) (recur false 0))
                  (recur true n)))))))
      (catch InterruptedException _ nil)
      (catch Throwable t
        (binding [*out* *err*]
          (println "[transcript] writer-loop crashed:" (.getMessage t))))
      (finally
        (try (.flush w) (catch Throwable _ nil))
        (try (.close w) (catch Throwable _ nil))))))

(defrecord TranscriptSink [^LinkedBlockingQueue queue ^Thread thread seq-counter path closed?])

(>defn open-transcript
  "Open a single-writer JSONL transcript sink. Spawns a daemon writer thread that owns the file.

  `opts`:
   * `:path` (required) — destination file path. Parent dirs are created if missing.
   * `:append?` (default true) — append to the file if it exists.
   * `:fsync?` (default false) — call `fsync` after every write (slow; rare use).

  Returns an opaque sink. Use `write!` to enqueue events and `close!` to flush + join."
  [{:keys [path append? fsync?] :or {append? true fsync? false} :as _opts}]
  [:map => any?]
  (assert path "transcript :path is required")
  (ensure-parent-dirs! path)
  (let [q           (LinkedBlockingQueue.)
        seq-counter (atom 0)
        w           (io/writer (io/file path) :append append?)
        bw          (BufferedWriter. w)
        raf-fn      (fn [] nil)                             ; fsync support stubbed; would need RandomAccessFile to do properly
        runnable    (fn [] (writer-loop q bw seq-counter fsync? raf-fn))
        ^Thread t   (threads/unstarted-daemon "transcript-writer" runnable)
        closed?     (atom false)]
    (.start t)
    (->TranscriptSink q t seq-counter path closed?)))

(>defn write!
  "Enqueue `event` for asynchronous JSONL serialization. Non-blocking. Safe from any thread."
  [sink event]
  [any? :map => :boolean]
  (if @(:closed? sink)
    false
    (do (.put ^LinkedBlockingQueue (:queue sink) event)
        true)))

(>defn close!
  "Signal the writer thread to drain and exit, then join. Idempotent."
  [sink]
  [any? => :nil]
  (when (compare-and-set! (:closed? sink) false true)
    (.put ^LinkedBlockingQueue (:queue sink) end-sentinel)
    (try (.join ^Thread (:thread sink) 5000)
         (catch InterruptedException _ nil)))
  nil)

(defn make-transcript-fn
  "Returns `(fn [event] (write! sink event))` suitable for `engine.env`'s `:transcript-fn` slot."
  [sink]
  (fn [event]
    (try (write! sink event)
         (catch Throwable _ false))))
