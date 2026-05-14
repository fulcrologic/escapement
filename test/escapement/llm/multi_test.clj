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

(def anth (->StubBackend :anthropic))
(def codex (->StubBackend :codex))
(def fallback (->StubBackend :fallback))

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
