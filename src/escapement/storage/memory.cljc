(ns escapement.storage.memory
  "In-memory implementation of all of Escapement's IO protocols plus the library's
   `WorkingMemoryStore`. Backed by a single atom; nothing touches a filesystem, so it runs
   identically under bb, CLJ, and CLJS.

   Two roles:
     * the **stub backend** the protocol/resolver/replay tests run against (`io-refactor-plan.md`
       §8), and
     * a legitimate **ephemeral backend** for short-lived or test sessions.

   The store is dumb (per the protocol contract): it assigns `:transcript/seq` and nothing else.

   State shape (per session-id):
     {session-id {:seq       <next gapless seq, long>
                  :events    [event …]                 ; append order == seq order
                  :artifacts {path {:content <UTF-8 bytes> :meta m}}
                  :wmem      <working memory>
                  :summary   {…session summary fields…}}}"
  (:require
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
    [com.fulcrologic.statecharts :as-alias sc]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.protocols :as proto]
    [escapement.storage.common :as common]))

(defn- artifact-summary
  "Build the heavy-field-free summary map for the artifact stored at `path`. `content` is kept as
   the UTF-8 bytes written, so size and byte reads are exact whether it arrived as a string or bytes."
  [path {:keys [content meta]}]
  (common/artifact-summary path (common/byte-count content) {}
    (dissoc meta :artifact/path :artifact/size)))

(defn- store-artifact!
  "Store `content` bytes and `meta` at `path` for `session-id` in `state`, returning the summary."
  [state session-id path content meta]
  (let [entry {:content content :meta (or meta {})}]
    (swap! state assoc-in [session-id :artifacts path] entry)
    (artifact-summary path entry)))

(defn- matches-query?
  "True when `event` passes the `read-events` `query` predicates."
  [{:keys [types node-id from-seq to-seq]} event]
  (and (or (nil? types) (contains? types (:transcript/kind event)))
    (or (nil? node-id) (= node-id (:transcript/node-id event)))
    (or (nil? from-seq) (>= (:transcript/seq event) from-seq))
    (or (nil? to-seq) (<= (:transcript/seq event) to-seq))))

(defrecord MemoryStore [state]
  proto/TranscriptStore
  (append-event! [_ session-id event]
    ;; Assign the next gapless per-session seq inside the swap so concurrent appends can't
    ;; collide, then read the just-stored event back out of the new state.
    (-> (swap! state
          (fn [st]
            (let [s (get-in st [session-id :seq] 0)]
              (-> st
                (assoc-in [session-id :seq] (inc s))
                (update-in [session-id :events] (fnil conj [])
                  (assoc event :transcript/seq s))))))
      (get-in [session-id :events])
      peek))
  (read-events [_ session-id query]
    (let [events (get-in @state [session-id :events] [])
          kept   (if (seq query) (filterv #(matches-query? query %) events) events)]
      (if-let [limit (:limit query)]
        (vec (take limit kept))
        (vec kept))))

  proto/ArtifactStore
  (write-artifact! [_ session-id path content meta]
    (store-artifact! state session-id path (common/utf8-bytes content) meta))
  (read-artifact [_ session-id path]
    (some-> (get-in @state [session-id :artifacts path :content]) common/utf8-string))
  (list-artifacts [_ session-id]
    (->> (get-in @state [session-id :artifacts] {})
      (mapv (fn [[path entry]] (artifact-summary path entry)))
      (sort-by :artifact/path)
      vec))
  (write-artifact-bytes! [_ session-id path content meta]
    (store-artifact! state session-id path content (common/binary-meta meta)))
  (read-artifact-bytes [_ session-id path]
    (get-in @state [session-id :artifacts path :content]))

  proto/SessionIndex
  (list-sessions [_]
    (mapv (fn [[session-id s]]
            (assoc (:summary s) ::sc/session-id session-id))
      @state))

  sp/WorkingMemoryStore
  (get-working-memory [_ _env session-id]
    (get-in @state [session-id :wmem]))
  (save-working-memory! [_ _env session-id wmem]
    (swap! state assoc-in [session-id :wmem] wmem)
    nil)
  (delete-working-memory! [_ _env session-id]
    (swap! state update session-id dissoc :wmem)
    nil))

(>defn new-store
  "Create a fresh in-memory store implementing `TranscriptStore`, `ArtifactStore`,
   `SessionIndex`, and the library's `WorkingMemoryStore`."
  []
  [=> [:fn (partial instance? MemoryStore)]]
  (->MemoryStore (atom {})))

(>defn merge-session-summary!
  "Merge `summary` fields into the stored summary for `session-id`, so `list-sessions` can report
   them. The runner (and tests) call this to register identity/status the store can't infer on its
   own."
  [store session-id summary]
  [[:fn (partial instance? MemoryStore)] any? map? => :nil]
  (swap! (:state store) update-in [session-id :summary] merge summary)
  nil)
