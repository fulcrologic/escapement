/**
 * Wire decoding for the Escapement <-> OpenTUI sidecar protocol.
 *
 * Canonical contract: docs/opentui-wire.md. Transport-agnostic: the same
 * envelope shape arrives over the live WebSocket and from the replay JSONL
 * fixture, so this module is the single decode path for both.
 *
 * Encoding rules (see wire doc §2):
 *  - JSON, UTF-8, one object per frame / per line.
 *  - Clojure keywords are strings WITHOUT the leading colon (`:llm/delta` ->
 *    "llm/delta"). Some value strings (e.g. `session-id`) may still carry a
 *    leading colon for historical reasons; `session-id` is OPAQUE -- compare
 *    for equality only, never parse it.
 *  - Map keys keep hyphens (`output-tokens`, `config-after`, `first-ts`).
 *  - `nil` -> null; absent and null both mean "unset".
 *  - `ts` is integer epoch-ms at the envelope top level.
 *
 * Decoding here is intentionally shallow: we validate the frame router
 * discriminant (`kind`) and the common envelope fields, but leave `data`
 * untyped at the boundary. The domain store (task 006) folds `data` per
 * event type; over-typing it here would couple this layer to every event.
 */

// --- Shared debugger value shapes (wire doc §9.5) --------------------------

/**
 * Replay marker value carried in an `event` frame's `data["replay/source"]`
 * (wire doc §9.4). "captured" = served from a matched capture; "live" =
 * actually executed during the branch run. Absent ⇒ original/normal run.
 */
export type ReplaySource = "captured" | "live";

/** Conversation message: role + flattened text (wire doc §9.5). */
export interface WireMessage {
  /** Keyword-name string. */
  role: "system" | "user" | "assistant";
  /** Flattened text of the message content blocks. */
  text: string;
}

/**
 * A branch-point coordinate (wire doc §9.5): the node-entry visit counter +
 * 0-based turn index within that conversation. All opaque/integer per §2.
 */
export interface BranchPoint {
  "node-id": string;
  visit: number;
  turn: number;
}

/** An expanded model target: a provider/model pair (wire doc §9.2). */
export interface CatalogTarget {
  /** Provider keyword-name string, e.g. "openai". */
  provider: string;
  /** Model id (plain string). */
  model: string;
}

/** One alias and its ordered expanded targets (wire doc §9.2). */
export interface CatalogAlias {
  /** Alias keyword-name string, e.g. "smart". */
  alias: string;
  targets: CatalogTarget[];
}

/** One editable conversation turn (wire doc §9.2). */
export interface ConversationTurn {
  /** 0-based turn index within the conversation. */
  turn: number;
  /** Model id used for this turn (plain string). */
  model: string;
  /** Captured request system prompt (string). */
  system: string;
  /** Ordered messages; edited in place by the UI. */
  messages: WireMessage[];
}

/**
 * Override draft shipped back on `rerun-from` (wire doc §9.1/§9.5). Every key
 * is OPTIONAL; an absent/null value means "keep the captured value".
 */
export interface RerunOverrides {
  /** Alias keyword-name string. */
  alias?: string;
  /** Provider keyword-name string. */
  provider?: string;
  model?: string;
  temperature?: number;
  /** Full edited system prompt. */
  system?: string;
  /** Edited transcript prefix up to (and incl.) the resume turn. */
  messages?: WireMessage[];
}

// --- Forward envelopes (agent -> UI) ---------------------------------------

/** Top-level `kind` discriminants seen on the wire (wire doc §7 + §9.2). */
export type FrameKind =
  | "event"
  | "phase"
  | "prompt"
  | "debug"
  | "model-catalog"
  | "conversation"
  | "answer"
  | "control";

/** A transcript event envelope -- the workhorse forward frame (wire doc §3). */
export interface EventEnvelope {
  kind: "event";
  /** Monotonic, gap-free per session. Use for ordering + reconnect de-dup. */
  seq: number;
  /** Epoch milliseconds. */
  ts: number;
  /** Transcript event keyword as a string, e.g. "llm/delta", "runner/started". */
  event: string;
  /**
   * Event-specific payload; shape varies per `event` (wire doc §3.1).
   *
   * On a forked branch run, side-effect-bearing events may carry a replay
   * marker `data["replay/source"]` (wire doc §9.4): "captured" (served from a
   * matched capture) or "live" (executed now). Absent on the original run.
   */
  data: Record<string, unknown> & { "replay/source"?: ReplaySource };
  /** Unknown top-level bookkeeping keys (transcript/node-id, ...) tolerated. */
  [extra: string]: unknown;
}

/** Phase / config snapshot for the header strip (wire doc §4). */
export interface PhaseEnvelope {
  kind: "phase";
  ts?: number;
  /** Active leaf-path config, e.g. ["run","route-planner"]. */
  config: string[];
  /** Optional ancestor chain for the header. */
  breadcrumb?: string[];
  /** Optional sibling states of the active leaf. */
  siblings?: string[];
  [extra: string]: unknown;
}

/** Human-input prompt that opens a modal and parks a chart worker (wire doc §5.1). */
export interface PromptEnvelope {
  kind: "prompt";
  "prompt-id": string;
  invokeid: string;
  type: "text" | "select" | "multi" | "confirm";
  /** Flat HumanRenderer param map (prompt/options/default/...). */
  opts: Record<string, unknown>;
  [extra: string]: unknown;
}

/**
 * Live debugger snapshot (wire doc §6). Pushed by the agent when a debug
 * controller is active (`--debug --tui=opentui`): on connect, on auto-pause at
 * startup, and after every pause/step/continue/arm op. Drives the PAUSED banner
 * + the Debugger view without polling.
 */
export interface DebugEnvelope {
  kind: "debug";
  ts?: number;
  /** True while the runner is halting event processing. */
  paused: boolean;
  /** Events the runner may process before re-checking mode (0 when fully paused). */
  "step-budget": number;
  /** Optional active config the agent had at snapshot time (informational). */
  config?: string[];

  // --- Optional time-travel debugger state (wire doc §9.3) -----------------
  /**
   * Debugger mode. Absent ⇒ treat as "running" (no debug gate engaged).
   * "paused-at-turn": parked at the LLM turn gate; "branch-running": a forked
   * branch is executing forward after `rerun-from`.
   */
  mode?: "running" | "paused-at-turn" | "branch-running";
  /** Current turn pointer while paused at a turn gate; null/absent otherwise. */
  "turn-index"?: number | null;
  /** True after `arm-llm-breakpoint` until the gate is hit. Absent ⇒ false. */
  "breakpoint-armed"?: boolean;
  /** Active forked branch, or null/absent when on the original run. */
  branch?: DebugBranch | null;
  /** Set when a `rerun-from` could not start a branch (so Ctrl-R is never a
   *  silent no-op). Human-readable; cleared by the next debug frame. */
  "branch-error"?: string | null;

  [extra: string]: unknown;
}

/** Active forked branch descriptor on the extended `debug` frame (wire doc §9.3). */
export interface DebugBranch {
  /** Opaque branch session id. */
  "session-id": string;
  /** Opaque parent session id. */
  parent: string;
  "branch-point": BranchPoint;
}

/**
 * Model catalog (wire doc §9.2). Answers `request-model-catalog`; feeds the
 * model/alias dropdown in the debug form.
 */
export interface ModelCatalogEnvelope {
  kind: "model-catalog";
  ts?: number;
  aliases: CatalogAlias[];
  /** Optional ordered preference list (alias name strings). */
  preferences?: string[] | null;
  [extra: string]: unknown;
}

/**
 * Editable conversation transcript (wire doc §9.2). Answers
 * `request-conversation`; the UI edits `turns` in place and ships the edited
 * prefix back as `rerun-from` overrides.
 */
export interface ConversationEnvelope {
  kind: "conversation";
  ts?: number;
  /** Opaque invocation id (the llm-conversation invokeid). */
  invokeid: string;
  /** Opaque node id. */
  "node-id": string;
  visit: number;
  turns: ConversationTurn[];
  [extra: string]: unknown;
}

/** Any frame the agent may push to the UI. */
export type ForwardFrame =
  | EventEnvelope
  | PhaseEnvelope
  | PromptEnvelope
  | DebugEnvelope
  | ModelCatalogEnvelope
  | ConversationEnvelope;

// --- Back-channel envelopes (UI -> agent) ----------------------------------

/** Answer to a parked prompt, keyed by prompt-id (wire doc §5.2). */
export type AnswerFrame =
  | { kind: "answer"; "prompt-id": string; value: unknown }
  | { kind: "answer"; "prompt-id": string; cancelled: true };

/**
 * Control / interrupt / quit op (wire doc §6). Time-travel debugger ops
 * (wire doc §9.1) reuse the same `{ "kind":"control", "op":… }` envelope.
 */
export type ControlFrame =
  | SimpleControlFrame
  | RerunFromFrame
  | RequestConversationFrame;

/** The plain ops that carry no payload beyond the op itself (+ optional `n`). */
export interface SimpleControlFrame {
  kind: "control";
  op:
    // existing §6 ops
    | "pause"
    | "step"
    | "continue"
    | "arm"
    | "ui-interrupt"
    | "ui-quit"
    // new debugger ops (wire doc §9.1) with no extra payload
    | "arm-llm-breakpoint"
    | "turn-next"
    | "turn-back"
    | "request-model-catalog";
  /** step only: step-budget bump, default 1. */
  n?: number;
}

/** `request-conversation`: pull the editable transcript for one invocation (§9.1). */
export interface RequestConversationFrame {
  kind: "control";
  op: "request-conversation";
  /** Opaque llm-conversation invocation id. */
  invokeid: string;
  /** Capture coordinates resolved sidecar-side from the llm/request fold, so the
   *  agent reads `nodes/<node-id>/<visit>/turns/…` directly. node-id rides as
   *  `(str <kw>)` (":writer"); the agent's encode-node-id strips the colon.
   *  Optional — absent ⇒ agent defaults visit 0 (and likely an empty editor). */
  "node-id"?: string;
  visit?: number;
}

/** `rerun-from`: fork a branch at a chosen turn and re-enter with overrides (§9.1). */
export interface RerunFromFrame {
  kind: "control";
  op: "rerun-from";
  /** Opaque parent session id (never parsed). */
  "session-id": string;
  /** Opaque llm-conversation invocation id. */
  invokeid: string;
  /** Opaque node id. */
  "node-id": string;
  visit: number;
  /** Turn to resume from (the chosen branch-point turn). */
  turn: number;
  /** Edited override draft; every field optional (omitted/null = unchanged). */
  overrides?: RerunOverrides;
}

/** Any frame the UI may send back to the agent. */
export type OutboundFrame = AnswerFrame | ControlFrame;

// --- Decode ----------------------------------------------------------------

export class WireDecodeError extends Error {
  constructor(message: string, public readonly raw: string) {
    super(message);
    this.name = "WireDecodeError";
  }
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

/**
 * Parse a single wire line/frame (a JSON string) into a typed forward frame.
 *
 * Returns null for frames the UI should skip rather than fold: a
 * `transcript/serialize-error` event (wire doc §2) or an empty/blank line.
 * Throws WireDecodeError for malformed JSON or a missing/unknown `kind`.
 */
export function decodeFrame(line: string): ForwardFrame | null {
  const trimmed = line.trim();
  if (trimmed.length === 0) return null;

  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch (e) {
    throw new WireDecodeError(
      `invalid JSON: ${(e as Error).message}`,
      trimmed,
    );
  }

  if (!isObject(parsed)) {
    throw new WireDecodeError("frame is not a JSON object", trimmed);
  }

  const kind = parsed["kind"];
  switch (kind) {
    case "event": {
      const env = parsed as EventEnvelope;
      // Tolerate (skip) a serialize-error row -- agent emits it instead of a
      // half line; the UI logs/ignores it.
      if (env.event === "transcript/serialize-error") return null;
      if (typeof env.event !== "string") {
        throw new WireDecodeError("event envelope missing string `event`", trimmed);
      }
      if (!isObject(env.data)) {
        // Be lenient: some events carry no data. Normalize to {}.
        (env as { data: Record<string, unknown> }).data = {};
      }
      return env;
    }
    case "phase":
      return parsed as PhaseEnvelope;
    case "prompt":
      return parsed as PromptEnvelope;
    case "debug":
      return parsed as DebugEnvelope;
    case "model-catalog":
      return parsed as ModelCatalogEnvelope;
    case "conversation":
      return parsed as ConversationEnvelope;
    case "answer":
    case "control":
      // Back-channel (UI -> agent) frames. They are not forward frames, but a
      // recorded replay fixture may include them to exercise a round-trip
      // (wire doc §8). Skip rather than error -- the forward decoder ignores them.
      return null;
    default:
      throw new WireDecodeError(
        `unknown or missing frame kind: ${JSON.stringify(kind)}`,
        trimmed,
      );
  }
}

/** Encode an outbound frame for the back-channel. */
export function encodeFrame(frame: OutboundFrame): string {
  return JSON.stringify(frame);
}
