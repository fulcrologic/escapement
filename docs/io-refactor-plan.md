# Escapement IO Refactor + EQL/UI Plan

## Context

Today Escapement persists everything through **direct, host-specific file IO**:

- Artifacts — `chart/helpers.cljc` `atomic-write!` / `read-artifact!` (temp-file + atomic rename).
- Transcript — `transcript.clj`, a daemon-thread JSONL sink draining a `LinkedBlockingQueue`,
  owning monotonic `:seq` assignment + serialize-error resilience + idempotent close.
- Checkpoints — `engine/store.clj` `FileBackedStore`, the one piece that is *already* behind a
  protocol (`com.fulcrologic.statecharts.protocols/WorkingMemoryStore`).

This couples the runtime to a JVM/bb filesystem and blocks three things we want:

1. **Escapement running in the browser** (CLJS), where there is no filesystem, no daemon thread,
   and persistence is IndexedDB/localStorage.
2. **A UI** (history / transcripts / artifacts / swim-lane timeline) that reads this data through a
   clean **EQL API** rather than scraping files — without embedding the UI in the runtime.
3. **Refinement/replay primitives** — re-running a single LLM turn, an `llm-conversation` node, or a
   sub-chart with *tuned inputs* (e.g. an edited system prompt) **without re-running the whole chart**.
   This is the prompt-tuning / eval inner loop, and it only works if the captured inputs are complete
   enough to *re-feed*, not merely complete enough to *display* (see §0).

The decision is to **fully protocolize the IO layer now** (read *and* write), make the runtime
host-agnostic, and put **Pathom resolvers on top of those same protocols as the bridge to EQL**.
The UI is one read-side consumer of that bridge. The existing Fulcro statechart **Visualizer**
(in `com.fulcrologic.statecharts`) is reused as-is for the diagram.

### Locked decisions (from design discussion)

- **Full protocolization now**: route *all* writes through protocols, plus the read side, plus the UI.
- **Protocols are runtime-centric**, not UI-centric. Declarations live in one `escapement.protocols` ns;
  implementations live in host-specific namespaces.
- **One protocol per concern**, session-scoped, with `session-id` as a **positional argument** (never a
  map option) so cross-session operations can't be expressed by accident. The *one* cross-session
  operation, "what sessions exist," lives on its own `SessionIndex` protocol.
- Checkpoints keep using the library's **`WorkingMemoryStore`** — no new protocol, no wrapping.
- **Transcript is a sequence of EDN event maps.** JSONL is demoted from "the interface" to "the disk
  backend's serialization." Browser stores the same maps as records.
- **Store owns `:transcript/seq`** (it owns ordering); assigned synchronously at append even when the
  write is async.
- **`append-event!` timing is backend-defined** — each backend documents its own durability guarantee
  (disk buffers via writer thread; browser fires async).
- **Identity is an EQL ident using the library's attribute: `[::sc/session-id <uuid>]`.** No bespoke
  `:session/id` / `:session/uuid`. Reuse `::sc/*` wherever a predefined semantic exists.
- **Node-relative "what happened here"** is first-class: every transcript event and artifact carries a
  source `:transcript/node-id` (a chart element id) + a `:transcript/visit` re-entry index, and — for
  per-turn LLM I/O — a `:transcript/turn` index within the visit.
- **The JSONL is an index, never the system of record for payloads.** Heavy fields (LLM request, LLM
  response, tool-result content) are NOT stored inline. The event carries an **80-char snippet** for
  human correlation plus an **`:io/ref`** locator; the full payload is a captured blob in the artifact
  store. This kills today's lossy 8192-byte truncation and makes any event fully reconstructable by
  dereference (see §0, §3).
- **Capture-to-re-feed, not capture-to-display.** Captured inputs are designed to be replayable: each
  `llm-conversation` node invocation persists a **seed** (resolved params + initiating event) plus
  per-turn request/response/tool-result blobs. Replay is the design driver; single-turn refine ships in
  this effort (§0, §5b).
- **No new storage protocol for captured I/O.** It is `ArtifactStore` writes whose *path is a structured,
  node-relative locator*. node→visit→turn navigation and the replay primitives live in a CLJC layer over
  `list-artifacts` + `read-events`; the stores stay dumb (§3, §5b).
- **Artifact ids are store-assigned opaque locators**, path-shaped so the disk backend is walkable with
  no tooling (`<session>/nodes/<node-id>/<visit>/turns/<n>/request`). Content-dedup stays available as an
  internal store optimization without changing the `:io/ref` contract.
- **v1 UI scope = read-only viewer**, **snapshot + manual refresh** (no live server push). All-in-browser
  mode gets near-live for free since it shares the runtime. Live server streaming is a follow-up.
- **TUI-as-Fulcro-renderer is out of scope** — a later spike in its own space; we only preserve the seam.

## Architecture

```
                         ┌──────────────────────────────────────────────┐
                         │  escapement.protocols  (declarations only)    │
                         │  TranscriptStore · ArtifactStore · SessionIndex│
                         │  (+ library WorkingMemoryStore for checkpoints)│
                         └───────────────┬───────────────┬───────────────┘
                  implements             │               │            implements
        ┌────────────────────────────────┘               └────────────────────────────────┐
        ▼                                                                                   ▼
  escapement.storage.disk  (bb/CLJ)                                  escapement.storage.browser (CLJS)
  atomic write, JSONL/EDN, checkpoint files                          IndexedDB / localStorage records
        ▲                                                                                   ▲
        │ injected into engine env (engine/env.cljc)                                        │
  ┌─────┴───────────────────────────────────┐                       ┌───────────────────────┴─────────┐
  │ Runtime (bb): agent writes via emit layer│                       │ Runtime (CLJS): agent in browser │
  │ which stamps node-id/visit/ts from env   │                       │ writes via same emit layer       │
  └─────┬───────────────────────────────────┘                       └───────────────────────┬─────────┘
        │                                                                                    │
        │           escapement.ui.resolvers  (CLJC, store-injected Pathom)  ◀────────────────┘
        │           the SAME resolvers run over either backend
        ▼                                                                                    ▼
  bb http-kit  POST /api  (transit)                                  in-process CLJS Pathom remote
        ▲                                                                                    ▲
        │ Fulcro http-remote                                                                 │ Fulcro remote
        └───────────────────────────  CLJS Fulcro UI (shadow-cljs :ui)  ─────────────────────┘
              SessionList → SessionDetail → {timeline, transcript, artifacts, diagram=Visualizer}
```

The single swap seam for "both hosts": the **injected store** (disk vs browser) on the resolver side,
and the Fulcro **`:remotes` value** (HTTP vs in-process Pathom) on the client side. Resolvers and UI
components are identical across hosts.

## 0. Capture & replay model (the spine that shapes everything below)

**Problem today.** The transcript JSONL stores LLM I/O *inline and truncated*. Four sites in
`invocation/llm_conversation.clj` lose data, all governed by one 8192-byte cap
(`transcript-block-cap` / `truncate-for-transcript`, ~lines 263-279):

1. `:llm/request` — `:system-preview` + `:user-blocks` (the prompt), truncated (~884-890).
2. `:llm/response` — `:content` blocks, each truncated via `->transcript-content-block` (~1221-1234).
3. `:llm/tool-result` — `:content-preview`, truncated (~591-599).
4. `:llm/continuation`/`:llm/delta` — partial content, truncated.

**Principle.** The JSONL is an **index of references + 80-char snippets**. Every heavy field becomes a
**captured blob** addressed by an `:io/ref` locator; the event holds `{:io/ref … :io/snippet "<≤80>"}`.
Full reconstruction = dereference. Truncation is gone.

**Why "re-feedable", not just "displayable".** The capture contract is set by the hardest consumer —
**replay** — not the easiest one (the UI). Read-only display needs *enough to render*; replay needs
*enough to re-run*. Designing to replay subsumes display.

**Unit of capture = one `llm-conversation` node invocation**, recorded under a node-relative locator:

```
<session>/
  artifacts/<name>                              author files: path-addressed, mutable, latest-wins
  nodes/<node-id>/<visit>/
    seed.edn                                    REPLAYABLE INPUT: resolved params + initiating event
    turns/<n>/request.json                      full Request (system+messages+tools+model+knobs)
    turns/<n>/response.json                     full Response (content blocks, stop-reason, usage)
    turns/<n>/tool-results/<tool_use_id>.json   full tool output (a replay input, not log decoration)
    output.edn                                  idle/verdict data for the invocation
```

The locator is the `:io/ref` value, literally (`nodes/<node-id>/<visit>/turns/<n>/request`). On disk it
is a walkable path; in the browser backend the same `{node-id, visit, turn, kind}` tuple is an IndexedDB
key. A human can `cd` to "what did this conversation do on its 3rd visit, 2nd turn" with zero tooling;
the protocol API and the UI navigate the identical structure.

**Three replay granularities** (capture must enable all; we *implement* #1 now — see §5b):

1. **Single-turn refine** — load one `request.json`, override fields (tuned system prompt, swap model,
   bump temperature), re-issue via `llm/send-turn*`, diff the response. No statechart engine. The tight
   prompt-tuning loop and the 80% case. Reuses the worker's existing `build-request` (llm_conversation.clj:364).
2. **Node-invocation refine** — re-run the `llm-conversation` worker standalone from `seed.edn` with
   overrides, multi-turn. The worker is already an `InvocationProcessor`; `escapement.engine.testing` is
   already a standalone mock harness. Crucial knob: **`:tool-source :captured | :live`** — replaying
   captured tool-results holds everything constant except the prompt, isolating the prompt's effect
   (this is *why* tool-results are captured in full).
3. **Sub-chart refine** — re-enter a region from a `WorkingMemoryStore` checkpoint taken at region entry
   plus per-node seeds. Heaviest; staged. Capture layout must not preclude it.

## 1. Protocols (`escapement.protocols`, CLJC — declarations only)

```clojure
(ns escapement.protocols)

(defprotocol TranscriptStore
  (append-event! [store session-id event]
    "Persist one transcript event (a map missing :transcript/seq). The store ASSIGNS a gapless,
     monotonic per-session :transcript/seq synchronously and returns it (or the stored event).
     Write TIMING is backend-defined (disk buffers; browser is async).")
  (read-events   [store session-id query]
    "Return events for the session as a seq of maps in :transcript/seq order.
     `query` (nil/{} => all): {:types #{…} :node-id … :from-seq … :to-seq … :limit …}.
     v1 backends MAY ignore the query and return all (caller filters); the signature lets a
     later indexed backend push predicates down without changing callers."))

(defprotocol ArtifactStore
  ;; Stores BOTH classes of artifact. `path` is the addressing key:
  ;;  * author files       — "artifacts/<name>"                              (mutable, latest-wins)
  ;;  * captured I/O blobs  — "nodes/<node-id>/<visit>/turns/<n>/request"     (immutable; the :io/ref)
  ;; No separate BlobStore: captured I/O is just a write whose path is a structured node-relative
  ;; locator (§0). node→visit→turn grouping + replay are a CLJC layer over list-artifacts (§5b).
  (write-artifact! [store session-id path content meta])  ; meta carries :transcript/node-id, :transcript/visit, :transcript/turn
  (read-artifact   [store session-id path])
  (list-artifacts  [store session-id]))                   ; items include :artifact/* + source node-id/visit/turn; supports prefix listing under "nodes/<node-id>/"

(defprotocol SessionIndex
  (list-sessions [store]))                                ; the ONE cross-session op; returns summaries
```

- Checkpoints: keep `com.fulcrologic.statecharts.protocols/WorkingMemoryStore`
  (`get/save/delete-working-memory!`, already `[_ env session-id]`-shaped). Add a browser impl.
- A single backend record MAY implement all of `TranscriptStore` / `ArtifactStore` / `SessionIndex`
  (+ `WorkingMemoryStore`) at once.
- **The stores are dumb**: they stamp nothing they aren't given (except seq, which is theirs).
  All enrichment happens in the emit layer (§3).
- `SessionIndex` is justified because the library has **no** session enumeration: `WorkingMemoryStore`
  is pure get/save/delete by id (protocols.cljc:217-229) and `StatechartRegistry/all-charts`
  (protocols.cljc:213-215) lists chart *definitions*, not sessions. Identity comes from the library
  (`::sc/session-id`); enumeration is ours.

## 2. Data vocabulary (namespaced — bare keywords are a footgun here)

**Reused from the library (predefined semantics):**
- `::sc/session-id` — session identity; ident is `[::sc/session-id <uuid>]`. This is the **same** ident
  the Visualizer's `SessionState` uses, so our historical-run entity and the diagram entity normalize
  into one — no glue when feeding the diagram.
- `::sc/configuration` — active state set (drives diagram highlight).
- `::sc/statechart-src` — the chart registry key (what we'd loosely called "chart-id"; it's what the
  chart-definition resolver looks up).

**Escapement-owned (simple namespaces):**
- **Transcript record** — ident `[:transcript/id [<session-id> <seq>]]`; fields: `::sc/session-id`,
  `:transcript/seq`, `:transcript/ts`, `:transcript/kind` (the type, e.g. `"runner/started"` — named
  `kind` not `event` to avoid colliding with statechart events), `:transcript/node-id`,
  `:transcript/visit`, `:transcript/turn` (per-turn LLM I/O only; nil otherwise), `:transcript/data`.
- **`:io/ref` + `:io/snippet`** — on events whose payload was externalized (`:llm/request`,
  `:llm/response`, `:llm/tool-result`, …): `:io/ref` is the captured blob's locator (a path-shaped
  opaque id == the artifact path); `:io/snippet` is the ≤80-char human-correlation slice. One event may
  carry several (e.g. a response with multiple tool-result children); represent as a vector of
  `{:io/ref … :io/snippet … :io/kind …}` when more than one.
- **Artifact** — ident `[:artifact/id [<session-id> <path>]]`; fields: `:artifact/path`,
  `:artifact/content-type`, `:artifact/size`, `:artifact/content` (lazy), `:transcript/node-id`,
  `:transcript/visit`, `:transcript/turn`, and `:artifact/class` ∈ `#{:author :captured-io}`. Author
  files use `"artifacts/<name>"`; captured I/O uses the node-relative locator (§0).
- **Seed** — `nodes/<node-id>/<visit>/seed.edn`: the replayable input for one `llm-conversation` node
  invocation. Fields: resolved conversation params (`:system`, `:model`/`:models`, `:temperature`,
  `:top-p`, `:thinking`, `:needs`, real-tool selector, `:allowed-events`, `:chart-tools`,
  `:verdict-schema`, cache knobs) + the initiating event `:data` + the resolved tool palette (so replay
  is deterministic even if the registry later changes).
- **Invocation** (derived, not stored — assembled by the §5b CLJC layer) — `{:node-id :visit :turns
  [{:turn :request-ref :response-ref :tool-result-refs […]}] :seed-ref :output-ref :started-at}`.
- **Session summary** (from `list-sessions`) — `::sc/session-id`, `::sc/statechart-src`,
  `:session/started-at`, `:session/status`, `:session/parent-id` (value is a session-id),
  `:session/child-ids`.

`:transcript/node-id`'s **value is a chart element id** (a key in `::sc/elements-by-id`), so it lines up
directly with `::sc/configuration` members and with what the Visualizer highlights.

## 3. Write-path migration (full) + the emit layer

**Emit/enrichment layer** (the reworked `escapement.transcript`, the structural keystone): sits between
chart code and the stores; the stores stay dumb. On each emit it pulls from the processing `env`:
- `::sc/session-id` — the session.
- `:transcript/node-id` — from **`::sc/context-element-id`** (confirmed present in the processing env;
  `:ROOT` at top level; helper `com.fulcrologic.statecharts.environment/context-element-id`,
  `environment.cljc:63`). No chart-author involvement needed.
- `:transcript/visit` — a per-(session, node) entry counter. The library does **not** track re-entry
  counts, so we maintain a small counter incremented on state entry (in working memory or an env
  volatile) and stamp it. (v1 UI may ignore the visit dimension and just group by node.)
- `:transcript/turn` — a per-(session, node, visit) LLM round-trip counter, owned by the conversation
  worker (it already loops turns; it just needs to count them). Stamped on `:llm/request` /
  `:llm/response` / `:llm/tool-result` so request↔response↔tool-results of one turn share coordinates.
- `:transcript/ts` — wall clock.

**Externalize-then-reference** (the §0 principle, applied at emit): for any heavy field
(LLM request, LLM response content, tool-result content) the emit layer
1. writes the full payload via `(p/write-artifact! store session-id <locator> content meta)` where
   `<locator>` is `nodes/<node-id>/<visit>/turns/<turn>/{request,response}` or
   `.../tool-results/<tool_use_id>`, and `meta` carries node-id/visit/turn + `:artifact/class :captured-io`;
2. replaces the inline field with `{:io/ref <locator> :io/snippet (take 80 …)}`;
3. then calls `(p/append-event! store session-id event)`; the store assigns `:transcript/seq`.

This **replaces** `truncate-for-transcript` at the four §0 sites — the 8192-byte cap and the
`…(truncated)` marker are deleted; the snippet is a fixed ≤80 chars and nothing is lost (the full text
is the referenced blob).

**Seed capture for replay** — on `llm-conversation` worker spawn (the invocation start, where resolved
params + the initiating event `:data` are both in hand), the emit layer writes `seed.edn` once per
node invocation. This is the single hook that unlocks node/sub-chart refine (§5b granularities #2/#3);
single-turn refine (#1) needs only the per-turn `request` blob, which the externalization rule above
already produces.

**Site-by-site:**
- `chart/helpers.cljc` author-artifact writes (`atomic-write!` / `capture-llm-output`, ~404-469) → an
  artifact-emit helper that reads node-id/visit from env and calls `ArtifactStore/write-artifact!` with
  path `artifacts/<name>` and `:artifact/class :author`. Disk impl keeps the atomic temp+rename.
- `invocation/llm_conversation.clj` the four truncation sites (§0): `:llm/request` (~884-890),
  `:llm/response` (~1221-1234), `:llm/tool-result` (~591-599), `:llm/continuation`/`:llm/delta` → switch
  to externalize-then-reference. Delete `transcript-block-cap` / `transcript-truncate-marker` /
  `truncate-for-transcript` (~263-279) and the `*-preview`/`content-preview` truncated fields; capture
  full blobs + 80-char snippets. Add the per-turn counter + `seed.edn` write on worker spawn.
- `transcript.clj` daemon thread + `LinkedBlockingQueue` + JSONL + serialize-error fallback + idempotent
  close → all become **disk-backend internals** behind `append-event!`. The browser backend has no
  thread; it just `put`s a record.
- Checkpoints → unchanged protocol; add browser `WorkingMemoryStore` impl.

**Env wiring** (`engine/env.cljc`): `new-env` already injects `::sc/working-memory-store`. Inject
`ArtifactStore` and `TranscriptStore` the same way under escapement-namespaced keys (replacing the
current `:escapement/transcript-fn` slot). One construction site decides disk vs browser.

## 4. Backends

- **`escapement.storage.disk`** (bb/CLJ): reuses today's atomic write + checkpoint logic. JSONL becomes a
  serialization detail of `TranscriptStore`; reads parse it back to EDN maps with ranged/paged access
  (never slurp whole files — transcripts are large, LLM events heavy).
  - **Captured-I/O layout** (§0): `write-artifact!` renders a captured-I/O locator path verbatim under
    the session dir — `nodes/<node-id>/<visit>/turns/<n>/request.json`, etc. — so the tree is walkable
    with `cd`/`ls`/`cat` and no escapement code. Author files stay under `artifacts/`. Atomic temp+rename
    for both. `:io/ref` round-trips to a path with no translation table (the ref *is* the relative path).
    `node-id` is filename-sanitized (it can be a namespaced keyword); the sanitization must be reversible
    or the listing must reconstruct the original element id (carry it in a sidecar/meta if lossy).
  - Blob `content-type` from the locator suffix (`.json` LLM I/O, `.edn` seed/output); `list-artifacts`
    supports prefix scans (`nodes/<node-id>/`) so §5b can group an invocation cheaply.
  - **GOTCHA**: bb's EDN reader throws `Invalid token: :session/<uuid>` on keywords whose name starts
    with a digit, and real checkpoints embed `:session/<uuid>` values. `FileBackedStore` masks this in
    production via its in-memory cache; a fresh read-only process hits it on first read. Use a
    digit-tolerant reader and derive session-id from the directory name. (seed.edn/output.edn may also
    contain digit-leading keywords — same reader.)
- **`escapement.storage.browser`** (CLJS): IndexedDB (object store per concern, keyed/indexed by
  session-id, `:transcript/seq`, `:transcript/node-id`, `:transcript/kind`) / localStorage. Captured-I/O
  blobs key off the same `{node-id, visit, turn, kind}` tuple as the disk locator (an artifact object
  store indexed by `[session-id node-id visit turn]`), so the `:io/ref` contract and the §5b navigation
  are byte-identical across hosts. Serializing checkpoints + blobs via transit/structured records
  **sidesteps the digit-keyword EDN gotcha** entirely.

## 5. EQL / Pathom resolver layer (CLJC — the bridge)

**Feasibility (already spiked on real bb):** Pathom 2.4.0 runs under Babashka **iff**
`com.fulcrologic/guardrails` is pinned to `1.2.16` (which `bb.edn` already does — Pathom's transitive
`0.0.12` uses a timbre macro SCI can't analyze; the pin shadows it). A registered `pc/defresolver`
parses through a parser, including ident-input + nested joins, with the full escapement classpath
loaded. `edn-query-language/eql` 1.0.2 loads (fallback processor basis). `cognitect.transit` JSON
round-trips under bb (wire format).

- Resolvers are **CLJC, parameterized by the injected stores** (carried on the Pathom env, same
  instances as the engine env). A resolver body just calls protocol methods — it never knows disk vs
  browser. The same resolvers compile for the bb server and for in-browser CLJS.
- **Resolver set**: session list (`SessionIndex`), session detail, timeline (`read-events` grouped by
  `::sc/session-id` into swim lanes; child sessions = extra lanes), paged transcript, node-scoped
  events/artifacts (`read-events` with `:node-id` / `list-artifacts` filtered), artifact list + lazy
  content, session config (`::sc/configuration` from checkpoint), and a **chart-definition resolver**
  (port `remove-functions` postwalk → `:fn` from the statecharts demo `visualization/server/resolvers.clj`
  so `escapement.runner/chart` serializes to transit).
- **Invocation navigation** (the capture/replay read side, §0/§5b): `node-invocations(session, node-id)`
  → the list of `{:visit :turn-count :model :started-at :input-snippet :output-snippet}`; and
  `invocation(session, node-id, visit)` → the assembled `Invocation` (seed-ref, ordered turns each with
  request/response/tool-result refs, output-ref). Both are folds over `list-artifacts` (prefix
  `nodes/<node-id>/`) + `read-events` (filtered by node-id); the heavy blob bodies stay lazy. This is the
  shared substrate for both the UI's node-click drill-in and the replay primitives.
- **Commit a `pathom_smoke_test`** under `bb test` as a permanent guard against dependency drift letting
  Pathom's transitive guardrails win.
- Keep a thin `edn-query-language.core` fallback processor sharing the same resolver bodies — insurance
  + the in-browser processor.

## 5b. Replay / refinement primitives (`escapement.replay`, CLJC)

The reason capture is "re-feedable, not just displayable" (§0). The library should let you tune an
interaction by re-running a *piece* of a chart with edited inputs — not the whole chart. Three
granularities; **#1 ships in this effort, #2/#3 are designed-for but staged.**

```clojure
(ns escapement.replay)   ; CLJC — backend + store injected, so it runs disk-host or browser-host

;; #1 SINGLE-TURN REFINE — ships now. No statechart engine; the prompt-tuning inner loop.
(refine-turn store session-id node-id visit turn
  {:overrides {:system "tuned system prompt" :model "claude-opus-4-7" :temperature 0.2}
   :backend   live-backend})
;; loads turns/<turn>/request.json  →  deep-merges :overrides  →  reuses the worker's existing
;; build-request (llm_conversation.clj:364)  →  llm/send-turn*  →
;; {:request <effective> :response <new> :usage … :original {:request … :response …}}   ; caller diffs
```

- **#1 single-turn refine** — built entirely on the captured `request` blob + `llm/send-turn*`. The only
  new machinery beyond §0/§5 capture is the deep-merge of overrides and the result shape. It deliberately
  does **not** dispatch tools or post events — it answers "what would this exact turn have produced with
  a different prompt/model/temperature?" Cheapest, deterministic w.r.t. everything but the model.
- **#2 node-invocation refine** (staged) — re-run the `llm-conversation` worker standalone from
  `seed.edn` with overrides, multi-turn. The worker is already an `InvocationProcessor` and
  `escapement.engine.testing` is already a standalone mock harness; the new piece is a thin driver that
  feeds the seed and collects posted events + the final verdict/idle data. Knob **`:tool-source`**:
  `:captured` replays `tool-results/*` for byte-identical tool behavior (isolates the prompt's effect —
  the eval use case), `:live` re-dispatches through the real tool registry (true re-run).
- **#3 sub-chart refine** (staged) — re-enter a region from a `WorkingMemoryStore` checkpoint captured at
  region entry + per-node seeds, with per-node overrides. Heaviest; depends on a region-entry checkpoint
  policy that this plan only needs to *not preclude*.

**Host note**: `refine-turn` needs a live LLM backend. Under bb that's the existing backend; in-browser
it's whatever LLM remote the browser host injects. Keeping `escapement.replay` CLJC with the backend
**injected** (never reached for globally) preserves the both-hosts seam — same as the stores.

**Scope guard**: replay is **not** wired into the v1 read-only UI surface (no "re-run" button this
effort). `refine-turn` is a programmatic/REPL primitive proving the capture contract end-to-end; a UI
affordance is a follow-up once #2 lands.

## 6. Transport / remotes

- **Server mode**: `escapement.ui.server` — bb http-kit, mirror `debug/viz_server.clj`. `POST /api`
  transit+json, **read-only** (no mutations), CORS + an OPTIONS preflight branch for the shadow dev
  origin. Reads disk-backed stores.
- **All-in-browser mode**: a Fulcro remote that calls a **local CLJS Pathom parser directly**
  (in-process, no HTTP) over browser-backed stores. Agent + UI share one JS runtime → resolves against
  live state, not a disk snapshot.
- **Liveness (v1)**: snapshot + manual refresh in server mode (re-query on reload). All-in-browser is
  near-live for free. Live server streaming (SSE reusing the `viz_server` precedent; WS only if future
  live *control* needs it) is a deliberate follow-up.

## 7. UI (CLJS Fulcro, new `shadow-cljs :ui` build)

New CLJS source root (e.g. `src-ui/escapement/ui/…`), a `:ui` deps alias (fulcro 3.9.3, clojurescript
1.12.116, shadow-cljs 3.3.4, devtools), a `:ui` browser build modeled on the statecharts `:viz` build,
npm `react`/`react-dom` 19.2.0 + `elkjs`, and an `index.html`. This is JVM/node tooling, fully separate
from the bb runtime. If the published statecharts snapshot lags the local checkout (Visualizer/routing),
use `:local/root "../statecharts"` during dev (per the upstream-work convention).

**Component tree** (defsc; idents in parens):
- `SessionList` / `SessionListItem` (`[::sc/session-id …]`) — landing.
- `SessionDetail` (`[::sc/session-id …]`) — tabbed shell; loads light tab data only.
- `SwimLaneTimeline` + `TimelineLane` (`[::sc/session-id …]`) + `TimelineMark`
  (`[:transcript/id [sid seq]]`) — lanes = parent + child sessions; x = `:transcript/seq`/`:ts`; marks
  are a lightweight projection of the shared event entities. **Clicking a chart node** filters to that
  node's events+artifacts (`:transcript/node-id`).
- `TranscriptInspector` (`[::sc/session-id …]`) + `TranscriptEvent` (`[:transcript/id …]`) —
  server-paged + filterable; page/filter state in Fulcro state; heavy `:transcript/data` lazy on click.
  Shares the `:transcript/id` table with timeline marks (one entity, two views; click stays in sync).
- `ArtifactList` / `ArtifactViewer` (`[:artifact/id …]`) — content lazy. Distinguishes
  `:artifact/class` (author files vs captured-I/O blobs); the captured-I/O view is the §5b invocation
  drill-in, not a flat file list.
- `NodeInvocations` (`[::sc/session-id …]`, scoped by `:transcript/node-id`) — node-click drill-in:
  per-visit list → per-turn request/response/tool-result, each a lazy `[:artifact/id …]` blob. Reads the
  §5b `node-invocations`/`invocation` resolvers. (Read-only this effort — no "re-run" affordance; replay
  is the §5b programmatic primitive for now.)
- `ChartDiagramPanel` (`[::sc/session-id …]`) embeds the reused `viz/Visualizer`.

**Feeding the Visualizer** (the central reuse): pass computed props `{:chart <chart-map>
:current-configuration <active-set>}`, never `:session-id` (no live in-browser chart in server mode).
Chart map comes from the chart-definition resolver, loaded once into `[::sc/id chart-id]` and cached.
**Timeline scrubbing is a pure fold, not delta replay** — transcripts record full configs, so
"active config at seq N = the latest config at/-before N." Backend should emit timeline configs as
**full keyword active-state sets** matching `::sc/configuration` semantics (it holds the chart, so it
keywordizes + expands ancestors) → frontend passes the set straight through.

**Routing**: statechart-driven via `com.fulcrologic.statecharts.integration.fulcro.routing` (the
non-deprecated package), routes `sessions → session/<id> → {timeline,transcript,artifacts,diagram}` with
`install-url-sync!` for deep links. Keep the route chart in `.cljc` and isolate `js/window`/history to
the app entry — that's the seam for a future TUI host.

**Data management**: Fulcro + statecharts only; React hooks restricted to transient UI (zoom level,
search strings), per house rules. App built via `(make-app remote)` so the remote is swappable.

## 8. Testing

- **Pure logic** (config-at-seq fold, keywordization/ancestor expansion, swim-lane grouping, visit
  derivation, turn derivation, invocation assembly, pagination math) — Guardrails + fulcro-spec, tested
  first.
- **Protocols + resolvers** — fulcro-spec under `bb test` against an in-memory stub store; `bb test`
  green throughout. `pathom_smoke_test` guards the bb+Pathom path.
- **Capture/externalization** — emitting an `:llm/request`/`:llm/response`/`:llm/tool-result` writes a
  blob at the expected locator, the event carries `{:io/ref :io/snippet}` with snippet ≤80 chars, and
  reading the ref reproduces the full payload byte-for-byte (no truncation). Assert the four §0 sites no
  longer truncate.
- **Replay (single-turn refine)** — round-trip a captured `request.json` through `build-request` and
  assert it reproduces the original request; `refine-turn` with `{}` overrides re-issues the identical
  request (against a mock backend) and with `:overrides` applies the deep-merge correctly. This is the
  end-to-end proof that capture is re-feedable.
- **Disk backend** — `io-layer-testing` pattern against a real fixture session dir, incl. a
  `^{:multi-session? true}` run (e.g. `examples/n_subagents_demo`) to verify parent/child lanes and the
  digit-keyword read. Assert the captured-I/O tree is walkable (`nodes/<node-id>/<visit>/turns/<n>/…`
  exists as plain files) and that `:io/ref` ↔ relative path needs no translation.
- **API parity** — run one query table through both the Pathom parser and the `eql` fallback; assert
  identical results (also proves the in-browser processor will match).
- **UI** — fulcro-spec for query/ident/normalization + mutations on the state map; headless Fulcro
  (`fulcro-headless`) for `df/load!` + route transitions end-to-end without a browser.

## 9. Suggested sequencing

1. `escapement.protocols` + in-memory stub store + the `pathom_smoke_test` guard.
2. Disk backends for all three concerns; migrate write sites (artifact helper, transcript, checkpoints)
   + the emit layer (node-id/visit/**turn** stamping, **seed capture**, **externalize-then-reference**
   replacing the four §0 truncation sites); keep `bb test` green.
3. **Single-turn refine** (`escapement.replay/refine-turn`) + capture/replay tests — the end-to-end
   proof that capture is re-feedable, on top of step 2's blobs. (Node/sub-chart refine deferred.)
4. Browser backends (IndexedDB) — proves escapement-in-browser persistence end-to-end.
5. CLJC resolver layer (incl. `node-invocations`/`invocation` navigation) + bb http-kit `/api` server;
   API-parity tests.
6. shadow-cljs `:ui` build + Fulcro UI (Visualizer, timeline, transcript, artifacts, node-click +
   invocation drill-in, routing).
7. In-process CLJS Pathom remote (all-in-browser mode).

## 10. Risks

1. **Config representation contract** (highest): backend must emit timeline/diagram configs as full
   keyword active-state sets matching `::sc/configuration`, or the diagram won't highlight correctly.
   Verify `remove-functions` round-trips `escapement.runner/chart`.
2. **Digit-leading session keywords** break bb's EDN reader on cold checkpoint reads — handle in the disk
   backend.
3. **Dependency drift** could let Pathom's transitive guardrails `0.0.12` win and break bb — the smoke
   test guards this.
4. **Append-timing portability**: with backend-defined timing, callers can't assume durability; document
   each backend's guarantee and keep crash-window expectations explicit (disk buffer / async browser put).
5. **Visit + turn counters**: re-entry and per-turn tracking are ours to maintain; get them right at
   state entry / turn boundary or visits and the request↔response↔tool-result correlation are
   unreconstructable later — and replay loses its addressing.
6. **Statecharts snapshot freshness** vs the local checkout (Visualizer/routing) — use `:local/root` if
   the published artifact lags.
7. **Transcript scale** — pagination + lazy heavy-field loading are mandatory, not optional. Note
   externalization *helps* here: the JSONL line is now small (snippet + ref), with bulk moved to blobs
   read on demand.
8. **Seed completeness** (replay correctness): `seed.edn` must capture *every* input that determines a
   turn — system, model/models, all sampling knobs, tool palette, allowed-events, verdict-schema, cache
   knobs, initiating event data. Miss one and node-refine diverges from the original run in a way that's
   hard to attribute. Mitigation: derive the seed from the *same resolved params map* the worker uses,
   not a hand-picked subset; round-trip-test request reconstruction (§8).
9. **`node-id` → filesystem path** (disk backend): node-ids can be namespaced keywords; sanitizing them
   into directory names must be reversible (or the original element id carried in meta) so `:io/ref`
   and node-scoped queries map back to the real chart element the Visualizer highlights.
10. **Tool-result replay determinism** (#2 node-refine): `:tool-source :captured` correlates replays to
    the original `tool_use_id`s; if a tuned prompt makes the model call tools in a different order or
    with different ids, captured results won't match. Document that `:captured` is exact only when the
    tool-call sequence is unchanged; otherwise fall back to `:live`.

## Out of scope (future)

- Live server streaming of a running session (SSE/WS) — v1 is snapshot + refresh.
- Live *control* (sending events / driving a running session) — read-only for now.
- **Node-invocation refine (#2) and sub-chart refine (#3)** — the capture/seed/locator model is designed
  to support them (§5b) and `seed.edn` is written now, but only single-turn refine (#1) is built this
  effort. A UI "re-run/tune" affordance is also deferred — `refine-turn` is REPL/programmatic for now.
- **Content-addressed dedup** of captured blobs (repeated system prompts/tool defs) — available as an
  internal store optimization later without changing the `:io/ref` contract; not built now.
- Deferred read-side **indexing** (node-id/type/seq pushdown). v1 returns data and filters in-process;
  the query-map signature leaves the door open without caller changes.
- **TUI-as-Fulcro-render-plugin** (JLine renderer over a Fulcro component tree) — separate experimental
  spike. This plan only preserves the seam (route chart + reconstruction logic in `.cljc`;
  browser-specifics isolated).
