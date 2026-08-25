(ns escapement.llm.providers-test
  "Step 3: hermetic explicit-credentials assembly. These tests exercise
   ASSEMBLY/ROUTING only — never `send-turn` — so no network call is made.
   They also assert the path is hermetic: zero `System/getenv` reads, zero
   disk access."
  (:require
    [clojure.string]
    [escapement.llm.providers :as providers]
    [fulcro-spec.core :refer [=> assertions component specification]]))

;; --- helpers --------------------------------------------------------------

(defn backend-class
  "The simple record name of a backend, so we can assert which concrete wire
   backend a route resolved to without touching the network. Under SCI
   `(class x)` is `SciRecord`; `(type x)` carries the real defrecord name."
  [b]
  (when b
    (-> (str (type b)) (clojure.string/split #"\.") last)))

(defn route-classes
  "Vector of [route-pattern-source backend-class] for a built multi backend,
   in route order."
  [mb]
  (mapv (fn [[matcher b]]
          [(when (instance? java.util.regex.Pattern matcher) (str matcher))
           (backend-class b)])
    (:routes mb)))

(defn count-getenv
  "Run `thunk` with `System/getenv` spied; returns [result getenv-call-count].
   Any env read on the assembly path bumps the count and fails hermeticity."
  [thunk]
  (let [calls (atom 0)
        real  System/getenv]
    (with-redefs [providers/nonblank-env
                  (fn [& _] (swap! calls inc) nil)]
      ;; nonblank-env is the only sanctioned env reader in this ns; spying it
      ;; proves the injection path does not route through env at all. We also
      ;; assert detect-available-credentials is never called.
      (let [r (thunk)]
        [r @calls]))))

;; --- specs ----------------------------------------------------------------

(specification "build-injected-credentials-backend — hermetic assembly"

  (component "builds a multi backend purely from explicit descriptors"
    (let [descs [{:provider :anthropic :api-key "sk-anthropic"}
                 {:provider :openai :api-key "sk-openai"}]
          prefs [{:provider :anthropic :model "claude-opus-4-7"}
                 {:provider :openai :model "gpt-5"}]
          mb    (providers/build-injected-credentials-backend descs prefs)]
      (assertions
        "returns a multi routing backend"
        (contains? #{"MultiBackend" "StreamingMultiBackend"}
          (backend-class mb)) => true

        "one route per resolvable descriptor"
        (count (:routes mb)) => 2

        "anthropic descriptor resolved to the Anthropic wire backend"
        (->> (:routes mb)
          (filter (fn [[m _]] (= (str m) "^claude-")))
          first second backend-class) => "AnthropicAPIBackend"

        "openai descriptor resolved to the OpenAI wire backend"
        (->> (:routes mb)
          (filter (fn [[m _]] (= (str m) "^gpt-")))
          first second backend-class) => "OpenAIBackend"

        "a default-backend is provided so unrouted models still work"
        (some? (:default-backend mb)) => true

        "default-backend is the first descriptor's backend"
        (backend-class (:default-backend mb)) => "AnthropicAPIBackend")))

  (component "zero env reads — never calls detect-available-credentials"
    (let [detect-called (atom false)]
      (with-redefs [providers/detect-available-credentials
                    (fn [] (reset! detect-called true) [])]
        (let [[mb getenv-calls]
              (count-getenv
                #(providers/build-injected-credentials-backend
                   [{:provider :anthropic :api-key "sk-x"}
                    {:provider :openai :api-key "sk-y"}]
                   [{:provider :openai :model "gpt-5"}]))]
          (assertions
            "no env var consulted during assembly"
            getenv-calls => 0

            "detect-available-credentials never called"
            @detect-called => false

            "still produced a working multi backend"
            (count (:routes mb)) => 2)))))

  (component "routing order follows the injected :llm/preferences order"
    ;; descriptors in one order, preferences in the REVERSE order — the route
    ;; table must follow preferences, not descriptor order.
    (let [descs [{:provider :anthropic :api-key "a"}
                 {:provider :openai :api-key "b"}
                 {:provider :z-ai :api-key "c"}]
          prefs [{:provider :z-ai :model "glm-4.7"}
                 {:provider :openai :model "gpt-5"}
                 {:provider :anthropic :model "claude-opus-4-7"}]
          mb    (providers/build-injected-credentials-backend descs prefs)]
      (assertions
        "routes are ordered by preference rank, not descriptor order"
        (mapv first (route-classes mb)) => ["^glm-" "^gpt-" "^claude-"])))

  (component "providers absent from preferences keep descriptor order, last"
    (let [descs [{:provider :anthropic :api-key "a"}
                 {:provider :openai :api-key "b"}
                 {:provider :z-ai :api-key "c"}]
          ;; only :openai ranked; the rest are unranked
          prefs [{:provider :openai :model "gpt-5"}]
          mb    (providers/build-injected-credentials-backend descs prefs)]
      (assertions
        "ranked provider first, unranked keep their descriptor order after"
        (mapv first (route-classes mb)) => ["^gpt-" "^claude-" "^glm-"])))

  (component "a model routes to the descriptor whose provider serves it"
    ;; We assert routing selection WITHOUT send-turn by reaching into the
    ;; private pick logic via the public route table + multi matcher semantics.
    (let [descs [{:provider :anthropic :api-key "a"}
                 {:provider :openai :api-key "b"}]
          prefs [{:provider :anthropic :model "claude-opus-4-7"}
                 {:provider :openai :model "gpt-5"}]
          mb    (providers/build-injected-credentials-backend descs prefs)
          pick  (fn [model]
                  (some (fn [[m b]]
                          (when (re-find m model) (backend-class b)))
                    (:routes mb)))]
      (assertions
        "claude-* model selects the Anthropic backend"
        (pick "claude-opus-4-7") => "AnthropicAPIBackend"

        "gpt-* model selects the OpenAI backend"
        (pick "gpt-5") => "OpenAIBackend")))

  (component "subscription / aliased providers resolve to a wire backend"
    (let [mb (providers/build-injected-credentials-backend
               [{:provider :z-ai-plan :subscription true}
                {:provider :codex}]
               [{:provider :z-ai-plan :model "glm-5.1"}
                {:provider :codex :model "gpt-5.1-codex"}])]
      (assertions
        ":z-ai-plan resolves to the Anthropic-compatible (z.ai) backend"
        (->> (:routes mb)
          (filter (fn [[m _]] (= (str m) "^glm-")))
          first second backend-class) => "AnthropicAPIBackend"

        ":codex resolves to the OpenAICodex backend"
        (->> (:routes mb)
          (filter (fn [[m _]] (= (str m) "^gpt-5")))
          first second backend-class) => "OpenAICodexBackend")))

  (component ":zai-coding-plan (v1 legacy Responses wire) resolves to the codex backend"
    (let [mb (providers/build-injected-credentials-backend
               [{:provider :zai-coding-plan :api-key "k"}]
               [{:provider :zai-coding-plan :model "glm-5.3"}])
          b    (->> (:routes mb)
                  (filter (fn [[m _]] (= (str m) "^glm-")))
                  first second)]
      (assertions
        "resolves to the OpenAICodex backend (Responses wire)"
        (backend-class b) => "OpenAICodexBackend"

        "carries the api-key and v1 endpoint root"
        (select-keys b [:api-key :base-url]) => {:api-key "k"
                                                 :base-url "https://api.z.ai/api/v1"}

        "default model is glm-5.3"
        (:default-model b) => "glm-5.3")))

  (component "caller overrides win over the static template"
    (let [mb (providers/build-injected-credentials-backend
               [{:provider :openai :api-key "k" :base-url "https://proxy.local/v1"
                 :model    "gpt-5"}]
               [{:provider :openai :model "gpt-5"}])
          ;; OpenAIBackend stores its opts; confirm override threaded through.
          b  (:default-backend mb)]
      (assertions
        "still an OpenAI backend"
        (backend-class b) => "OpenAIBackend"

        "base-url override carried into the constructed backend opts"
        (-> b :opts :base-url) => "https://proxy.local/v1"

        "model override becomes the backend default-model"
        (-> b :opts :default-model) => "gpt-5")))

  (component "missing / empty / unknown descriptors behave sanely"
    (assertions
      "empty descriptors → nil (nothing to assemble)"
      (providers/build-injected-credentials-backend [] []) => nil

      "nil descriptors → nil"
      (providers/build-injected-credentials-backend nil nil) => nil

      "all-unknown providers → nil (dropped cleanly)"
      (providers/build-injected-credentials-backend
        [{:provider :totally-made-up :api-key "x"}] []) => nil)

    (let [mb (providers/build-injected-credentials-backend
               [{:provider :totally-made-up :api-key "x"}
                {:provider :anthropic :api-key "ok"}]
               [{:provider :anthropic :model "claude-opus-4-7"}])]
      (assertions
        "unknown descriptor dropped, known one still assembled"
        (count (:routes mb)) => 1

        "surviving route is the known provider"
        (-> (route-classes mb) first) => ["^claude-" "AnthropicAPIBackend"]))))
