(ns escapement.llm.multi-test
  (:require
    [escapement.llm.multi :as multi]
    [escapement.llm.protocol :as proto]
    [fulcro-spec.core :refer [=> assertions component specification]]
    [com.fulcrologic.statecharts.promise :as p]))

(defrecord StubBackend [tag]
  proto/LLMBackend
  (send-turn [_ request]
    (p/do!
      {:stop-reason      :end_turn
       :content          [{:type :text :text (str "from-" (name tag))}]
       :usage            {:input-tokens 1 :output-tokens 1}
       :model            (:model request)
       :backend-metadata {:tag tag}})))

(defrecord StreamingStubBackend [tag deltas]
  proto/LLMBackend
  (send-turn [_ request]
    (p/do!
      {:stop-reason      :end_turn
       :content          [{:type :text :text (apply str deltas)}]
       :usage            {:input-tokens 1 :output-tokens 1}
       :model            (:model request)
       :backend-metadata {:tag tag}}))
  proto/StreamingLLMBackend
  (stream-turn [_ request on-delta]
    (p/do!
      (doseq [d deltas]
        (on-delta {:type :text-delta :text d}))
      {:stop-reason      :end_turn
       :content          [{:type :text :text (apply str deltas)}]
       :usage            {:input-tokens 1 :output-tokens 1}
       :model            (:model request)
       :backend-metadata {:tag tag :streamed true}})))

(def anth (->StubBackend :anthropic))
(def codex (->StubBackend :codex))
(def fallback (->StubBackend :fallback))
(def streamer (->StreamingStubBackend :streamer ["He" "llo" "!"]))

(specification "MultiBackend routes by model name"
  (component "regex matcher"
    (let [b (multi/new-backend
              {:routes [[#"^claude-" anth] [#"^gpt-5" codex]]})]
      (assertions
        "claude-* routes to anthropic stub"
        (-> (p/await! (proto/send-turn b {:model "claude-sonnet-4-6" :messages []}))
          :backend-metadata :tag) => :anthropic

        "gpt-5* routes to codex stub"
        (-> (p/await! (proto/send-turn b {:model "gpt-5.2-codex" :messages []}))
          :backend-metadata :tag) => :codex)))

  (component "set matcher (exact membership)"
    (let [b (multi/new-backend
              {:routes          [[#{"glm-4.6" "deepseek"} anth]]
               :default-backend fallback})]
      (assertions
        "membership routes match"
        (-> (p/await! (proto/send-turn b {:model "glm-4.6" :messages []}))
          :backend-metadata :tag) => :anthropic

        "non-member falls through to default"
        (-> (p/await! (proto/send-turn b {:model "haiku" :messages []}))
          :backend-metadata :tag) => :fallback)))

  (component "function matcher"
    (let [b (multi/new-backend
              {:routes          [[(fn [m] (.startsWith ^String m "opus")) anth]]
               :default-backend codex})]
      (assertions
        "fn returns truthy → route taken"
        (-> (p/await! (proto/send-turn b {:model "opus-x" :messages []}))
          :backend-metadata :tag) => :anthropic

        "fn returns false → default"
        (-> (p/await! (proto/send-turn b {:model "other" :messages []}))
          :backend-metadata :tag) => :codex)))

  (component "no match + no default throws"
    (let [b (multi/new-backend {:routes [[#"^claude-" anth]]})]
      (assertions
        "throws ex-info with model in data"
        (try (p/await! (proto/send-turn b {:model "gpt-5" :messages []}))
             :no-throw
             (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
               (:model (ex-data e)))) => "gpt-5")))

  (component "nil model uses default"
    (let [b (multi/new-backend
              {:routes          [[#"^claude-" anth]]
               :default-backend fallback})]
      (assertions
        "missing :model falls through to default"
        (-> (p/await! (proto/send-turn b {:messages []}))
          :backend-metadata :tag) => :fallback)))

  (component "first matching route wins"
    (let [b (multi/new-backend
              {:routes [[#"-mini$" anth]
                        [#"^gpt-" codex]]})]
      (assertions
        "gpt-5-mini matches the -mini rule before gpt-* rule"
        (-> (p/await! (proto/send-turn b {:model "gpt-5-mini" :messages []}))
          :backend-metadata :tag) => :anthropic))))

(specification "MultiBackend provider-keyed dispatch (R3)"
  (component "request :provider selects the provider-tagged sub-backend"
    (let [b (multi/new-backend
              {:routes [[#"^claude-" anth :anthropic]
                        [#"^gpt-5" codex :codex]]})]
      (assertions
        "explicit :provider bypasses the model regex (model that would match no route)"
        (-> (p/await! (proto/send-turn b {:provider :codex :model "kimi-k2.6" :messages []}))
          :backend-metadata :tag) => :codex

        "explicit :provider overrides what the model regex would have picked"
        (-> (p/await! (proto/send-turn b {:provider :anthropic :model "gpt-5.2" :messages []}))
          :backend-metadata :tag) => :anthropic)))

  (component "absent :provider is inert — legacy matcher path unchanged"
    (let [b (multi/new-backend
              {:routes [[#"^claude-" anth :anthropic]
                        [#"^gpt-5" codex :codex]]})]
      (assertions
        "no :provider → routes by model regex exactly as before"
        (-> (p/await! (proto/send-turn b {:model "claude-sonnet-4-6" :messages []}))
          :backend-metadata :tag) => :anthropic)))

  (component "unknown :provider falls back to matcher logic"
    (let [b (multi/new-backend
              {:routes          [[#"^claude-" anth :anthropic]]
               :default-backend fallback})]
      (assertions
        "untagged provider → not in index → matcher/default path"
        (-> (p/await! (proto/send-turn b {:provider :nonesuch :model "claude-x" :messages []}))
          :backend-metadata :tag) => :anthropic

        "untagged provider + non-matching model → default"
        (-> (p/await! (proto/send-turn b {:provider :nonesuch :model "haiku" :messages []}))
          :backend-metadata :tag) => :fallback)))

  (component "legacy 2-tuple routes build an empty provider index (provider branch inert)"
    (let [b (multi/new-backend
              {:routes [[#"^claude-" anth] [#"^gpt-5" codex]]})]
      (assertions
        "provider-index empty for untagged routes"
        (:provider-index b) => {}

        "a :provider on the request with no tagged routes still routes by model"
        (-> (p/await! (proto/send-turn b {:provider :anthropic :model "gpt-5.2" :messages []}))
          :backend-metadata :tag) => :codex)))

  (component "first provider occurrence wins in the index"
    (let [b (multi/new-backend
              {:routes [[#"^a" anth :dup] [#"^b" codex :dup]]})]
      (assertions
        "index keeps the first sub-backend tagged with a given provider"
        (-> (p/await! (proto/send-turn b {:provider :dup :model "zzz" :messages []}))
          :backend-metadata :tag) => :anthropic)))

  (component "streaming variant honors provider-keyed dispatch"
    (let [b (multi/new-backend
              {:routes [[#"^stream-" streamer :streamer]]})
          seen (atom [])
          resp (p/await! (proto/stream-turn
                           b {:provider :streamer :model "no-match" :messages []}
                           #(swap! seen conj %)))]
      (assertions
        "provider-tagged streaming sub-backend selected despite non-matching model"
        (-> resp :backend-metadata :tag) => :streamer

        "deltas still forwarded"
        (mapv :text @seen) => ["He" "llo" "!"]))))

(specification "MultiBackend streaming forwarding"
  (component "streaming? reflects picked sub-backend"
    (let [stream-multi (multi/new-backend
                         {:routes [[#"^stream-" streamer]]})
          plain-multi  (multi/new-backend
                         {:routes [[#"^claude-" anth]]})
          mixed-multi  (multi/new-backend
                         {:routes [[#"^stream-" streamer]
                                   [#"^claude-" anth]]})]
      (assertions
        "true when every selectable sub-backend streams"
        (proto/streaming? stream-multi) => true

        "false only when NO selectable sub-backend streams"
        (proto/streaming? plain-multi) => false

        "true when ANY selectable sub-backend streams (mixed routes stream the
         streaming providers and fall back to send-turn for the rest, so one
         non-streaming sub-backend must not disable streaming wholesale)"
        (proto/streaming? mixed-multi) => true

        "default-backend participates in capability detection"
        (proto/streaming?
          (multi/new-backend {:routes          []
                              :default-backend streamer})) => true)))

  (component "mixed multi: streams the streamer, falls back for the non-streamer"
    (let [b    (multi/new-backend
                 {:routes [[#"^stream-" streamer]
                           [#"^claude-" anth]]})
          seen (atom [])
          ;; streaming route → deltas forwarded
          _    (p/await! (proto/stream-turn
                           b {:model "stream-x" :messages []}
                           #(swap! seen conj %)))
          n-streamed (count @seen)
          ;; non-streaming route → no deltas, still returns a Response
          resp (p/await! (proto/stream-turn
                           b {:model "claude-3" :messages []}
                           #(swap! seen conj %)))]
      (assertions
        "the streaming sub-backend forwarded deltas"
        (pos? n-streamed) => true

        "the non-streaming route added no further deltas (graceful fallback)"
        (count @seen) => n-streamed

        "and still produced the sub-backend's Response"
        (:stop-reason resp) => :end_turn)))

  (component "stream-turn yields deltas then a final Response"
    (let [b    (multi/new-backend
                 {:routes [[#"^stream-" streamer]]})
          seen (atom [])
          resp (p/await! (proto/stream-turn
                           b {:model "stream-x" :messages []}
                           #(swap! seen conj %)))]
      (assertions
        "deltas forwarded unchanged, in order"
        @seen => [{:type :text-delta :text "He"}
                  {:type :text-delta :text "llo"}
                  {:type :text-delta :text "!"}]

        "final value is the sub-backend's Response (forwarded unchanged)"
        (:stop-reason resp) => :end_turn

        "routed to the picked sub-backend"
        (-> resp :backend-metadata :tag) => :streamer

        "Response not re-shaped by the multi layer"
        (-> resp :backend-metadata :streamed) => true)))

  (component "send-turn* drives streaming through the multi"
    (let [b    (multi/new-backend
                 {:routes [[#"^stream-" streamer]]})
          seen (atom [])
          resp (p/await! (proto/send-turn*
                           b {:model "stream-x" :messages []}
                           #(swap! seen conj %)))]
      (assertions
        "capability-aware entry point streams via the multi"
        (mapv :text @seen) => ["He" "llo" "!"]

        "still returns the final Response"
        (:stop-reason resp) => :end_turn)))

  (component "send-turn behavior unchanged on streaming variant"
    (let [b (multi/new-backend
              {:routes [[#"^stream-" streamer]]})]
      (assertions
        "send-turn still returns the buffered Response"
        (-> (p/await! (proto/send-turn b {:model "stream-x" :messages []}))
          :backend-metadata :tag) => :streamer))))
