(ns hooks.fulcro-spec
  "clj-kondo hooks for fulcrologic/fulcro-spec (no clj-kondo export in the
   jar). Rewrites the spec macros into ordinary forms so the bodies still
   get linted (unused bindings, typos in lhs/rhs forms) without false
   `unresolved-symbol` errors on the `=>`-family arrows."
  (:require [clj-kondo.hooks-api :as api]))

(defn- arrow?
  "Truthy for the fulcro-spec arrow tokens: => =fn=> =throws=> =check=> …"
  [n]
  (when (api/token-node? n)
    (let [s (api/sexpr n)]
      (and (symbol? s) (re-find #"=>" (name s))))))

(defn- block
  "Drop all arrow tokens and collect the rest of the body into a vector
   node. A vector keeps every form analyzed (unresolved symbols, unused
   bindings, arity) while treating each as a *used* value, so we don't
   trade arrow false-positives for `unused-value` ones on assertion lhs's
   and docstrings."
  [{:keys [node]}]
  (let [forms (->> (rest (:children node))
                   (remove arrow?))]
    {:node (api/vector-node forms)}))

;; (specification "desc" body…) / behavior / component  -> (do body…)
(def specification block)
(def behavior block)
(def component block)

;; (assertions "doc"? lhs => rhs …) -> (do lhs rhs …)
(def assertions block)

;; (provided! "doc"? (mock args) =Nx=> ret … (assertions …)) -> (do …)
;; Keeps the mocked call + result forms (catches typos) minus the arrows.
(def provided! block)
(def when-mocking! block)
