(ns escapement.llm.models
  "Backward-compat shim. The catalog was split into normalized model facts
   and a separate provider/pricing layer — see `escapement.llm.catalog`.

   This namespace re-exports the id-keyed helpers so existing callers keep
   working unchanged. New code should require `escapement.llm.catalog`
   directly and pass a provider where pricing matters (`pricing` here is
   the old id-only \"cheapest metered list price\" arity)."
  (:require [escapement.llm.catalog :as catalog]))

(def info             catalog/info)
(def context-window   catalog/context-window)
(def intelligence     catalog/intelligence)
(def max-output-tokens catalog/max-output-tokens)
(def vision?          catalog/vision?)
(def company          catalog/company)

(defn pricing
  "Approximate cheapest metered list price `{:input N :output M}` (USD per
   1M tokens) for `model`, or nil. Pricing is now provider-specific — see
   `escapement.llm.catalog/pricing` for the precise per-provider answer."
  [model]
  (catalog/pricing model))

(defn approaching-limit?
  "True when `input-tokens` is at or past `threshold` (default 0.8) of
   `model`'s context window. False when the model is unknown."
  ([model input-tokens]
   (approaching-limit? model input-tokens 0.8))
  ([model input-tokens threshold]
   (when-let [cap (catalog/context-window model)]
     (>= (long input-tokens) (long (* cap threshold))))))
