(ns escapement.ui.remote-renderer
  "A `HumanRenderer` (`escapement.invocation.human-input/HumanRenderer`) whose
   prompts are driven over the wire to the OpenTUI sidecar instead of to a local
   terminal.

   Mirrors the JLine `TuiRenderer/ask!` park-deliver pattern: each value-returning
   prompt generates a `prompt-id`, publishes a `prompt` message (per
   `docs/opentui-wire.md` §5.1) over an injected `publish-fn`, parks the calling
   (human-input worker) thread on a promise registered under that `prompt-id`, and
   returns a `com.fulcrologic.statecharts.promise` that resolves when the matching
   `answer` message arrives over any transport. A cancel rejects the promise with
   `{:reason :cancelled}` so the human-input processor posts the chart's
   `:on-cancel-event` (interrupt semantics, spec R3/R13).

   Transport-agnostic delivery seam: `deliver-answer!`/`cancel-answer!` are public
   and keyed by `prompt-id`, so BOTH the WS inbound handler (task 002/004) and the
   `escapement.human/answer` EQL mutation (task 003, resolvers.cljc) feed the same
   pending registry without either depending on the other.

   Pause-gate parity: while any prompt is pending, `human-input-active?` reports
   true, so the debug pause gate yields exactly as it does for the JLine TUI.

   This is an `escapement.ui.*` add-on; the CLI reaches it via `requiring-resolve`
   (task 004). It must NOT be statically required by engine/core code."
  (:require
    [escapement.invocation.human-input :as hi]
    [com.fulcrologic.statecharts.promise :as p]))

;; ---------------------------------------------------------------------------
;; Pending-prompt registry (the delivery seam)
;; ---------------------------------------------------------------------------
;;
;; prompt-id (string) -> {:promise <clojure promise> :type "text"|... :invokeid …}
;; A single process-wide registry: prompt-ids are globally unique (see `next-id`)
;; so a flat atom is sufficient and lets any transport deliver without a handle.

(defonce ^:private pending (atom {}))

(def ^:private id-counter (atom 0))

(defn- next-id
  "Globally-unique prompt-id of the form `<invokeid>#<n>` (matches the wire-doc
   example `ask-name#1`). The monotonic suffix guarantees uniqueness even when an
   invokeid prompts more than once."
  [invokeid]
  (str (or invokeid "prompt") "#" (swap! id-counter inc)))

(def ^:private cancelled-sentinel ::cancelled)

(defn pending?
  "True when at least one prompt is awaiting an answer. Drives the pause gate
   (`human-input-active?`)."
  []
  (boolean (seq @pending)))

(defn pending-ids
  "The set of prompt-ids currently awaiting an answer (for diagnostics/reads)."
  []
  (set (keys @pending)))

(defn human-input-active?
  "Pause-gate predicate, parity with `escapement.tui/human-input-active?`.
   True while any remote prompt is parked. The CLI wires this as the runner's
   `:human-input-active?`."
  []
  (pending?))

(defn deliver-answer!
  "Resolve the parked prompt `prompt-id` with `value`. Idempotent / safe for an
   unknown id (late or duplicate delivery → no-op, returns false). Returns true
   when a pending prompt was matched and delivered.

   This is the single delivery seam every transport calls (WS `answer` frame and
   the `escapement.human/answer` EQL mutation)."
  [prompt-id value]
  (if-let [entry (get @pending prompt-id)]
    (do
      (swap! pending dissoc prompt-id)
      (deliver (:promise entry) value)
      true)
    false))

(defn cancel-answer!
  "Reject the parked prompt `prompt-id` (the UI closed the modal with Esc).
   Resolves the underlying promise with a cancel sentinel; the renderer then
   throws `{:reason :cancelled}` so the human-input worker posts the chart's
   cancel event. Idempotent for unknown ids (returns false)."
  [prompt-id]
  (if-let [entry (get @pending prompt-id)]
    (do
      (swap! pending dissoc prompt-id)
      (deliver (:promise entry) cancelled-sentinel)
      true)
    false))

(defn cancel-all!
  "Reject every pending prompt (e.g. on sidecar disconnect / run teardown) so no
   chart worker is left parked forever."
  []
  (doseq [id (keys @pending)]
    (cancel-answer! id)))

;; ---------------------------------------------------------------------------
;; Renderer
;; ---------------------------------------------------------------------------

(defn- options->wire
  "Normalize chart-author option maps to the wire shape `[{:label … :value …}]`.
   Tolerates options that are already maps or bare scalars."
  [options]
  (mapv (fn [o]
          (if (map? o)
            {:label (:label o) :value (:value o)}
            {:label (str o) :value o}))
    (or options [])))

(defn- kind->wire-type
  "Chart `:kind` -> wire `type` string (`:multi-select` -> \"multi\")."
  [kind]
  (case kind
    :text "text"
    :select "select"
    :multi-select "multi"
    :confirm "confirm"
    (name kind)))

(defn- ask!
  "Publish a `prompt` message, park on a promise, and block until an answer (or
   cancel) is delivered via `deliver-answer!`/`cancel-answer!`. Mirrors
   `TuiRenderer/ask!`. Throws `{:reason :cancelled}` on cancel.

   `client-connected?` guards the no-sidecar case: with no client to answer, the
   prompt would park forever, so we fail fast with a clear reason (the chart sees
   `:error.human.renderer`). In `--tui=opentui` the sidecar is always present;
   this only fires on a crash/disconnect."
  [{:keys [publish-fn client-connected?]} wire-type invokeid opts]
  (when (and client-connected? (not (client-connected?)))
    (throw (ex-info "No OpenTUI sidecar connected to answer human-input prompt"
             {:reason :renderer :prompt-type wire-type :invokeid invokeid})))
  (let [prompt-id (next-id invokeid)
        prom      (promise)]
    (swap! pending assoc prompt-id
      {:promise prom :type wire-type :invokeid invokeid})
    (try
      (publish-fn {:kind      "prompt"
                   :prompt-id prompt-id
                   :invokeid  invokeid
                   :type      wire-type
                   :opts      opts})
      (catch Throwable t
        ;; Publishing failed — unpark and propagate so we never leak a pending
        ;; entry or block forever.
        (swap! pending dissoc prompt-id)
        (throw (ex-info "Failed to publish human-input prompt to sidecar"
                 {:reason :renderer :prompt-type wire-type :invokeid invokeid}
                 t))))
    (let [v @prom]
      (if (= v cancelled-sentinel)
        (throw (ex-info "User cancelled the prompt" {:reason :cancelled}))
        v))))

(defrecord RemoteUiRenderer [publish-fn client-connected?]
  hi/HumanRenderer
  (prompt-text [this {:keys [prompt invokeid]}]
    (p/do! (ask! this "text" invokeid {:prompt (or prompt "?")})))
  (prompt-select [this {:keys [prompt options invokeid]}]
    (p/do! (ask! this "select" invokeid
             {:prompt (or prompt "Select:") :options (options->wire options)})))
  (prompt-multi [this {:keys [prompt options invokeid]}]
    (p/do! (ask! this "multi" invokeid
             {:prompt (or prompt "Select any:") :options (options->wire options)})))
  (prompt-confirm [this {:keys [prompt default invokeid]}]
    (p/do! (ask! this "confirm" invokeid
             {:prompt (or prompt "Confirm?") :default (boolean default)})))
  ;; Progress is observational over the wire: publish a tiny snapshot the UI may
  ;; render later; at minimum these never error. `handle` is a local atom.
  (start-progress [this {:keys [prompt invokeid]}]
    (try (publish-fn {:kind "progress" :phase "start" :invokeid invokeid
                      :prompt prompt :pct 0})
         (catch Throwable _ nil))
    (atom {:pct 0 :prompt prompt :invokeid invokeid}))
  (update-progress [this handle pct label]
    (swap! handle assoc :pct pct :label label)
    (try (publish-fn {:kind "progress" :phase "update"
                      :invokeid (:invokeid @handle) :pct pct :label label})
         (catch Throwable _ nil)))
  (end-progress [this handle]
    (try (publish-fn {:kind "progress" :phase "end"
                      :invokeid (:invokeid @handle)})
         (catch Throwable _ nil)))
  (custom-render [_ f env data] (p/do! (f env data))))

(defn ->renderer
  "Build a `RemoteUiRenderer`.

   Opts:
     * `:publish-fn` (required) — `(fn [msg-map] …)` that serializes `msg-map`
       (a wire message per `docs/opentui-wire.md`) and pushes it to the sidecar.
       Supplied by the WS push hub (task 002).
     * `:client-connected?` (optional) — zero-arg predicate; when present and it
       returns false at prompt time, the prompt fails fast with `:reason :renderer`
       instead of parking forever. Omit to always assume a client is present."
  [{:keys [publish-fn client-connected?]}]
  (assert publish-fn ":publish-fn is required")
  (->RemoteUiRenderer publish-fn client-connected?))
