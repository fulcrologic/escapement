(ns escapement.llm.multi-test
  (:require
   [escapement.llm.multi :as multi]
   [escapement.llm.protocol :as proto]
   [fulcro-spec.core :refer [specification component assertions =>]]))

(defrecord StubBackend [tag]
  proto/LLMBackend
  (send-turn [_ request]
    {:stop-reason :end_turn
     :content     [{:type :text :text (str "from-" (name tag))}]
     :usage       {:input-tokens 1 :output-tokens 1}
     :model       (:model request)
     :backend-metadata {:tag tag}}))

(defrecord StreamingStubBackend [tag deltas]
  proto/LLMBackend
  (send-turn [_ request]
    {:stop-reason :end_turn
     :content     [{:type :text :text (apply str deltas)}]
     :usage       {:input-tokens 1 :output-tokens 1}
     :model       (:model request)
     :backend-metadata {:tag tag}})
  proto/StreamingLLMBackend
  (stream-turn [_ request on-delta]
    (doseq [d deltas]
      (on-delta {:type :text-delta :text d}))
    {:stop-reason :end_turn
     :content     [{:type :text :text (apply str deltas)}]
     :usage       {:input-tokens 1 :output-tokens 1}
     :model       (:model request)
     :backend-metadata {:tag tag :streamed true}}))

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
                             (-> (proto/send-turn b {:model "claude-sonnet-4-6" :messages []})
                                 :backend-metadata :tag) => :anthropic

                             "gpt-5* routes to codex stub"
                             (-> (proto/send-turn b {:model "gpt-5.2-codex" :messages []})
                                 :backend-metadata :tag) => :codex)))

               (component "set matcher (exact membership)"
                          (let [b (multi/new-backend
                                   {:routes [[#{"glm-4.6" "deepseek"} anth]]
                                    :default-backend fallback})]
                            (assertions
                             "membership routes match"
                             (-> (proto/send-turn b {:model "glm-4.6" :messages []})
                                 :backend-metadata :tag) => :anthropic

                             "non-member falls through to default"
                             (-> (proto/send-turn b {:model "haiku" :messages []})
                                 :backend-metadata :tag) => :fallback)))

               (component "function matcher"
                          (let [b (multi/new-backend
                                   {:routes [[(fn [m] (.startsWith ^String m "opus")) anth]]
                                    :default-backend codex})]
                            (assertions
                             "fn returns truthy → route taken"
                             (-> (proto/send-turn b {:model "opus-x" :messages []})
                                 :backend-metadata :tag) => :anthropic

                             "fn returns false → default"
                             (-> (proto/send-turn b {:model "other" :messages []})
                                 :backend-metadata :tag) => :codex)))

               (component "no match + no default throws"
                          (let [b (multi/new-backend {:routes [[#"^claude-" anth]]})]
                            (assertions
                             "throws ex-info with model in data"
                             (try (proto/send-turn b {:model "gpt-5" :messages []})
                                  :no-throw
                                  (catch clojure.lang.ExceptionInfo e
                                    (:model (ex-data e)))) => "gpt-5")))

               (component "nil model uses default"
                          (let [b (multi/new-backend
                                   {:routes [[#"^claude-" anth]]
                                    :default-backend fallback})]
                            (assertions
                             "missing :model falls through to default"
                             (-> (proto/send-turn b {:messages []})
                                 :backend-metadata :tag) => :fallback)))

               (component "first matching route wins"
                          (let [b (multi/new-backend
                                   {:routes [[#"-mini$" anth]
                                             [#"^gpt-" codex]]})]
                            (assertions
                             "gpt-5-mini matches the -mini rule before gpt-* rule"
                             (-> (proto/send-turn b {:model "gpt-5-mini" :messages []})
                                 :backend-metadata :tag) => :anthropic))))

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

                             "false when selected sub-backend does not stream"
                             (proto/streaming? plain-multi) => false

                             "false (conservative) when a selectable sub-backend is non-streaming"
                             (proto/streaming? mixed-multi) => false

                             "default-backend participates in capability detection"
                             (proto/streaming?
                              (multi/new-backend {:routes []
                                                  :default-backend streamer})) => true)))

               (component "stream-turn yields deltas then a final Response"
                          (let [b    (multi/new-backend
                                      {:routes [[#"^stream-" streamer]]})
                                seen (atom [])
                                resp (proto/stream-turn
                                      b {:model "stream-x" :messages []}
                                      #(swap! seen conj %))]
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
                                resp (proto/send-turn*
                                      b {:model "stream-x" :messages []}
                                      #(swap! seen conj %))]
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
                             (-> (proto/send-turn b {:model "stream-x" :messages []})
                                 :backend-metadata :tag) => :streamer))))
