(ns matrix-team.prompts
  "Prompt loader for the matrix-team demo. One template per agent."
  (:require
    [clojure.java.io :as io]
    [escapement.prompts :as prompts]))

(def ^:private prompt-root "matrix_team/prompts/")

(defn- resource-path [name]
  (or (io/resource (str prompt-root name))
    (str "demos/" prompt-root name)))

(def ^:private templates
  {:experimenter "experimenter.md"
   :tester       "tester.md"})

(defn render
  "Render the prompt for `agent-key` using values from chart data."
  [agent-key data]
  (let [tpl  (or (get templates agent-key)
               (throw (ex-info (str "Unknown agent prompt: " agent-key)
                        {:agent agent-key})))
        subs {:PROJECT_DIR    (:project-dir data)
              :SOURCE_PATH    (:source-path data)
              :TEST_PATH      (:test-path data)
              :MAX_ITERATIONS (:max-iterations data)}]
    (prompts/render-file (resource-path tpl) subs)))
