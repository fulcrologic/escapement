(ns escapement.chart.check
  "Structural checks for workflow SHAPES (see `escapement.chart.data`).

   `index` normalizes a shape into a flat table of states and transitions —
   including the pieces the compiler generates (wait timeouts, retry waits) — and
   `problems` runs the checks over it, returning every problem it finds as data:

       {:level :error|:warn :code kw :state kw-or-nil :at [path in the shape]
        :message \"a readable sentence\"}

   Checks: shape errors (missing/duplicate ids, bad keys), unknown targets and
   initial states, unknown step references, every state reachable from the initial
   state, no stuck non-final state, an error exit on every state that invokes
   something (AI step, outside call, other invoke), bounded retries (no automatic
   error loop without a guard), no `:human-input` invokes, and parallels with at
   least two regions. Pure data in, data out."
  (:require
    [clojure.set :as set]
    [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Small helpers
;; ---------------------------------------------------------------------------

(defn- as-vec [x] (cond (nil? x) [] (sequential? x) (vec x) :else [x]))

(defn kw-str
  "Printable form of a state id / event for messages."
  [x]
  (if (keyword? x) (str x) (pr-str x)))

(defn id-token
  "A dot-free token for a state id, used in generated event and element names."
  [id]
  (-> (if (keyword? id)
        (str (when (namespace id) (str (namespace id) "_")) (name id))
        (str id))
    (str/replace #"[^A-Za-z0-9_\-]" "_")))

(defn problem
  "Construct a problem map."
  [level code state at message]
  (cond-> {:level level :code code :message message}
    state (assoc :state state)
    at (assoc :at at)))

(defn error-event?
  "True when event keyword `e` is an error event (`:error` or `:error.<…>`)."
  [e]
  (and (keyword? e) (nil? (namespace e))
    (let [n (name e)] (or (= n "error") (str/starts-with? n "error.")))))

(defn- event-covers?
  "True when a transition on `declared` (prefix match, `*` allowed) receives
   every event in the family named by `family` (e.g. `\"error.llm\"`)."
  [declared family]
  (when (keyword? declared)
    (let [d (str/replace (name declared) #"\.\*$" "")]
      (or (= d "*")
        (= d family)
        (str/starts-with? family (str d "."))))))

(def invoke-error-families
  "The error event family each invoking kind raises."
  {:ai "error.llm" :call "error.call"})

(defn call-done-event
  "The done event of the outside call whose invoke id is `call-id`."
  [call-id]
  (keyword (str "done.invoke." call-id)))

(defn invoke-id
  "The invoke id of an `:ai`/`:call`/`:invoke` state (a string)."
  [{:keys [id] :as state} kind]
  (str (or (get-in state [kind :id]) (id-token id))))

(defn retry-state-id
  "Id of the generated wait state between retries of state `id`."
  [id]
  (keyword (str (id-token id) ".retry-wait")))

(defn after-event [id i] (keyword (str "after." (id-token id) "." i)))
(defn wait-timeout-event [id] (keyword (str "wait.timeout." (id-token id))))

;; ---------------------------------------------------------------------------
;; Index
;; ---------------------------------------------------------------------------

(defn- invoke-kind [s]
  (cond (contains? s :ai) :ai (contains? s :call) :call (contains? s :invoke) :invoke))

(defn- event-aliases
  "Expand the shape's event aliases for a state."
  [s kind events]
  (mapv (fn [e]
          (cond
            (and (= kind :call) (= e :call.done)) (call-done-event (invoke-id s :call))
            (and (= kind :call) (= e :call.error)) :error.call
            :else e))
    events))

(defn- norm-transition [s kind i t at]
  (let [events (event-aliases s kind (as-vec (:event t)))]
    {:source     (:id s)
     :events     events
     :targets    (as-vec (:target t))
     :guard      (:guard t)
     :actions    (as-vec (:actions t))
     :label      (:label t)
     :condition  (:condition t)
     :after      (:after t)
     :type       (:type t)
     :index      i
     :at         (conj at :on i)
     :error?     (boolean (some error-event? events))
     ;; Fires without an outside event: a timer, an error, or eventless.
     :automatic? (boolean (or (:after t) (empty? events) (some error-event? events)))
     :raw        t}))

(defn- generated-transitions
  "Transitions the compiler adds for `:wait` timeouts and `:retry`."
  [s kind at]
  (let [{:keys [id wait retry]} s
        fam (get invoke-error-families kind)]
    (cond-> []
      (and wait (:timeout-ms wait) (:on-timeout wait))
      (conj {:source id :events [(wait-timeout-event id)] :targets (as-vec (:on-timeout wait))
             :label (or (:label wait) "timed out") :at (conj at :wait) :generated :wait-timeout
             :automatic? true :after (:timeout-ms wait)})

      (and retry fam)
      (into [{:source id :events [(keyword fam)] :targets [(retry-state-id id)]
              :guard ::retries-left :label "retry" :at (conj at :retry) :generated :retry
              :error? true :automatic? true}
             {:source id :events [(keyword fam)] :targets (as-vec (:on-exhausted retry))
              :label (or (:label retry) "gave up") :at (conj at :retry) :generated :retry-exhausted
              :error? true :automatic? true}]))))

(defn index
  "Normalize `shape` into
     {:initial kw
      :states  {id {:id :kind :parent :children :initial :invoke :src :at :transitions}}
      :order   [id …]           ; document order
      :problems [shape problems found while indexing]}"
  [shape]
  (let [acc (volatile! {:states {} :order [] :problems []})
        add-problem! (fn [p] (vswap! acc update :problems conj p))]
    (letfn [(walk [states parent at]
              (doall
                (map-indexed
                  (fn [i s]
                    (let [at (conj at :states i)]
                      (cond
                        (not (map? s))
                        (add-problem! (problem :error :bad-state nil at
                                        (str "Expected a state map at " at ", got " (pr-str s) ".")))

                        (not (keyword? (:id s)))
                        (add-problem! (problem :error :missing-id nil at
                                        (str "A state at " at " has no keyword :id.")))

                        (contains? (:states @acc) (:id s))
                        (add-problem! (problem :error :duplicate-id (:id s) at
                                        (str "Two states are both called " (kw-str (:id s))
                                          "; state ids must be unique.")))

                        :else
                        (let [{:keys [id type]} s
                              kind     (case type :final :final :parallel :parallel :state)
                              ik       (invoke-kind s)
                              children (filterv #(and (map? %) (keyword? (:id %))) (:states s))
                              gen      (generated-transitions s ik at)
                              retry-t  (filter #(#{:retry :retry-exhausted} (:generated %)) gen)
                              own      (map-indexed (fn [j t] (norm-transition s ik j t at))
                                         (as-vec (:on s)))
                              fam      (get invoke-error-families ik)
                              ;; Document order decides between transitions on the same
                              ;; event. The shape's exits on SPECIFIC errors of this
                              ;; step (e.g. :error.call.unknown) come first and win; then
                              ;; the retry; then everything else, so a generic error exit
                              ;; (:error.llm, :error) only fires once retries run out.
                              specific? (fn [t] (and fam (seq (:events t))
                                                  (every? #(and (keyword? %)
                                                             (str/starts-with? (name %) (str fam ".")))
                                                    (:events t))))
                              trans    (vec (concat
                                              (when (seq retry-t) (filter specific? own))
                                              retry-t
                                              (if (seq retry-t) (remove specific? own) own)
                                              (remove #(#{:retry :retry-exhausted} (:generated %)) gen)))]
                          (when (and type (not (#{:state :final :parallel} type)))
                            (add-problem! (problem :error :bad-type id at
                                            (str "State " (kw-str id) " has :type " (pr-str type)
                                              "; use :state, :parallel or :final."))))
                          (when (< 1 (count (filter #(contains? s %) [:ai :call :invoke])))
                            (add-problem! (problem :error :many-invokes id at
                                            (str "State " (kw-str id) " has more than one of :ai, :call, :invoke;"
                                              " give each its own state."))))
                          (vswap! acc update :states assoc id
                            {:id          id
                             :kind        kind
                             :parent      parent
                             :children    (mapv :id children)
                             :initial     (when (seq children)
                                            (if (= kind :parallel) nil (or (:initial s) (:id (first children)))))
                             :invoke      ik
                             :src         s
                             :at          at
                             :transitions trans})
                          (vswap! acc update :order conj id)
                          (when (:retry s)
                            (let [rid (retry-state-id id)]
                              (vswap! acc update :states assoc rid
                                {:id rid :kind :state :parent parent :children [] :generated :retry-wait
                                 :src {:id rid :label "Wait before retrying" :detail :full}
                                 :at (conj at :retry)
                                 :transitions [{:source rid :events [] :targets [id] :label "try again"
                                                :at (conj at :retry) :generated :retry-again
                                                :automatic? true :after :backoff}]})
                              (vswap! acc update :order conj rid)))
                          (walk (:states s) id at)))))
                  (as-vec states))))]
      (cond
        (not (map? shape))
        {:states {} :order [] :problems [(problem :error :bad-shape nil []
                                           "A workflow shape must be a map with :initial and :states.")]}

        :else
        (do
          (when-not (sequential? (:states shape))
            (add-problem! (problem :error :no-states nil [:states]
                            "The shape has no :states vector.")))
          (walk (:states shape) nil [])
          (let [{:keys [states order]} @acc
                initial (or (:initial shape) (first (filter #(nil? (:parent (states %))) order)))]
            (assoc @acc :initial initial)))))))

;; ---------------------------------------------------------------------------
;; Graph helpers
;; ---------------------------------------------------------------------------

(defn ancestors-of
  "Proper ancestors of `id`, nearest first."
  [{:keys [states]} id]
  (loop [p (:parent (states id)) out []]
    (if p (recur (:parent (states p)) (conj out p)) out)))

(defn- entry-closure
  "States that become active when `target` is entered: it, its ancestors, and its
   default descendants (initial child chains; every region of a parallel)."
  [{:keys [states] :as idx} target]
  (letfn [(down [id]
            (when-let [s (states id)]
              (cons id
                (case (:kind s)
                  :parallel (mapcat down (:children s))
                  (when-let [i (:initial s)] (down i))))))]
    (into (set (ancestors-of idx target)) (down target))))

(defn reachable
  "Every state id that can become active, starting from the initial state."
  [{:keys [states initial] :as idx}]
  (if-not (states initial)
    #{}
    (loop [seen (entry-closure idx initial) frontier seen]
      (if (empty? frontier)
        seen
        (let [new (->> frontier
                    (mapcat (fn [id] (mapcat :targets (:transitions (states id)))))
                    (filter states)
                    (mapcat #(entry-closure idx %))
                    (remove seen)
                    set)]
          (recur (into seen new) new))))))

(defn- transitions-in-scope
  "Transitions available while `id` is active: its own plus every ancestor's."
  [{:keys [states] :as idx} id]
  (mapcat #(:transitions (states %)) (cons id (ancestors-of idx id))))

;; ---------------------------------------------------------------------------
;; Checks
;; ---------------------------------------------------------------------------

(defn- check-targets [{:keys [states order initial] :as idx}]
  (concat
    (when-not (states initial)
      [(problem :error :unknown-initial nil [:initial]
         (if initial
           (str "The initial state " (kw-str initial) " does not exist.")
           "The shape has no :initial state."))])
    (for [id order
          :let [s (states id)]
          :when (and (:initial (:src s)) (not (some #{(:initial (:src s))} (:children s))))]
      (problem :error :unknown-initial id (conj (:at s) :initial)
        (str "State " (kw-str id) " starts in " (kw-str (:initial (:src s)))
          ", which is not one of its child states.")))
    (for [id order
          t  (:transitions (states id))
          tgt (:targets t)
          :when (not (states tgt))]
      (problem :error :unknown-target id (:at t)
        (str "A transition from " (kw-str id)
          (when (seq (:events t)) (str " on " (str/join ", " (map kw-str (:events t)))))
          " goes to " (kw-str tgt) ", which does not exist.")))))

(defn- check-reachable [{:keys [states order] :as idx}]
  (when (states (:initial idx))
    (let [r (reachable idx)]
      (for [id order
            :let [s (states id)]
            :when (and (not (r id)) (not (:generated s)))]
        (problem :error :unreachable id (:at s)
          (str "State " (kw-str id) (when-let [l (:label (:src s))] (str " (\"" l "\")"))
            " can never be reached from the start (" (kw-str (:initial idx)) ")."))))))

(defn- check-stuck [{:keys [states order] :as idx}]
  (for [id order
        :let [s (states id)]
        :when (and (= :state (:kind s)) (empty? (:children s))
                (empty? (transitions-in-scope idx id))
                ;; A state inside a parallel region may legitimately rest while
                ;; a sibling region finishes; flag it only outside parallels.
                (not-any? #(= :parallel (:kind (states %))) (ancestors-of idx id)))]
    (problem :error :stuck id (:at s)
      (str "State " (kw-str id) (when-let [l (:label (:src s))] (str " (\"" l "\")"))
        " has no way out: it is not final and has no transitions."
        " Add a transition, or make it :type :final."))))

(defn- check-error-exits [{:keys [states order] :as idx}]
  (for [id order
        :let [s (states id) kind (:invoke s)]
        :when kind
        :let [fam      (get invoke-error-families kind)
              covered? (some (fn [t]
                               (some (fn [e]
                                       (if fam
                                         (event-covers? e fam)
                                         (error-event? e)))
                                 (:events t)))
                         (transitions-in-scope idx id))]
        :when (not covered?)]
    (problem :error :no-error-exit id (:at s)
      (str (case kind :ai "AI step " :call "Outside call " "Invoking state ")
        (kw-str id) " has no error exit. Add a transition on "
        (case kind :ai ":error.llm" :call ":call.error" ":error")
        " (or a :retry with :on-exhausted) so a failure has somewhere to go."))))

(defn- check-human-input [{:keys [states order]}]
  (for [id order
        :let [s (states id)]
        :when (= :human-input (get-in s [:src :invoke :type]))]
    (problem :error :human-input id (conj (:at s) :invoke)
      (str "State " (kw-str id) " uses a :human-input invoke. Workflows ask people through"
        " wait states (:wait) so a restart never loses the question."))))

(defn- check-parallels [{:keys [states order]}]
  (for [id order
        :let [s (states id)]
        :when (and (= :parallel (:kind s)) (< (count (:children s)) 2))]
    (problem :warn :thin-parallel id (:at s)
      (str "Parallel state " (kw-str id) " has fewer than two regions."))))

(defn- check-retry-config [{:keys [states order]}]
  (for [id order
        :let [s (states id) retry (:retry (:src s))]
        :when retry
        :let [msg (cond
                    (not (:invoke s)) "has a :retry but invokes nothing (:ai or :call)."
                    (= :invoke (:invoke s)) "has a :retry; only :ai and :call states can retry."
                    (not (pos-int? (:max retry))) "has a :retry without a positive :max."
                    (not (:on-exhausted retry)) "has a :retry without :on-exhausted.")]
        :when msg]
    (problem :error :bad-retry id (conj (:at s) :retry) (str "State " (kw-str id) " " msg))))

(defn- automatic-cycles
  "Cycles made only of AUTOMATIC transitions (timers, errors, eventless), none
   of them guarded, that include at least one error transition — i.e. a retry
   loop that never stops. Re-entering an ancestor counts as reaching every
   state below it."
  [{:keys [states order] :as idx}]
  (let [edges (for [id order
                    t (:transitions (states id))
                    :when (and (:automatic? t) (nil? (:guard t)))
                    tgt (:targets t)
                    :when (states tgt)
                    to (entry-closure idx tgt)
                    ;; Only atomic/landing states matter for the loop.
                    :when (empty? (:children (states to)))]
                ;; An ancestor's transition applies from every descendant.
                {:from id :to to :error? (:error? t) :t t})
        ;; expand ancestor-sourced edges to their atomic descendants
        atomic (set (filter #(empty? (:children (states %))) order))
        from-atomic (fn [id] (filter #(or (= % id) (some #{id} (ancestors-of idx %))) atomic))
        adj  (reduce (fn [m {:keys [from to error?]}]
                       (reduce (fn [m a] (update m a (fnil conj #{}) [to error?])) m (from-atomic from)))
               {} edges)]
    ;; DFS for a cycle through an error edge.
    (distinct
      (for [start atomic
            :let [found (loop [stack [[start #{} false]] seen #{}]
                          (when-let [[n path err?] (peek stack)]
                            (let [stack (pop stack)
                                  nexts (get adj n)]
                              (if-let [hit (some (fn [[to e?]] (when (and (= to start) (or err? e?)) start)) nexts)]
                                hit
                                (recur (into stack
                                         (for [[to e?] nexts
                                               :when (not (seen [to (or err? e?)]))]
                                           [to (conj path n) (or err? e?)]))
                                  (into seen (map (fn [[to e?]] [to (or err? e?)]) nexts)))))))]
            :when found]
        start))))

(defn- check-bounded-retries [{:keys [states] :as idx}]
  (for [id (automatic-cycles idx)
        :let [s (states id)]
        :when (not (:generated s))]
    (problem :error :unbounded-retry id (:at s)
      (str "State " (kw-str id) " can retry forever: an error leads back to it through"
        " automatic steps with no condition. Use :retry {:max n …} or guard the loop."))))

(defn structural-problems
  "All structural problems of an `index` (see `index`), shape problems first."
  [idx]
  (vec (concat (:problems idx)
         (check-targets idx)
         (check-reachable idx)
         (check-stuck idx)
         (check-error-exits idx)
         (check-retry-config idx)
         (check-bounded-retries idx)
         (check-human-input idx)
         (check-parallels idx))))

;; ---------------------------------------------------------------------------
;; References
;; ---------------------------------------------------------------------------

(defn- levenshtein [a b]
  (let [a (vec a) b (vec b)]
    (peek
      (reduce (fn [prev i]
                (reduce (fn [row j]
                          (conj row (min (inc (peek row))
                                      (inc (nth prev (inc j)))
                                      (+ (nth prev j) (if (= (nth a i) (nth b j)) 0 1)))))
                  [(inc i)] (range (count b))))
        (vec (range (inc (count b)))) (range (count a))))))

(defn did-you-mean
  "The closest of `candidates` (strings) to `s`, when close enough."
  [s candidates]
  (let [scored (sort-by first (map (fn [c] [(levenshtein s c) c]) candidates))
        [d c]  (first scored)]
    (when (and c (<= d (max 2 (quot (count s) 3)))) c)))

(defn ref-sites
  "Every step reference in the indexed shape:
   `[{:ref kw :role :action|:guard|:value :state id :at path} …]`."
  [{:keys [states order]}]
  (let [value-refs (fn [id at m]
                     (for [[k v] m
                           :when (and (map? v) (= [:step] (keys v)))]
                       {:ref (:step v) :role :value :state id :at (conj at k)}))]
    (vec
      (concat
        (for [id order
              :let [{:keys [src at]} (states id)]
              [k role] [[:entry :action] [:exit :action]]
              [i r] (map-indexed vector (as-vec (get src k)))]
          {:ref r :role role :state id :at (conj at k i)})
        (for [id order
              t (:transitions (states id))
              :when (not (:generated t))
              :let [g (:guard t)]
              site (concat
                     (when g [{:ref g :role :guard :state id :at (conj (:at t) :guard)}])
                     (when (and (map? (:after t)) (= [:step] (keys (:after t))))
                       [{:ref (:step (:after t)) :role :value :state id :at (conj (:at t) :after)}])
                     (map-indexed (fn [i a] {:ref a :role :action :state id :at (conj (:at t) :actions i)})
                       (:actions t)))]
          site)
        (for [id order
              :let [{:keys [src at]} (states id)]
              k [:ai :call]
              :when (map? (get src k))
              site (concat (value-refs id (conj at k) (get src k))
                     (when (map? (get-in src [k :input]))
                       (when (= [:step] (keys (get-in src [k :input])))
                         [{:ref (get-in src [k :input :step]) :role :value :state id :at (conj at k :input)}])))]
          site)
        (for [id order
              :let [{:keys [src at]} (states id)]
              :when (map? (get-in src [:invoke :params]))
              site (value-refs id (conj at :invoke :params) (get-in src [:invoke :params]))]
          site)
        (for [id order
              :let [{:keys [src at]} (states id)
                    tm (get-in src [:wait :timeout-ms])]
              :when (and (map? tm) (= [:step] (keys tm)))]
          {:ref (:step tm) :role :value :state id :at (conj at :wait :timeout-ms)})
        (for [id order
              :let [{:keys [src at]} (states id)]
              :when (and (map? (:output src)) (= [:step] (keys (:output src))))]
          {:ref (get-in src [:output :step]) :role :value :state id :at (conj at :output)})))))

(defn reference-problems
  "Problems for references that `resolve-ref` cannot resolve. `available` is the
   collection of names (strings) offered for \"did you mean\"."
  [idx resolve-ref available]
  (vec
    (distinct
      (for [{:keys [ref role state at]} (ref-sites idx)
            :when (or (not (keyword? ref)) (nil? (resolve-ref ref)))]
        (if-not (keyword? ref)
          (problem :error :bad-ref state at
            (str "In state " (kw-str state) ", a " (name role) " reference must be a keyword, got "
              (pr-str ref) "."))
          (let [hint (when-not (namespace ref) (did-you-mean (name ref) available))]
            (problem :error :unknown-ref state at
              (str "In state " (kw-str state) ", the " (name role) " " (kw-str ref)
                " names no function"
                (if (namespace ref) " in the built-in vocabulary" " in the step namespace")
                "." (when hint (str " Did you mean :" hint "?"))))))))))
