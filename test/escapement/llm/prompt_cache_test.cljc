(ns escapement.llm.prompt-cache-test
  "Unit tests for the pure rolling-breakpoint placement fn.

   Conventions confirmed from 001-results.md + types.cljc:
   - The breakpoint lands on the LAST STABLE message — the message before the
     newest inbound turn. The newest turn is never cached (caching it buys
     nothing next turn).
   - Budget is a plain int the caller computes (4 - markers already used by
     system+tools). 0 => no-op.
   - Marker shape mirrors system/tools: {:type :ephemeral} by default
     (ttl :5m omitted), {:type :ephemeral :ttl :1h} for :1h."
  (:require
    [escapement.llm.prompt-cache :as pc]
    [fulcro-spec.core :refer [=> assertions specification]]))

(defn- u [text] {:role :user :content [{:type :text :text text}]})
(defn- a [text] {:role :assistant :content [{:type :text :text text}]})

(defn- marked?
  "True if the message at `idx` carries a :cache-control marker."
  [messages idx]
  (some? (get-in messages [idx :cache-control])))

(defn- marked-indices
  "Set of message indices carrying a :cache-control marker."
  [messages]
  (into #{} (filter #(marked? messages %)) (range (count messages))))

(defn- default-opts
  ([] (default-opts {}))
  ([overrides] (merge {:remaining-budget 4 :enabled? true} overrides)))

(specification "place-message-breakpoints — empty / short vectors"
  (assertions
    "empty messages => unchanged, no markers (identity value)"
    (pc/place-message-breakpoints [] (default-opts)) => []
    "empty messages returns the same (identical) reference"
    (let [in []]
      (identical? in (pc/place-message-breakpoints in (default-opts)))) => true
    "single message IS the newest inbound turn => no marker (nothing stable)"
    (marked-indices (pc/place-message-breakpoints [(u "hi")] (default-opts))) => #{}
    "single message returned unchanged"
    (pc/place-message-breakpoints [(u "hi")] (default-opts)) => [(u "hi")]))

(specification "place-message-breakpoints — last-stable placement"
  (let [msgs [(u "q1") (a "a1") (u "q2")]
        out  (pc/place-message-breakpoints msgs (default-opts))]
    (assertions
      "marker lands on the last STABLE message (index 1), not the newest user turn (index 2)"
      (marked-indices out) => #{1}
      "newest inbound turn is NOT marked"
      (marked? out 2) => false
      "the stable message before it IS marked"
      (marked? out 1) => true))
  (let [msgs [(u "q1") (a "a1") (u "q2") (a "a2") (u "q3")]
        out  (pc/place-message-breakpoints msgs (default-opts))]
    (assertions
      "with 5 messages the marker tracks the last stable message (index 3)"
      (marked-indices out) => #{3})))

(specification "place-message-breakpoints — rolling advance across turns"
  ;; Simulate the @messages-atom growing turn over turn. The marker index must
  ;; advance forward to track the latest stable message.
  (let [turn1 [(u "q1")]
        turn2 [(u "q1") (a "a1") (u "q2")]
        turn3 [(u "q1") (a "a1") (u "q2") (a "a2") (u "q3")]
        i1    (first (marked-indices (pc/place-message-breakpoints turn1 (default-opts))))
        i2    (first (marked-indices (pc/place-message-breakpoints turn2 (default-opts))))
        i3    (first (marked-indices (pc/place-message-breakpoints turn3 (default-opts))))]
    (assertions
      "turn 1 (only the newest turn exists) => no stable message to mark"
      i1 => nil
      "turn 2 marks index 1"
      i2 => 1
      "turn 3 marks index 3 — the breakpoint advanced forward"
      i3 => 3
      "marker index strictly increases as the conversation grows"
      (< i2 i3) => true)))

(specification "place-message-breakpoints — budget"
  (let [msgs [(u "q1") (a "a1") (u "q2") (a "a2") (u "q3")]]
    (assertions
      "remaining-budget 0 => no message markers (unchanged)"
      (marked-indices (pc/place-message-breakpoints msgs (default-opts {:remaining-budget 0}))) => #{}
      "remaining-budget 0 returns the input unchanged"
      (pc/place-message-breakpoints msgs (default-opts {:remaining-budget 0})) => msgs
      "remaining-budget 1 => exactly one marker"
      (count (marked-indices (pc/place-message-breakpoints msgs (default-opts {:remaining-budget 1})))) => 1
      "marker count never exceeds the budget"
      (<= (count (marked-indices
                   (pc/place-message-breakpoints msgs (default-opts {:remaining-budget 1
                                                                     :strategy {:tail 5}}))))
        1) => true)))

(specification "place-message-breakpoints — rolling {:tail N} strategy + drop-latest-first"
  (let [msgs [(u "q1") (a "a1") (u "q2") (a "a2") (u "q3")]]
    ;; Stable messages are indices 0..3 (index 4 is the newest user turn).
    (assertions
      "tail 2 with budget 2 marks the two freshest stable boundaries (indices 2 and 3)"
      (marked-indices (pc/place-message-breakpoints msgs (default-opts {:strategy {:tail 2}}))) => #{2 3}
      "tail 2 with budget 1 keeps only the freshest stable boundary (index 3); the older candidate is dropped"
      (marked-indices (pc/place-message-breakpoints msgs (default-opts {:strategy {:tail 2} :remaining-budget 1})))
      => #{3}
      "drop-latest-first: more candidates than budget never exceeds budget"
      (<= (count (marked-indices
                   (pc/place-message-breakpoints msgs (default-opts {:strategy {:tail 4} :remaining-budget 2}))))
        2) => true)))

(specification "place-message-breakpoints — TTL"
  (let [msgs [(u "q1") (a "a1") (u "q2")]]
    (assertions
      "default (no :ttl) => {:type :ephemeral} (5m omitted, matching system/tools)"
      (get-in (pc/place-message-breakpoints msgs (default-opts)) [1 :cache-control])
      => {:type :ephemeral}
      ":ttl :5m explicitly => still {:type :ephemeral} (default ttl omitted)"
      (get-in (pc/place-message-breakpoints msgs (default-opts {:ttl :5m})) [1 :cache-control])
      => {:type :ephemeral}
      ":ttl :1h => {:type :ephemeral :ttl :1h}"
      (get-in (pc/place-message-breakpoints msgs (default-opts {:ttl :1h})) [1 :cache-control])
      => {:type :ephemeral :ttl :1h})))

(specification "place-message-breakpoints — disabled / never-clobber"
  (let [msgs [(u "q1") (a "a1") (u "q2")]]
    (assertions
      ":enabled? false => messages unchanged (no markers)"
      (pc/place-message-breakpoints msgs (default-opts {:enabled? false})) => msgs
      ":enabled? false returns the input unchanged (no markers)"
      (marked-indices (pc/place-message-breakpoints msgs (default-opts {:enabled? false}))) => #{})
    ;; A caller-set marker on a message must never be clobbered.
    (let [pre  (assoc-in msgs [0 :cache-control] {:type :ephemeral :ttl :1h})
          out  (pc/place-message-breakpoints pre (default-opts))]
      (assertions
        "a pre-existing caller-set marker is preserved verbatim"
        (get-in out [0 :cache-control]) => {:type :ephemeral :ttl :1h}))))
