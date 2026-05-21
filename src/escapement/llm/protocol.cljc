(ns escapement.llm.protocol
  "The `LLMBackend` protocol. A backend takes a Request map (see `escapement.llm.types`)
   and returns a Response map (also see `types`), or throws an `ex-info` on failure.

   Backends:
   - `escapement.llm.api`           — Anthropic Messages API (Anthropic / z.ai / OpenAI-compat)
   - `escapement.llm.openai-codex`  — ChatGPT Plus/Pro subscription via OAuth (Responses API)
   - `escapement.llm.cache`         — wraps any backend with a content-addressed disk cache.")

(def error-categories
  "Canonical set of backend-failure categories. A backend SHOULD throw
   `(llm-error category msg ...)` with `category` drawn from this set so a
   consumer (and a statechart on top of it) can branch on the failure class
   instead of treating every failure identically."
  #{:rate-limited :overloaded :auth :invalid-request :context-length :timeout :transport})

(defn llm-error
  "Construct a categorized backend failure as an `ex-info`. `category` must be
   one of `error-categories`. Backends SHOULD throw this so consumers can
   branch on the failure class; an uncategorized throwable is treated as the
   generic backend error (back-compat).

   `opts` may carry `:status` (HTTP status int), `:cause` (a wrapped
   Throwable) and `:data` (extra ex-data merged in, e.g. `:body`/`:url`)."
  ([category message] (llm-error category message nil))
  ([category message {:keys [status cause data] :as _opts}]
   (ex-info message
     (merge {:llm/category category}
       (when status {:llm/status status})
       data)
     cause)))

(defn error-category
  "Returns the `:llm/category` keyword carried by `throwable` (walking the
   `ex-cause` chain so a wrapped/rethrown categorized error is still
   detectable), or nil if uncategorized."
  [throwable]
  (loop [t throwable]
    (when t
      (or (:llm/category (ex-data t))
        (recur (ex-cause t))))))

(defprotocol LLMBackend
  "An LLM conversational backend. Implementations are responsible for honoring
   (or explicitly ignoring) cache_control markers carried in the request.

   Cross-host async contract: `send-turn` returns a promise from
   `com.fulcrologic.statecharts.promise` that resolves to a Response map
   or rejects with a categorized error. Implementations on CLJ/bb can
   compute synchronously and wrap with `(p/resolved …)`; implementations
   on CLJS use native promises end-to-end. Consumers should chain via
   `p/then`/`p/catch` or block via `p/await!` (CLJ/bb only)."
  (send-turn [this request]
    "Send `request` to the backend. Returns a promise of a Response map.

     Backends SHOULD reject the promise with `(llm-error category msg ...)`
     carrying a category from `error-categories`; consumers map known
     categories to `:error.llm.<category>` chart events and fall back to
     `:error.llm.backend` when the throwable is uncategorized.

     A synchronous throw is tolerated for back-compat (the worker catches
     either way), but impls should prefer `p/rejected` to keep the contract
     uniform on hosts where sync throws are not propagated through promise
     chains."))

(defprotocol StreamingLLMBackend
  "OPTIONAL capability. A backend that can surface incremental output before
   the final Response. Implementing this is opt-in; callers feature-detect
   with `streaming?` and should always be able to fall back to `send-turn`."
  (stream-turn [this request on-delta]
    "Like `send-turn`, but invokes `(on-delta delta-map)` zero or more times
     as output arrives. Returns a promise of the SAME final Response map
     `send-turn` would yield. `delta-map` is `{:type :text-delta :text \"...\"}`
     (other :type values, e.g. `:thinking-delta`, may be added — consumers
     MUST ignore unknown `:type`). `on-delta` exceptions must not abort the
     turn."))

(defn streaming?
  "True when `backend` implements `StreamingLLMBackend`."
  [backend]
  (satisfies? StreamingLLMBackend backend))

(defn send-turn*
  "Capability-aware turn. Streams via `stream-turn` when `backend` supports
   it AND `on-delta` is non-nil; otherwise issues a plain `send-turn` (no
   deltas). Returns a promise of the final Response map. This is the entry
   point callers should use so streaming stays an invisible optimization."
  [backend request on-delta]
  (if (and on-delta (streaming? backend))
    (stream-turn backend request on-delta)
    (send-turn backend request)))
