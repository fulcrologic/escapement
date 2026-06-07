(ns escapement.cli-test
  (:require
    [clojure.java.io :as io]
    [escapement.cli :as cli]
    [escapement.llm.providers :as providers]
    [fulcro-spec.core :refer [=> assertions component specification]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir [] (str (Files/createTempDirectory "escapement-cli" (into-array FileAttribute []))))

(defn- index-of [xs x]
  (first (keep-indexed (fn [idx item] (when (= item x) idx)) xs)))

(specification "parse-param"
  (assertions
    "splits key=value and EDN-reads the value"
    (cli/parse-param "a=1") => [:a 1]
    (cli/parse-param "max=5") => [:max 5]
    (cli/parse-param "flag=true") => [:flag true]
    (cli/parse-param "kw=:x") => [:kw :x]
    "unparseable EDN falls back to a plain string"
    (cli/parse-param "name=alice") => [:name "alice"]
    "quoted strings round-trip via EDN"
    (cli/parse-param "name=\"alice\"") => [:name "alice"]
    "key may contain dashes"
    (cli/parse-param "max-iters=5") => [:max-iters 5]
    "missing = yields nil"
    (cli/parse-param "noequals") => nil
    "empty key yields nil"
    (cli/parse-param "=1") => nil
    "value may be empty string"
    (cli/parse-param "k=") => [:k nil]))                    ;; (edn/read-string "") -> nil

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
        cfg      {:source-paths  ["charts"]
                  :deps          '{hiccup/hiccup {:mvn/version "2.0.0-RC3"}
                                   pinned/lib    {:mvn/version "1.0.0"}}
                  :tools-ns      '[a.tools/reg]
                  :work-dir      "transcripts"
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
                  {:work-dir     "/abs/wd"
                   :source-paths "extra:also"
                   :tools-ns     ["b.tools/reg"]
                   :deps         "{pinned/lib {:mvn/version \"9.9.9\"}}"}
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

(specification "provider backend wiring"
  (component "explicit Ollama backend uses the Ollama Cloud OpenAI-compatible endpoint"
    (with-redefs [cli/build-openai-backend       identity
                  providers/build-openai-backend identity]
      (let [result (#'cli/make-backend {:backend "ollama"
                                        :model   "kimi-k2.5"})]
        (assertions
          "base URL"
          (get-in result [:backend :base-url]) => "https://ollama.com/v1"
          "default model"
          (get-in result [:backend :default-model]) => "kimi-k2.5"
          "fallback list"
          (:default-models result) => ["kimi-k2.5"]))))

  (component "OpenCode Go chooses OpenAI-compatible wiring for GLM/Kimi/MIMO models"
    (with-redefs [providers/build-openai-backend identity
                  providers/build-api-backend    identity]
      (let [backend (#'providers/build-opencode-go-backend {:api-key "k" :model "glm-5"})]
        (assertions
          "base URL"
          (:base-url backend) => "https://opencode.ai/zen/go/v1"
          "default model"
          (:default-model backend) => "glm-5"))))

  (component "explicit OpenCode Go backend honors --api-base-url"
    (with-redefs [cli/build-openai-backend       identity
                  cli/build-api-backend          identity
                  providers/build-openai-backend identity
                  providers/build-api-backend    identity]
      (let [openai-backend (#'cli/make-backend {:backend      "opencode-go"
                                                :model        "glm-5"
                                                :api-base-url "https://proxy.example/v1"})
            api-backend    (#'cli/make-backend {:backend      "opencode-go"
                                                :model        "minimax-m2.7"
                                                :api-base-url "https://proxy.example/anthropic"})]
        (assertions
          "OpenAI-shaped route uses override"
          (get-in openai-backend [:backend :base-url]) => "https://proxy.example/v1"
          "Anthropic-shaped route uses override"
          (get-in api-backend [:backend :base-url]) => "https://proxy.example/anthropic"))))

  (component "OpenCode Go chooses Anthropic-compatible wiring for MiniMax models"
    (with-redefs [providers/build-openai-backend identity
                  providers/build-api-backend    identity]
      (let [backend (#'providers/build-opencode-go-backend {:api-key "k" :model "minimax-m2.7"})]
        (assertions
          "base URL"
          (:base-url backend) => "https://opencode.ai/zen/go"
          "auth mode"
          (:auth-mode backend) => :x-api-key
          "default model"
          (:default-model backend) => "minimax-m2.7"))))

  (component "auto-detection keeps z.ai glm routing ahead of new hosted gateways"
    (with-redefs [providers/nonblank-env (fn [k]
                                           (when (contains? #{"ZAI_API_KEY" "OLLAMA_API_KEY" "OPENCODE_GO_API_KEY"} k)
                                             (str k "-value")))]
      (let [kinds (mapv :kind (#'providers/detect-available-credentials))]
        (assertions
          "z.ai route is present"
          (some? (index-of kinds :zai)) => true
          "OpenCode Go route is present"
          (some? (index-of kinds :opencode-go-openai)) => true
          "Ollama route is present"
          (some? (index-of kinds :ollama)) => true
          "z.ai keeps precedence for glm-*"
          (< (index-of kinds :zai)
            (index-of kinds :opencode-go-openai)
            (index-of kinds :ollama)) => true)))))

(specification "resolve-log-level (R4)"
  (component "explicit --log-level wins (case-insensitive)"
    (assertions
      "debug"
      (cli/resolve-log-level {:log-level "debug"}) => [:level :debug]
      "INFO upper-case"
      (cli/resolve-log-level {:log-level "INFO"}) => [:level :info]
      "warn with surrounding whitespace"
      (cli/resolve-log-level {:log-level " warn "}) => [:level :warn]
      "error"
      (cli/resolve-log-level {:log-level "Error"}) => [:level :error]
      "explicit wins even under --no-tui"
      (cli/resolve-log-level {:log-level "debug" :no-tui true}) => [:level :debug]
      "unrecognized value -> :error tag"
      (first (cli/resolve-log-level {:log-level "loud"})) => :error))
  (component "defaults"
    (assertions
      "headless (--no-tui) with no explicit flag -> INFO"
      (cli/resolve-log-level {:no-tui true}) => [:level :info]
      "interactive with no explicit flag -> nil (library default preserved)"
      (cli/resolve-log-level {}) => [:level nil]
      "explicit flag overrides headless default"
      (cli/resolve-log-level {:no-tui true :log-level "warn"}) => [:level :warn])))

(specification "log routing toggle (#3 clean teardown)"
  ;; While a TUI owns the terminal, logs must be file-routed (console appender
  ;; OFF) so no library DEBUG line scribbles over the alt-screen. After the
  ;; alt-screen is exited the console appender comes back so ordinary CLI
  ;; errors print. Verify both transitions flip the appender enabled flags.
  (require '[taoensso.timbre :as timbre])
  (let [dir       (str (Files/createTempDirectory "escapement-cli-log" (into-array FileAttribute [])))
        path      (str dir "/escapement.log")
        cfg-var   (resolve 'taoensso.timbre/*config*)
        set-cfg!  (resolve 'taoensso.timbre/set-config!)
        enabled?  (fn [k] (get-in (var-get cfg-var) [:appenders k :enabled?]))
        orig      (var-get cfg-var)]
    (try
      (let [ret (cli/route-logs-to-file! path)]
        (assertions
          "route-logs-to-file! returns the path"
          ret => path
          "console (:println) appender is disabled while the TUI owns the tty"
          (enabled? :println) => false
          "file appender is enabled (logs land in the session log)"
          (enabled? :file) => true))
      (cli/restore-console-logging!)
      (assertions
        "after alt-screen exit the console appender is re-enabled"
        (enabled? :println) => true
        "and the file appender is disabled again"
        (enabled? :file) => false)
      (finally
        ;; restore timbre to its pre-test config so other specs are unaffected
        (set-cfg! orig)))))

(specification "read-json-store"
  (let [f (str (tmp-dir) "/store.json")]
    (spit f "{\"a\":{\"k\":\"v\"}}")
    (assertions
      "parses a JSON file to a string-keyed map"
      (get-in (#'cli/read-json-store f) ["a" "k"]) => "v"
      "a missing file yields nil (not an error)"
      (#'cli/read-json-store (str (tmp-dir) "/nope.json")) => nil)))

(specification "resolve-config-credentials"
  (let [store (str (tmp-dir) "/auth.json")
        _     (spit store "{\"zai-coding-plan\":{\"key\":\"K-ZAI\"},\"ollama-cloud\":{\"key\":\"K-OLL\"}}")
        cfg   {:llm/credential-sources {:opencode store}
               :llm/credentials
               [{:provider :z-ai-plan   :key-from [:opencode "zai-coding-plan" "key"]}
                {:provider :ollama      :key-from [:opencode "ollama-cloud" "key"]}
                {:provider :codex}
                {:provider :opencode-go :key-from [:opencode "absent" "key"]}]}
        ;; the unresolved descriptor logs a WARN to *err*; mute it for clean output
        out   (binding [*err* (java.io.StringWriter.)]
                (#'cli/resolve-config-credentials cfg))
        by-p  (fn [p] (first (filter #(= p (:provider %)) out)))]
    (assertions
      "resolves each :key-from against the store and attaches :api-key"
      (:api-key (by-p :z-ai-plan)) => "K-ZAI"
      (:api-key (by-p :ollama)) => "K-OLL"
      ":key-from is stripped from the resolved descriptor"
      (contains? (by-p :z-ai-plan) :key-from) => false
      ":codex passes through with no key (OAuth file)"
      (by-p :codex) => {:provider :codex}
      "a descriptor whose key cannot be resolved is dropped"
      (by-p :opencode-go) => nil
      "an inline :api-key is honored without a store"
      (:api-key (first (binding [*err* (java.io.StringWriter.)]
                         (#'cli/resolve-config-credentials
                           {:llm/credentials [{:provider :anthropic :api-key "K-INLINE"}]}))))
      => "K-INLINE"
      "returns nil when :llm/credentials is absent"
      (#'cli/resolve-config-credentials {}) => nil)))
