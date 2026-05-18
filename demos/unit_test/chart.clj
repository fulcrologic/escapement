(ns unit-test.chart
  "Demo chart: drive an LLM through the same multi-phase unit-test pipeline that
  the `unit_test` pi extension implements, but as an Escapement statechart with
  a parallel REPL-manager region.

  ## Structure

  The top-level `:work` state is a parallel composite of two regions:

    * `:pipeline` — the main behaviors → abstraction → (write|gap) →
      (critique|patch) → await-repl → refine pipeline.
    * `:repl-mgr` — a project-agnostic REPL skill region: a cheap scripted
      discovery first, then an `:llm-conversation` fallback that reads
      `deps.edn` and starts a test REPL.

  The two regions communicate purely through the shared data model (`:nrepl-port`,
  `:repl-status`) and one coordination event (`:repl/available`). Once both
  regions reach their per-region final states, the chart transitions to its
  top-level final and the runner terminates.

  ## Initial data (from --input EDN)

    * `:source-path`     — required, e.g. `src/main/com/fulcrologic/.../tempid.cljc`
    * `:function`        — required, e.g. `tempid`
    * `:project-dir`     — required, absolute path of the project under test
    * `:test-file`       — optional, defaults to `<project-dir>/src/test/.../tempid_spec.cljc`
    * `:test-namespace`  — optional, defaults to derived
    * `:source-namespace`— optional, defaults to derived
    * `:work-dir`        — optional, defaults under `/tmp/escapement/unit-test/`
    * `:max-iterations`  — optional, default 10
    * `:nrepl-port`      — optional; if supplied, the repl-mgr skips discovery
                          and LLM startup entirely."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.data-model.operations :as ops]
   [com.fulcrologic.statecharts.elements :refer [final on-entry parallel script state transition]]
   [com.fulcrologic.statecharts.protocols :as sp]
   [escapement.chart.helpers :as h]
   [escapement.tools.protocol :as tp]
   [unit-test.prompts :as p]))

;; ---------------------------------------------------------------------------
;; Derivation helpers (pure)
;; ---------------------------------------------------------------------------

(defn source-path->namespace
  "Convert a Clojure source path to a namespace name. Strips any leading
   `src/main/`, `src/`, `test/`, `src/test/` segment, drops the
   `.clj[cs]?` extension, replaces `/` with `.` and `_` with `-`."
  [path]
  (let [stripped (reduce (fn [acc pfx]
                           (if (str/starts-with? acc pfx) (subs acc (count pfx)) acc))
                         path
                         ["src/main/" "src/test/" "src/" "test/"])
        no-ext   (str/replace stripped #"\.clj[cs]?$" "")]
    (-> no-ext (str/replace "/" ".") (str/replace "_" "-"))))

(defn source-path->test-path
  "Derive a default test-file path: `src/main/<x>.cljc` → `src/test/<x>_spec.cljc`,
   `src/<x>.clj` → `test/<x>_test.clj`."
  [source-path]
  (cond
    (str/starts-with? source-path "src/main/")
    (-> source-path
        (str/replace-first #"^src/main/" "src/test/")
        (str/replace #"(\.clj[cs]?)$" "_spec$1"))

    (str/starts-with? source-path "src/")
    (-> source-path
        (str/replace-first #"^src/" "test/")
        (str/replace #"(\.clj[cs]?)$" "_test$1"))

    :else (str/replace source-path #"(\.clj[cs]?)$" "_test$1")))

(defn test-namespace
  "Test namespace name derived from a test-file path."
  [test-path]
  (source-path->namespace test-path))

(defn function-slug
  "Filesystem-safe slug for a function name (handles `!`, `?`, `/`, etc.)."
  [fn-name]
  (-> fn-name
      (str/replace "!" "_BANG_")
      (str/replace "?" "_QMARK_")
      (str/replace "/" "_SLASH_")
      (str/replace #"[^A-Za-z0-9_.-]" "_")))

(defn default-work-dir
  "Compute a default work-dir for `source-ns` and `function`."
  [source-ns function]
  (str "/tmp/escapement/unit-test/" source-ns "/" (function-slug function)))

;; ---------------------------------------------------------------------------
;; Init: compute derived fields in one assign block.
;; ---------------------------------------------------------------------------

(defn- init-derivations
  [_env data]
  (let [src       (:source-path data)
        _         (assert src ":source-path is required (pass via --input)")
        fn-name   (:function data)
        _         (assert fn-name ":function is required (pass via --input)")
        project   (or (:project-dir data) ".")
        src-ns    (or (:source-namespace data) (source-path->namespace src))
        test-rel  (or (:test-file data) (source-path->test-path src))
        ;; Resolve test-file to an absolute path under the project dir.
        test-file (if (.isAbsolute (io/file test-rel)) test-rel (str project "/" test-rel))
        test-ns   (or (:test-namespace data) (test-namespace test-rel))
        src-abs   (if (.isAbsolute (io/file src)) src (str project "/" src))
        work-dir  (or (:work-dir data) (default-work-dir src-ns fn-name))
        _         (.mkdirs (io/file work-dir))
        existing? (.exists (io/file test-file))]
    [(ops/assign :source-path src-abs)
     (ops/assign :source-namespace src-ns)
     (ops/assign :test-file test-file)
     (ops/assign :test-namespace test-ns)
     (ops/assign :work-dir work-dir)
     (ops/assign :project-dir project)
     (ops/assign :behaviors-file (str work-dir "/behaviors.md"))
     (ops/assign :mock-strategy-file (str work-dir "/abstraction.md"))
     (ops/assign :gap-analysis-file (str work-dir "/gap-analysis.md"))
     (ops/assign :max-iterations (or (:max-iterations data) 10))
     (ops/assign :existing? existing?)]))

;; ---------------------------------------------------------------------------
;; Per-phase params builders
;; ---------------------------------------------------------------------------

(def ^:private done-event
  {:event :phase/done :data-schema [:map [:summary :string]]})

(defn- analysis-params [phase data]
  {:system               (p/render-phase phase data)
   :real-tools           [:fs/read :fs/write]
   :allowed-events       [done-event]
   :initial-user-message (str "Begin the `" (name phase)
                              "` phase for `" (:function data)
                              "` in `" (:source-path data) "`. Follow the instructions exactly.")})

(defn- mutation-params [phase data]
  {:system               (p/render-phase phase data)
   :real-tools           [:fs/read :fs/write :fs/edit]
   :allowed-events       [done-event]
   :initial-user-message (str "Begin the `" (name phase) "` phase. The test file is `"
                              (:test-file data) "`. Follow the instructions exactly.")})

(defn- refine-params [data]
  {:system               (p/render-phase :refine data)
   :real-tools           [:fs/read :fs/write :fs/edit :shell/run]
   :allowed-events       [{:event       :refine/sealed
                           :data-schema [:map
                                         [:signature :string]
                                         [:iterations {:optional true} :int]]}
                          {:event       :refine/give-up
                           :data-schema [:map [:reason :string]]}]
   :initial-user-message (str "Refine the tests in `" (:test-file data)
                              "` for `" (:function data) "` until they pass and seal `:covers`."
                              " REPL port: " (:nrepl-port data) ".")})

(defn- repl-mgr-params [data]
  {:system               (p/render-phase :repl-manager data)
   :real-tools           [:fs/read :shell/run]
   :allowed-events       [{:event       :repl/ready-evt
                           :data-schema [:map [:port :int]]}
                          {:event       :repl/failed
                           :data-schema [:map [:reason :string]]}]
   :initial-user-message (str "Establish a TEST-mode nREPL for the project at `"
                              (:project-dir data)
                              "`. Follow the procedure exactly.")})

;; ---------------------------------------------------------------------------
;; REPL discovery (scripted cheap path)
;; ---------------------------------------------------------------------------

(def ^:private discover-line-re
  ;; e.g. "  localhost:50643 (clj) - /Users/me/project"
  #"localhost:(\d+)\s+\(([^)]+)\)\s+-\s+(.+)$")

(defn- parse-discover-output
  "Return the first localhost port that matches `project-dir` and is *not*
   marked as `(shadow)`, or nil."
  [output project-dir]
  (let [target (-> project-dir io/file .getAbsolutePath)]
    (some (fn [line]
            (when-let [[_ port kind dir] (re-find discover-line-re line)]
              (when (and (not= "shadow" kind)
                         (= target (-> dir str/trim io/file .getAbsolutePath)))
                (Integer/parseInt port))))
          (str/split-lines output))))

(defn- post-self-event!
  "Send `event` (with optional `data`) back to the current chart session."
  [env event-name event-data]
  (let [queue (::sc/event-queue env)
        sid   (some-> env ::sc/vwmem deref ::sc/session-id)]
    (when (and queue sid)
      (sp/send! queue env {:target            sid
                           :source-session-id sid
                           :event             event-name
                           :data              event-data}))))

(defn- discover-action
  "On entry to :repl-mgr/discovering, try the cheap path:

     1. If `:nrepl-port` is already in the data model (user pre-supplied or a
        prior run cached it), fire `:repl/found` immediately.
     2. Else run `clj-nrepl-eval --discover-ports` via `:shell/run`, parse, and
        accept any non-shadow port whose project-dir matches `:project-dir`.
        On hit, fire `:repl/found`; on miss, fire `:repl/need-llm`."
  []
  (script
   {:expr
    (fn [env data]
      (let [pre-port (:nrepl-port data)]
        (cond
          (some? pre-port)
          (do (post-self-event! env :repl/found {:port pre-port})
              nil)

          :else
          (let [registry (or (:escapement/tool-registry env)
                             (get-in env [:escapement/engine :tool-registry]))
                {:keys [result is-error]}
                (if registry
                  (tp/dispatch registry :shell/run
                               {:command "clj-nrepl-eval --discover-ports"})
                  {:result "" :is-error true})
                port     (when-not is-error
                           (parse-discover-output (or result "") (:project-dir data)))]
            (if port
              (post-self-event! env :repl/found {:port port})
              (post-self-event! env :repl/need-llm {}))
            nil))))}))

;; ---------------------------------------------------------------------------
;; Reusable chart segments
;; ---------------------------------------------------------------------------
;;
;; Each `def` below is a single state node (a plain map). Composition tests in
;; `demos/unit_test/test/` can drop these vars into a minimal shim chart, wrap
;; them with a synthetic parent + finals, and drive them through
;; `escapement.engine.testing` with a mocked backend. See
;; `[[chart-segment-testing]]` in `Guide.adoc` for the pattern.
;;
;; Transitions inside these segments use the same state IDs as the full chart
;; (`:pipeline-done`, etc.); a shim chart must provide states with those ids
;; or override them by adding parallel transitions in the wrapping state.

(def refine-state
  "The refine-phase LLM-bound state, isolatable for segment composition tests.
   Inputs (data model): `:test-file`, `:function`, `:nrepl-port`, prompt subs.
   Outputs (data model): `:final-status`, `:covers-signature`, `:give-up-reason`.
   Transitions out: `:pipeline-done` (on `:refine/sealed` or `:refine/give-up`)."
  (state {:id :refine}
         (h/llm-conversation
          {:id        "refine"
           :params-fn (fn [_env data] (refine-params data))})
         (transition {:event :refine/sealed :target :pipeline-done}
                     (script {:expr (fn [_env data]
                                      [(ops/assign :final-status :sealed)
                                       (ops/assign :covers-signature
                                                   (get-in data [:_event :data :signature]))])}))
         (transition {:event :refine/give-up :target :pipeline-done}
                     (script {:expr (fn [_env data]
                                      [(ops/assign :final-status :gave-up)
                                       (ops/assign :give-up-reason
                                                   (get-in data [:_event :data :reason]))])}))))

;; ---------------------------------------------------------------------------
;; Chart
;; ---------------------------------------------------------------------------

(def agent
  (chart/statechart
   {:initial :run}

   (state {:id :run :initial :init}

      ;; Compute derived fields first, then enter the parallel work block.
          (state {:id :init}
                 (on-entry {} (script {:expr init-derivations}))
                 (transition {:target :work}))

          (parallel {:id :work}

        ;; =====================================================
        ;; Region A: the main pipeline
        ;; =====================================================
                    (state {:id :pipeline :initial :behaviors}

                           (state {:id :behaviors}
                                  (h/llm-conversation
                                   {:id        "behaviors"
                                    :params-fn (fn [_env data] (analysis-params :behaviors data))})
                                  (transition {:event :phase/done :target :abstraction}))

                           (state {:id :abstraction}
                                  (h/llm-conversation
                                   {:id        "abstraction"
                                    :params-fn (fn [_env data] (analysis-params :abstraction data))})
                                  (transition {:event :phase/done :target :choose-path}))

                           (state {:id :choose-path}
                                  (transition {:target :gap-analysis
                                               :cond   (fn [_env data] (boolean (:existing? data)))})
                                  (transition {:target :write}))

          ;; NEW path
                           (state {:id :write}
                                  (h/llm-conversation
                                   {:id        "write"
                                    :params-fn (fn [_env data] (mutation-params :write data))})
                                  (transition {:event :phase/done :target :critique}))

                           (state {:id :critique}
                                  (h/llm-conversation
                                   {:id        "critique"
                                    :params-fn (fn [_env data] (mutation-params :critique data))})
                                  (transition {:event :phase/done :target :await-repl}))

          ;; EXISTING path
                           (state {:id :gap-analysis}
                                  (h/llm-conversation
                                   {:id        "gap-analysis"
                                    :params-fn (fn [_env data] (analysis-params :gap-analysis data))})
                                  (transition {:event :phase/done :target :patch}))

                           (state {:id :patch}
                                  (h/llm-conversation
                                   {:id        "patch"
                                    :params-fn (fn [_env data] (mutation-params :patch data))})
                                  (transition {:event :phase/done :target :await-repl}))

          ;; Wait for the REPL manager region to publish a port.
          ;; Eventless transition fires immediately if `:nrepl-port`
          ;; is already set by the time we arrive; otherwise we
          ;; transition on the `:repl/available` event.
                           (state {:id :await-repl}
            ;; If the manager region already published the port
            ;; before we got here, self-post the event so we
            ;; transition immediately rather than wait forever.
                                  (on-entry {}
                                            (script {:expr (fn [env data]
                                                             (when (:nrepl-port data)
                                                               (post-self-event! env :repl/available
                                                                                 {:port (:nrepl-port data)}))
                                                             nil)}))
                                  (transition {:event :repl/available :target :refine})
                                  (transition {:event :repl/aborted :target :pipeline-failed}))

                           refine-state

                           (final {:id :pipeline-done})
                           (final {:id :pipeline-failed}
                                  (on-entry {}
                                            (script {:expr (fn [_env _]
                                                             [(ops/assign :final-status :repl-unavailable)])}))))

        ;; =====================================================
        ;; Region B: the REPL manager
        ;; =====================================================
                    (state {:id :repl-mgr :initial :discovering}

                           (state {:id :discovering}
                                  (on-entry {} (discover-action))
                                  (transition {:event :repl/found :target :repl-ready}
                                              (script {:expr (fn [_env data]
                                                               [(ops/assign :nrepl-port
                                                                            (get-in data [:_event :data :port]))
                                                                (ops/assign :repl-status :discovered)])}))
                                  (transition {:event :repl/need-llm :target :inspecting}))

                           (state {:id :inspecting}
                                  (h/llm-conversation
                                   {:id        "repl-mgr"
                                    :params-fn (fn [_env data] (repl-mgr-params data))})
                                  (transition {:event :repl/ready-evt :target :repl-ready}
                                              (script {:expr (fn [_env data]
                                                               [(ops/assign :nrepl-port
                                                                            (get-in data [:_event :data :port]))
                                                                (ops/assign :repl-status :started)])}))
                                  (transition {:event :repl/failed :target :repl-aborted}
                                              (script {:expr (fn [_env data]
                                                               [(ops/assign :repl-status :failed)
                                                                (ops/assign :repl-failure-reason
                                                                            (get-in data [:_event :data :reason]))])})))

          ;; Final states. On entry, broadcast :repl/available
          ;; (or :repl/aborted) so the pipeline region can move on.
                           (final {:id :repl-ready}
                                  (on-entry {}
                                            (script {:expr (fn [env data]
                                                             (post-self-event! env :repl/available
                                                                               {:port (:nrepl-port data)})
                                                             nil)})))
                           (final {:id :repl-aborted}
                                  (on-entry {}
                                            (script {:expr (fn [env _data]
                                                             (post-self-event! env :repl/aborted {})
                                                             nil)})))))

      ;; Both regions in final → parallel raises done.state.work.
          (transition {:event :done.state.work :target :finished})

          (final {:id :finished}))))
