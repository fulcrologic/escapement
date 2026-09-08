(ns escapement.llm.model-quirks-test
  "The per-endpoint request-key quirk seam.

   The scoping is the whole point and is the easy thing to get wrong:
   `kimi-k2.7-code` rejects any temperature other than 1 through opencode.ai's
   Zen gateway but accepts 0.7 through Ollama Cloud — same model id, same wire
   format, different answer. Both verified live 2026-09-07. So quirks hang off
   a BACKEND INSTANCE, never off a model id alone."
  (:require
    [escapement.llm.model-quirks :as quirks]
    [escapement.llm.openai :as openai]
    [escapement.llm.providers :as providers]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(def table
  [{:match #"^kimi-k2\.7" :pin {:temperature 1} :why "only 1 is allowed"}
   {:match "exact-model" :drop #{:top-k}}])

(specification "per-model request-key quirks"

  (component "a pin forces the only value the endpoint accepts"
    (assertions
      "a conflicting temperature is pinned"
      (:temperature (quirks/apply-quirks table {:model "kimi-k2.7-code" :temperature 0.7})) => 1

      "a temperature that already matches is left alone"
      (quirks/apply-quirks table {:model "kimi-k2.7-code" :temperature 1})
      => {:model "kimi-k2.7-code" :temperature 1}

      "a key the caller never set is NOT invented"
      (contains? (quirks/apply-quirks table {:model "kimi-k2.7-code"}) :temperature) => false))

  (component "a drop removes a key the endpoint rejects outright"
    (assertions
      "matched by an exact model string"
      (quirks/apply-quirks table {:model "exact-model" :top-k 40 :temperature 0.5})
      => {:model "exact-model" :temperature 0.5}))

  (component "no matching quirk means no change at all"
    (assertions
      "an unmatched model is returned untouched"
      (quirks/apply-quirks table {:model "some-other-model" :temperature 0.7})
      => {:model "some-other-model" :temperature 0.7}

      "an empty table is a no-op"
      (quirks/apply-quirks [] {:model "kimi-k2.7-code" :temperature 0.7})
      => {:model "kimi-k2.7-code" :temperature 0.7}

      "so is a nil table — a backend that carries none behaves as before"
      (quirks/apply-quirks nil {:model "kimi-k2.7-code" :temperature 0.7})
      => {:model "kimi-k2.7-code" :temperature 0.7}))

  (component "matching is by regex or exact string, never by accident"
    (assertions
      "a regex quirk matches the family"
      (mapv :why (quirks/for-model table "kimi-k2.7-code")) => ["only 1 is allowed"]

      "an exact-string quirk does not match a longer id"
      (quirks/for-model table "exact-model-2") => []))

  (component "the quirk is a property of the ENDPOINT, not the model"
    ;; Verified live: the same model, the same request, opposite outcomes on
    ;; two gateways. A model-keyed table would pin a temperature that Ollama
    ;; was perfectly happy to honour.
    (let [oc (-> (providers/build-injected-credentials-backend
                   [{:provider :opencode-go :api-key "k"}] [])
               :default-backend :default-backend)
          ol (-> (providers/build-injected-credentials-backend
                   [{:provider :ollama :api-key "k"}] [])
               :default-backend)
          req {:model "kimi-k2.7-code" :temperature 0.7}]
      (assertions
        "opencode-go carries the kimi quirk"
        (:temperature (quirks/apply-quirks (-> oc :opts :model-quirks) req)) => 1

        "Ollama carries no quirk for it, so the caller's temperature survives"
        (:temperature (quirks/apply-quirks (-> ol :opts :model-quirks) req)) => 0.7)))

  (component "the backend applies them on the way to the wire"
    (let [b (-> (providers/build-injected-credentials-backend
                  [{:provider :opencode-go :api-key "k"}] [])
              :default-backend :default-backend)
          adjusted (quirks/apply-quirks (-> b :opts :model-quirks)
                     {:model "kimi-k2.7-code" :messages [] :temperature 0.7})]
      (assertions
        "the wire body carries the pinned value, not the caller's"
        (get (openai/request->openai-json adjusted) "temperature") => 1))))
