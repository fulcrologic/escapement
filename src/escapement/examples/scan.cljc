(ns escapement.examples.scan
  "M6 demo: a real-tool + fan-out demo. The LLM is given `:fs/read` and
  `:shell/run` as real tools, plus two event-tools (`event__scan_found_bug` and
  `event__scan_complete`). It is instructed to scan a repo path, fire one or
  more `:scan/found-bug` events for findings, and one `:scan/complete` event when
  done.

  The chart accumulates findings via an internal-transition handler so each
  `:scan/found-bug` does not exit the bound state and kill the LLM worker."
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :refer [final script state transition]]
    [escapement.chart.helpers :as h]))

(def system-prompt
  ;; Real-tools (`fs_read`) are now exposed. Bug #1/#2 are fixed: tool_use inputs are
  ;; keyword-keyed and tool defs serialize correctly. Keep file reads tightly bounded
  ;; (<=2) to control token use during smoke runs.
  (str "You are scanning a small repository for notable issues or interesting findings. "
    "Read AT MOST TWO files with `fs_read`, then STOP reading and report. "
    "Read exactly these two, in order: \"<repo-root>/deps.edn\" then "
    "\"<repo-root>/README.md\" (append each to the ABSOLUTE repo root you are "
    "given). Do NOT guess any other paths — if a read fails, do not retry or try "
    "alternatives; just proceed to reporting from what you have. "
    "After at most two reads, report 2-3 plausible findings. "
    "For EACH finding, call `event__scan_found_bug` with `{file, line, summary}` "
    "(all keys plain — file is a string path, line is an integer, summary is a short string). "
    "Then call `event__scan_complete` with `{total_findings: <int>}` and end your turn. "
    "Keep summaries short (one line). Do not call any tools after `event__scan_complete`."))

(defn- user-message [data]
  ;; Always name a concrete, existing repo root (an ABSOLUTE path) so the model
  ;; reads real files instead of hallucinating paths. Defaults to the process
  ;; working directory — the fs tool passes absolute paths through unchanged
  ;; (relative paths would resolve against the empty session work-dir).
  (let [p (:repo-path data (System/getProperty "user.dir"))]
    (str "Scan the repo rooted at the absolute path: " p
      " (build file paths by appending to it, e.g. \"" p "/deps.edn\")."
      " Read at most TWO files, then report up to 3 findings.")))

(def agent
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :scanning}
      (state {:id :scanning}
        (h/llm-conversation
          {:id             "scanner"
           :system         system-prompt
           :real-tools     [:fs/read]
           ;; Bound the conversation so it cannot wander: a handful of read
           ;; turns plus the finding/complete turns, with a hard wall-clock cap.
           :max-turns      8
           :budget-ms      120000
           :allowed-events [{:event       :scan/found-bug
                             :data-schema [:map
                                           [:file :string]
                                           [:line :int]
                                           [:summary :string]]}
                            {:event       :scan/complete
                             :data-schema [:map [:total_findings :int]]}]
           :message        (fn [_env data] (user-message data))})
        ;; Accumulate findings without leaving :scanning (internal transition).
        ;; An event-tool turn ends the LLM turn (the worker parks in
        ;; :awaiting-user — see system-prompt "and end your turn"), so we
        ;; must re-drive the still-bound conversation to elicit the next
        ;; finding or the terminating :scan/complete.
        (transition {:event :scan/found-bug :type :internal}
          (script {:expr (fn [_env data]
                           [(ops/assign :findings
                              (conj (vec (:findings data))
                                (-> data :_event :data)))])})
          (h/tell-llm
            {:expr (fn [_env _data]
                     (str "Finding recorded. Continue: report another "
                       "finding via event__scan_found_bug, or call "
                       "event__scan_complete if you are done."))}))
        (transition {:event :scan/complete :target :finished}
          (script {:expr (fn [_env data]
                           [(ops/assign :total-findings
                              (get-in data [:_event :data :total_findings]))])})))
      (final {:id :finished}))))
