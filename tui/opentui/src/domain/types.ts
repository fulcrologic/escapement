/**
 * Domain types for the OpenTUI sidecar's state layer.
 *
 * These mirror the pure data shapes the JLine TUI derives in Clojure
 * (`escapement.tui.live`, `escapement.tui.transcript`, `escapement.tui`),
 * ported faithfully to TypeScript. The snapshot/unit tests (tasks 015/016)
 * assert equivalence against the recorded JSONL, so the shapes here are the
 * contract the panes (008/009/010/011) and tests consume.
 */

// --- Live token aggregation (port of tui/live.clj) -------------------------

/** Per-session streaming status. Sort rank: streaming < waiting < error < done. */
export type LiveStatus = "streaming" | "waiting" | "error" | "done";

/** Whether the in-flight stream is text or thinking deltas. */
export type LiveKind = "text" | "thinking";

/**
 * One session's live counter (port of `fold-live-event`'s per-session map).
 * Keyed under invokeid -> sessions -> session-id in the store.
 */
export interface LiveSession {
  /** Raw text-delta chunk count (≈ tokens when usage absent). */
  chunks: number;
  /** Total characters streamed. */
  chars: number;
  /** Provider-reported running output-tokens, when streamed. */
  tokens?: number;
  /** Re-anchored to the FIRST delta (excludes time-to-first-token). */
  "first-ts": number;
  /** Original llm/start timestamp (ms), preserved across the first-delta
   *  re-anchor so the streaming WAIT (time-to-first-token) can be measured. */
  "start-ts"?: number;
  /** Time-to-first-token (ms): first-delta-ts − start-ts. Frozen once the
   *  first delta arrives; the "wait" column reads this. */
  "wait-ms"?: number;
  /** Last activity timestamp (ms). */
  "last-ts": number;
  status: LiveStatus;
  kind?: LiveKind;
  /** Capped in-flight partial text (last `LIVE_PARTIAL_TAIL_CHARS` chars). */
  text: string;
  model?: string | null;
  /** Winning candidate's provider (e.g. "ollama"), for the `provider/model` label. */
  provider?: string | null;
  /** The session-id this counter belongs to (opaque string). */
  session: string;
  /** True generation rate from llm/response output-tps (preferred over estimate). */
  "real-tps"?: number;
  "elapsed-ms"?: number;
  /** stop-reason / error message / worker-exit reason. */
  reason?: string | null;
}

/** invokeid -> { sessions: { session-id -> LiveSession } }. */
export interface LiveGroupEntry {
  sessions: Record<string, LiveSession>;
}

/** invokeid -> group. */
export type LiveMap = Record<string, LiveGroupEntry>;

/** Per-invokeid rollup (port of `live-agg`). */
export interface LiveAgg {
  tokens: number;
  tps: number;
  status?: LiveStatus;
  /** total sessions */
  n: number;
  "n-active": number;
  "n-done": number;
  "last-ts": number;
  model?: string | null;
  /** in-flight partial text of the most active session */
  text?: string;
}

// --- Scrollback entries (port of `entries-for` / `event!*`) ----------------

/** A scrollback source: an invokeid string, or a semantic lane keyword name. */
export type EntrySource =
  | string // invokeid (e.g. "planner") OR a lane name below
  | "chart"
  | "human"
  | "debug"
  | "error"
  | "viz";

/** One scrollback line (port of an `entries-for` entry, post-`event!*` fold). */
export interface ScrollbackEntry {
  source: EntrySource;
  glyph: string;
  summary: string;
  /** The originating event envelope (for transcript reconstruction). */
  ev: EventLike;
  /** A response content block, when the entry came from an `:llm/response` block. */
  block?: Record<string, unknown>;
}

// --- Invocation history (port of `update-invocation-history`) --------------

export interface InvocationEntry {
  invokeid: string | null;
  "session-id"?: unknown;
  "started-ms": number;
  "ended-ms": number | null;
  reason?: unknown;
  /** This invocation's OWN token count, frozen from its live session at
   *  worker-exit (so concurrent same-role turns show distinct counts rather
   *  than the shared role aggregate). Undefined while still streaming. */
  tokens?: number;
  /** Model resolved for THIS invocation, frozen at worker-exit. */
  model?: string | null;
}

// --- Transcript SENT/REPLY blocks (port of tui/transcript.clj) -------------

export type BlockDir = "sent" | "reply";

export interface BlockMeta {
  stop?: string | null;
  in?: number | null;
  out?: number | null;
  tps?: number | null;
  "streaming?"?: boolean;
  chars?: number;
}

export interface TranscriptBlock {
  dir: BlockDir;
  /** "hh:mm:ss" */
  ts: string;
  label: "system" | "user" | "tool" | "assistant" | "error";
  sublabel?: string | null;
  /** invokeid, for role-hued REPLY labels. */
  role: string;
  meta: BlockMeta;
  body: string;
  "collapsible?"?: boolean;
}

// --- Event envelope (decoded, as the store sees it) ------------------------

/**
 * The store's normalized view of an `EventEnvelope`. Keyword names arrive as
 * strings (no leading colon for keyword names; `session-id` values are opaque
 * and may carry a colon — never parse). We keep `data` loosely typed and read
 * fields defensively, mirroring the Clojure folds.
 */
export interface EventLike {
  event: string;
  seq?: number;
  ts?: number;
  data: Record<string, unknown>;
}

// --- Phase / config --------------------------------------------------------

export interface PhaseModel {
  /** Active leaf-path config (authoritative: last `config-after`). */
  config: string[];
  breadcrumb?: string[];
  siblings?: string[];
}
