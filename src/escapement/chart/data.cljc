(ns escapement.chart.data
  "Charts built from DATA: a workflow SHAPE (EDN) plus a namespace of step functions.

   The shape names states, their children, transitions, events, delays and
   invocations, and refers to guards and actions BY KEYWORD. Building resolves every
   keyword to the function it names in the step namespace (unqualified keywords) or
   in a `:builtins` vocabulary map (qualified keywords). A reference that names no
   function is reported as a problem — never left as a literal. The result is an
   ordinary statechart value: it runs, resumes and is inspected like a hand-written
   chart. Labels and conditions from the shape land on the states and transitions
   (`:diagram/label`, `:diagram/condition`).

   ## Shape

       {:id :email-triage
        :initial :fetch
        :data {:attempts 0}                       ; defaults under the run's :initial-data
        :states
        [{:id :fetch :label \"Get new emails\" :entry [:load-inbox]
          :on [{:event :inbox/loaded :target :classify :actions [:store]}]}
         {:id :classify :label \"Classify\"
          :ai {:system \"…\" :message {:step :prompt} :verdict-schema [:map …]
               :events [{:event :triage/spam :schema [:map]}] :tools [:kb/search]}
          :on [{:event :llm.idle :guard :confident? :condition \"confidence ≥ 0.9\" :target :route}
               {:event :llm.idle :target :review}
               {:event :error.llm :target :failed}]}
         {:id :lookup :call {:tool :crm/lookup :input {:step :crm-input} :timeout-ms 10000}
          :retry {:max 3 :backoff-ms 1000 :factor 2 :on-exhausted :failed}
          :on [{:event :call.done :target :reply}]}
         {:id :await :wait {:timeout-ms 172800000 :on-timeout :expired}
          :on [{:event :approval/answered :guard :approved? :target :send}]}
         {:id :cooldown :on [{:after 3600000 :target :fetch}]}
         {:id :done :type :final}]}

   State keys: `:id` `:type` (`:state` `:parallel` `:final`) `:label` `:detail`
   (`:business`|`:full`) `:states` `:initial` `:entry` `:exit` `:on` and at most one of
   `:ai` (an `:llm-conversation` invoke), `:call` (a `:tool-call` invoke) or `:invoke`
   (raw: `{:type kw :id s :params {k literal-or-ref}}`), plus `:wait` and `:retry`.

   Transition keys: `:event` (kw or vector), `:target` (kw or vector; omit for a
   targetless transition), `:guard`, `:actions`, `:label`, `:condition`, `:type`,
   `:after` (ms). Inside a `:call` state `:call.done` / `:call.error` name that call's
   done / error events.

   ## Step functions

   Every step fn takes `[env data]`. `data` is the data model; the triggering event
   is `(:_event data)`.
     * action — returns nil, a vector of statecharts ops, or a MAP whose entries are
       assigned into the data model (the recommended form);
     * guard  — returns truthy/falsey;
     * value  — `{:step :fn}` in a param position; returns the param's value.
   Effects go through tools: `(tool! env :email/send input)`.

   ## API

   `compile`, `compile!`, `validate`, `diagram`, `resolver`."
  (:refer-clojure :exclude [compile])
  (:require
    [clojure.string :as str]
    [com.fulcrologic.statecharts.algorithms.v20150901-validation :as v]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.convenience :as conv]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :as elt]
    [com.fulcrologic.statecharts.environment :as env-ns]
    [escapement.chart.check :as check]
    [escapement.chart.helpers :as h]
    [escapement.tools.protocol :as tp]))

;; ---------------------------------------------------------------------------
;; Step-fn helpers (for step namespaces)
;; ---------------------------------------------------------------------------

(defn tool!
  "Call tool `tool-kw` with `input` through the run's tool registry. Returns
   `{:result string :is-error bool}`. In tests and dry runs the registry holds
   pretend tools. Throws when the run has no tool registry."
  [env tool-kw input]
  (if-let [reg (:escapement/tool-registry env)]
    (tp/dispatch reg tool-kw (or input {}))
    (throw (ex-info (str "tool! " tool-kw ": this run has no :tool-registry")
             {:tool tool-kw}))))

(defn event
  "The triggering event of a step: `{:name kw :data map}`."
  [data]
  (:_event data))

(defn event-data
  "The triggering event's data."
  [data]
  (get-in data [:_event :data]))

;; ---------------------------------------------------------------------------
;; Reference resolution
;; ---------------------------------------------------------------------------

(def std-builtins
  "The generic vocabulary every shape may use (qualified `:std/*` keywords)."
  {:std/always   (fn [_ _] true)
   :std/never    (fn [_ _] false)
   :std/noop     (fn [_ _] nil)
   :std/store-event-data
   (fn [_ data] (let [d (event-data data)] (when (map? d) d)))})

(defn- steps-map
  "Normalize `:steps` to `{\"name\" fn}`."
  [steps]
  (cond
    (nil? steps) {}
    (map? steps) (reduce-kv (fn [m k v] (assoc m (name k) v)) {} steps)
    #?@(:clj [(symbol? steps)
              (if-let [ns (find-ns steps)]
                (reduce-kv (fn [m k v] (let [f @v] (if (fn? f) (assoc m (name k) f) m)))
                  {} (ns-publics ns))
                (throw (ex-info (str "Step namespace " steps " is not loaded") {:steps steps})))])
    :else (throw (ex-info "`:steps` must be a loaded namespace symbol or a map of name -> fn"
                   {:steps steps}))))

(defn resolver
  "A `(fn [kw] f-or-nil)` over the step fns (`:steps`) and vocabulary (`:builtins`
   merged over `std-builtins`)."
  [{:keys [steps builtins]}]
  (let [sm (steps-map steps)
        bm (merge std-builtins builtins)]
    (fn [kw]
      (when (keyword? kw)
        (if (namespace kw)
          (get bm kw)
          (get sm (name kw)))))))

(defn- available-names [opts]
  (keys (steps-map (:steps opts))))

;; ---------------------------------------------------------------------------
;; Element building
;; ---------------------------------------------------------------------------

(defn- ->ops
  "Normalize an action's return: nil, an ops vector, or a map of assignments."
  [x]
  (cond
    (nil? x) nil
    (map? x) (if (contains? x :op) [x] (ops/set-map-ops x))
    (sequential? x) (vec x)
    :else nil))

(defn- action-script [f kw]
  (elt/script {:diagram/label (name kw)
               :expr          (fn [env data] (->ops (f env data)))}))

(defn- transcript! [env row]
  (when-let [tf (:escapement/transcript-fn env)]
    (try (tf row) (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn- guard-fn
  "Wrap guard `f` so each decision is logged as a `:chart/guard` transcript row."
  [f {:keys [kw state targets condition log?]}]
  (if-not log?
    (fn [env data] (boolean (f env data)))
    (fn [env data]
      (let [r (boolean (f env data))]
        (transcript! env
          {:event :chart/guard
           :data  {:session-id (str (env-ns/session-id env))
                   :state      state
                   :event      (get-in data [:_event :name])
                   :target     targets
                   :guard      kw
                   :condition  condition
                   :result     r}})
        r))))

(defn- value-fn
  "A param value: a literal, or `{:step kw}` resolved to `(fn [env data])`."
  [resolve v]
  (if (and (map? v) (= [:step] (keys v)))
    (let [f (resolve (:step v))] (fn [env data] (f env data)))
    v))

(defn- resolve-values [resolve m]
  (reduce-kv (fn [acc k v] (assoc acc k (value-fn resolve v))) {} m))

(defn- humanize [id]
  (let [s (str/replace (name id) #"[-_.]" " ")]
    (str (str/upper-case (subs s 0 (min 1 (count s)))) (subs s (min 1 (count s))))))

(defn- state-label [s] (or (:label s) (humanize (:id s))))

(defn- attempts-key [] :escapement/attempts)

(defn- attempts [data id] (get-in data [(attempts-key) id] 0))

(defn- set-attempts [data id n]
  (ops/set-map-ops {(attempts-key) (assoc (or (get data (attempts-key)) {}) id n)}))

(defn- backoff-ms [{:keys [backoff-ms factor max-backoff-ms] :or {backoff-ms 1000 factor 2}} n]
  (let [ms (* backoff-ms #?(:clj (Math/pow (double factor) (max 0 (dec n)))
                               :cljs (js/Math.pow factor (max 0 (dec n)))))]
    (long (if max-backoff-ms (min ms max-backoff-ms) ms))))

(defn- build-transition
  [{:keys [resolve log?]} t]
  (let [{:keys [source events targets guard actions label condition type generated raw]} t
        retry   (:retry-cfg t)
        cond-fn (cond
                  (= guard ::check/retries-left)
                  (fn [_ data] (< (attempts data source) (:max retry)))
                  guard
                  (guard-fn (resolve guard) {:kw guard :state source :targets targets
                                             :condition (or condition (name guard)) :log? log?}))
        children (concat
                   (map (fn [a] (action-script (resolve a) a)) actions)
                   (case generated
                     :retry [(elt/script {:diagram/label "count attempt"
                                          :expr (fn [_ data] (set-attempts data source (inc (attempts data source))))})]
                     :retry-exhausted [(elt/script {:diagram/label "reset attempts"
                                                    :expr (fn [_ data] (set-attempts data source 0))})]
                     nil))
        attrs (cond-> {}
                (seq events) (assoc :event (if (= 1 (count events)) (first events) (vec events)))
                (seq targets) (assoc :target (vec targets))
                cond-fn (assoc :cond cond-fn)
                label (assoc :diagram/label label)
                (or condition guard) (assoc :diagram/condition
                                       (or condition (when (and guard (not= guard ::check/retries-left))
                                                       (name guard))
                                         (when (= guard ::check/retries-left) "attempts left")))
                type (assoc :type type))]
    (apply elt/transition attrs children)))

(defn- timer-pieces
  "Entry sends + exit cancels for a state's delayed transitions; returns
   `{:entries [el] :exits [el] :transitions [norm-t with :events rewritten]}`."
  [resolve id transitions retry-cfg]
  (reduce
    (fn [acc [i t]]
      (cond
        (= :backoff (:after t))
        (let [send-id (str "retry-backoff:" (check/id-token id))
              ev      (check/after-event id i)
              target  (first (:targets t))]
          (-> acc
            (update :entries conj (elt/Send {:id        send-id
                                             :event     ev
                                             :delayexpr (fn [_ data]
                                                          (backoff-ms (get retry-cfg target)
                                                            (attempts data target)))}))
            (update :exits conj (elt/cancel {:sendid send-id}))
            (update :transitions conj (assoc t :events [ev]))))

        (:after t)
        (let [ev      (if (= :wait-timeout (:generated t)) (first (:events t)) (check/after-event id i))
              send-id (str "after:" (check/id-token id) ":" i)
              after   (:after t)
              timing  (if (and (map? after) (= [:step] (keys after)))
                        ;; A computed delay: (fn [env data] ms).
                        (let [f (resolve (:step after))]
                          {:delayexpr (fn [env data] (long (or (f env data) 0)))})
                        {:delay (long after)})]
          (-> acc
            (update :entries conj (elt/Send (merge {:id send-id :event ev} timing)))
            (update :exits conj (elt/cancel {:sendid send-id}))
            (update :transitions conj (assoc t :events [ev]))))

        :else (update acc :transitions conj t)))
    {:entries [] :exits [] :transitions []}
    (map-indexed vector transitions)))

(defn- event-decl
  "A shape event declaration `{:event kw :description s :schema m}` as an
   llm-conversation `:allowed-events` entry."
  [e]
  (-> (dissoc e :schema)
    (assoc :data-schema (or (:schema e) (:data-schema e) [:map]))))

(defn- ai-invoke [resolve s]
  (let [{:keys [ai]} s
        id     (check/invoke-id s :ai)
        params (resolve-values resolve (dissoc ai :id :events :tools :schema))
        params (cond-> (assoc params :real-tools (vec (:tools ai)))
                 (:events ai) (assoc :allowed-events (mapv event-decl (:events ai))))]
    (h/llm-conversation (assoc params :id id))))

(defn- call-invoke [resolve s]
  (let [{:keys [call]} s
        id (check/invoke-id s :call)
        params (resolve-values resolve (dissoc call :id))]
    (elt/invoke {:type   :tool-call
                 :id     id
                 :params (fn [env data]
                           (reduce-kv (fn [m k v] (assoc m k (if (fn? v) (v env data) v))) {} params))})))

(defn- raw-invoke [resolve s]
  (let [{:keys [invoke]} s
        id (check/invoke-id s :invoke)
        params (resolve-values resolve (or (:params invoke) {}))]
    (elt/invoke (cond-> {:type   (:type invoke)
                         :id     id
                         :params (fn [env data]
                                   (reduce-kv (fn [m k v] (assoc m k (if (fn? v) (v env data) v))) {} params))}
                  (contains? invoke :autoforward) (assoc :autoforward (:autoforward invoke))))))

(defn- build-state
  [{:keys [resolve idx] :as ctx} id]
  (let [{:keys [kind children src transitions generated]} (get-in idx [:states id])
        retry-cfg (into {} (for [[sid st] (:states idx) :when (:retry (:src st))] [sid (:retry (:src st))]))
        trans     (map (fn [t] (cond-> t (#{:retry :retry-exhausted} (:generated t))
                                 (assoc :retry-cfg (:retry src))))
                    transitions)
        {:keys [entries exits] trans' :transitions} (timer-pieces resolve id trans retry-cfg)
        entry-acts (map (fn [a] (action-script (resolve a) a)) (:entry src))
        exit-acts  (map (fn [a] (action-script (resolve a) a)) (:exit src))
        ;; A retrying state forgets its attempts when it is left for good (any
        ;; exit not caused by an error event).
        retry-reset (when (:retry src)
                      [(elt/script {:diagram/label "reset attempts"
                                    :expr (fn [_ data]
                                            (when-not (check/error-event? (get-in data [:_event :name]))
                                              (set-attempts data id 0)))})])
        ;; Every entry leaves a `:chart/entered` row, so the exact path is on
        ;; record — including states passed through within one step (which a
        ;; before/after configuration diff can't see) and the final state a
        ;; finished chart ended in (its configuration is then empty).
        entry-mark (when (:trace-entries? ctx)
                     [(elt/script {:diagram/label "entered"
                                   :expr (fn [env _]
                                           (transcript! env {:event :chart/entered
                                                             :data  (cond-> {:session-id (str (env-ns/session-id env))
                                                                             :state      id}
                                                                      (= kind :final) (assoc :final? true))})
                                           nil)})])
        entry      (when (seq (concat entries entry-acts entry-mark))
                     (apply elt/on-entry {} (concat entry-mark entry-acts entries)))
        exit       (when (seq (concat exits exit-acts retry-reset))
                     (apply elt/on-exit {} (concat exits exit-acts retry-reset)))
        inv        (case (:invoke (get-in idx [:states id]))
                     :ai (ai-invoke resolve src)
                     :call (call-invoke resolve src)
                     :invoke (raw-invoke resolve src)
                     nil)
        output     (when-let [o (:output src)]
                     (elt/done-data {:expr (let [f (value-fn resolve o)]
                                             (if (fn? f) f (fn [_ _] f)))}))
        kids       (map #(build-state ctx %) children)
        ;; Generated retry-wait siblings follow their state.
        retry-kids (for [c children
                         :when (:retry (get-in idx [:states c :src]))]
                     (build-state ctx (check/retry-state-id c)))
        attrs      (cond-> {:id id :diagram/label (state-label src)}
                     (:detail src) (assoc :diagram/detail (:detail src))
                     (and (= kind :state) (seq children)) (assoc :initial (get-in idx [:states id :initial]))
                     generated (assoc :escapement/generated generated))
        body       (concat (remove nil? [entry exit inv output])
                     (map #(build-transition ctx %) trans')
                     kids retry-kids)]
    (case kind
      :final (apply elt/final (dissoc attrs :initial) (remove nil? [entry exit output]))
      :parallel (apply elt/parallel (dissoc attrs :initial) body)
      (apply elt/state attrs body))))

(defn- top-level-ids [idx]
  (filter #(nil? (get-in idx [:states % :parent])) (:order idx)))

(defn- build-chart [shape idx resolve {:keys [log? trace-entries?]}]
  (let [ctx  {:resolve resolve :idx idx :log? log? :trace-entries? trace-entries?}
        tops (top-level-ids idx)]
    (apply chart/statechart
      (cond-> {:initial (:initial idx)}
        (:id shape) (assoc :escapement/workflow (:id shape))
        (:data shape) (assoc :escapement/default-data (:data shape)))
      (map #(build-state ctx %) tops))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn- has-errors? [problems] (boolean (some #(= :error (:level %)) problems)))

(defn validate
  "Every problem with `shape` against `opts` (`:steps`, `:builtins`): shape and
   structural checks plus unresolved step references. Returns `[problem …]`."
  [shape opts]
  (let [idx     (check/index shape)
        resolve (resolver opts)]
    (vec (concat (check/structural-problems idx)
           (check/reference-problems idx resolve (available-names opts))))))

(defn- library-problems [c]
  (try
    (mapv (fn [{:keys [level element message]}]
            (check/problem level :statecharts (:id element) nil message))
      (v/problems c))
    (catch #?(:clj Throwable :cljs :default) e
      [(check/problem :error :statecharts nil nil (str "The statecharts library rejected the chart: "
                                                     (ex-message e)))])))

(defn compile
  "Build a chart from `shape` and step fns.

   `opts`:
     * `:steps`      — a loaded step-namespace symbol, or a map `{name fn}`;
     * `:builtins`   — `{qualified-kw fn}` vocabulary (merged over `std-builtins`);
     * `:chart-id`   — the version's chart id, returned as-is for the host to pass to
                       `run` (`escapement.authoring.loader/version-chart-id`);
     * `:guard-log?` — log each guard decision as a `:chart/guard` row (default true);
     * `:trace-entries?` — log each state entry as a `:chart/entered` row (default
                       true): the exact path, finals included.

   Returns `{:ok? bool :chart chart-or-nil :chart-id id :problems [problem …]}`.
   A shape with errors builds no chart (`:chart nil`); warnings don't block. Never
   throws for mistakes in the shape."
  [shape {:keys [chart-id guard-log? trace-entries?] :or {guard-log? true trace-entries? true} :as opts}]
  (let [problems (validate shape opts)]
    (if (has-errors? problems)
      {:ok? false :chart nil :chart-id chart-id :problems problems}
      (let [idx     (check/index shape)
            resolve (resolver opts)
            built   (try {:chart (build-chart shape idx resolve {:log? guard-log?
                                                                  :trace-entries? trace-entries?})}
                         (catch #?(:clj Throwable :cljs :default) e
                           {:problems [(check/problem :error :build nil nil
                                         (str "Building the chart failed: " (ex-message e)))]}))
            lib     (when (:chart built) (library-problems (:chart built)))
            all     (vec (concat problems (:problems built) lib))]
        (if (has-errors? all)
          {:ok? false :chart nil :chart-id chart-id :problems all}
          {:ok? true :chart (:chart built) :chart-id chart-id :problems all})))))

(defn compile!
  "Like `compile` but returns the chart, or throws ex-info carrying `:problems`."
  [shape opts]
  (let [{:keys [ok? chart problems]} (compile shape opts)]
    (if ok?
      chart
      (throw (ex-info (str "The workflow shape has problems:\n"
                        (str/join "\n" (map #(str "- " (:message %)) (filter #(= :error (:level %)) problems))))
               {:problems problems})))))

(defn initial-data
  "The shape's `:data` defaults under a run's `initial-data` (the run wins)."
  [shape initial-data]
  (merge (:data shape) initial-data))

;; ---------------------------------------------------------------------------
;; Diagram
;; ---------------------------------------------------------------------------

(defn- node-kind [{:keys [kind invoke children src]}]
  (cond
    (= kind :final) :final
    (= kind :parallel) :parallel
    (= invoke :ai) :ai
    (= invoke :call) :call
    (= invoke :invoke) :invoke
    (:wait src) :wait
    (seq children) :compound
    :else :action))

(defn diagram
  "A plain graph of `shape` for drawing: `{:nodes [...] :edges [...] :initial id}`.

   Nodes: `{:id :label :kind :parent :detail :generated?}` — kind is one of
   `:ai :call :invoke :wait :action :compound :parallel :final`.
   Edges: `{:id :from :to :events :label :condition :error? :timer? :generated?}`.

   `{:detail :business}` (default) hides `:detail :full` states and generated
   states, reconnecting the arrows around them; `{:detail :full}` shows all."
  ([shape] (diagram shape {}))
  ([shape {:keys [detail] :or {detail :business}}]
   (let [idx   (check/index shape)
         nodes (vec (for [id (:order idx)
                          :let [s (get-in idx [:states id])]]
                      (cond-> {:id id :label (state-label (:src s)) :kind (node-kind s)
                               :parent (:parent s) :detail (or (:detail (:src s)) (if (:generated s) :full :business))
                               :generated? (boolean (:generated s))}
                        ;; Host vocabulary keys on the state (qualified, e.g.
                        ;; :sova/kind) ride along for the host's own drawing.
                        (seq (filter qualified-keyword? (keys (:src s))))
                        (assoc :meta (select-keys (:src s) (filter qualified-keyword? (keys (:src s))))))))
         edges (vec (for [id (:order idx)
                          [i t] (map-indexed vector (get-in idx [:states id :transitions]))
                          tgt (or (seq (:targets t)) [nil])]
                      {:id         (str (check/id-token id) "->" (some-> tgt check/id-token) "#" i)
                       :from       id
                       :to         tgt
                       :events     (:events t)
                       :label      (:label t)
                       :condition  (or (:condition t) (when (and (:guard t) (keyword? (:guard t))
                                                             (not= (:guard t) ::check/retries-left))
                                                        (name (:guard t))))
                       :error?     (boolean (:error? t))
                       :timer?     (boolean (:after t))
                       :generated? (boolean (:generated t))}))]
     (if (= detail :full)
       {:initial (:initial idx) :nodes nodes :edges edges}
       (let [hidden (set (map :id (filter #(= :full (:detail %)) nodes)))
             ;; Reconnect around hidden nodes: in -> hidden -> out becomes in -> out.
             edges' (loop [es edges guard 0]
                      (let [h (some (fn [e] (when (hidden (:to e)) e)) es)]
                        (if (or (nil? h) (> guard 1000))
                          es
                          (let [outs (filter #(and (= (:from %) (:to h)) (not= (:to %) (:to h))) es)
                                repl (map (fn [o] (assoc h :to (:to o) :id (str (:id h) "+" (:id o)))) outs)]
                            (recur (concat (remove #(= % h) es) repl) (inc guard))))))]
         {:initial (:initial idx)
          :nodes   (vec (remove #(hidden (:id %)) nodes))
          :edges   (vec (distinct (remove #(or (hidden (:from %)) (hidden (:to %))) edges')))})))))
