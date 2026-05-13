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
               (let [reg (builtin/new-builtin-registry)
                     dir (tmp-dir)]
                 (component "single-line file returns cat -n style numbered content"
                            (let [path (tmp-file dir "hello.txt" "hi there")
                                  r    (tp/dispatch reg :fs/read {:path path})]
                              (assertions
                               "non-error"          (:is-error r) => false
                               "line 1 numbered"    (:result r)   => "     1\thi there\n")))
                 (component "multi-line file numbers every line in order"
                            (let [path (tmp-file dir "multi.txt" "one\ntwo\nthree")
                                  r    (tp/dispatch reg :fs/read {:path path})]
                              (assertions
                               "all three lines numbered"
                               (:result r)
                               => "     1\tone\n     2\ttwo\n     3\tthree\n")))
                 (component "offset + limit paginate"
                            (let [content (clojure.string/join "\n" (mapv str (range 1 11)))
                                  path    (tmp-file dir "ten.txt" content)
                                  r       (tp/dispatch reg :fs/read {:path path :offset 4 :limit 3})]
                              (assertions
                               "shows lines 4..6 only"
                               (clojure.string/includes? (:result r) "     4\t4\n     5\t5\n     6\t6\n") => true
                               "trailing notice points to the next offset"
                               (clojure.string/includes? (:result r) "[4 more lines; read with :offset 7]") => true)))
                 (component "offset past end is a non-error empty result with explanation"
                            (let [path (tmp-file dir "small.txt" "a\nb")
                                  r    (tp/dispatch reg :fs/read {:path path :offset 9})]
                              (assertions
                               "non-error"
                               (:is-error r) => false
                               "result explains the situation"
                               (clojure.string/includes? (:result r) "past the last line") => true)))
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
                               "file is updated" (slurp p) => "foo BAR baz")))

                 (component "replace-all replaces every occurrence and reports count"
                            (let [p (tmp-file dir "d.txt" "x x x y x")
                                  r (tp/dispatch reg :fs/edit {:path p :old-string "x" :new-string "Z"
                                                               :replace-all true})]
                              (assertions
                               "non-error"                       (:is-error r) => false
                               "every occurrence replaced"       (slurp p) => "Z Z Z y Z"
                               "replacement count in message"
                               (boolean (re-find #"4 replacements" (:result r))) => true)))

                 (component "old=new is rejected"
                            (let [p (tmp-file dir "e.txt" "hello")
                                  r (tp/dispatch reg :fs/edit {:path p :old-string "hello" :new-string "hello"})]
                              (assertions
                               "is-error"           (:is-error r) => true
                               "file is unchanged"  (slurp p) => "hello")))))

(specification ":fs/multi-edit"
               (let [reg (builtin/new-builtin-registry)
                     dir (tmp-dir)]
                 (component "applies every edit atomically in order"
                            (let [p (tmp-file dir "m1.clj" "(defn foo [x] x)\n(defn bar [y] y)\n")
                                  r (tp/dispatch reg :fs/multi-edit
                                                 {:path  p
                                                  :edits [{:old-string "foo" :new-string "FOO"}
                                                          {:old-string "bar" :new-string "BAR"}]})]
                              (assertions
                               "non-error"               (:is-error r) => false
                               "both edits applied"      (slurp p) => "(defn FOO [x] x)\n(defn BAR [y] y)\n"
                               "result mentions count"   (boolean (re-find #"2 edits" (:result r))) => true)))

                 (component "any failing edit aborts the whole batch — file unchanged"
                            (let [original "(defn foo [x] x)\n"
                                  p (tmp-file dir "m2.clj" original)
                                  r (tp/dispatch reg :fs/multi-edit
                                                 {:path  p
                                                  :edits [{:old-string "foo" :new-string "FOO"}
                                                          {:old-string "nope" :new-string "X"}]})]
                              (assertions
                               "is-error"                                  (:is-error r) => true
                               "file content was not modified on abort"    (slurp p) => original
                               "failure message identifies the edit index"
                               (boolean (re-find #"edit #2 of 2" (:result r))) => true)))

                 (component "later edit can operate on the output of an earlier edit"
                            (let [p (tmp-file dir "m3.txt" "A")
                                  r (tp/dispatch reg :fs/multi-edit
                                                 {:path  p
                                                  :edits [{:old-string "A" :new-string "AB"}
                                                          {:old-string "AB" :new-string "ABC"}]})]
                              (assertions
                               "non-error"               (:is-error r) => false
                               "edits chain through"     (slurp p) => "ABC")))))

(specification ":fs/glob"
               (let [reg (builtin/new-builtin-registry)
                     dir (tmp-dir)]
                 (tmp-file dir "a.clj" "(ns a)")
                 (tmp-file dir "b.cljc" "(ns b)")
                 (tmp-file dir "c.txt" "no")
                 (let [sub (str dir "/sub")]
                   (.mkdirs (io/file sub))
                   (tmp-file sub "d.cljc" "(ns d)"))
                 (component "pattern matches recursively"
                            (let [r (tp/dispatch reg :fs/glob {:pattern "**/*.cljc" :cwd dir :by-mtime? false})]
                              (assertions
                               "non-error"          (:is-error r) => false
                               "matches b.cljc"     (clojure.string/includes? (:result r) "/b.cljc")  => true
                               "matches sub/d.cljc" (clojure.string/includes? (:result r) "/sub/d.cljc") => true
                               "skips .clj"         (clojure.string/includes? (:result r) "a.clj\n") => false
                               "skips .txt"         (clojure.string/includes? (:result r) "c.txt") => false)))
                 (component "no matches yields the [no matches] sentinel"
                            (let [r (tp/dispatch reg :fs/glob {:pattern "**/*.nope" :cwd dir})]
                              (assertions
                               "non-error"     (:is-error r) => false
                               "no matches"    (:result r)   => "[no matches]")))))

(specification ":fs/grep"
               (let [reg (builtin/new-builtin-registry)
                     dir (tmp-dir)]
                 (tmp-file dir "one.clj" "(defn alpha [] :a)\n(defn beta [] :b)\n")
                 (tmp-file dir "two.txt" "alpha-not-a-clj-file")
                 (component "files-with-matches (default) returns matching paths only"
                            (let [r (tp/dispatch reg :fs/grep {:pattern "defn alpha" :path dir})]
                              (assertions
                               "non-error"         (:is-error r) => false
                               "lists one.clj"     (clojure.string/includes? (:result r) "/one.clj") => true)))
                 (component "content mode emits numbered match lines"
                            (let [r (tp/dispatch reg :fs/grep {:pattern "defn (alpha|beta)"
                                                               :path        dir
                                                               :output-mode "content"})]
                              (assertions
                               "non-error"      (:is-error r) => false
                               "alpha hit"      (boolean (re-find #":\d+:.*alpha" (:result r))) => true
                               "beta hit"       (boolean (re-find #":\d+:.*beta"  (:result r))) => true)))
                 (component "no matches returns [no matches]"
                            (let [r (tp/dispatch reg :fs/grep {:pattern "this-pattern-does-not-occur" :path dir})]
                              (assertions
                               "non-error"  (:is-error r) => false
                               "sentinel"   (:result r)   => "[no matches]")))))

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
