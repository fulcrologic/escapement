(ns escapement.llm.needs
  "Translate the ergonomic chart-node `:needs` map into the canonical
   low-level policy `escapement.llm.catalog/satisfies-policy?` consumes.

   `:needs` is the surface a chart author types at an `llm-conversation`
   node — a **flat** `fact → constraint` map (one nesting level). It is
   pure ergonomics layered over the stable, canonical
   `{:require {…} :min {…} :max {…}}` policy shape; the catalog API never
   sees `:needs`.

   The keyspace `k` ranges over one uniform namespace mixing two sources
   (the translation never special-cases a key):

   * **Documented objective facts** — the published vocabulary in
     `escapement.llm.catalog/eligibility-facts` (`:vision?`,
     `:tool-call?`, `:context-tokens`, …), resolved from the
     models.dev-backed catalog.
   * **Subjective ratings** — any host-defined key from `:llm/ratings`
     (`:clojure`, `:ux`, `:tier`, …).

   Each entry states its own rule by shape:

   * bare value `v`        ⇒ exact equality   → `:require {k v}`
   * `[:>= n]`             ⇒ inclusive floor  → `:min {k n}`
   * `[:<= n]`             ⇒ inclusive ceiling → `:max {k n}`

   ONLY the two comparators `:>=` and `:<=` are accepted — they map 1:1
   onto the canonical predicate. There is deliberately no `:>` / `:<` /
   `:=`. No model fact is ever itself a literal vector, so `[op n]` vs a
   bare value is unambiguous.

   All clauses must hold (logical AND — exactly the canonical semantics).
   An empty / nil `:needs` yields the canonical empty policy
   `{:require {} :min {} :max {}}`, which `satisfies-policy?` treats as
   \"admits everything\".

   Malformed entries fail loudly rather than silently mis-gating model
   selection:

   * a vector value whose operator is not `:>=` / `:<=`,
   * a vector value that is not exactly a 2-element `[op n]` pair,
   * a vector value whose bound is not a number,

   each throw an `ex-info` whose message names the offending key.

   Pure: no I/O, no config, no disk, no globals."
  (:require
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]))

(def ^:private comparator->clause
  "The only two accepted `:needs` operators and the canonical policy
   clause each maps onto."
  {:>= :min
   :<= :max})

(defn- bad-entry!
  [k v reason]
  (throw (ex-info (str "Invalid :needs entry for " (pr-str k) ": " reason
                    " (got " (pr-str v) ")")
           {:key k :value v :reason reason})))

(>defn needs->policy
  "Translate a flat `:needs` map into the canonical
   `{:require {…} :min {…} :max {…}}` policy.

   See the namespace docstring for the full contract. `needs` may be nil
   or empty (⇒ canonical empty policy, admitting everything). Throws an
   `ex-info` naming the offending key on any malformed / unknown-operator
   entry."
  [needs]
  [[:maybe map?] => [:map [:require map?] [:min map?] [:max map?]]]
  (reduce-kv
    (fn [acc k v]
      (cond
        ;; [op n] form — numeric floor / ceiling.
        (vector? v)
        (let [[op n & extra] v]
          (cond
            (or (seq extra) (not= 2 (count v)))
            (bad-entry! k v "expected a 2-element [:>= n] or [:<= n] vector")

            (not (contains? comparator->clause op))
            (bad-entry! k v (str "unknown operator " (pr-str op)
                              " — only :>= and :<= are supported"))

            (not (number? n))
            (bad-entry! k v "the bound after the operator must be a number")

            :else
            (assoc-in acc [(comparator->clause op) k] n)))

        ;; bare value — exact equality.
        :else
        (assoc-in acc [:require k] v)))
    {:require {} :min {} :max {}}
    (or needs {})))
