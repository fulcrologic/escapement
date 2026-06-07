/**
 * Solid reactive wrapper around the pure `reduceFrame` reducer + the derived
 * selectors the panes consume. Keeping the reducer pure (store.ts) and the
 * Solid wiring thin here means tasks 015/016 unit-test the logic with no
 * OpenTUI/Solid, while the live UI (tasks 008+) gets fine-grained reactivity.
 *
 * `createDomainStore(source)` subscribes to the transport EventSource, folds
 * every forward frame through `reduceFrame`, and applies the result to a Solid
 * store via `reconcile` (so only changed leaves notify). It returns the
 * read-only store plus a `dispose()` that unsubscribes + stops the source.
 */

import { batch } from "solid-js";
import { createStore, reconcile } from "solid-js/store";
import type { EventSource } from "../transport/event-source";
import type { ForwardFrame } from "../transport/wire";
import {
  type DomainState,
  initialDomainState,
  reduceFrame,
} from "./store";
import { liveAgg, statusRank } from "./aggregate";
import type { LiveAgg, LiveMap } from "./types";

export interface DomainStore {
  /** Read-only reactive snapshot of the mirrored stream. */
  state: DomainState;
  /** Manually fold a frame (used by replay drivers / tests with a real store). */
  push(frame: ForwardFrame): void;
  /** Unsubscribe from the source and stop it. */
  dispose(): void;
}

/** One invokeid group's rollup + its raw sessions + the invokeid, sorted-ready. */
export interface LiveGroup extends LiveAgg {
  iid: string;
  sessions: Record<string, import("./types").LiveSession>;
}

/**
 * Ordered group list, one entry per invokeid/role, sorted so in-flight groups
 * stay on top (status rank asc, then most-recent activity first). Port of
 * `live-groups` in `tui/live.clj` — the panes (task 009) and the row-index map
 * build on this exact ordering.
 */
export function liveGroups(live: LiveMap): LiveGroup[] {
  const groups: LiveGroup[] = Object.entries(live).map(([iid, g]) => ({
    ...liveAgg(g.sessions),
    iid,
    sessions: g.sessions,
  }));
  groups.sort((a, b) => {
    const ra = statusRank(a.status);
    const rb = statusRank(b.status);
    if (ra !== rb) return ra - rb;
    // (- last-ts): most recent first.
    return (b["last-ts"] ?? 0) - (a["last-ts"] ?? 0);
  });
  return groups;
}

/**
 * Live frames are COALESCED before being committed to the Solid store. Each
 * `reconcile()` diffs the whole DomainState tree (scrollback ≤2000, the live
 * map, the event ring); doing that per frame at a streaming rate of hundreds of
 * `llm/delta`s per second saturates the single JS thread, starving keystrokes
 * and the render loop (the symptom: laggy LIVE selection + a LOG pane that
 * trails the stream). Instead we fold all frames that arrive within one window
 * through the pure reducer and `reconcile()` ONCE per window — so the store
 * commits at ~`FLUSH_MS` cadence regardless of token rate, and many same-session
 * deltas collapse into a single tree diff. (The agent-side WS already coalesces
 * consecutive deltas per client; this is the matching coalesce on the UI side.)
 */
const FLUSH_MS = 16;

/**
 * Create the reactive domain store wired to a transport EventSource.
 * `flushMs` is the live-coalesce window (see {@link FLUSH_MS}); pass `0` to
 * commit synchronously per frame (used by deterministic tests).
 */
export function createDomainStore(
  source: EventSource,
  flushMs: number = FLUSH_MS,
): DomainStore {
  let current: DomainState = initialDomainState();
  const [state, setState] = createStore<DomainState>(current);

  const commit = (next: DomainState) => {
    current = next;
    setState(reconcile(next, { merge: true }));
  };

  // `push` stays synchronous: replay drivers / tests fold a frame and read the
  // store immediately. The live source path (below) batches instead.
  const push = (frame: ForwardFrame) => commit(reduceFrame(current, frame));

  // Coalescing buffer for the live source. Frames accumulate here and are folded
  // + committed in one pass per flush window.
  let pending: ForwardFrame[] = [];
  let timer: ReturnType<typeof setTimeout> | null = null;
  const flush = () => {
    timer = null;
    if (pending.length === 0) return;
    const frames = pending;
    pending = [];
    let next = current;
    for (const f of frames) next = reduceFrame(next, f);
    batch(() => commit(next));
  };
  const onFrame = (frame: ForwardFrame) => {
    if (flushMs <= 0) {
      push(frame);
      return;
    }
    pending.push(frame);
    if (timer === null) timer = setTimeout(flush, flushMs);
  };

  const off = source.onFrame(onFrame);

  const dispose = () => {
    off();
    if (timer !== null) clearTimeout(timer);
    flush(); // drain any buffered frames so the final state is committed
    source.stop();
  };

  return { state, push, dispose };
}
