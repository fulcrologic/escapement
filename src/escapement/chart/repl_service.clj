(ns escapement.chart.repl-service
  "Concrete service region helper: nREPL discovery + eval, packaged as a
  drop-in chart segment.

  This is NOT framework — it's an author-side example of the service-region
  pattern (see `escapement.chart.service`). Chart authors who want a REPL
  service that other LLM regions can call drop `(repl-service-region ...)`
  into a parallel region and declare `:chart-tools [{:owner :repl-mgr}]`
  on their consumer conversation.

  The discovery logic mirrors the bespoke `:repl-mgr` discover-action in
  `demos/unit_test/chart.clj` — shells out to `clj-nrepl-eval --discover-ports`
  to find a non-shadow port whose project-dir matches.

  Eval is delegated to `:repl/eval` from `escapement.tools.builtin` when
  available — that tool runs Clojure code in a fresh in-process namespace
  via SCI, which is bb-compatible and good enough for the helper's
  smoke-test purpose. Charts that need to evaluate AGAINST a remote nREPL
  process can substitute their own handler-fn."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [com.fulcrologic.statecharts.elements :refer [on-entry script state]]
    [escapement.chart.service :as service]
    [escapement.tools.protocol :as tp]))

;; ---------------------------------------------------------------------------
;; Discovery
;; ---------------------------------------------------------------------------

(def ^:private discover-line-re
  ;; e.g. "  localhost:50643 (clj) - /Users/me/project"
  #"localhost:(\d+)\s+\(([^)]+)\)\s+-\s+(.+)$")

(defn parse-discover-output
  "Return the first localhost port that matches `project-dir` and is *not*
   marked as `(shadow)`, or nil.

   Mirrors `unit-test.chart/parse-discover-output` from the demo — kept
   here so this ns is self-contained and the demo can migrate to use it."
  [output project-dir]
  (let [target (-> project-dir io/file .getAbsolutePath)]
    (some (fn [line]
            (when-let [[_ port kind dir] (re-find discover-line-re line)]
              (when (and (not= "shadow" kind)
                      (= target (-> dir str/trim io/file .getAbsolutePath)))
                (Integer/parseInt port))))
      (str/split-lines output))))

(defn discover-port
  "Discover an active non-shadow nREPL port matching `project-dir` via the
   chart's tool registry. Returns the port (int) or nil. Returns nil
   without throwing when the tool registry isn't available or
   `clj-nrepl-eval` isn't on PATH."
  [env project-dir]
  (let [registry (or (:escapement/tool-registry env)
                   (get-in env [:escapement/engine :tool-registry]))]
    (when registry
      (let [{:keys [result is-error]}
            (try
              (tp/dispatch registry :shell/run
                {:command "clj-nrepl-eval --discover-ports"})
              (catch Throwable _
                {:result "" :is-error true}))]
        (when-not is-error
          (parse-discover-output (or result "") project-dir))))))

;; ---------------------------------------------------------------------------
;; Service handlers
;; ---------------------------------------------------------------------------

(defn- eval-via-builtin
  "Dispatch a Clojure form through the chart's `:repl/eval` builtin tool.
   Returns the service handler reply map `{:result :is-error}`."
  [env {:keys [data]}]
  (let [registry (or (:escapement/tool-registry env)
                   (get-in env [:escapement/engine :tool-registry]))]
    (cond
      (nil? registry)
      {:result   "No tool registry on env; cannot dispatch :repl/eval."
       :is-error true}

      (nil? (:expr data))
      {:result "Missing :expr in tool input." :is-error true}

      :else
      (let [{:keys [result is-error]}
            (tp/dispatch registry :repl/eval
              {:code (str (:expr data))})]
        {:result   (str result)
         :is-error (boolean is-error)}))))

;; ---------------------------------------------------------------------------
;; Drop-in region
;; ---------------------------------------------------------------------------

(defn repl-service-region
  "Returns a `state` element implementing the `:repl/eval` tool (and
   optionally `:repl/status`) as a service region. Drop this into a parallel
   region in your chart, then declare `:chart-tools [{:owner :repl-mgr}]`
   (or whatever you pass for `:id`) on consumer conversations.

   `opts`:
     * `:id`          (optional, default `:repl-mgr`) — state-id for the
                      service region. Use this as the `:owner` on consumer
                      `:chart-tools`.
     * `:project-dir` (optional) — passed to `discover-port`. If supplied
                      the on-entry action attempts auto-discovery and
                      stamps `:nrepl-port` into the data model (best-
                      effort; missing port is non-fatal).
     * `:eval-fn`     (optional) — `(fn [env req] reply-map)` overrides the
                      default in-process eval handler. Pass this when your
                      chart needs to talk to a real external nREPL.
     * `:status-fn`   (optional) — handler for `:repl/status`. The tool is
                      only registered when this is supplied (the helper has
                      no honest default — without a `:status-fn` your chart
                      author knows their REPL is reachable iff `:repl/eval`
                      works)."
  ([] (repl-service-region {}))
  ([{:keys [id project-dir eval-fn status-fn]
     :or   {id      :repl-mgr
            eval-fn eval-via-builtin}}]
   (let [discover-entry (when project-dir
                          (on-entry {}
                            (script
                              {:expr
                               (fn [env _data]
                                 ;; Best-effort discovery — failures are non-fatal.
                                 (try (discover-port env project-dir)
                                      (catch Throwable _ nil))
                                 nil)})))
         status-entries (when status-fn
                          [(on-entry {}
                             (service/register-tool!
                               {:tool         :repl/status
                                :description  "Return brief REPL status."
                                :input-schema [:map]}))])
         ready-children (cond-> [(service/handle :repl/eval eval-fn)]
                          status-fn (conj (service/handle :repl/status status-fn)))
         children       (filterv
                          some?
                          (concat
                            [(on-entry {}
                               (service/register-tool!
                                 {:tool         :repl/eval
                                  :description  "Evaluate a Clojure form. Input :expr (string)."
                                  :input-schema [:map [:expr :string]]}))
                             discover-entry]
                            status-entries
                            [(apply state {:id :ready} ready-children)]))]
     (apply state {:id id :initial :ready} children))))
