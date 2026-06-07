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

/** Create the reactive domain store wired to a transport EventSource. */
export function createDomainStore(source: EventSource): DomainStore {
  let current: DomainState = initialDomainState();
  const [state, setState] = createStore<DomainState>(current);

  const apply = (next: DomainState) => {
    current = next;
    setState(reconcile(next, { merge: true }));
  };

  const push = (frame: ForwardFrame) => apply(reduceFrame(current, frame));

  const off = source.onFrame(push);

  const dispose = () => {
    off();
    source.stop();
  };

  return { state, push, dispose };
}
