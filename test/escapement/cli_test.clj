(ns escapement.cli-test
  (:require
   [clojure.java.io :as io]
   [escapement.cli :as cli]
   [fulcro-spec.core :refer [specification component assertions =>]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir [] (str (Files/createTempDirectory "escapement-cli" (into-array FileAttribute []))))

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

(specification "parse-source-paths"
               (assertions
                "splits on colons"
                (vec (cli/parse-source-paths "a:b:c")) => ["a" "b" "c"]
                "nil yields nil"
                (cli/parse-source-paths nil) => nil
                "drops empty entries"
                (vec (cli/parse-source-paths "a::b")) => ["a" "b"]))

(specification "parse-tools-ns-flag"
               (assertions
                "splits accumulated values on commas and reads symbols"
                (cli/parse-tools-ns-flag ["a.b/x" "c.d/y,e.f/z"])
                => '[a.b/x c.d/y e.f/z]
                "empty input yields empty vector"
                (cli/parse-tools-ns-flag []) => []))

(specification "parse-deps-flag"
               (assertions
                "reads an EDN map"
                (cli/parse-deps-flag "{hiccup/hiccup {:mvn/version \"2.0.0\"}}")
                => '{hiccup/hiccup {:mvn/version "2.0.0"}}
                "nil passes through"
                (cli/parse-deps-flag nil) => nil))

(specification "effective-opts — precedence"
               (let [root-str (tmp-dir)
                     root     (io/file root-str)
                     cfg      {:source-paths ["charts"]
                               :deps         '{hiccup/hiccup {:mvn/version "2.0.0-RC3"}
                                               pinned/lib    {:mvn/version "1.0.0"}}
                               :tools-ns     '[a.tools/reg]
                               :work-dir     "transcripts"
                               :default-chart 'my.app.charts.hello/agent}]
                 (component "config supplies defaults"
                            (let [eff (cli/effective-opts {} cfg root)]
                              (assertions
                               "source-paths resolved against config root"
                               (mapv #(.getName ^java.io.File %) (:source-paths eff)) => ["charts"]
                               "work-dir resolved to absolute path under config root"
                               (:work-dir eff) => (.getAbsolutePath (io/file root "transcripts"))
                               "tools-ns from config"
                               (:tools-ns eff) => '[a.tools/reg]
                               "deps from config"
                               (:deps eff) => '{hiccup/hiccup {:mvn/version "2.0.0-RC3"}
                                                pinned/lib    {:mvn/version "1.0.0"}}
                               "default-chart surfaced"
                               (:default-chart eff) => 'my.app.charts.hello/agent)))

                 (component "CLI flags override config"
                            (let [eff (cli/effective-opts
                                       {:work-dir "/abs/wd"
                                        :source-paths "extra:also"
                                        :tools-ns ["b.tools/reg"]
                                        :deps "{pinned/lib {:mvn/version \"9.9.9\"}}"}
                                       cfg root)]
                              (assertions
                               "CLI work-dir wins outright"
                               (:work-dir eff) => "/abs/wd"
                               "CLI deps merged on top of config (CLI wins per coord)"
                               (get-in eff [:deps 'pinned/lib]) => {:mvn/version "9.9.9"}
                               "config-only coordinates preserved"
                               (get-in eff [:deps 'hiccup/hiccup]) => {:mvn/version "2.0.0-RC3"}
                               "CLI tools-ns appended to config tools-ns"
                               (:tools-ns eff) => '[a.tools/reg b.tools/reg]
                               "CLI source-paths prepended (cwd-relative)"
                               (count (:source-paths eff)) => 3)))

                 (component "no config, no flags — defaults kick in"
                            (let [eff (cli/effective-opts {} nil nil)]
                              (assertions
                               "default work-dir"
                               (:work-dir eff) => ".escapement"
                               "no source-paths"
                               (:source-paths eff) => []
                               "no deps"
                               (:deps eff) => nil
                               "no tools-ns"
                               (:tools-ns eff) => []
                               "no default-chart"
                               (:default-chart eff) => nil)))))
