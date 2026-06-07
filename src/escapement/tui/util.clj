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

(defn artifacts-dir
  "The `<session-dir>/artifacts` File for `session-dir`, or nil when session-dir
   is nil."
  [session-dir]
  (when session-dir (io/file (str session-dir "/artifacts"))))

(defn list-all-artifacts
  "Every regular file in `<session-dir>/artifacts`, sorted by name, as a vector of
   `{:name :path :size}` (path is absolute, size in bytes). [] when the directory
   is absent/unreadable."
  [session-dir]
  (try
    (let [d (artifacts-dir session-dir)]
      (if (and d (.exists d) (.isDirectory d))
        (->> (.listFiles d)
          (filter #(.isFile ^java.io.File %))
          (sort-by #(.getName ^java.io.File %))
          (mapv (fn [^java.io.File f]
                  {:name (.getName f)
                   :path (.getAbsolutePath f)
                   :size (.length f)})))
        []))
    (catch Throwable _ [])))

(defn human-size
  "Format a byte count as a short human-readable string (B/KB/MB/GB)."
  [bytes]
  (let [b (long (or bytes 0))]
    (cond
      (< b 1024)             (str b " B")
      (< b (* 1024 1024))    (format "%.1f KB" (/ b 1024.0))
      (< b (* 1024 1024 1024)) (format "%.1f MB" (/ b (* 1024.0 1024)))
      :else                  (format "%.1f GB" (/ b (* 1024.0 1024 1024))))))

(defn osc52-seq
  "The OSC 52 escape sequence that sets the terminal clipboard (`c` selection) to
   `text`. Pure; the caller writes it to the terminal output stream."
  [text]
  (let [b64 (.encodeToString (java.util.Base64/getEncoder)
              (.getBytes (str text) "UTF-8"))]
    (str (char 27) "]52;c;" b64 (char 27) "\\")))
