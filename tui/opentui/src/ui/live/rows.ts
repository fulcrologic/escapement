/**
 * LIVE-pane row model — the single source of truth for which logical rows the
 * panel renders, in order, capped exactly like the JLine TUI. `liveRows` and
 * `liveRowIndex` stay in LOCKSTEP so a cursor index is a 1:1 drill-in lookup.
 *
 * Two shapes coexist:
 *   • MULTIPLEX phases render as a 3-level tree — phase header → child (poet/
 *     judge) → call (invokeid) — reconstructed from the engine's deterministic
 *     `multiplex.<mux>.<idx>` session ids (see {@link buildTree}). A child with
 *     a single call collapses to one row; a child with several (e.g. a poet's
 *     muse + 3 haiku + critique) gets a child header plus indented call rows.
 *   • NON-multiplex invokeids (planner, host, any plain chart) keep the original
 *     per-invokeid grouping: a lone session is one flat row; concurrent sessions
 *     get a group header + indented child rows.
 *
 * Caps: ALL top-level units render (the panel's sticky-bottom `<scrollbox>` owns
 * overflow). Within a group/phase, children cap at `LIVE_GROUP_CHILDREN`, then a
 * `…+N more` roll-up row.
 */

import { compareLiveOrder, liveBucket, sessionStartKey } from "../../domain/aggregate";
import type { LiveGroup } from "../../domain/solid-store";
import type { LiveSession } from "../../domain/types";
import { buildTree, type ChildCall, type ChildNode, type PhaseNode } from "./tree";

/** Cap on concurrent sessions/children shown before a `…+N more` roll-up. */
export const LIVE_GROUP_CHILDREN = 6;

/** What `Enter` on a row opens (parity with `live-row-index`). */
export interface RowTarget {
  invokeid: string;
  /** Representative / own session id; null only if a group had no sessions. */
  session: string | null;
  kind: "group" | "session" | "more";
}

/** A row to render + the drill-in target it maps to. */
export type LiveRow =
  // --- non-multiplex invokeid groups (unchanged) ---
  | { kind: "single"; group: LiveGroup; session: LiveSession; target: RowTarget }
  | {
      kind: "group-header";
      group: LiveGroup;
      done: number;
      total: number;
      target: RowTarget;
    }
  | { kind: "child"; group: LiveGroup; session: LiveSession; last: boolean; target: RowTarget }
  | { kind: "more"; group: LiveGroup; more: number; target: RowTarget }
  // --- multiplex phase tree (option C) ---
  | { kind: "phase-header"; phase: PhaseNode; done: number; total: number; target: RowTarget }
  | { kind: "child-header"; child: ChildNode; last: boolean; target: RowTarget }
  | { kind: "call"; child: ChildNode; call: ChildCall; last: boolean; target: RowTarget }
  | { kind: "child-single"; child: ChildNode; call: ChildCall; last: boolean; target: RowTarget }
  | { kind: "phase-more"; phase: PhaseNode; more: number; target: RowTarget };

/**
 * WITHIN-group child order: in-flight (streaming/waiting) above terminal, then
 * by first-appearance (start ts) ascending. This is the child level only — the
 * TOP-level interleave of whole groups/phases is recency-ordered by
 * `compareLiveOrder` (the shared source of truth). Children keep first-appearance
 * order so siblings under one group stay in their natural index order.
 */
function sortedSessions(group: LiveGroup): LiveSession[] {
  return Object.values(group.sessions).slice().sort((a, b) => {
    const r = liveBucket(a.status) - liveBucket(b.status);
    if (r !== 0) return r;
    return sessionStartKey(a) - sessionStartKey(b);
  });
}

/** The representative (most in-flight) session id of a group. */
function bestSession(group: LiveGroup): string | null {
  return sortedSessions(group)[0]?.session ?? null;
}

/** Expand one non-multiplex invokeid group (single | header + children + more). */
function pushGroupRows(rows: LiveRow[], group: LiveGroup): void {
  const iid = group.iid;
  if (group.n <= 1) {
    const session = Object.values(group.sessions)[0];
    if (!session) return;
    rows.push({
      kind: "single",
      group,
      session,
      target: { invokeid: iid, session: session.session, kind: "session" },
    });
    return;
  }
  const rep = bestSession(group);
  rows.push({
    kind: "group-header",
    group,
    done: group["n-done"],
    total: group.n,
    target: { invokeid: iid, session: rep, kind: "group" },
  });
  const kids = sortedSessions(group).slice(0, LIVE_GROUP_CHILDREN);
  const more = group.n - kids.length;
  kids.forEach((session, i) => {
    rows.push({
      kind: "child",
      group,
      session,
      last: more === 0 && i === kids.length - 1,
      target: { invokeid: iid, session: session.session, kind: "session" },
    });
  });
  if (more > 0) {
    rows.push({
      kind: "more",
      group,
      more,
      target: { invokeid: iid, session: rep, kind: "more" },
    });
  }
}

/** Expand one multiplex phase: header → children (collapsed or header+calls). */
function pushPhaseRows(rows: LiveRow[], phase: PhaseNode): void {
  const total = phase.children.length;
  const rep = phase.children[0];
  const repSid = rep ? `multiplex.${phase.phase}.${rep.idx}` : null;
  const repIid = rep?.calls[0]?.iid ?? phase.phase;
  rows.push({
    kind: "phase-header",
    phase,
    done: phase.doneChildren,
    total,
    target: { invokeid: repIid, session: repSid, kind: "group" },
  });
  const kids = phase.children.slice(0, LIVE_GROUP_CHILDREN);
  const more = total - kids.length;
  kids.forEach((child, i) => {
    const lastChild = more === 0 && i === kids.length - 1;
    const childSid = `multiplex.${child.phase}.${child.idx}`;
    if (child.calls.length <= 1) {
      const call = child.calls[0]!;
      rows.push({
        kind: "child-single",
        child,
        call,
        last: lastChild,
        target: { invokeid: call.iid, session: String(call.session.session), kind: "session" },
      });
    } else {
      rows.push({
        kind: "child-header",
        child,
        last: lastChild,
        target: { invokeid: child.calls[0]!.iid, session: childSid, kind: "group" },
      });
      child.calls.forEach((call, j) => {
        rows.push({
          kind: "call",
          child,
          call,
          last: j === child.calls.length - 1,
          target: { invokeid: call.iid, session: String(call.session.session), kind: "session" },
        });
      });
    }
  });
  if (more > 0) {
    rows.push({
      kind: "phase-more",
      phase,
      more,
      target: { invokeid: repIid, session: null, kind: "more" },
    });
  }
}

/**
 * Expand the live groups into the visible row list. Multiplex phases and
 * non-multiplex groups are interleaved at the top level by the SAME
 * in-flight-first / most-recent ordering, then each is expanded in place.
 */
export function liveRows(groups: LiveGroup[]): LiveRow[] {
  const { phases, nonMux } = buildTree(groups);
  // Top-level units (multiplex phases + non-mux invokeid groups) interleave via
  // the SHARED `compareLiveOrder` — the single live-ordering source of truth
  // (`liveGroups` uses the same comparator). Each unit's orderable is its own
  // status + `last-ts`; a phase reads them off its aggregate.
  type Unit =
    | { kind: "phase"; node: PhaseNode }
    | { kind: "group"; node: LiveGroup };
  const units: Unit[] = [
    ...phases.map((node) => ({ kind: "phase" as const, node })),
    ...nonMux.map((node) => ({ kind: "group" as const, node })),
  ];
  const orderableOf = (u: Unit) =>
    u.kind === "phase" ? u.node.agg : u.node;
  units.sort((a, b) => compareLiveOrder(orderableOf(a), orderableOf(b)));

  const rows: LiveRow[] = [];
  for (const u of units) {
    if (u.kind === "group") pushGroupRows(rows, u.node);
    else pushPhaseRows(rows, u.node);
  }
  return rows;
}

/**
 * The cursor→drill-in index, parallel to {@link liveRows}. Each entry is the
 * {@link RowTarget} the row at that index opens.
 */
export function liveRowIndex(groups: LiveGroup[]): RowTarget[] {
  return liveRows(groups).map((r) => r.target);
}

/**
 * THE single group-hue key for a row — the one source of truth for "one hue per
 * top-level group" (R3). Every row belonging to the same top-level group returns
 * the SAME key, so the view colors a header and ALL its descendant child/call/
 * leaf rows + connectors in one hue (parity with JLINE's per-group `color-for s
 * iid`, live.clj:146/165). The key is the group's color source:
 *   • multiplex phase + its children/calls → the phase id (e.g. `"poets"`)
 *   • non-multiplex invokeid group/single   → the invokeid
 * The view feeds this key to `theme.roleColor(key)`; it must never be derived
 * per-call (that is the "rainbow" regression this helper prevents).
 */
export function groupHueKey(row: LiveRow): string {
  switch (row.kind) {
    case "single":
    case "group-header":
    case "child":
    case "more":
      return row.group.iid;
    case "phase-header":
    case "phase-more":
      return row.phase.phase;
    case "child-header":
    case "call":
    case "child-single":
      return row.child.phase;
  }
}
