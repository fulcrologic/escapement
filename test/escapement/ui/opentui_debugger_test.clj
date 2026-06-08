(ns escapement.ui.opentui-debugger-test
  "Agent-side proof (under `bb test`) of the OpenTUI TIME-TRAVEL DEBUGGER seams —
   the Clojure/bb half of the feature (spec R6–R11, tasks 002–006):

     1. Branch fork (002) — `escapement.debug.branch/fork-session!` seeds a new
        session from the parent's pre-conversation checkpoint, records parentage,
        and NEVER mutates the parent (byte-for-byte enforced).
     2. Override injection (003) — `escapement.llm/apply-debug-overrides` layers
        provider/model/temperature/system above node params, and
        `escapement.ui.debug-control/normalize-overrides` translates the wire
        override map into the agent-side `:debug/overrides` payload.
     3. Replay policy (004) — `escapement.debug.replay/replay-aware-dispatch`
        serves captured tool results by match (`captured`), runs unmatched calls
        live (`live`), and the destructive guard withholds (covered in depth by
        `replay_test`; here we prove the integration shape the control surface
        feeds it).
     4. Breakpoint + turn nav (005) — `escapement.debug.controller` arm / await /
        next / back / continue, including a REAL parked-worker future that the
        turn-gate releases.
     5. WS control-op round-trip (006) — `opentui.sidecar/make-ws-handlers` drives
        each new `control` op to its `escapement.ui.debug-control` effect and
        pushes the right forward frame over a stubbed `escapement.ui.ws-push` hub.

   Style mirrors `escapement.ui.live-control-http-test` /
   `escapement.ui.opentui-push-test`: drive the public seams directly with a
   stubbed `http/send!` so the fan-out is race-free and SCI-safe. No real engine
   thread, no socket, no port. Live-API portions are gated on env vars and skip
   cleanly when unset (none are required here — every seam is exercised with mocks).

   Runs under `bb test` (normal `*_test.clj`, NOT JVM-only)."
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.promise :as p]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.capture :as capture]
    [escapement.debug.branch :as branch]
    [escapement.debug.controller :as dbg]
    [escapement.debug.control-handle :as ch]
    [escapement.debug.replay :as dr]
    [escapement.engine.store :as store]
    [escapement.llm :as ellm]
    [escapement.protocols :as proto]
    [escapement.replay :as replay]
    [escapement.storage.memory :as mem]
    [escapement.tools.protocol :as tp]
    [escapement.ui.debug-control :as dc]
    [escapement.ui.ws-push :as ws]
    [fulcro-spec.core :refer [=> assertions component specification]]
    [opentui.sidecar :as sidecar]
    [org.httpkit.server :as http]))

;; ---------------------------------------------------------------------------
;; Frame-capture helper: stub http/send! so every forward frame the publishers
;; broadcast lands in an atom (the hub's broadcast! calls http/send! per client).
;; ---------------------------------------------------------------------------

(defn- with-capture
  "Run `f` with `http/send!` capturing every (ch, payload) into the returned
   atom `{ch [json …]}`. `f` receives the capture atom."
  [f]
  (let [sent (atom {})]
    (with-redefs [http/send! (fn [ch payload]
                               (swap! sent update ch (fnil conj []) payload)
                               true)]
      (f sent))
    sent))

(defn- all-frames
  "All decoded JSON frames captured across every fake channel, send order per ch."
  [sent]
  (->> (vals @sent)
    (mapcat identity)
    (mapv #(json/parse-string % true))))

;; ===========================================================================
;; 1. Branch fork (task 002) — seeded wmem + parentage, parent untouched
;; ===========================================================================

(defn- temp-dir [prefix]
  (let [d (java.nio.file.Files/createTempDirectory prefix (make-array java.nio.file.attribute.FileAttribute 0))]
    (str d)))

(def ^:private known-config #{:S/root :writer})

(defn- seed-parent-run!
  "Lay down a parent session on disk with a node-entry checkpoint for
   {node-id, visit} so `fork-session!` resolves a precise seed. Returns
   `{:work-dir :parent-id :ck-dir}`."
  [{:keys [node-id visit] :or {node-id ":writer" visit 0}}]
  (let [work-dir  (temp-dir "esc-fork-")
        parent-id "parent-sess"
        ck-dir    (str work-dir "/" parent-id "/checkpoints")
        st        (store/new-store ck-dir)
        wmem      {::sc/configuration known-config :data-model {:x 1}}]
    ;; canonical latest checkpoint AND the precise node-entry snapshot
    (sp/save-working-memory! st {} parent-id {::sc/configuration #{:S/done}})
    (store/save-node-entry-checkpoint! st parent-id node-id visit wmem)
    {:work-dir work-dir :parent-id parent-id :ck-dir ck-dir}))

(defn- snapshot-tree
  "Map of relative-path -> bytes for every file under `dir` (for byte-equality)."
  [dir]
  (let [root (io/file dir)]
    (->> (file-seq root)
      (filter #(.isFile %))
      (reduce (fn [m f]
                (assoc m (subs (.getPath f) (count (.getPath root)))
                  (slurp f))) {}))))

(specification "branch fork seeds a new session + parentage and never mutates the parent"
  (let [{:keys [work-dir parent-id]} (seed-parent-run! {})
        before (snapshot-tree (str work-dir "/" parent-id))
        fork   (branch/fork-session!
                 {:parent-session-id parent-id
                  :branch-point      {:node-id ":writer" :visit 0 :turn 2}
                  :work-dir          work-dir
                  :branch-id         "the-branch"})
        after  (snapshot-tree (str work-dir "/" parent-id))]
    (assertions
      "the fork returns a NEW branch id distinct from the parent"
      (:branch-id fork) => "the-branch"
      (:parent fork) => parent-id
      "the seed came from the precise node-entry checkpoint (not the latest fallback)"
      (:seed-source fork) => :node-entry
      "the branch dir + seeded checkpoint dir + transcript path are reported"
      (:checkpoint-dir fork) => (str work-dir "/the-branch/checkpoints")
      (:transcript-path fork) => (str work-dir "/the-branch/transcript.jsonl")
      "the branch's seeded canonical checkpoint carries the parent's pre-node wmem"
      (::sc/configuration (sp/get-working-memory (store/new-store (:checkpoint-dir fork)) {} "the-branch"))
      => known-config)
    (component "parentage metadata is recorded on disk"
      (let [pa (branch/read-parentage (:session-dir fork))]
        (assertions
          "branch.edn records parent + branch-point + seed-source"
          (:parent pa) => parent-id
          (:branch-point pa) => {:node-id ":writer" :visit 0 :turn 2}
          (:seed-source pa) => :node-entry)))
    (component "the parent's files are byte-for-byte unchanged after the fork"
      (assertions
        "every parent file is identical before/after"
        after => before))))

(specification "branch fork falls back to the latest checkpoint when no node-entry snapshot exists"
  (let [work-dir  (temp-dir "esc-fork2-")
        parent-id "p2"
        ck-dir    (str work-dir "/" parent-id "/checkpoints")
        st        (store/new-store ck-dir)
        _         (sp/save-working-memory! st {} parent-id {::sc/configuration known-config})
        fork      (branch/fork-session!
                    {:parent-session-id parent-id
                     :branch-point      {:node-id ":never-snapshotted" :visit 0 :turn 0}
                     :work-dir          work-dir
                     :env               {}
                     :branch-id         "b2"})]
    (assertions
      "with no node-entry snapshot the seed comes from the latest checkpoint"
      (:seed-source fork) => :latest
      "and it is still seeded with a runnable configuration"
      (::sc/configuration (sp/get-working-memory (store/new-store (:checkpoint-dir fork)) {} "b2"))
      => known-config)))

(specification "branch fork refuses a terminated run (empty configuration)"
  (let [work-dir  (temp-dir "esc-fork3-")
        parent-id "p3"
        st        (store/new-store (str work-dir "/" parent-id "/checkpoints"))
        _         (sp/save-working-memory! st {} parent-id {::sc/configuration #{}})]
    (assertions
      "forking from a terminated run throws (nothing to continue)"
      (try (branch/fork-session!
             {:parent-session-id parent-id
              :branch-point      {:node-id ":x" :visit 0 :turn 0}
              :work-dir          work-dir :env {} :branch-id "b3"})
           :no-throw
           (catch Throwable _ :threw))
      => :threw)))

;; ===========================================================================
;; 2. Override injection (task 003)
;; ===========================================================================

(specification "apply-debug-overrides layers provider/model/temperature/system above node params"
  (component "explicit provider+model pins the candidate (no failover)"
    (let [{:keys [params pinned]}
          (ellm/apply-debug-overrides {:model :smart :temperature 0.2}
            {:provider :openai :model "gpt-4o" :temperature 0.9 :system "You are X"})]
      (assertions
        "a pinned candidate carries provider+model verbatim"
        (select-keys pinned [:provider :model]) => {:provider :openai :model "gpt-4o"}
        "temperature + system override the node params"
        (:temperature params) => 0.9
        (:system params) => "You are X")))
  (component "an alias override selects a single keyword pick and clears :models"
    (let [{:keys [params pinned]}
          (ellm/apply-debug-overrides {:model :smart :models [{:provider :a} {:provider :b}]}
            {:alias "fast"})]
      (assertions
        "no pin (provider+model not both given)"  pinned => nil
        "the alias becomes the single :model pick" (:model params) => :fast
        "the node :models vector is cleared"       (:models params) => nil)))
  (component ":system-append appends to the node's existing system (keeps node instructions)"
    (assertions
      "appended after a blank-line separator"
      (-> (ellm/apply-debug-overrides {:system "BASE RULES"} {:system-append "EXTRA: strawberries"})
        :params :system)
      => "BASE RULES\n\nEXTRA: strawberries"
      "append with no existing system yields just the append text"
      (-> (ellm/apply-debug-overrides {} {:system-append "ONLY THIS"}) :params :system)
      => "ONLY THIS"
      ":system (replace) then :system-append (append) compose"
      (-> (ellm/apply-debug-overrides {:system "NODE"} {:system "REPLACED" :system-append "ADDED"})
        :params :system)
      => "REPLACED\n\nADDED"))
  (component "empty overrides leave params untouched"
    (assertions
      (ellm/apply-debug-overrides {:model :smart} {}) => {:params {:model :smart} :pinned nil})))

(specification "normalize-overrides translates the wire override map to the agent payload"
  (assertions
    "string-keyed wire map: provider/alias -> kw, model -> string, messages -> {:role :text}"
    (dc/normalize-overrides {"provider" "openai" "model" "gpt-4o" "temperature" 0.3
                             "system"   "sys" "messages" [{"role" "user" "text" "hi"}
                                                          {"role" "assistant" "text" "yo"}]})
    => {:provider :openai :model "gpt-4o" :temperature 0.3 :system "sys"
        :messages [{:role :user :text "hi"} {:role :assistant :text "yo"}]}
    "keyword-keyed wire map normalizes the same way"
    (dc/normalize-overrides {:alias "smart"}) => {:alias :smart}
    "an empty/absent map yields an empty payload"
    (dc/normalize-overrides nil) => {}))

;; ===========================================================================
;; 3. Replay policy integration shape (task 004) — the control surface feeds
;;    replay-aware-dispatch; captured-by-match vs live, guard withholds.
;; ===========================================================================

(defrecord CountingTool [kw runs]
  tp/Tool
  (tool-name [_] kw)
  (description [_] "test tool")
  (input-schema [_] [:map])
  (invoke [_ input] (swap! runs inc) {:result (str "LIVE:" (pr-str input)) :is-error false}))

(defn- seed-tool-capture! [store session-id]
  (let [cap (capture/capture-blob!
              {:store store :session-id session-id :node-id :writer :visit 0}
              1 "tool-results/toolu_1" "FILE BODY" "FILE BODY")]
    (proto/append-event! store session-id
      {:event :llm/tool-result
       :data  {:tool_use_id "toolu_1" :tool :fs/read :input {:path "a.txt"}
               :io/ref (:io/ref cap)}})
    nil))

(specification "replay policy: make-index + replay-aware-dispatch serve captured-by-match, live, withheld"
  (let [store (mem/new-store)
        _     (seed-tool-capture! store "parent")
        idx   (dr/make-index store {:source "parent"})]
    (component "a matching call returns the captured result tagged `captured`, tool NOT run"
      (let [runs (atom 0)
            out  (dr/replay-aware-dispatch
                   {:replay idx :parent-store store :policy {:mode :replay-then-live}
                    :tool-registry (tp/new-registry [(->CountingTool :fs/read runs)])}
                   {:node-id :writer :visit 0 :turn 1} :fs/read :fs/read {:path "a.txt"})]
        (assertions
          "tagged captured"      (:replay/source out) => dr/tag-captured
          "captured content"     (:result out) => "FILE BODY"
          "live tool never ran"  @runs => 0)))
    (component "a non-matching call runs live tagged `live` + flagged unmatched"
      (let [runs (atom 0)
            out  (dr/replay-aware-dispatch
                   {:replay idx :parent-store store :policy {:mode :replay-then-live}
                    :tool-registry (tp/new-registry [(->CountingTool :fs/read runs)])}
                   {:node-id :writer :visit 0 :turn 1} :fs/read :fs/read {:path "NEW.txt"})]
        (assertions
          "tagged live"       (:replay/source out) => dr/tag-live
          "flagged unmatched" (:replay/unmatched out) => true
          "live tool ran"     @runs => 1)))
    (component "the destructive guard withholds an unmatched destructive call when configured :deny"
      (let [runs (atom 0)
            out  (dr/replay-aware-dispatch
                   {:replay nil :parent-store store
                    :policy {:mode :replay-then-live :destructive :deny}
                    :tool-registry (tp/new-registry [(->CountingTool :fs/write runs)])}
                   {:node-id :writer :visit 0 :turn 1} :fs/write :fs/write {:path "x" :content "y"})]
        (assertions
          "withheld error"    (:is-error out) => true
          "guard flag set"    (:guard/withheld out) => true
          "tool did NOT run"  @runs => 0)))))

;; ===========================================================================
;; 4. Breakpoint + turn navigation (task 005) — incl. a real parked worker
;; ===========================================================================

(specification "controller arm / next / back / continue drive the LLM turn-gate"
  (component "arming sets turn-armed?; await parks only when armed"
    (let [c (dbg/new-controller {})]
      (assertions
        "not armed initially -> await returns nil immediately (no park)"
        (dbg/turn-armed? c) => false
        (dbg/await-turn-release! c) => nil)
      (dbg/arm-llm-breakpoint! c)
      (assertions
        "armed after arm-llm-breakpoint!"
        (dbg/turn-armed? c) => true)))

  (component "turn-back! moves the pointer (clamped at 0); turn-next! advances it"
    (let [c (dbg/new-controller {})]
      (dbg/turn-next! c) (dbg/turn-next! c)
      (assertions "two nexts advance the turn-index to 2" (:turn-index @c) => 2)
      (dbg/turn-back! c)
      (assertions "back moves it to 1" (:turn-index @c) => 1)
      (dbg/turn-back! c) (dbg/turn-back! c) (dbg/turn-back! c)
      (assertions "back is clamped at 0" (:turn-index @c) => 0)))

  (component "a real parked worker releases on turn-next! (one turn) then re-arms"
    (let [c       (dbg/new-controller {})
          log     (atom [])
          ;; arm BEFORE the worker starts so the first turn boundary parks (no race).
          _       (dbg/arm-llm-breakpoint! c)
          ;; A fake worker turn loop: park at each turn boundary, record release.
          worker  (future
                    (dotimes [_ 3]
                      (let [rel (dbg/await-turn-release! c)]
                        (swap! log conj rel))))
          ;; spin until the worker is parked on the gate before releasing.
          park!   (fn [] (loop [i 0]
                           (when (and (dbg/turn-armed? c) (< i 200))
                             (Thread/sleep 5) (recur (inc i)))))]
      (park!)                          ; worker parked on turn 1 (arm cleared on entry)
      (dbg/turn-next! c)               ; release turn 1, re-arm
      (park!)                          ; worker parked on turn 2
      (dbg/turn-next! c)               ; release turn 2, re-arm
      (park!)                          ; worker parked on turn 3
      (dbg/turn-continue! c)           ; release turn 3, run free
      (assertions
        "the worker completed (3 turn boundaries crossed)"
        (deref worker 2000 :timeout) => nil
        "two nexts delivered :next, the continue delivered :continue"
        @log => [:next :next :continue])))

  (component "continue! (control surface) releases BOTH gates and is nil-safe"
    (let [c (dbg/new-controller {:initial-pause? true})]
      (dbg/arm-llm-breakpoint! c)
      (dc/continue! c)
      (assertions
        "turn arm cleared"        (dbg/turn-armed? c) => false
        "per-event gate released" (dbg/paused? c) => false))
    (assertions
      "continue! on a nil controller is a safe no-op"
      (dc/continue! nil) => nil)))

;; ===========================================================================
;; 5. debug-frame / model-catalog / conversation builders (task 006)
;; ===========================================================================

(specification "debug-frame reflects running / paused-at-turn / branch-running modes"
  (let [c (dbg/new-controller {})]
    (assertions
      "no controller -> running, unarmed, no branch"
      (dc/debug-frame nil) => {:kind "debug" :mode "running" :turn-index nil
                               :breakpoint-armed false :branch nil}
      "armed controller -> paused-at-turn with the live turn-index"
      (do (dbg/arm-llm-breakpoint! c)
          (select-keys (dc/debug-frame c) [:mode :breakpoint-armed :turn-index]))
      => {:mode "paused-at-turn" :breakpoint-armed true :turn-index 0}
      "an :branch extra -> branch-running"
      (:mode (dc/debug-frame c {:branch {:session-id "b"}})) => "branch-running")))

(specification "model-catalog enumerates configured aliases + expanded targets + preferences"
  (let [cfg     {:llm/aliases {:smart [{:provider :openai :model "gpt-4o"}]
                               :fast  [{:provider :anthropic :model "claude-haiku"}]}
                 :llm/preferences [:smart :fast]}
        catalog (dc/model-catalog cfg)
        smart   (first (filter #(= "smart" (:alias %)) (:aliases catalog)))]
    (assertions
      "frame kind is model-catalog"
      (:kind catalog) => "model-catalog"
      "alias keywords are wire name-strings"
      (set (map :alias (:aliases catalog))) => #{"smart" "fast"}
      "each alias's targets expand to {:provider :model} with provider as a name-string"
      (:targets smart) => [{:provider "openai" :model "gpt-4o"}]
      "preferences are ordered name-strings"
      (:preferences catalog) => ["smart" "fast"])))

(specification "conversation reconstructs the editable transcript from captured turn requests"
  (let [store (mem/new-store)
        sid   "conv-sess"
        cap   {:store store :session-id sid :node-id :writer :visit 0}]
    ;; capture two turn requests (turn 0, turn 1); turn 2 absent -> walk stops.
    (capture/capture-request! cap 0
      {:system "SYS" :model "gpt-4o"
       :messages [{:role :user :content [{:type :text :text "Q1"}]}]}
      "Q1")
    (capture/capture-request! cap 1
      {:system "SYS" :model "gpt-4o"
       :messages [{:role :user :content "Q2"}
                  {:role :assistant :content [{:type :text :text "A1"}]}]}
      "Q2")
    (let [conv (dc/conversation store sid {:invokeid "inv-1" :node-id :writer :visit 0})]
      (assertions
        "frame kind + echoed coordinates"
        (:kind conv) => "conversation"
        (:invokeid conv) => "inv-1"
        (:node-id conv) => ":writer"
        (:visit conv) => 0
        "walks turns 0,1 and stops at the first missing request"
        (mapv :turn (:turns conv)) => [0 1]
        "turn 0 flattens text-block content to prose"
        (-> conv :turns first :messages) => [{:role "user" :text "Q1"}]
        "turn 1 carries both a string-content and a text-block message, system echoed"
        (-> conv :turns second :system) => "SYS"
        (-> conv :turns second :messages) => [{:role "user" :text "Q2"}
                                              {:role "assistant" :text "A1"}]))))

;; ===========================================================================
;; 6. WS control-op round-trip (task 006) — make-ws-handlers op -> effect + frame
;; ===========================================================================

(defn- handlers+
  "Build `make-ws-handlers` over a real controller + filled control-handle + a
   ws hub with one attached fake client. Returns
   `{:control :answer :controller :hub :handle :env}`. `live-extra` is merged
   into the live env handle (e.g. an :escapement/artifact-store)."
  ([] (handlers+ {}))
  ([live-extra]
   (let [controller (dbg/new-controller {:initial-pause? true})
         hub        (ws/new-hub)
         _          (ws/attach-client! hub (Object.))
         env        (merge {:escapement/transcript-fn nil} (:env live-extra))
         handle     (ch/fill! (ch/new-handle)
                      (merge {:controller controller :env env
                              :session-id "live-sid" :queue nil}
                        (dissoc live-extra :env)))
         hs         (sidecar/make-ws-handlers
                      {:control-handle handle :controller controller :ws-hub hub})]
     (assoc hs :controller controller :hub hub :handle handle :env env))))

(specification "WS op round-trip: arm-llm-breakpoint drives the controller + pushes a debug frame"
  (with-capture
    (fn [sent]
      (let [{:keys [control controller]} (handlers+)]
        (control {:op "arm-llm-breakpoint"})
        (let [frame (last (filter #(= "debug" (:kind %)) (all-frames sent)))]
          (assertions
            "the controller's LLM turn breakpoint is armed"
            (dbg/turn-armed? controller) => true
            "a debug frame was pushed reflecting paused-at-turn"
            (:mode frame) => "paused-at-turn"
            (:breakpoint-armed frame) => true))))))

(specification "WS op round-trip: turn-next advances the turn-index + re-pushes debug"
  (with-capture
    (fn [sent]
      (let [{:keys [control controller]} (handlers+)]
        (control {:op "arm-llm-breakpoint"})
        (control {:op "turn-next"})
        (let [frame (last (filter #(= "debug" (:kind %)) (all-frames sent)))]
          (assertions
            "turn-index advanced to 1"
            (:turn-index @controller) => 1
            "the pushed debug frame carries the advanced turn-index"
            (:turn-index frame) => 1))))))

(specification "WS op round-trip: turn-back moves the pointer back + re-pushes debug"
  (with-capture
    (fn [sent]
      (let [{:keys [control controller]} (handlers+)]
        (control {:op "arm-llm-breakpoint"})
        (control {:op "turn-next"})
        (control {:op "turn-next"})
        (control {:op "turn-back"})
        (assertions
          "two nexts then a back leaves the pointer at 1"
          (:turn-index @controller) => 1
          "the last pushed debug frame agrees"
          (:turn-index (last (filter #(= "debug" (:kind %)) (all-frames sent)))) => 1)))))

(specification "WS op round-trip: request-model-catalog pushes a model-catalog frame"
  (with-capture
    (fn [sent]
      ;; The op calls debug-control/model-catalog with no cfg, which falls back to
      ;; config/load-config off disk; whatever the env has, the frame shape holds.
      (let [{:keys [control]} (handlers+)]
        (control {:op "request-model-catalog"})
        (let [frame (first (filter #(= "model-catalog" (:kind %)) (all-frames sent)))]
          (assertions
            "a model-catalog frame was broadcast with the contract keys"
            (some? frame) => true
            (contains? frame :aliases) => true
            (contains? frame :preferences) => true))))))

(specification "WS op round-trip: request-conversation reads captures off the live artifact-store"
  (with-capture
    (fn [sent]
      (let [art (mem/new-store)]
        (capture/capture-request!
          {:store art :session-id "live-sid" :node-id :writer :visit 0}
          0 {:system "SYS" :messages [{:role :user :content "Hello"}]} "Hello")
        (let [{:keys [control]} (handlers+ {:env {:escapement/artifact-store art}})]
          (control {:op "request-conversation" :invokeid "inv" :node-id ":writer" :visit 0})
          (let [frame (first (filter #(= "conversation" (:kind %)) (all-frames sent)))]
            (assertions
              "a conversation frame was broadcast"
              (some? frame) => true
              "it carries the reconstructed turn-0 transcript"
              (:invokeid frame) => "inv"
              (-> frame :turns first :turn) => 0
              (-> frame :turns first :messages first :text) => "Hello")))))))

(specification "WS op round-trip: rerun-from forks a branch + pushes a branch-running debug frame"
  (with-capture
    (fn [sent]
      (let [{:keys [work-dir parent-id]} (seed-parent-run! {})
            captured-run (atom nil)]
        ;; Build handlers whose live env points at the seeded parent on disk, and
        ;; inject a stub run-fn so no real engine thread starts. We can't pass
        ;; :run-fn through make-ws-handlers, so we redef the runner entry the
        ;; control surface resolves.
        (with-redefs [escapement.runner/run! (fn [opts] (reset! captured-run opts) :ran)]
          (let [registry (reify sp/StatechartRegistry
                           (register-statechart! [_ _ _] nil)
                           (get-statechart [_ _] {:fake :chart}))
                env    {:escapement/session-dir (str work-dir "/" parent-id)
                        :escapement/transcript-fn nil
                        ::sc/statechart-registry registry}
                {:keys [control]} (handlers+ {:env env :session-id parent-id})]
            (control {:op "rerun-from" :session-id parent-id :node-id ":writer"
                      :visit 0 :turn 1
                      :overrides {"provider" "openai" "model" "gpt-4o" "temperature" 0.5}})
            (let [frame (last (filter #(= "debug" (:kind %)) (all-frames sent)))
                  ran   (deref (future (loop [i 0]
                                         (cond @captured-run @captured-run
                                               (> i 200) nil
                                               :else (do (Thread/sleep 5) (recur (inc i))))))
                          3000 nil)]
              (assertions
                "a branch-running debug frame was pushed with parentage"
                (:mode frame) => "branch-running"
                (-> frame :branch :parent) => parent-id
                "the branch run! was invoked with resume? + the debug seams"
                (:resume? ran) => true
                "the normalized overrides were scoped to node/visit/turn"
                ;; The override `:node-id` is the STATE-ID KEYWORD (`:writer`),
                ;; not the colon-prefixed wire string — `rerun-from!` re-keys the
                ;; wire `":writer"` so it matches `(context-element-id env)` in
                ;; `llm_conversation` (else the override silently never applies).
                (select-keys (:debug-overrides ran) [:provider :model :temperature :node-id :visit :turn])
                => {:provider :openai :model "gpt-4o" :temperature 0.5
                    :node-id :writer :visit 0 :turn 1}
                "a replay-then-live policy sourced from the parent was attached"
                (:source (:debug-replay-policy ran)) => parent-id
                (:mode (:debug-replay-policy ran)) => :replay-then-live))))))))
