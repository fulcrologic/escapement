(ns escapement.tools.builtin-test
  (:require
   [clojure.java.io :as io]
   [escapement.tools.builtin :as builtin]
   [escapement.tools.protocol :as tp]
   [fulcro-spec.core :refer [=> assertions component specification]])
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
                               "non-error" (:is-error r) => false
                               "line 1 numbered" (:result r) => "     1\thi there\n")))
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
                                  r (tp/dispatch reg :fs/edit {:path        p :old-string "x" :new-string "Z"
                                                               :replace-all true})]
                              (assertions
                               "non-error" (:is-error r) => false
                               "every occurrence replaced" (slurp p) => "Z Z Z y Z"
                               "replacement count in message"
                               (boolean (re-find #"4 replacements" (:result r))) => true)))

                 (component "old=new is rejected"
                            (let [p (tmp-file dir "e.txt" "hello")
                                  r (tp/dispatch reg :fs/edit {:path p :old-string "hello" :new-string "hello"})]
                              (assertions
                               "is-error" (:is-error r) => true
                               "file is unchanged" (slurp p) => "hello")))))

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
                               "non-error" (:is-error r) => false
                               "both edits applied" (slurp p) => "(defn FOO [x] x)\n(defn BAR [y] y)\n"
                               "result mentions count" (boolean (re-find #"2 edits" (:result r))) => true)))

                 (component "any failing edit aborts the whole batch — file unchanged"
                            (let [original "(defn foo [x] x)\n"
                                  p        (tmp-file dir "m2.clj" original)
                                  r        (tp/dispatch reg :fs/multi-edit
                                                        {:path  p
                                                         :edits [{:old-string "foo" :new-string "FOO"}
                                                                 {:old-string "nope" :new-string "X"}]})]
                              (assertions
                               "is-error" (:is-error r) => true
                               "file content was not modified on abort" (slurp p) => original
                               "failure message identifies the edit index"
                               (boolean (re-find #"edit #2 of 2" (:result r))) => true)))

                 (component "later edit can operate on the output of an earlier edit"
                            (let [p (tmp-file dir "m3.txt" "A")
                                  r (tp/dispatch reg :fs/multi-edit
                                                 {:path  p
                                                  :edits [{:old-string "A" :new-string "AB"}
                                                          {:old-string "AB" :new-string "ABC"}]})]
                              (assertions
                               "non-error" (:is-error r) => false
                               "edits chain through" (slurp p) => "ABC")))))

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
                            (let [r (tp/dispatch reg :fs/glob {:pattern "**/*.cljc" :cwd dir :by-mtime false})]
                              (assertions
                               "non-error" (:is-error r) => false
                               "matches b.cljc" (clojure.string/includes? (:result r) "/b.cljc") => true
                               "matches sub/d.cljc" (clojure.string/includes? (:result r) "/sub/d.cljc") => true
                               "skips .clj" (clojure.string/includes? (:result r) "a.clj\n") => false
                               "skips .txt" (clojure.string/includes? (:result r) "c.txt") => false)))
                 (component "no matches yields the [no matches] sentinel"
                            (let [r (tp/dispatch reg :fs/glob {:pattern "**/*.nope" :cwd dir})]
                              (assertions
                               "non-error" (:is-error r) => false
                               "no matches" (:result r) => "[no matches]")))))

(specification ":fs/grep"
               (let [reg (builtin/new-builtin-registry)
                     dir (tmp-dir)]
                 (tmp-file dir "one.clj" "(defn alpha [] :a)\n(defn beta [] :b)\n")
                 (tmp-file dir "two.txt" "alpha-not-a-clj-file")
                 (component "files-with-matches (default) returns matching paths only"
                            (let [r (tp/dispatch reg :fs/grep {:pattern "defn alpha" :path dir})]
                              (assertions
                               "non-error" (:is-error r) => false
                               "lists one.clj" (clojure.string/includes? (:result r) "/one.clj") => true)))
                 (component "content mode emits numbered match lines"
                            (let [r (tp/dispatch reg :fs/grep {:pattern     "defn (alpha|beta)"
                                                               :path        dir
                                                               :output-mode "content"})]
                              (assertions
                               "non-error" (:is-error r) => false
                               "alpha hit" (boolean (re-find #":\d+:.*alpha" (:result r))) => true
                               "beta hit" (boolean (re-find #":\d+:.*beta" (:result r))) => true)))
                 (component "no matches returns [no matches]"
                            (let [r (tp/dispatch reg :fs/grep {:pattern "this-pattern-does-not-occur" :path dir})]
                              (assertions
                               "non-error" (:is-error r) => false
                               "sentinel" (:result r) => "[no matches]")))))

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
                               (boolean (re-find #"timed out" (:result r))) => true)))

                 (component "honors the bound base-dir as the shell working directory (4-arg dispatch)"
                            (let [base    (tmp-dir)
                                  pwd-of  (fn [r] (clojure.string/trim (first (clojure.string/split (:result r) #"\n\[exit"))))
                                  in-base (pwd-of (tp/dispatch reg :shell/run {:command "pwd"} base))
                                  unbound (pwd-of (tp/dispatch reg :shell/run {:command "pwd"}))]
                              (assertions
                               "a base-bound run executes in the base-dir"
                               (.getCanonicalPath (io/file in-base)) => (.getCanonicalPath (io/file base))
                               "an unbound run does NOT execute in the base-dir (base-dir is what redirects it)"
                               (= (.getCanonicalPath (io/file unbound)) (.getCanonicalPath (io/file base))) => false)))))

(specification "R3: session-relative tool paths"
               (let [reg         (builtin/new-builtin-registry)
                     session-dir (tmp-dir)
                     cwd         (System/getProperty "user.dir")
                     rel         "notes/todo.txt"]
                 (component "relative :fs/write lands under session-dir, NOT cwd, via 4-arg base-dir"
                            (let [r          (tp/dispatch reg :fs/write
                                                          {:path rel :content "session-relative"}
                                                          session-dir)
                                  under-sess (io/file session-dir rel)
                                  under-cwd  (io/file cwd rel)]
                              (assertions
                               "non-error" (:is-error r) => false
                               "file exists under session-dir" (.exists under-sess) => true
                               "file does NOT exist under cwd" (.exists under-cwd) => false
                               ":is-error false implies file present"
                               (and (false? (:is-error r)) (.exists under-sess)) => true
                               "resolved-path is the absolute session path"
                               (:resolved-path r) => (.getAbsolutePath under-sess)
                               "content written correctly" (slurp under-sess) => "session-relative")))
                 (component "relative :fs/read resolves the same session path"
                            (let [r (tp/dispatch reg :fs/read {:path rel} session-dir)]
                              (assertions
                               "non-error" (:is-error r) => false
                               "content read back"
                               (clojure.string/includes? (:result r) "session-relative") => true
                               "resolved-path present"
                               (:resolved-path r) => (.getAbsolutePath (io/file session-dir rel)))))
                 (component "relative :fs/edit resolves under session-dir"
                            (let [r (tp/dispatch reg :fs/edit
                                                 {:path rel :old-string "session" :new-string "SESSION"}
                                                 session-dir)]
                              (assertions
                               "non-error" (:is-error r) => false
                               "edit applied to session file"
                               (slurp (io/file session-dir rel)) => "SESSION-relative"
                               "resolved-path is absolute session path"
                               (:resolved-path r) => (.getAbsolutePath (io/file session-dir rel)))))
                 (component "absolute paths are unaffected by base-dir"
                            (let [abs-dir (tmp-dir)
                                  abs     (.getAbsolutePath (io/file abs-dir "abs.txt"))
                                  w       (tp/dispatch reg :fs/write {:path abs :content "absolute"}
                                                       session-dir)
                                  rd      (tp/dispatch reg :fs/read {:path abs} session-dir)]
                              (assertions
                               "write non-error" (:is-error w) => false
                               "wrote to the absolute path verbatim" (slurp abs) => "absolute"
                               "resolved-path is the absolute path verbatim, NOT under session-dir"
                               (:resolved-path w) => abs
                               "not nested under session-dir"
                               (.exists (io/file session-dir "abs.txt")) => false
                               "read resolves the absolute path"
                               (clojure.string/includes? (:result rd) "absolute") => true)))
                 (component "registry metadata supplies base-dir to 3-arg dispatch"
                            (let [reg2 (builtin/new-builtin-registry)
                                  sd   (tmp-dir)]
                              (alter-meta! reg2 assoc :escapement/base-dir sd)
                              (let [r (tp/dispatch reg2 :fs/write
                                                   {:path "meta/x.txt" :content "via-meta"})]
                                (assertions
                                 "non-error" (:is-error r) => false
                                 "resolved under metadata base-dir"
                                 (.exists (io/file sd "meta/x.txt")) => true
                                 "resolved-path reflects metadata base-dir"
                                 (:resolved-path r) => (.getAbsolutePath (io/file sd "meta/x.txt"))))))
                 (component "no base-dir falls back to process cwd (back-compat)"
      ;; With no 4-arg base-dir and no registry metadata,
      ;; a relative path resolves exactly as plain
      ;; `(io/file path)` does — i.e. against the process
      ;; working directory (back-compat behaviour).
                            (let [r (tp/dispatch reg :fs/read {:path "deps.edn"})]
                              (assertions
                               "non-error reading a cwd-relative file" (:is-error r) => false
                               "resolved-path matches plain io/file resolution"
                               (:resolved-path r) => (.getAbsolutePath (io/file "deps.edn"))
                               "resolved-path is absolute and ends with deps.edn"
                               (boolean (re-find #"/deps\.edn$" (:resolved-path r))) => true)))))
