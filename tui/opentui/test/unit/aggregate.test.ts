import { describe, expect, test } from "bun:test";
import {
  foldLiveEvent,
  liveAgg,
  liveCount,
  liveTps,
  statusRank,
  shortSession,
  invokeidLive,
  liveNodeRef,
  liveNodeRefForSession,
  capTail,
  compareLiveOrder,
  LIVE_PARTIAL_TAIL_CHARS,
} from "../../src/domain/aggregate";
import { liveGroups } from "../../src/domain/solid-store";
import { reduceFrames, initialDomainState } from "../../src/domain/store";
import { decodeFrame } from "../../src/transport/wire";
import type { EventLike, LiveMap, LiveSession } from "../../src/domain/types";
import type { ForwardFrame } from "../../src/transport/wire";

/** Helper: build an EventLike envelope (keyword names arrive WITHOUT a colon). */
function ev(event: string, ts: number, data: Record<string, unknown>): EventLike {
  return { event, ts, data };
}

/** Fold a sequence of EventLikes onto an (optionally) seeded live map. */
function fold(evs: EventLike[], live: LiveMap = {}): LiveMap {
  return evs.reduce((m, e) => foldLiveEvent(m, e), live);
}

const IID = "planner";
const SID = "session/abc";

describe("statusRank", () => {
  test("in-flight sorts before finished", () => {
    expect(statusRank("streaming")).toBe(0);
    expect(statusRank("waiting")).toBe(1);
    expect(statusRank("error")).toBe(2);
    expect(statusRank("done")).toBe(3);
  });
  test("unknown/undefined ranks like done (3)", () => {
    expect(statusRank(undefined)).toBe(3);
    expect(statusRank("garbage" as any)).toBe(3);
  });
});

describe("liveCount", () => {
  test("prefers provider tokens over chunk count", () => {
    expect(liveCount({ tokens: 42, chunks: 7 })).toBe(42);
  });
  test("falls back to chunk count, then 0", () => {
    expect(liveCount({ chunks: 7 })).toBe(7);
    expect(liveCount({})).toBe(0);
  });
});

describe("liveTps", () => {
  test("prefers real-tps when present", () => {
    expect(
      liveTps({ "real-tps": 61.4, chunks: 5, "first-ts": 0, "last-ts": 5000 }),
    ).toBe(61.4);
  });

  test("real-tps of 0 is still honored (not treated as missing)", () => {
    expect(liveTps({ "real-tps": 0 })).toBe(0);
  });

  test("falls back to delta-rate count/seconds", () => {
    // 10 chunks over a 2s window -> 5 t/s.
    expect(
      liveTps({ chunks: 10, "first-ts": 1000, "last-ts": 3000 }),
    ).toBeCloseTo(5.0, 6);
  });

  test("zero-elapsed window is clamped to 1ms (no divide-by-zero)", () => {
    // (max 1 (- last first)) => 1ms => 0.001s; 1 token -> 1000 t/s.
    expect(
      liveTps({ chunks: 1, "first-ts": 5000, "last-ts": 5000 }),
    ).toBeCloseTo(1000.0, 6);
  });

  test("negative window also clamps to 1ms", () => {
    expect(
      liveTps({ chunks: 1, "first-ts": 5000, "last-ts": 4000 }),
    ).toBeCloseTo(1000.0, 6);
  });
});

describe("foldLiveEvent — per-session lifecycle", () => {
  test("llm/start -> waiting, both timestamps stamped, model captured", () => {
    const m = fold([
      ev("llm/start", 1000, { invokeid: IID, "session-id": SID, model: "gemma3:1b" }),
    ]);
    const s = m[IID]!.sessions[SID]!;
    expect(s.status).toBe("waiting");
    expect(s["first-ts"]).toBe(1000);
    expect(s["start-ts"]).toBe(1000); // preserved across the first-delta re-anchor
    expect(s["last-ts"]).toBe(1000);
    expect(s.model).toBe("gemma3:1b");
    expect(s.chunks).toBe(0);
  });

  test("first delta freezes wait-ms = TTFT and keeps start-ts", () => {
    // start t=1000, first delta t=1500 ⇒ waited 500ms for the model to stream.
    const m = fold([
      ev("llm/start", 1000, { invokeid: IID, "session-id": SID }),
      ev("llm/delta", 1500, { invokeid: IID, "session-id": SID, text: "ab" }),
      ev("llm/delta", 1800, { invokeid: IID, "session-id": SID, text: "cd" }),
    ]);
    const s = m[IID]!.sessions[SID]!;
    expect(s["start-ts"]).toBe(1000); // unchanged by the re-anchor
    expect(s["wait-ms"]).toBe(500); // 1500 - 1000, frozen at the first delta
  });

  test("wait-ms is 0 when a delta has no prior start (start-ts absent)", () => {
    const m = fold([
      ev("llm/delta", 2000, { invokeid: IID, "session-id": SID, text: "hi" }),
    ]);
    expect(m[IID]!.sessions[SID]!["wait-ms"]).toBe(0);
  });

  test("FIRST delta re-anchors first-ts (excludes time-to-first-token)", () => {
    // start at t=1000 (waiting); first delta at t=1500 must RE-STAMP first-ts.
    const m = fold([
      ev("llm/start", 1000, { invokeid: IID, "session-id": SID }),
      ev("llm/delta", 1500, { invokeid: IID, "session-id": SID, text: "ab" }),
    ]);
    const s = m[IID]!.sessions[SID]!;
    expect(s["first-ts"]).toBe(1500); // re-anchored, NOT 1000
    expect(s["last-ts"]).toBe(1500);
    expect(s.status).toBe("streaming");
    expect(s.chunks).toBe(1);
    expect(s.chars).toBe(2);
  });

  test("first-delta re-anchor makes tps measure generation, not TTFT", () => {
    // start t=0, first delta t=900 (long TTFT), second delta t=1100.
    // Without re-anchor: 2 tok / 1.1s. With re-anchor: 2 tok / 0.2s.
    const m = fold([
      ev("llm/start", 0, { invokeid: IID, "session-id": SID }),
      ev("llm/delta", 900, { invokeid: IID, "session-id": SID, text: "x" }),
      ev("llm/delta", 1100, { invokeid: IID, "session-id": SID, text: "y" }),
    ]);
    const s = m[IID]!.sessions[SID]!;
    expect(s["first-ts"]).toBe(900);
    expect(s["last-ts"]).toBe(1100);
    // 2 chunks over 200ms = 10 t/s — the generation rate, not 2/1.1.
    expect(liveTps(s)).toBeCloseTo(10.0, 6);
  });

  test("SECOND+ deltas do NOT re-anchor first-ts", () => {
    const m = fold([
      ev("llm/delta", 1500, { invokeid: IID, "session-id": SID, text: "a" }),
      ev("llm/delta", 1800, { invokeid: IID, "session-id": SID, text: "b" }),
      ev("llm/delta", 2200, { invokeid: IID, "session-id": SID, text: "c" }),
    ]);
    const s = m[IID]!.sessions[SID]!;
    expect(s["first-ts"]).toBe(1500); // first delta anchored, later ones don't move it
    expect(s["last-ts"]).toBe(2200);
    expect(s.chunks).toBe(3);
  });

  test("delta with no prior start synthesizes a session (first-ts = its ts)", () => {
    const m = fold([
      ev("llm/delta", 2000, { invokeid: IID, "session-id": SID, text: "hi" }),
    ]);
    const s = m[IID]!.sessions[SID]!;
    expect(s["first-ts"]).toBe(2000);
    expect(s.chunks).toBe(1);
    expect(s.status).toBe("streaming");
  });

  test("delta usage output-tokens overrides chunk-based token count", () => {
    const m = fold([
      ev("llm/delta", 100, { invokeid: IID, "session-id": SID, text: "a" }),
      ev("llm/delta", 200, {
        invokeid: IID,
        "session-id": SID,
        text: "b",
        usage: { "output-tokens": 99 },
      }),
    ]);
    const s = m[IID]!.sessions[SID]!;
    expect(s.tokens).toBe(99);
    expect(liveCount(s)).toBe(99);
  });

  test("thinking-delta sets kind=thinking; text-delta -> kind=text", () => {
    const think = fold([
      ev("llm/delta", 1, { invokeid: IID, "session-id": SID, text: "t", type: "thinking-delta" }),
    ]);
    expect(think[IID]!.sessions[SID]!.kind).toBe("thinking");
    const txt = fold([
      ev("llm/delta", 1, { invokeid: IID, "session-id": SID, text: "t", type: "text-delta" }),
    ]);
    expect(txt[IID]!.sessions[SID]!.kind).toBe("text");
  });

  test("llm/response -> done, clears text, sets real-tps + elapsed", () => {
    const m = fold([
      ev("llm/start", 0, { invokeid: IID, "session-id": SID }),
      ev("llm/delta", 100, { invokeid: IID, "session-id": SID, text: "hello" }),
      ev("llm/response", 500, {
        invokeid: IID,
        "session-id": SID,
        "stop-reason": "end_turn",
        "output-tps": 68.08,
        "elapsed-ms": 400,
      }),
    ]);
    const s = m[IID]!.sessions[SID]!;
    expect(s.status).toBe("done");
    expect(s.text).toBe("");
    expect(s["real-tps"]).toBe(68.08);
    expect(s["elapsed-ms"]).toBe(400);
    expect(s.reason).toBe("end_turn");
  });

  test("llm/error and llm/model-down -> error status", () => {
    const e = fold([
      ev("llm/error", 1, { invokeid: IID, "session-id": SID, message: "boom" }),
    ]);
    expect(e[IID]!.sessions[SID]!.status).toBe("error");
    expect(e[IID]!.sessions[SID]!.reason).toBe("boom");
    const d = fold([
      ev("llm/model-down", 1, { invokeid: IID, "session-id": SID, model: "gemma3:1b" }),
    ]);
    expect(d[IID]!.sessions[SID]!.status).toBe("error");
  });

  test("error event WITHOUT session-id keys under the bare invokeid (by design)", () => {
    const m = fold([
      ev("llm/error", 1, { invokeid: IID, message: "no sid" }),
    ]);
    expect(Object.keys(m[IID]!.sessions)).toEqual([IID]);
    expect(m[IID]!.sessions[IID]!.status).toBe("error");
  });

  test("worker-exit after response -> done; after error -> stays error", () => {
    const okExit = fold([
      ev("llm/response", 1, { invokeid: IID, "session-id": SID, "stop-reason": "end_turn" }),
      ev("llm/worker-exit", 2, { invokeid: IID, "session-id": SID }),
    ]);
    expect(okExit[IID]!.sessions[SID]!.status).toBe("done");

    const errExit = fold([
      ev("llm/error", 1, { invokeid: IID, "session-id": SID, message: "x" }),
      ev("llm/worker-exit", 2, { invokeid: IID, "session-id": SID }),
    ]);
    expect(errExit[IID]!.sessions[SID]!.status).toBe("error");
  });

  test("event without invokeid is a no-op (returns same map identity)", () => {
    const live: LiveMap = {};
    expect(foldLiveEvent(live, ev("llm/start", 1, {}))).toBe(live);
  });

  test("unknown event type is a no-op", () => {
    const live: LiveMap = {};
    expect(foldLiveEvent(live, ev("llm/whatever", 1, { invokeid: IID }))).toBe(live);
  });

  test("fold is immutable — returns a new map, does not mutate input", () => {
    const before: LiveMap = {};
    const after = foldLiveEvent(before, ev("llm/start", 1, { invokeid: IID, "session-id": SID }));
    expect(before).toEqual({});
    expect(after).not.toBe(before);
  });

  test("parallel multiplex children under one invokeid keep distinct sessions", () => {
    const m = fold([
      ev("llm/start", 1, { invokeid: IID, "session-id": "s1" }),
      ev("llm/start", 2, { invokeid: IID, "session-id": "s2" }),
      ev("llm/delta", 3, { invokeid: IID, "session-id": "s1", text: "a" }),
    ]);
    expect(Object.keys(m[IID]!.sessions).sort()).toEqual(["s1", "s2"]);
  });

  test("llm/request stamps node-id/visit onto the existing session (debugger §9.1)", () => {
    const m = fold([
      ev("llm/start", 1, { invokeid: IID, "session-id": SID }),
      ev("llm/request", 2, {
        invokeid: IID,
        "session-id": SID,
        model: "gpt-4o",
        "node-id": ":writer",
        visit: 3,
      }),
    ]);
    expect(m[IID]!.sessions[SID]!.nodeId).toBe(":writer");
    expect(m[IID]!.sessions[SID]!.visit).toBe(3);
  });
});

describe("liveNodeRef", () => {
  test("resolves the latest session's capture coordinates for an invokeid", () => {
    const m = fold([
      ev("llm/start", 1, { invokeid: IID, "session-id": SID }),
      ev("llm/request", 2, { invokeid: IID, "session-id": SID, "node-id": ":writer", visit: 3 }),
    ]);
    expect(liveNodeRef(m, IID)).toEqual({ nodeId: ":writer", visit: 3 });
  });

  test("returns null for an unknown invokeid or one with no node-id seen", () => {
    const m = fold([ev("llm/start", 1, { invokeid: IID, "session-id": SID })]);
    expect(liveNodeRef(m, "nope")).toBeNull();
    expect(liveNodeRef(m, IID)).toBeNull(); // started, no llm/request yet
    expect(liveNodeRef(m, null)).toBeNull();
  });
});

describe("liveNodeRefForSession", () => {
  // Parallel multiplex children share one invokeid; each is a distinct sub-chart
  // session with its OWN node-entry checkpoint (visit). A re-run must target the
  // SELECTED sibling — liveNodeRef (latest) would pick the wrong poet.
  test("resolves the SPECIFIC session's coordinates, not the most-recent sibling", () => {
    const m = fold([
      ev("llm/start", 1, { invokeid: IID, "session-id": "multiplex.poets.4" }),
      ev("llm/start", 2, { invokeid: IID, "session-id": "multiplex.poets.5" }),
      ev("llm/request", 3, { invokeid: IID, "session-id": "multiplex.poets.4", "node-id": ":haiku-1", visit: 3 }),
      ev("llm/request", 4, { invokeid: IID, "session-id": "multiplex.poets.5", "node-id": ":haiku-1", visit: 4 }),
    ]);
    // liveNodeRef picks the latest (poets.5 / visit 4)…
    expect(liveNodeRef(m, IID)).toEqual({ nodeId: ":haiku-1", visit: 4 });
    // …but the per-session form pins the selected sibling.
    expect(liveNodeRefForSession(m, IID, "multiplex.poets.4")).toEqual({ nodeId: ":haiku-1", visit: 3 });
    expect(liveNodeRefForSession(m, IID, "multiplex.poets.5")).toEqual({ nodeId: ":haiku-1", visit: 4 });
  });

  test("returns null for an unknown session or null args", () => {
    const m = fold([
      ev("llm/start", 1, { invokeid: IID, "session-id": "multiplex.poets.4" }),
      ev("llm/request", 2, { invokeid: IID, "session-id": "multiplex.poets.4", "node-id": ":haiku-1", visit: 3 }),
    ]);
    expect(liveNodeRefForSession(m, IID, "multiplex.poets.9")).toBeNull();
    expect(liveNodeRefForSession(m, IID, null)).toBeNull();
    expect(liveNodeRefForSession(m, null, "multiplex.poets.4")).toBeNull();
  });
});

describe("liveAgg", () => {
  test("rolls up tokens (sum), tps (sum of per-session rates), counts, last-ts", () => {
    const sessions: Record<string, LiveSession> = {
      s1: { chunks: 3, chars: 0, "first-ts": 0, "last-ts": 100, status: "streaming", text: "hi", session: "s1", "real-tps": 10 } as LiveSession,
      s2: { chunks: 0, chars: 0, "first-ts": 0, "last-ts": 200, status: "done", text: "", session: "s2", tokens: 50, "real-tps": 20 } as LiveSession,
      s3: { chunks: 0, chars: 0, "first-ts": 0, "last-ts": 50, status: "error", text: "", session: "s3" } as LiveSession,
    };
    const agg = liveAgg(sessions);
    expect(agg.tokens).toBe(3 + 50 + 0);
    expect(agg.tps).toBeCloseTo(30, 6); // 10 + 20 (+ 0 for the error w/ no rate? clamps)
    expect(agg.n).toBe(3);
    expect(agg["n-active"]).toBe(1); // streaming
    expect(agg["n-done"]).toBe(1);
    expect(agg["last-ts"]).toBe(200);
    expect(agg.status).toBe("streaming"); // best = lowest rank
  });

  test("status reflects the most in-flight (lowest-rank) session", () => {
    const sessions: Record<string, LiveSession> = {
      a: { status: "done", "last-ts": 9 } as LiveSession,
      b: { status: "waiting", "last-ts": 1 } as LiveSession,
    };
    expect(liveAgg(sessions).status).toBe("waiting");
  });

  test("model = first non-nil model in iteration order", () => {
    const sessions: Record<string, LiveSession> = {
      a: { status: "done", model: null } as LiveSession,
      b: { status: "done", model: "gemma3:1b" } as LiveSession,
    };
    expect(liveAgg(sessions).model).toBe("gemma3:1b");
  });

  test("empty/undefined sessions -> zeroed agg", () => {
    const agg = liveAgg(undefined);
    expect(agg.n).toBe(0);
    expect(agg.tokens).toBe(0);
    expect(agg["n-active"]).toBe(0);
    expect(agg.status).toBeUndefined();
  });
});

describe("invokeidLive", () => {
  const live = fold([
    ev("llm/delta", 1, { invokeid: "live1", "session-id": "s", text: "a" }),
    ev("llm/response", 2, { invokeid: "done1", "session-id": "s", "stop-reason": "end_turn" }),
  ]);
  test("true when a session is streaming/waiting", () => {
    expect(invokeidLive(live, "live1")).toBe(true);
  });
  test("false when all sessions finished", () => {
    expect(invokeidLive(live, "done1")).toBe(false);
  });
  test("false for nil/unknown invokeid", () => {
    expect(invokeidLive(live, null)).toBe(false);
    expect(invokeidLive(live, "nope")).toBe(false);
  });
});

describe("shortSession", () => {
  test("drops multiplex. prefix and leading colon, caps at 16", () => {
    expect(shortSession("multiplex.judges-r1.5")).toBe("judges-r1.5");
    expect(shortSession(":session-x")).toBe("session-x");
  });
  test("long names cap to 15 chars + ellipsis", () => {
    const out = shortSession("abcdefghijklmnopqrstuvwxyz");
    expect(out.length).toBe(16);
    expect(out.endsWith("…")).toBe(true);
  });
});

describe("capTail", () => {
  test("short strings pass through unchanged", () => {
    expect(capTail("hello")).toBe("hello");
  });
  test("over-cap strings keep the last LIVE_PARTIAL_TAIL_CHARS chars", () => {
    const s = "x".repeat(LIVE_PARTIAL_TAIL_CHARS + 100) + "TAIL";
    const out = capTail(s);
    expect(out.length).toBe(LIVE_PARTIAL_TAIL_CHARS);
    expect(out.endsWith("TAIL")).toBe(true);
  });
});

describe("liveGroups ordering (port of live-groups)", () => {
  test("in-flight groups sort first; within a rank most-recent last-ts first", () => {
    const live = fold([
      ev("llm/response", 100, { invokeid: "doneOld", "session-id": "s", "stop-reason": "end_turn" }),
      ev("llm/response", 300, { invokeid: "doneNew", "session-id": "s", "stop-reason": "end_turn" }),
      ev("llm/delta", 200, { invokeid: "streaming", "session-id": "s", text: "a" }),
    ]);
    const order = liveGroups(live).map((g) => g.iid);
    expect(order[0]).toBe("streaming"); // rank 0 first
    // both done (rank 3): newer last-ts first
    expect(order.slice(1)).toEqual(["doneNew", "doneOld"]);
  });
});

describe("compareLiveOrder (THE single live comparator)", () => {
  const u = (status: any, lastTs: number) => ({ status, "last-ts": lastTs });
  test("in-flight (streaming/waiting) sorts above terminal (done/error)", () => {
    expect(compareLiveOrder(u("streaming", 1), u("done", 999))).toBeLessThan(0);
    expect(compareLiveOrder(u("done", 999), u("waiting", 1))).toBeGreaterThan(0);
  });
  test("within the same bucket, most-recent last-ts first (DESC)", () => {
    expect(compareLiveOrder(u("done", 300), u("done", 100))).toBeLessThan(0);
    expect(compareLiveOrder(u("done", 100), u("done", 300))).toBeGreaterThan(0);
  });
  test("missing last-ts treated as 0", () => {
    expect(compareLiveOrder(u("done", 5), { status: "done" } as any)).toBeLessThan(0);
  });
});

describe("haiku fixture — end-to-end live rollup", () => {
  const text = require("fs").readFileSync(
    require("path").join(import.meta.dir, "../fixtures/haiku-sample.jsonl"),
    "utf8",
  ) as string;
  const frames = text
    .split("\n")
    .map((l: string) => l.trim())
    .filter((l: string) => l.length > 0)
    .map((l: string) => decodeFrame(l))
    .filter((f): f is ForwardFrame => f != null);
  const state = reduceFrames(frames, initialDomainState());

  test("deltas never reach scrollback (live-only fast path)", () => {
    expect(state.scrollback.some((e) => e.ev?.event === "llm/delta")).toBe(false);
  });

  test("planner finished done with provider real-tps", () => {
    const g = state.live["planner"]!;
    const s = Object.values(g.sessions)[0]!;
    expect(s.status).toBe("done");
    expect(s["real-tps"]).toBeCloseTo(68.08, 2);
    expect(liveTps(s)).toBeCloseTo(68.08, 2);
  });

  test("poet1 done @61.4; poet2 ends in error", () => {
    const poet1 = Object.values(state.live["poet1"]!.sessions)[0]!;
    expect(poet1.status).toBe("done");
    expect(liveTps(poet1)).toBeCloseTo(61.4, 2);
    // poet2: its error line had no session-id -> bare-iid session; model-down + exit on the sid session.
    const poet2agg = liveAgg(state.live["poet2"]!.sessions);
    expect(poet2agg.status).toBe("error");
  });

  test("poet2 group has TWO sessions (bare-iid error + sid session) — faithful to fold", () => {
    expect(Object.keys(state.live["poet2"]!.sessions).length).toBe(2);
  });
});
