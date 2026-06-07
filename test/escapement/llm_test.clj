(ns escapement.llm-test
  "Covers the public `escapement.llm` facade: `response-text`, the one-shot
   `ask`/`ask*`, `elect-model`, and the bounded-concurrency `map-prompt`. The
   shared resolution + failover engine (`resolve-candidates`/`run-turn`) is
   exercised end-to-end here AND directly in `escapement.llm.aliases-test`
   (via the `#'llm-conversation` re-exports), so this namespace focuses on the
   envelope contract and the script-facing sugar. Deterministic: a recording
   mock backend, no network, no worker threads."
  (:require
    [com.fulcrologic.statecharts.promise :as p]
    [escapement.llm :as llm]
    [escapement.llm.protocol :as proto]
    [fulcro-spec.core :refer [=> assertions component specification]]))

;; ---------------------------------------------------------------------------
;; Mock backends
;; ---------------------------------------------------------------------------

(defn- end-turn
  "An :end_turn Response whose single text block is `text`."
  [text]
  {:stop-reason :end_turn
   :content     [{:type :text :text text}]
   :usage       {:input-tokens 1 :output-tokens 1}
   :model       "mock"})

(defn- truncated
  "A `:max_tokens` Response — the API forcibly cut the model off at the cap."
  [text]
  {:stop-reason :max_tokens
   :content     [{:type :text :text text}]
   :usage       {:input-tokens 1 :output-tokens 99}
   :model       "mock"})

(defrecord RecordingBackend [log responder]
  proto/LLMBackend
  (send-turn [_ request]
    (p/do!
      (swap! log conj request)
      (responder request))))

(defn- backend
  "A recording backend. `responder` (req → Response, or throws) defaults to
   echoing the trailing user text as `echo:<text>`."
  ([] (backend
        (fn [req]
          (let [txt (->> (:messages req) last :content
                      (filter #(= :text (:type %))) (map :text) (apply str))]
            (end-turn (str "echo:" txt))))))
  ([responder] (->RecordingBackend (atom []) responder)))

(def ^:private aliases
  {:fast    [{:provider :ollama :model "glm-5.1"}]
   :kimi2.6 [{:provider :baseten :model "moonshotai/Kimi-K2.6" :temperature 0.6}
             {:provider :fireworks-ai :model "accounts/fireworks/models/kimi-k2p6"}]})

(defn- ctx [b] {:backend b :aliases aliases :preferences [:fast]})

;; ===========================================================================
;; response-text
;; ===========================================================================

(specification "response-text"
  (assertions
    "concatenates the text of every :text block in order"
    (llm/response-text {:content [{:type :text :text "Hel"} {:type :text :text "lo"}]})
    => "Hello"
    "ignores non-text blocks (tool_use, thinking)"
    (llm/response-text {:content [{:type :text :text "a"}
                                  {:type :tool_use :id "i" :name "n" :input {}}
                                  {:type :thinking :text "ignored"}
                                  {:type :text :text "b"}]})
    => "ab"
    "is the empty string when there is no text content"
    (llm/response-text {:content [{:type :tool_use :id "i" :name "n" :input {}}]}) => ""
    "is the empty string for a nil/absent :content"
    (llm/response-text {}) => ""))

;; ===========================================================================
;; ask / ask*
;; ===========================================================================

(specification "ask / ask*"
  (component "success"
    (let [b (backend (fn [_] (end-turn "the answer")))]
      (assertions
        "ask returns the extracted assistant text under :response with :status :ok"
        (llm/ask (ctx b) {:prompt "q" :model :fast})
        => {:status :ok :response "the answer" :model "glm-5.1"
            :usage  {:input-tokens 1 :output-tokens 1}}
        "ask* returns the FULL Response under :response (not just the text)"
        (dissoc (:response (llm/ask* (ctx b) {:prompt "q" :model :fast})) :elapsed-ms)
        => (end-turn "the answer")
        "ask* surfaces per-call latency as :elapsed-ms on the Response"
        (number? (:elapsed-ms (:response (llm/ask* (ctx b) {:prompt "q" :model :fast})))) => true)))

  (component "alias resolution + cross-provider failover"
    (let [b (backend (fn [req]
                       (if (= "moonshotai/Kimi-K2.6" (:model req))
                         (throw (proto/llm-error :auth "401 baseten" {}))
                         (end-turn "recovered"))))
          r (llm/ask (ctx b) {:prompt "q" :model :kimi2.6})]
      (assertions
        "fails the first target and recovers on the second"
        (:status r) => :ok
        (:response r) => "recovered"
        "the winning model is reported"
        (:model r) => "accounts/fireworks/models/kimi-k2p6"
        "both targets were attempted on the wire, in order"
        (mapv :model @(:log b))
        => ["moonshotai/Kimi-K2.6" "accounts/fireworks/models/kimi-k2p6"])))

  (component "failures are envelopes, never thrown"
    (let [throwing (backend (fn [_] (throw (proto/llm-error :auth "401 everywhere" {}))))]
      (assertions
        "a backend that always fails yields :status :exhausted (no throw)"
        (:status (llm/ask (ctx throwing) {:prompt "q" :model :fast})) => :exhausted
        "the categorized error is carried under :error"
        (get-in (llm/ask (ctx throwing) {:prompt "q" :model :fast}) [:error :category]) => :auth
        "an unknown alias is :status :unknown-alias and never reaches the wire"
        (:status (llm/ask (ctx (backend)) {:prompt "q" :model :nope})) => :unknown-alias
        "a STRING model is rejected as :status :string-model (keyword aliases only)"
        (:status (llm/ask (ctx (backend)) {:prompt "q" :model "glm-5.1"})) => :string-model)))

  (component "env-aware arity"
    (let [b   (backend (fn [_] (end-turn "via env")))
          env {:escapement/llm-backend     b
               :escapement/llm-aliases     aliases
               :escapement/llm-preferences [:fast]}]
      (assertions
        "an env (carrying :escapement/llm-* keys) resolves via its preferences"
        (llm/ask env {:prompt "hi"}) => {:status :ok :response "via env" :model "glm-5.1"
                                         :usage  {:input-tokens 1 :output-tokens 1}}))))

;; ===========================================================================
;; elect-model
;; ===========================================================================

(specification "elect-model"
  (component "success → reusable pinned ctx"
    (let [b  (backend (fn [_] (end-turn "ok")))
          el (llm/elect-model (ctx b) {:model :kimi2.6 :probe-prompt "ping"})]
      (assertions
        "returns :status :ok with the winning model"
        (:status el) => :ok
        (:model el) => "moonshotai/Kimi-K2.6"
        "the :response is the ctx augmented with :elected + the winning :pinned candidate"
        (:elected (:response el)) => true
        (get-in el [:response :pinned :model]) => "moonshotai/Kimi-K2.6"
        "exactly one probe call was issued"
        (count @(:log b)) => 1)))

  (component "failure"
    (let [throwing (backend (fn [_] (throw (proto/llm-error :auth "401" {}))))]
      (assertions
        "a model that never works yields a failure envelope, not a pinned ctx"
        (:status (llm/elect-model (ctx throwing) {:model :fast})) => :exhausted
        (:response (llm/elect-model (ctx throwing) {:model :fast})) => nil))))

;; ===========================================================================
;; map-prompt
;; ===========================================================================

(specification "map-prompt"
  (component "happy fan-out preserves input order"
    (let [b (backend)
          r (llm/map-prompt (ctx b) {:model :fast :concurrency 4} identity ["a" "b" "c" "d" "e"])]
      (assertions
        "one :ok envelope per item"
        (mapv :status r) => [:ok :ok :ok :ok :ok]
        "results echo the prompts in input order"
        (mapv :response r) => ["echo:a" "echo:b" "echo:c" "echo:d" "echo:e"])))

  (component "an empty collection yields an empty vector (no election call)"
    (let [b (backend)]
      (assertions
        "result is empty"
        (llm/map-prompt (ctx b) {:model :fast} identity []) => []
        "no backend call was made"
        (count @(:log b)) => 0)))

  (component ":on-error :collect isolates a per-item failure"
    ;; A proven model, but one specific document makes the backend throw a
    ;; TERMINAL error — that item fails while its neighbours succeed.
    (let [b (backend (fn [req]
                       (let [txt (->> (:messages req) last :content
                                   (filter #(= :text (:type %))) (map :text) (apply str))]
                         (if (re-find #"BAD" txt)
                           (throw (proto/llm-error :auth "401 bad doc" {}))
                           (end-turn (str "echo:" txt))))))
          r (llm/map-prompt (ctx b) {:model :fast :concurrency 4} identity ["x" "BAD" "z"])]
      (assertions
        "the failing item is its own :error envelope; the others succeed"
        (mapv :status r) => [:ok :exhausted :ok]
        "successful responses are present; the failed one is nil"
        (mapv :response r) => ["echo:x" nil "echo:z"])))

  (component "a pre-pinned ctx skips election entirely"
    (let [b      (backend)
          pinned (:response (llm/elect-model (ctx b) {:model :fast}))
          _      (reset! (:log b) [])
          r      (llm/map-prompt pinned {:concurrency 2} identity ["p" "q"])]
      (assertions
        "every item runs on the pinned model"
        (mapv :response r) => ["echo:p" "echo:q"]
        "exactly one call per item — no extra election probe"
        (count @(:log b)) => 2)))

  (component "bounded concurrency caps in-flight calls"
    (let [in-flight (atom 0)
          peak      (atom 0)
          b (backend (fn [req]
                       (let [n (swap! in-flight inc)]
                         (swap! peak max n)
                         (Thread/sleep 40)
                         (swap! in-flight dec)
                         (end-turn "ok"))))
          r (llm/map-prompt (ctx b) {:model :fast :concurrency 3} identity (vec (range 9)))]
      (assertions
        "all items completed"
        (count r) => 9
        (every? #(= :ok (:status %)) r) => true
        "peak in-flight never exceeded :concurrency"
        (<= @peak 3) => true
        "and real parallelism occurred (more than one call overlapped)"
        (>= @peak 2) => true))))

;; ===========================================================================
;; Overrun primitive: rerun-on-truncation (:resilience :overrun)
;; ===========================================================================

(specification "overrun: rerun a turn truncated at the token cap"
  (component "off by default — a :max_tokens turn is accepted as :ok, no rerun"
    (let [b (backend (fn [_] (truncated "cut off")))
          r (llm/ask* (ctx b) {:prompt "q" :model :fast})]
      (assertions
        "the truncated turn is returned verbatim (legacy behavior preserved)"
        (:status r) => :ok
        (:stop-reason (:response r)) => :max_tokens
        "exactly one call — no rerun without an :overrun policy"
        (count @(:log b)) => 1)))

  (component "recovers when a later attempt finishes within the cap"
    ;; First call truncates, second call (identical context) finishes cleanly —
    ;; the sampling-variance case the rerun is designed for.
    (let [calls (atom 0)
          b     (backend (fn [_]
                           (if (= 1 (swap! calls inc))
                             (truncated "half a hai")
                             (end-turn "a full haiku"))))
          r     (llm/ask (ctx b) {:prompt "q" :model :fast
                                  :resilience {:overrun {:max-retries 2}}})]
      (assertions
        "the second (un-truncated) turn wins"
        (:status r) => :ok
        (:response r) => "a full haiku"
        "exactly two calls — one rerun"
        (count @(:log b)) => 2
        "every attempt saw the IDENTICAL request context"
        (apply = (map :messages @(:log b))) => true)))

  (component "bounded — a deterministic runaway re-truncates up to :max-retries"
    (let [b (backend (fn [_] (truncated "runaway")))
          r (llm/ask* (ctx b) {:prompt "q" :model :fast
                               :resilience {:overrun {:max-retries 3}}})]
      (assertions
        ":on-exhausted defaults to :truncate → the truncated turn is :ok"
        (:status r) => :ok
        (:stop-reason (:response r)) => :max_tokens
        "the original attempt plus exactly :max-retries reruns were issued"
        (count @(:log b)) => 4)))

  (component ":on-exhausted :fail surfaces an :overrun failure envelope"
    (let [b (backend (fn [_] (truncated "runaway")))
          r (llm/ask* (ctx b) {:prompt "q" :model :fast
                               :resilience {:overrun {:max-retries 1
                                                      :on-exhausted :fail}}})]
      (assertions
        "the status is :overrun (not :ok), with no response"
        (:status r) => :overrun
        (:response r) => nil
        "the error is categorized :overrun"
        (get-in r [:error :category]) => :overrun
        "original attempt + 1 rerun"
        (count @(:log b)) => 2)))

  (component ":temperature-bump raises temperature on each rerun (breaks a deterministic loop)"
    ;; A deterministic model re-truncates forever on an identical rerun; the
    ;; bump forces sampling variance. Here all attempts still truncate so we can
    ;; read the full temperature ladder off the wire log.
    (let [b (backend (fn [_] (truncated "runaway")))
          _ (llm/ask* (ctx b) {:prompt "q" :model :fast :temperature 0.0
                               :resilience {:overrun {:max-retries 3
                                                      :temperature-bump 0.3}}})]
      (assertions
        "attempt 0 uses the base temperature; each rerun adds the bump"
        (mapv :temperature @(:log b)) => [0.0 0.3 0.6 0.9])))

  (component ":temperature-max clamps the bumped temperature"
    (let [b (backend (fn [_] (truncated "runaway")))
          _ (llm/ask* (ctx b) {:prompt "q" :model :fast :temperature 0.5
                               :resilience {:overrun {:max-retries 3
                                                      :temperature-bump 0.4
                                                      :temperature-max 1.0}}})]
      (assertions
        "0.5 → 0.9 → clamped at 1.0 → 1.0"
        (mapv :temperature @(:log b)) => [0.5 0.9 1.0 1.0])))

  (component ":max-output-tokens is sent as the request cap when the catalog is silent"
    ;; A model the catalog doesn't know → `effective-max-tokens` is nil, so the
    ;; overrun cap is the only source of :max-tokens on the wire. (An explicit
    ;; cap / a known catalog model still take precedence over this fallback.)
    (let [b       (backend (fn [_] (end-turn "ok")))
          ctx-unk {:backend b
                   :aliases {:local [{:provider :ollama :model "gemma3:1b"}]}
                   :preferences [:local]}
          _       (llm/ask ctx-unk {:prompt "q" :model :local
                                    :resilience {:overrun {:max-output-tokens 256}}})]
      (assertions
        "the wire request carried max-tokens 256 from the overrun policy"
        (:max-tokens (first @(:log b))) => 256))))
