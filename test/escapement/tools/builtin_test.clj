(ns escapement.tools.builtin-test
  (:require
   [clojure.java.io :as io]
   [escapement.tools.builtin :as builtin]
   [escapement.tools.protocol :as tp]
   [fulcro-spec.core :refer [specification assertions component =>]])
  (:import
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir []
  (str (Files/createTempDirectory "tools-test" (into-array FileAttribute []))))

(defn- tmp-file [dir name content]
  (let [f (io/file dir name)]
    (spit f content)
    (.getCanonicalPath f)))

(specification ":fs/read"
               (let [reg  (builtin/new-builtin-registry)
                     dir  (tmp-dir)
                     path (tmp-file dir "hello.txt" "hi there")]
                 (component "happy path returns file content"
                            (assertions
                             "content matches"
                             (tp/dispatch reg :fs/read {:path path})
                             => {:result "hi there" :is-error false}))
                 (component "missing file returns is-error"
                            (let [r (tp/dispatch reg :fs/read {:path (str dir "/missing.txt")})]
                              (assertions
                               "is-error true" (:is-error r) => true)))))

(specification ":fs/write"
               (let [reg  (builtin/new-builtin-registry)
                     dir  (tmp-dir)
                     path (str dir "/sub/dir/written.txt")
                     r    (tp/dispatch reg :fs/write {:path path :content "abc"})]
                 (assertions
                  "non-error result" (:is-error r) => false
                  "file is created with the content"
                  (slurp path) => "abc"
                  "result string mentions byte count"
                  (boolean (re-find #"wrote 3 bytes" (:result r))) => true)))

(specification ":fs/edit"
               (let [reg (builtin/new-builtin-registry)
                     dir (tmp-dir)]
                 (component "zero matches is an error"
                            (let [p (tmp-file dir "a.txt" "hello world")
                                  r (tp/dispatch reg :fs/edit {:path p :old-string "absent" :new-string "x"})]
                              (assertions
                               "is-error" (:is-error r) => true)))

                 (component "ambiguous (>1) matches is an error"
                            (let [p (tmp-file dir "b.txt" "aa aa")
                                  r (tp/dispatch reg :fs/edit {:path p :old-string "aa" :new-string "bb"})]
                              (assertions
                               "is-error" (:is-error r) => true
                               "file is unchanged on error"
                               (slurp p) => "aa aa")))

                 (component "single match replaces successfully"
                            (let [p (tmp-file dir "c.txt" "foo bar baz")
                                  r (tp/dispatch reg :fs/edit {:path p :old-string "bar" :new-string "BAR"})]
                              (assertions
                               "non-error" (:is-error r) => false
                               "file is updated" (slurp p) => "foo BAR baz")))))

(specification ":shell/run"
               (let [reg (builtin/new-builtin-registry)]
                 (component "happy path captures stdout and exit 0"
                            (let [r (tp/dispatch reg :shell/run {:command "echo hi"})]
                              (assertions
                               "non-error" (:is-error r) => false
                               "stdout captured"
                               (boolean (re-find #"hi" (:result r))) => true
                               "exit code reported"
                               (boolean (re-find #"\[exit 0\]" (:result r))) => true)))

                 (component "non-zero exit is an error"
                            (let [r (tp/dispatch reg :shell/run {:command "exit 7"})]
                              (assertions
                               "is-error" (:is-error r) => true
                               "exit code reported"
                               (boolean (re-find #"\[exit 7\]" (:result r))) => true)))

                 (component "timeout fires when command exceeds budget"
                            (let [r (tp/dispatch reg :shell/run {:command "sleep 2" :timeout-ms 100})]
                              (assertions
                               "is-error" (:is-error r) => true
                               "result mentions timeout"
                               (boolean (re-find #"timed out" (:result r))) => true)))))

(specification ":repl/eval"
               (let [reg (builtin/new-builtin-registry)]
                 (component "happy path evaluates an expression"
                            (assertions
                             "(+ 1 2) => 3"
                             (tp/dispatch reg :repl/eval {:code "(+ 1 2)"})
                             => {:result "3" :is-error false}))

                 (component "syntax error is caught"
                            (let [r (tp/dispatch reg :repl/eval {:code "(+ 1 "})]
                              (assertions
                               "is-error" (:is-error r) => true)))))
