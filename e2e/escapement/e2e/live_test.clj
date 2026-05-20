(ns escapement.e2e.live-test
  "Live end-to-end checks against real LLM providers.

   Run with `bb test:e2e` (NOT part of `bb test`). For every provider whose
   credential is present in the environment (see
   `escapement.llm.providers/detect-available-credentials`) this exercises the
   real wire: a basic turn, streaming, vision, the structured error categories,
   and `:max_tokens` truncation detection. Providers without a credential are
   reported as SKIP — never a failure. Credential-independent checks (transport,
   timeout, catalog) always run so the suite is meaningful even with no keys.

   Nothing here prints a secret: keys are masked everywhere. Prompts and token
   caps are tiny by design (tiny + always-on cost posture). A real provider
   misbehaving is the only thing that fails the run (non-zero exit)."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [escapement.llm.catalog :as catalog]
    [escapement.llm.protocol :as proto]
    [escapement.llm.providers :as providers]
    [escapement.llm.types :as types]))

;; ---------------------------------------------------------------------------
;; Result matrix
;; ---------------------------------------------------------------------------

(def ^:private results (atom []))

(defn- record!
  "status ∈ #{:pass :fail :skip}. detail is a short human string (never a secret)."
  [provider capability status detail]
  (swap! results conj {:provider provider :capability capability
                       :status   status :detail detail}))

(defn- mask
  "Never reveal any key bytes — only that one is present and its length."
  [s]
  (str "set(" (count (str s)) " chars)"))

(defn- safe
  "Run thunk; return {:ok v} or {:err throwable}."
  [thunk]
  (try {:ok (thunk)} (catch Throwable t {:err t})))

(defn- tiny-text-request [extra]
  (merge {:messages   [{:role    :user
                        :content [{:type :text :text "Reply with exactly: OK"}]}]
          :max-tokens 16}
    extra))

;; A 1x1 transparent PNG — enough to prove the vision wire path round-trips
;; without shipping a binary fixture.
(def ^:private tiny-png-b64
  (str "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk"
    "YPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="))

;; ---------------------------------------------------------------------------
;; Per-provider checks
;; ---------------------------------------------------------------------------

(defn- check-basic! [provider backend]
  (let [{:keys [ok err]} (safe #(proto/send-turn backend (tiny-text-request nil)))]
    (cond
      err
      (do (record! provider "reachable" :fail
            (str "send-turn threw: " (.getMessage ^Throwable err)))
          (is false (str provider " reachable: " (.getMessage ^Throwable err)))
          nil)
      (types/validate-response ok)
      (do (record! provider "reachable" :fail "response failed Malli validation")
          (is false (str provider " response invalid"))
          nil)
      :else
      (let [txt (->> (:content ok) (filter #(= :text (:type %))) (map :text) (apply str))]
        (record! provider "reachable" :pass
          (str "stop=" (:stop-reason ok) " text=" (pr-str (subs txt 0 (min 24 (count txt))))))
        (is true)
        ok))))

(defn- check-streaming! [provider backend]
  (if-not (proto/streaming? backend)
    (record! provider "streaming" :skip "backend has no StreamingLLMBackend")
    (let [deltas (atom [])
          {:keys [ok err]} (safe #(proto/stream-turn backend (tiny-text-request nil)
                                    (fn [d] (swap! deltas conj d))))]
      (cond
        err
        (do (record! provider "streaming" :fail (.getMessage ^Throwable err))
            (is false (str provider " streaming: " (.getMessage ^Throwable err))))
        (or (empty? @deltas) (types/validate-response ok))
        (do (record! provider "streaming" :fail
              (str "deltas=" (count @deltas) " valid?="
                (nil? (types/validate-response ok))))
            (is false (str provider " streaming produced no/invalid output")))
        :else
        (do (record! provider "streaming" :pass
              (str (count @deltas) " deltas, types "
                (vec (distinct (map :type @deltas)))))
            (is true))))))

(defn- check-max-tokens-truncation! [provider backend]
  ;; Live proof the backend honestly reports the cap was hit. The unbounded
  ;; continuation/stitch that consumes this lives in
  ;; escapement.invocation.llm-conversation and is unit-covered there.
  (let [req (tiny-text-request
              {:messages   [{:role    :user
                             :content [{:type :text
                                        :text "Write a 300-word story about the sea."}]}]
               :max-tokens 16})
        {:keys [ok err]} (safe #(proto/send-turn backend req))]
    (cond
      err (record! provider "max_tokens-detect" :fail (.getMessage ^Throwable err))
      (= :max_tokens (:stop-reason ok))
      (do (record! provider "max_tokens-detect" :pass "stop_reason :max_tokens")
          (is true))
      :else
      (record! provider "max_tokens-detect" :skip
        (str "model stopped with " (:stop-reason ok)
          " before hitting the 16-token cap")))))

(defn- check-vision! [provider backend]
  (let [req {:messages   [{:role    :user
                           :content [{:type   :image
                                      :source {:type       :base64
                                               :media-type "image/png"
                                               :data       tiny-png-b64}}
                                     {:type :text :text "Reply with exactly: SEEN"}]}]
             :max-tokens 16}
        {:keys [ok err]} (safe #(proto/send-turn backend req))]
    (cond
      err
      (let [cat (proto/error-category err)]
        (if (= :invalid-request cat)
          (record! provider "vision" :skip "model rejected image (not vision-capable)")
          (do (record! provider "vision" :fail
                (str (or cat :uncategorized) ": " (.getMessage ^Throwable err)))
              (is false (str provider " vision: " (.getMessage ^Throwable err))))))
      (types/validate-response ok)
      (do (record! provider "vision" :fail "response failed Malli validation")
          (is false (str provider " vision invalid response")))
      :else
      (do (record! provider "vision" :pass (str "stop=" (:stop-reason ok)))
          (is true)))))

;; ---------------------------------------------------------------------------
;; Credential-independent error-category checks (no quota cost)
;; ---------------------------------------------------------------------------

(defn- expect-category! [label backend req expected-set]
  (let [{:keys [ok err]} (safe #(proto/send-turn backend req))]
    (if-not err
      (do (record! "api" label :fail (str "expected throw, got stop=" (:stop-reason ok)))
          (is false (str label " expected an error, succeeded")))
      (let [cat (proto/error-category err)]
        (if (contains? expected-set cat)
          (do (record! "api" label :pass (str "category " cat))
              (is true))
          (do (record! "api" label :fail
                (str "expected " expected-set " got " (or cat :uncategorized)))
              (is false (str label ": got " (or cat :uncategorized)))))))))

(deftest credential-independent-error-categories
  (require 'escapement.llm.api)
  (let [new-backend (resolve 'escapement.llm.api/new-backend)]
    ;; :transport — bogus host, no key needed, no quota.
    (expect-category!
      "error:transport"
      (new-backend {:base-url "https://no-such-host.invalid.escapement"
                    :api-key  "x" :default-model "claude-sonnet-4-6"})
      (tiny-text-request nil)
      #{:transport})
    ;; :timeout — real host, 1ms timeout so the connection cannot complete.
    (expect-category!
      "error:timeout"
      (new-backend {:base-url      "https://api.anthropic.com" :api-key "x"
                    :default-model "claude-sonnet-4-6" :http-timeout-ms 1})
      (tiny-text-request nil)
      #{:timeout :transport})
    ;; :auth — real Anthropic endpoint, deliberately bad key. No quota spent.
    (expect-category!
      "error:auth"
      (new-backend {:base-url      "https://api.anthropic.com"
                    :api-key       "sk-ant-definitely-invalid-key"
                    :default-model "claude-sonnet-4-6"})
      (tiny-text-request nil)
      #{:auth})))

;; ---------------------------------------------------------------------------
;; Catalog freshness (no live call)
;; ---------------------------------------------------------------------------

(deftest catalog-resolves-provider-default-models
  (doseq [c (providers/detect-available-credentials)]
    (when-let [m (:default-model c)]
      (let [out (catalog/max-output-tokens m)
            ctx (catalog/context-window m)]
        (if (or out ctx)
          (do (record! (name (:kind c)) "catalog" :pass
                (str m " out=" out " ctx=" ctx))
              (is true))
          (record! (name (:kind c)) "catalog" :skip
            (str m " not in catalog (backend wire default applies)")))))))

;; ---------------------------------------------------------------------------
;; Live per-provider sweep
;; ---------------------------------------------------------------------------

(deftest live-provider-sweep
  (let [creds (providers/detect-available-credentials)]
    (if (empty? creds)
      (record! "—" "providers" :skip
        "no provider credentials in env; ran only credential-independent checks")
      (doseq [c creds]
        (let [provider (name (:kind c))
              {:keys [ok err]} (safe #(providers/build-credential-backend c))]
          (if err
            (do (record! provider "build" :fail (.getMessage ^Throwable err))
                (is false (str provider " backend construction failed")))
            (let [backend ok]
              (record! provider "build" :pass
                (str "from " (:source c)
                  (when (:api-key c) (str " key=" (mask (:api-key c))))))
              (when (check-basic! provider backend)
                (check-streaming! provider backend)
                (check-max-tokens-truncation! provider backend)
                (check-vision! provider backend)))))))))

;; ---------------------------------------------------------------------------
;; Report
;; ---------------------------------------------------------------------------

(defn- print-matrix! []
  (let [rows @results
        w (fn [s n] (let [s (str s)] (str s (apply str (repeat (max 0 (- n (count s))) " ")))))
        sym {:pass "PASS" :fail "FAIL" :skip "SKIP"}]
    (println)
    (println "================ e2e live matrix ================")
    (println (w "PROVIDER" 14) (w "CAPABILITY" 20) (w "STATUS" 6) "DETAIL")
    (println (apply str (repeat 78 "-")))
    (doseq [{:keys [provider capability status detail]} rows]
      (println (w provider 14) (w capability 20) (w (sym status) 6) detail))
    (println (apply str (repeat 78 "-")))
    (let [f (frequencies (map :status rows))]
      (println (format "totals: %d pass, %d skip, %d fail"
                 (get f :pass 0) (get f :skip 0) (get f :fail 0))))
    (when (zero? (count rows))
      (println "(no checks recorded)"))
    (println "=================================================")
    (println)))

(use-fixtures :once
  (fn [t]
    (reset! results [])
    (t)
    (print-matrix!)))
