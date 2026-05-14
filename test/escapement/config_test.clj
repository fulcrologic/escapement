(ns escapement.config-test
  (:require
   [clojure.java.io :as io]
   [escapement.config :as config]
   [fulcro-spec.core :refer [specification component assertions =>]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir [] (str (Files/createTempDirectory "escapement-cfg" (into-array FileAttribute []))))

(specification "deep-merge"
               (assertions
                "merges flat maps with later wins"
                (config/deep-merge {:a 1 :b 2} {:b 99 :c 3}) => {:a 1 :b 99 :c 3}
                "recurses into nested maps"
                (config/deep-merge {:a {:x 1 :y 2}} {:a {:y 99 :z 3}}) => {:a {:x 1 :y 99 :z 3}}
                "treats vectors as opaque values (later wins)"
                (config/deep-merge {:xs [1 2]} {:xs [3]}) => {:xs [3]}
                "returns nil for empty input"
                (config/deep-merge) => nil
                "returns single map unchanged"
                (config/deep-merge {:a 1}) => {:a 1}
                "ignores nil arguments"
                (config/deep-merge nil {:a 1} nil) => {:a 1}))

(specification "expand-command"
               (assertions
                "substitutes {{path}} with shell-quoted path"
                (config/expand-command "open -a 'Foo' {{path}}" "/tmp/x.svg")
                => "open -a 'Foo' '/tmp/x.svg'"

                "appends shell-quoted path when template lacks {{path}}"
                (config/expand-command "open" "/tmp/x.svg")
                => "open '/tmp/x.svg'"

                "escapes embedded single quotes safely"
                (config/expand-command "cat {{path}}" "/tmp/it's.txt")
                => "cat '/tmp/it'\\''s.txt'"))

(specification "viewer-for"
               (let [cfg {:viewers {"md"      "vim {{path}}"
                                    "svg"     "open -a 'Chrome' {{path}}"
                                    "default" :internal}}]
                 (assertions
                  "matches by extension (case-insensitive)"
                  (config/viewer-for cfg "notes.MD") => "vim {{path}}"

                  "uses :default when no extension matches"
                  (config/viewer-for cfg "thing.bin") => :internal

                  "falls back to :internal when no viewers configured"
                  (config/viewer-for {} "x.png") => :internal

                  "treats path with no extension via default"
                  (config/viewer-for cfg "README") => :internal)))

(specification "load-config"
               (component "with no config files present"
    ;; Point both env vars at empty tmp dirs so neither file exists.
                          (let [home (tmp-dir)
                                cwd  (tmp-dir)]
                            (System/setProperty "user.home" home)
                            (System/setProperty "user.dir"  cwd)
                            (assertions
                             "returns nil/{} when neither file exists"
                             (or (config/load-config) {}) => {})))

               (component "with project config overriding user config"
                          (let [home (tmp-dir)
                                cwd  (tmp-dir)]
                            (spit (io/file home ".escapement.edn")
                                  (pr-str {:debug   {:auto-pause? false}
                                           :viewers {"md" "global-viewer"}}))
                            (spit (io/file cwd ".escapement.edn")
                                  (pr-str {:debug   {:auto-pause? true}
                                           :viewers {"svg" "project-viewer"}}))
                            (System/setProperty "user.home" home)
                            (System/setProperty "user.dir"  cwd)
                            (let [cfg (config/load-config)]
                              (assertions
                               "project value wins for overlapping nested keys"
                               (get-in cfg [:debug :auto-pause?]) => true

                               "user value preserved when not overridden"
                               (get-in cfg [:viewers "md"]) => "global-viewer"

                               "project-only entries are present"
                               (get-in cfg [:viewers "svg"]) => "project-viewer"))))

               (component "with malformed user config"
                          (let [home (tmp-dir)
                                cwd  (tmp-dir)]
                            (spit (io/file home ".escapement.edn") "{not edn")
                            (spit (io/file cwd ".escapement.edn")  (pr-str {:debug {:auto-pause? true}}))
                            (System/setProperty "user.home" home)
                            (System/setProperty "user.dir"  cwd)
                            (let [cfg (config/load-config)]
                              (assertions
                               "tolerates a broken user file by treating it as empty"
                               (get-in cfg [:debug :auto-pause?]) => true)))))
