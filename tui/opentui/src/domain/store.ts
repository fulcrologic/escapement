/**
 * The domain store: a Solid store mirroring the event stream, fed by the
 * transport `EventSource` (task 005). It ports the routing of `event!` /
 * `event!*` from `escapement.tui`:
 *
 *  - `llm/delta`  → updates the LIVE map ONLY (never scrollback — deltas are
 *    high-frequency; scrollback gets the response, not each token).
 *  - every other event → an `entries-for` scrollback fold (trimmed ~2000),
 *    plus the live-lifecycle fold for start/response/error/model-down/
 *    worker-exit, the invocations history (start/worker-exit, capped 200), the
 *    inspector event ring (capped 1000), and the active-config / phase update.
 *  - `phase`  frames update the phase model.
 *  - `prompt` frames are surfaced for the modal layer (task 013).
 *
 * The reducer (`reduceFrame`) is a PURE function of (state, frame) so tasks
 * 015/016 can drive it over recorded JSONL with no OpenTUI / Solid. The Solid
 * store (`createDomainStore`) just wires the EventSource into it reactively.
 */

import type {
  DebugEnvelope,
  EventEnvelope,
  ForwardFrame,
  PhaseEnvelope,
  PromptEnvelope,
} from "../transport/wire";
import type { EventSource } from "../transport/event-source";
import {
  debugEventOfInterest,
  entriesFor,
  updateInvocationHistory,
} from "./entries";
import { foldLiveEvent } from "./aggregate";
import type {
  EventLike,
  InvocationEntry,
  LiveMap,
  PhaseModel,
  ScrollbackEntry,
} from "./types";

const SCROLLBACK_CAP = 2000;
const EVENTS_CAP = 1000;

/** The full mirrored domain state. */
export interface DomainState {
  /** Rendered scrollback entries (excludes deltas), trimmed to SCROLLBACK_CAP. */
  scrollback: ScrollbackEntry[];
  /** invokeid -> { sessions: { session-id -> LiveSession } }. */
  live: LiveMap;
  /** Newest-first invocation history, capped 200. */
  invocations: InvocationEntry[];
  /** Inspector event ring (event-processed + debug/*), capped 1000. */
  events: EventLike[];
  /** Active config (last `config-after` / `start-config`). */
  config: string[];
  /** Phase model from explicit `phase` snapshots (breadcrumb/siblings). */
  phase: PhaseModel | null;
  /** The last open human-input prompt, for the modal layer (task 013). */
  prompt: PromptEnvelope | null;
  /** Live debugger state from `debug` snapshots (task 014). null ⇒ no controller. */
  debug: DebugState | null;
  /** Highest `seq` folded — for reconnect de-dup ("events since seq N"). */
  lastSeq: number;
}

/** Live debugger snapshot mirrored into the store (task 014). */
export interface DebugState {
  paused: boolean;
  stepBudget: number;
  /** Active config the agent reported at snapshot time, if any. */
  config?: string[];
}

export function initialDomainState(): DomainState {
  return {
    scrollback: [],
    live: {},
    invocations: [],
    events: [],
    config: [],
    phase: null,
    prompt: null,
    debug: null,
    lastSeq: -1,
  };
}

const LIVE_LIFECYCLE = new Set([
  "llm/start",
  "llm/request",
  "llm/response",
  "llm/error",
  "llm/model-down",
  "llm/worker-exit",
]);
const HISTORY_EVENTS = new Set(["llm/start", "llm/worker-exit"]);

function toEventLike(env: EventEnvelope): EventLike {
  return { event: env.event, seq: env.seq, ts: env.ts, data: env.data ?? {} };
}

/** Fold a decoded `event` envelope into state. Pure. Mirrors `event!`/`event!*`. */
export function reduceEvent(state: DomainState, env: EventEnvelope): DomainState {
  const ev = toEventLike(env);
  const lastSeq =
    typeof env.seq === "number" ? Math.max(state.lastSeq, env.seq) : state.lastSeq;

  // Hot path: deltas update the live map ONLY.
  if (ev.event === "llm/delta") {
    return { ...state, live: foldLiveEvent(state.live, ev), lastSeq };
  }

  const next: DomainState = { ...state, lastSeq };

  // Scrollback entries (entries-for). Multi-line events expand to several.
  const entries = entriesFor(ev);
  if (entries.length > 0) {
    let sb = state.scrollback.concat(entries);
    if (sb.length > SCROLLBACK_CAP) sb = sb.slice(sb.length - SCROLLBACK_CAP);
    next.scrollback = sb;
  }

  // Active-config / phase update from the authoritative signals.
  const cfgAfter = ev.data["config-after"] as string[] | undefined;
  if (cfgAfter !== undefined && cfgAfter !== null) next.config = cfgAfter;
  if (ev.event === "runner/start-config") {
    const cfg = ev.data["config"] as string[] | undefined;
    if (cfg !== undefined && cfg !== null) next.config = cfg;
  }

  // Inspector event ring.
  if (debugEventOfInterest(ev)) {
    let evs = state.events.concat([ev]);
    if (evs.length > EVENTS_CAP) evs = evs.slice(evs.length - EVENTS_CAP);
    next.events = evs;
  }

  // Invocation history.
  if (HISTORY_EVENTS.has(ev.event)) {
    // Pass the PRE-fold live map so worker-exit can freeze the invocation's own
    // token count before the live lifecycle fold (below) mutates the session.
    next.invocations = updateInvocationHistory(state.invocations, ev, state.live);
  }

  // Live-panel lifecycle (deltas already handled above on the fast path).
  if (LIVE_LIFECYCLE.has(ev.event)) {
    next.live = foldLiveEvent(next.live, ev);
  }

  return next;
}

/** Fold a `phase` snapshot into state. Pure. */
export function reducePhase(state: DomainState, env: PhaseEnvelope): DomainState {
  return {
    ...state,
    config: env.config ?? state.config,
    phase: {
      config: env.config ?? state.config,
      breadcrumb: env.breadcrumb,
      siblings: env.siblings,
    },
  };
}

/** Fold a `prompt` frame into state (surface for the modal layer). Pure. */
export function reducePrompt(state: DomainState, env: PromptEnvelope): DomainState {
  return { ...state, prompt: env };
}

/** Fold a `debug` snapshot into state (live debugger). Pure. */
export function reduceDebug(state: DomainState, env: DebugEnvelope): DomainState {
  return {
    ...state,
    debug: {
      paused: Boolean(env.paused),
      stepBudget:
        typeof env["step-budget"] === "number" ? env["step-budget"] : 0,
      // Keep a previously-reported debug config if this snapshot omits one.
      config:
        env.config ?? (state.debug ? state.debug.config : undefined),
    },
  };
}

/** Single dispatch over any forward frame. Pure — the heart of the store. */
export function reduceFrame(state: DomainState, frame: ForwardFrame): DomainState {
  switch (frame.kind) {
    case "event":
      return reduceEvent(state, frame);
    case "phase":
      return reducePhase(state, frame);
    case "prompt":
      return reducePrompt(state, frame);
    case "debug":
      return reduceDebug(state, frame);
    default:
      return state;
  }
}

/** Fold a whole sequence of frames (used by replay / tests). Pure. */
export function reduceFrames(
  frames: ForwardFrame[],
  state: DomainState = initialDomainState(),
): DomainState {
  let s = state;
  for (const f of frames) s = reduceFrame(s, f);
  return s;
}
