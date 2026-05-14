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
