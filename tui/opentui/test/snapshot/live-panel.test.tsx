// Pin timezone BEFORE any Date-touching import (ts->hms is local-zone).
process.env.TZ = "UTC";

import { describe, expect, test } from "bun:test";
import { LivePanel } from "../../src/ui/LivePanel";
import { makeTheme } from "../../src/domain/theme";
import { liveGroups } from "../../src/domain/solid-store";
import { foldLiveEvent } from "../../src/domain/aggregate";
import { liveRows, groupHueKey } from "../../src/ui/live/rows";
import { shimmerPos } from "../../src/ui/live/Shimmer";
import { barFill, liveBarWidthFor } from "../../src/ui/live/CompletionBar";
import type { PaneContext } from "../../src/ui/Shell";
import { stateFromFixture, renderFrame } from "./_helpers";

/** A fixed PaneContext for a deterministic render (no signals, no clock). */
function ctx(fixture: string, width: number, focused = true): PaneContext {
  return {
    state: stateFromFixture(fixture),
    theme: makeTheme("none"), // no-color → text-only deterministic glyph frame
    focused,
    width,
  };
}

describe("LivePanel — render-tree text snapshots (fixed tick, no model)", () => {
  test("single-session + multi-session group (haiku fixture)", async () => {
    // poet2 is a 2-session GROUP (header + bar + 2 children); poet1/planner are
    // single rows — all done/error at end of replay (no shimmer here).
    // Width 90 (≥76) so the responsive MODEL column is enabled — below that
    // breakpoint it is dropped to keep the role name / bar from being crowded
    // (parity with the JLine `model-w` gating).
    const c = ctx("haiku-sample.jsonl", 90);
    const { frame } = await renderFrame(() => <LivePanel ctx={c} tick={0} height={24} />, {
      width: 90,
      height: 12,
    });
    expect(frame).toMatchSnapshot();
    // Load-bearing content the snapshot locks:
    expect(frame).toContain("poet2"); // group header role
    expect(frame).toContain("◇"); // group diamond
    expect(frame).toContain("planner");
    expect(frame).toContain("tok"); // metric column
    expect(frame).toContain("t/s");
    expect(frame).toContain("gemma3:1b"); // model column
  });

  test("streaming multi-session group: 6 children + …+N more + shimmer single", async () => {
    // poets = 7 sessions (>LIVE_GROUP_CHILDREN) → header+6 kids+more; host = a
    // streaming single → shimmer bar + in-flight cursor. Tick pinned (3) so the
    // shimmer cell is at a known position.
    const c = ctx("live-streaming.jsonl", 60);
    const { frame } = await renderFrame(() => <LivePanel ctx={c} tick={3} height={24} />, {
      width: 60,
      height: 14,
    });
    expect(frame).toMatchSnapshot();
    expect(frame).toContain("poets");
    expect(frame).toContain("more sessions"); // …+N more roll-up
    expect(frame).toContain("host");
    expect(frame).toContain("▏"); // in-flight cursor on a streaming row
  });

  test("empty live → idle placeholder", async () => {
    const c = ctx("haiku-sample.jsonl", 40);
    (c.state as any).live = {}; // force the fallback branch
    const { frame } = await renderFrame(() => <LivePanel ctx={c} tick={0} height={24} />, {
      width: 40,
      height: 4,
    });
    expect(frame).toContain("no live activity");
  });
});

describe("LivePanel — timing columns (wait / total)", () => {
  // At width ≥100 the row shows the time-to-first-token ("wait") and total
  // elapsed ("tot") columns. Build a done single-session live map by folding a
  // start→delta→response sequence so wait-ms (500) and elapsed-ms (5500) are set.
  function timingState(width = 110) {
    const c = ctx("haiku-sample.jsonl", width);
    let live = {};
    live = foldLiveEvent(live, {
      event: "llm/start",
      ts: 1000,
      data: { invokeid: "planner", "session-id": "s1", model: "gemma3:1b" },
    });
    live = foldLiveEvent(live, {
      event: "llm/delta",
      ts: 1500, // waited 500ms for first token
      data: { invokeid: "planner", "session-id": "s1", text: "hello" },
    });
    live = foldLiveEvent(live, {
      event: "llm/response",
      ts: 7000,
      data: {
        invokeid: "planner",
        "session-id": "s1",
        "stop-reason": "end_turn",
        "elapsed-ms": 5500,
        "usage": { "output-tokens": 42 },
      },
    });
    (c.state as any).live = live;
    return c;
  }

  test("done single row renders 0.5s → 5.5s ttft→tot at width 110", async () => {
    const c = timingState();
    const { frame } = await renderFrame(() => <LivePanel ctx={c} tick={0} height={24} />, {
      width: 110,
      height: 6,
    });
    expect(frame).toMatchSnapshot();
    expect(frame).toContain("ttft→tot"); // single combined timing column label
    expect(frame).toContain("0.5s"); // time-to-first-token
    expect(frame).toContain("5.5s"); // total elapsed
    expect(frame).toContain("→"); // arrow appears once wait is known
  });

  test("timing column is dropped below the width-100 breakpoint", async () => {
    const c = timingState(90);
    const { frame } = await renderFrame(() => <LivePanel ctx={c} tick={0} height={24} />, {
      width: 90,
      height: 6,
    });
    expect(frame).not.toContain("ttft→tot");
    expect(frame).not.toContain("0.5s");
  });
});

describe("LivePanel — group-bar fixed-column alignment (#7)", () => {
  // Two groups whose `done/total` labels differ in digit width ("0/3" vs
  // "0/12"). The bar must start at the SAME column for both (fixed `labelWidth`
  // = widest label), mirroring JLine's fixed `left-w`.
  function session(id: string): import("../../src/domain/types").LiveSession {
    return {
      chunks: 0,
      chars: 0,
      "first-ts": 0,
      "last-ts": 1,
      status: "streaming",
      text: "",
      model: "m",
      session: id,
    };
  }
  function group(n: number): import("../../src/domain/types").LiveGroupEntry {
    const sessions: Record<string, any> = {};
    for (let i = 0; i < n; i++) sessions[`s${i}`] = session(`s${i}`);
    return { sessions };
  }

  test("bars across groups start at the same column", async () => {
    const c: PaneContext = {
      state: {
        ...stateFromFixture("haiku-sample.jsonl"),
        // aa = 3-session group ("0/3 done"); bb = 12-session group ("0/12 done")
        live: { aa: group(3), bb: group(12) } as any,
      },
      theme: makeTheme("none"),
      focused: true,
      width: 60,
    };
    const { frame } = await renderFrame(() => <LivePanel ctx={c} tick={0} height={24} />, {
      width: 60,
      height: 16,
    });
    const headerLines = frame
      .split("\n")
      .filter((l) => l.includes("◇") && l.includes("done"));
    expect(headerLines.length).toBe(2);
    // The completion bar's first cell (█ or ░) must be at the same column on
    // both group-header rows once the label is padded to a fixed width.
    const barCol = (l: string) => {
      const i = l.search(/[█░]/u);
      return i;
    };
    const cols = headerLines.map(barCol);
    expect(cols[0]).toBeGreaterThan(0);
    expect(cols[0]).toBe(cols[1]); // aligned across "0/3" and "0/12"
  });
});

describe("LivePanel — pure geometry (deterministic, render-free)", () => {
  test("shimmerPos slides +1 per tick and wraps at width", () => {
    const seq = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9].map((t) => shimmerPos(t, 0, 8));
    expect(seq).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 0, 1]);
  });

  test("shimmerPos folds last-ts (quot 100) into the phase", () => {
    expect(shimmerPos(0, 300, 8)).toBe(3);
    expect(shimmerPos(2, 300, 8)).toBe(5);
  });

  test("barFill floor semantics + total=0 guard", () => {
    expect(barFill(0, 2, 8)).toBe(0);
    expect(barFill(1, 2, 8)).toBe(4);
    expect(barFill(2, 2, 8)).toBe(8);
    expect(barFill(1, 3, 8)).toBe(2);
    expect(barFill(5, 0, 8)).toBe(0);
  });

  test("liveBarWidthFor breakpoints (10/8/6/4 floor)", () => {
    expect(liveBarWidthFor(100)).toBe(10);
    expect(liveBarWidthFor(28)).toBe(4);
    expect(liveBarWidthFor(10)).toBe(4);
  });

  test("liveRows expands the haiku fixture: 2 singles + group-header + 2 children", () => {
    const rows = liveRows(
      liveGroups(stateFromFixture("haiku-sample.jsonl").live),
    );
    // All sessions are done (same bucket), so units order by recency
    // (most-recent last-ts first, JLINE parity via compareLiveOrder): the
    // poet2 group has the newest activity and sorts on top, then the poet1
    // single, then the planner single (oldest).
    expect(rows.map((r) => r.kind)).toEqual([
      "group-header",
      "child",
      "child",
      "single",
      "single",
    ]);
  });

  test("top-level units order by recency: newest-activity group on top", () => {
    // poet2 group (last activity) > poet1 single > planner single. The first row
    // identifies the winning group, the trailing singles are oldest-last.
    const rows = liveRows(
      liveGroups(stateFromFixture("haiku-sample.jsonl").live),
    );
    expect(rows[0]!.kind).toBe("group-header");
    expect((rows[0] as any).group.iid).toBe("poet2");
    const singles = rows.filter((r) => r.kind === "single") as any[];
    expect(singles.map((r) => r.group.iid)).toEqual(["poet1", "planner"]);
  });

  test("a top-level group resolves to ONE hue for header + every child (R3)", () => {
    // Every row belonging to the poet2 group (its header + both children) must
    // share a single hue key — the group iid — so no intra-group rainbow.
    const rows = liveRows(
      liveGroups(stateFromFixture("haiku-sample.jsonl").live),
    );
    const poet2Rows = rows.filter(
      (r) =>
        (r.kind === "group-header" || r.kind === "child") &&
        (r as any).group.iid === "poet2",
    );
    expect(poet2Rows.length).toBe(3); // header + 2 children
    const hues = new Set(poet2Rows.map((r) => groupHueKey(r)));
    expect(hues.size).toBe(1);
    expect([...hues][0]).toBe("poet2");
  });
});

describe("LivePanel — multiplex phase tree (option C)", () => {
  // The multiplex `multiplex.<mux>.<idx>` session ids reconstruct a 3-level
  // tree: phase (poets) → child (poets.<idx>) → call (muse / haiku-N / critique).
  // A secret Muse/Critique nests as a CALL under its poet's child header; a
  // single-call child (a judge) collapses to one row; parent-session singles
  // (planner / host) stay bare and out of any phase.
  function sess(session: string, first: number, model = "glm"): any {
    return {
      chunks: 0,
      chars: 0,
      "first-ts": first,
      "last-ts": first + 100,
      status: "done",
      text: "",
      model,
      session,
    };
  }
  // poet 2 = muse + 3 haiku; poet 4 = critique + 3 haiku; plus a flat planner.
  function treeCtx(): PaneContext {
    const live: any = {
      muse: { sessions: { a: sess(":multiplex.poets.2", 5, "codex/gpt-5.5") } },
      critique: { sessions: { a: sess(":multiplex.poets.4", 6, "codex/gpt-5.5") } },
      "poet-1": { sessions: { p2: sess(":multiplex.poets.2", 50), p4: sess(":multiplex.poets.4", 51) } },
      "poet-2": { sessions: { p2: sess(":multiplex.poets.2", 60), p4: sess(":multiplex.poets.4", 61) } },
      "poet-3": { sessions: { p2: sess(":multiplex.poets.2", 70), p4: sess(":multiplex.poets.4", 71) } },
      planner: { sessions: { x: sess(":session/abc-123", 0, "ollama/gemma") } },
    };
    return {
      state: { ...stateFromFixture("haiku-sample.jsonl"), live },
      theme: makeTheme("none"),
      focused: true,
      width: 100,
    };
  }

  test("muse nests as a call under its poet's child header (poets.2 / muse)", async () => {
    const rows = liveRows(liveGroups(treeCtx().state.live));
    // phase header for `poets`, a child header `poets.2`, and a `muse` call row.
    expect(rows.some((r) => r.kind === "phase-header" && r.phase.phase === "poets")).toBe(true);
    expect(rows.some((r) => r.kind === "child-header" && r.child.label === "poets.2")).toBe(true);
    expect(rows.some((r) => r.kind === "call" && r.call.iid === "muse" && r.child.label === "poets.2")).toBe(true);
    const { frame } = await renderFrame(() => <LivePanel ctx={treeCtx()} tick={0} height={24} />, {
      width: 100,
      height: 16,
    });
    expect(frame).toContain("poets.2");
    expect(frame).toContain("muse");
  });

  test("critique nests under poets.4", async () => {
    const rows = liveRows(liveGroups(treeCtx().state.live));
    expect(rows.some((r) => r.kind === "child-header" && r.child.label === "poets.4")).toBe(true);
    expect(rows.some((r) => r.kind === "call" && r.call.iid === "critique" && r.child.label === "poets.4")).toBe(true);
  });

  test("a single-call child (judge) collapses to one row", async () => {
    const live: any = {
      judge1: { sessions: { j: sess(":multiplex.judges-r1.7", 9) } },
    };
    const rows = liveRows(liveGroups(live));
    expect(rows.some((r) => r.kind === "phase-header" && r.phase.phase === "judges-r1")).toBe(true);
    expect(rows.some((r) => r.kind === "child-single" && r.child.label === "judges-r1.7")).toBe(true);
    expect(rows.some((r) => r.kind === "child-header")).toBe(false);
  });

  test("parent-session single (planner) stays a flat row, outside any phase", async () => {
    const rows = liveRows(liveGroups(treeCtx().state.live));
    expect(rows.some((r) => r.kind === "single" && r.group.iid === "planner")).toBe(true);
  });

  test("the whole `poets` multiplex group shares ONE hue (phase id) — R3", () => {
    // phase-header + every child-header / call / child-single under `poets`
    // resolves to the same hue key (the phase id), so the tree is monochrome.
    const rows = liveRows(liveGroups(treeCtx().state.live));
    const poetsRows = rows.filter(
      (r) =>
        r.kind === "phase-header" ||
        r.kind === "child-header" ||
        r.kind === "call" ||
        r.kind === "child-single",
    );
    expect(poetsRows.length).toBeGreaterThan(1);
    const hues = new Set(poetsRows.map((r) => groupHueKey(r)));
    expect(hues).toEqual(new Set(["poets"]));
  });
});
