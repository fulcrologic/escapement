#!/usr/bin/env bb
;; Render a claude-code-like transcript for ONE llm-conversation node invocation, reconstructed from
;; a finished session's captured-I/O tree via the EQL reconstruction API
;; (escapement.ui.resolvers) + dereferenced blobs.
;;
;; Usage:
;;   bb dev/llm_transcript.clj <session-id> [work-dir] [node-id] [visit]
;;
;;   work-dir defaults to ".escapement"; node-id/visit default to the FIRST invocation in the session
;;   (the common case — a chart with a single llm-conversation, like examples/large-files).
;;
;; Example:
;;   bb dev/llm_transcript.clj 1604611f-baf6-4ff6-a436-b7daddd02b02
(ns llm-transcript
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [escapement.protocols :as proto]
    [escapement.storage.disk-read :as dr]
    [escapement.ui.resolvers :as r]))

(defn- truncate [s n]
  (let [s (str s)]
    (if (<= (count s) n) s (str (subs s 0 n) " …[+" (- (count s) n) " chars]"))))

(defn- user-text [content]
  (if (string? content)
    content
    (->> content (keep :text) (str/join "\n"))))

(defn render-llm-transcript
  "Print the transcript for the invocation `[sid node-id visit]` in `work-dir`. When `node-id`/`visit`
   are nil, uses the session's first invocation."
  [work-dir sid node-id visit]
  (let [store  (dr/new-store work-dir)
        deref* (fn [ref] (when ref
                           (some-> (proto/read-artifact store sid ref)
                             (->> (edn/read-string {:default tagged-literal})))))
        id     (if node-id
                 [sid node-id visit]
                 (-> (r/process {:escapement/store store}
                       [{[:com.fulcrologic.statecharts/session-id sid]
                         [{:session/invocations [:llm.conversation/invocation-id]}]}])
                   (get-in [[:com.fulcrologic.statecharts/session-id sid] :session/invocations])
                   first :llm.conversation/invocation-id))]
    (when-not id
      (println "No llm-conversation invocation found for session" sid "in" work-dir)
      (System/exit 1))
    (let [inv   (-> (r/process {:escapement/store store}
                      [{[:llm.conversation/invocation-id id]
                        [:invocation/turn-count :llm.conversation/output-ref
                         {:invocation/timeline [:timeline/kind :transcript/turn
                                                :turn/request-ref :turn/response-ref
                                                :turn/tool-result-refs :turn/output-ref
                                                :event/name :event/data]}]}])
                  (get [:llm.conversation/invocation-id id]))
          turns (filterv #(= :turn (:timeline/kind %)) (:invocation/timeline inv))
          req0  (deref* (:turn/request-ref (first turns)))]
      (println "════════════════════════════════════════════════════════════")
      (println (str "LLM INVOCATION  node=" (second id) "  visit=" (nth id 2)
                 "  turns=" (:invocation/turn-count inv)))
      (println "════════════════════════════════════════════════════════════")
      (println "\n## SYSTEM\n" (truncate (:system req0) 800))
      (println "\n## USER (initial)\n"
        (truncate (->> (:messages req0) (filter #(= :user (:role %))) first :content user-text) 1000))
      (doseq [t (:invocation/timeline inv)]
        (case (:timeline/kind t)
          :turn
          (let [resp (deref* (:turn/response-ref t))]
            (println (str "\n──────────── TURN " (:transcript/turn t) " ────────────"))
            (doseq [b resp]
              (case (:type b)
                :text     (println "\n🤖 ASSISTANT:\n" (truncate (:text b) 2000))
                :thinking (println "\n💭 (thinking)\n" (truncate (:thinking b) 400))
                :tool_use (println (str "\n🔧 TOOL CALL  " (:name b) "  →  " (truncate (pr-str (:input b)) 500)))
                (println "\n[block]" (:type b))))
            (doseq [tr (:turn/tool-result-refs t)]
              (println (str "\n📄 TOOL RESULT (" (last (str/split tr #"/")) "):\n" (truncate (deref* tr) 800)))))
          :fired-event
          (println (str "\n📣 EVENT SENT  " (:event/name t) "  " (pr-str (:event/data t))))
          nil))
      (when-let [out (deref* (:llm.conversation/output-ref inv))]
        (println "\n══════════════════ FINAL OUTPUT (output.edn) ══════════════════")
        (println (:text out))))))

(let [[sid work-dir node-id visit] *command-line-args*]
  (when-not sid
    (println "usage: bb dev/llm_transcript.clj <session-id> [work-dir] [node-id] [visit]")
    (System/exit 2))
  (render-llm-transcript (or work-dir ".escapement") sid
    (some-> node-id edn/read-string) (some-> visit edn/read-string)))
