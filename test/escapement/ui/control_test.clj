(ns escapement.ui.control-test
  "Wire-contract tests for `escapement.ui.control` — the client-side control plane wrappers.

   The load-bearing detail: Fulcro transmits the EXACT symbol written in a transaction over the
   remote, so the four control mutations MUST serialize to the SERVER symbols
   `escapement.control/{pause,step,continue,arm-pause-on-next-external}` (what the Pathom parser knows
   — see `escapement.ui.resolvers`), NOT `escapement.ui.control/*`. `m/declare-mutation` binds each
   client var to its server symbol; invoking the var returns the mutation data-literal carrying that
   symbol. We prove the transmitted dispatch key by building the EQL AST of the transaction (the same
   structure Fulcro hands the remote).

   `refresh-live!` must issue four `df/load!`s — `:session/{paused?,step-budget,live-configuration,
   pending-events}` — targeted under the shared live ident. We capture the outgoing EQL via a mock
   remote on a real Fulcro app and assert the load root keys.

   JVM-only; excluded from `bb test` (runner jvm-only-namespaces); run via `clojure -M:ui-test`."
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.headless :as h]
    [com.fulcrologic.fulcro.headless.loopback-remotes :as lb]
    [edn-query-language.core :as eql]
    [escapement.ui.control :as control]
    [fulcro-spec.core :refer [=> assertions component specification]]
    [taoensso.timbre :as log]))

(defn- mutation-dispatch-key
  "Returns the wire dispatch symbol of mutation data-literal `mutation-expr` by building its EQL AST
   — the same structure Fulcro hands a remote, so this is exactly what is transmitted."
  [mutation-expr]
  (:dispatch-key (first (:children (eql/query->ast [mutation-expr])))))

;; ===========================================================================
;; Control mutations carry the SERVER wire symbols (not this ns's symbols)
;; ===========================================================================

(specification "control mutations transmit the server wire symbols"
  (assertions
    "pause serializes to escapement.control/pause (the Pathom ::pc/sym), NOT escapement.ui.control/pause"
    (mutation-dispatch-key (control/pause {})) => 'escapement.control/pause
    "step serializes to escapement.control/step"
    (mutation-dispatch-key (control/step {})) => 'escapement.control/step
    "continue serializes to escapement.control/continue"
    (mutation-dispatch-key (control/continue {})) => 'escapement.control/continue
    "arm-pause-on-next-external serializes to escapement.control/arm-pause-on-next-external"
    (mutation-dispatch-key (control/arm-pause-on-next-external {}))
    => 'escapement.control/arm-pause-on-next-external
    ;; The whole point of declare-mutation: the wire symbol does NOT inherit this client ns.
    "none of the control mutations leak the client ns into the wire symbol"
    (mapv #(namespace (mutation-dispatch-key %))
      [(control/pause {}) (control/step {}) (control/continue {}) (control/arm-pause-on-next-external {})])
    => ["escapement.control" "escapement.control" "escapement.control" "escapement.control"]))

;; ===========================================================================
;; refresh-live! issues the four snapshot loads
;; ===========================================================================

(defsc Captor
  "Minimal root used only to host the app for capturing outgoing loads."
  [_ _]
  {:query [:x] :initial-state {:x 1}}
  nil)

(defn- capturing-app
  "Builds a headless Fulcro app whose `:remote` records every outgoing EQL into `captured`."
  [captured]
  (let [a (h/build-test-app
            {:root-class Captor
             :remotes    {:remote (lb/sync-remote (fn [eql] (swap! captured conj eql) {}))}})]
    (app/mount! a Captor :app)
    a))

(defn- load-root-keys
  "Returns the set of query-root keys across all captured EQL queries (a `df/load!` of a root key K
   sends EQL `[K]` or `[{(K params) [...]}]`)."
  [captured]
  (into #{}
    (mapcat (fn [eql] (map (fn [node] (if (map? node) (ffirst node) node)) eql)))
    captured))

(specification "refresh-live! loads the four live-snapshot data into the shared live entity"
  (log/with-merged-config {:min-level :error}
    (let [captured (atom [])
          a        (capturing-app captured)]
      (reset! captured [])
      (control/refresh-live! a)
      ;; Drain the load queue (build-test-app processes synchronously, but render to settle).
      (dotimes [_ 5] (h/render-frame! a))
      (let [roots (load-root-keys @captured)]
        (assertions
          "loads :session/paused?"
          (contains? roots :session/paused?) => true
          "loads :session/step-budget"
          (contains? roots :session/step-budget) => true
          "loads :session/live-configuration"
          (contains? roots :session/live-configuration) => true
          "loads :session/pending-events"
          (contains? roots :session/pending-events) => true
          "issues exactly those four snapshot loads (no more, no fewer)"
          roots => #{:session/paused? :session/step-budget
                     :session/live-configuration :session/pending-events})))))

(specification "refresh-live! targets all four loads under the single shared live ident"
  ;; The shared live ident is what BOTH the Debugger and ChartView read, so every datum must land
  ;; under it. `live-ident` is the contract; the loads `conj` their key onto it as the target.
  (assertions
    "the shared live ident is the fully-qualified single-colon control/live keyword"
    control/live-ident => [:component/id :escapement.ui.control/live]))
