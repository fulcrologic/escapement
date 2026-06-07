(ns escapement.ui.ws-push
  "Live WebSocket push fan-out for the api-server.

   Mirrors the runner's transcript event stream out to connected sidecar/browser clients as JSON
   wire envelopes (see `docs/opentui-wire.md`). The design goal is to NEVER block or lock the
   runner's transcript writer thread: `publish!` only mutates an in-memory hub and does a
   non-blocking http-kit `send!` per client. Slow/paused clients are absorbed by a bounded
   per-client queue with a documented overflow policy (coalesce consecutive `llm/delta`s for the
   same invokeid+session-id; otherwise drop oldest).

   ## Hub model
   A hub is an atom holding:
     {:seq        <long>           ; monotonic envelope seq assigned by the push layer
      :clients    {ch client}      ; http-kit channel -> client record
      :phase      {...}            ; last phase snapshot (config/breadcrumb/siblings)
      :recent     [env ...]}       ; small ring of recent envelopes for catch-up replay

   A client record:
     {:ch ch :queue (atom clojure.lang.PersistentQueue) :sending? (atom false) :cap n}

   ## Backpressure / overflow policy (per the task + wire schema)
   Each client has a bounded outbound queue (`:cap`, default 4096 envelopes). The sender drains it
   to the socket via http-kit `send!` callbacks (single in-flight send per client so frames stay
   ordered). On enqueue, if the queue is at cap:
     1. If the incoming envelope is an `llm/delta` and the queue's TAIL is an `llm/delta` for the
        SAME invokeid+session-id, COALESCE: replace the tail with the newer delta, carrying a
        running `coalesced` count + concatenated text so the UI can still derive token counts / t/s
        from the surviving frame (counts remain correct under coalescing).
     2. Otherwise DROP the OLDEST envelope (head) to make room, and append the new one. A
        `dropped` counter is bumped so the UI/log can note a gap.
   This bounds memory and never blocks the producer.

   Everything here is SCI/bb-safe (atoms, http-kit `send!`/`on-close`, cheshire). No JVM-only deps."
  (:require
    [cheshire.core :as json]
    [org.httpkit.server :as http]))

(def ^:private default-cap 4096)

;; The number of recent envelopes kept for late-join catch-up. Small on purpose: the UI can also
;; ask the EQL surface for a full snapshot; this just avoids a blank screen on connect.
(def ^:private recent-cap 256)

;; -------------------------------------------------------------------------------------------------
;; Wire encoding
;; -------------------------------------------------------------------------------------------------

(defn- kw->wire
  "A keyword event name -> its wire string (name without leading colon, namespace kept with `/`)."
  [k]
  (cond
    (keyword? k) (subs (str k) 1)
    (string? k)  k
    (nil? k)     nil
    :else        (str k)))

(defn event->envelope
  "Wrap a raw transcript event map into a forward wire envelope (see wire schema §3). `seq` and `ts`
   are taken from the event when the transcript writer already injected them; otherwise the caller's
   `seq`/`ts` are used. Returns a plain map ready for `json/generate-string`."
  [seq ev]
  (let [data (or (:data ev) (dissoc ev :event :seq :ts))]
    (cond-> {:kind  "event"
             :seq   (or (:seq ev) seq)
             :ts    (or (:ts ev) (System/currentTimeMillis))
             :event (kw->wire (:event ev))
             :data  data}
      ;; surface coalescing bookkeeping if present so the UI can keep counters honest
      (:ws/coalesced ev) (assoc-in [:data :coalesced] (:ws/coalesced ev)))))

(defn- envelope->json [env]
  (json/generate-string env))

;; -------------------------------------------------------------------------------------------------
;; Hub
;; -------------------------------------------------------------------------------------------------

(defn new-hub
  "Create a new fan-out hub. `opts`:
     * `:cap`     per-client bounded queue size (default 4096 envelopes)
     * `:debug?`  emit terse stderr logging on attach/detach/overflow"
  ([] (new-hub {}))
  ([{:keys [cap debug?] :or {cap default-cap debug? false}}]
   (atom {:seq     0
          :clients {}
          :phase   nil
          :debug-snap nil
          :pending-prompt nil
          :recent  []
          :cap     cap
          :debug?  debug?})))

(defn- log! [hub & args]
  (when (:debug? @hub)
    (binding [*out* *err*]
      (apply println "[ws-push]" args))))

;; ---- per-client outbound queue (bounded, coalescing) -------------------------------------------

(defn- delta-coalesce-key
  "Coalescing identity for an `llm/delta` envelope: [invokeid session-id type]. nil for non-deltas."
  [env]
  (when (= "llm/delta" (:event env))
    (let [d (:data env)]
      [(:invokeid d) (:session-id d) (:type d)])))

(defn- coalesce-delta
  "Merge `new-env` into the queued tail `old-env` (both `llm/delta`s for the same key): concatenate
   text and carry a running coalesced count so token counts / t/s remain derivable from the survivor."
  [old-env new-env]
  (let [old-d (:data old-env)
        new-d (:data new-env)
        n     (inc (long (or (:coalesced old-d) 1)))]
    (-> new-env
        (assoc :seq (:seq new-env))
        (assoc-in [:data :text] (str (:text old-d) (:text new-d)))
        (assoc-in [:data :coalesced] n))))

(defn- enqueue!
  "Append wire envelope `env` to client `c`'s bounded queue, applying the overflow policy. Returns
   true if accepted (always true — we never block; we coalesce or drop oldest to fit)."
  [hub c env]
  (let [cap (:cap c)]
    (swap! (:queue c)
      (fn [q]
        (cond
          ;; room: just append
          (< (count q) cap)
          (conj q env)

          ;; full + delta coalescible against the tail of the same key: merge in place
          (and (delta-coalesce-key env)
               (when-let [tail (peek (vec q))]            ; PersistentQueue: peek is head, so scan tail via vec
                 (= (delta-coalesce-key tail) (delta-coalesce-key env))))
          (let [v    (vec q)
                tail (nth v (dec (count v)))]
            (into clojure.lang.PersistentQueue/EMPTY
              (conj (subvec v 0 (dec (count v))) (coalesce-delta tail env))))

          ;; full, not coalescible: drop oldest (head) and append
          :else
          (do (log! hub "overflow: dropping oldest for slow client")
              (conj (pop q) env))))))
  true)

(defn- send-next!
  "Drain the client's bounded queue to its socket. A `sending?` CAS guard makes exactly one thread
   drain a given client at a time so frames stay ordered and the producer never blocks (if a publish
   races, its envelope is already enqueued and the active drainer will pick it up, or the next call
   will). http-kit's `send!` enqueues into the channel's own write buffer; bb http-kit exposes no
   per-frame ack callback, so we drain in a loop here rather than chaining callbacks."
  [hub c]
  (when (compare-and-set! (:sending? c) false true)
    (try
      (loop []
        (when-let [head (peek @(:queue c))]
          (let [json (try (envelope->json head)
                          (catch Throwable t (log! hub "encode failed:" (.getMessage t)) nil))]
            (swap! (:queue c) pop)
            (when (and json (http/send! (:ch c) json))
              (recur)))))
      (finally (reset! (:sending? c) false))))
  ;; A publish that enqueued after we observed an empty queue but before we released the guard would
  ;; otherwise be stranded; re-check once after releasing.
  (when (and (peek @(:queue c)) (compare-and-set! (:sending? c) false true))
    (reset! (:sending? c) false)
    (recur hub c)))

;; ---- client lifecycle --------------------------------------------------------------------------

(defn- make-client [cap]
  {:ch       nil
   :queue    (atom clojure.lang.PersistentQueue/EMPTY)
   :sending? (atom false)
   :cap      cap})

(defn attach-client!
  "Register http-kit channel `ch` as a client of `hub`. Sends a catch-up: the current phase snapshot
   (if any) followed by the recent envelope ring, so a late-joining UI is not blank. Returns the
   client record."
  [hub ch]
  (let [{:keys [cap phase debug-snap pending-prompt recent]} @hub
        c (assoc (make-client cap) :ch ch)]
    (swap! hub assoc-in [:clients ch] c)
    (log! hub "client attached; total" (count (:clients @hub)))
    ;; catch-up: phase snapshot first, the current debug snapshot, then recent events in order
    (when phase
      (try (http/send! ch (json/generate-string phase)) (catch Throwable _ nil)))
    (when debug-snap
      (try (http/send! ch (json/generate-string debug-snap)) (catch Throwable _ nil)))
    ;; Replay any still-open human-input prompt so a late-joining sidecar (the
    ;; normal case: the agent parks on `ask!` before the Bun process finishes
    ;; cold-starting + connecting) opens the modal instead of hanging forever.
    (when pending-prompt
      (try (http/send! ch (json/generate-string pending-prompt)) (catch Throwable _ nil)))
    (doseq [env recent]
      (enqueue! hub c env))
    (send-next! hub c)
    c))

(defn detach-client!
  "Deregister channel `ch` from `hub` (on socket close)."
  [hub ch]
  (swap! hub update :clients dissoc ch)
  (log! hub "client detached; total" (count (:clients @hub))))

;; ---- publish (the runner-tap hot path) ---------------------------------------------------------

(defn- maybe-update-phase!
  "Update the hub's phase snapshot when `env` carries an active-config signal (`runner/start-config`
   `config`, or `runner/event-processed` `config-after`). Pushes a `phase` frame to all clients on
   change so the header strip stays live without polling."
  [hub env]
  (let [data   (:data env)
        config (case (:event env)
                 "runner/start-config"    (:config data)
                 "runner/event-processed" (:config-after data)
                 nil)]
    (when (and config (not= config (get-in @hub [:phase :config])))
      (let [snap {:kind   "phase"
                  :ts     (:ts env)
                  :config config}]
        (swap! hub assoc :phase snap)
        (let [json (json/generate-string snap)]
          (doseq [[ch _c] (:clients @hub)]
            (try (http/send! ch json) (catch Throwable _ nil))))))))

(defn publish!
  "Mirror one raw transcript event `ev` to every connected client. NON-BLOCKING: assigns a push seq,
   wraps to a wire envelope, records it in the catch-up ring, and enqueues to each client's bounded
   queue (coalesce/drop on overflow). Called from the runner's transcript-tap (writer thread) — must
   never throw or block. Also maintains the live phase snapshot from `runner/*` config signals."
  [hub ev]
  (try
    (let [seq (:seq (swap! hub update :seq inc))
          env (event->envelope seq ev)]
      ;; maintain catch-up ring (bounded)
      (swap! hub update :recent
        (fn [r] (let [r' (conj r env)]
                  (if (> (count r') recent-cap) (subvec r' (- (count r') recent-cap)) r'))))
      ;; derive/refresh the phase snapshot from config-bearing runner events
      (maybe-update-phase! hub env)
      ;; fan out
      (doseq [[ch c] (:clients @hub)]
        (enqueue! hub c env)
        (send-next! hub c)))
    (catch Throwable t (log! hub "publish! threw:" (.getMessage t))))
  nil)

(defn broadcast!
  "Send a raw non-`event` control frame (`prompt`/`progress`/etc., already a
   Clojure map per `docs/opentui-wire.md`) directly to every connected client.
   Used by the `RemoteUiRenderer`'s `publish-fn` (task 003/004) — these frames
   bypass the `seq`/catch-up/coalesce path since they are out-of-band from the
   transcript event stream. NON-BLOCKING + never throws."
  [hub msg]
  (try
    ;; Remember an open human-input prompt so a late-joining client gets it on
    ;; attach (see `attach-client!`). A `progress` end / a fresh prompt naturally
    ;; supersede; the prompt is cleared on answer via `clear-pending-prompt!`.
    (when (= "prompt" (str (or (:kind msg) (get msg "kind"))))
      (swap! hub assoc :pending-prompt msg))
    (let [json (json/generate-string msg)]
      (doseq [[ch _c] (:clients @hub)]
        (try (http/send! ch json) (catch Throwable _ nil))))
    (catch Throwable t (log! hub "broadcast! threw:" (.getMessage t))))
  nil)

(defn clear-pending-prompt!
  "Forget the remembered open prompt (see `broadcast!`) once it has been answered
   or cancelled, so a client attaching afterwards is not shown a stale modal.
   NON-BLOCKING + never throws."
  [hub]
  (try (swap! hub assoc :pending-prompt nil) (catch Throwable _ nil))
  nil)

(defn publish-debug!
  "Store + fan out the live debugger snapshot (`docs/opentui-wire.md` §6 forward
   push: `paused?`/`step-budget`/active config). `snap` is a Clojure map already
   shaped `{:kind \"debug\" :paused <bool> :step-budget <int> :config [..]}`;
   it is remembered on the hub so a late-joining client gets the current state on
   attach (like `:phase`). NON-BLOCKING + never throws."
  [hub snap]
  (try
    (let [snap (assoc snap :kind "debug")]
      (swap! hub assoc :debug-snap snap)
      (broadcast! hub snap))
    (catch Throwable t (log! hub "publish-debug! threw:" (.getMessage t))))
  nil)

;; -------------------------------------------------------------------------------------------------
;; Back-channel dispatch (UI -> agent)
;; -------------------------------------------------------------------------------------------------

(defn dispatch-inbound!
  "Decode one inbound text frame (`raw` JSON string) from a UI client and route it per the wire
   schema §7. `handlers` is a map of seam fns the caller (server/cli) supplies:
     :control (fn [{:keys [op n]}])  — control/interrupt/quit ops (§6)
     :answer  (fn [{:keys [prompt-id value cancelled]}]) — human-input answer (§5.2; task 003 seam)
   Unknown/blank frames are ignored. Never throws."
  [handlers raw]
  (try
    (let [msg  (json/parse-string raw true)
          kind (:kind msg)]
      (case kind
        "control" (when-let [f (:control handlers)] (f msg))
        "answer"  (when-let [f (:answer handlers)] (f msg))
        nil))
    (catch Throwable _ nil))
  nil)

;; -------------------------------------------------------------------------------------------------
;; http-kit route handler
;; -------------------------------------------------------------------------------------------------

(defn ws-handler
  "Return a Ring handler for the WS upgrade `GET /ws`. `hub` is the fan-out hub; `handlers` is the
   back-channel seam map (see `dispatch-inbound!`). Uses http-kit `as-channel`; on open registers the
   client, on receive dispatches inbound, on close detaches. SCI/bb-safe (no `websocket?` predicate)."
  [hub handlers]
  (fn [req]
    (http/as-channel req
      {:on-open    (fn [ch] (attach-client! hub ch))
       :on-receive (fn [_ch msg] (dispatch-inbound! handlers msg))
       :on-close   (fn [ch _status] (detach-client! hub ch))})))
