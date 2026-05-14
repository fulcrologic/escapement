(ns escapement.llm.openai-codex.cli
  "Entry points for `escapement login codex` and `escapement logout codex`."
  (:require
   [escapement.llm.openai-codex.auth :as auth]))

(defn login!
  "Runs the interactive OAuth login flow and saves credentials to AUTH-FILE.
  Prints confirmation with account ID and expiry."
  [_args]
  (let [a (auth/login-flow!)]
    (println "Logged in as ChatGPT account:" (:account-id a))
    (println "Token expires at:" (java.util.Date. ^long (:expires-at a)))
    (println "Credentials saved to:" auth/AUTH-FILE)))

(defn logout!
  "Removes the saved credential file, if present."
  [_args]
  (let [f (java.io.File. ^String auth/AUTH-FILE)]
    (if (.exists f)
      (do (.delete f) (println "Logged out:" auth/AUTH-FILE))
      (println "No credentials to remove."))))
