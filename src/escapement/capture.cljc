(ns escapement.capture
  "The capture layer: externalize heavy LLM I/O to the `ArtifactStore` and hand back a small
   `{:io/ref :io/snippet}` reference for the transcript event. This is how the JSONL stops being
   the system of record for payloads (see `io-refactor-plan.md` §0).

   A captured blob is addressed by a node-relative LOCATOR — the path IS the opaque id, so the
   disk backend's tree is walkable and `:io/ref` round-trips with no translation table:

     nodes/<node-id>/<visit>/seed.edn
     nodes/<node-id>/<visit>/turns/<turn>/request.edn
     nodes/<node-id>/<visit>/turns/<turn>/response.edn
     nodes/<node-id>/<visit>/turns/<turn>/tool-results/<tool_use_id>.edn

   Payloads are serialized as EDN (not JSON) so a captured request round-trips to the exact Clojure
   data structure `escapement.replay/refine-turn` re-feeds — keywords, enums, and nested schemas
   survive losslessly.

   No filesystem here: this namespace only calls `ArtifactStore` and does string work, so it is
   host-portable (bb/CLJ/CLJS)."
  (:require
    [clojure.string :as str]
    [escapement.protocols :as proto]))

(def ^:const snippet-len
  "Maximum length (characters, ellipsis included) of the human-correlation snippet kept inline in a
   transcript event. The full value lives in the referenced blob."
  80)

(defn snippet
  "Return a ≤`snippet-len`-char human-correlation slice of `s` (coerced to string). Appends a single
   ellipsis char on overflow, so the result never exceeds `snippet-len`."
  [s]
  (let [s (if (some? s) (str s) "")]
    (if (<= (count s) snippet-len)
      s
      (str (subs s 0 (dec snippet-len)) "…"))))

(defn- encode-node-id
  "Filesystem-safe segment for `node-id` (a chart element id, e.g. `:writer` or `:a/b`). Drops the
   leading colon and replaces `/` so a namespaced id stays a single path segment. `nil` (top level)
   becomes `ROOT`."
  [node-id]
  (if (nil? node-id)
    "ROOT"
    (-> (str node-id) (subs 1) (str/replace "/" "_"))))

(defn turn-dir
  "The directory locator (no trailing slash) for one logical turn of a node invocation."
  [node-id visit turn]
  (str "nodes/" (encode-node-id node-id) "/" visit "/turns/" turn))

(defn seed-locator
  "The node-relative locator (`nodes/<enc-node-id>/<visit>/seed.edn`) for the replayable seed of the
   `(node-id, visit)` invocation. The single source of truth for the seed path — both `capture-seed!`
   (write) and `escapement.replay/load-seed` (read) address through it."
  [node-id visit]
  (str "nodes/" (encode-node-id node-id) "/" visit "/seed.edn"))

(defn capture-blob!
  "Write `data` (any EDN-serializable value) as the captured-I/O blob named `kind` for the turn at
   `(node-id, visit, turn)` of `session-id`, and return `{:io/ref <locator> :io/snippet <≤80 chars
   of snippet-text>}` for the transcript event to carry.

   `capture` is `{:store <ArtifactStore> :session-id … :node-id … :visit …}`. `kind` is a relative
   name such as `\"request\"`, `\"response\"`, or `\"tool-results/<tool_use_id>\"`; the `.edn` suffix
   is appended here. `snippet-text` is the human string to slice (the trailing user prompt, the
   first assistant text, the tool result, …) — distinct from the serialized `data`."
  [{:keys [store session-id node-id visit]} turn kind data snippet-text]
  (let [path (str (turn-dir node-id visit turn) "/" kind ".edn")]
    (proto/write-artifact! store session-id path (pr-str data)
      {:transcript/node-id node-id
       :transcript/visit   visit
       :transcript/turn    turn
       :artifact/class     :captured-io})
    {:io/ref path :io/snippet (snippet snippet-text)}))

(defn capture-request!
  "First-write-wins capture of a turn's request blob. A logical turn may issue several physical
   requests (model fallback, `:max_tokens` continuation with a prefill); only the FIRST — the base
   turn request — is kept, so `escapement.replay/refine-turn` tunes the real prompt rather than a
   continuation prefill. Returns `{:io/ref :io/snippet}` (the ref is deterministic whether or not
   this call wrote)."
  [{:keys [store session-id node-id visit] :as _capture} turn request snippet-text]
  (let [path (str (turn-dir node-id visit turn) "/request.edn")]
    (when-not (proto/read-artifact store session-id path)
      (proto/write-artifact! store session-id path (pr-str request)
        {:transcript/node-id node-id :transcript/visit visit
         :transcript/turn turn :artifact/class :captured-io}))
    {:io/ref path :io/snippet (snippet snippet-text)}))

(defn capture-output!
  "Capture the idle/verdict OUTPUT of one logical turn — the value the conversation hands its parent
   chart on the `on-end-turn-event` — as `nodes/<node-id>/<visit>/turns/<turn>/output.edn`, and return
   `{:io/ref <locator> :io/snippet <≤80 chars>}`. `output` is the EDN map `{:text <full assistant
   text> :verdict <…> :from <id>}`; the snippet slices its `:text`. Per-turn (like request/response)
   so each `on-end-turn-event`'s ref is immutable and distinct across a multi-turn invocation."
  [capture turn output]
  (capture-blob! capture turn "output" output (:text output)))

(defn seed-visit-counts
  "Return `{node-id max-visit}` for every node that already has captured-I/O artifacts in `store`
   for `session-id`. A resumed or restarted run seeds `:escapement/visit-counts` with this so the
   NEXT entry into a node is numbered `max-visit + 1` rather than colliding at `0` and overwriting
   the prior run's blobs. Nodes with no prior artifacts are absent from the result (they start at 0
   via the increment's `(fnil inc -1)`). Pure over `list-artifacts`; safe on a fresh session — an
   empty store yields `{}`."
  [store session-id]
  (reduce
    (fn [acc {class :artifact/class node-id :transcript/node-id visit :transcript/visit}]
      (if (and (= :captured-io class) (some? node-id) (int? visit))
        (update acc node-id (fnil max -1) visit)
        acc))
    {}
    (proto/list-artifacts store session-id)))

(defn capture-seed!
  "Write the replayable `seed` (resolved params + initiating input) for the `(node-id, visit)`
   invocation of `session-id`. Returns the stored artifact summary. The seed is what
   `escapement.replay` granularities #2/#3 re-enter the node from."
  [{:keys [store session-id node-id visit]} seed]
  (proto/write-artifact! store session-id
    (seed-locator node-id visit)
    (pr-str seed)
    {:transcript/node-id node-id
     :transcript/visit   visit
     :artifact/class     :captured-io}))
