(ns escapement.ui.opentui-push-test
  "Agent-side proof (under `bb test`) of the OpenTUI live push + human-input
   back-channel, the two seams the `--tui=opentui` sidecar rides:

     1. WS push fan-out (`escapement.ui.ws-push`): `publish!` mirrors raw
        transcript events to connected clients as ordered, correctly-encoded
        JSON wire envelopes (incl. `:llm/delta`), maintains the `phase`
        snapshot on config change, and absorbs slow clients via the bounded
        per-client queue (coalesce consecutive deltas / drop oldest) WITHOUT
        blocking the producer.
     2. `RemoteUiRenderer` prompt→answer round-trip: a prompt parks a worker
        on a promise, an answer (via the public `deliver-answer!` seam AND via
        the real `escapement.human/answer` EQL mutation through the Pathom
        parser) resolves it; a cancel rejects with `{:reason :cancelled}`;
        `human-input-active?` is true while parked.

   Style mirrors `escapement.ui.live-control-http-test`: drive the public seams
   directly, no real engine thread. For the fan-out we drive the hub directly
   with a stubbed `http/send!` (captures per-client frames) so the test is
   race-free and SCI-safe — PLUS one REAL RFC-6455 socket attach→receive proof
   over a live http-kit `GET /ws` route, since task 001/002 proved the upgrade
   works under bb.

   Runs under `bb test` (normal `*_test.clj`, NOT JVM-only)."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [com.fulcrologic.statecharts.promise :as p]
    [escapement.invocation.human-input]
    [escapement.ui.remote-renderer :as rr]
    [escapement.ui.resolvers :as resolvers]
    [escapement.ui.server :as server]
    [escapement.ui.ws-push :as ws]
    [escapement.protocols :as proto]
    [fulcro-spec.core :refer [=> assertions component specification]]
    [org.httpkit.server :as http])
  (:import
    (java.io DataInputStream)
    (java.net Socket)
    (java.security MessageDigest)
    (java.util Base64)))

;; ---------------------------------------------------------------------------
;; Fan-out via a stubbed http/send! — captures frames per fake channel.
;; ---------------------------------------------------------------------------
;;
;; A "channel" here is just a unique object key; `with-redefs` on `http/send!`
;; routes captured JSON strings into `sent` keyed by that object. This exercises
;; the FULL hub logic (attach catch-up, publish ordering, phase derivation,
;; bounded-queue overflow policy) without a socket.

(defn- with-capture
  "Run `f` with `http/send!` capturing every (ch, payload) into the returned
   atom `{ch [json …]}`. `f` receives the capture atom. Returns the atom."
  [f]
  (let [sent (atom {})]
    (with-redefs [http/send! (fn [ch payload]
                               (swap! sent update ch (fnil conj []) payload)
                               true)]
      (f sent))
    sent))

(defn- frames-for
  "Decoded JSON frames captured for fake channel `ch`, in send order."
  [sent ch]
  (mapv #(json/parse-string % true) (get @sent ch [])))

(defn- delta-ev
  "A raw `:llm/delta` transcript event for `invokeid` carrying `text`."
  [invokeid text]
  {:event :llm/delta
   :data  {:type "text-delta" :text text :invokeid invokeid
           :session-id "session/abc"}})

;; ---------------------------------------------------------------------------
;; A minimal read store (only to satisfy the EQL `process` ctx, like the
;; live-control test). The human/answer mutation never reads it.
;; ---------------------------------------------------------------------------

(defn- read-store []
  (reify
    proto/SessionIndex
    (list-sessions [_] [])
    proto/TranscriptStore
    (append-event! [_ _ _] nil)
    (read-events [_ _ _] [])
    proto/ArtifactStore
    (write-artifact! [_ _ _ _ _] nil)
    (list-artifacts [_ _] [])
    (read-artifact [_ _ _] nil)))

;; ===========================================================================
;; 1. Pure wire encoder
;; ===========================================================================

(specification "event->envelope: pure transcript-event -> wire envelope"
  (let [env (ws/event->envelope 7 (delta-ev "planner" "Hi"))]
    (assertions
      "wraps with kind=event and the supplied seq"
      (:kind env) => "event"
      (:seq env) => 7
      "keyword event name -> wire string without the leading colon"
      (:event env) => "llm/delta"
      "carries the raw event data through verbatim"
      (:data env) => {:type "text-delta" :text "Hi" :invokeid "planner"
                      :session-id "session/abc"}
      "a ts is always present"
      (number? (:ts env)) => true))
  (assertions
    "an event that already carries :seq/:ts wins over the caller's seq"
    (select-keys (ws/event->envelope 99 {:event :runner/started :seq 3 :ts 42 :data {}})
      [:seq :ts :event])
    => {:seq 3 :ts 42 :event "runner/started"}))

;; ===========================================================================
;; 2. Fan-out ordering + delta encoding + phase snapshot
;; ===========================================================================

(specification "ws-push fan-out: ordered, correctly-encoded envelopes incl. deltas"
  (component "two attached clients each receive every published event in :seq order"
    (with-capture
      (fn [sent]
        (let [hub (ws/new-hub)
              a   (Object.)
              b   (Object.)]
          (ws/attach-client! hub a)
          (ws/attach-client! hub b)
          (ws/publish! hub {:event :llm/start :data {:invokeid "planner"}})
          (ws/publish! hub (delta-ev "planner" "Hel"))
          (ws/publish! hub (delta-ev "planner" "lo"))
          (ws/publish! hub {:event :llm/response :data {:invokeid "planner"}})
          (doseq [ch [a b]]
            (let [fs (frames-for sent ch)]
              (assertions
                "all four events arrived as wire envelopes"
                (mapv :event fs) => ["llm/start" "llm/delta" "llm/delta" "llm/response"]
                "the push seq is monotonic gap-free in delivery order"
                (mapv :seq fs) => [1 2 3 4]
                "every frame is kind=event"
                (every? #(= "event" (:kind %)) fs) => true
                "delta text round-trips through the JSON envelope"
                (->> fs (filter #(= "llm/delta" (:event %))) (map #(get-in % [:data :text])))
                => ["Hel" "lo"])))))))

  (component "a config-bearing runner event pushes a `phase` snapshot to all clients"
    (with-capture
      (fn [sent]
        (let [hub (ws/new-hub)
              a   (Object.)]
          (ws/attach-client! hub a)
          (ws/publish! hub {:event :runner/start-config :ts 11
                            :data {:config #{:S/root :S/working}}})
          (let [fs    (frames-for sent a)
                phase (first (filter #(= "phase" (:kind %)) fs))]
            (assertions
              "a phase frame was emitted on the config change"
              (some? phase) => true
              "it carries the active configuration (as a JSON array of state names)"
              (set (map keyword (:config phase))) => #{:S/root :S/working})))))))

;; ===========================================================================
;; 3. Backpressure: bounded queue coalesces deltas / drops oldest, never blocks
;; ===========================================================================

;; The bounded-queue overflow policy lives in the private `enqueue!`; the public
;; `publish!` immediately drains via `send-next!`, so to isolate the OVERFLOW
;; behavior (coalesce/drop) we drive `enqueue!` directly against a client record
;; whose queue is never drained — exactly the "slow/paused client" condition.
;; This keeps the test deterministic (no draining race) and SCI-safe.

(defn- fake-client [cap]
  {:ch (Object.) :queue (atom clojure.lang.PersistentQueue/EMPTY) :sending? (atom false) :cap cap})

(def ^:private enqueue! #'ws/enqueue!)

(specification "ws-push backpressure: bounded per-client queue absorbs a slow client"
  (component "consecutive deltas for one invokeid coalesce when the queue is full (counts preserved)"
    (let [hub (ws/new-hub {:cap 2})
          c   (fake-client 2)]
      ;; cap=2; enqueue 5 same-key deltas. Tail-coalescing collapses overflow
      ;; into the queue tail rather than dropping, so total text is preserved.
      (doseq [t ["a" "b" "c" "d" "e"]]
        (enqueue! hub c (ws/event->envelope 0 (delta-ev "planner" t))))
      (let [q    (vec @(:queue c))
            tail (last q)]
        (assertions
          "the producer never blocked — queue stayed bounded at cap=2"
          (<= (count q) 2) => true
          "the coalesced tail concatenates the overflowed deltas' text (counts derivable)"
          (apply str (map #(get-in % [:data :text]) q)) => "abcde"
          "the survivor carries a coalesced count > 1"
          (> (long (or (get-in tail [:data :coalesced]) 1)) 1) => true))))

  (component "non-coalescible overflow drops the OLDEST envelope (head)"
    (let [hub (ws/new-hub {:cap 2})
          c   (fake-client 2)]
      ;; distinct events (different keys) can't coalesce -> oldest dropped
      (enqueue! hub c (ws/event->envelope 1 {:event :llm/start :data {:invokeid "p"}}))
      (enqueue! hub c (ws/event->envelope 2 {:event :tool/started :data {:invokeid "p"}}))
      (enqueue! hub c (ws/event->envelope 3 {:event :llm/response :data {:invokeid "p"}}))
      (let [evs (mapv :event (vec @(:queue c)))]
        (assertions
          "queue bounded at cap=2"
          (count evs) => 2
          "oldest (llm/start) was dropped; the two newest survive in order"
          evs => ["tool/started" "llm/response"])))))

;; ===========================================================================
;; 4. Inbound back-channel dispatch (pure)
;; ===========================================================================

(specification "dispatch-inbound!: routes control/answer frames to seam fns"
  (let [seen (atom [])
        handlers {:control (fn [m] (swap! seen conj [:control m]))
                  :answer  (fn [m] (swap! seen conj [:answer m]))}]
    (ws/dispatch-inbound! handlers (json/generate-string {:kind "control" :op "step" :n 1}))
    (ws/dispatch-inbound! handlers (json/generate-string {:kind "answer" :prompt-id "x#1" :value "v"}))
    (ws/dispatch-inbound! handlers "not json {{{")
    (ws/dispatch-inbound! handlers (json/generate-string {:kind "bogus"}))
    (assertions
      "control + answer routed with parsed keyword maps; junk/unknown ignored"
      @seen => [[:control {:kind "control" :op "step" :n 1}]
                [:answer {:kind "answer" :prompt-id "x#1" :value "v"}]])))

;; ===========================================================================
;; 5. RemoteUiRenderer prompt -> answer round-trip
;; ===========================================================================
;;
;; `ask!` parks the calling thread (native promise mode under bb runs `p/do!`'s
;; body synchronously), so each prompt is driven on a future; the test thread
;; delivers the answer, then awaits the renderer's promise.

(defn- new-renderer [published]
  (rr/->renderer {:publish-fn (fn [msg] (swap! published conj msg))}))

(defn- await-id
  "Poll until exactly one prompt is pending, then return its id (or nil on timeout)."
  []
  (loop [i 0]
    (cond
      (seq (rr/pending-ids)) (first (rr/pending-ids))
      (> i 200) nil
      :else (do (Thread/sleep 5) (recur (inc i))))))

(specification "RemoteUiRenderer: prompt -> answer resolves; cancel rejects; pause-gate flips"
  (rr/cancel-all!)                                  ; clean slate (process-wide registry)

  (component "prompt-text round-trips: published prompt + delivered answer resolves the promise"
    (let [published (atom [])
          r         (new-renderer published)
          fut       (future (p/await! (#'escapement.invocation.human-input/prompt-text
                                        r {:prompt "Name?" :invokeid "ask-name"})))
          pid       (await-id)]
      (assertions
        "exactly one prompt parked -> human-input-active? is true while pending"
        (rr/human-input-active?) => true
        (rr/pending?) => true
        "a `prompt` wire message was published with the right shape"
        (let [m (last @published)]
          (select-keys m [:kind :type :invokeid])) => {:kind "prompt" :type "text" :invokeid "ask-name"}
        "prompt-id is the `<invokeid>#<n>` scheme"
        (boolean (re-matches #"ask-name#\d+" pid)) => true)
      ;; deliver via the public seam
      (assertions
        "deliver-answer! matches the pending prompt"
        (rr/deliver-answer! pid "Ada") => true
        "the renderer promise resolves with the delivered value"
        (deref fut 1000 :timeout) => "Ada"
        "registry drained -> pause gate releases"
        (rr/pending?) => false
        (rr/human-input-active?) => false)))

  (component "cancel-answer! rejects the prompt with {:reason :cancelled}"
    (let [published (atom [])
          r         (new-renderer published)
          fut       (future (try (p/await! (#'escapement.invocation.human-input/prompt-confirm
                                             r {:prompt "Sure?" :invokeid "ask-ok"}))
                                 (catch Throwable t (ex-data t))))
          pid       (await-id)]
      (assertions
        "cancel matches the pending prompt"
        (rr/cancel-answer! pid) => true
        "the prompt throws with the cancel reason -> interrupt semantics"
        (deref fut 1000 :timeout) => {:reason :cancelled}
        "registry drained"
        (rr/pending?) => false)))

  (component "the escapement.human/answer EQL mutation delivers through the real Pathom parser"
    (let [published (atom [])
          r         (new-renderer published)
          ;; The renderer registry is process-wide; the mutation resolves it via
          ;; requiring-resolve, so a live prompt here is answerable over EQL.
          fut       (future (p/await! (#'escapement.invocation.human-input/prompt-text
                                        r {:prompt "City?" :invokeid "ask-city"})))
          pid       (await-id)
          res       (resolvers/process {:escapement/store (read-store)}
                      `[(escapement.human/answer {:prompt-id ~pid :value "Cairo"})])]
      (assertions
        "the mutation reports it matched + delivered a pending prompt"
        (get res `escapement.human/answer) => {:human/delivered? true}
        "the parked renderer promise resolves with the EQL-delivered value"
        (deref fut 1000 :timeout) => "Cairo")))

  (component "the EQL mutation can also cancel a pending prompt"
    (let [published (atom [])
          r         (new-renderer published)
          fut       (future (try (p/await! (#'escapement.invocation.human-input/prompt-text
                                             r {:prompt "?" :invokeid "ask-x"}))
                                 (catch Throwable t (ex-data t))))
          pid       (await-id)
          res       (resolvers/process {:escapement/store (read-store)}
                      `[(escapement.human/answer {:prompt-id ~pid :cancelled true})])]
      (assertions
        "the mutation reports delivered (a cancel is a match)"
        (get res `escapement.human/answer) => {:human/delivered? true}
        "the prompt rejects with the cancel reason"
        (deref fut 1000 :timeout) => {:reason :cancelled}))))

;; ===========================================================================
;; 6. REAL socket: RFC-6455 attach -> receive ordered push frames over GET /ws
;; ===========================================================================
;;
;; Proves the live route end-to-end (http-kit `as-channel` upgrade under bb,
;; per task 001/002), not just the hub internals. Hand-rolled client: HTTP
;; upgrade handshake, then read server text frames (server->client frames are
;; never masked, so the reader is small).

(defn- ws-accept [key]
  (let [magic "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        md    (MessageDigest/getInstance "SHA-1")]
    (.encodeToString (Base64/getEncoder) (.digest md (.getBytes (str key magic) "UTF-8")))))

(defn- read-text-frame
  "Read one unmasked server text frame from `in` (DataInputStream). Returns the
   UTF-8 payload string. Handles 7-bit and 16-bit length only (frames here are
   small). 3-arg InputStream.read is avoided per the bb note."
  [^DataInputStream in]
  (let [b0  (.readUnsignedByte in)               ; FIN+opcode
        _   b0
        b1  (.readUnsignedByte in)
        len0 (bit-and b1 0x7f)
        len  (cond
               (< len0 126) len0
               (= len0 126) (.readUnsignedShort in)
               :else        (.readLong in))
        ba  (byte-array len)]
    (.readFully in ba)
    (String. ba "UTF-8")))

(specification "real RFC-6455 client attaches to GET /ws and receives ordered push frames (bb)"
  (let [hub  (ws/new-hub)
        ctx  {:escapement/ws-push hub :escapement/ws-handlers {}}
        stop (http/run-server (server/make-handler ctx) {:port 0})
        port (:local-port (meta stop))]
    (try
      (let [sock (Socket. "127.0.0.1" (int port))
            out  (.getOutputStream sock)
            in   (DataInputStream. (.getInputStream sock))
            key  (.encodeToString (Base64/getEncoder) (.getBytes "0123456789abcdef"))
            req  (str "GET /ws HTTP/1.1\r\n"
                   "Host: 127.0.0.1:" port "\r\n"
                   "Upgrade: websocket\r\n"
                   "Connection: Upgrade\r\n"
                   "Sec-WebSocket-Key: " key "\r\n"
                   "Sec-WebSocket-Version: 13\r\n\r\n")]
        (.write out (.getBytes req "UTF-8"))
        (.flush out)
        ;; consume the HTTP/1.1 101 response headers (terminated by a blank line)
        (let [sb (StringBuilder.)]
          (loop []
            (let [c (.read in)]
              (when (>= c 0)
                (.append sb (char c))
                (when-not (clojure.string/includes? (.toString sb) "\r\n\r\n")
                  (recur)))))
          (assertions
            "server completed the WS upgrade (101 Switching Protocols)"
            (clojure.string/includes? (.toString sb) "101") => true
            "Sec-WebSocket-Accept matches the RFC-6455 key derivation"
            (clojure.string/includes? (.toString sb) (ws-accept key)) => true))
        ;; publish two events; the attached client must receive them in order.
        ;; (attach catch-up sends nothing here: no phase + empty recent ring,
        ;;  since publish happens AFTER attach completed during handshake.)
        (ws/publish! hub {:event :llm/start :data {:invokeid "planner"}})
        (ws/publish! hub (delta-ev "planner" "yo"))
        (.setSoTimeout sock 2000)
        (let [f1 (json/parse-string (read-text-frame in) true)
              f2 (json/parse-string (read-text-frame in) true)]
          (assertions
            "the two pushed events arrived as ordered wire envelopes over the socket"
            (mapv :event [f1 f2]) => ["llm/start" "llm/delta"]
            (mapv :seq [f1 f2]) => [1 2]
            "the delta's text round-tripped over the real socket"
            (get-in f2 [:data :text]) => "yo"))
        (.close sock))
      (finally (stop)))))
