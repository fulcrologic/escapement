(ns escapement.tools.input-keys
  "Schema-driven key decoding for tool input.

   A model's tool input is JSON, and every provider parser leaves it with keys
   that do not match the tool's Malli schema at some depth: the Anthropic and
   OpenAI-compatible parsers keywordize only the top level (so a vector of maps
   keeps string keys), while the claude CLI and Codex parsers keywordize every
   level (so open `[:map-of :string ...]` data grows keyword keys). Validation
   then fails with \"missing required key\" on input the model got right.

   `key-transformer` fixes both directions against the schema itself:

   - a key DECLARED by a `:map` schema becomes that keyword wherever the map
     sits — nested maps, vectors of maps, optional entries, `:maybe`, `:or`
     branches and `:multi` branches (keyed before dispatch, so a keyword
     dispatch function sees its key);
   - `[:map-of :string ...]` keys stay (or become again) strings;
   - an undeclared key is left exactly as it arrived.

   Schema-driven rather than a blanket deep keywordize because the blanket
   form corrupts `:map-of :string` data (a file path or a free-form label used
   as a key) and invents keywords from arbitrary model text."
  (:require
    [malli.core :as m]
    [malli.transform :as mt]))

(defn- json-name
  "The JSON property name a keyword key travels under (`:a/b` -> \"a/b\")."
  [k]
  (if (keyword? k)
    (if-let [n (namespace k)] (str n "/" (name k)) (name k))
    (str k)))

(defn- declared-keys
  "`{json-name keyword}` for every keyword key a `:map` `schema` declares."
  [schema]
  (into {}
    (keep (fn [[k]] (when (keyword? k) [(json-name k) k])))
    (m/children schema)))

(defn- keywordize-declared
  "A decoder renaming each string key of a map that names a key in
   `name->key` to that keyword. A key already present as a keyword wins."
  [name->key]
  (fn [x]
    (if (map? x)
      (reduce-kv
        (fn [acc s k]
          (if (and (contains? acc s) (not (contains? acc k)))
            (-> acc (dissoc s) (assoc k (get acc s)))
            acc))
        x name->key)
      x)))

(defn- multi-declared-keys
  "The union of `declared-keys` over the `:map` branches of a `:multi`."
  [schema]
  (into {}
    (comp
      (map (fn [[_ _ child]] (m/deref-all child)))
      (filter #(= :map (m/type %)))
      (map declared-keys))
    (m/children schema)))

(defn- stringify-keys
  "A decoder turning every keyword key of a map back into its JSON name."
  [x]
  (if (map? x)
    (reduce-kv (fn [acc k v] (assoc acc (if (keyword? k) (json-name k) k) v)) {} x)
    x))

(def key-transformer
  "Malli transformer that decodes tool-input keys against the schema (see the
   namespace docstring). Compose it BEFORE value transformers such as
   `mt/json-transformer`, so their per-key decoders find their keys."
  (mt/transformer
    {:name     ::input-keys
     :decoders {:map    {:compile (fn [schema _] (keywordize-declared (declared-keys schema)))}
                :multi  {:compile (fn [schema _] (keywordize-declared (multi-declared-keys schema)))}
                :map-of {:compile (fn [schema _]
                                    (when (= :string (m/type (m/deref-all (first (m/children schema)))))
                                      stringify-keys))}}}))

(defn decode-keys
  "Returns `input` with its keys decoded against the Malli `schema` by
   `key-transformer`. Values are untouched."
  [schema input]
  (m/decode schema input key-transformer))
