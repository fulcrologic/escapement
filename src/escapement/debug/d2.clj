(ns escapement.debug.d2
  "Render a (compiled) Fulcrologic Statecharts chart as a [d2](https://d2lang.com)
   diagram, with optional `chart.svg` rendering via the `d2` binary.

   Two public entry points:

   * `chart->d2` — pure: chart map + active-state set -> d2 source string.
   * `render-and-open!` — writes `<dir>/chart.d2`, attempts to render
     `<dir>/chart.svg`, and (when an external viewer is configured) launches
     it in the background. Never throws.

   Walking strategy mirrors `com.fulcrologic.statecharts.visualization.visualizer`:

   * States, finals, parallels, and history nodes are drawn as boxes; initials
     are drawn as small filled dots.
   * Edges come from a single scan of `(vals ::sc/elements-by-id)` for
     `:node-type :transition`; each carries its source via `:parent` and
     targets via `:target`. No nested-walk bookkeeping needed.
   * Labels use `chart/diagram-label` and `chart/transition-label` so author
     `:diagram/label` overrides and guard conditions appear automatically.

   Assumes a compiled chart (one that has been through `chart/statechart`).
   Babashka-compatible."
  (:require
    [babashka.process :as bp]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [escapement.config :as config]))

(def ^:private active-fill "\"#ffe680\"")
(def ^:private active-stroke 3)

(defn safe-id
  "Returns a d2-safe identifier for a state id. Replaces characters that
   confuse d2's container-path syntax. Public because consumers that build
   CSS class names need to mirror the same encoding the renderer used."
  [id]
  (let [s (cond
            (keyword? id) (subs (str id) 1)
            (symbol? id) (str id)
            :else (str id))]
    (-> s
      (str/replace "/" "__")
      (str/replace " " "_"))))

(defn- d2-quote
  "d2 string label literal."
  [s]
  (pr-str (str (or s ""))))

(defn- initial-pseudo-state?
  "Compiled charts encode initial pseudo-states as `{:node-type :state
   :initial? true}` rather than `:node-type :initial`. We always draw these
   as small filled dots, never as boxes."
  [n]
  (boolean (:initial? n)))

(defn- state-node?
  "Boxy node-types — anything that gets drawn as a rectangle/circle/etc."
  [n]
  (and (not (initial-pseudo-state? n))
    (boolean (#{:state :final :parallel :history :statechart} (:node-type n)))))

(defn- shape-for [{:keys [node-type deep?] :as node}]
  (case node-type
    :final "circle"
    :parallel "package"
    :history "circle"
    "rectangle"))

(defn- node-label
  "Display label for a state. History nodes get H / H* suffix per UML."
  [node]
  (let [base (chart/diagram-label node)]
    (case (:node-type node)
      :history (str base " · " (if (:deep? node) "H*" "H"))
      base)))

(defn- indent [n] (apply str (repeat n "  ")))

(defn- emit-initial!
  "An `:initial` pseudo-state is rendered as a small filled circle attached
   to its parent. We emit it as a top-level d2 node with a synthetic name
   so we can draw the edge to the actual initial target."
  [sb id pad]
  (let [sid (safe-id id)]
    (.append sb (str pad sid ": \"\" {\n"))
    (.append sb (str pad "  shape: circle\n"))
    (.append sb (str pad "  width: 20\n"))
    (.append sb (str pad "  height: 20\n"))
    (.append sb (str pad "  style.fill: \"#000\"\n"))
    (.append sb (str pad "}\n"))))

(defn- emit-state!
  "Recursive: writes d2 lines for `node` and all of its state-like
   children. `elements-by-id` is the chart's id->element index."
  [sb elements-by-id node active? depth]
  (let [{:keys [id node-type children]} node
        pad (indent depth)]
    (cond
      (initial-pseudo-state? node)
      (emit-initial! sb id pad)

      (state-node? node)
      (let [sid   (safe-id id)
            label (node-label node)]
        (.append sb (str pad sid ": " (d2-quote label) " {\n"))
        (.append sb (str pad "  shape: " (shape-for node) "\n"))
        (.append sb (str pad "  class: state-" sid "\n"))
        (when (= :parallel node-type)
          (.append sb (str pad "  style.stroke-dash: 3\n")))
        (when (active? id)
          (.append sb (str pad "  style.fill: " active-fill "\n"))
          (.append sb (str pad "  style.stroke-width: " active-stroke "\n"))
          (.append sb (str pad "  style.bold: true\n")))
        (doseq [c-id children
                :let [c (get elements-by-id c-id)]
                :when (and c (or (initial-pseudo-state? c) (state-node? c)))]
          (emit-state! sb elements-by-id c active? (inc depth)))
        (.append sb (str pad "}\n"))))))

(defn- all-transitions
  "Returns every transition node in the chart (vector of element maps)."
  [{::sc/keys [elements-by-id]}]
  (filterv #(= :transition (:node-type %)) (vals elements-by-id)))

(defn- ancestor-state-ids
  "Returns the chain of ancestor STATE ids from the immediate state-like
   parent up to (but not including) the chart root. Skips transition nodes
   so a transition's own `:parent` (the source state) is the head of the
   chain."
  [elements-by-id id]
  (loop [cur  (get elements-by-id id)
         path []]
    (let [p (some-> cur :parent (->> (get elements-by-id)))]
      (if (and p (state-node? p))
        (recur p (conj path (:id p)))
        path))))

(defn- d2-path
  "Returns the fully-qualified d2 path for a state id, e.g. `run.writing`,
   so edges target the right (nested) box rather than a synthetic top-level
   one. The leaf comes last."
  [elements-by-id id]
  (let [chain (reverse (ancestor-state-ids elements-by-id id))]
    (str/join "." (conj (mapv safe-id chain) (safe-id id)))))

(defn- emit-edge!
  "Writes one d2 edge line for a transition `t`. Falls back to a self-loop
   when `:target` is empty (i.e. an internal/no-target transition). Each
   emitted edge carries `class: edge-<safe-transition-id>` so the rendered
   SVG can be retargeted via CSS without recompiling the layout."
  [sb elements-by-id t]
  (let [{:keys [id parent target]} t
        label (chart/transition-label elements-by-id t)
        from  (d2-path elements-by-id parent)
        tgts  (if (and target (sequential? target)) target (when target [target]))
        cls   (str "edge-" (safe-id id))]
    (doseq [to (or (seq tgts) [parent])]
      (.append sb (str from " -> " (d2-path elements-by-id to)
                    ": " (if label (d2-quote label) "\"\"")
                    " { class: " cls " }"
                    "\n")))))

(defn chart->d2
  "Returns a d2 source string for a compiled `chart` (must carry
   `::sc/elements-by-id`). State ids in `active-config` are highlighted."
  [chart active-config]
  (let [ebi     (::sc/elements-by-id chart)
        active? (or active-config #{})
        active? (if (set? active?) active? (set active?))
        sb      (StringBuilder.)]
    (.append sb "direction: down\n")
    ;; States: walk top-level children.
    (doseq [c-id (:children chart)
            :let [c (get ebi c-id)]
            :when (and c (or (initial-pseudo-state? c) (state-node? c)))]
      (emit-state! sb ebi c active? 0))
    ;; Edges: one pass over the index.
    (doseq [t (all-transitions chart)]
      (emit-edge! sb ebi t))
    (.toString sb)))

;; ---------------------------------------------------------------------------
;; Render + open
;; ---------------------------------------------------------------------------

(defn- log-err! [msg]
  (binding [*out* *err*]
    (println (str "[escapement.debug.d2] " msg))))

(defn- run-d2!
  "Shells out to the `d2` binary. Returns
   `{:exit code :out stdout :err stderr}` on success, or
   `{:launch-error \"...\"}` when the binary couldn't be launched at all."
  [command layout d2-path svg-path]
  (let [argv [command (str "--layout=" layout) (str d2-path) (str svg-path)]]
    (try
      ;; With {:out :string :err :string} bp/process already gives us the
      ;; captured streams as plain strings — DO NOT slurp them (slurp would
      ;; treat the captured text as a filename and throw FileNotFoundException).
      (let [proc @(bp/process argv {:out :string :err :string})]
        {:exit (:exit proc)
         :out  (str (:out proc))
         :err  (str (:err proc))})
      (catch Throwable t
        (let [msg (str (.getName (class t)) ": " (.getMessage t))]
          (log-err! (str "d2 invocation failed: " msg))
          {:launch-error msg
           :argv         argv})))))

(defn- launch-viewer!
  "Fire-and-forget viewer launch. Spawns the configured command fully
   detached — stdio routed to DISCARD so the live TUI terminal is unaffected
   and the child survives independently of the parent process. Returns
   `{:ok? true :proc ... :cmd ...}` on success or
   `{:ok? false :error \"msg\" :cmd ...}` on failure."
  [tmpl svg-path]
  (let [cmd (config/expand-command tmpl (str svg-path))]
    (try
      (let [;; DISCARD is write-only — illegal for stdin. Read from /dev/null
            ;; so the child can't block on input regardless of what it does.
            devnull (java.io.File. "/dev/null")
            pb      (doto (ProcessBuilder. ^java.util.List ["bash" "-lc" cmd])
                      (.redirectInput devnull)
                      (.redirectOutput java.lang.ProcessBuilder$Redirect/DISCARD)
                      (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD))
            proc    (.start pb)]
        {:ok? true :proc proc :cmd cmd})
      (catch Throwable t
        (let [msg (str (.getName (class t)) ": " (.getMessage t))]
          (log-err! (str "viewer launch failed: " msg))
          {:ok? false :error msg :cmd cmd})))))

(defn render-and-open!
  "Writes `<session-dir>/chart.d2`, renders `chart.svg` via the `d2` binary,
   and (when an external viewer is configured) launches it in the
   background. Never throws.

   Honors `(:d2 cfg)`:

   * `:command` — d2 binary (default `\"d2\"`).
   * `:layout`  — layout engine (default `\"elk\"`).

   Returns one of:

   * `{:svg-path \"...\" :viewer-cmd \"...\"}` — rendered and viewer dispatched.
   * `{:svg-path \"...\" :internal? true}` — rendered; caller should display.
   * `{:svg-path \"...\" :error \"...\"}` — rendered but viewer launch failed.
   * `{:error \"...\"}` — d2 failed or wasn't installed."
  [chart active-config session-dir cfg]
  (try
    (let [dir      (.getAbsoluteFile (io/file (str session-dir)))
          _        (.mkdirs dir)
          d2-path  (.getAbsoluteFile (io/file dir "chart.d2"))
          svg-path (.getAbsoluteFile (io/file dir "chart.svg"))
          src      (chart->d2 chart active-config)
          d2-cfg   (:d2 cfg)
          command  (or (:command d2-cfg) "d2")
          layout   (or (:layout d2-cfg) "elk")]
      (spit d2-path src)
      (let [{:keys [exit out err launch-error argv] :as r}
            (run-d2! command layout d2-path svg-path)
            err-tail (when (seq err) (str " stderr: " (apply str (take 500 err))))
            out-tail (when (seq out) (str " stdout: " (apply str (take 500 out))))]
        (cond
          launch-error
          {:error (str "Could not launch `" command "`: " launch-error
                    ". argv=" (pr-str argv))}

          (and exit (not (zero? exit)))
          {:error (str "d2 exited with status " exit "." err-tail out-tail)}

          (not (.exists svg-path))
          {:error (str "d2 reported success but " (str svg-path) " was not created."
                    err-tail out-tail
                    " (Source written to " (str d2-path) " — try: "
                    command " --layout=" layout " " (str d2-path) " " (str svg-path) ")")}

          :else
          (let [viewer (config/viewer-for cfg (str svg-path))]
            (cond
              (= :internal viewer)
              {:svg-path (str svg-path) :internal? true}

              :else
              (let [{:keys [ok? error cmd]} (launch-viewer! viewer svg-path)]
                (if ok?
                  {:svg-path (str svg-path) :viewer-cmd cmd}
                  {:svg-path (str svg-path)
                   :error    (str "Viewer launch failed (" error "). "
                               "Try the command yourself: " cmd)})))))))
    (catch Throwable t
      (log-err! (str "render-and-open! crashed: " (.getMessage t)))
      {:error (str "Crashed: " (.getMessage t))})))
