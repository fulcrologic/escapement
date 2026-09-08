(ns escapement.llm-latency-test
  "Covers the TTFT (time-to-first-token) latency cap in `escapement.llm/run-turn`
   (`:resilience {:latency {:first-token-ms … :fallback […]}}`): a backend slow
   to START responding is abandoned and the turn fails over to the next
   candidate, WITHOUT marking the slow model `:down`.

   CRITICAL test-design note: the mock's first-token delay runs inside a
   `future` (mirroring `BabashkaHttpTransport`), NOT inline. If the delay ran
   inline, it would block inside `send-turn*` BEFORE `await-turn!` ever polls,
   so the race would never trigger and the test would pass for the wrong reason."
  (:require
    [com.fulcrologic.statecharts.promise :as p]
    [escapement.llm :as llm]
    [escapement.llm.protocol :as proto]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(defn- end-turn [text]
  {:stop-reason :end_turn
   :content     [{:type :text :text text}]
   :usage       {:input-tokens 1 :output-tokens 1}
   :model       "mock"})

(defrecord SpeedBackend [calls profiles]
  ;; `profiles`: model-string → {:ttft-ms ms :tail-ms ms}. The first token lands
  ;; after :ttft-ms, the final Response after a further :tail-ms — both delays
  ;; off the request thread, in a future, like the real transport.
  proto/LLMBackend
  (send-turn [_ req]
    (swap! calls conj (:model req))
    (p/do! (end-turn (str "done:" (:model req)))))
  proto/StreamingLLMBackend
  (stream-turn [_ req on-delta]
    (swap! calls conj (:model req))
    (let [{:keys [ttft-ms tail-ms]} (get profiles (:model req) {})
          d (promise)]
      (future
        (try
          (when (and ttft-ms (pos? (long ttft-ms))) (Thread/sleep (long ttft-ms)))
          (on-delta {:type :text-delta :text (str "tok:" (:model req))})
          (when (and tail-ms (pos? (long tail-ms))) (Thread/sleep (long tail-ms)))
          (deliver d (end-turn (str "done:" (:model req))))
          (catch Throwable e (deliver d (p/rejected e)))))
      (p/then d identity))))

(defrecord InlineSpeedBackend [calls profiles]
  ;; Mirrors the REAL CLJ/bb backends (openai.clj / api.clj): stream-turn runs
  ;; inline under p/do! and BLOCKS the caller until the response is ready — the
  ;; slow first token is NOT on a side thread. This is the path the async
  ;; SpeedBackend above does NOT exercise; it is the one that broke the cap in
  ;; the real haiku tournament (the turn blocked in send-turn* before the cap
  ;; could fire). The fix: await-turn! invokes the send THUNK on its own future.
  proto/LLMBackend
  (send-turn [_ req]
    (swap! calls conj (:model req))
    (p/do! (end-turn (str "done:" (:model req)))))
  proto/StreamingLLMBackend
  (stream-turn [_ req on-delta]
    (swap! calls conj (:model req))
    (p/do!
      (let [{:keys [ttft-ms tail-ms]} (get profiles (:model req) {})]
        (when (and ttft-ms (pos? (long ttft-ms))) (Thread/sleep (long ttft-ms)))
        (on-delta {:type :text-delta :text (str "tok:" (:model req))})
        (when (and tail-ms (pos? (long tail-ms))) (Thread/sleep (long tail-ms)))
        (end-turn (str "done:" (:model req)))))))

(defrecord MuteStreamBackend [calls profiles]
  ;; A backend that SATISFIES StreamingLLMBackend but never calls `on-delta` for
  ;; this particular turn, then resolves a perfectly good Response. Not a
  ;; hypothetical: verified live 2026-09-08 against the real `claude` CLI
  ;; 2.1.263 — a tool-use turn (3 tool calls, 41 CLI lines, content
  ;; [:text :tool_use :tool_use :tool_use]) emitted ZERO partial-message events,
  ;; because the CLI streams partial text only for plain prose turns. Inline
  ;; under `p/do!` rather than on a side future, mirroring how
  ;; `ClaudeCliBackend/stream-turn` actually blocks in `send-turn*`.
  proto/LLMBackend
  (send-turn [_ req]
    (swap! calls conj (:model req))
    (p/do! (end-turn (str "done:" (:model req)))))
  proto/StreamingLLMBackend
  (stream-turn [_ req _on-delta]
    (swap! calls conj (:model req))
    (p/do!
      (let [{:keys [tail-ms]} (get profiles (:model req) {})]
        (when (and tail-ms (pos? (long tail-ms))) (Thread/sleep (long tail-ms)))
        (end-turn (str "done:" (:model req)))))))

(def ^:private mute-profiles
  {"mute-slow" {:tail-ms 600}   ; healthy, delta-less, but slower than the cap
   "mute-fast" {:tail-ms 0}})   ; healthy, delta-less, finishes under the cap

(def ^:private profiles
  {"slow"     {:ttft-ms 1500}            ; never produces a first token under the cap
   "fast"     {:ttft-ms 0}              ; first token immediately
   "slowtail" {:ttft-ms 0 :tail-ms 600} ; fast first token, slow to FINISH
   "rideout"  {:ttft-ms 400}})          ; over the cap, but used with no fallback

(def ^:private cap-ms 250)

(defn- run!
  "Drive `run-turn` directly with a streaming delta sink (required so a first
   token signal exists) and a shared `model-status` atom the caller can inspect.
   `latency` is the `:latency` resilience submap. Returns `{:env … :calls …
   :status @model-status}`."
  [backend primary-alias latency]
  (let [ms (atom {})
        env (llm/run-turn
              {:backend      backend
               :aliases      {:primary [{:provider :a :model primary-alias}]}
               :preferences  [:primary]
               :model-status ms
               ;; non-nil sink ⇒ run-turn streams ⇒ first-tok gets stamped
               :hooks        {:delta-sink (fn [_ _] (fn [_] nil))}}
              {:model :primary :resilience {:latency latency}}
              [{:role :user :content [{:type :text :text "hi"}]}]
              [])]
    {:env env :calls @(:calls backend) :status @ms}))

(specification "TTFT latency cap"
  (component "breach with a SYNCHRONOUSLY-streaming backend (matches real bb backends)"
    ;; Regression guard: the original await-turn! evaluated send-turn* on the
    ;; CALLING thread, so this inline-blocking slow turn blocked fully BEFORE the
    ;; cap could fire and never failed over — the bug seen in the real haiku run.
    ;; The fix runs send-turn* on a side future; this asserts the cap engages.
    (let [b   (->InlineSpeedBackend (atom []) profiles)
          {:keys [env calls status]}
          (run! b "slow" {:first-token-ms cap-ms
                          :fallback [{:provider :b :model "fast"}]})]
      (assertions
        "the inline-blocking slow turn is abandoned and fails over to the fallback"
        (:status env) => :ok
        (:model env)  => "fast"
        calls => ["slow" "fast"]
        "the slow model is NOT marked :down"
        (get status "slow") => nil)))

  (component "breach → fail over to the fallback provider"
    (let [b   (->SpeedBackend (atom []) profiles)
          {:keys [env calls status]}
          (run! b "slow" {:first-token-ms cap-ms
                          :fallback [{:provider :b :model "fast"}]})]
      (assertions
        "the turn succeeds on the fallback, not the slow primary"
        (:status env) => :ok
        (:model env)  => "fast"
        "both candidates were attempted, slow first then the fallback"
        calls => ["slow" "fast"]
        "the slow model is NOT marked :down — slowness is transient, not a fault"
        (get status "slow") => nil)))

  (component "fast first token but slow to FINISH → ride it out (TTFT, not total)"
    (let [b   (->SpeedBackend (atom []) profiles)
          {:keys [env calls]}
          (run! b "slowtail" {:first-token-ms cap-ms
                              :fallback [{:provider :b :model "fast"}]})]
      (assertions
        "a turn whose first token beats the cap completes even though its TOTAL
         time (600ms) exceeds the cap (250ms) — the cap is on first token only"
        (:status env) => :ok
        (:model env)  => "slowtail"
        "no failover happened"
        calls => ["slowtail"])))

  (component "no fallback left → ride it out rather than fail"
    (let [b   (->SpeedBackend (atom []) profiles)
          {:keys [env calls status]}
          (run! b "rideout" {:first-token-ms cap-ms})]   ; no :fallback
      (assertions
        "the sole candidate is slow but there is nowhere to switch, so the turn
         waits it out and succeeds (a slow answer beats no answer)"
        (:status env) => :ok
        (:model env)  => "rideout"
        calls => ["rideout"]
        "and it is not marked :down"
        (get status "rideout") => nil)))

  (component "a streaming backend that emits NO deltas is judged on total time"
    ;; The cap cannot distinguish "slow to start" from "never signals". A
    ;; backend that advertises `streaming?` but stays silent for a given turn
    ;; therefore has its TOTAL duration measured against a FIRST-TOKEN budget.
    ;; This is the claude-cli tool-use case (see MuteStreamBackend): the turn is
    ;; healthy and will complete, but it is abandoned anyway if a fallback
    ;; candidate exists. Documented as a caveat on ClaudeCliBackend; pinned here
    ;; so the behavior cannot change silently.
    (let [b (->MuteStreamBackend (atom []) mute-profiles)
          {:keys [env calls status]}
          (run! b "mute-slow" {:first-token-ms cap-ms
                               :fallback [{:provider :b :model "mute-fast"}]})]
      (assertions
        "a healthy but delta-less turn IS abandoned at the cap and fails over"
        (:status env) => :ok
        (:model env)  => "mute-fast"
        calls => ["mute-slow" "mute-fast"]
        "and the abandoned model is still not marked :down"
        (get status "mute-slow") => nil))

    (component "…but only when it also outruns the cap"
      (let [b (->MuteStreamBackend (atom []) mute-profiles)
            {:keys [env calls]}
            (run! b "mute-fast" {:first-token-ms cap-ms
                                 :fallback [{:provider :b :model "mute-slow"}]})]
        (assertions
          "a delta-less turn that finishes inside the budget is never abandoned,
           so emitting no deltas is not by itself a failover trigger"
          (:status env) => :ok
          (:model env)  => "mute-fast"
          calls => ["mute-fast"]))))

  (component "feature off (no cap) → no abandonment even for a slow backend"
    (let [b   (->SpeedBackend (atom []) profiles)
          {:keys [env calls]}
          (run! b "slow" {:fallback [{:provider :b :model "fast"}]})] ; :first-token-ms nil
      (assertions
        "without :first-token-ms the cap is inert: the slow primary runs to
         completion and the fallback is never appended/tried"
        (:status env) => :ok
        (:model env)  => "slow"
        calls => ["slow"]))))
