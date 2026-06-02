(ns escapement.llm.prompt-cache
  "Pure, SCI-safe placement of rolling Anthropic prefix-cache breakpoints onto
   the conversation `:messages` vector.

   Anthropic prefix caching works by stamping `cache_control` breakpoints on the
   prompt; everything up to and including the last breakpoint becomes a cached
   prefix that bills at ~10% on subsequent turns. The static `system`+`tools`
   prefix is already marked elsewhere; this fn extends caching INTO the growing
   transcript so long conversations don't re-prefill the whole `:messages` array
   every turn.

   Provider-neutral: the wire layer serializes the marker (Anthropic) or drops
   it (OpenAI, etc.), so there is no per-provider branching here. The caller
   computes the remaining breakpoint budget (Anthropic caps total breakpoints at
   4) after placing system/tools markers and passes it in as `:remaining-budget`.

   Marker shape mirrors the system/tools convention in `types.cljc`:
     {:type :ephemeral}            ; default (ttl :5m is the Anthropic default, omitted)
     {:type :ephemeral :ttl :1h}   ; explicit 1h

   Rolling rule (the invariant the tests pin):
     The breakpoint(s) land on the LAST STABLE message — the message(s) BEFORE
     the newest inbound turn. The newest inbound turn (the trailing user
     message) is never marked: caching it buys nothing because it changes every
     turn. As the transcript grows turn-over-turn the last-stable index advances
     forward, so the cached prefix grows.")

(defn- ttl->marker
  "Build the cache-control marker for `ttl`. Omits :ttl for the default (:5m or
   nil) to match the {:type :ephemeral} shape used by system/tools markers."
  [ttl]
  (if (and ttl (not= ttl :5m))
    {:type :ephemeral :ttl ttl}
    {:type :ephemeral}))

(defn- stable-count
  "How many leading messages are STABLE (eligible to cache) — i.e. everything
   except the newest inbound turn. The newest turn is the trailing user message;
   we treat the single last message as the in-flight turn, so stable = count-1.

   Returns 0 when there are <2 messages (a lone message IS the newest turn)."
  [messages]
  (max 0 (dec (count messages))))

(defn- candidate-indices
  "Stable message indices to mark, freshest-first, per `strategy`:

   - :last-stable (default) => just the single last stable index.
   - {:tail N}              => the last N stable indices, freshest first.

   Freshest-first ordering encodes the drop-latest-first... er, the priority:
   when budget < candidates we keep the FRESHEST stable boundaries (the most
   recently-stabilized prefix wins the cache hit next turn) and drop the older
   ones. Returns a (possibly empty) seq of indices ordered freshest→oldest."
  [messages strategy]
  (let [n (stable-count messages)]
    (if (zero? n)
      ()
      (let [last-stable (dec n)]
        (cond
          (and (map? strategy) (pos-int? (:tail strategy)))
          (let [tail  (:tail strategy)
                start (max 0 (- n tail))]
            ;; indices start..last-stable, freshest first
            (range last-stable (dec start) -1))

          ;; default :last-stable (or anything unrecognized)
          :else
          (list last-stable))))))

(defn place-message-breakpoints
  "Pure. Stamp rolling `:cache-control` markers on `messages` for Anthropic
   prefix caching, respecting a remaining breakpoint budget. Returns the
   (possibly unchanged) message vector.

   messages - vector of Message maps (types.cljc Message). Each may already
              carry a caller-set `:cache-control`; those are NEVER clobbered and
              do NOT consume budget here (the caller accounts for them in the
              budget it passes).
   opts:
     :remaining-budget <int>  ;; max message markers to add (4 - system/tools
                              ;; markers already used). 0 or negative => no-op.
     :enabled?         <bool> ;; default true. false => no message markers.
     :strategy         <kw|map> ;; :last-stable (default) | {:tail N}
     :ttl              <kw>   ;; :5m (default, omitted) | :1h

   Guarantees:
     - Never marks the newest inbound turn (the trailing message).
     - Adds at most :remaining-budget markers; over-budget candidates are
       dropped oldest-first (freshest stable boundaries win).
     - Returns the identical input vector when nothing changes (pure no-op)."
  [messages {:keys [remaining-budget enabled? strategy ttl]
             :or   {remaining-budget 0 enabled? true strategy :last-stable}}]
  (if (or (not enabled?)
        (<= remaining-budget 0)
        (empty? messages))
    messages
    (let [marker  (ttl->marker ttl)
          ;; freshest-first candidates, bounded by budget (drop oldest beyond it)
          chosen  (take remaining-budget (candidate-indices messages strategy))]
      (if (empty? chosen)
        messages
        (reduce (fn [msgs idx]
                  ;; never clobber a caller-set marker
                  (if (get-in msgs [idx :cache-control])
                    msgs
                    (assoc-in msgs [idx :cache-control] marker)))
          messages
          chosen)))))
