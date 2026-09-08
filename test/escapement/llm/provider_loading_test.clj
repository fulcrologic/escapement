(ns escapement.llm.provider-loading-test
  (:require
    [clojure.test :refer [deftest is]]
    [escapement.llm.providers :as providers]))

(deftest lazy-lookups-use-requiring-resolve
  ;; Keep all seven call sites covered even when the suite has warmed the JVM.
  (let [lookups (atom [])
        quirks {:test-quirk true}]
    (with-redefs [clojure.core/requiring-resolve
                  (fn [sym]
                    (swap! lookups conj sym)
                    (case sym
                      escapement.llm.model-quirks/opencode-go-quirks (atom quirks)
                      escapement.llm.openai-codex.auth/load-auth! (constantly nil)
                      identity))
                  providers/nonblank-env (constantly nil)]
      (doseq [build [providers/build-api-backend
                    providers/build-openai-backend
                    providers/build-codex-backend
                    providers/build-claude-cli-backend
                    providers/build-multi-backend]]
        (is (= {:sentinel true} (build {:sentinel true}))))
      (is (= quirks (providers/opencode-go-quirks)))
      (is (= [] (providers/detect-available-credentials)))
      (is (= '[escapement.llm.api/new-backend
               escapement.llm.openai/new-backend
               escapement.llm.openai-codex/new-backend
               escapement.llm.claude-cli/new-backend
               escapement.llm.multi/new-backend
               escapement.llm.model-quirks/opencode-go-quirks
               escapement.llm.openai-codex.auth/load-auth!]
            @lookups)))))
