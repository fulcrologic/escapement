(ns escapement.config-test
  (:require
    [clojure.java.io :as io]
    [escapement.config :as config]
    [fulcro-spec.core :refer [=> assertions component specification]])
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
      (System/setProperty "user.dir" cwd)
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
      (System/setProperty "user.dir" cwd)
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
      (spit (io/file cwd ".escapement.edn") (pr-str {:debug {:auto-pause? true}}))
      (System/setProperty "user.home" home)
      (System/setProperty "user.dir" cwd)
      (let [cfg (config/load-config)]
        (assertions
          "tolerates a broken user file by treating it as empty"
          (get-in cfg [:debug :auto-pause?]) => true)))))

(specification "find-project-config — walk-up discovery"
  (component "finds .escapement.edn at start dir"
    (let [root (tmp-dir)]
      (spit (io/file root ".escapement.edn") (pr-str {}))
      (assertions
        "returns the file at root"
        (.getPath (config/find-project-config root))
        => (.getPath (io/file root ".escapement.edn")))))

  (component "walks up parents until found"
    (let [root (tmp-dir)
          sub  (io/file root "a" "b")]
      (.mkdirs sub)
      (spit (io/file root ".escapement.edn") (pr-str {}))
      (assertions
        "finds the parent's config"
        (.getPath (config/find-project-config (.getPath sub)))
        => (.getPath (io/file root ".escapement.edn")))))

  (component "returns nil when no file exists in tree"
    (let [root (tmp-dir)]
      (assertions
        "no config found"
        (config/find-project-config root) => nil))))

(specification "load-project-config — schema + normalization"
  (component "valid config with all keys"
    (let [root (tmp-dir)]
      (spit (io/file root ".escapement.edn")
        (pr-str {:source-paths  ["src" "charts"]
                 :deps          {'hiccup/hiccup {:mvn/version "2.0.0-RC3"}}
                 :tools-ns      'my.app/register
                 :work-dir      "transcripts"
                 :default-chart 'my.app.charts.hello/agent}))
      (let [{:keys [config root path]} (config/load-project-config root)]
        (assertions
          "tools-ns scalar is normalized to vector"
          (:tools-ns config) => '[my.app/register]
          "source-paths preserved"
          (:source-paths config) => ["src" "charts"]
          "root is the parent dir of the config file"
          (.getPath ^java.io.File root) => (.getParent ^java.io.File path)))))

  (component "tools-ns vector form is preserved"
    (let [root (tmp-dir)]
      (spit (io/file root ".escapement.edn")
        (pr-str {:tools-ns '[a/b c/d]}))
      (assertions
        "vector passes through"
        (:tools-ns (:config (config/load-project-config root)))
        => '[a/b c/d])))

  (component "missing file returns nil"
    (assertions
      "nil result for empty tree"
      (config/load-project-config (tmp-dir)) => nil))

  (component "malformed schema throws"
    (let [root (tmp-dir)]
      (spit (io/file root ".escapement.edn")
        (pr-str {:source-paths "not-a-vector"}))
      (assertions
        "ex-info with humanized errors"
        (try (config/load-project-config root) :ok
             (catch clojure.lang.ExceptionInfo e
               (-> (ex-data e) :errors some?)))
        => true)))

  (component "unknown keys rejected (closed schema)"
    (let [root (tmp-dir)]
      (spit (io/file root ".escapement.edn")
        (pr-str {:source-paths ["src"] :nope true}))
      (assertions
        "throws on unknown key"
        (try (config/load-project-config root) :ok
             (catch clojure.lang.ExceptionInfo _ :err))
        => :err)))

  ;; Regression: the closed schema must permit the documented
  ;; LLM overlays in a *project* .escapement.edn. Previously
  ;; these tripped the closed-map check and `escapement run`
  ;; died before the chart loaded, so the surface documented in
  ;; CHANGELOG/Guide/clj_refactor was non-functional.
  ;; Mandatory-aliases model: :llm/preferences is a vector of ALIAS KEYWORDS
  ;; and :llm/ratings is keyed by ALIAS KEYWORD; both reference :llm/aliases.
  (component "alias-keyword :llm/preferences + alias-keyed :llm/ratings survive validation (flat keys)"
    (let [root    (tmp-dir)
          aliases {:opus [{:provider :anthropic :model "claude-opus-4-7"}]
                   :gpt  [{:provider :openai :model "gpt-5"}]}
          prefs   [:opus :gpt]
          rate    {:opus {:clojure 10 :tool-calling 9}}]
      (spit (io/file root ".escapement.edn")
        (pr-str {:llm/aliases     aliases
                 :llm/preferences prefs
                 :llm/ratings     rate}))
      (let [cfg (:config (config/load-project-config root))]
        (assertions
          "load-project-config does not throw and preserves the keys"
          (:llm/preferences cfg) => prefs
          (:llm/ratings cfg) => rate
          (:llm/aliases cfg) => aliases))))

  (component "alias-keyword prefs resolve against the built-in default aliases (no :llm/aliases configured)"
    (let [root (tmp-dir)]
      (spit (io/file root ".escapement.edn")
        (pr-str {:llm/preferences [:default-opus :default-gpt]}))
      (assertions
        "preferences referencing built-in default aliases load"
        (:llm/preferences (:config (config/load-project-config root)))
        => [:default-opus :default-gpt])))

  (component "R2/R3/R4: invalid overlay shapes are categorized errors, never a passthrough"
    ;; `err!` returns :err when load rejects (with humanized :errors in ex-data
    ;; — whether from the structural malli pass for the old pair/string shapes,
    ;; or from the cross-key referential check for dangling/unknown keywords).
    (let [root  (tmp-dir)
          err!  (fn [m]
                  (spit (io/file root ".escapement.edn") (pr-str m))
                  (try (config/load-project-config root) :ok
                       (catch clojure.lang.ExceptionInfo e
                         (if (-> (ex-data e) :errors some?) :err :no-errors))))
          ;; The referential check produces a vector of message strings; assert
          ;; the dangling/unknown cases carry a clear, alias-naming message.
          msgs! (fn [m]
                  (spit (io/file root ".escapement.edn") (pr-str m))
                  (try (config/load-project-config root) []
                       (catch clojure.lang.ExceptionInfo e (-> (ex-data e) :errors))))
          opus  {:opus [{:provider :anthropic :model "claude-opus-4-7"}]}]
      (assertions
        "old pair-shaped :llm/preferences entry rejected (R2/R4)"
        (err! {:llm/aliases opus :llm/preferences [{:provider :anthropic :model "claude-opus-4-7"}]})
        => :err
        "string-keyed :llm/ratings rejected (R3/R4)"
        (err! {:llm/aliases opus :llm/ratings {"claude-opus-4-7" {:clojure 10}}})
        => :err
        "dangling :llm/preferences keyword rejected with an alias-naming message (R2)"
        (boolean (some #(re-find #":llm/preferences references unknown alias" (str %))
                   (msgs! {:llm/aliases opus :llm/preferences [:opus :nope]})))
        => true
        "unknown :llm/ratings alias key rejected with an alias-naming message (R3)"
        (boolean (some #(re-find #":llm/ratings references unknown alias" (str %))
                   (msgs! {:llm/aliases opus :llm/ratings {:nope {:clojure 10}}})))
        => true)))

  (component ":llm/aliases — valid multi-target vector loads"
    (let [root    (tmp-dir)
          aliases {:kimi2.6 [{:provider :baseten :model "moonshotai/Kimi-K2.6"
                              :temperature 0.7 :thinking {:type :enabled :budget-tokens 4096}}
                             {:provider :fireworks-ai :model "accounts/fireworks/models/kimi-k2p6"}]
                   :fast    [{:provider :ollama :model "glm-5.1"}]}]
      (spit (io/file root ".escapement.edn")
        (pr-str {:llm/aliases aliases}))
      (let [cfg (:config (config/load-project-config root))]
        (assertions
          "alias map preserved verbatim"
          (:llm/aliases cfg) => aliases))))

  (component ":llm/aliases — malformed entries rejected"
    (let [root (tmp-dir)
          bad! (fn [v]
                 (spit (io/file root ".escapement.edn") (pr-str {:llm/aliases v}))
                 (try (config/load-project-config root) :ok
                      (catch clojure.lang.ExceptionInfo _ :err)))]
      (assertions
        "value not a vector"
        (bad! {:x {:provider :ollama :model "m"}}) => :err
        "empty vector"
        (bad! {:x []}) => :err
        "target missing :provider"
        (bad! {:x [{:model "m"}]}) => :err
        "target missing :model"
        (bad! {:x [{:provider :ollama}]}) => :err
        "target not a map"
        (bad! {:x ["m"]}) => :err
        "temperature out of (0,1] range"
        (bad! {:x [{:provider :ollama :model "m" :temperature 2}]}) => :err)))

  (component "config WITHOUT :llm/aliases loads unchanged"
    (let [root (tmp-dir)]
      (spit (io/file root ".escapement.edn")
        (pr-str {:source-paths ["src"]}))
      (let [cfg (:config (config/load-project-config root))]
        (assertions
          "no :llm/aliases key present"
          (contains? cfg :llm/aliases) => false
          "other keys intact"
          (:source-paths cfg) => ["src"]))))

  (component ":llm/aliases — deep-merge: project overrides global per alias key"
    (let [global {:llm/aliases {:kimi2.6 [{:provider :baseten :model "moonshotai/Kimi-K2.6"}]
                                :fast    [{:provider :ollama :model "glm-5.1"}]}}
          proj   {:llm/aliases {:kimi2.6 [{:provider :fireworks-ai :model "accounts/fireworks/models/kimi-k2p6"}]}}
          merged (config/deep-merge global proj)]
      (assertions
        "project alias replaces global same-key (vectors are opaque)"
        (get-in merged [:llm/aliases :kimi2.6])
        => [{:provider :fireworks-ai :model "accounts/fireworks/models/kimi-k2p6"}]
        "global-only alias preserved"
        (get-in merged [:llm/aliases :fast])
        => [{:provider :ollama :model "glm-5.1"}])))

  (component "documented :llm overlays survive validation (nested map form, alias-keyed)"
    (let [root (tmp-dir)
          rate {:gpt {:intelligence 9}}]
      (spit (io/file root ".escapement.edn")
        (pr-str {:llm {:aliases {:gpt [{:provider :openai :model "gpt-5"}]}
                       :ratings rate}}))
      (let [cfg (:config (config/load-project-config root))]
        (assertions
          "nested [:llm :ratings] passes the closed schema (alias-keyed)"
          (get-in cfg [:llm :ratings]) => rate)))))

(specification "resolve-path"
  (let [root (tmp-dir)]
    (assertions
      "relative path resolves against root"
      (.getPath (config/resolve-path root "charts"))
      => (.getPath (io/file root "charts"))

      "absolute path passes through"
      (.getPath (config/resolve-path root "/tmp/abs"))
      => "/tmp/abs")))
