(ns escapement.ui.replay-source-test
  "Agent-side proof (under `bb test`) of the disk→wire bridge that backs
   `escapement open <session-dir>` (the OpenTUI read-only replay path).

   Covers `escapement.ui.replay-source`:
     1. `disk-line->wire` — exact wire envelope (`kind:\"event\"` + fields),
        mirroring `ws-push/event->envelope` (so replay renders == live).
     2. `transcript->wire-lines` / `write-wire-file!` — one wire line per input
        transcript line, in `seq` order.
     3. `validate-session-dir` — success map for a valid dir; clear error maps
        for blank / missing-dir / not-a-dir / missing-transcript.

   bb-safe: cheshire + clojure.java.io + java.io.File only (no `*Deque`)."
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [escapement.ui.replay-source :as rs]
    [fulcro-spec.core :refer [=> assertions component specification]])
  (:import
    (java.io File)))

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- with-temp-dir
  "Make a fresh temp dir, run (f dir-File), recursively delete it after."
  [f]
  (let [d (File/createTempFile "escapement-replay-test-" "")]
    (.delete d)
    (.mkdirs d)
    (try
      (f d)
      (finally
        (doseq [^File c (reverse (file-seq d))] (.delete c))))))

(defn- write-transcript!
  "Write `rows` (vector of maps) one JSON object per line to `dir/transcript.jsonl`."
  [^File dir rows]
  (let [t (io/file dir rs/transcript-name)]
    (with-open [w (io/writer t)]
      (doseq [r rows]
        (.write w ^String (json/generate-string r))
        (.write w "\n")))
    t))

;; ===========================================================================
;; 1. disk-line->wire — exact envelope
;; ===========================================================================

(specification "disk-line->wire: transcript row -> exact wire envelope"
  (component "a delta row maps to {kind=event, seq, ts, event, data}"
    (let [row {:event "llm/delta" :seq 7 :ts 1234
               :data {:type "text-delta" :text "Hi" :invokeid "planner"}}
          env (rs/disk-line->wire row)]
      (assertions
        "the full envelope is exactly the five wire keys + values"
        env => {:kind  "event"
                :seq   7
                :ts    1234
                :event "llm/delta"
                :data  {:type "text-delta" :text "Hi" :invokeid "planner"}})))

  (component "a keyword :event is normalised to its wire string (no leading colon)"
    (assertions
      (:event (rs/disk-line->wire {:event :runner/started :seq 1 :ts 0 :data {}}))
      => "runner/started"))

  (component "when :data is absent, it falls back to the row minus envelope keys"
    (let [env (rs/disk-line->wire {:event "tool/started" :seq 2 :ts 5 :invokeid "p" :name "ls"})]
      (assertions
        "the residual fields become the data map"
        (:data env) => {:invokeid "p" :name "ls"}
        "envelope keys are not duplicated into data"
        (contains? (:data env) :event) => false)))

  (component ":ws/coalesced (if present) is surfaced under data"
    (assertions
      (get-in (rs/disk-line->wire {:event "llm/delta" :seq 1 :ts 0 :data {:text "x"}
                                   :ws/coalesced 3})
        [:data :coalesced])
      => 3)))

;; ===========================================================================
;; 2. temp-file writer — one wire line per input, seq order
;; ===========================================================================

(specification "write-wire-file!: one wire envelope per transcript line, in seq order"
  (with-temp-dir
    (fn [dir]
      (let [rows [{:event "runner/started" :seq 1 :ts 10 :data {}}
                  {:event "llm/delta"      :seq 2 :ts 11 :data {:text "a"}}
                  {:event "llm/delta"      :seq 3 :ts 12 :data {:text "b"}}
                  {:event "runner/done"    :seq 4 :ts 13 :data {}}]
            _      (write-transcript! dir rows)
            valid  (rs/validate-session-dir (.getAbsolutePath dir))
            out    (rs/write-wire-file! valid)
            lines  (->> (slurp out) str/split-lines (remove str/blank?))
            frames (mapv #(json/parse-string % true) lines)]
        (assertions
          "exactly one output line per input transcript line"
          (count lines) => (count rows)
          "envelopes preserve input (= seq) order"
          (mapv :seq frames) => [1 2 3 4]
          "events round-trip in order"
          (mapv :event frames) => ["runner/started" "llm/delta" "llm/delta" "runner/done"]
          "every frame is a wire envelope"
          (every? #(= "event" (:kind %)) frames) => true
          "delta text round-trips"
          (->> frames (filter #(= "llm/delta" (:event %))) (mapv #(get-in % [:data :text])))
          => ["a" "b"]
          "the temp file lives in the system temp dir, named escapement-replay-*"
          (boolean (re-find #"escapement-replay-.*\.jsonl$" out)) => true))))

  (component "write-wire-file! refuses an invalid (not :ok?) map"
    (assertions
      (try (rs/write-wire-file! {:ok? false :error :blank}) :no-throw
        (catch Exception _ :threw))
      => :threw)))

;; ===========================================================================
;; 3. validate-session-dir — success + clear errors
;; ===========================================================================

(specification "validate-session-dir: success + clear errors"
  (component "a dir containing transcript.jsonl validates (trailing slash tolerated)"
    (with-temp-dir
      (fn [dir]
        (write-transcript! dir [{:event "runner/started" :seq 1 :ts 0 :data {}}])
        (let [v (rs/validate-session-dir (str (.getAbsolutePath dir) "/"))]
          (assertions
            "ok? true"
            (:ok? v) => true
            ":dir is the absolute session dir (no trailing slash)"
            (:dir v) => (.getAbsolutePath dir)
            ":transcript points at the on-disk transcript.jsonl"
            (:transcript v) => (.getAbsolutePath (io/file dir rs/transcript-name)))))))

  (component "a blank/nil dir errors :blank with a clear message"
    (doseq [bad [nil "" "   "]]
      (let [v (rs/validate-session-dir bad)]
        (assertions
          "ok? false"
          (:ok? v) => false
          "error tagged :blank"
          (:error v) => :blank
          "a human message is present"
          (string? (:message v)) => true))))

  (component "a non-existent dir errors :missing-dir with the path in the message"
    (let [path "/tmp/escapement-definitely-does-not-exist-zzz-12345"
          v    (rs/validate-session-dir path)]
      (assertions
        (:ok? v) => false
        (:error v) => :missing-dir
        "message names the missing path"
        (boolean (re-find #"does not exist" (:message v))) => true)))

  (component "a file (not a dir) errors :not-a-dir"
    (let [^File f (File/createTempFile "escapement-not-a-dir-" ".txt")]
      (try
        (let [v (rs/validate-session-dir (.getAbsolutePath f))]
          (assertions
            (:ok? v) => false
            (:error v) => :not-a-dir))
        (finally (.delete f)))))

  (component "a dir with no transcript.jsonl errors :missing-transcript"
    (with-temp-dir
      (fn [dir]
        (let [v (rs/validate-session-dir (.getAbsolutePath dir))]
          (assertions
            (:ok? v) => false
            (:error v) => :missing-transcript
            "message names transcript.jsonl"
            (boolean (re-find #"transcript\.jsonl" (:message v))) => true))))))

;; ===========================================================================
;; 4. envelope parity with the live ws-push builder (the lock-step contract)
;; ===========================================================================

(specification "disk-line->wire mirrors ws-push/event->envelope (replay == live)"
  ;; require ws-push lazily so this ns stays dependency-light if ws-push is heavy;
  ;; it is on the bb test classpath (opentui_push_test.clj uses it directly).
  (let [event->envelope (requiring-resolve 'escapement.ui.ws-push/event->envelope)
        row             {:event "llm/delta" :seq 9 :ts 77
                         :data {:type "text-delta" :text "yo" :invokeid "p"}}
        live            (event->envelope 9 (update row :event keyword))
        replay          (rs/disk-line->wire row)]
    (assertions
      "the disk replay envelope equals the live WS envelope for the same event"
      replay => live)))
