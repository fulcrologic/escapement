(ns escapement.llm.aliases-test
  "R1–R7 coverage for `:llm/aliases` (keyword `:model` → ordered cross-provider
   target list). Schema accept/reject (R1) lives alongside the sibling overlay
   tests in `escapement.config-test`; a thin cross-check is repeated here so this
   namespace stands on its own. R2–R7 (resolution, provider dispatch, ordered
   failover, per-target param merge + node override, backward-compat, unknown
   alias) drive the resolver and `try-models!` directly with recording/throwing
   mock backends — no network, fully deterministic (no worker threads)."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final script state transition]]
    [escapement.chart.helpers :as h]
    [escapement.config :as config]
    [escapement.engine.testing :as dct]
    [escapement.invocation.llm-conversation :as llmc]
    [escapement.llm.protocol :as llm]
    [escapement.tools.protocol :as tp]
    [fulcro-spec.core :refer [=> assertions component specification]]
    [com.fulcrologic.statecharts.promise :as p]))

;; ---------------------------------------------------------------------------
;; Mock backends
;; ---------------------------------------------------------------------------

(defn- end-turn-response [text]
  {:stop-reason :end_turn
   :content     [{:type :text :text (or text "ok")}]
   :usage       {:input-tokens 1 :output-tokens 1}
   :model       "mock"})

(defrecord RecordingBackend [call-log responder]
  llm/LLMBackend
  (send-turn [_ request]
    (p/do!
      (swap! call-log conj request)
      (responder request))))

(defn- recording-backend
  "A backend that records every request and, by default, returns an end_turn.
   `responder` (fn of request → response, or throws) lets a test fail the first
   target so failover advances. Defaults to always succeeding."
  ([] (recording-backend (fn [_] (end-turn-response "ok"))))
  ([responder] (->RecordingBackend (atom []) responder)))

(defn- base-ctx
  "A `try-models!` ctx with no auto-fallback default-models — an alias supplies
   its own ordered candidate list, so defaults must stay empty to isolate the
   alias path."
  [backend aliases]
  {:backend         backend
   :transcript-fn   (fn [_] nil)
   :worker-state    (atom :running)
   :model-status    (atom {})
   :default-models  []
   :catalog-ratings {}
   :parent-ctx      {:invokeid "iv"}
   :aliases         aliases})

(defn- run-turn!
  "Drive `try-models!` once for the given node params + aliases against backend."
  [backend aliases params]
  (#'llmc/try-models! (base-ctx backend aliases)
    params
    [{:role :user :content [{:type :text :text "hi"}]}]
    []))

;; ===========================================================================
;; R1 — schema accept/reject (cross-check; full coverage in config-test)
;; ===========================================================================

(specification "R1: :llm/aliases schema accepts well-formed and rejects malformed"
  (let [tmp  (fn [] (str (java.nio.file.Files/createTempDirectory
                           "alias-schema" (make-array java.nio.file.attribute.FileAttribute 0))))
        load (fn [v]
               (let [root (tmp)]
                 (spit (java.io.File. root ".escapement.edn") (pr-str {:llm/aliases v}))
                 (try (:config (config/load-project-config root))
                      (catch clojure.lang.ExceptionInfo _ :err))))]
    (assertions
      "valid multi-target vector (incl. params + thinking) loads verbatim"
      (load {:kimi2.6 [{:provider :baseten :model "moonshotai/Kimi-K2.6"
                        :temperature 0.6 :thinking {:type :enabled :budget-tokens 4096}}
                       {:provider :fireworks-ai :model "accounts/fireworks/models/kimi-k2p6"}]})
      => {:llm/aliases {:kimi2.6 [{:provider :baseten :model "moonshotai/Kimi-K2.6"
                                   :temperature 0.6 :thinking {:type :enabled :budget-tokens 4096}}
                                  {:provider :fireworks-ai :model "accounts/fireworks/models/kimi-k2p6"}]}}
      "single-element vector (degenerate single-target) is valid"
      (:llm/aliases (load {:fast [{:provider :ollama :model "glm-5.1"}]}))
      => {:fast [{:provider :ollama :model "glm-5.1"}]}
      "non-vector value rejected"
      (load {:x {:provider :ollama :model "m"}}) => :err
      "empty vector rejected"
      (load {:x []}) => :err
      "target missing :provider rejected"
      (load {:x [{:model "m"}]}) => :err
      "target missing :model rejected"
      (load {:x [{:provider :ollama}]}) => :err)))

;; ===========================================================================
;; R2 + R3 — resolution to ordered candidates + provider-aware dispatch
;; ===========================================================================

(specification "resolve-candidates expands a keyword :model into ordered candidate maps (R2)"
  (let [aliases {:kimi2.6 [{:provider :baseten :model "moonshotai/Kimi-K2.6" :temperature 0.6}
                           {:provider :fireworks-ai :model "accounts/fireworks/models/kimi-k2p6"}]}]
    (assertions
      "keyword :model → ordered candidates, each carrying its own provider/model/params"
      ;; New arity (task 002): [params model-status preferences aliases].
      ;; task 003: each candidate now carries its source :alias keyword.
      (:candidates (#'llmc/resolve-candidates {:model :kimi2.6} (atom {}) [] aliases))
      => [{:provider :baseten :model "moonshotai/Kimi-K2.6" :alias :kimi2.6 :params {:temperature 0.6}}
          {:provider :fireworks-ai :model "accounts/fireworks/models/kimi-k2p6" :alias :kimi2.6 :params {}}]
      "author order is preserved (no rating-based reordering)"
      (mapv :provider (:candidates (#'llmc/resolve-candidates {:model :kimi2.6} (atom {}) [] aliases)))
      => [:baseten :fireworks-ai])))

(specification "R2/R3: a keyword :model dispatches to the FIRST target's provider + provider-specific model id"
  (let [aliases {:kimi2.6 [{:provider :baseten :model "moonshotai/Kimi-K2.6"}
                           {:provider :fireworks-ai :model "accounts/fireworks/models/kimi-k2p6"}]}
        backend (recording-backend)
        result  (run-turn! backend aliases {:model :kimi2.6})
        req     (first @(:call-log backend))]
    (assertions
      "turn succeeded against the first target"
      (some? (:ok result)) => true
      (:model-used result) => "moonshotai/Kimi-K2.6"
      "request carries the FIRST target's provider keyword (R3: routing by provider, not regex)"
      (:provider req) => :baseten
      "request carries the FIRST target's provider-specific model id"
      (:model req) => "moonshotai/Kimi-K2.6"
      "exactly one backend call (no failover needed)"
      (count @(:call-log backend)) => 1
      "the keyword alias never leaked to the wire as a model"
      (keyword? (:model req)) => false)))

;; ===========================================================================
;; R5 — ordered failover (provider + model + params switch); exhaustion error
;; ===========================================================================

(specification "R5: first target's backend error fails over to the next target (provider+model+params switch)"
  (let [aliases  {:kimi2.6 [{:provider :baseten :model "moonshotai/Kimi-K2.6" :temperature 0.2}
                            {:provider :fireworks-ai :model "accounts/fireworks/models/kimi-k2p6" :temperature 0.9}]}
        ;; First target's model throws; second succeeds.
        backend  (recording-backend
                   (fn [req]
                     (if (= "moonshotai/Kimi-K2.6" (:model req))
                       (throw (llm/llm-error :auth "401 from baseten" {}))
                       (end-turn-response "ok"))))
        result   (run-turn! backend aliases {:model :kimi2.6})
        log      @(:call-log backend)
        [req1 req2] log]
    (assertions
      "two attempts were issued (first failed, second tried)"
      (count log) => 2
      "first attempt used the first target's provider+model"
      (:provider req1) => :baseten
      (:model req1) => "moonshotai/Kimi-K2.6"
      (:temperature req1) => 0.2
      "second attempt switched provider AND model AND params"
      (:provider req2) => :fireworks-ai
      (:model req2) => "accounts/fireworks/models/kimi-k2p6"
      (:temperature req2) => 0.9
      "the conversation recovered on the second target"
      (some? (:ok result)) => true
      (:model-used result) => "accounts/fireworks/models/kimi-k2p6")))

(specification "R5: exhausting every target yields the categorized :exhausted error"
  (let [aliases {:kimi2.6 [{:provider :baseten :model "moonshotai/Kimi-K2.6"}
                           {:provider :fireworks-ai :model "accounts/fireworks/models/kimi-k2p6"}]}
        backend (recording-backend (fn [_] (throw (llm/llm-error :auth "401 everywhere" {}))))
        result  (run-turn! backend aliases {:model :kimi2.6})]
    (assertions
      "no :ok — every target failed"
      (:ok result) => nil
      "an :exhausted shape is returned for the caller to fail the node"
      (boolean (:exhausted result)) => true
      "attempts recorded both targets' models (oldest first)"
      (mapv :model (:exhausted result))
      => ["moonshotai/Kimi-K2.6" "accounts/fireworks/models/kimi-k2p6"]
      "both targets were actually attempted on the wire"
      (mapv :model @(:call-log backend))
      => ["moonshotai/Kimi-K2.6" "accounts/fireworks/models/kimi-k2p6"])))

;; ===========================================================================
;; R4 — per-target param merge with node-override precedence
;; ===========================================================================

(specification "R4: per-target params appear on the issued request"
  (let [aliases {:tuned [{:provider :baseten :model "m1"
                          :temperature 0.3 :top-p 0.8 :top-k 40
                          :thinking {:type :enabled :budget-tokens 2048}}]}
        backend (recording-backend)
        _       (run-turn! backend aliases {:model :tuned})
        req     (first @(:call-log backend))]
    (assertions
      "target :temperature reached the request"
      (:temperature req) => 0.3
      "target :top-p reached the request"
      (:top-p req) => 0.8
      "target :top-k reached the request"
      (:top-k req) => 40
      "target :thinking reached the request"
      (:thinking req) => {:type :enabled :budget-tokens 2048})))

(specification "R4: an explicit node param overrides the alias-target value; a param-less target inherits node defaults"
  (let [aliases {:tuned [{:provider :baseten :model "m1" :temperature 0.3 :top-p 0.8}]
                 :bare  [{:provider :baseten :model "m1"}]}]
    (component "node value wins over target value (alias = defaults)"
      (let [backend (recording-backend)
            _       (run-turn! backend aliases {:model :tuned :temperature 0.95})
            req     (first @(:call-log backend))]
        (assertions
          "node :temperature 0.95 overrides target's 0.3"
          (:temperature req) => 0.95
          "target :top-p (no node override) is still applied"
          (:top-p req) => 0.8)))
    (component "a target with no params inherits the node-level params"
      (let [backend (recording-backend)
            _       (run-turn! backend aliases {:model :bare :temperature 0.42 :top-k 7})
            req     (first @(:call-log backend))]
        (assertions
          "node :temperature flows through unchanged"
          (:temperature req) => 0.42
          "node :top-k flows through unchanged"
          (:top-k req) => 7)))))

;; ===========================================================================
;; R4 — string model references are REJECTED (mandatory-aliases reverses old R6)
;; ===========================================================================

(specification "R4: a string :model is a categorized error, never shipped to a backend"
  (let [aliases {:kimi2.6 [{:provider :baseten :model "moonshotai/Kimi-K2.6"}]}
        backend (recording-backend)
        result  (run-turn! backend aliases {:model "claude-opus-4-7"})]
    (assertions
      "try-models! surfaces a categorized :string-model shape (not :ok, not :exhausted)"
      (:string-model result) => "claude-opus-4-7"
      (:ok result) => nil
      (:exhausted result) => nil
      "NO request ever reached the backend (string never shipped as a model)"
      (count @(:call-log backend)) => 0)))

(specification "R4: a string element in :models is a categorized error, never shipped"
  (let [aliases {:kimi2.6 [{:provider :baseten :model "moonshotai/Kimi-K2.6"}]}
        backend (recording-backend)
        result  (run-turn! backend aliases {:models ["gpt-4o-mini" "claude-opus-4-1"]})]
    (assertions
      "try-models! surfaces a categorized :string-models shape"
      (vector? (:string-models result)) => true
      "the offending non-keyword elements are surfaced"
      (:bad result) => ["gpt-4o-mini" "claude-opus-4-1"]
      "no backend call"
      (count @(:call-log backend)) => 0)))

(specification "R4: resolve-candidates categorizes string forms without delegating to a backend"
  (let [status (atom {})]
    (assertions
      "string :model → {:string-model …}, no :candidates"
      (#'llmc/resolve-candidates {:model "claude-opus-4-7"} status [] {:some [{:provider :a :model "m"}]})
      => {:string-model "claude-opus-4-7"}
      "string element in :models → {:string-models … :bad …}"
      (:bad (#'llmc/resolve-candidates {:models [:ok-kw "str"]} status [] {:ok-kw [{:provider :a :model "m"}]}))
      => ["str"])))

(specification "absent model with no resolvable aliases — backend default pick (nil model), no :provider"
  ;; With empty aliases and no preferences, the resolver yields a single
  ;; nil-model backend-default candidate so the backend picks its own default.
  (let [backend (recording-backend)
        result  (run-turn! backend {} {})
        req     (first @(:call-log backend))]
    (assertions
      "model-used is nil (backend chooses its own default)"
      (:model-used result) => nil
      "build-request omits :model entirely when nil"
      (contains? req :model) => false)))

;; ===========================================================================
;; R7 — unknown alias → categorized error, keyword never reaches the backend
;; ===========================================================================

(specification "R7: unknown alias keyword → categorized error, no request issued"
  (let [aliases {:kimi2.6 [{:provider :baseten :model "moonshotai/Kimi-K2.6"}]}
        backend (recording-backend)
        result  (run-turn! backend aliases {:model :no-such-alias})]
    (assertions
      "try-models! returns a categorized :unknown-alias shape (not :exhausted, not :ok)"
      (:unknown-alias result) => :no-such-alias
      (:ok result) => nil
      (:exhausted result) => nil
      "the known alias names are surfaced for diagnostics"
      (:known result) => [:kimi2.6]
      "NO request ever reached the backend (keyword never shipped as a model)"
      (count @(:call-log backend)) => 0)))

(specification "R7: empty aliases map + keyword :model is an unknown-alias error"
  (let [backend (recording-backend)
        result  (run-turn! backend {} {:model :anything})]
    (assertions
      "unknown alias surfaced even with no aliases configured"
      (:unknown-alias result) => :anything
      "known list is empty"
      (:known result) => []
      "no backend call"
      (count @(:call-log backend)) => 0)))

(specification "R7 end-to-end: unknown alias fails the node with :error.llm.invalid-request (categorized)"
  ;; Through the processor: an unknown-alias outcome becomes a categorized
  ;; :invalid-request error + an :llm/error transcript with :detail :unknown-alias.
  (let [captured (atom [])
        ;; backend would throw if ever called with the keyword — proves no leak.
        backend  (reify llm/LLMBackend
                   (send-turn [_ req]
                     (p/do! (throw (ex-info "backend should not be called for unknown alias"
                                     {:got (:model req)})))))
        proc     (llmc/new-processor {:backend       backend
                                      :tool-registry (tp/new-registry)
                                      :aliases       {:known-alias [{:provider :ollama :model "glm-5.1"}]}
                                      :transcript-fn (fn [ev] (swap! captured conj ev))})
        seen-err (atom nil)
        chart    (chart/statechart
                   {:initial :wrap}
                   (state {:id :wrap :initial :work}
                     (state {:id :work}
                       (h/llm-conversation
                         {:id      "p"
                          :model   :totally-unknown
                          :message "go"})
                       (transition {:event :error.llm.invalid-request :target :failed}
                         (script {:expr (fn [_ d] (reset! seen-err (:_event d)) nil)})))
                     (final {:id :failed})))
        t        (-> (dct/new-testing-env {:statechart chart} proc)
                   (dct/start!))
        deadline (+ (System/currentTimeMillis) 3000)]
    (loop []
      (dct/drain! t)
      (when (and (not (dct/in? t :failed)) (< (System/currentTimeMillis) deadline))
        (Thread/sleep 25) (recur)))
    (assertions
      "chart reached :failed via the categorized :error.llm.invalid-request event"
      (dct/in? t :failed) => true
      "error data carries :detail :unknown-alias and the offending alias"
      (get-in @seen-err [:data :detail]) => :unknown-alias
      (get-in @seen-err [:data :alias]) => :totally-unknown
      "known aliases are surfaced for the author"
      (get-in @seen-err [:data :known]) => [:known-alias]
      "an :llm/error transcript event was emitted with :detail :unknown-alias"
      (->> @captured
        (filter #(= :llm/error (:event %)))
        (some #(= :unknown-alias (get-in % [:data :detail]))))
      => true)))
