(ns escapement.engine.store-roundtrip-test
  "Checkpoint EDN round-trip guarantee (resume robustness).

   Resume/fork is impossible if a checkpoint cannot be read back. The legacy
   session-id VALUE `:session/<uuid>` (a namespaced keyword with a digit-leading
   name) is `pr`-written but EDN-UNREADABLE (\"Invalid token\"). In-process runs
   survive because `FileBackedStore` caches working memory and never round-trips
   it; a cross-process resume/fork that reloads such a file hits the reader.

   This pins: (1) a poison session-id value either round-trips cleanly OR fails
   with a CAUGHT, clear `:unreadable-checkpoint` error (never a raw, opaque
   crash); (2) the EDN-safe `:session-<uuid>` form new code writes round-trips
   fine; (3) an optional scan of the real `.escapement/` corpus reports how many
   checkpoints are unreadable (the documented blast radius)."
  (:require
    [babashka.fs :as fs]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.engine.store :as store]
    [fulcro-spec.core :refer [=> assertions specification]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir [] (str (Files/createTempDirectory "store-rt" (into-array FileAttribute []))))

(specification "checkpoint round-trip: EDN-safe session-id forms round-trip cleanly"
  (let [dir   (tmp-dir)
        store (store/new-store dir)
        ;; The EDN-safe id form NEW sessions write (CLI: `:session-<uuid>`).
        sid   :session-5f3a1b2c
        wmem  {::sc/session-id    sid
               ::sc/configuration #{:run :talk}
               :data              {:k "v"}}]
    (sp/save-working-memory! store {} sid wmem)
    (store/reload-from-disk! store sid)
    (assertions
      "save -> reload-from-disk! -> get round-trips without throwing"
      (::sc/configuration (sp/get-working-memory store {} sid)) => #{:run :talk}
      "the EDN-safe session-id value survives the round-trip"
      (::sc/session-id (sp/get-working-memory store {} sid)) => sid)))

(specification "checkpoint round-trip: a poison :session/<uuid> value never raw-crashes a resume"
  (let [dir    (tmp-dir)
        store  (store/new-store dir)
        sid    :branch-1
        ;; The poison: a namespaced keyword whose NAME starts with a digit.
        poison (keyword "session" "5f3a1b2c-dead-beef")
        wmem   {::sc/session-id    poison
                ::sc/configuration #{:run :talk}}]
    ;; pr-writing the poison succeeds; reading it back is the failure mode.
    (sp/save-working-memory! store {} sid wmem)
    (store/reload-from-disk! store sid)
    (let [outcome (try
                    {:ok (sp/get-working-memory store {} sid)}
                    (catch clojure.lang.ExceptionInfo e
                      {:err (ex-data e)}))]
      (assertions
        "reading the poisoned checkpoint either succeeds OR raises a CAUGHT, clear :unreadable-checkpoint error (never a raw crash)"
        (boolean (or (contains? outcome :ok)
                   (= :unreadable-checkpoint (:reason (:err outcome))))) => true
        "the underlying token really is EDN-unreadable (documents the failure mode)"
        (try (edn/read-string (pr-str poison)) :readable
          (catch Throwable _ :unreadable)) => :unreadable))))

;; ---------------------------------------------------------------------------
;; Corpus scan (optional, gated): walk the real `.escapement/` checkpoints and
;; report how many fail to read. Documents the blast radius; does NOT fix the
;; legacy files. Skips cleanly when `.escapement/` is absent.
;; ---------------------------------------------------------------------------
(specification "corpus scan: report unreadable .escapement/ checkpoints (blast radius)"
  (let [root (fs/file ".escapement")]
    (if-not (fs/exists? root)
      (assertions ".escapement/ absent — corpus scan skipped" true => true)
      (let [edns      (->> (fs/glob root "**/*.edn") (map fs/file))
            results   (reduce
                        (fn [m f]
                          (try
                            (edn/read-string {:default (fn [_ v] v)} (slurp f))
                            (update m :readable inc)
                            (catch Throwable _
                              (-> m (update :unreadable inc)))))
                        {:readable 0 :unreadable 0}
                        edns)
            total     (+ (:readable results) (:unreadable results))]
        (println (format "[corpus-scan] .escapement/ checkpoints: %d total, %d readable, %d UNREADABLE"
                   total (:readable results) (:unreadable results)))
        (assertions
          "the scan ran over the corpus and produced a count (documentation, not a gate)"
          (>= total 0) => true)))))
