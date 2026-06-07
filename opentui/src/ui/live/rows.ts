/**
 * LIVE-pane row model — the single source of truth for which logical rows the
 * panel renders, in order, capped exactly like the JLine TUI.
 *
 * Port of `escapement.tui.live/live-row-index` (+ the row layout half of
 * `live-pane-lines`): from the SAME `liveGroups` ordering, expand each group
 * into its visible rows so the rendered rows and the cursor→drill-in target
 * stay in LOCKSTEP. Drill-in itself is task 011/012; here we expose the row
 * descriptors (incl. the `target` a cursor row would open) plus the data each
 * row needs to render.
 *
 * Caps: `LIVE_MAX_GROUPS = 4`, `LIVE_GROUP_CHILDREN = 6`, then a `…+N more`
 * roll-up row.
 */

import { statusRank } from "../../domain/aggregate";
import type { LiveGroup } from "../../domain/solid-store";
import type { LiveSession } from "../../domain/types";

/** Cap on rendered invocation GROUPS (one per invokeid/role). */
export const LIVE_MAX_GROUPS = 4;
/** Cap on concurrent sessions shown before a `…+N more` roll-up. */
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
  | {
      kind: "single";
      group: LiveGroup;
      session: LiveSession;
      target: RowTarget;
    }
  | {
      kind: "group-header";
      group: LiveGroup;
      done: number;
      total: number;
      target: RowTarget;
    }
  | {
      kind: "child";
      group: LiveGroup;
      session: LiveSession;
      last: boolean;
      target: RowTarget;
    }
  | {
      kind: "more";
      group: LiveGroup;
      more: number;
      target: RowTarget;
    };

/** Sort a group's sessions: in-flight first, then most-recent activity. */
function sortedSessions(group: LiveGroup): LiveSession[] {
  return Object.values(group.sessions).slice().sort((a, b) => {
    const r = statusRank(a.status) - statusRank(b.status);
    if (r !== 0) return r;
    return (b["last-ts"] ?? 0) - (a["last-ts"] ?? 0);
  });
}

/** The representative (most in-flight) session id of a group. */
function bestSession(group: LiveGroup): string | null {
  return sortedSessions(group)[0]?.session ?? null;
}

/**
 * Expand a sorted, capped group list into the visible row list. The order
 * mirrors `live-pane-lines` exactly, so a cursor index into this array is a
 * 1:1 drill-in lookup (parity with `live-row-index`).
 */
export function liveRows(groups: LiveGroup[]): LiveRow[] {
  const rows: LiveRow[] = [];
  for (const group of groups.slice(0, LIVE_MAX_GROUPS)) {
    const iid = group.iid;
    if (group.n <= 1) {
      const session = Object.values(group.sessions)[0];
      if (!session) continue;
      rows.push({
        kind: "single",
        group,
        session,
        target: { invokeid: iid, session: session.session, kind: "session" },
      });
      continue;
    }
    // multi-session group → header + indented kids + maybe …+N more
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
      const last = more === 0 && i === kids.length - 1;
      rows.push({
        kind: "child",
        group,
        session,
        last,
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
  return rows;
}

/**
 * The cursor→drill-in index, parallel to {@link liveRows}. Port of
 * `live-row-index`: each entry is the {@link RowTarget} the row at that index
 * opens. Task 011/012 indexes this with the LIVE cursor.
 */
export function liveRowIndex(groups: LiveGroup[]): RowTarget[] {
  return liveRows(groups).map((r) => r.target);
}
