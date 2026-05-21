(ns escapement.examples.n-subagents-demo
  "Demo of the N-subagents pattern (see ../../../n-subagents.md): one chart
  with three parallel regions — a BOARD that owns shared state, N WORKER
  regions that claim tasks and submit results, and a WATCHER that fires
  the parallel-join.

  This is the deterministic, no-LLM skeleton — it exercises the
  orchestration shape end-to-end without needing the bb-safe child-session
  path that real LLM subagents would require (n-subagents.md
  §Escapement-specific constraints). Workers \"work\" by upper-casing the
  task string; the point is the choreography, not the work.

  Run from the REPL:

    bb -e \"(require '[escapement.examples.n-subagents-demo :as d])
            (require '[escapement.engine.testing :as t])
            (-> (t/new-testing-env {:statechart d/agent})
                (t/start!)
                (t/drain!)
                ((juxt t/configuration t/data))
                prn)\""
  (:require
   [clojure.string :as str]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.data-model.operations :as ops]
   [com.fulcrologic.statecharts.elements
    :refer [final on-entry parallel script state transition]]
   [com.fulcrologic.statecharts.protocols :as sp]))

(def ^:private default-tasks
  ["alpha" "beta" "gamma" "delta" "epsilon" "zeta"])

(def ^:private worker-count 3)

(defn- raise!
  "Post a chart event onto our own session's queue from inside a script."
  [env event data]
  (let [queue (get env ::sc/event-queue)
        sid   (some-> env ::sc/vwmem deref ::sc/session-id)]
    (when (and queue sid)
      (sp/send! queue env {:target            sid
                           :source-session-id sid
                           :event             event
                           :data              data}))))

(defn- mine? [w]
  (fn [_env data] (= w (get-in data [:_event :data :worker]))))

(defn- worker-region
  "Build one worker region. Worker `w` loops idle -> idle by claiming and
  immediately processing tasks; it terminates when the board says
  `:no-tasks` for it or a `:wrap-up` arrives."
  [w]
  (let [idle (keyword (str "w" w "-idle"))
        done (keyword (str "w" w "-done"))]
    (state {:id (keyword (str "worker-" w))
            :initial idle}
      (state {:id idle}
        (on-entry {}
          (script {:expr (fn [env _data]
                           (raise! env :claim-task {:worker w})
                           nil)}))
        ;; Got a task -> do work, submit, re-enter :idle to claim again.
        (transition {:event :assign-task
                     :cond  (mine? w)
                     :target idle}
          (script {:expr
                   (fn [env data]
                     (let [idx    (get-in data [:_event :data :idx])
                           task   (get-in data [:_event :data :task])
                           result (str/upper-case (str task))]
                       (raise! env :submit-result
                         {:worker w :idx idx :result result})
                       nil))}))
        (transition {:event :no-tasks :cond (mine? w) :target done})
        (transition {:event :wrap-up :target done}))
      (final {:id done}))))

(def board-region
  (state {:id :board :initial :open}
    (state {:id :open}
      ;; Internal so we stay in :open across many claim/submit cycles.
      (transition {:event :claim-task :type :internal}
        (script {:expr
                 (fn [env data]
                   (let [w        (get-in data [:_event :data :worker])
                         tasks    (:tasks data)
                         claimed  (or (:claimed data) #{})
                         next-idx (first (remove claimed
                                           (range (count tasks))))]
                     (if next-idx
                       (do (raise! env :assign-task
                             {:worker w :idx next-idx
                              :task (nth tasks next-idx)})
                           [(ops/assign :claimed (conj claimed next-idx))])
                       (do (raise! env :no-tasks {:worker w})
                           nil))))}))
      (transition {:event :submit-result :type :internal}
        (script {:expr
                 (fn [env data]
                   (let [idx     (get-in data [:_event :data :idx])
                         result  (get-in data [:_event :data :result])
                         results (assoc (or (:results data) {})
                                   idx result)
                         total   (count (:tasks data))]
                     (when (= (count results) total)
                       (raise! env :wrap-up {}))
                     [(ops/assign :results results)]))}))
      (transition {:event :wrap-up :target :board-done}))
    (final {:id :board-done})))

(def watcher-region
  (state {:id :watcher :initial :watching}
    (state {:id :watching}
      (transition {:event :wrap-up :target :watcher-done}))
    (final {:id :watcher-done})))

(def agent
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :work}
      (on-entry {}
        (script {:expr (fn [_env data]
                         (when-not (:tasks data)
                           [(ops/assign :tasks default-tasks)]))}))

      (apply parallel {:id :work}
        board-region
        watcher-region
        (map worker-region (range worker-count)))

      ;; All regions hit their region-finals -> parallel raises this.
      (transition {:event :done.state.work :target :finished})

      (final {:id :finished}))))
