(ns escapement.llm.providers-test
  "Step 3: hermetic explicit-credentials assembly. These tests exercise
   ASSEMBLY/ROUTING only — never `send-turn` — so no network call is made.
   They also assert the path is hermetic: zero `System/getenv` reads, zero
   disk access."
  (:require
    [clojure.string :as str]
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
        (-> (route-classes mb) first) => ["^claude-" "AnthropicAPIBackend"])))

  (component "an unknown provider is dropped LOUDLY"
    ;; The drop is deliberate — tolerating an unknown keyword is
    ;; forward-compatibility for a host passing a superset of descriptors across
    ;; versions. The defect was that it happened in total silence: a
    ;; one-character typo removed a provider from the run and it proceeded on a
    ;; different one, with the intended key unused.
    (let [warned (with-out-str
                   (providers/build-injected-credentials-backend
                     [{:provider :anthropc :api-key "k"}] []))]
      (assertions
        "something was said at all"
        (boolean (seq warned)) => true

        "the unrecognised keyword is named"
        (str/includes? warned ":anthropc") => true

        "and so is the one the caller almost certainly meant"
        (str/includes? warned ":anthropic") => true)))

  (component "a known provider is never warned about"
    (assertions
      "assembly of a valid descriptor is silent"
      (with-out-str
        (providers/build-injected-credentials-backend
          [{:provider :anthropic :api-key "k"}] [])) => "")))

(specification ":deepseek provider template"

  (component "an injected :deepseek descriptor resolves to the OpenAI wire backend"
    (let [mb (providers/build-injected-credentials-backend
               [{:provider :deepseek :api-key "sk-ds"}]
               [{:provider :deepseek :model "deepseek-v4-pro"}])
          b  (:default-backend mb)]
      (assertions
        "DeepSeek speaks OpenAI chat-completions, so it is the OpenAI backend"
        (backend-class b) => "OpenAIBackend"

        "on DeepSeek's own metered endpoint"
        (-> b :opts :base-url) => "https://api.deepseek.com/v1"

        "carrying the injected key"
        (-> b :opts :api-key) => "sk-ds"

        "and the template default model"
        (-> b :opts :default-model) => "deepseek-v4-flash"

        "routed by the deepseek- model prefix"
        (-> (route-classes mb) first first) => "^deepseek-")))

  (component "the env-detected descriptor mirrors the static template"
    ;; The two assembly paths must not drift: same kind, base-url, model, route.
    (let [tmpl (get @(resolve 'escapement.llm.providers/provider-templates) :deepseek)
          desc (with-redefs [providers/nonblank-env
                             (fn [k] (when (= k "DEEPSEEK_API_KEY") "sk-ds"))]
                 (->> (providers/detect-available-credentials)
                   (filter #(= :deepseek (:kind %)))
                   first))]
      (assertions
        "env detection emits a :deepseek descriptor when the key is present"
        (some? desc) => true

        "same wire facts as the template"
        (select-keys desc [:kind :base-url :default-model])
        => (select-keys tmpl [:kind :base-url :default-model])

        "same route"
        (str (:route desc)) => (str (:route tmpl)))))

  (component "DeepSeek's own key outranks a gateway that merely resells the models"
    ;; Ollama's route also matches `deepseek-*`; the vendor endpoint must be
    ;; tried first when both credentials are present.
    (let [descs (with-redefs [providers/nonblank-env
                              (fn [k] (get {"DEEPSEEK_API_KEY" "sk-ds"
                                            "OLLAMA_API_KEY"   "sk-ol"} k))]
                  (providers/detect-available-credentials))
          ;; A saved codex OAuth token may also be detected on the host
          ;; running the suite; only the two under test matter here.
          kinds (filterv #{:deepseek :ollama} (mapv :kind descs))]
      (assertions
        "both providers detected"
        (set kinds) => #{:deepseek :ollama}

        ":deepseek is ordered before :ollama"
        (< (.indexOf kinds :deepseek) (.indexOf kinds :ollama)) => true))))

(specification "opencode.ai Zen requires a session header on every request"
  ;; Verified live 2026-09-07: without `x-opencode-session` the gateway answers
  ;; `MissingSessionID` / HTTP 400 on BOTH its wire formats, so every
  ;; opencode-go request through this library used to fail.

  (component "the header is attached on the OpenAI-shaped route"
    (let [b (-> (providers/build-injected-credentials-backend
                  [{:provider :opencode-go :api-key "k"}] [])
              :default-backend)]
      (assertions
        "a session header is present"
        (some? (get (-> b :opts :extra-headers) "x-opencode-session")) => true)))

  (component "and on the Anthropic-shaped route"
    (let [b (-> (providers/build-injected-credentials-backend
                  [{:provider :opencode-go-anthropic :api-key "k"}] [])
              :default-backend)]
      (assertions
        "a session header is present here too"
        (some? (get (-> b :opts :extra-headers) "x-opencode-session")) => true)))

  (component "each backend instance gets its own id"
    (assertions
      "two instances do not share a session id"
      (= (get (providers/opencode-session-headers) "x-opencode-session")
        (get (providers/opencode-session-headers) "x-opencode-session")) => false)))

(specification "provider default models are live, not stale"
  ;; `kimi-k2.5` was retired upstream on 2026-07-31 while it was still this
  ;; library's Ollama default, so every run taking that default failed. This
  ;; pins the replacement and documents the sweep.

  (component "the Ollama default is the current general-purpose model"
    (let [tmpl @(resolve 'escapement.llm.providers/provider-templates)]
      (assertions
        "not the retired kimi-k2.5"
        (get-in tmpl [:ollama :default-model]) =fn=> #(not= "kimi-k2.5" %)

        "and confirmed answering 2026-09-07"
        (get-in tmpl [:ollama :default-model]) => "glm-5.3-flash"

        "deliberately general-purpose, not a task-specialised -code model"
        (clojure.string/includes? (get-in tmpl [:ollama :default-model]) "-code") => false))))

(specification "the z.ai default names the model that actually runs"
  ;; The endpoint answers a `glm-4.6` request with `glm-5.3-flash` and always
  ;; did; the old default therefore named a model that never ran. This is a
  ;; truthfulness fix, NOT a tier change — same model delivered either way.

  (component "both z.ai templates name the delivered model"
    (let [tmpl @(resolve 'escapement.llm.providers/provider-templates)]
      (assertions
        "metered z.ai"
        (get-in tmpl [:z-ai :default-model]) => "glm-5.3-flash"

        "and its subscription-billed twin, which shares the wire backend"
        (get-in tmpl [:z-ai-plan :default-model]) => "glm-5.3-flash"

        ;; (`:route` holds a Pattern, which is never `=` to another Pattern.)
        "the two still agree on everything else"
        (-> (get tmpl :z-ai) (dissoc :kind) (update :route str))
        => (-> (get tmpl :z-ai-plan) (dissoc :kind) (update :route str))))))

(specification "assistant-prefill continuation support is declared per provider"
  ;; Evidence-only: Ollama answers a trailing assistant message with a hard 400
  ;; (verified 2026-09-07). Providers we could not verify keep today's
  ;; behaviour, because declaring :unsupported REMOVES working behaviour rather
  ;; than withholding an unverified payload.

  (component "the endpoint with evidence of breakage is declared"
    (let [b (-> (providers/build-injected-credentials-backend
                  [{:provider :ollama :api-key "k"}] [])
              :default-backend)]
      (assertions
        "Ollama declares prefill unsupported"
        (-> b :opts :prefill-support) => :unsupported)))

  (component "unverified providers are NOT downgraded on a guess"
    (let [anth (-> (providers/build-injected-credentials-backend
                     [{:provider :anthropic :api-key "k"}] [])
               :default-backend)
          oai  (-> (providers/build-injected-credentials-backend
                     [{:provider :openai :api-key "k"}] [])
                 :default-backend)]
      (assertions
        "Anthropic — documented prefill support, not verifiable here, left alone"
        (-> anth :opts :prefill-support) => nil

        "OpenAI — no evidence either way, left alone"
        (-> oai :opts :prefill-support) => nil))))

(specification "the two assembly paths do not drift"
  ;; `providers.clj` states that the injected path "MIRRORS — fact for fact —
  ;; the descriptor `detect-available-credentials` emits" and that "the
  ;; equivalence is covered by tests". Until this test it was NOT: the only
  ;; parity assertion in the suite covered one provider, while the comment told
  ;; the next maintainer that drift was caught for all of them. A comment
  ;; claiming coverage that does not exist is worse than no comment, so this
  ;; makes the claim true rather than softening it.

  (let [templates @(resolve 'escapement.llm.providers/provider-templates)
        ;; Every env var the detector reads, present — so every env-detectable
        ;; provider emits a descriptor in one pass.
        detected  (with-redefs [providers/nonblank-env (fn [k] (str k "-value"))]
                    (providers/detect-available-credentials))
        by-kind   (group-by :kind detected)
        ;; Facts that MUST agree wherever both paths describe the same provider.
        ;; `:api-key` and `:source` are excluded on purpose: they are the
        ;; env-dependent half, which is exactly what the two paths differ on by
        ;; design.
        shared    [:kind :base-url :auth-mode :reasoning-dialect :http-timeout-ms]
        drift     (for [[pk tmpl] (sort-by key templates)
                        :let [desc (first (get by-kind (:kind tmpl)))]
                        :when desc
                        :let [a (select-keys tmpl shared)
                              b (select-keys desc shared)]
                        :when (or (not= a b)
                                (not= (str (:route tmpl)) (str (:route desc))))]
                    {:provider pk
                     :template (assoc a :route (str (:route tmpl)))
                     :detected (assoc b :route (str (:route desc)))})]

    (component "every provider described by both paths describes it identically"
      (assertions
        "no drift in wire facts or routing"
        (vec drift) => []))

    (component "the test actually reaches the providers it claims to"
      (assertions
        ;; Guard against the failure mode this test exists to prevent: a parity
        ;; test that passes because it compared nothing.
        "several provider kinds were detected and compared"
        (> (count by-kind) 5) => true

        "and each detected kind has a template describing the same provider"
        (vec (remove (set (map :kind (vals templates))) (keys by-kind))) => []))

    (component "a template that drifts WOULD be caught"
      ;; Proves the comparison has teeth rather than trivially passing.
      (let [poisoned (assoc-in templates [:anthropic :base-url] "https://elsewhere.example")
            desc     (first (get by-kind :anthropic))]
        (assertions
          "the poisoned template no longer matches its detected twin"
          (= (select-keys (get poisoned :anthropic) shared)
            (select-keys desc shared)) => false)))))

(specification "a host-supplied :http-timeout-ms reaches the backend"
  ;; It used to be dropped by `descriptor->credential`'s override select-keys
  ;; for EVERY provider, so only a template's own value survived — which is why
  ;; the z.ai entries worked and nothing else did. A caller whose generations
  ;; legitimately run past the 60s default had no way to say so, and a stalled
  ;; turn has no other bound: the runner's no-progress counter does not advance
  ;; while an invocation is live, so the HTTP timeout is the only one there is.

  (component "every openai-shaped provider honours it, asserted one by one"
    ;; NOT one representative. `:deepseek`'s branch already threaded it, so a
    ;; half-fix — the select-keys alone — would look complete when tested
    ;; through DeepSeek while leaving Ollama and OpenRouter at the default.
    (let [timeout-of (fn [provider]
                       (-> (providers/build-injected-credentials-backend
                             [{:provider provider :api-key "k" :http-timeout-ms 300000}] [])
                         :default-backend :opts :http-timeout-ms))]
      (assertions
        ":openai"
        (timeout-of :openai) => 300000

        ":openrouter"
        (timeout-of :openrouter) => 300000

        ":ollama"
        (timeout-of :ollama) => 300000

        ":opencode-go"
        (timeout-of :opencode-go) => 300000

        ":deepseek (already threaded — the one that would mask a half-fix)"
        (timeout-of :deepseek) => 300000)))

  (component "and so do the other backend families"
    (let [record-timeout (fn [provider]
                           (-> (providers/build-injected-credentials-backend
                                 [{:provider provider :api-key "k" :http-timeout-ms 300000}] [])
                             :default-backend :http-timeout-ms))
          opts-timeout   (fn [provider]
                           (-> (providers/build-injected-credentials-backend
                                 [{:provider provider :api-key "k" :http-timeout-ms 300000}] [])
                             :default-backend :opts :http-timeout-ms))]
      (assertions
        ;; The codex backend keeps its config on the record, not under :opts.
        ":zai-coding-plan (Responses wire)"
        (record-timeout :zai-coding-plan) => 300000

        ":codex — was dropped too; its branch passed only :default-model"
        (record-timeout :codex) => 300000

        ":anthropic (Anthropic-shaped)"
        (opts-timeout :anthropic) => 300000

        ":z-ai"
        (opts-timeout :z-ai) => 300000)))

  (component "a template's own value still applies when the host says nothing"
    (assertions
      "z.ai keeps its 300s default — that endpoint is slow and the template knows it"
      (-> (providers/build-injected-credentials-backend
            [{:provider :z-ai :api-key "k"}] [])
        :default-backend :opts :http-timeout-ms) => 300000

      "and a provider with no template value leaves the backend on its own default"
      (-> (providers/build-injected-credentials-backend
            [{:provider :openai :api-key "k"}] [])
        :default-backend :opts :http-timeout-ms) => nil)))
