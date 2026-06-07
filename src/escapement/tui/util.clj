(ns escapement.tui.util
  "Tiny shared helpers for the terminal-UI add-on tree. Pure, dependency-light
   (only clojure.string + java.text date formatting), so multiple renderer
   modules (live/log/phase/inspector/transcript) and the facade can share them
   without cross-requiring each other."
  (:require
    [clojure.java.io :as io]
    [clojure.pprint :as pp]
    [clojure.string :as str]
    [escapement.tui.compositor :as cmp]))

(defn short-invokeid
  "Strip a namespace-style prefix from `id` and cap at ~10 chars for the
   `[<invokeid>]` source tag."
  [id]
  (when id
    (let [s (str id)
          s (or (last (str/split s #"[/.]")) s)]
      (cmp/truncate s 10))))

(defn ts->hms
  "Format a unix-ms timestamp as HH:MM:SS in the local timezone."
  [ts]
  (let [ts  (or ts (System/currentTimeMillis))
        fmt (java.text.SimpleDateFormat. "HH:mm:ss")]
    (.format fmt (java.util.Date. ^long ts))))

(defn pretty
  "Pretty-print `x` to a string."
  [x]
  (try (with-out-str (pp/pprint x))
       (catch Throwable _ (pr-str x))))

(defn session-dir-from-env [env]
  (get env :escapement/session-dir))

(defn list-artifacts
  "Returns a vector of artifact filenames in `session-dir`'s artifacts/ that
   match `<invokeid>.*`. Returns [] if the directory doesn't exist."
  [session-dir invokeid]
  (try
    (let [d (io/file (str session-dir "/artifacts"))]
      (if (and (.exists d) (.isDirectory d))
        (let [prefix (str invokeid)]
          (vec (sort (filter #(or (= % prefix)
                                (str/starts-with? % (str prefix ".")))
                       (.list d)))))
        []))
    (catch Throwable _ [])))
