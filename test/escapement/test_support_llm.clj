(ns escapement.test-support-llm
  "Shared LLM-test helpers: a mock backend with canned responses and
   the two canonical response shapes (`end_turn` and `tool_use`). Lives
   in its own ns because the core `escapement.test-support` is for
   tiny generic utilities; this file imports `escapement.llm.protocol`."
  (:require
   [escapement.llm.protocol :as llm]
   [escapement.test-support :as ts]))

(defrecord MockBackend [responses call-log]
  llm/LLMBackend
  (send-turn [_ request]
    (swap! call-log conj request)
    (let [r (ts/pop-first! responses)]
      (when (nil? r)
        (throw (ex-info "Mock backend out of canned responses"
                        {:n-calls (count @call-log)})))
      r)))

(defn mock-backend
  "Build a MockBackend whose `send-turn` will return canned `responses`
   in order. Throws if exhausted."
  [responses]
  (->MockBackend (ts/queue responses) (atom [])))

(defn end-turn-response
  "Canonical `:end_turn` response from the LLM."
  [text]
  {:stop-reason :end_turn
   :content     [{:type :text :text (or text "done")}]
   :usage       {:input-tokens 1 :output-tokens 1}
   :model       "mock"})

(defn tool-use-response
  "Canonical `:tool_use` response. `tool-uses` is a vector of
   `{:id :name :input}` maps."
  [tool-uses]
  {:stop-reason :tool_use
   :content     (mapv (fn [{:keys [id name input]}]
                        {:type :tool_use :id id :name name :input input})
                      tool-uses)
   :usage       {:input-tokens 1 :output-tokens 1}
   :model       "mock"})
