(ns escapement.cli-test
  (:require
   [escapement.cli :as cli]
   [fulcro-spec.core :refer [specification assertions =>]]))

(specification "parse-param"
               (assertions
                "splits key=value and EDN-reads the value"
                (cli/parse-param "a=1")          => [:a 1]
                (cli/parse-param "max=5")        => [:max 5]
                (cli/parse-param "flag=true")    => [:flag true]
                (cli/parse-param "kw=:x")        => [:kw :x]
                "unparseable EDN falls back to a plain string"
                (cli/parse-param "name=alice")   => [:name "alice"]
                "quoted strings round-trip via EDN"
                (cli/parse-param "name=\"alice\"") => [:name "alice"]
                "key may contain dashes"
                (cli/parse-param "max-iters=5")  => [:max-iters 5]
                "missing = yields nil"
                (cli/parse-param "noequals")     => nil
                "empty key yields nil"
                (cli/parse-param "=1")           => nil
                "value may be empty string"
                (cli/parse-param "k=")           => [:k nil])) ;; (edn/read-string "") -> nil
