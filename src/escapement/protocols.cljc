(ns escapement.protocols
  "Runtime-centric IO protocols for Escapement — declarations only.

  These are the single seam that lets the runtime persist without knowing its host. A disk
  backend (bb/CLJ) and a browser backend (CLJS) each implement them; one construction site
  decides which. Read-side EQL/Pathom resolvers and the replay primitives call these same
  methods, so they too are host-agnostic.

  Design rules (see `io-refactor-plan.md`):

   * **One protocol per concern**, session-scoped, with `session-id` a POSITIONAL argument so a
     cross-session operation can't be expressed by accident. The one cross-session op — \"what
     sessions exist\" — is its own `SessionIndex`.
   * **Stores are dumb**: they stamp nothing they aren't given, EXCEPT `:transcript/seq`, which
     the transcript store owns (it owns ordering). All other enrichment (node-id/visit/turn/ts)
     happens in the emit layer before these are called.
   * **Checkpoints are NOT here** — they keep using the library's
     `com.fulcrologic.statecharts.protocols/WorkingMemoryStore` unchanged.
   * A single backend record MAY implement all three of these (plus `WorkingMemoryStore`) at once.")

(defprotocol TranscriptStore
  "Append-and-read access to one session's ordered transcript of EDN event maps. The disk
   backend serializes these as JSONL; the browser backend stores them as records — the EDN map
   is the interface, the serialization is an implementation detail."
  (append-event! [store session-id event]
    "Persist one transcript `event` (a map WITHOUT `:transcript/seq`). The store ASSIGNS a
     gapless, monotonic per-session `:transcript/seq` synchronously and returns the stored event
     (with the seq). Write TIMING is backend-defined — the disk backend buffers through a writer
     thread; the browser backend fires async — so callers must not assume durability on return.")
  (read-events [store session-id query]
    "Return this session's events as a seq of maps in `:transcript/seq` order. `query` is
     `nil`/`{}` => all, otherwise a map of optional predicates:

       * `:types`    — set of `:transcript/kind` values to keep
       * `:node-id`  — only events whose `:transcript/node-id` matches
       * `:from-seq` — inclusive lower `:transcript/seq` bound
       * `:to-seq`   — inclusive upper `:transcript/seq` bound
       * `:limit`    — cap the number returned (after ordering/filtering)

     v1 backends MAY ignore the predicates and return all (the caller filters); the signature
     lets a later indexed backend push them down without changing callers."))

(defprotocol ArtifactStore
  "Read/write access to one session's artifacts. Stores BOTH classes, distinguished only by the
   `path` addressing key (see `io-refactor-plan.md` §0):

     * author files      — `\"artifacts/<name>\"`                            mutable, latest-wins
     * captured-I/O blobs — `\"nodes/<node-id>/<visit>/turns/<n>/request\"`   immutable; the `:io/ref`

   There is deliberately NO separate blob store: captured LLM I/O is just a write whose `path` is
   a structured, node-relative locator. The locator IS the opaque id, so on the disk backend the
   tree is walkable with no tooling and `:io/ref` round-trips to a relative path with no
   translation table. node->visit->turn grouping and the replay primitives are a CLJC layer over
   `list-artifacts` + `TranscriptStore/read-events`."
  (write-artifact! [store session-id path content meta]
    "Write `content` (a string) at `path` for `session-id`. `meta` is a map the store persists
     alongside the bytes; the emit layer supplies `:transcript/node-id`, `:transcript/visit`,
     `:transcript/turn`, `:artifact/class` (`:author` | `:captured-io`), and optionally
     `:artifact/content-type`. Returns the stored artifact summary (see `list-artifacts`).")
  (read-artifact [store session-id path]
    "Return the full `content` string previously written at `path`, or `nil` if absent.")
  (list-artifacts [store session-id]
    "Return a seq of artifact summary maps for `session-id`. Each item carries `:artifact/path`,
     `:artifact/size`, `:artifact/content-type`, `:artifact/class`, and the source coordinates
     `:transcript/node-id` / `:transcript/visit` / `:transcript/turn` (when present). Heavy
     `:artifact/content` is NOT included — it is loaded lazily via `read-artifact`. Supports
     prefix scans (e.g. everything under `\"nodes/<node-id>/\"`) so the §5b layer can assemble one
     invocation cheaply."))

(defprotocol SessionIndex
  "The ONE cross-session operation. The library has no session enumeration — `WorkingMemoryStore`
   is pure get/save/delete by id, and the chart registry lists chart definitions, not sessions —
   so enumeration is ours. Identity still comes from the library (`:com.fulcrologic.statecharts/session-id`)."
  (list-sessions [store]
    "Return a seq of session summary maps, each with `:com.fulcrologic.statecharts/session-id`,
     `:com.fulcrologic.statecharts/statechart-src`, `:session/started-at`, `:session/status`,
     `:session/parent-id` (a session-id), and `:session/child-ids`."))
