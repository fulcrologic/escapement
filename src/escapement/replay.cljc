(ns escapement.replay
  "Refinement/replay primitives over captured LLM I/O (see `io-refactor-plan.md` §5b).

   This namespace ships granularity #1: **single-turn refine** — re-issue ONE captured turn with a
   tuned prompt/model/params, with no statechart engine involved. It is the tight prompt-tuning
   inner loop: load the captured request, deep-merge overrides, send it to a live backend, and hand
   back the new response next to the original request for diffing.

   Node-invocation refine (#2, from `seed.edn`) and sub-chart refine (#3, from a checkpoint) are
   designed-for but not implemented here.

   CLJC with the backend injected (never reached for globally), so the same code runs against the
   bb backend or a browser LLM remote."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [com.fulcrologic.statecharts.promise :as p]
    [escapement.capture :as capture]
    [escapement.llm.protocol :as llm]
    [escapement.protocols :as proto]))

(defn- read-edn [s]
  (edn/read-string {:default tagged-literal} s))

(defn deep-merge
  "Recursively merge maps; for matching keys whose values are both maps, merge them, otherwise the
   later value wins. Non-map collections (e.g. `:messages`) are replaced wholesale."
  [& maps]
  (let [maps (remove nil? maps)]
    (when (seq maps)
      (apply merge-with
        (fn [a b] (if (and (map? a) (map? b)) (deep-merge a b) b))
        maps))))

(defn load-request
  "Load the captured base request map for the turn at `(node-id, visit, turn)` of `session-id` from
   the `store` (an `ArtifactStore`), or `nil` if no request was captured there."
  [store session-id node-id visit turn]
  (let [path (str (capture/turn-dir node-id visit turn) "/request.edn")]
    (some-> (proto/read-artifact store session-id path) read-edn)))

(defn refine-turn
  "Re-issue the single captured LLM turn at `(node-id, visit, turn)` of `session-id`, tuned by
   `opts`, WITHOUT re-running the chart. Returns
   `{:request <effective> :response <new Response> :original-request <captured>}`; the caller diffs.

   `opts`:
     * `:backend`   (required) — an `escapement.llm.protocol/LLMBackend` to issue the turn against.
     * `:overrides` (optional) — a partial request map deep-merged onto the captured request, e.g.
                                 `{:system \"tuned prompt\" :model \"claude-opus-4-7\" :temperature 0.2}`.

   Throws if no request was captured at the coordinates (nothing to refine)."
  [store session-id node-id visit turn {:keys [backend overrides]}]
  (assert backend "refine-turn requires a :backend")
  (let [original (load-request store session-id node-id visit turn)]
    (when-not original
      (throw (ex-info "No captured request to refine at these coordinates"
               {:reason :no-captured-request
                :session-id session-id :node-id node-id :visit visit :turn turn})))
    (let [effective (deep-merge original overrides)
          response  (p/await! (llm/send-turn* backend effective nil))]
      {:request          effective
       :response         response
       :original-request original})))

;; ----------------------------------------------------------------------------
;; Granularity #2/#3 building block — tool-result replay-by-match.
;;
;; On a forked branch continuation (see `escapement.debug.branch`,
;; `:debug/replay-policy`) the deterministic prefix is restored from the
;; checkpoint (NOT re-run). The changed LLM turn hits the provider live; tool
;; calls it makes are served from the PARENT's captured tool-results WHEN THEY
;; MATCH (same node/visit/turn + tool name + decoded input), so we don't
;; re-execute side effects we already have an answer for. Unmatched calls (the
;; expected case once the conversation diverges) execute live and are flagged.
;;
;; Match key is `[node-id visit turn tool input]`. tool_use_ids are NOT stable
;; across a re-run, so we never key on them; instead we index the parent's
;; captured `:llm/tool-result` transcript events (which carry `:tool` + `:input`
;; + an `:io/ref` to the result blob) and join to the blob for the content.
;; ----------------------------------------------------------------------------

(defn ref->coords
  "Parse the invocation coordinates out of a captured tool-result `:io/ref`
   locator of the form `nodes/<enc-node-id>/<visit>/turns/<turn>/tool-results/<id>.edn`.
   Returns `{:node-seg <string> :visit <int> :turn <int>}` or `nil` if the path
   is not a tool-result locator. `:node-seg` is the filesystem-encoded node-id
   segment (so callers compare encoded-to-encoded, never reconstructing the kw)."
  [io-ref]
  (when (string? io-ref)
    (let [m (re-matches #"nodes/([^/]+)/(\d+)/turns/(\d+)/tool-results/.*\.edn" io-ref)]
      (when m
        {:node-seg (nth m 1)
         :visit    (parse-long (nth m 2))
         :turn     (parse-long (nth m 3))}))))

(defn- norm-tool
  "Normalize a tool label to a stable string key. The parent's captured `:tool`
   may be a keyword (memory store, verbatim) OR a string (disk store, after a
   JSON round-trip drops the leading colon, e.g. `:test/echo` -> `\"test/echo\"`).
   The branch lookup passes the resolved tool keyword. Comparing in the keyword's
   `str`-minus-leading-colon domain makes both stores match identically."
  [tool]
  (cond
    (keyword? tool) (subs (str tool) 1)
    (nil? tool)     nil
    :else           (str tool)))

(defn- enc-node [node-id]
  ;; Mirror `capture/encode-node-id` (private there) for the index key. Kept in
  ;; lockstep with the locator scheme in `capture.cljc`.
  (if (nil? node-id)
    "ROOT"
    (-> (str node-id) (subs 1) (str/replace "/" "_"))))

(defn build-tool-result-index
  "Scan `session-id`'s captured `:llm/tool-result` transcript events in `store`
   (a `TranscriptStore` + `ArtifactStore`, e.g. the parent session's stores) and
   return a match index:

     {[node-seg visit turn tool input] {:io/ref <locator> :tool_use_id <id>} …}

   `tool` is the captured tool label (string or keyword as recorded in the
   event's `:data`), `input` the decoded tool input map. Later captures for the
   same key win (rare; a turn re-issues only on fallback, which keeps the first
   tool-result). The result CONTENT is loaded lazily by `lookup-captured-tool-result`
   so the index stays cheap.

   `read-events` may be passed explicitly for hosts whose TranscriptStore is
   reached differently; defaults to `proto/read-events`."
  ([store session-id]
   (build-tool-result-index store session-id proto/read-events))
  ([store session-id read-events-fn]
   ;; Tolerate BOTH event shapes: the disk-read-normalized form
   ;; (`:transcript/kind`/`:transcript/data`, `:io/ref` hoisted) AND the raw
   ;; append form (`:event`/`:data`). The memory store returns events verbatim;
   ;; the disk store normalizes. We don't push `:types` down (the raw store
   ;; ignores it / lacks `:transcript/kind`); we filter here over both shapes.
   (->> (read-events-fn store session-id nil)
     (reduce
       (fn [idx ev]
         (let [kind    (or (:transcript/kind ev) (some-> (:event ev) keyword))
               data    (or (:transcript/data ev) (:data ev))
               io-ref  (or (:io/ref ev) (:io/ref data))
               coords  (ref->coords io-ref)
               tool    (:tool data)
               input   (:input data)]
           (if (and (= kind :llm/tool-result) coords tool io-ref)
             (assoc idx
               [(:node-seg coords) (:visit coords) (:turn coords) (norm-tool tool) input]
               {:io/ref io-ref :tool_use_id (:tool_use_id data)})
             idx)))
       {}))))

(defn lookup-captured-tool-result
  "Look a tool call up in `index` (from `build-tool-result-index`) by deterministic
   coordinates + tool + input. On a hit, load the captured result CONTENT from
   `store` via the indexed `:io/ref` and return
   `{:matched? true :content <captured> :io/ref <locator>}`. On a miss return
   `{:matched? false}`.

   `node-id` is the (un-encoded) chart node id; it is encoded internally to match
   the index key. `tool` must equal the captured label (compare in the same domain
   you indexed — pass the same value the live dispatch records)."
  [store session-id index {:keys [node-id visit turn tool input]}]
  (let [k     [(enc-node node-id) visit turn (norm-tool tool) input]
        entry (get index k)]
    (if-let [io-ref (:io/ref entry)]
      (let [raw (proto/read-artifact store session-id io-ref)]
        ;; tool-result blobs are written via `capture-blob!` => `(pr-str content)`.
        ;; Round-trip back to the original value (usually a string, sometimes EDN).
        {:matched?    true
         :content     (some-> raw read-edn)
         :io/ref      io-ref
         :tool_use_id (:tool_use_id entry)})
      {:matched? false})))
