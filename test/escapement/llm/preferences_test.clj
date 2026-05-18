(ns escapement.llm.preferences-test
  "Priority-ordered (provider, model) preference list: validation against
   the catalog, config extraction/fallback, and the pure list transforms
   (`sanitize`/`available`/`model-order`) the llm-conversation path consumes."
  (:require
   [escapement.llm.preferences :as prefs]
   [fulcro-spec.core :refer [specification assertions component =>]]))

;; A pair the catalog is asserted to reach by
;; `catalog_test.clj` "default preferences stay reachable".
(def ^:private valid (first prefs/default-preferences))
(def ^:private bogus {:provider :no-such-provider :model "no-such-model"})

(specification "preferences"
  (component "valid-entry? — only catalog-reachable pairs"
    (assertions
     "a default entry validates"
     (prefs/valid-entry? valid) => true
     "unknown provider/model is rejected"
     (prefs/valid-entry? bogus) => false
     "missing provider or model is rejected"
     (prefs/valid-entry? {:model "x"}) => false
     (prefs/valid-entry? {:provider :z-ai}) => false))

  (component "from-config — flat key, nested key, or nil"
    (assertions
     "flat :llm/preferences wins"
     (prefs/from-config {:llm/preferences [valid]}) => [valid]
     "nested [:llm :preferences] is accepted"
     (prefs/from-config {:llm {:preferences [valid]}}) => [valid]
     "absent → nil so caller can fall back"
     (prefs/from-config {}) => nil))

  (component "sanitize — coerce, validate, preserve order"
    (assertions
     "tuple [:provider \"model\"] form is accepted alongside maps"
     (prefs/sanitize [[(:provider valid) (:model valid)]]) => [valid]
     "invalid entries are dropped, valid order preserved"
     (prefs/sanitize [bogus valid bogus]) => [valid]
     "extra keys on a map entry are stripped to :provider/:model"
     (prefs/sanitize [(assoc valid :note "hi")]) => [valid]
     "empty in, empty out"
     (prefs/sanitize []) => []))

  (component "preferences — config overrides, else default"
    (assertions
     "explicit config is sanitized and used"
     (prefs/preferences {:llm/preferences [valid bogus]}) => [valid]
     "absent config falls back to the sanitized default list"
     (prefs/preferences {}) => (prefs/sanitize prefs/default-preferences)))

  (component "available — keep priority order, filter unusable providers"
    (let [a {:provider :a :model "m1"}
          b {:provider :b :model "m2"}
          c {:provider :a :model "m3"}]
      (assertions
       "no predicate → everything, order preserved"
       (prefs/available [a b c]) => [a b c]
       "predicate filters by provider, order preserved"
       (prefs/available [a b c] #{:a}) => [a c]
       "nothing usable → empty"
       (prefs/available [a b c] (constantly false)) => [])))

  (component "model-order — distinct model ids in priority order"
    (assertions
     "dedupes while keeping first-seen order"
     (prefs/model-order [{:provider :a :model "x"}
                         {:provider :b :model "x"}
                         {:provider :c :model "y"}])
     => ["x" "y"]
     "empty in, empty out"
     (prefs/model-order []) => [])))
