(ns escapement.llm.ratings-test
  "The subjective overlay: opinion about models (intelligence and any
   other free-form tags) kept out of the objective catalog and out of the
   invocation path, layered from `.escapement.edn`."
  (:require
   [escapement.llm.ratings :as ratings]
   [fulcro-spec.core :refer [specification assertions component =>]]))

(specification "ratings overlay"
  (component "built-in defaults"
    (assertions
     "ship a coarse intelligence rating for well-known ids"
     (:intelligence (ratings/rating-for {} "claude-opus-4-7")) => 10
     (:intelligence (ratings/rating-for {} "gpt-5")) => 10
     "unknown model has no opinion"
     (ratings/rating-for {} "totally-made-up") => nil
     "dated id resolves to the family entry via longest-prefix"
     (:intelligence (ratings/rating-for {} "claude-opus-4-7-20260101")) => 10))

  (component "config overlay — :llm/ratings wins per key, project layered"
    (let [cfg {:llm/ratings {"gpt-5"        {:intelligence 7}
                             "claude-opus-4-7" {:good-at #{:code}}}}]
      (assertions
       "config overrides a default key"
       (:intelligence (ratings/rating-for cfg "gpt-5")) => 7
       "config can add a brand-new opinion key (arbitrary, chart-usable)"
       (:good-at (ratings/rating-for cfg "claude-opus-4-7")) => #{:code}
       "non-overridden default keys survive the merge"
       (:intelligence (ratings/rating-for cfg "claude-opus-4-7")) => 10
       "nested [:llm :ratings] form is also accepted"
       (:intelligence (ratings/rating-for {:llm {:ratings {"gpt-5" {:intelligence 3}}}}
                                          "gpt-5"))
       => 3)))

  (component "full merged table"
    (assertions
     "ratings returns id->opinion map merged with config"
     (get-in (ratings/ratings {:llm/ratings {"gpt-5" {:intelligence 1}}})
             ["gpt-5" :intelligence])
     => 1)))
