(ns escapement.debug.override-reinvoke-test
  "Task 003: the debugger's `:debug/overrides` actually take effect on the turn
   the conversation node re-issues when a forked branch is resumed.

   The assertion boundary is the MOCK BACKEND: `RecordingBackend` captures the
   fully-assembled `request` map handed to `send-turn` (its `:system`, `:model`,
   `:provider`, `:temperature`, `:messages`). Whatever the override changed must
   be visible there — that is the actually-issued turn, not a patched map in
   isolation. Reuses task 001's mock-backend chart + `branch/fork-session!`.

   Covered overrides (each its own component on a re-invoked branch turn):
     * :system          → request :system is the override text
     * :alias           → resolution picks that alias's provider+model
     * :provider+:model → resolution short-circuits to that exact target (pin)
     * :temperature     → present on the request
     * :messages prefix → seeds worker history verbatim (a :system entry in the
                          list is NOT added as a message role)
   Plus override SCOPING: an override bound to a different {node-id, visit} does
   NOT apply (turn assembles as captured)."
  (:require
    [clojure.java.io :as io]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final on-entry send state transition]]
    [com.fulcrologic.statecharts.promise :as p]
    [escapement.chart.helpers :as h]
    [escapement.debug.branch :as branch]
    [escapement.llm.protocol :as llm]
    [escapement.runner :as runner]
    [escapement.test-support :as ts]
    [escapement.tools.protocol :as tp]
    [fulcro-spec.core :refer [=> assertions component specification]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir [] (str (Files/createTempDirectory "override" (into-array FileAttribute []))))

;; Records each fully-assembled request handed to the backend, so a test can
;; assert what the override actually produced on the re-issued turn.
(defrecord RecordingBackend [responses calls]
  llm/LLMBackend
  (send-turn [_ request]
    (swap! calls conj request)
    (p/do! (or (ts/pop-first! responses)
             {:stop-reason :end_turn
              :content     [{:type :text :text "done"}]
              :usage       {:input-tokens 1 :output-tokens 1}
              :model       "mock"}))))

;; `:warmup` self-sends a delayed `:go`, so `:talk` (an llm-conversation node) is
;; entered by a real EVENT and a node-entry checkpoint exists before it runs.
;; The conversation node pins a `:model` alias so resolution has a default
;; (overridable). `:system` is set so a non-overriding run has a baseline.
(def ^:private talk-chart
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :warmup}
      (state {:id :warmup}
        (on-entry {} (send {:event :go :delay 1}))
        (transition {:event :go :target :talk}))
      (state {:id :talk}
        (h/llm-conversation {:id      "writer"
                             :message "hi"
                             :model   :base
                             :system  "BASE SYSTEM"})
        (transition {:event :llm.idle :target :done}))
      (final {:id :done}))))

;; Aliases the override / baseline resolution resolve against.
(def ^:private aliases
  {:base        [{:provider :prov-base :model "base-model"}]
   :p-gemma270m [{:provider :ollama :model "gemma3:270m"}]})

(defn- run-parent! [{:keys [sdir chk sid]}]
  (runner/run! {:chart              talk-chart
                :session-id         sid
                :transcript-path    (str sdir "/transcript.jsonl")
                :checkpoint-dir     chk
                :session-dir        sdir
                :llm-aliases        aliases
                :backend            (->RecordingBackend (ts/queue []) (atom []))
                :tool-registry      (tp/new-registry)
                :quiescent-sleep-ms 5}))

(defn- fork! [sid dir]
  (branch/fork-session!
    {:parent-session-id sid
     :branch-point      {:node-id "talk" :visit 0 :turn 0}
     :work-dir          dir}))

(defn- resume-branch!
  "Resume the forked branch with `overrides` as `:debug-overrides`; return the
   captured request the branch re-issued (or nil if none)."
  [branch overrides]
  (let [bcalls (atom [])]
    (runner/run! {:chart              talk-chart
                  :session-id         (:branch-id branch)
                  :transcript-path    (:transcript-path branch)
                  :checkpoint-dir     (:checkpoint-dir branch)
                  :session-dir        (:session-dir branch)
                  :resume?            true
                  :llm-aliases        aliases
                  :debug-overrides    overrides
                  :backend            (->RecordingBackend (ts/queue []) bcalls)
                  :tool-registry      (tp/new-registry)
                  :quiescent-sleep-ms 5})
    (first @bcalls)))

(defn- fresh-branch
  "A new parent run + a fresh fork, so each override case resumes an untouched
   branch (resuming twice would consume the seeded checkpoint event)."
  []
  (let [dir (tmp-dir)
        sid (str "ov-" (System/nanoTime))
        sdir (str dir "/" sid)
        chk (str sdir "/checkpoints")]
    (.mkdirs (io/file chk))
    (run-parent! {:sdir sdir :chk chk :sid sid})
    (fork! sid dir)))

(specification "override injection on the re-invoked branch turn"
  (component "baseline (no overrides) — turn assembles from node params"
    (let [req (resume-branch! (fresh-branch) nil)]
      (assertions
        "the branch re-issued a turn (backend was called)"
        (some? req) => true
        "system is the node's baseline prompt"
        (:system req) => "BASE SYSTEM"
        "model resolves to the node's :base alias target"
        (:model req) => "base-model"
        "provider is the :base alias's provider"
        (:provider req) => :prov-base)))

  (component ":system override → re-issued request carries the override text"
    (let [req (resume-branch! (fresh-branch)
                {:node-id :talk :visit 0 :system "OVERRIDE SYSTEM PROMPT"})]
      (assertions
        "the assembled system prompt is the override, not the node baseline"
        (:system req) => "OVERRIDE SYSTEM PROMPT")))

  (component ":alias override → resolution picks that alias's provider+model"
    (let [req (resume-branch! (fresh-branch)
                {:node-id :talk :visit 0 :alias :p-gemma270m})]
      (assertions
        "the resolved model is the alias's target model"
        (:model req) => "gemma3:270m"
        "the resolved provider is the alias's provider"
        (:provider req) => :ollama)))

  (component ":provider + :model pin → resolution short-circuits to that target"
    (let [req (resume-branch! (fresh-branch)
                {:node-id :talk :visit 0 :provider :pinned-prov :model "pinned-model"})]
      (assertions
        "the request goes to the pinned model exactly"
        (:model req) => "pinned-model"
        "and the pinned provider (no alias resolution, no failover)"
        (:provider req) => :pinned-prov)))

  (component ":temperature override → present on the assembled request"
    (let [req (resume-branch! (fresh-branch)
                {:node-id :talk :visit 0 :temperature 0.42})]
      (assertions
        "temperature is threaded onto the request"
        (:temperature req) => 0.42)))

  (component ":messages prefix → seeds worker history verbatim"
    (let [req (resume-branch! (fresh-branch)
                {:node-id  :talk :visit 0
                 :messages [{:role :system :text "SYS LINE"}
                            {:role :user :text "edited user"}
                            {:role :assistant :text "edited assistant"}]})
          msgs (:messages req)
          roles (mapv :role msgs)
          texts (->> msgs
                  (mapcat :content)
                  (keep :text)
                  vec)]
      (assertions
        "the worker history is seeded from the edited prefix (user + assistant)"
        roles => [:user :assistant]
        "the user/assistant edited text is what was sent"
        texts => ["edited user" "edited assistant"]
        "a :system entry in the messages list is NOT added as a message role"
        (some #(= :system %) roles) => nil)))

  (component "scoping: override bound to a DIFFERENT node-id does NOT apply"
    (let [req (resume-branch! (fresh-branch)
                {:node-id :some-other-node :visit 0 :system "SHOULD NOT APPLY"})]
      (assertions
        "the turn assembles as captured (baseline system, not the override)"
        (:system req) => "BASE SYSTEM"
        "and the baseline model"
        (:model req) => "base-model")))

  (component "scoping: override bound to a DIFFERENT visit does NOT apply"
    (let [req (resume-branch! (fresh-branch)
                {:node-id :talk :visit 7 :system "SHOULD NOT APPLY"})]
      (assertions
        "the turn assembles as captured (baseline system, not the override)"
        (:system req) => "BASE SYSTEM"))))
