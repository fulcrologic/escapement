(ns escapement.examples.scan
  "M6 demo: a real-tool + fan-out demo. The LLM is given `:fs/read` and
  `:shell/run` as real tools, plus two event-tools (`event__found_bug` and
  `event__scan_complete`). It is instructed to scan a repo path, fire one or
  more `:found-bug` events for findings, and one `:scan-complete` event when
  done.

  The chart accumulates findings via an internal-transition handler so each
  `:found-bug` does not exit the bound state and kill the LLM worker."
  (:require
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.data-model.operations :as ops]
   [com.fulcrologic.statecharts.elements :refer [state transition final script]]
   [escapement.chart.helpers :as h]))

(def system-prompt
  ;; Real-tools (`fs_read`) are now exposed. Bug #1/#2 are fixed: tool_use inputs are
  ;; keyword-keyed and tool defs serialize correctly. Keep file reads tightly bounded
  ;; (<=2) to control token use during smoke runs.
  (str "You are scanning a small repository for notable issues or interesting findings. "
       "You MAY use the `fs_read` tool to read at most TWO files before reporting "
       "(prefer `deps.edn` and one source file). Use the `path` argument (an absolute path). "
       "Then report 2-3 plausible findings. "
       "For each finding, call `event__found_bug` with `{file, line, summary}` "
       "(all keys plain — file is a string path, line is an integer, summary is a short string). "
       "When done, call `event__scan_complete` with `{total_findings: <int>}` and end your turn. "
       "Keep summaries short (one line). Do not call any tools after `event__scan_complete`."))

(defn- user-message [data]
  ;; If `:repo-path` is in the data-model, use it; otherwise the system prompt
  ;; covers the "make up findings" case.
  (str "Scan the repo"
       (when-let [p (:repo-path data)] (str " rooted at: " p))
       ". Limit to 3 findings."))

(def agent
  (chart/statechart
   {:initial :run}
   (state {:id :run :initial :scanning}
          (state {:id :scanning}
                 (h/llm-conversation
                  {:id        "scanner"
                   :params-fn (fn [_env data]
                                {:system               system-prompt
                                 :real-tools           [:fs/read]
                                 :allowed-events       [{:event       :found-bug
                                                         :data-schema [:map
                                                                       [:file :string]
                                                                       [:line :int]
                                                                       [:summary :string]]}
                                                        {:event       :scan-complete
                                                         :data-schema [:map [:total_findings :int]]}]
                                 :initial-user-message (user-message data)})})
                 ;; Accumulate findings without leaving :scanning (internal transition).
                 ;; An event-tool turn ends the LLM turn (the worker parks in
                 ;; :awaiting-user — see system-prompt "and end your turn"), so we
                 ;; must re-drive the still-bound conversation to elicit the next
                 ;; finding or the terminating :scan-complete.
                 (transition {:event :found-bug :type :internal}
                             (script {:expr (fn [_env data]
                                              [(ops/assign :findings
                                                           (conj (vec (:findings data))
                                                                 (-> data :_event :data)))])})
                             (h/tell-llm
                              {:expr (fn [_env _data]
                                       (str "Finding recorded. Continue: report another "
                                            "finding via event__found_bug, or call "
                                            "event__scan_complete if you are done."))}))
                 (transition {:event :scan-complete :target :finished}
                             (script {:expr (fn [_env data]
                                              [(ops/assign :total-findings
                                                           (get-in data [:_event :data :total_findings]))])})))
          (final {:id :finished}))))
