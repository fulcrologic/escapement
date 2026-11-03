(ns escapement.recording
  "A recorded run, read back from its transcript: what it started with, every event
   that came from outside the chart (in the order processed), the AI answers each
   AI step got, the path it took and where it ended. A recording is plain EDN, so a
   real run can be saved as an eval case and replayed against another version of
   its chart (`escapement.recording.replay`).

       (from-file \"…/transcript.jsonl\" {:session-dir \"…\"})  ; or (from-rows rows)
       ;; => {:recording/version 1
       ;;     :chart-id \":wf.p/v1\" :session-id \"…\" :started-at ms
       ;;     :initial-data {…}
       ;;     :events [{:event :approval/answered :data {…} :at-ms 3600000} …]
       ;;     :ai {\"classify\" [{:response {…}} {:fail :timeout} …]}  ; scripted-backend shape
       ;;     :path [:inbox :classify …] :final [:done] :status :done}

   Transcript rows: the run's `:runner/started` row carries `:initial-data`;
   `:runner/event-processed` rows mark outside events `:external? true`; both also
   carry an EDN copy (`:initial-data-edn`, `:event-data-edn`) so a recording read
   back from the JSONL file keeps its keywords; each AI
   turn leaves an `:llm/response` row (or an `:llm/error`). Text in a transcript row
   is shortened; with `:session-dir` (or an `:artifact-store`) the full answer is
   read from the run's captured I/O."
  (:require
    [babashka.fs :as fs]
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [escapement.llm.protocol :as llm]
    [escapement.protocols :as proto]
    [escapement.storage.disk :as disk]))

(defn- kw [x]
  (cond (keyword? x) x
        (and (string? x) (str/starts-with? x ":")) (keyword (subs x 1))
        (string? x) (keyword x)
        :else x))

(defn read-transcript
  "Read a transcript JSONL file into rows (`:event` as a keyword)."
  [path]
  (with-open [r (clojure.java.io/reader (str path))]
    (into []
      (keep (fn [line]
              (when-not (str/blank? line)
                (let [row (json/parse-string line true)]
                  (update row :event kw)))))
      (line-seq r))))

(defn- edn-copy
  "The EDN copy of a value the runner writes beside it (`<k>-edn`), read back
   so keywords survive the JSONL file; else the value itself."
  [data k]
  (let [copy (get data (keyword (str (name k) "-edn")))]
    (if (string? copy)
      (try (edn/read-string {:default tagged-literal} copy) (catch Exception _ (get data k)))
      (get data k))))

(defn- blob [store session-id ref]
  (when (and store ref)
    (try (some-> (proto/read-artifact store session-id ref)
           (->> (edn/read-string {:default tagged-literal})))
         (catch Exception _ nil))))

(defn- answer
  "The scripted-backend reply that reproduces an `:llm/response` row."
  [{:keys [data]} store session-id]
  (let [content (or (some-> (blob store session-id (:io/ref data)) vec) (:content data))]
    {:response {:stop-reason (kw (:stop-reason data))
                :content     (mapv (fn [b] (-> b (update :type kw))) content)
                :usage       (or (:usage data) {})
                :model       (or (:model data) "recorded")}}))

(defn- failure
  "The scripted-backend reply that reproduces an `:llm/error` row."
  [{:keys [data]}]
  (let [reason (kw (:reason data))]
    (cond
      (contains? llm/error-categories reason) {:fail reason}
      (and reason (str/includes? (name reason) "verdict")) {:malformed true}
      :else {:fail :backend :recorded-reason reason})))

(defn from-rows
  "A recording from transcript `rows` (maps with `:event`, `:ts`, `:data`).
   `opts`: `:artifact-store` or `:session-dir` to read full AI answers."
  ([rows] (from-rows rows nil))
  ([rows {:keys [artifact-store session-dir]}]
   (let [store   (or artifact-store (when session-dir (disk/new-artifact-store session-dir)))
         started (first (filter #(and (= :runner/started (:event %)) (not (get-in % [:data :resume?]))) rows))
         ;; Offsets are on the RUN's clock when the rows say (`:clock-ms`, so a
         ;; test-clock run replays at its fake times), else on the wall clock.
         clock?  (some? (get-in started [:data :clock-ms]))
         at      (fn [{:keys [ts data]}] (if clock? (:clock-ms data) ts))
         start   (or (at started) (some->> (seq (keep :ts rows)) (apply min)) 0)
         sid     (get-in started [:data :session-id])
         ai      (reduce (fn [acc {:keys [event data] :as row}]
                           (case event
                             :llm/response (if (:one-shot? data)
                                             acc
                                             (update acc (str (:invokeid data)) (fnil conj [])
                                               (answer row store sid)))
                             :llm/error (update acc (str (:invokeid data)) (fnil conj []) (failure row))
                             acc))
                   {} rows)
         events  (into []
                   (comp (filter #(and (= :runner/event-processed (:event %)) (get-in % [:data :external?])))
                     (map (fn [{:keys [data] :as row}]
                            (let [t (at row)]
                              (cond-> {:event (kw (:event-name data)) :data (or (edn-copy data :event-data) {})}
                                t (assoc :at-ms (max 0 (- t start))))))))
                   rows)
         path    (into [] (keep #(when (= :chart/entered (:event %)) (kw (get-in % [:data :state])))) rows)
         finals  (keep #(when (and (= :chart/entered (:event %)) (get-in % [:data :final?]))
                          (kw (get-in % [:data :state]))) rows)
         done    (last (filter #(= :runner/done (:event %)) rows))
         cfg     (mapv kw (get-in done [:data :final-config]))]
     {:recording/version 1
      :chart-id          (get-in started [:data :chart-id])
      :session-id        sid
      :run-id            (get-in started [:data :run-id])
      :started-at        start
      :initial-data      (or (edn-copy (:data started) :initial-data) {})
      :events            events
      :ai                ai
      :path              path
      :final             (if (seq cfg) cfg (vec (take-last 1 finals)))
      :status            (kw (get-in done [:data :status]))})))

(defn from-file
  "A recording from a transcript JSONL file. `opts` as for `from-rows`; when the run's
   `:session-dir` is the transcript's directory, pass it to get full AI answers."
  ([path] (from-file path nil))
  ([path opts]
   (from-rows (read-transcript path) opts)))

(defn save!
  "Write `recording` as EDN to `path` (an eval case's recorded input)."
  [recording path]
  (fs/create-dirs (fs/parent (fs/absolutize path)))
  (spit (str path) (pr-str recording))
  path)

(defn load-recording
  "Read a recording saved with `save!`."
  [path]
  (edn/read-string {:default tagged-literal} (slurp (str path))))
