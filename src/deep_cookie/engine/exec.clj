(ns deep-cookie.engine.exec
  "Lambda-style `ExecutionModel`. Expressions are Clojure functions of `(fn [env data])`.
  If the expression returns a vector and `::sc/raw-result?` is not set on `env`, the vector
  is interpreted as a data-model update op list.

  Crib of `com.fulcrologic.statecharts.execution-model.lambda` without the timbre dependency
  so it can be loaded cleanly under bb without pulling a logger chain."
  (:require
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.protocols :as sp]))

(deftype LambdaExecutionModel [data-model event-queue]
  sp/ExecutionModel
  (run-expression! [_this env expr]
    (if (fn? expr)
      (let [data    (sp/current-data data-model env)
            result  (expr env data)
            update? (and (not (::sc/raw-result? env))
                         (vector? result))]
        (when update?
          (sp/update! data-model env {:ops result}))
        result)
      (let [update? (and (not (::sc/raw-result? env))
                         (vector? expr))]
        (when update?
          (sp/update! data-model env {:ops expr}))
        expr))))

(defn new-execution-model
  "Create a lambda execution model. Functions in the chart will be called as `(f env data)` and
  may return a vector of data-model ops to apply."
  [data-model event-queue]
  (->LambdaExecutionModel data-model event-queue))
