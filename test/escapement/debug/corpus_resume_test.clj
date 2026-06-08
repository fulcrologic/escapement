(ns escapement.debug.corpus-resume-test
  "Verify the corrected fork/resume machinery against the REAL on-disk
   `.escapement/` session corpus (537 sessions, 7 with node-entry checkpoints).

   Three proofs, all against forks of throwaway COPIES of corpus sessions (the
   real corpus is strictly read-only):

   1. **Dead-branch reproduction + contrast.** `.escapement/multiplex.poets.2-branch-636ef2f4`
      is the one real fork shipped: a 3-line transcript
      (`runner/started(resume?=true)` → `runner/resumed config=[\"role-route\"]`
      → `runner/done`), zero LLM calls. Its seed came from the parent
      `6207c655…` poet child `multiplex.poets.0`'s `_musing__0.edn` — which (a) is
      EDN-POISONED (`:session/<uuid>` grandparent value) and (b) captured the
      WRONG pre-entry config `#{:role-route}` (no invoking state → never
      re-invokes). We reproduce BOTH failure modes against the real file, then
      show that a node-IN-config corpus checkpoint with the corrected machinery
      DOES re-invoke (mock backend) — the contrast.

   2. **Readable node-entry fork+resume re-invokes + advances.** The modern
      `3e7bc30e…` session uses the NEW EDN-safe encoding and has node-IN-config
      node-entry checkpoints (e.g. `multiplex.poets.2/haiku-1__5.edn`, config
      `#{:musing}`). Forking it and resuming with the real `poet-chart` registered
      re-invokes the conversation node and the sub-chart advances.

   3. **Poisoned-seed clean error.** A poisoned node-entry checkpoint surfaces the
      caught `:unreadable-checkpoint` ex-info from task 001 (store `read-edn-file`),
      never a raw \"Invalid token\" crash.

   Skips cleanly when `.escapement/` (or a specific corpus session) is absent."
  (:require
    [clojure.java.io :as io]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.promise :as p]
    [escapement.debug.branch :as branch]
    [escapement.engine.store :as store]
    [escapement.examples.haiku-tournament-dynamic :as haiku]
    [escapement.llm.protocol :as llm]
    [escapement.runner :as runner]
    [escapement.test-support :as ts]
    [escapement.tools.protocol :as tp]
    [fulcro-spec.core :refer [=> assertions specification behavior]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private corpus-root "/home/naomarik/github/escapement/.escapement")

;; The two corpus sessions this task pins:
;;   * 6207c655… — LEGACY `_`-prefixed node-entry dirs, POISONED (:session/<uuid>),
;;                 pre-entry `#{:role-route}` configs (the dead-branch source).
;;   * 3e7bc30e… — MODERN NEW-encoding, EDN-safe, node-IN-config checkpoints.
(def ^:private legacy-session "6207c655-a5dc-4e10-8fb0-6f0d6b29eae2")
(def ^:private modern-session "3e7bc30e-a28e-410b-a6ec-d974beedb4de")

(defn- corpus-present? [session]
  (.exists (io/file corpus-root session "checkpoints" "node-entries")))

(defn- tmp-dir [] (str (Files/createTempDirectory "corpus-resume" (into-array FileAttribute []))))

;; Mock backend — records calls so a test can assert the worker re-ran. The point
;; of every corpus fork here is whether the chart RE-INVOKES, not the model output.
(defrecord RecordingBackend [responses calls]
  llm/LLMBackend
  (send-turn [_ request]
    (swap! calls conj request)
    (p/do! (or (ts/pop-first! responses)
             {:stop-reason :end_turn
              :content     [{:type :text :text "mock"}]
              :usage       {:input-tokens 1 :output-tokens 1}
              :model       "mock"}))))

(defn- copy-session!
  "Copy a corpus session's checkpoints + transcript into a throwaway work-dir as
   `<work-dir>/<session>`. The real corpus is never written. Returns work-dir."
  [session]
  (let [work-dir (tmp-dir)
        src      (io/file corpus-root session)
        dst      (io/file work-dir session)]
    (.mkdirs dst)
    (doseq [^java.io.File f (file-seq src)
            :when (.isFile f)]
      (let [rel  (subs (.getPath f) (inc (count (.getPath src))))
            outf (io/file dst rel)]
        (.mkdirs (.getParentFile outf))
        (io/copy f outf)))
    work-dir))

(defn- transcript-rows [path]
  (when (.exists (io/file path))
    (with-open [r (io/reader path)] (doall (line-seq r)))))

;; --------------------------------------------------------------------------
;; Proof 1 + 3: the dead branch — poisoned seed → clean caught error.
;; --------------------------------------------------------------------------

(specification "the dead multiplex.poets.2-branch seed: poisoned legacy node-entry checkpoint surfaces a clean caught error (not a raw crash)"
  (if-not (corpus-present? legacy-session)
    (assertions "corpus absent — skipping cleanly" true => true)
    (let [work-dir (copy-session! legacy-session)
          ;; The dead branch's seed: parent poet child multiplex.poets.0, node
          ;; :musing visit 0 — the LEGACY `_multiplex.poets.0/_musing__0.edn`
          ;; file, which is EDN-POISONED (:session/<uuid> grandparent).
          parent-ck (str work-dir "/" legacy-session "/checkpoints")
          store     (store/new-store parent-ck)]
      (behavior "the legacy `_`-prefixed encoding resolves to the on-disk file"
        ;; If reconciliation failed we'd silently miss the file → nil → no error.
        (let [ex (try (store/node-entry-checkpoint store "multiplex.poets.0" "musing" 0)
                      nil
                      (catch Throwable t t))]
          (assertions
            "reading the poisoned legacy checkpoint throws (it was FOUND, not missed)"
            (some? ex) => true
            "and it is the CLEAN caught :unreadable-checkpoint error from task 001"
            (:reason (ex-data ex)) => :unreadable-checkpoint
            "naming the offending file"
            (boolean (re-find #"_musing__0\.edn" (:file (ex-data ex)))) => true)))
      (behavior "fork-session! at that branch-point surfaces the same clean error"
        (let [ex (try (branch/fork-session!
                        {:parent-session-id "multiplex.poets.0"
                         :branch-point      {:node-id "musing" :visit 0 :turn 0}
                         :work-dir          (str work-dir "/" legacy-session)
                         :parent-checkpoint-dir parent-ck})
                      nil
                      (catch Throwable t t))]
          (assertions
            "fork raises rather than producing a 3-line silent dead branch"
            (some? ex) => true
            "with the documented unreadable-checkpoint reason (root cause)"
            (->> ex ex-data :reason
              ((fn [r] (or (= r :unreadable-checkpoint)
                         ;; ex-info may wrap; check cause chain
                         (= :unreadable-checkpoint (:reason (ex-data (ex-cause ex)))))))) => true))))))

;; --------------------------------------------------------------------------
;; Proof 1 contrast + Proof 2: a node-IN-config corpus checkpoint RE-INVOKES.
;; --------------------------------------------------------------------------

(specification "readable corpus node-entry session forks, resumes, RE-INVOKES the conversation, and advances (vs. the recorded 3-line dead branch)"
  (if-not (corpus-present? modern-session)
    (assertions "corpus absent — skipping cleanly" true => true)
    (let [work-dir (copy-session! modern-session)
          parent-ck (str work-dir "/" modern-session "/checkpoints")
          ;; multiplex.poets.2/haiku-1__5.edn — config #{:musing}: the :musing
          ;; conversation node is IN the configuration (re-invokable). This is the
          ;; corrected node-in-config shape, unlike the dead branch's pre-entry
          ;; #{:role-route} seed.
          child-sid "multiplex.poets.2"
          node-id   "haiku-1"
          visit     5
          seed      (store/resolve-node-entry-wmem
                      (store/new-store parent-ck) {} child-sid node-id visit)]
      (assertions
        "sanity: the chosen seed reads cleanly and has the conversation node IN config"
        (:source seed) => :node-entry
        (boolean (seq (::sc/configuration (:wmem seed)))) => true
        "and names the poet sub-chart to resume"
        (::sc/statechart-src (:wmem seed)) => :escapement.examples.haiku-tournament-dynamic/poet)
      (let [branch  (branch/fork-session!
                      {:parent-session-id child-sid
                       :branch-point      {:node-id node-id :visit visit :turn 0}
                       :work-dir          (str work-dir "/" modern-session)
                       :parent-checkpoint-dir parent-ck})
            bcalls  (atom [])
            ;; The poet sub-chart pins model aliases (host-gpt, the pool, p-oc-kimi);
            ;; map them all to the mock provider so the re-invoked turns reach the
            ;; backend rather than failing alias resolution. (The point is RE-INVOKE,
            ;; not the model — any resolvable backend serves.)
            mock-aliases (into {} (map (fn [a] [a [{:provider :mock :model "mock"}]]))
                           [:host-gpt :p-oc-kimi :p-oc-qwen :p-glm47 :p-glm-turbo
                            :p-gemma1b :p-gemma270m])
            _       (runner/run!
                      {:chart              haiku/poet-chart
                       :chart-id           :escapement.examples.haiku-tournament-dynamic/poet
                       :session-id         (:branch-id branch)
                       :transcript-path    (:transcript-path branch)
                       :checkpoint-dir     (:checkpoint-dir branch)
                       :session-dir        (:session-dir branch)
                       :resume?            true
                       :backend            (->RecordingBackend (ts/queue []) bcalls)
                       :tool-registry      (tp/new-registry)
                       :llm-aliases        mock-aliases
                       :quiescent-sleep-ms 5})
            rows    (transcript-rows (:transcript-path branch))
            ;; states the chart ENTERED after the seed (proof of forward progress).
            entered (->> rows
                      (keep #(when (re-find #"\"event\":\"runner/event-processed\"" %) %))
                      (mapcat #(re-seq #"\"entered\":\[([^\]]*)\]" %))
                      (mapcat #(re-seq #"\"([^\"]+)\"" (second %)))
                      (map second)
                      set)]
        (assertions
          "the branch seeded from the node-entry checkpoint (node in config)"
          (:seed-source branch) => :node-entry
          "UNLIKE the recorded 3-line dead branch, resume RE-INVOKED the conversation (backend was called)"
          (pos? (count @bcalls)) => true
          "the branch logged an llm/start at the re-entered :musing node (worker actually ran)"
          (boolean (some #(re-find #"\"llm/start\".*musing" %) rows)) => true
          "the branch transcript is MORE than the dead branch's 3 silent rows"
          (> (count rows) 3) => true
          "and the sub-chart ADVANCED forward into downstream states (musing -> haiku-1 -> ... -> report)"
          (boolean (some entered ["haiku-1" "compose-route" "report"])) => true)))))

;; --------------------------------------------------------------------------
;; Proof 3 (explicit): poisoned-seed clean error via the store, store-level.
;; --------------------------------------------------------------------------

(specification "poisoned :session/<uuid> node-entry checkpoint yields the caught error, never a raw 'Invalid token' crash"
  (if-not (corpus-present? legacy-session)
    (assertions "corpus absent — skipping cleanly" true => true)
    (let [work-dir (copy-session! legacy-session)
          store    (store/new-store (str work-dir "/" legacy-session "/checkpoints"))
          ;; All 6207c655 node-entry files carry the :session/<uuid> grandparent.
          ;; poets.0's haiku-1 visit is 5 (legacy `_multiplex.poets.0/_haiku-1__5.edn`).
          ex       (try (store/node-entry-checkpoint store "multiplex.poets.0" "haiku-1" 5)
                        nil
                        (catch Throwable t t))]
      (assertions
        "a caught ex-info is thrown (not a raw reader crash)"
        (instance? clojure.lang.ExceptionInfo ex) => true
        "tagged :unreadable-checkpoint with the file path"
        (:reason (ex-data ex)) => :unreadable-checkpoint
        (string? (:file (ex-data ex))) => true))))
