(ns escapement.engine.store
  "WorkingMemoryStore with atomic file-based checkpoint persistence.

  Each session's working memory is serialized as EDN and written via temp-file + atomic rename
  so that a crash mid-write cannot corrupt the canonical checkpoint file. On reload, the canonical
  file is read; if it does not exist the session is considered new.

  An in-memory cache mirrors the on-disk state so that hot reads (every event) don't hit the disk."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
    [com.fulcrologic.statecharts.protocols :as sp])
  (:import
    (java.nio.file CopyOption Files Path StandardCopyOption)))

(>defn ^:private session-file
  "Returns the canonical checkpoint file for `session-id` inside `dir`."
  [dir session-id]
  [:string [:or :keyword :string :uuid :symbol] => any?]
  (io/file dir (str (cond
                      (keyword? session-id) (subs (str session-id) 1)
                      :else (str session-id))
                 ".edn")))

(defn- ensure-dir! [^String dir]
  (let [d (io/file dir)]
    (when-not (.exists d) (.mkdirs d))))

(defn- ^Path as-path [f] (.toPath (io/file f)))

(defn- atomic-write-edn!
  "Serialize `value` as EDN, write to `<file>.tmp`, then atomically rename over `file`.
   The rename is performed with `ATOMIC_MOVE` and `REPLACE_EXISTING`."
  [file value]
  (ensure-dir! (.getParent (io/file file)))
  (let [tmp (io/file (str (.getPath ^java.io.File file) ".tmp"))]
    (with-open [w (io/writer tmp)]
      (binding [*out* w]
        (pr value)))
    (Files/move (as-path tmp) (as-path file)
      (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                              StandardCopyOption/REPLACE_EXISTING]))
    nil))

(defn- read-edn-file
  "Read an EDN checkpoint file, or `nil` if it does not exist.

   A checkpoint can be EDN-UNREADABLE even though it was `pr`-written: a
   namespaced keyword with a digit-leading name (e.g. the legacy session-id
   value `:session/5f3a…`) round-trips through `pr` but `clojure.edn/read`
   rejects it with \"Invalid token\". 650 canonical + 82 node-entry such files
   exist in the real `.escapement/` corpus. In-process runs survive because the
   `FileBackedStore` caches working memory and never round-trips it; only a
   cross-process resume or a fork that reloads such a file hits the reader.

   We translate any read failure into a CLEAR, CAUGHT `ex-info`
   (`:reason :unreadable-checkpoint`, with the file path) so a resume/fork
   reports an actionable error instead of an opaque mid-pump crash. New
   sessions this code writes are EDN-safe (the CLI uses the `:session-<uuid>`
   form), so this guards only legacy/poisoned files."
  [file]
  (when (.exists (io/file file))
    (let [s (slurp file)]
      (try
        (edn/read-string {:default tagged-literal} s)
        (catch Throwable t
          (throw (ex-info (str "Unreadable checkpoint (EDN read failed): " file)
                   {:reason :unreadable-checkpoint
                    :file   (str file)}
                   t)))))))

(defrecord FileBackedStore [dir cache]
  sp/WorkingMemoryStore
  (get-working-memory [_ _env session-id]
    (if-let [hit (get @cache session-id)]
      hit
      (when-let [v (read-edn-file (session-file dir session-id))]
        (swap! cache assoc session-id v)
        v)))
  (save-working-memory! [_ _env session-id wmem]
    (swap! cache assoc session-id wmem)
    (atomic-write-edn! (session-file dir session-id) wmem)
    nil)
  (delete-working-memory! [_ _env session-id]
    (swap! cache dissoc session-id)
    (let [f (session-file dir session-id)]
      (when (.exists f) (.delete f)))
    nil))

(>defn new-store
  "Create a new checkpoint store backed by `dir` (creating it if necessary)."
  [dir]
  [:string => any?]
  (ensure-dir! dir)
  (->FileBackedStore dir (atom {})))

(>defn reload-from-disk!
  "Drop the in-memory cache so the next read for `session-id` comes from disk (simulating a process restart)."
  [store session-id]
  [any? any? => :nil]
  (swap! (:cache store) dissoc session-id)
  nil)

;; ---------------------------------------------------------------------------
;; Node-entry checkpoints (time-travel debugger seam)
;; ---------------------------------------------------------------------------
;;
;; The canonical per-session checkpoint (above) is OVERWRITTEN on every event,
;; so it only ever holds the LATEST working memory — it cannot, by itself, locate
;; the working memory as it was when an LLM-conversation node was (re)entered.
;;
;; To make a re-enter-at-node restore locatable, we keep a SECONDARY, append-only
;; family of checkpoints keyed by `{node-id, visit}` under
;; `<checkpoint-dir>/node-entries/<session-id>/<node-id>__<visit>.edn`. Each file
;; is the EDN working memory (`::sc/configuration` + data model) snapshotted at the
;; moment that node visit was about to be entered. `node-id`/`visit` are the same
;; coordinates the capture layer stamps (`nodes/<node-id>/<visit>/…`, see
;; `capture.cljc`), so a UI selection of a conversation maps 1:1 to a snapshot.
;;
;; These are written via `save-node-entry-checkpoint!` and read by
;; `node-entry-checkpoint` / `resolve-node-entry-wmem`. They are pure inputs for
;; the branch fork (see `escapement.debug.branch`); nothing in the normal pump
;; path reads them, so a run without the debugger pays nothing.

(defn- ^String safe-segment
  "Filesystem-safe single path segment for an opaque id (node-id, session-id).
   node-ids/session-ids may contain characters illegal in file names; encode
   conservatively.

   A LEADING colon is stripped first so that a keyword id and its bare-name
   string form collapse to the SAME segment. This matters because the OpenTUI
   wire normalizes keyword names to colon-less strings (see `docs/opentui-wire.md`
   §2), so a node-entry checkpoint SAVED under the keyword `:multiplex.poets.4`
   (str `\":multiplex.poets.4\"`) must still be FOUND when a re-run looks it up
   via the wire string `\"multiplex.poets.4\"`. Without the strip the two forms
   encode to `_multiplex.poets.4` vs `multiplex.poets.4` and never match —
   the multi-session re-run \"No checkpoint to seed branch from\" bug. This
   matches `escapement.capture/encode-node-id`, which already drops the colon."
  [s]
  (-> (str s)
    (str/replace #"^:" "")
    (str/replace #"[^A-Za-z0-9._-]" "_")))

(defn- node-entry-file
  "Canonical file for the `{node-id, visit}` entry checkpoint of `session-id`."
  [dir session-id node-id visit]
  (io/file dir "node-entries"
    (safe-segment session-id)
    (str (safe-segment node-id) "__" (long visit) ".edn")))

(defn- ^String legacy-segment
  "OLD node-entry encoding: a leading colon was REPLACED by `_` (not stripped),
   so `:multiplex.poets.0` was stored as `_multiplex.poets.0` and `:musing` as
   `_musing`. The real `.escapement/` corpus has 6 of the 7 node-entry sessions
   in this form (see `de2f0082…`, `6207c655…`). The NEW `safe-segment` STRIPS the
   leading colon; a wire lookup for `\"multiplex.poets.0\"` would otherwise miss
   the legacy `_multiplex.poets.0` dir. We probe this form as a fallback so a real
   selection resolves whether the snapshot was written old- or new-style."
  [s]
  (-> (str s)
    (str/replace #"[^A-Za-z0-9._-]" "_")))

(defn- legacy-node-entry-file
  "Legacy (`_`-prefixed) file for the `{node-id, visit}` entry checkpoint."
  [dir session-id node-id visit]
  (io/file dir "node-entries"
    (legacy-segment session-id)
    (str (legacy-segment node-id) "__" (long visit) ".edn")))

(defn- resolve-node-entry-file
  "Locate the node-entry checkpoint file, preferring the NEW colon-stripped
   encoding and falling back to the LEGACY `_`-prefixed form when only the latter
   exists on disk. Returns the new-form file when neither exists (so callers can
   `read-edn-file` it → `nil`)."
  [dir session-id node-id visit]
  (let [new-f    (node-entry-file dir session-id node-id visit)
        legacy-f (legacy-node-entry-file dir session-id node-id visit)
        ;; The wire/lookup id is colon-LESS ("multiplex.poets.0"), but the LEGACY
        ;; segment was derived from the KEYWORD (:multiplex.poets.0 →
        ;; _multiplex.poets.0), i.e. an extra leading `_`. Probe that form too.
        kw-dir   (io/file dir "node-entries" (str "_" (legacy-segment session-id)))
        kw-f     (io/file kw-dir (str "_" (legacy-segment node-id) "__" (long visit) ".edn"))]
    (cond
      (.exists new-f)    new-f
      (.exists legacy-f) legacy-f
      (.exists kw-f)     kw-f
      :else              new-f)))

(>defn save-node-entry-checkpoint!
  "Persist `wmem` (a working-memory map: `::sc/configuration` + data model) as the
   restorable snapshot for re-entering conversation node `node-id` on its 0-based
   `visit` within `session-id`. Atomic temp-file + rename, like the canonical
   checkpoint. `store` must be a `FileBackedStore` (carries `:dir`)."
  [store session-id node-id visit wmem]
  [any? [:or :keyword :string :uuid :symbol] some? :int [:maybe map?] => :nil]
  (let [dir (:dir store)]
    (assert dir "save-node-entry-checkpoint! requires a FileBackedStore (with :dir)")
    (atomic-write-edn! (node-entry-file dir session-id node-id visit) wmem))
  nil)

(>defn node-entry-checkpoint
  "Return the working memory snapshotted at entry of conversation node `node-id`
   on `visit` within `session-id`, or `nil` if no such snapshot was written."
  [store session-id node-id visit]
  [any? [:or :keyword :string :uuid :symbol] some? :int => [:maybe map?]]
  (let [dir (:dir store)]
    (assert dir "node-entry-checkpoint requires a FileBackedStore (with :dir)")
    (read-edn-file (resolve-node-entry-file dir session-id node-id visit))))

(>defn resolve-node-entry-wmem
  "Resolve the working memory to RESTORE in order to re-enter conversation node
   `node-id` on `visit` within `session-id`.

   Resolution order:
     1. The explicit node-entry checkpoint for `{node-id, visit}`, if one was
        written (`save-node-entry-checkpoint!`). This is the precise snapshot.
     2. FALLBACK — the canonical latest session checkpoint
        (`get-working-memory`). This is only correct when the node in question
        is the LAST thing the run did (i.e. re-running the most recent
        conversation); for an earlier node it over-shoots. Callers that need a
        truly historical entry must rely on (1) having been written, or
        replay-forward from an earlier checkpoint to the node entry (a
        documented future extension — the statechart library has no rollback
        primitive, so 'replay forward' = restore an earlier wmem and pump the
        deterministic prefix). Returns `{:wmem … :source :node-entry|:latest}`,
        or `nil` if nothing is available."
  [store env session-id node-id visit]
  [any? map? [:or :keyword :string :uuid :symbol] some? :int => [:maybe map?]]
  (if-let [w (node-entry-checkpoint store session-id node-id visit)]
    {:wmem w :source :node-entry}
    (when-let [w (sp/get-working-memory store env session-id)]
      {:wmem w :source :latest})))
