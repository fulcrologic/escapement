/**
 * LIVE-pane hierarchical grouping (option C): turn the flat invokeid-keyed live
 * map into the tree the ENGINE already implies, with ZERO statechart changes.
 *
 * The multiplex invocation processor names every child session deterministically
 * `:multiplex.<mux-id>.<idx>` (statecharts `multiplex_processor`/`child-invokeid`).
 * That single id encodes the whole hierarchy: the PHASE (the multiplex's id, e.g.
 * `poets` / `judges-r1`) and the CHILD index (the poet/judge number). Combined
 * with each call's invokeid we can reconstruct:
 *
 *     phase (mux-id) → child (idx) → call (invokeid)
 *
 * so e.g. the secret Muse/Critique calls nest under their poet automatically.
 *
 * This is purely additive and engine-agnostic: only sessions whose id matches
 * `multiplex.<mux>.<idx>` are folded into phases; every other session (planner,
 * host, or any non-multiplex chart) is returned untouched as a normal invokeid
 * group, so charts that don't use multiplex render exactly as before.
 */

import type { LiveGroup } from "../../domain/solid-store";
import type { LiveAgg, LiveSession } from "../../domain/types";
import { liveAgg, liveBucket } from "../../domain/aggregate";

/** One LLM call within a child: its invokeid + live counter. */
export interface ChildCall {
  iid: string;
  session: LiveSession;
}

/** One multiplex child (a single poet/judge) and the calls it ran. */
export interface ChildNode {
  phase: string;
  idx: number;
  /** Display label, e.g. `poets.1` (parity with `shortSession`). */
  label: string;
  /** Calls in chronological (first-token) order: muse, haiku-1…3, critique… */
  calls: ChildCall[];
  agg: LiveAgg;
}

/** One multiplex phase (one `multiplex` invoke) and its children. */
export interface PhaseNode {
  phase: string;
  children: ChildNode[];
  agg: LiveAgg;
  /** Children all of whose calls are done — drives the phase completion bar. */
  doneChildren: number;
}

export interface SplitTree {
  phases: PhaseNode[];
  /** Non-multiplex invokeid groups (planner, host, …), passed through as-is. */
  nonMux: LiveGroup[];
}

/**
 * Parse a multiplex child session id into `{ phase, idx }`, or null when the id
 * is not a multiplex child (parent sessions, plain charts). Tolerant of the
 * leading `:` that `(str keyword)` leaves on the wire. `mux-id` never contains a
 * trailing `.<digits>` segment, so the greedy phase capture is unambiguous.
 */
export function parseChild(sid: unknown): { phase: string; idx: number } | null {
  const s = String(sid ?? "").replace(/^:/, "");
  const m = /^multiplex\.(.+)\.(\d+)$/.exec(s);
  if (!m) return null;
  return { phase: m[1]!, idx: Number(m[2]) };
}

/** Aggregate an arbitrary set of calls into a LiveAgg. */
function aggOfCalls(calls: ChildCall[]): LiveAgg {
  const rec: Record<string, LiveSession> = {};
  calls.forEach((c, i) => {
    rec[`${c.iid}#${i}`] = c.session;
  });
  return liveAgg(rec);
}

/**
 * Split the flat invokeid groups into multiplex phases + passthrough groups.
 * Children are ordered in-flight-above-terminal, then STABLY by child index
 * (poet/judge number) so a running child surfaces above the cap while siblings
 * keep their natural index order. Phases are returned UNSORTED at the top level —
 * `liveRows` interleaves them with the non-mux groups via the shared
 * `compareLiveOrder` (the single live-ordering source of truth).
 * Calls within a child stay in CHRONOLOGICAL order (first-token ts) so a poet
 * reads muse → haiku-1 → haiku-2 → haiku-3 → critique → revise top-to-bottom.
 */
export function buildTree(groups: LiveGroup[]): SplitTree {
  const phaseMap = new Map<string, Map<number, ChildCall[]>>();
  const nonMux: LiveGroup[] = [];

  for (const g of groups) {
    const rest: Record<string, LiveSession> = {};
    for (const sess of Object.values(g.sessions)) {
      const pc = parseChild(sess.session);
      if (pc) {
        let byIdx = phaseMap.get(pc.phase);
        if (!byIdx) {
          byIdx = new Map();
          phaseMap.set(pc.phase, byIdx);
        }
        const arr = byIdx.get(pc.idx) ?? [];
        arr.push({ iid: g.iid, session: sess });
        byIdx.set(pc.idx, arr);
      } else {
        rest[String(sess.session)] = sess;
      }
    }
    // Re-aggregate the non-multiplex remainder of this invokeid so its rollup
    // reflects only the passed-through sessions.
    if (Object.keys(rest).length > 0) {
      nonMux.push({ ...g, ...liveAgg(rest), sessions: rest, iid: g.iid });
    }
  }

  const phases: PhaseNode[] = [];
  for (const [phase, byIdx] of phaseMap) {
    const children: ChildNode[] = [];
    for (const [idx, calls] of byIdx) {
      const ordered = calls
        .slice()
        .sort(
          (a, b) =>
            (a.session["first-ts"] ?? 0) - (b.session["first-ts"] ?? 0),
        );
      children.push({
        phase,
        idx,
        label: `${phase}.${idx}`,
        calls: ordered,
        agg: aggOfCalls(calls),
      });
    }
    children.sort((a, b) => {
      const r = liveBucket(a.agg.status) - liveBucket(b.agg.status);
      if (r !== 0) return r;
      return a.idx - b.idx;
    });
    const allCalls = children.flatMap((c) => c.calls);
    phases.push({
      phase,
      children,
      agg: aggOfCalls(allCalls),
      doneChildren: children.filter((c) =>
        c.calls.every((cc) => cc.session.status === "done"),
      ).length,
    });
  }
  // Phases are returned UNSORTED at the top level: `liveRows` interleaves them
  // with the non-mux groups via the shared `compareLiveOrder` (the single
  // live-ordering source of truth), so any sort here would be discarded.
  return { phases, nonMux };
}
