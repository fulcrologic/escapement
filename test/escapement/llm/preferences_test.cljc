(ns escapement.llm.preferences-test
  "Preference list as a vector of ALIAS KEYWORDS (mandatory-aliases model):
   config extraction/fallback, the built-in default alias set + default
   preference vector, and the alias-flatten transforms (`flatten-targets`/
   `model-order`/`provider-order`) the llm-conversation + providers paths
   consume. (Full R1–R8 acceptance coverage lives in task 004.)"
  (:require
    [escapement.llm.preferences :as prefs]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(specification "preferences (alias-keyword vector)"
  (component "default alias set + default preference vector"
    (assertions
      "default-preferences is a vector of keywords"
      (every? keyword? prefs/default-preferences) => true
      "every default preference keyword is a default-aliases key"
      (every? (set (keys prefs/default-aliases)) prefs/default-preferences) => true))

  (component "from-config — flat key, nested key, or nil"
    (assertions
      "flat :llm/preferences wins"
      (prefs/from-config {:llm/preferences [:fast :smart]}) => [:fast :smart]
      "nested [:llm :preferences] is accepted"
      (prefs/from-config {:llm {:preferences [:fast]}}) => [:fast]
      "absent → nil so caller can fall back"
      (prefs/from-config {}) => nil))

  (component "aliases-from-config — config wins, else built-in defaults"
    (assertions
      "flat :llm/aliases wins"
      (prefs/aliases-from-config {:llm/aliases {:x [{:provider :a :model "m"}]}})
      => {:x [{:provider :a :model "m"}]}
      "absent → built-in default-aliases"
      (prefs/aliases-from-config {}) => prefs/default-aliases))

  (component "preferences — config overrides, else default vector"
    (assertions
      "explicit alias-keyword config is used as-is"
      (prefs/preferences {:llm/preferences [:fast :smart]}) => [:fast :smart]
      "absent config falls back to the default preference vector"
      (prefs/preferences {}) => prefs/default-preferences))

  (component "flatten-targets — alias keywords → ordered, de-duped targets"
    (let [aliases {:a [{:provider :p1 :model "x"} {:provider :p2 :model "y"}]
                   :b [{:provider :p2 :model "y"} {:provider :p3 :model "z"}]}]
      (assertions
        "flattens in preference order, de-duping repeated targets"
        (prefs/flatten-targets [:a :b] aliases)
        => [{:provider :p1 :model "x"} {:provider :p2 :model "y"} {:provider :p3 :model "z"}]
        "unknown alias keywords contribute nothing"
        (prefs/flatten-targets [:a :nope] aliases)
        => [{:provider :p1 :model "x"} {:provider :p2 :model "y"}])))

  (component "model-order — distinct model ids in priority order"
    (let [aliases {:a [{:provider :p1 :model "x"} {:provider :p2 :model "x"}]
                   :b [{:provider :p3 :model "y"}]}]
      (assertions
        "two-arg: flatten aliases then dedupe model ids"
        (prefs/model-order [:a :b] aliases) => ["x" "y"]
        "one-arg legacy seam: already-flattened target maps → model ids"
        (prefs/model-order [{:provider :a :model "x"}
                            {:provider :b :model "x"}
                            {:provider :c :model "y"}]) => ["x" "y"]
        "empty in, empty out"
        (prefs/model-order [] {}) => [])))

  (component "provider-order — distinct providers in preference order"
    (let [aliases {:a [{:provider :p1 :model "x"} {:provider :p2 :model "y"}]
                   :b [{:provider :p2 :model "z"} {:provider :p3 :model "w"}]}]
      (assertions
        "providers in flattened order, de-duped"
        (prefs/provider-order [:a :b] aliases) => [:p1 :p2 :p3]))))
