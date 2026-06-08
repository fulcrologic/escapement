(ns escapement.debug.replay-test
  "Tests for the replay-aware tool-dispatch layer (time-travel debugger,
   safety-critical task 004): captured-by-match vs. live, destructive guard,
   and mode handling."
  (:require
    [escapement.capture :as capture]
    [escapement.debug.replay :as dr]
    [escapement.protocols :as proto]
    [escapement.replay :as replay]
    [escapement.storage.memory :as mem]
    [escapement.tools.protocol :as tp]
    [fulcro-spec.core :refer [=> assertions component specification]]))

;; ---- a live tool that records how many times it actually ran -------------

(defrecord CountingTool [kw runs]
  tp/Tool
  (tool-name [_] kw)
  (description [_] "test tool")
  (input-schema [_] [:map])
  (invoke [_ input]
    (swap! runs inc)
    {:result (str "LIVE:" (pr-str input)) :is-error false}))

(defn- registry-with [kw runs]
  (tp/new-registry [(->CountingTool kw runs)]))

;; ---- seed a parent session with a captured tool-result -------------------

(def ^:private parent "parent-session")

(defn- seed-parent!
  "Capture a tool-result blob for (node :writer, visit 0, turn 1, tool :fs/read,
   input {:path \"a.txt\"}) and append the matching transcript event, mirroring
   what `llm_conversation`'s `post-tool-result!` writes."
  [store {:keys [tool input result] :or {tool :fs/read input {:path "a.txt"} result "FILE CONTENTS"}}]
  (let [cap   {:store store :session-id parent :node-id :writer :visit 0}
        id    "toolu_abc"
        ref   (capture/capture-blob! cap 1 (str "tool-results/" id) result result)]
    (proto/append-event! store parent
      {:event :llm/tool-result
       :data  {:tool_use_id id
               :tool        tool
               :input       input
               :io/ref      (:io/ref ref)}})
    (:io/ref ref)))

(defn- index-for [store]
  (replay/build-tool-result-index store parent))

;; ---- ref->coords / index -------------------------------------------------

(specification "ref->coords parses a tool-result locator"
  (assertions
    "extracts encoded node segment + visit + turn"
    (replay/ref->coords "nodes/writer/0/turns/1/tool-results/toolu_abc.edn")
    => {:node-seg "writer" :visit 0 :turn 1}
    "handles namespaced (underscore-encoded) node ids"
    (replay/ref->coords "nodes/a_b/2/turns/3/tool-results/x.edn")
    => {:node-seg "a_b" :visit 2 :turn 3}
    "returns nil for a non-tool-result path"
    (replay/ref->coords "nodes/writer/0/turns/1/response.edn") => nil
    "returns nil for non-strings"
    (replay/ref->coords nil) => nil))

(specification "build-tool-result-index + lookup-captured-tool-result"
  (let [store (mem/new-store)
        _     (seed-parent! store {})
        index (index-for store)]
    (assertions
      ;; The index normalizes the tool label to a string (keyword from a memory
      ;; store, or already-a-string after a disk JSON round-trip) so both stores
      ;; match identically — see escapement.replay/norm-tool.
      "indexes the captured tool-result by [node-seg visit turn tool input]"
      (contains? index ["writer" 0 1 "fs/read" {:path "a.txt"}]) => true)
    (component "exact match returns the captured content without re-execution"
      (let [hit (replay/lookup-captured-tool-result store parent index
                  {:node-id :writer :visit 0 :turn 1 :tool :fs/read :input {:path "a.txt"}})]
        (assertions
          "matched"   (:matched? hit) => true
          "content round-trips from the blob" (:content hit) => "FILE CONTENTS")))
    (component "different input ⇒ miss"
      (let [hit (replay/lookup-captured-tool-result store parent index
                  {:node-id :writer :visit 0 :turn 1 :tool :fs/read :input {:path "OTHER.txt"}})]
        (assertions "not matched" (:matched? hit) => false)))
    (component "different turn ⇒ miss"
      (let [hit (replay/lookup-captured-tool-result store parent index
                  {:node-id :writer :visit 0 :turn 2 :tool :fs/read :input {:path "a.txt"}})]
        (assertions "not matched" (:matched? hit) => false)))))

;; ---- replay-aware-dispatch: captured path --------------------------------

(specification "replay-aware-dispatch serves a matching captured result without running the tool"
  (let [store  (mem/new-store)
        _      (seed-parent! store {})
        index  (index-for store)
        runs   (atom 0)
        reg    (registry-with :fs/read runs)
        ctx    {:replay {:index index :source parent}
                :parent-store store
                :policy {:mode :replay-then-live}
                :tool-registry reg}
        out    (dr/replay-aware-dispatch ctx
                 {:node-id :writer :visit 0 :turn 1} :fs/read :fs/read {:path "a.txt"})]
    (assertions
      "tagged captured"        (:replay/source out) => dr/tag-captured
      "returns captured content" (:result out) => "FILE CONTENTS"
      "not an error"           (:is-error out) => false
      "the live tool NEVER ran" @runs => 0)))

;; ---- replay-aware-dispatch: live path (unmatched, non-destructive) -------

(specification "an unmatched non-destructive call executes live and is tagged live"
  (let [store  (mem/new-store)
        _      (seed-parent! store {})
        index  (index-for store)
        runs   (atom 0)
        reg    (registry-with :fs/read runs)
        ctx    {:replay {:index index :source parent}
                :parent-store store
                :policy {:mode :replay-then-live}
                :tool-registry reg}
        out    (dr/replay-aware-dispatch ctx
                 {:node-id :writer :visit 0 :turn 1} :fs/read :fs/read {:path "NEW.txt"})]
    (assertions
      "tagged live"          (:replay/source out) => dr/tag-live
      "flagged unmatched"    (:replay/unmatched out) => true
      "ran the live tool"    @runs => 1
      "returns live result"  (:result out) => "LIVE:{:path \"NEW.txt\"}")))

;; ---- destructive guard ----------------------------------------------------

(specification "the destructive guard withholds an unmatched destructive call"
  (let [store  (mem/new-store)
        runs   (atom 0)
        reg    (registry-with :fs/write runs)
        base   {:replay nil :parent-store store :tool-registry reg}]
    (component ":deny withholds and never runs the tool"
      (let [out (dr/replay-aware-dispatch
                  (assoc base :policy {:mode :replay-then-live :destructive :deny})
                  {:node-id :writer :visit 0 :turn 1} :fs/write :fs/write {:path "x" :content "y"})]
        (assertions
          "withheld error"    (:is-error out) => true
          "guard flag set"    (:guard/withheld out) => true
          "tagged live"       (:replay/source out) => dr/tag-live
          "tool did NOT run"  @runs => 0)))
    (component "a confirm fn returning :allow runs the tool"
      (let [out (dr/replay-aware-dispatch
                  (assoc base :policy {:mode :replay-then-live
                                       :destructive (fn [tk _] (when (= tk :fs/write) :allow))})
                  {:node-id :writer :visit 0 :turn 1} :fs/write :fs/write {:path "x" :content "y"})]
        (assertions
          "ran the tool"   @runs => 1
          "not withheld"   (:guard/withheld out) => nil
          "tagged live"    (:replay/source out) => dr/tag-live)))
    (component "a confirm fn returning a result map short-circuits with that map"
      (let [runs2 (atom 0)
            reg2  (registry-with :shell/run runs2)
            out   (dr/replay-aware-dispatch
                    {:replay nil :parent-store store :tool-registry reg2
                     :policy {:destructive (fn [_ _] {:result "needs human ok" :is-error true})}}
                    {:node-id :writer :visit 0 :turn 1} :shell/run :shell/run {:command "rm -rf /"})]
        (assertions
          "uses the supplied result" (:result out) => "needs human ok"
          "withheld flag"            (:guard/withheld out) => true
          "tool did NOT run"         @runs2 => 0)))
    (component "default-destructive? recognises the built-in mutating tools"
      (assertions
        ":shell/run is destructive"  (dr/default-destructive? :shell/run {}) => true
        ":fs/write is destructive"   (dr/default-destructive? :fs/write {}) => true
        ":fs/read is NOT destructive" (dr/default-destructive? :fs/read {}) => false))))

;; ---- modes ---------------------------------------------------------------

(specification "mode handling"
  (let [store  (mem/new-store)
        _      (seed-parent! store {})
        index  (index-for store)
        runs   (atom 0)
        reg    (registry-with :fs/read runs)
        ctx    {:replay {:index index :source parent}
                :parent-store store :tool-registry reg}]
    (component ":all-live ignores captures and always runs live"
      (reset! runs 0)
      (let [out (dr/replay-aware-dispatch (assoc ctx :policy {:mode :all-live})
                  {:node-id :writer :visit 0 :turn 1} :fs/read :fs/read {:path "a.txt"})]
        (assertions
          "ran live despite a matching capture" @runs => 1
          "tagged live"                          (:replay/source out) => dr/tag-live)))
    (component ":all-replay refuses to run an unmatched tool live"
      (reset! runs 0)
      (let [out (dr/replay-aware-dispatch (assoc ctx :policy {:mode :all-replay})
                  {:node-id :writer :visit 0 :turn 1} :fs/read :fs/read {:path "MISS.txt"})]
        (assertions
          "error"                  (:is-error out) => true
          "tagged captured"        (:replay/source out) => dr/tag-captured
          "flagged unmatched"      (:replay/unmatched out) => true
          "tool did NOT run live"  @runs => 0)))))

;; ---- make-index ----------------------------------------------------------

(specification "make-index"
  (let [store (mem/new-store)
        _     (seed-parent! store {})]
    (assertions
      "builds an index from the policy :source"
      (-> (dr/make-index store {:source parent}) :index
        (contains? ["writer" 0 1 "fs/read" {:path "a.txt"}])) => true
      "nil when no :source"
      (dr/make-index store {}) => nil)))
