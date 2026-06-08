(ns escapement.debug.replay
  "Replay-aware tool dispatch for a forked-branch continuation (the time-travel
   debugger's safety-critical layer; see `docs/opentui-debugger.md` and the
   `:debug/replay-policy` env key in `escapement.engine.env`).

   On a branch continuation the deterministic prefix is restored from the
   checkpoint (NOT re-run). The changed LLM turn hits the provider live; the tool
   calls it makes are routed through `replay-aware-dispatch`, which:

     1. consults the PARENT session's captured tool-results by deterministic
        match (`escapement.replay/lookup-captured-tool-result`); a MATCH returns
        the captured result WITHOUT executing the tool, tagged `:replay/source
        \"captured\"`;
     2. on a MISS (the expected case once the conversation diverges) executes the
        tool LIVE and tags it `:replay/source \"live\"`, after consulting a
        configurable DESTRUCTIVE-TOOL GUARD so file/shell writes can be withheld
        or confirmed.

   This is an engine-core add-on (`escapement.debug.*`): it requires only
   `escapement.replay` + `escapement.tools.protocol`, never the UI/Pathom/RAD
   layer, so the architecture-boundary test stays green. It is reached from the
   llm-conversation worker / control surface via plain require or
   `requiring-resolve`; nothing in the core statically depends on it.

   The `:replay/source` tag field name is the wire contract from task 001
   (`data[\"replay/source\"]` on the `event` frame): `\"captured\"` | `\"live\"`."
  (:require
    [escapement.replay :as replay]
    [escapement.tools.protocol :as tp]))

;; The `:replay/source` tag values (wire contract, task 001).
(def ^:const tag-captured "captured")
(def ^:const tag-live "live")

(def default-destructive-tools
  "Built-in tool keywords whose live execution mutates the world (filesystem /
   shell). Read-only tools (`:fs/read`, `:fs/glob`, `:fs/grep`, `:web/search`,
   `:web/fetch`) are NOT here. Used by `default-destructive?` as the conservative
   default guard set when a branch replay diverges and would re-run side effects
   live. Callers can override via the policy's `:destructive?` predicate."
  #{:fs/write :fs/edit :fs/multi-edit :shell/run})

(defn default-destructive?
  "Default destructive-tool predicate: true when `tool-kw` is in
   `default-destructive-tools`."
  [tool-kw _input]
  (contains? default-destructive-tools tool-kw))

(defn make-index
  "Build (once per branch continuation) the parent's tool-result match index from
   `:debug/replay-policy`. `store` is the PARENT's combined Transcript+Artifact
   store; the policy's `:source` is the parent session-id. Returns
   `{:index <map> :source <parent-session-id>}`, or `nil` when no policy / no
   `:source` (⇒ everything runs live)."
  [store policy]
  (when-let [source (:source policy)]
    {:index  (replay/build-tool-result-index store source)
     :source source}))

(defn- guard-result
  "Apply the destructive guard to an UNMATCHED (would-run-live) destructive call.
   `guard` is one of:
     * `:allow`    — run it (default when no guard configured);
     * `:deny`     — withhold; return a synthetic error tool result;
     * a fn `(fn [tool-kw input] => :allow | :deny | <result-map>)` — a
       confirm/allowlist hook. A returned map is used verbatim as the result
       (e.g. `{:result \"withheld\" :is-error true}`).
   Returns either `:allow` (proceed to live dispatch) or a result map to short-circuit."
  [guard tool-kw input]
  (let [decision (cond
                   (fn? guard) (guard tool-kw input)
                   :else       (or guard :allow))]
    (cond
      (= decision :allow) :allow
      (= decision :deny)  {:result      (str "Destructive tool " tool-kw
                                          " withheld by replay guard (branch continuation).")
                           :is-error    true
                           :guard/withheld true}
      (map? decision)     (assoc decision :guard/withheld true)
      :else               :allow)))

(defn replay-aware-dispatch
  "Replay-aware single tool dispatch. Returns a result map shaped like
   `escapement.tools.protocol/dispatch`'s output
   (`{:result :is-error :resolved-path}`) PLUS a `:replay/source` tag
   (`\"captured\"` | `\"live\"`) and, when withheld, `:guard/withheld true`.

   Args:
     * `ctx` — `{:replay {:index … :source …}      ; from `make-index`, or nil
                 :parent-store <Artifact+Transcript store of the parent>
                 :policy <:debug/replay-policy map> ; :mode / :flag-unmatched? / :destructive?
                 :tool-registry <registry>}`        ; for live dispatch
     * `coords` — `{:node-id :visit :turn}` of the CURRENT (branch) turn issuing
                  the call (matched against captured coordinates).
     * `tool-kw` — the resolved tool keyword.
     * `tool-label` — the captured label the parent recorded under the event's
                  `:tool` (so the match domain agrees with the index); usually
                  `tool-kw`.
     * `input` — the decoded tool input map.

   Mode handling (`:mode` in policy, default `:replay-then-live`):
     * `:all-replay`       — only serve matches; a miss is an error (no live run).
     * `:all-live`         — never consult captures; always live (still guarded).
     * `:replay-then-live` — match ⇒ captured; miss ⇒ guarded live (the default)."
  [{:keys [replay parent-store policy tool-registry]} coords tool-kw tool-label input]
  (let [mode    (get policy :mode :replay-then-live)
        index   (:index replay)
        source  (:source replay)
        guard   (get policy :destructive :allow)
        destr?  (get policy :destructive? default-destructive?)
        ;; Try captured-result match unless caller forced all-live.
        hit     (when (and index (not= mode :all-live))
                  (replay/lookup-captured-tool-result parent-store source index
                    {:node-id (:node-id coords)
                     :visit   (:visit coords)
                     :turn    (:turn coords)
                     :tool    tool-label
                     :input   input}))]
    (cond
      (:matched? hit)
      {:result        (:content hit)
       :is-error      false
       :replay/source tag-captured
       :io/ref        (:io/ref hit)}

      (= mode :all-replay)
      {:result        (str "No captured tool-result to replay for " tool-kw
                        " (mode :all-replay forbids live execution).")
       :is-error      true
       :replay/source tag-captured
       :replay/unmatched true}

      ;; Miss ⇒ would run live. Consult the destructive guard first.
      :else
      (let [guarded (if (destr? tool-kw input)
                      (guard-result guard tool-kw input)
                      :allow)]
        (if (map? guarded)
          (assoc guarded :replay/source tag-live :replay/unmatched true)
          (let [{:keys [result is-error resolved-path]}
                (tp/dispatch tool-registry tool-kw (or input {}))]
            (cond-> {:result        result
                     :is-error      (boolean is-error)
                     :replay/source tag-live
                     :replay/unmatched true}
              resolved-path (assoc :resolved-path resolved-path))))))))
