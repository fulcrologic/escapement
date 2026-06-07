// Pin timezone BEFORE any Date-touching import (ts->hms is local-zone).
process.env.TZ = "UTC";

import { describe, expect, test } from "bun:test";
import { LivePanel } from "../../src/ui/LivePanel";
import { makeTheme } from "../../src/domain/theme";
import { liveGroups } from "../../src/domain/solid-store";
import { liveRows } from "../../src/ui/live/rows";
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
    const c = ctx("haiku-sample.jsonl", 60);
    const { frame } = await renderFrame(() => <LivePanel ctx={c} tick={0} />, {
      width: 60,
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
    const { frame } = await renderFrame(() => <LivePanel ctx={c} tick={3} />, {
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
    const { frame } = await renderFrame(() => <LivePanel ctx={c} tick={0} />, {
      width: 40,
      height: 4,
    });
    expect(frame).toContain("no live activity");
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

  test("liveRows expands the haiku fixture: group-header + 2 children + 2 singles", () => {
    const rows = liveRows(
      liveGroups(stateFromFixture("haiku-sample.jsonl").live),
    );
    expect(rows.map((r) => r.kind)).toEqual([
      "group-header",
      "child",
      "child",
      "single",
      "single",
    ]);
  });
});
