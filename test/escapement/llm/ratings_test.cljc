(ns escapement.llm.ratings-test
  "The subjective overlay: opinion about models (intelligence and any
   other free-form tags) kept out of the objective catalog and out of the
   invocation path, layered from `.escapement.edn`."
  (:require
    [escapement.llm.ratings :as ratings]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(specification "ratings overlay (alias-keyword keyed — mandatory-aliases model)"
  (component "no built-in opinion"
    (assertions
      "nothing configured → empty table, every alias unknown"
      (ratings/ratings {}) => {}
      (ratings/rating-for {} :opus) => nil
      (ratings/rating-for {} :gpt) => nil))

  (component "config-defined — :llm/ratings is the only source, keyed by ALIAS keyword"
    (let [cfg {:llm/ratings {:gpt  {:intelligence 7}
                             :opus {:good-at #{:code} :clojure 9}}}]
      (assertions
        "a configured alias carries exactly the keys the user set"
        (:intelligence (ratings/rating-for cfg :gpt)) => 7
        "keys are free-form (arbitrary, chart-usable)"
        (:good-at (ratings/rating-for cfg :opus)) => #{:code}
        (:clojure (ratings/rating-for cfg :opus)) => 9
        "an alias absent from the config has no opinion"
        (ratings/rating-for cfg :haiku) => nil
        "nested [:llm :ratings] form is also accepted"
        (:intelligence (ratings/rating-for {:llm {:ratings {:gpt {:intelligence 3}}}}
                         :gpt))
        => 3)))

  (component "exact alias-keyword lookup only — no string-prefix resolution"
    (let [cfg {:llm/ratings {:opus {:intelligence 10}}}]
      (assertions
        "a string model id is not a legal rating key — nil"
        (ratings/rating-for cfg "claude-opus-4-7") => nil
        "an unrelated alias has no opinion"
        (ratings/rating-for cfg :gpt) => nil)))

  (component "string-keyed? + dangling-references referential helpers"
    (assertions
      "string-keyed? flags legacy model-id string keys"
      (ratings/string-keyed? {"gpt-5" {:intelligence 1}}) => true
      (ratings/string-keyed? {:gpt {:intelligence 1}}) => false
      "dangling-references lists rating keys absent from :llm/aliases"
      (ratings/dangling-references {:opus {} :nope {}}
        {:opus [{:provider :anthropic :model "claude-opus-4-7"}]})
      => [:nope]))

  (component "full merged table"
    (assertions
      "ratings returns the config table verbatim (alias-keyed)"
      (get-in (ratings/ratings {:llm/ratings {:gpt {:intelligence 1}}})
        [:gpt :intelligence])
      => 1)))
