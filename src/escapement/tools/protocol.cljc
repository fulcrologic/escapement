(ns escapement.tools.protocol
  "The `Tool` protocol and a keyword-keyed registry.

  A `Tool` is the unit the LLM sees: it has a stable keyword name, a human-readable
  description, a Malli input schema, and an `invoke` that performs the side effect and
  returns the Anthropic-shaped tool result `{:result <string> :is-error <bool>}`.

  Registry values are tools indexed by `tool-name`. `dispatch` validates the input
  against the tool's Malli schema before invoking; validation failures are returned
  as `{:result <pretty error> :is-error true}` so the LLM gets a corrective tool_result
  rather than a thrown exception."
  (:require
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
    [malli.core :as m]
    [malli.error :as me]
    [malli.json-schema :as mjs]))

(def ^:dynamic *base-dir*
  "Directory that path-taking builtin tools resolve RELATIVE paths against.
   Bound by `dispatch` for the duration of a tool `invoke`. `nil` means
   \"use the process working directory\" (back-compat default).

   The base-dir is sourced (in order): the explicit `base-dir` arg to
   `dispatch`, then `(:escapement/base-dir (meta reg))` (set by the CLI
   from `:escapement/session-dir` when it builds the registry), else nil."
  nil)

(defprotocol Tool
  "A tool the LLM may invoke. Implementations are typically `defrecord`s."
  (tool-name [t] "Returns the tool's keyword name, e.g. `:fs/read`.")
  (description [t] "Returns a short human-readable description for the LLM.")
  (input-schema [t] "Returns the tool's Malli input schema.")
  (invoke [t input] "Performs the tool action. Returns `{:result <string> :is-error <bool>}`."))

(defn- tool? [x] (satisfies? Tool x))

(>defn new-registry
  "Returns a fresh atom-backed registry, optionally seeded with `tools` (a collection)."
  ([] [=> any?] (new-registry []))
  ([tools]
   [[:sequential [:fn tool?]] => any?]
   (atom (reduce (fn [m t] (assoc m (tool-name t) t)) {} tools))))

(>defn register!
  "Registers `tool` into `reg` under its `tool-name`. Returns the tool."
  [reg tool]
  [any? [:fn tool?] => any?]
  (swap! reg assoc (tool-name tool) tool)
  tool)

(>defn lookup
  "Returns the tool registered under `kw`, or nil."
  [reg kw]
  [any? :keyword => [:maybe [:fn tool?]]]
  (get @reg kw))

(>defn all-tools
  "Returns a vector of all registered tools (deterministic by tool-name)."
  [reg]
  [any? => [:sequential [:fn tool?]]]
  (->> @reg vals (sort-by tool-name) vec))

(defn- kw->str [kw]
  (if (qualified-keyword? kw)
    (str (namespace kw) "_" (name kw))
    (name kw)))

(>defn tool->anthropic-tool-def
  "Returns the internal tool definition map for `tool`:
  `{:name <string> :description <string> :input-schema <JSON Schema>}`.

  NOTE: We use `:input-schema` (dash form) internally — matches our other
  keyword conventions (`:max-tokens`, `:stop-reason`). The api backend translates
  it to wire-form `\"input_schema\"` when serializing JSON.

  Anthropic tool names must be a non-empty alphanum/underscore string, so a
  qualified keyword like `:fs/read` is rendered as `\"fs_read\"`."
  [tool]
  [[:fn tool?] => map?]
  {:name         (kw->str (tool-name tool))
   :description  (description tool)
   :input-schema (mjs/transform (input-schema tool))})

(defn- humanize-error [schema input]
  (let [explained (m/explain schema input)]
    (with-out-str
      (println "Tool input failed validation.")
      (println "Errors:")
      (prn (me/humanize explained)))))

(>defn dispatch
  "Look up `tool-kw` in `reg` and `invoke` it with `input` after validating against
  the tool's input schema. Returns `{:result <string> :is-error <bool>}`. Unknown
  tool names and validation failures are returned as error results.

  `base-dir` (optional) is the directory that path-taking builtin tools resolve
  RELATIVE paths against; absolute paths pass through untouched. When omitted,
  it falls back to `(:escapement/base-dir (meta reg))` (set by the CLI from
  `:escapement/session-dir`) and finally to the process working directory
  (back-compat: existing 3-arg callers keep their previous behaviour)."
  ([reg tool-kw input]
   [any? :keyword any? => [:map [:result :string] [:is-error :boolean]]]
   (dispatch reg tool-kw input (:escapement/base-dir (meta reg))))
  ([reg tool-kw input base-dir]
   [any? :keyword any? [:maybe :string] => [:map [:result :string] [:is-error :boolean]]]
   (if-let [t (lookup reg tool-kw)]
     (let [schema (input-schema t)]
       (if (m/validate schema input)
         (binding [*base-dir* base-dir]
           (try
             (invoke t input)
             (catch Throwable ex
               {:result   (str "Tool threw: " (.getMessage ex))
                :is-error true})))
         {:result   (humanize-error schema input)
          :is-error true}))
     {:result   (str "No such tool: " tool-kw)
      :is-error true})))
