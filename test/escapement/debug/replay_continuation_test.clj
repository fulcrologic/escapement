(ns escapement.debug.replay-continuation-test
  "FULL-WORKER integration for replay-by-match on a forked-branch continuation.

   A parent run makes a real tool call (counting `:test/echo`) at a known
   {node :talk, visit 0, turn 0} with input {:msg \"hi\"}, so a captured
   :llm/tool-result + result blob land on disk under the parent session.

   We then fork a branch and resume it with a `:debug/replay-policy` sourced from
   the parent (`:source` = parent session-id, `:work-dir` = sessions root). The
   re-invoked `:talk` turn issues tool calls through the REAL worker dispatch
   path (`escapement.invocation.llm-conversation/handle-tool-use-block` ->
   `escapement.debug.replay/replay-aware-dispatch`) and we assert, per branch:

     * MATCHED call (same coords + tool + input)  -> served from capture, the live
       tool's invoke counter does NOT increment, event tagged :replay/source
       \"captured\".
     * UNMATCHED near-miss (same tool, DIFFERENT input) -> tool runs live (counter
       increments), event tagged \"live\" + :replay/unmatched.
     * UNMATCHED destructive call (:deny vs :allow) -> withheld (synthetic error,
       not run) under :deny; runs under :allow.

   tool_use_ids differ across the re-run (parent \"u1\" vs branch \"b1\"); matching
   keys on [node visit turn tool input], NEVER the id, so the difference is inert.

   This complements the isolated `replay_dispatch_wiring_test` (which drives the
   private handler directly); here the full runner + fork + resume + re-invoke
   path is exercised end to end."
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
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

(defn- tmp-dir [] (str (Files/createTempDirectory "replay-cont" (into-array FileAttribute []))))

;; --- mock backend -----------------------------------------------------------

(defrecord RecordingBackend [responses calls]
  llm/LLMBackend
  (send-turn [_ request]
    (swap! calls conj request)
    (p/do! (or (ts/pop-first! responses)
             {:stop-reason :end_turn
              :content     [{:type :text :text "done"}]
              :usage       {:input-tokens 1 :output-tokens 1}
              :model       "mock"}))))

(defn- tool-use-turn [tool-uses]
  {:stop-reason :tool_use
   :content     (mapv (fn [{:keys [id name input]}]
                        {:type :tool_use :id id :name name :input input})
                  tool-uses)
   :usage       {:input-tokens 1 :output-tokens 1}
   :model       "mock"})

(defn- end-turn [text]
  {:stop-reason :end_turn
   :content     [{:type :text :text (or text "ok")}]
   :usage       {:input-tokens 1 :output-tokens 1}
   :model       "mock"})

;; --- tools ------------------------------------------------------------------

(defrecord CountingEcho [calls]
  tp/Tool
  (tool-name [_] :test/echo)
  (description [_] "Echo :msg; counts live invocations.")
  (input-schema [_] [:map {:closed true} [:msg :string]])
  (invoke [_ {:keys [msg]}] (swap! calls inc) {:result (str "live:" msg) :is-error false}))

;; A stand-in destructive tool (so the destructive guard is exercised without
;; touching the real filesystem). The policy's :destructive? predicate flags it.
(defrecord CountingWrite [calls]
  tp/Tool
  (tool-name [_] :dang/write)
  (description [_] "Pretend to write a file; counts live invocations.")
  (input-schema [_] [:map {:closed true} [:path :string]])
  (invoke [_ {:keys [path]}] (swap! calls inc) {:result (str "wrote:" path) :is-error false}))

;; --- chart ------------------------------------------------------------------
;; :warmup self-sends a delayed :go so :talk is entered by a real event (a
;; node-entry checkpoint is written). :talk exposes both tools and on :llm.idle
;; continues to :done.
(defn- talk-chart []
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :warmup}
      (state {:id :warmup}
        (on-entry {} (send {:event :go :delay 1}))
        (transition {:event :go :target :talk}))
      (state {:id :talk}
        (h/llm-conversation {:id "writer" :message "hi" :real-tools [:test/echo :dang/write]})
        (transition {:event :llm.idle :target :done}))
      (final {:id :done}))))

(defn- registry [echo-calls write-calls]
  (tp/new-registry [(->CountingEcho echo-calls) (->CountingWrite write-calls)]))

(defn- run! [{:keys [sid sdir chk session-dir responses echo-calls write-calls
                     resume? transcript-path checkpoint-dir replay-policy bcalls]}]
  (runner/run!
    (cond-> {:chart              (talk-chart)
             :session-id         sid
             :transcript-path    (or transcript-path (str sdir "/transcript.jsonl"))
             :checkpoint-dir     (or checkpoint-dir chk)
             :session-dir        session-dir
             :backend            (->RecordingBackend (ts/queue (or responses []))
                                   (or bcalls (atom [])))
             :tool-registry      (registry echo-calls write-calls)
             :quiescent-sleep-ms 5}
      resume?       (assoc :resume? true)
      replay-policy (assoc :debug-replay-policy replay-policy))))

;; ---------------------------------------------------------------------------

(defn- find-tool-result
  "Read the branch transcript and return the :data of the LAST :llm/tool-result row."
  [transcript-path]
  (->> (slurp transcript-path)
    (str/split-lines)
    (keep (fn [l] (when (seq l) (json/parse-string l true))))
    (filter #(= "llm/tool-result" (:event %)))
    last
    :data))

(specification "branch continuation serves captured tool-results by match through the real worker"
  (let [dir         (tmp-dir)
        sid         "replay-parent"
        sdir        (str dir "/" sid)
        chk         (str sdir "/checkpoints")
        _           (.mkdirs (io/file chk))
        p-echo      (atom 0)
        p-write     (atom 0)
        ;; Parent: tool_use(:test/echo {:msg "hi"}) then end_turn. Produces a
        ;; captured :llm/tool-result at talk/0/turn0 + a result blob.
        presult     (run! {:sid sid :sdir sdir :chk chk :session-dir sdir
                           :echo-calls p-echo :write-calls p-write
                           :responses [(tool-use-turn [{:id "u1" :name "test_echo" :input {:msg "hi"}}])
                                       (end-turn "parent done")]})
        ;; The policy that lets the branch worker build the PARENT store and index.
        ;; :source = parent session-id; :work-dir = sessions root; disk-read reads
        ;; <work-dir>/<source>/{transcript.jsonl,nodes/…}.
        base-policy {:source          sid
                     :work-dir        dir
                     :mode            :replay-then-live
                     :flag-unmatched? true}]
    (assertions
      "parent reached :done and ran the echo tool exactly once"
      (contains? (set (:final-config presult)) :done) => true
      @p-echo => 1)

    (component "MATCHED call -> captured (tool not run), event tagged captured; tool_use_id differs"
      (let [fork    (branch/fork-session!
                      {:parent-session-id sid
                       :branch-point      {:node-id "talk" :visit 0 :turn 0}
                       :work-dir          dir})
            echo    (atom 0)
            write   (atom 0)
            bcalls  (atom [])
            _       (run! {:sid (:branch-id fork)
                           :transcript-path (:transcript-path fork)
                           :checkpoint-dir  (:checkpoint-dir fork)
                           :session-dir     (:session-dir fork)
                           :resume?         true
                           :echo-calls      echo :write-calls write :bcalls bcalls
                           :replay-policy   base-policy
                           ;; DIFFERENT tool_use_id ("b1" vs parent "u1"), SAME input.
                           :responses [(tool-use-turn [{:id "b1" :name "test_echo" :input {:msg "hi"}}])
                                       (end-turn "branch done")]})
            data    (find-tool-result (:transcript-path fork))]
        (assertions
          "the branch re-invoked the conversation worker"
          (pos? (count @bcalls)) => true
          "the live echo tool was NOT run (served from capture)"
          @echo => 0
          "the tool-result event is flagged captured"
          (:replay/source data) => "captured"
          "and carries the captured content (not a live echo)"
          (:content-preview data) => "live:hi")))

    (component "UNMATCHED near-miss (same tool, different input) -> runs live, tagged live"
      (let [fork    (branch/fork-session!
                      {:parent-session-id sid
                       :branch-point      {:node-id "talk" :visit 0 :turn 0}
                       :work-dir          dir})
            echo    (atom 0)
            write   (atom 0)
            _       (run! {:sid (:branch-id fork)
                           :transcript-path (:transcript-path fork)
                           :checkpoint-dir  (:checkpoint-dir fork)
                           :session-dir     (:session-dir fork)
                           :resume?         true
                           :echo-calls echo :write-calls write
                           :replay-policy base-policy
                           :responses [(tool-use-turn [{:id "b2" :name "test_echo" :input {:msg "bye"}}])
                                       (end-turn "branch done")]})
            data    (find-tool-result (:transcript-path fork))]
        (assertions
          "the live echo tool ran exactly once (no false match)"
          @echo => 1
          "the tool-result event is flagged live (the unmatched/new-side-effect case)"
          (:replay/source data) => "live"
          "and carries the LIVE result"
          (:content-preview data) => "live:bye")))

    (component "UNMATCHED destructive call with guard :deny -> withheld, tool NOT run"
      (let [fork    (branch/fork-session!
                      {:parent-session-id sid
                       :branch-point      {:node-id "talk" :visit 0 :turn 0}
                       :work-dir          dir})
            echo    (atom 0)
            write   (atom 0)
            _       (run! {:sid (:branch-id fork)
                           :transcript-path (:transcript-path fork)
                           :checkpoint-dir  (:checkpoint-dir fork)
                           :session-dir     (:session-dir fork)
                           :resume?         true
                           :echo-calls echo :write-calls write
                           :replay-policy (assoc base-policy
                                            :destructive  :deny
                                            :destructive? (fn [kw _] (= kw :dang/write)))
                           :responses [(tool-use-turn [{:id "b3" :name "dang_write" :input {:path "/x"}}])
                                       (end-turn "branch done")]})
            data    (find-tool-result (:transcript-path fork))]
        (assertions
          "the destructive tool was NOT run"
          @write => 0
          "the result is a synthetic error"
          (:is-error data) => true
          "tagged live + unmatched (it was a miss, withheld at the guard)"
          (:replay/source data) => "live")))

    (component "UNMATCHED destructive call with guard :allow -> runs live"
      (let [fork    (branch/fork-session!
                      {:parent-session-id sid
                       :branch-point      {:node-id "talk" :visit 0 :turn 0}
                       :work-dir          dir})
            echo    (atom 0)
            write   (atom 0)
            _       (run! {:sid (:branch-id fork)
                           :transcript-path (:transcript-path fork)
                           :checkpoint-dir  (:checkpoint-dir fork)
                           :session-dir     (:session-dir fork)
                           :resume?         true
                           :echo-calls echo :write-calls write
                           :replay-policy (assoc base-policy
                                            :destructive  :allow
                                            :destructive? (fn [kw _] (= kw :dang/write)))
                           :responses [(tool-use-turn [{:id "b4" :name "dang_write" :input {:path "/y"}}])
                                       (end-turn "branch done")]})
            data    (find-tool-result (:transcript-path fork))]
        (assertions
          "the destructive tool ran exactly once"
          @write => 1
          "tagged live"
          (:replay/source data) => "live"
          "and carries the LIVE result"
          (:content-preview data) => "wrote:/y")))

    (assertions
      "the deterministic prefix was never re-executed live across all branches"
      @p-echo => 1)))
