(ns escapement.llm.ratings-test
  "The subjective overlay: opinion about models (intelligence and any
   other free-form tags) kept out of the objective catalog and out of the
   invocation path, layered from `.escapement.edn`."
  (:require
    [escapement.llm.ratings :as ratings]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(specification "ratings overlay"
  (component "no built-in opinion"
    (assertions
      "nothing configured → empty table, every id unknown"
      (ratings/ratings {}) => {}
      (ratings/rating-for {} "claude-opus-4-7") => nil
      (ratings/rating-for {} "gpt-5") => nil))

  (component "config-defined — :llm/ratings is the only source"
    (let [cfg {:llm/ratings {"gpt-5"           {:intelligence 7}
                             "claude-opus-4-7" {:good-at #{:code} :clojure 9}}}]
      (assertions
        "a configured id carries exactly the keys the user set"
        (:intelligence (ratings/rating-for cfg "gpt-5")) => 7
        "keys are free-form (arbitrary, chart-usable)"
        (:good-at (ratings/rating-for cfg "claude-opus-4-7")) => #{:code}
        (:clojure (ratings/rating-for cfg "claude-opus-4-7")) => 9
        "an id absent from the config has no opinion"
        (ratings/rating-for cfg "claude-haiku-4-5") => nil
        "nested [:llm :ratings] form is also accepted"
        (:intelligence (ratings/rating-for {:llm {:ratings {"gpt-5" {:intelligence 3}}}}
                         "gpt-5"))
        => 3)))

  (component "dated id resolves to the family entry via longest-prefix"
    (let [cfg {:llm/ratings {"claude-opus-4-7" {:intelligence 10}}}]
      (assertions
        (:intelligence (ratings/rating-for cfg "claude-opus-4-7-20260101")) => 10)))

  (component "full merged table"
    (assertions
      "ratings returns the config table verbatim"
      (get-in (ratings/ratings {:llm/ratings {"gpt-5" {:intelligence 1}}})
        ["gpt-5" :intelligence])
      => 1)))
