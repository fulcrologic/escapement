(ns escapement.metrics
  "What a run used: tokens (incl. cache read/write), cost in US dollars, AI calls and
   time.

   * `call-cost` — the cost of one AI call from the model catalog's pricing (or a
     host price table). Unknown pricing gives `nil` — never zero.
   * `summarize` — fold a run's transcript rows into totals.
   * `collector` — a live tap that keeps the same totals as rows arrive.

   Prices are USD per 1M tokens: `{:input 3.0 :output 15.0 :cache-read 0.3
   :cache-write 3.75}`. The catalog gives `:input`/`:output` only; cache tokens
   are then priced at 0.1× (read) and 1.25× (write) the input price, and the result
   says so (`:cost-basis :catalog+cache-estimate`)."
  (:require
    [escapement.llm.catalog :as catalog]))

(def cache-read-multiplier 0.1)
(def cache-write-multiplier 1.25)

(defn- per-token [usd-per-mtok] (/ (double usd-per-mtok) 1000000.0))

(defn- lookup-price
  "Price for `model` from `prices` (`{model-id price}`, also keyed by
   `[provider model-id]`), else the catalog."
  [prices provider model]
  (let [m (some-> model str)]
    (or (when prices (or (get prices [provider m]) (get prices m)))
      (when m
        (try (if provider
               (or (catalog/pricing provider m) (catalog/pricing m))
               (catalog/pricing m))
             (catch #?(:clj Throwable :cljs :default) _ nil))))))

(defn call-cost
  "Cost of one AI call. `{:provider :model :usage}`; `opts` `{:prices table}`.
   Returns `{:cost-usd n-or-nil :cost-basis kw}`: `:cost-basis` is `:prices`,
   `:catalog`, `:catalog+cache-estimate` or `:unknown` (then `:cost-usd` is nil)."
  ([call] (call-cost call nil))
  ([{:keys [provider model usage]} {:keys [prices]}]
   (let [p (lookup-price prices provider model)]
     (if-not (and p (number? (:input p)) (number? (:output p)))
       {:cost-usd nil :cost-basis :unknown}
       (let [in   (or (:input-tokens usage) 0)
             out  (or (:output-tokens usage) 0)
             cr   (or (:cache-read-input-tokens usage) 0)
             cw   (or (:cache-creation-input-tokens usage) 0)
             cr-p (or (:cache-read p) (* cache-read-multiplier (:input p)))
             cw-p (or (:cache-write p) (* cache-write-multiplier (:input p)))
             estimated? (and (or (pos? cr) (pos? cw))
                          (not (and (:cache-read p) (:cache-write p))))
             cost (+ (* in (per-token (:input p)))
                    (* out (per-token (:output p)))
                    (* cr (per-token cr-p))
                    (* cw (per-token cw-p)))]
         {:cost-usd   cost
          :cost-basis (cond
                        (and prices (or (get prices [provider (str model)]) (get prices (str model)))) :prices
                        estimated? :catalog+cache-estimate
                        :else :catalog)})))))

(defn- priced-call
  "Price a call by the model the provider REPORTED, falling back to the one
   requested when the reported id has no price."
  [provider model model-requested usage opts]
  (let [a (call-cost {:provider provider :model model :usage usage} opts)]
    (if (and (nil? (:cost-usd a)) model-requested (not= (str model) (str model-requested)))
      (call-cost {:provider provider :model model-requested :usage usage} opts)
      a)))

(defn response-row-cost
  "Fields to add to an `:llm/response` transcript row's data for `response`
   (with `:provider`/`:model-requested` when known): `:cost-usd` (nil when
   unknown), `:cost-basis`, and `:cost-unknown? true` when unknown."
  [{:keys [provider model model-requested usage]}]
  (let [{:keys [cost-usd cost-basis]} (priced-call provider model model-requested usage nil)]
    (cond-> {:cost-usd cost-usd :cost-basis cost-basis}
      (nil? cost-usd) (assoc :cost-unknown? true))))

;; ---------------------------------------------------------------------------
;; Folding rows
;; ---------------------------------------------------------------------------

(def empty-totals
  {:ai-calls        0
   :tokens          {:input 0 :output 0 :cache-read 0 :cache-write 0 :total 0}
   :cost-usd        0.0
   :cost-complete?  true
   :unpriced-models #{}
   :replay-cache-hits 0
   :llm-ms          0
   :by-model        {}
   :by-step         {}
   :started-ts      nil
   :ended-ts        nil
   :wall-ms         nil
   :events          0})

(defn- add-tokens [t usage]
  (let [in (or (:input-tokens usage) 0) out (or (:output-tokens usage) 0)
        cr (or (:cache-read-input-tokens usage) 0) cw (or (:cache-creation-input-tokens usage) 0)]
    (-> t
      (update :input + in) (update :output + out)
      (update :cache-read + cr) (update :cache-write + cw)
      (update :total + in out cr cw))))

(def ^:private zero-tokens (:tokens empty-totals))

(defn add-row
  "Fold one transcript `row` into `totals`. `opts` `{:prices table}` recomputes
   each call's cost from that table instead of the row's own `:cost-usd`."
  ([totals row] (add-row totals row nil))
  ([totals {:keys [event ts data]} opts]
   (let [totals (cond-> totals
                  (and ts (nil? (:started-ts totals))) (assoc :started-ts ts)
                  ts (assoc :ended-ts ts))
         totals (if (and (:started-ts totals) (:ended-ts totals))
                  (assoc totals :wall-ms (- (:ended-ts totals) (:started-ts totals)))
                  totals)]
     (case event
       :llm/response
       (let [{:keys [usage model model-requested provider elapsed-ms invokeid]} data
             priced (if (or (:prices opts) (not (contains? data :cost-usd)))
                      (priced-call provider model model-requested usage opts)
                      {:cost-usd (:cost-usd data)})
             cost   (:cost-usd priced)
             mkey   (str (or model model-requested "unknown"))
             step   (some-> invokeid str)]
         (-> totals
           (update :ai-calls inc)
           (update :tokens add-tokens usage)
           (update :llm-ms + (or elapsed-ms 0))
           (cond-> (:replay-cache/hit? data) (update :replay-cache-hits inc))
           (cond-> cost (update :cost-usd + cost))
           (cond-> (nil? cost) (-> (assoc :cost-complete? false)
                                 (update :unpriced-models conj mkey)))
           (update-in [:by-model mkey]
             (fn [m] (-> (or m {:calls 0 :tokens zero-tokens :cost-usd 0.0 :provider provider})
                       (update :calls inc)
                       (update :tokens add-tokens usage)
                       (cond-> cost (update :cost-usd + cost))
                       (cond-> (nil? cost) (assoc :cost-usd nil)))))
           (cond-> step
             (update-in [:by-step step]
               (fn [m] (-> (or m {:calls 0 :tokens zero-tokens :cost-usd 0.0 :llm-ms 0})
                         (update :calls inc)
                         (update :tokens add-tokens usage)
                         (update :llm-ms + (or elapsed-ms 0))
                         (cond-> cost (update :cost-usd + (or cost 0)))))))))

       :runner/event-processed (update totals :events inc)
       totals))))

(defn- finish [totals]
  (cond-> totals
    (not (:cost-complete? totals)) (assoc :cost-usd nil :cost-known-usd (:cost-usd totals))))

(defn summarize
  "Totals for a run from its transcript `rows`:

       {:ai-calls n
        :tokens {:input :output :cache-read :cache-write :total}
        :cost-usd x          ; nil when any call's price is unknown (then :cost-known-usd
        :cost-complete? b    ;  holds the priced part and :unpriced-models names the rest)
        :unpriced-models #{model}
        :replay-cache-hits n
        :llm-ms n            ; time spent waiting on AI calls
        :wall-ms n           ; first row to last row
        :events n            ; chart events processed
        :by-model {model {:calls :tokens :cost-usd :provider}}
        :by-step  {invoke-id {:calls :tokens :cost-usd :llm-ms}}}

   `opts` `{:prices table}` re-prices every call from the given table."
  ([rows] (summarize rows nil))
  ([rows opts]
   (finish (reduce #(add-row %1 %2 opts) empty-totals rows))))

(defn collector
  "A live collector: `{:tap (fn [row]) :snapshot (fn [] totals)}`. Compose `:tap`
   with a run's transcript tap; `snapshot` returns `summarize`-shaped totals."
  ([] (collector nil))
  ([opts]
   (let [acc (atom empty-totals)]
     {:tap      (fn [row] (swap! acc add-row row opts) nil)
      :snapshot (fn [] (finish @acc))})))
