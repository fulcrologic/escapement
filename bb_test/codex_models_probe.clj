#!/usr/bin/env bb
;; Verifies that every model the catalog advertises for the codex backend is
;; ACTUALLY usable with ChatGPT-account auth — and that the ids we deliberately
;; exclude are still rejected.
;;
;; This exists because the catalog drifted badly once already: it advertised
;; `gpt-5`/`gpt-5-mini`/`o3` while the backend defaulted to `gpt-5.1-codex`, and
;; none of the four worked. ChatGPT-account auth accepts a much smaller set than
;; the OpenAI model catalog implies, and the set moves.
;;
;; PREREQUISITES:
;;   Run `escapement login codex` once first (saves ~/.escapement/openai-auth.json).
;;
;; USAGE:
;;   bb bb_test/codex_models_probe.clj            # probe the supported set
;;   bb bb_test/codex_models_probe.clj --all      # also re-check the excluded ids
;;
;; Each probe is a one-token prompt. Exits 0 when the catalog matches reality.

(require '[babashka.classpath :refer [add-classpath]])
(add-classpath "src")

(require
  '[cheshire.core :as json]
  '[com.fulcrologic.statecharts.promise :as p]
  '[escapement.llm.catalog :as catalog]
  '[escapement.llm.openai-codex :as codex]
  '[escapement.llm.openai-codex.translate :as t]
  '[escapement.llm.protocol :as proto])

(def check-excluded? (some #{"--all"} *command-line-args*))

(def known-rejected
  "Ids probed as rejected on 2026-07-29. Re-checked with `--all` so we notice if
   OpenAI ever opens one of them up to ChatGPT-account auth."
  ["gpt-5.6" "gpt-5.6-sol-ultra" "gpt-5.5-pro" "gpt-5.4-pro" "gpt-5.4-nano"
   "gpt-5.3-codex" "gpt-5.3-codex-spark" "gpt-5.2-codex" "gpt-5.1-codex"
   "gpt-5.1-codex-mini" "gpt-5.1" "gpt-5.2"])

(defn probe
  "Returns `{:ok true}` or `{:ok false :detail s}` for one model id."
  [model]
  (try
    (let [resp (p/await! (proto/send-turn (codex/new-backend {:default-model model})
                           {:model    model
                            :system   "Reply with one character."
                            :messages [{:role :user :content [{:type :text :text "K"}]}]}))]
      {:ok true :model (:model resp) :usage (:usage resp)})
    (catch Throwable t
      {:ok     false
       :detail (or (:detail (try (json/parse-string (str (:body (ex-data t))) true)
                                 (catch Throwable _ nil)))
                 (ex-message t))})))

(def failures (atom []))

;;; ---------------------------------------------------------------------------
;;; 1. Catalog / backend agreement (offline — no API calls)

(println "[1/3] catalog and backend agree on the supported set")
(let [cat (set (keys (:models (catalog/provider-info :openai-codex))))
      sup (set t/supported-models)]
  (if (= cat sup)
    (println (str "  PASS  both list " (count sup) " models: " (pr-str t/supported-models)))
    (do (println (str "  FAIL  catalog=" (pr-str (sort cat)) " backend=" (pr-str (sort sup))))
        (swap! failures conj "catalog/backend disagree")))
  (if (contains? sup t/default-model)
    (println (str "  PASS  default-model " (pr-str t/default-model) " is in the set"))
    (do (println (str "  FAIL  default-model " (pr-str t/default-model) " is NOT in the set"))
        (swap! failures conj "default-model not supported")))
  ;; Both provider spellings must be flat-fee, or spend accounting mis-attributes.
  (doseq [pk [:openai-codex :codex]]
    (if (catalog/subscription? pk)
      (println (str "  PASS  " pk " is priced as a flat-fee subscription"))
      (do (println (str "  FAIL  " pk " is not marked :subscription"))
          (swap! failures conj (str pk " not subscription"))))))

;;; ---------------------------------------------------------------------------
;;; 2. Every advertised model actually works

(println "\n[2/3] every advertised model is usable with ChatGPT-account auth")
(doseq [m t/supported-models]
  (let [{:keys [ok model usage detail]} (probe m)]
    (if ok
      (println (format "  PASS  %-14s ran as %-14s in=%s out=%s" m (str model)
                 (:input-tokens usage) (:output-tokens usage)))
      (do (println (format "  FAIL  %-14s %s" m detail))
          (swap! failures conj m)))))

;;; ---------------------------------------------------------------------------
;;; 3. The excluded ids are still excluded (opt-in)

(if-not check-excluded?
  (println "\n[3/3] SKIP excluded-id re-check (pass --all to run it)")
  (do
    (println "\n[3/3] the deliberately-excluded ids are still rejected")
    (doseq [m known-rejected]
      (let [{:keys [ok]} (probe m)]
        (if ok
          ;; Not a failure — good news that needs a catalog update.
          (println (format "  NOTE  %-20s now WORKS — consider adding it to supported-models" m))
          (println (format "  PASS  %-20s still rejected" m)))))))

;;; ---------------------------------------------------------------------------

(println)
(if (empty? @failures)
  (do (println "PASS: the codex catalog matches reality") (System/exit 0))
  (do (println (str "FAIL: " (count @failures) " problem(s): " (pr-str @failures)))
      (System/exit 1)))
