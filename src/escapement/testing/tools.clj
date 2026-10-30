(ns escapement.testing.tools
  "Pretend tools for tests, dry runs and replays: a tool registry whose tools answer
   from fixed replies or a fn, and which records every call.

       (def reg (pretend-registry {:email/send {:reply {:result \"sent\"}}
                                   :crm/lookup {:replies [{:result \"{...}\"}
                                                          {:result \"down\" :is-error true}]}
                                   :kb/search  (fn [input] (str \"hits for \" (:q input)))}))
       (calls reg) ;; => [{:tool :email/send :input {...} :result {...}} …]

   A spec is a fn `(fn [input] reply)` or a map:
     * `:reply`       — the same reply for every call;
     * `:replies`     — one reply per call, in order; the last repeats once used up;
     * `:fn`          — `(fn [input] reply)`;
     * `:schema`      — Malli input schema (default `[:map]`);
     * `:description` — what the model is told (default \"Pretend <tool>\").
   A reply is a string (a successful result) or `{:result s :is-error bool}`; a
   non-string `:result` is `pr-str`ed. A spec with no reply answers \"ok\"."
  (:require
    [escapement.tools.protocol :as tp]))

(defn- normalize [reply]
  (cond
    (nil? reply)    {:result "ok" :is-error false}
    (string? reply) {:result reply :is-error false}
    (map? reply)    {:result   (let [r (:result reply)] (if (string? r) r (pr-str r)))
                     :is-error (boolean (:is-error reply))}
    :else           {:result (pr-str reply) :is-error false}))

(defrecord PretendTool [kw desc schema answer calls]
  tp/Tool
  (tool-name [_] kw)
  (description [_] desc)
  (input-schema [_] schema)
  (invoke [_ input]
    (let [n     (count (filter #(= kw (:tool %)) @calls))
          reply (normalize (answer input n))]
      (swap! calls conj {:tool kw :input input :result reply})
      reply)))

(defn- answer-fn [spec]
  (cond
    (fn? spec)            (fn [input _] (spec input))
    (:fn spec)            (fn [input _] ((:fn spec) input))
    (contains? spec :replies)
    (let [rs (vec (:replies spec))]
      (fn [_ n] (when (seq rs) (nth rs (min n (dec (count rs)))))))
    :else                 (fn [_ _] (:reply spec))))

(defrecord RecordingTool [inner calls]
  tp/Tool
  (tool-name [_] (tp/tool-name inner))
  (description [_] (tp/description inner))
  (input-schema [_] (tp/input-schema inner))
  (invoke [_ input]
    (let [reply (tp/invoke inner input)]
      (swap! calls conj {:tool (tp/tool-name inner) :input input :result reply})
      reply)))

(defn pretend-tool
  "One pretend `Tool` named `kw` from `spec` (see the ns doc), recording into `calls`."
  [kw spec calls]
  (->PretendTool kw
    (or (when (map? spec) (:description spec)) (str "Pretend " kw))
    (or (when (map? spec) (:schema spec)) [:map])
    (answer-fn spec)
    calls))

(defn pretend-registry
  "A fresh tool registry (atom) of pretend tools from `{tool-kw spec}`. With a `base`
   registry, its tools are included too (wrapped so their calls are recorded), and
   `specs` override base tools of the same name."
  ([specs] (pretend-registry nil specs))
  ([base specs]
   (let [calls (atom [])
         wrapped (map #(->RecordingTool % calls) (vals (some-> base deref)))
         pretend (map (fn [[kw spec]] (pretend-tool kw spec calls)) specs)
         reg (tp/new-registry (vec (concat wrapped pretend)))]
     (alter-meta! reg assoc ::calls calls)
     reg)))

(defn calls
  "Every call made through a `pretend-registry`, in order:
   `[{:tool kw :input m :result {:result s :is-error b}} …]`."
  [reg]
  (some-> (::calls (meta reg)) deref))

(defn call-counts
  "`{tool-kw n}` for a `pretend-registry`."
  [reg]
  (frequencies (map :tool (calls reg))))
