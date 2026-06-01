(ns escapement.debug.control-handle
  "A tiny shared handle that bridges the api-server control plane to the LIVE
   run. The api-server starts BEFORE the runner builds its env, so the server
   cannot be handed the env directly. Instead `cmd-run` creates this handle and
   passes it to BOTH:

     * the api-server Pathom ctx (as `:escapement/live`), and
     * `run!`'s `on-env-ready` hook, which calls `fill!` the moment the live env
       exists.

   The live resolvers/mutations deref the handle each request and degrade
   gracefully (nil) until it is filled, so the read surface keeps working before
   the chart starts.

   Babashka-safe: a plain atom."
  (:refer-clojure :exclude [empty?]))

(defn new-handle
  "Returns a fresh, empty control handle (an atom holding nil until filled)."
  []
  (atom nil))

(defn fill!
  "Populate `handle` with the live run handles. Call from `run!`'s
   `on-env-ready`. `m`:
     * `:env`        — the live `::sc/env`.
     * `:session-id` — the running session id.
     * `:queue`      — the live event queue (instrumented when debugging).
     * `:controller` — the debug controller atom (may be nil)."
  [handle m]
  (reset! handle m)
  handle)

(defn live
  "Deref `handle`, returning the live `{:env :session-id :queue :controller}` map,
   or nil if not yet filled. Tolerates a nil handle."
  [handle]
  (some-> handle deref))
