(ns escapement.authoring.portability
  "Portability check for AI-written STEP CODE (a workflow's step namespace).

   Step code must be portable, cljc-safe Clojure: pure functions over the data they
   are given. It may not read or write files, run shell commands, start threads,
   load or redefine code at runtime, or use host (JVM/JS) interop. Files, shell and
   network go through approved tools instead (`escapement.chart.data/tool!`).

   `check` reads the source WITHOUT evaluating it and returns problems:

       {:level :error :code :not-portable :category :file-io
        :symbol \"slurp\" :line 12 :column 5
        :message \"Line 12: `slurp` reads or writes files. …\"}

   It is a guard-rail for honest mistakes, not a sandbox: the service still runs
   step code in its own process (see the brief's per-run subprocess mode)."
  (:require
    [clojure.string :as str]
    [edamame.core :as e]))

(def allowed-requires
  "Namespaces step code may require."
  #{'clojure.string 'clojure.set 'clojure.walk 'clojure.edn 'clojure.math
    'com.fulcrologic.statecharts.data-model.operations
    'escapement.chart.data})

(def ^:private denied
  "core symbol -> [category reason]"
  (merge
    (zipmap '[slurp spit file-seq line-seq with-open]
      (repeat [:file-io "reads or writes files"]))
    (zipmap '[sh shell process]
      (repeat [:shell "runs shell commands"]))
    (zipmap '[future future-call pmap pcalls pvalues agent send send-off send-via await
              await-for locking promise deliver]
      (repeat [:threads "starts threads or blocks on them"]))
    (zipmap '[eval load load-string load-file load-reader read read-string require use
              import in-ns intern create-ns remove-ns find-ns the-ns ns-publics ns-interns
              ns-resolve resolve find-var requiring-resolve]
      (repeat [:code-loading "loads, finds or evaluates code at runtime"]))
    (zipmap '[alter-var-root set! with-redefs with-redefs-fn var-set alter-meta! reset-meta!]
      (repeat [:redefinition "changes vars or other code while it runs"]))
    (zipmap '[new . .. memfn set-agent-send-executor! set-agent-send-off-executor!]
      (repeat [:interop "uses host (JVM/JS) interop"]))))

(def ^:private denied-namespaces
  "Namespace (or alias target) -> [category reason]."
  {"clojure.java.io"    [:file-io "reads or writes files"]
   "babashka.fs"        [:file-io "reads or writes files"]
   "clojure.java.shell" [:shell "runs shell commands"]
   "babashka.process"   [:shell "runs shell commands"]
   "clojure.core.async" [:threads "starts threads or blocks on them"]
   "js"                 [:interop "uses JavaScript interop"]})

(def ^:private allowed-classes
  "Class names tolerated as `catch` targets (portable via reader conditionals)."
  #{'Exception 'Throwable 'ExceptionInfo 'Object})

(defn- loc [x parent]
  (let [m (or (meta x) (meta parent))]
    {:line (:line m) :column (:column m)}))

(defn- problem [category sym reason {:keys [line column]}]
  {:level    :error
   :code     :not-portable
   :category category
   :symbol   (str sym)
   :line     line
   :column   column
   :message  (str (when line (str "Line " line ": "))
               "`" sym "` " reason ". Step code must be portable"
               (case category
                 :file-io " — read and write data through a tool instead."
                 :shell " — run commands through an approved tool instead."
                 :threads " — use workflow states (waits, outside calls) instead of threads."
                 :interop " (cljc-safe, no JVM/JS interop)."
                 :requires "."
                 "."))})

(defn- class-like? [s]
  (boolean (re-matches #"(?:[a-z][\w$]*\.)*[A-Z][\w$]*" s)))

(defn- symbol-problem
  "Problem for one symbol `s` (with `aliases` alias->ns-string), or nil."
  [s aliases parent]
  (let [nm (name s) ns (namespace s) where (loc s parent)]
    (cond
      ;; Qualified: alias/ns or Class/static.
      ns
      (let [target (get aliases ns ns)]
        (cond
          (contains? denied-namespaces target)
          (let [[c r] (get denied-namespaces target)] (problem c s r where))
          (and (#{"clojure.core" "cljs.core"} target) (contains? denied (symbol nm)))
          (let [[c r] (get denied (symbol nm))] (problem c s r where))
          (class-like? target)
          (problem :interop s "calls a host class" where)
          (and (not (contains? aliases ns))
            (not (contains? allowed-requires (symbol target)))
            (not (#{"clojure.core" "cljs.core"} target)))
          (problem :requires s (str "uses namespace " target ", which step code may not require") where)))

      (contains? denied s)
      (let [[c r] (get denied s)] (problem c s r where))

      (and (str/starts-with? nm ".") (> (count nm) 1) (not= nm ".."))
      (problem :interop s "calls a host method" where)

      (and (str/ends-with? nm ".") (> (count nm) 1))
      (problem :interop s "constructs a host object" where)

      (and (class-like? nm) (not (contains? allowed-classes s)))
      (problem :interop s "names a host class" where))))

(defn- ns-form-problems
  "Check the `(ns …)` form; return `{:aliases {alias ns} :problems [...]}`."
  [form]
  (let [clauses (filter seq? (drop 2 form))
        acc     (volatile! {:aliases {} :problems []})]
    (doseq [[k & specs :as clause] clauses]
      (case (keyword (name k))
        :require
        (doseq [spec specs]
          (let [[lib & opts] (if (sequential? spec) spec [spec])
                opts (apply hash-map (if (odd? (count opts)) (butlast opts) opts))]
            (when-let [a (:as opts)] (vswap! acc assoc-in [:aliases (str a)] (str lib)))
            (when-not (contains? allowed-requires lib)
              (let [[c r] (get denied-namespaces (str lib))]
                (vswap! acc update :problems conj
                  (problem (or c :requires) lib
                    (str (if r (str "is a namespace that " r ", and") "is")
                      " not an allowed requirement (allowed: "
                      (str/join ", " (sort (map str allowed-requires))) ")")
                    (loc lib clause)))))))
        :refer-clojure nil
        (vswap! acc update :problems conj
          (problem (if (= :import (keyword (name k))) :interop :requires) k
            "is not allowed in a step namespace's ns form" (loc k clause)))))
    @acc))

(defn- walk-problems [form aliases]
  (let [out (volatile! [])]
    (letfn [(walk [x parent]
              (cond
                (symbol? x) (when-let [p (symbol-problem x aliases parent)] (vswap! out conj p))
                (seq? x)    (do
                              ;; (catch Exception e …): tolerate the class symbol.
                              (if (and (= 'catch (first x)) (symbol? (second x)))
                                (doseq [y (drop 2 x)] (walk y x))
                                (doseq [y x] (walk y x))))
                (map? x)    (doseq [[k v] x] (walk k parent) (walk v parent))
                (coll? x)   (doseq [y x] (walk y parent))
                :else nil))]
      (walk form nil))
    @out))

(defn parse
  "Read `source` into forms with `:line`/`:column` metadata, without evaluating.
   Returns `{:forms [...]} | {:error {...problem}}`."
  [source]
  (try
    {:forms (e/parse-string-all source
              {:all          true
               :row-key      :line
               :col-key      :column
               :read-cond    :allow
               :features     #{:clj}
               :auto-resolve (fn [x] (if (= :current x) 'steps x))
               :readers      (fn [t] (fn [v] (list 'tagged-literal (list 'quote t) v)))})}
    (catch Exception ex
      (let [{:keys [row col]} (ex-data ex)]
        {:error {:level :error :code :syntax :line row :column col
                 :message (str (when row (str "Line " row ": ")) "the step code does not read: "
                            (ex-message ex))}}))))

(defn check
  "Portability problems in step-namespace `source` (a string). `[]` when it is
   portable. The first form must be the `ns` form."
  [source]
  (let [{:keys [forms error]} (parse source)]
    (cond
      error [error]
      (not (and (seq? (first forms)) (= 'ns (ffirst forms))))
      [{:level :error :code :no-ns :line 1 :column 1
        :message "Step code must start with an (ns …) form."}]
      :else
      (let [{:keys [aliases problems]} (ns-form-problems (first forms))
            more (mapcat #(walk-problems % aliases) (rest forms))
            extra-ns (for [f (rest forms) :when (and (seq? f) (#{'ns 'in-ns} (first f)))]
                       {:level :error :code :many-ns :line (:line (meta f)) :column (:column (meta f))
                        :message (str "Line " (:line (meta f)) ": a step file has exactly one namespace.")})]
        (vec (concat problems extra-ns more))))))
