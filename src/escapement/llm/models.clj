(ns escapement.llm.models
  "Static facts about LLM models that the API doesn't expose at runtime.

   Anthropic and z.ai's Anthropic-compat endpoint do not return the model's
   context-window size on responses, so we keep a hand-maintained lookup
   here. Charts that want to soft-cap their accumulated input can call
   `context-window` to know the ceiling for their currently-chosen model.

   Keep this table current as Anthropic ships new model ids. Unknown models
   return `nil` rather than throwing — callers decide whether to fall back
   to a conservative default or raise."
  (:require [clojure.string :as str]))

(def ^:private known-models
  "Map of model id string → `{:context-tokens N :max-output-tokens M
                              :provider :anthropic | :z-ai}`. Only the keys
   we track are listed — the table is intentionally small and explicit
   rather than a wildcard match on the family."
  {;; Claude 4.x — current generation, all 200K context.
   "claude-opus-4-7"     {:context-tokens 200000 :max-output-tokens 64000 :provider :anthropic}
   "claude-opus-4-6"     {:context-tokens 200000 :max-output-tokens 64000 :provider :anthropic}
   "claude-opus-4"       {:context-tokens 200000 :max-output-tokens 64000 :provider :anthropic}
   "claude-sonnet-4-7"   {:context-tokens 200000 :max-output-tokens 64000 :provider :anthropic}
   "claude-sonnet-4-6"   {:context-tokens 200000 :max-output-tokens 64000 :provider :anthropic}
   "claude-sonnet-4-5"   {:context-tokens 200000 :max-output-tokens 64000 :provider :anthropic}
   "claude-haiku-4-5"    {:context-tokens 200000 :max-output-tokens 32000 :provider :anthropic}

   ;; Claude 3.5 family — still in service for some users.
   "claude-3-5-sonnet"   {:context-tokens 200000 :max-output-tokens  8192 :provider :anthropic}
   "claude-3-5-haiku"    {:context-tokens 200000 :max-output-tokens  8192 :provider :anthropic}
   "claude-3-opus"       {:context-tokens 200000 :max-output-tokens  4096 :provider :anthropic}

   ;; z.ai's GLM family. Served over z.ai's Anthropic-shaped
   ;; /api/anthropic endpoint AND over its OpenAI-shaped /api/paas/v4 endpoint.
   ;; Context windows from https://docs.z.ai/guides/overview/overview
   ;; GLM-5 series (current flagship)
   "glm-5.1"             {:context-tokens 200000 :max-output-tokens 32000 :provider :z-ai}
   "glm-5"               {:context-tokens 200000 :max-output-tokens 32000 :provider :z-ai}
   "glm-5-turbo"         {:context-tokens 200000 :max-output-tokens 32000 :provider :z-ai}
   ;; GLM-4.7 series
   "glm-4.7"             {:context-tokens 200000 :max-output-tokens 32000 :provider :z-ai}
   "glm-4.7-flashx"      {:context-tokens 200000 :max-output-tokens 32000 :provider :z-ai}
   "glm-4.7-flash"       {:context-tokens 200000 :max-output-tokens 32000 :provider :z-ai}
   ;; GLM-4.6 / 4.5 series
   "glm-4.6"             {:context-tokens 200000 :max-output-tokens 32000 :provider :z-ai}
   "glm-4.5"             {:context-tokens 128000 :max-output-tokens 32000 :provider :z-ai}
   "glm-4.5-air"         {:context-tokens 128000 :max-output-tokens 32000 :provider :z-ai}
   "glm-4.5-x"           {:context-tokens 128000 :max-output-tokens 32000 :provider :z-ai}
   "glm-4.5-airx"        {:context-tokens 128000 :max-output-tokens 32000 :provider :z-ai}
   "glm-4.5-flash"       {:context-tokens 128000 :max-output-tokens 32000 :provider :z-ai}
   "glm-4-32b-0414-128k" {:context-tokens 128000 :max-output-tokens 32000 :provider :z-ai}

   ;; OpenAI — current generation. Values are the documented input context
   ;; window and the per-response output cap. Used by the OpenAI backend (and
   ;; any OpenAI-compatible Codex-style endpoint that serves the same ids).
   "gpt-5"               {:context-tokens 400000 :max-output-tokens 128000 :provider :openai}
   "gpt-5-mini"          {:context-tokens 400000 :max-output-tokens 128000 :provider :openai}
   "gpt-5-nano"          {:context-tokens 400000 :max-output-tokens 128000 :provider :openai}
   "gpt-4.1"             {:context-tokens 1000000 :max-output-tokens 32768 :provider :openai}
   "gpt-4.1-mini"        {:context-tokens 1000000 :max-output-tokens 32768 :provider :openai}
   "gpt-4o"              {:context-tokens 128000 :max-output-tokens 16384 :provider :openai}
   "gpt-4o-mini"         {:context-tokens 128000 :max-output-tokens 16384 :provider :openai}
   "o3"                  {:context-tokens 200000 :max-output-tokens 100000 :provider :openai}
   "o3-mini"             {:context-tokens 200000 :max-output-tokens 100000 :provider :openai}
   "o1"                  {:context-tokens 200000 :max-output-tokens 100000 :provider :openai}})

(defn info
  "Return the model fact map for `model`, or nil when unknown.

   Tries an exact match first; falls back to a longest-prefix match so dated
   ids like `claude-opus-4-7-20260101` still resolve to the family entry."
  [model]
  (when (string? model)
    (or (get known-models model)
        ;; Longest-prefix fallback. Sort known keys by length desc so
        ;; `claude-opus-4-7-2026...` finds `claude-opus-4-7` before
        ;; `claude-opus-4`.
        (let [keys-by-len (sort-by (comp - count) (keys known-models))]
          (some (fn [k]
                  (when (str/starts-with? model k)
                    (get known-models k)))
                keys-by-len)))))

(defn context-window
  "Return the known context window (in tokens) for `model`, or nil. The
   value reflects the INPUT cap — i.e. everything the model sees on a
   request (system + tools + accumulated message history). See
   `max-output-tokens` for the per-response output ceiling."
  [model]
  (:context-tokens (info model)))

(defn max-output-tokens
  "Return the per-response output cap (in tokens) for `model`, or nil.
   This is the maximum sensible value for `:max-tokens` in a request to
   this model."
  [model]
  (:max-output-tokens (info model)))

(defn approaching-limit?
  "True when `input-tokens` is at or past `threshold` (default 0.8) of
   `model`'s context window. Returns false when the model is unknown."
  ([model input-tokens]
   (approaching-limit? model input-tokens 0.8))
  ([model input-tokens threshold]
   (when-let [cap (context-window model)]
     (>= (long input-tokens) (long (* cap threshold))))))
