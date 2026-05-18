(ns escapement.llm.protocol
  "The `LLMBackend` protocol. A backend takes a Request map (see `escapement.llm.types`)
   and returns a Response map (also see `types`), or throws an `ex-info` on failure.

   Backends:
   - `escapement.llm.api`           — Anthropic Messages API (Anthropic / z.ai / OpenAI-compat)
   - `escapement.llm.openai-codex`  — ChatGPT Plus/Pro subscription via OAuth (Responses API)
   - `escapement.llm.cache`         — wraps any backend with a content-addressed disk cache.")

(defprotocol LLMBackend
  "An LLM conversational backend. Implementations are responsible for honoring
   (or explicitly ignoring) cache_control markers carried in the request."
  (send-turn [this request]
    "Send `request` to the backend and return a Response map. May throw on error."))

(defprotocol StreamingLLMBackend
  "OPTIONAL capability. A backend that can surface incremental output before
   the final Response. Implementing this is opt-in; callers feature-detect
   with `streaming?` and should always be able to fall back to `send-turn`."
  (stream-turn [this request on-delta]
    "Like `send-turn`, but invokes `(on-delta delta-map)` zero or more times
     as output arrives, then returns the SAME final Response map `send-turn`
     would. `delta-map` is `{:type :text-delta :text \"...\"}` (other :type
     values, e.g. `:thinking-delta`, may be added — consumers MUST ignore
     unknown `:type`). `on-delta` exceptions must not abort the turn."))

(defn streaming?
  "True when `backend` implements `StreamingLLMBackend`."
  [backend]
  (satisfies? StreamingLLMBackend backend))

(defn send-turn*
  "Capability-aware turn. Streams via `stream-turn` when `backend` supports
   it AND `on-delta` is non-nil; otherwise issues a plain `send-turn` (no
   deltas). Always returns the final Response map. This is the entry point
   callers should use so streaming stays an invisible optimization."
  [backend request on-delta]
  (if (and on-delta (streaming? backend))
    (stream-turn backend request on-delta)
    (send-turn backend request)))
