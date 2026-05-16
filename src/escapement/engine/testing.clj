(ns escapement.engine.testing
  "Bb-friendly test harness replacing `com.fulcrologic.statecharts.testing` (which crashes under bb).

  Drives a chart synchronously by polling the manual queue until quiescent. Test code injects
  mock `InvocationProcessor`s as needed; chart authors don't need to know they're in a test."
  (:require
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.data-model.operations :as ops]
   [com.fulcrologic.statecharts.protocols :as sp]
   [escapement.engine.env :as env])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir []
  (str (Files/createTempDirectory "dcch-test" (into-array FileAttribute []))))

(defn new-testing-env
  "Build a test env that registers `statechart` under id `:dcch.test/chart` and binds the given session id.

  Args:
    * `{:keys [statechart session-id checkpoint-dir]}` — `statechart` required.
    * `invocation-processors` (varargs) — instances of `InvocationProcessor` to install.

  Returns a map `{:env, :session-id, :chart-id}` for use with the rest of this harness."
  [{:keys [statechart session-id checkpoint-dir tool-registry session-dir]}
   & invocation-processors]
  (let [sid       (or session-id :dcch.test/session)
        chart-id  ::chart
        ;; Default session-dir = a sibling of checkpoint-dir, so artifacts and
        ;; checkpoints share one tmp tree per test run.
        ckpt-dir  (or checkpoint-dir (tmp-dir))
        sess-dir  (or session-dir ckpt-dir)
        env       (env/new-env (cond-> {:checkpoint-dir        ckpt-dir
                                        :session-dir           sess-dir
                                        :invocation-processors (vec invocation-processors)}
                                 tool-registry (assoc :tool-registry tool-registry)))]
    (sp/register-statechart! (::sc/statechart-registry env) chart-id statechart)
    {:env env :session-id sid :chart-id chart-id}))

(defn start!
  "Start the chart; return the testing-env (with starter wmem persisted).
   Optional second arg `initial-data` is passed as `::sc/invocation-data` so the
   chart's data model is seeded before `on-entry` runs (matches runner behavior)."
  ([t] (start! t nil))
  ([{:keys [env session-id chart-id] :as t} initial-data]
   (let [processor (::sc/processor env)
         store     (::sc/working-memory-store env)
         w0        (sp/start! processor env chart-id
                              (cond-> {::sc/session-id session-id}
                                (seq initial-data) (assoc ::sc/invocation-data initial-data)))]
     (sp/save-working-memory! store env session-id w0)
     t)))

(defn- pump-once!
  "Drain currently-deliverable events for `session-id` through the processor exactly once.
   Returns true if at least one event was processed."
  [{:keys [env session-id]}]
  (let [queue     (::sc/event-queue env)
        store     (::sc/working-memory-store env)
        processor (::sc/processor env)
        progressed? (atom false)]
    (sp/receive-events! queue env
                        (fn [_ event]
                          (reset! progressed? true)
                          (let [wmem  (sp/get-working-memory store env session-id)
                                wmem' (sp/process-event! processor env wmem event)]
                            (sp/save-working-memory! store env session-id wmem')))
                        {:session-id session-id})
    @progressed?))

(defn drain!
  "Pump until quiescent (no more events available). Returns the testing-env."
  ([t] (drain! t 1000))
  ([t max-iters]
   (loop [i max-iters]
     (cond
       (zero? i) (throw (ex-info "drain! exceeded max iterations" {:max max-iters}))
       (pump-once! t) (recur (dec i))
       :else t))))

(defn run-events!
  "Post each event onto the queue (as a chart-self send) then drain."
  [{:keys [env session-id] :as t} & events]
  (let [queue (::sc/event-queue env)]
    (doseq [e events]
      (let [m (if (keyword? e) {:name e} e)]
        (sp/send! queue env {:event             (:name m)
                             :data              (:data m {})
                             :target            session-id
                             :source-session-id session-id}))))
  (drain! t))

(defn wmem
  "Return the latest working memory for the session."
  [{:keys [env session-id]}]
  (sp/get-working-memory (::sc/working-memory-store env) env session-id))

(defn configuration
  "Return the set of active state ids."
  [t]
  (::sc/configuration (wmem t) #{}))

(defn in?
  "True if `state` is in the current configuration."
  [t state]
  (contains? (configuration t) state))

(defn data
  "Return the current data-model contents."
  [{:keys [env] :as t}]
  (sp/current-data (::sc/data-model env)
                   (assoc env
                          ::sc/vwmem (volatile! (wmem t))
                          ::sc/context-element-id nil)))

(defn goto-configuration!
  "Force the chart into a specific configuration via direct working-memory edits, applying
   `data-ops` to the data model first. Useful for setting up mid-flow test states.

   This is a brute-force helper: it does not run entry actions. Use sparingly."
  [{:keys [env session-id] :as t} data-ops states]
  (let [store (::sc/working-memory-store env)
        dm    (::sc/data-model env)]
    (when (seq data-ops)
      (sp/update! dm
                  (assoc env ::sc/vwmem (volatile! (wmem t)))
                  {:ops data-ops}))
    (let [w (sp/get-working-memory store env session-id)]
      (sp/save-working-memory! store env session-id
                               (assoc w ::sc/configuration (set states)))))
  t)

;; Re-export ops helpers so test code only has to require this ns
(def assign ops/assign)
(def delete ops/delete)
