(ns escapement.runner-cancel-test
  "Cancellation tests for the additive `:cancel` signal (task-006).

  Verifies that a host-supplied cancel handle (atom or promise), threaded
  through the hosted facade and honored in the runner pump loop, terminates a
  started run promptly at a safe loop boundary, emits a distinguishable
  `:runner/aborted` lifecycle event, surfaces `:status :aborted` in the result
  map, and does NOT corrupt the transcript (well-formed JSONL rows) or the
  checkpoint (loadable by a fresh store). Also verifies the signal is purely
  additive: omitting it, or supplying an un-fired handle, preserves the prior
  `:status :done` behavior, and the closed facade schema still rejects unknown
  keys."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final state transition]]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.engine.store :as store]
    [escapement.lib :as lib]
    [escapement.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]])
  (:import
    (java.nio.file Files)
    (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir [prefix]
  (str (Files/createTempDirectory prefix (into-array FileAttribute []))))

(defn- read-rows [path]
  (mapv #(json/parse-string % true) (str/split-lines (slurp path))))

;; A chart that parks (initial state awaits an external `:go` that never
;; arrives), so absent any cancel signal the runner reaches quiescence with no
;; live invocations and terminates `:done`. With a cancel armed at
;; `:on-env-ready`, the pump loop observes the signal at the top of its first
;; iteration (a safe boundary, before any drain) and aborts promptly.
(def parked-chart
  (chart/statechart
    {:initial :work}
    (state {:id :work :initial :idle}
      (state {:id :idle}
        (transition {:event :go :target :done}))
      (final {:id :done}))))

(specification "cancel atom armed at on-env-ready aborts a started run promptly"
  (let [dir    (tmp-dir "cancel-atom-")
        tpath  (str dir "/t.jsonl")
        cdir   (str dir "/chk")
        cancel (atom false)
        result (runner/run! {:chart              parked-chart
                             :session-id         :cancel-test/atom
                             :transcript-path    tpath
                             :checkpoint-dir     cdir
                             :max-iterations     500
                             :quiescent-sleep-ms 10
                             :cancel             cancel
                             ;; Trigger the cancel as the run starts (env is
                             ;; built, chart not yet pumped) — the loop must
                             ;; observe it within the next (first) iteration.
                             :on-env-ready       (fn [_] (reset! cancel true))})
        rows   (read-rows tpath)
        events (mapv :event rows)
        ab     (first (filter #(= "runner/aborted" (:event %)) rows))
        done   (first (filter #(= "runner/done" (:event %)) rows))
        s2     (store/new-store cdir)]
    (store/reload-from-disk! s2 :cancel-test/atom)
    (assertions
      "the run reports an aborted status in the summary map"
      (:status result) => :aborted
      "a :runner/aborted lifecycle event was emitted with reason :cancelled"
      (:reason (:data ab)) => "cancelled"
      "the run stopped promptly: it never processed a chart event"
      (some #{"runner/event-processed"} events) => nil
      "the transcript is well-formed JSONL ending with a clean :runner/done"
      (last events) => "runner/done"
      "every transcript row parsed (no torn/partial row)"
      (every? map? rows) => true
      "the :runner/done event also carries the aborted status"
      (:status (:data done)) => "aborted"
      "the checkpoint written before the abort is loadable by a fresh store"
      (some? (sp/get-working-memory s2 nil :cancel-test/atom)) => true
      "the chart's parked configuration survived intact (not torn)"
      (vector? (:final-config result)) => true)))

(specification "a delivered promise also requests abort"
  (let [dir (tmp-dir "cancel-prom-")
        p   (promise)
        _   (deliver p true)
        r   (runner/run! {:chart           parked-chart
                          :session-id      :cancel-test/promise
                          :transcript-path (str dir "/t.jsonl")
                          :checkpoint-dir  (str dir "/chk")
                          :cancel          p})]
    (assertions
      "a delivered, truthy promise aborts the run"
      (:status r) => :aborted)))

(specification "an un-fired / absent cancel signal preserves prior behavior"
  (let [d1 (tmp-dir "cancel-none-")
        r1 (runner/run! {:chart           parked-chart
                         :session-id      :cancel-test/none
                         :transcript-path (str d1 "/t.jsonl")
                         :checkpoint-dir  (str d1 "/chk")})
        d2 (tmp-dir "cancel-atomf-")
        r2 (runner/run! {:chart           parked-chart
                         :session-id      :cancel-test/atom-false
                         :transcript-path (str d2 "/t.jsonl")
                         :checkpoint-dir  (str d2 "/chk")
                         :cancel          (atom false)})
        d3 (tmp-dir "cancel-prom-undeliv-")
        r3 (runner/run! {:chart           parked-chart
                         :session-id      :cancel-test/promise-undeliv
                         :transcript-path (str d3 "/t.jsonl")
                         :checkpoint-dir  (str d3 "/chk")
                         :cancel          (promise)})]
    (assertions
      "omitting :cancel entirely → normal :done (no signature/behavior change)"
      (:status r1) => :done
      "a falsy cancel atom → normal :done"
      (:status r2) => :done
      "an un-delivered promise never blocks and never aborts → :done"
      (:status r3) => :done)))

(specification "the hosted facade surfaces aborted status and stays additive"
  (let [cancel (atom false)
        ;; `:credentials` is unconditionally required by the closed schema
        ;; (Step 4). `parked-chart` has no LLM so the backend is never used.
        creds  [{:provider :anthropic :api-key "sk-test"}]
        rab    (lib/run {:chart        parked-chart
                         :session-id   :cancel-test/facade-aborted
                         :credentials  creds
                         :cancel       cancel
                         :on-env-ready (fn [_] (reset! cancel true))})
        rok    (lib/run {:chart       parked-chart
                         :session-id  :cancel-test/facade-normal
                         :credentials creds})]
    (assertions
      "lib/run surfaces :status :aborted from a cancelled run"
      (:status rab) => :aborted
      "the aborted run still carries a stable string :run-id"
      (string? (:run-id rab)) => true
      "a normal facade run is unchanged → :status :done"
      (:status rok) => :done
      "the closed facade option schema still rejects unknown keys"
      (try (lib/run {:chart parked-chart :session-id :x :bogus 1}) :no-throw
           (catch clojure.lang.ExceptionInfo e (-> e ex-data :errors some?)))
      => true
      "and :cancel remains a valid (optional) option"
      (lib/validate-options {:chart       parked-chart :session-id :s1
                             :credentials creds :cancel (atom false)})
      => nil)))
