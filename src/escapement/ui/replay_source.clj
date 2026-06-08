(ns escapement.ui.replay-source
  "Pure disk→wire bridge for opening a SAVED Escapement session directory in the
   OpenTUI sidecar (read-only replay).

   A persisted session dir holds `transcript.jsonl` — one bare-key JSON object per
   line, `{event, data, ts, seq}` (see `escapement.transcript` / `storage.disk-read`).
   The OpenTUI sidecar's offline `ReplaySource` (`tui/opentui/src/transport/replay.ts`)
   reads a JSONL fixture in the **wire-envelope** shape (`docs/opentui-wire.md` §8):
   one forward envelope per line — `{kind:\"event\", seq, ts, event, data}`.

   These differ only by the `\"kind\":\"event\"` wrapper the live WS push prepends.
   To make replay render IDENTICALLY to a live run we mirror the live envelope builder
   `escapement.ui.ws-push/event->envelope` exactly: same key set, same `kw->wire`
   semantics for the event name, same `data` fallback. We deliberately DO NOT depend on
   `ws-push` (it pulls http-kit + the phase model); this ns is dependency-light so it
   loads under `bb test`. Keep the two envelope builders in lock-step.

   Everything here is pure / bb-SCI-safe: cheshire (the codebase's JSON lib),
   `clojure.java.io`, and `java.io.File/createTempFile` (available under bb)."
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str])
  (:import
    (java.io File)))

(def ^:const transcript-name "transcript.jsonl")

;; -------------------------------------------------------------------------------------------------
;; Disk line -> wire envelope (mirror of escapement.ui.ws-push/event->envelope)
;; -------------------------------------------------------------------------------------------------

(defn- kw->wire
  "A keyword/string event name -> its wire string (name without leading colon,
   namespace kept with `/`). Mirrors `escapement.ui.ws-push/kw->wire`. On-disk
   `:event` is already a bare string (e.g. \"llm/delta\"), so this is usually a
   pass-through, but we keep parity for robustness."
  [k]
  (cond
    (keyword? k) (subs (str k) 1)
    (string? k)  k
    (nil? k)     nil
    :else        (str k)))

(defn disk-line->wire
  "Map ONE parsed transcript row (a JSON-parsed map with keyword keys, shape
   `{:event :data :ts :seq}`) to the OpenTUI forward wire envelope:

     {:kind \"event\" :seq <long> :ts <epoch-ms> :event <wire-string> :data <map>}

   Mirrors `escapement.ui.ws-push/event->envelope` so replay == live rendering:
   `data` falls back to the row minus the envelope keys when `:data` is absent,
   and `:ws/coalesced` (if ever present on disk) is surfaced under `data`."
  [row]
  (let [data (or (:data row) (dissoc row :event :seq :ts))]
    (cond-> {:kind  "event"
             :seq   (:seq row)
             :ts    (:ts row)
             :event (kw->wire (:event row))
             :data  data}
      (:ws/coalesced row) (assoc-in [:data :coalesced] (:ws/coalesced row)))))

;; -------------------------------------------------------------------------------------------------
;; Session-dir validation
;; -------------------------------------------------------------------------------------------------

(defn validate-session-dir
  "Validate that `dir` is a usable saved-session directory (tolerating a trailing
   slash). Returns a map:

     {:ok? true  :dir <abs-path> :transcript <abs-path>}                     ; valid
     {:ok? false :error <:missing-dir|:not-a-dir|:missing-transcript|:blank>
                 :dir <input> :message <human string>}                       ; invalid

   The error value carries enough for the CLI to print a clear message and exit
   non-zero WITHOUT spawning the sidecar."
  [dir]
  (cond
    (str/blank? (some-> dir str))
    {:ok? false :error :blank :dir dir
     :message "No session directory given."}

    :else
    (let [d (io/file (str/replace (str dir) #"/+$" ""))]
      (cond
        (not (.exists d))
        {:ok? false :error :missing-dir :dir (str dir)
         :message (str "Session directory does not exist: " (.getPath d))}

        (not (.isDirectory d))
        {:ok? false :error :not-a-dir :dir (str dir)
         :message (str "Not a directory: " (.getPath d))}

        :else
        (let [t (io/file d transcript-name)]
          (if (.isFile t)
            {:ok?        true
             :dir        (.getAbsolutePath d)
             :transcript (.getAbsolutePath t)}
            {:ok? false :error :missing-transcript :dir (.getAbsolutePath d)
             :message (str "No " transcript-name " found in session directory: "
                        (.getAbsolutePath d))}))))))

;; -------------------------------------------------------------------------------------------------
;; Temp wire-file writer
;; -------------------------------------------------------------------------------------------------

(defn transcript->wire-lines
  "Read `transcript-path` (a session's `transcript.jsonl`), parse every non-blank
   line, and return a lazy-realized vector of wire-envelope JSON STRINGS in input
   (= `seq`) order. Pure transform; no disk writes."
  [transcript-path]
  (with-open [r (io/reader (io/file transcript-path))]
    (into []
      (comp (remove str/blank?)
        (map #(json/parse-string % true))
        (map disk-line->wire)
        (map json/generate-string))
      (line-seq r))))

(defn write-wire-file!
  "Transform the validated session dir's transcript into a temp wire JSONL file
   that the OpenTUI `ReplaySource` can consume via `OPENTUI_REPLAY`, and return its
   absolute path.

   `valid` is the success map from `validate-session-dir` (must be `:ok? true`).
   The temp file is named `escapement-replay-*.jsonl` in the system temp dir and is
   marked `deleteOnExit` so it does not accumulate across runs.

   Output: one wire envelope (`disk-line->wire`) per input transcript line, in
   `seq` order, newline-terminated."
  [valid]
  (when-not (:ok? valid)
    (throw (ex-info "write-wire-file! requires a valid session dir"
             {:valid valid})))
  (let [^File tmp (File/createTempFile "escapement-replay-" ".jsonl")]
    (.deleteOnExit tmp)
    (with-open [w (io/writer tmp)]
      (doseq [line (transcript->wire-lines (:transcript valid))]
        (.write w ^String line)
        (.write w "\n")))
    (.getAbsolutePath tmp)))

(defn session-dir->wire-file
  "Convenience one-shot: validate `dir`, and if valid, write the temp wire file.
   Returns either the success map augmented with `:wire-file <abs-path>`, or the
   validation error map unchanged (so a caller can branch on `:ok?` and exit)."
  [dir]
  (let [v (validate-session-dir dir)]
    (if (:ok? v)
      (assoc v :wire-file (write-wire-file! v))
      v)))
