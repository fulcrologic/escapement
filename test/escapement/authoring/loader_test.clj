(ns escapement.authoring.loader-test
  "Version identity for data-built charts: step namespaces load under a per-version
   name, are never reloaded in place, and get a per-version chart id; plus the
   step-code portability check."
  (:require
    [clojure.string :as str]
    [escapement.authoring.loader :as loader]
    [escapement.authoring.portability :as port]
    [escapement.chart.data :as data]
    [fulcro-spec.core :refer [=> assertions behavior component specification]]))

(defn- steps-src [n]
  (str "(ns steps\n  (:require [clojure.string :as str]))\n\n"
    "(defn score [_ _] " n ")\n"
    ;; route calls score through its var — the case in-place reloads break
    "(defn route [env data] {:score (score env data) :tag (str/upper-case \"v" n "\")})\n"))

(defn- fresh [] (str "p" (rand-int 1000000000)))

(specification "loader: version names"
  (assertions
    "the step namespace is wf.<project>.v<sha12>"
    (loader/version-ns "Email Triage" "ABCDEF1234567890") => 'wf.email-triage.vabcdef123456
    "the chart id is :wf.<project>/v<sha12>"
    (loader/version-chart-id "email-triage" "abcdef1234567890") => :wf.email-triage/vabcdef123456
    "the ns head is rewritten, the rest kept"
    (loader/rename-ns ";; hi\n(ns steps (:require [x]))\n(def a 1)" 'wf.p.v1)
    => ";; hi\n(ns wf.p.v1 (:require [x]))\n(def a 1)"
    (loader/rename-ns "(def a 1)" 'wf.p.v1) => nil))

(specification "loader: two versions side by side"
  (let [p   (fresh)
        v1  (loader/version-ns p "111111111111")
        v2  (loader/version-ns p "222222222222")
        r1  (loader/load-steps! {:source (steps-src 1) :ns-sym v1})
        route-v1 (get (:publics r1) 'route)
        r2  (loader/load-steps! {:source (steps-src 2) :ns-sym v2})]
    (assertions
      "each version loads under its own name"
      (:ok? r1) => true
      (:ok? r2) => true
      (some? (find-ns v1)) => true
      "an old version keeps calling its own code after a new one loads"
      (route-v1 nil nil) => {:score 1 :tag "V1"}
      ((get (:publics r2) 'route) nil nil) => {:score 2 :tag "V2"}
      "loading the same source again reuses it"
      (:reused? (loader/load-steps! {:source (steps-src 1) :ns-sym v1})) => true
      "different code under an existing version is refused, never reloaded"
      (:code (first (:problems (loader/load-steps! {:source (steps-src 9) :ns-sym v1}))))
      => :already-loaded-different-source
      (route-v1 nil nil) => {:score 1 :tag "V1"}
      "the compiler resolves against the loaded version"
      (:ok? (data/compile {:initial :a :states [{:id :a :entry [:route] :type :final}]}
              {:steps v2})) => true)
    (behavior "unload removes a version"
      (loader/unload! v2)
      (assertions (find-ns v2) => nil (loader/loaded? v2) => false))))

(specification "loader: failures"
  (let [p (fresh)]
    (assertions
      "non-portable code is refused before it runs"
      (:code (first (:problems (loader/load-steps! {:source "(ns steps)\n(defn f [] (slurp \"/etc/passwd\"))"
                                                    :ns-sym (loader/version-ns p "a1")}))))
      => :not-portable
      (find-ns (loader/version-ns p "a1")) => nil
      "code that throws while loading leaves nothing behind"
      (:code (first (:problems (loader/load-steps! {:source "(ns steps)\n(def x (throw (ex-info \"boom\" {})))"
                                                    :ns-sym (loader/version-ns p "b2")}))))
      => :load-failed
      (find-ns (loader/version-ns p "b2")) => nil)))

(defn- cats [src] (set (map :category (port/check src))))

(specification "portability: step code must be portable"
  (assertions
    "pure code over allowed namespaces passes"
    (port/check (str "(ns steps (:require [clojure.string :as str]"
                  " [com.fulcrologic.statecharts.data-model.operations :as ops]"
                  " [escapement.chart.data :as data]))\n"
                  "(defn f [env data] (data/tool! env :email/send {:to (str/trim (:to data))}))\n"
                  "(defn g [_ d] (try (inc (:n d)) (catch Exception _ 0)))\n"
                  "(defn h [_ d] (into {} (map (fn [[k v]] [k (* 2 v)])) d))"))
    => []
    "file IO"
    (cats "(ns s)\n(defn f [] (spit \"x\" 1))") => #{:file-io}
    (cats "(ns s (:require [clojure.java.io :as io]))\n(defn f [] (io/file \"x\"))") =fn=> #(contains? % :file-io)
    "shell"
    (cats "(ns s (:require [babashka.process :as p]))\n(defn f [] (p/shell \"ls\"))") =fn=> #(contains? % :shell)
    "threads"
    (cats "(ns s)\n(defn f [] @(future 1))") => #{:threads}
    "code loading and redefinition"
    (cats "(ns s)\n(defn f [] (eval '(+ 1 2)))") => #{:code-loading}
    (cats "(ns s)\n(defn f [] (alter-var-root #'f identity))") => #{:redefinition}
    "JVM interop"
    (cats "(ns s)\n(defn f [] (System/currentTimeMillis))") => #{:interop}
    (cats "(ns s)\n(defn f [x] (.toUpperCase x))") => #{:interop}
    (cats "(ns s)\n(defn f [] (java.util.Date.))") => #{:interop}
    (cats "(ns s (:import [java.io File]))") => #{:interop}
    "JS interop"
    (cats "(ns s)\n(defn f [] (js/Date.))") =fn=> #(contains? % :interop))
  (let [[p] (port/check "(ns s)\n\n(defn f []\n  (slurp \"x\"))")]
    (assertions
      "problems carry the line and column and read as a sentence"
      (:line p) => 4
      (:column p) => 4
      (str/starts-with? (:message p) "Line 4: `slurp`") => true))
  (component "the shape of the file"
    (assertions
      "it must start with an ns form"
      (:code (first (port/check "(defn f [] 1)"))) => :no-ns
      "one namespace only"
      (:code (first (port/check "(ns a)\n(in-ns 'b)"))) =fn=> #{:many-ns :not-portable}
      "unreadable code is reported, not thrown"
      (:code (first (port/check "(ns a)\n(defn f [] (+ 1 2)"))) => :syntax)))
