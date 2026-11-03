(ns escapement.authoring.loader
  "Loads a workflow version's STEP NAMESPACE under a name unique to that version.

   Each version of a data-built chart is a fixed snapshot (its commit). Its step
   file is loaded as `wf.<project>.v<sha12>` — the file's own `ns` name is
   rewritten — via `load-string`, never `require`, and never reloaded in place: a
   run pinned to v3 keeps calling v3's functions while v4 is deployed beside it
   (reloading one namespace would make v3's inner calls reach v4's code). Each
   version also gets its own chart id, `:wf.<project>/v<sha12>`, so a checkpoint
   names the version it runs.

   SCI namespace state is process-global, so loads are serialized by one lock.

       (def sym (version-ns \"email-triage\" sha))           ;; wf.email-triage.v1a2b3c4d5e6f
       (load-steps! {:source (slurp \"workflow/steps.cljc\") :ns-sym sym})
       ;; => {:ok? true :ns sym :publics {'classify-prompt #fn …}}
       (version-chart-id \"email-triage\" sha)                ;; :wf.email-triage/v1a2b3c4d5e6f"
  (:require
    [clojure.string :as str]
    [escapement.authoring.portability :as portability]))

(defonce ^:private load-lock (Object.))

(defonce ^:private loaded
  ;; ns-sym -> source string loaded under it
  (atom {}))

(defn- slug [s]
  (let [s (-> (str s) str/lower-case (str/replace #"[^a-z0-9-]+" "-") (str/replace #"^-+|-+$" ""))]
    (if (or (str/blank? s) (re-find #"^[0-9]" s)) (str "p-" s) s)))

(defn- short-sha [sha]
  (let [s (str/lower-case (str sha))]
    (subs s 0 (min 12 (count s)))))

(defn version-ns
  "The step-namespace symbol for `project` at commit `sha`: `wf.<project>.v<sha12>`."
  [project sha]
  (symbol (str "wf." (slug project) ".v" (short-sha sha))))

(defn version-chart-id
  "The chart id for `project` at commit `sha`: `:wf.<project>/v<sha12>`."
  [project sha]
  (keyword (str "wf." (slug project)) (str "v" (short-sha sha))))

(defn rename-ns
  "Rewrite the `(ns <name> …)` head of `source` to `ns-sym`. Returns the new
   source, or nil when the source does not start with an ns form."
  [source ns-sym]
  (let [m (re-matcher #"(?s)\A((?:\s|;[^\n]*\n|#_\S+)*\(\s*ns\s+)([^\s()\[\]{}\"]+)" source)]
    (when (.find m)
      (str (.group m 1) ns-sym (subs source (.end m 2))))))

(defn loaded?
  "True when `ns-sym` was loaded by this loader and is still present."
  [ns-sym]
  (boolean (and (contains? @loaded ns-sym) (find-ns ns-sym))))

(defn- publics [ns-sym]
  (into {} (keep (fn [[k v]] (let [f @v] (when (fn? f) [k f])))) (ns-publics ns-sym)))

(defn load-steps!
  "Load step-namespace `source` as `ns-sym`.

   `opts`: `:source` (string, required), `:ns-sym` (required), `:check?` (run the
   portability check first; default true).

   Returns `{:ok? true :ns ns-sym :publics {sym fn}}`, or `{:ok? false :problems [...]}`:
     * `:not-portable` / `:syntax` / `:no-ns` — the portability check failed;
     * `:already-loaded-different-source` — `ns-sym` is already loaded with other
       source; it is never reloaded in place (pick a new version);
     * `:ns-exists` — `ns-sym` exists but this loader didn't load it;
     * `:load-failed` — evaluating the code threw (the half-loaded ns is removed).
   Loading the same source under the same name again returns the existing ns."
  [{:keys [source ns-sym check?] :or {check? true}}]
  (assert (string? source) "load-steps! needs :source")
  (assert (symbol? ns-sym) "load-steps! needs :ns-sym")
  (locking load-lock
    (cond
      (and (loaded? ns-sym) (= source (get @loaded ns-sym)))
      {:ok? true :ns ns-sym :publics (publics ns-sym) :reused? true}

      (loaded? ns-sym)
      {:ok? false
       :problems [{:level :error :code :already-loaded-different-source
                   :message (str ns-sym " is already loaded with different step code. A version is"
                              " never reloaded in place; load the new code as a new version.")}]}

      (find-ns ns-sym)
      {:ok? false
       :problems [{:level :error :code :ns-exists
                   :message (str "A namespace called " ns-sym " already exists in this process.")}]}

      :else
      (let [problems (when check? (portability/check source))
            renamed  (rename-ns source ns-sym)]
        (cond
          (seq problems) {:ok? false :problems problems}
          (nil? renamed) {:ok? false
                          :problems [{:level :error :code :no-ns :line 1 :column 1
                                      :message "Step code must start with an (ns …) form."}]}
          :else
          (try
            (binding [*ns* *ns*]
              (load-string renamed))
            (if (find-ns ns-sym)
              (do (swap! loaded assoc ns-sym source)
                  {:ok? true :ns ns-sym :publics (publics ns-sym)})
              {:ok? false
               :problems [{:level :error :code :load-failed
                           :message (str "Loading the step code did not create " ns-sym ".")}]})
            (catch Exception ex
              (when (find-ns ns-sym) (remove-ns ns-sym))
              {:ok? false
               :problems [(let [{:keys [line column]} (ex-data ex)]
                            (cond-> {:level :error :code :load-failed
                                     :message (str (when line (str "Line " line ": "))
                                                "the step code failed to load: " (ex-message ex))}
                              line (assoc :line line :column column)))]})))))))

(defn unload!
  "Remove a loaded version's namespace. Only call it when no run is pinned to it."
  [ns-sym]
  (locking load-lock
    (swap! loaded dissoc ns-sym)
    (when (find-ns ns-sym) (remove-ns ns-sym))
    nil))
