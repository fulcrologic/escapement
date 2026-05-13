(ns api-sanity
  "bb sanity script: round-trip a short prompt against z.ai via the api backend.

   Skips with a friendly message if ZAI_API_KEY is unset."
  (:require
   [escapement.llm.api :as api]
   [escapement.llm.protocol :as proto]
   [escapement.llm.types :as types]))

(defn -main [& _]
  (if-let [key (System/getenv "ZAI_API_KEY")]
    (let [backend (api/new-backend {:base-url      "https://api.z.ai/api/anthropic"
                                    :api-key       key
                                    :default-model "glm-4.6"})
          resp    (proto/send-turn backend
                                   {:messages   [{:role :user
                                                  :content [{:type :text
                                                             :text "Reply with exactly: OK"}]}]
                                    :max-tokens 32})]
      (println "z.ai response:")
      (doseq [b (:content resp)]
        (when (= :text (:type b))
          (println " " (:text b))))
      (println "Malli-valid?" (nil? (types/validate-response resp)))
      (println "stop-reason:" (:stop-reason resp))
      (println "usage:" (:usage resp)))
    (do (println "[skip] ZAI_API_KEY not set; nothing to do.")
        (System/exit 0))))

(-main)
