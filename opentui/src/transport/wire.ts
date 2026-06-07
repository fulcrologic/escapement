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

// --- Forward envelopes (agent -> UI) ---------------------------------------

/** Top-level `kind` discriminants seen on the wire (wire doc §7). */
export type FrameKind =
  | "event"
  | "phase"
  | "prompt"
  | "debug"
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
  /** Event-specific payload; shape varies per `event` (wire doc §3.1). */
  data: Record<string, unknown>;
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
  [extra: string]: unknown;
}

/** Any frame the agent may push to the UI. */
export type ForwardFrame =
  | EventEnvelope
  | PhaseEnvelope
  | PromptEnvelope
  | DebugEnvelope;

// --- Back-channel envelopes (UI -> agent) ----------------------------------

/** Answer to a parked prompt, keyed by prompt-id (wire doc §5.2). */
export type AnswerFrame =
  | { kind: "answer"; "prompt-id": string; value: unknown }
  | { kind: "answer"; "prompt-id": string; cancelled: true };

/** Control / interrupt / quit op (wire doc §6). */
export interface ControlFrame {
  kind: "control";
  op: "pause" | "step" | "continue" | "arm" | "ui-interrupt" | "ui-quit";
  /** step only: step-budget bump, default 1. */
  n?: number;
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
