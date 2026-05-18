(ns tools-sanity
  "Babashka sanity script. Builds the builtin tool registry and dispatches each
  tool against a trivial input. Prints PASS on success."
  (:require
   [clojure.java.io :as io]
   [escapement.tools.builtin :as builtin]
   [escapement.tools.protocol :as tp]))

(defn- fail! [msg]
  (println "FAIL:" msg)
  (System/exit 1))

(defn- expect-ok [tool-kw r]
  (when (:is-error r)
    (fail! (str tool-kw " unexpectedly errored: " (:result r))))
  (println "  " tool-kw "ok"))

(defn- expect-err [tool-kw r]
  (when-not (:is-error r)
    (fail! (str tool-kw " was expected to error but did not: " (:result r))))
  (println "  " tool-kw "errored as expected"))

(defn -main [& _]
  (let [reg (builtin/new-builtin-registry)
        dir (str (System/getProperty "java.io.tmpdir") "/dcch-tools-sanity-"
                 (System/currentTimeMillis))
        _   (.mkdirs (io/file dir))
        p   (str dir "/sample.txt")]
    (println "Tools sanity in" dir)

    (expect-ok :fs/write
               (tp/dispatch reg :fs/write {:path p :content "hello sanity"}))

    (expect-ok :fs/read
               (tp/dispatch reg :fs/read {:path p}))

    (expect-ok :fs/edit
               (tp/dispatch reg :fs/edit
                            {:path p :old-string "sanity" :new-string "WORLD"}))

    (let [on-disk (slurp p)]
      (when (not= on-disk "hello WORLD")
        (fail! (str ":fs/edit did not produce expected content: " (pr-str on-disk)))))

    (expect-ok :shell/run
               (tp/dispatch reg :shell/run {:command "echo hi"}))

    (expect-err :shell/run
                (tp/dispatch reg :shell/run {:command "exit 3"}))

    (println "PASS: tools_sanity — all four builtin tools dispatched correctly")
    (System/exit 0)))

(-main)
