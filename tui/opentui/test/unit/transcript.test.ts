import { describe, expect, test } from "bun:test";
import { transcriptBlocks, type BlobReaders } from "../../src/domain/transcript";
import { liveAgg } from "../../src/domain/aggregate";
import { reduceFrames, initialDomainState } from "../../src/domain/store";
import { decodeFrame } from "../../src/transport/wire";
import type {
  EventLike,
  LiveAgg,
  ScrollbackEntry,
} from "../../src/domain/types";
import type { ForwardFrame } from "../../src/transport/wire";

const IID = "planner";
const TS = 1780798849000; // fixed -> deterministic hms

/** A scrollback entry wrapping an event keyed to a source. */
function entry(source: string, ev: EventLike): ScrollbackEntry {
  return { source, glyph: "·", summary: "", ev };
}
function ev(event: string, data: Record<string, unknown>, ts = TS): EventLike {
  return { event, ts, data };
}

/** Empty live rollup (no in-flight reply). */
const NO_LIVE: LiveAgg = {
  tokens: 0,
  tps: 0,
  n: 0,
  "n-active": 0,
  "n-done": 0,
  "last-ts": 0,
};

describe("transcriptBlocks — SENT/REPLY block model", () => {
  test("system request -> a collapsible SENT system block (from system-preview)", () => {
    const blocks = transcriptBlocks({
      invokeid: IID,
      scrollback: [
        entry(IID, ev("llm/request", { invokeid: IID, "system-preview": "You are a poet." })),
      ],
      live: NO_LIVE,
      now: () => TS,
    });
    expect(blocks).toHaveLength(1);
    expect(blocks[0]).toMatchObject({
      dir: "sent",
      label: "system",
      role: IID,
      body: "You are a poet.",
      "collapsible?": true,
    });
    expect(blocks[0]!.meta.chars).toBe("You are a poet.".length);
  });

  test("user-message -> SENT user block", () => {
    const blocks = transcriptBlocks({
      invokeid: IID,
      scrollback: [entry(IID, ev("llm/user-message", { invokeid: IID, text: "Write a haiku" }))],
      live: NO_LIVE,
      now: () => TS,
    });
    expect(blocks).toHaveLength(1);
    expect(blocks[0]).toMatchObject({ dir: "sent", label: "user", body: "Write a haiku" });
  });

  test("tool-result -> SENT tool block; is-error appends (ERROR)", () => {
    const ok = transcriptBlocks({
      invokeid: IID,
      scrollback: [
        entry(IID, ev("llm/tool-result", { invokeid: IID, tool: "read_file", "content-preview": "contents" })),
      ],
      live: NO_LIVE,
      now: () => TS,
    });
    expect(ok[0]).toMatchObject({ dir: "sent", label: "tool", sublabel: "read_file", body: "contents" });

    const err = transcriptBlocks({
      invokeid: IID,
      scrollback: [
        entry(IID, ev("llm/tool-result", { invokeid: IID, tool: "read_file", "is-error": true, "content-preview": "boom" })),
      ],
      live: NO_LIVE,
      now: () => TS,
    });
    expect(err[0]!.sublabel).toBe("read_file (ERROR)");
  });

  test("response with text + thinking + tool_use -> ordered REPLY blocks; meta on first", () => {
    const blocks = transcriptBlocks({
      invokeid: IID,
      scrollback: [
        entry(
          IID,
          ev("llm/response", {
            invokeid: IID,
            "stop-reason": "end_turn",
            "output-tps": 68.08,
            usage: { "input-tokens": 12, "output-tokens": 30 },
            content: [
              { type: "thinking", thinking: "let me think" },
              { type: "text", text: "Cherry blossoms fall" },
              { type: "tool_use", name: "search", input: { q: "haiku" } },
            ],
          }),
        ),
      ],
      live: NO_LIVE,
      now: () => TS,
    });
    expect(blocks.map((b) => [b.dir, b.label, b.sublabel])).toEqual([
      ["reply", "assistant", "thinking"],
      ["reply", "assistant", null],
      ["reply", "assistant", `search(${'{ "q": "haiku" }'})`], // collapseWs of pretty JSON
    ]);
    // meta attaches to the FIRST reply block only.
    expect(blocks[0]!.meta).toMatchObject({ stop: "end_turn", in: 12, out: 30, tps: 68.08 });
    expect(blocks[1]!.meta).toEqual({});
  });

  test("empty thinking block is dropped; meta promotes to the text block", () => {
    // Encrypted-reasoning providers (e.g. OpenAI) return a signature-only
    // thinking block with empty plaintext — nothing to render.
    const blocks = transcriptBlocks({
      invokeid: IID,
      scrollback: [
        entry(
          IID,
          ev("llm/response", {
            invokeid: IID,
            "stop-reason": "end_turn",
            "output-tps": 54.3,
            usage: { "input-tokens": 2275, "output-tokens": 2156 },
            content: [
              { type: "thinking", thinking: "" },
              { type: "text", text: "# tournament-summary.md" },
            ],
          }),
        ),
      ],
      live: NO_LIVE,
      now: () => TS,
    });
    expect(blocks.map((b) => [b.dir, b.label, b.sublabel])).toEqual([
      ["reply", "assistant", null],
    ]);
    // usage meta lands on the surviving text block.
    expect(blocks[0]!.meta).toMatchObject({ stop: "end_turn", in: 2275, out: 2156, tps: 54.3 });
  });

  test("tool_use sublabel is collapsed to one <=60-char accent line", () => {
    const blocks = transcriptBlocks({
      invokeid: IID,
      scrollback: [
        entry(
          IID,
          ev("llm/response", {
            invokeid: IID,
            content: [{ type: "tool_use", name: "f", input: { big: "x".repeat(200) } }],
          }),
        ),
      ],
      live: NO_LIVE,
      now: () => TS,
    });
    const sub = blocks[0]!.sublabel!;
    // "f(" + collapsed args (<=60, trailing …) + ")"
    expect(sub.startsWith("f(")).toBe(true);
    expect(sub.endsWith(")")).toBe(true);
    expect(sub).toContain("…");
  });

  test("response with empty content -> one bare reply header carrying meta", () => {
    const blocks = transcriptBlocks({
      invokeid: IID,
      scrollback: [
        entry(IID, ev("llm/response", { invokeid: IID, "stop-reason": "end_turn", content: [] })),
      ],
      live: NO_LIVE,
      now: () => TS,
    });
    expect(blocks).toHaveLength(1);
    expect(blocks[0]).toMatchObject({ dir: "reply", label: "assistant", sublabel: null, body: "" });
    expect(blocks[0]!.meta.stop).toBe("end_turn");
  });

  test("llm/error -> a REPLY error block carrying the message", () => {
    const blocks = transcriptBlocks({
      invokeid: IID,
      scrollback: [entry(IID, ev("llm/error", { invokeid: IID, message: "model crashed" }))],
      live: NO_LIVE,
      now: () => TS,
    });
    expect(blocks[0]).toMatchObject({ dir: "reply", label: "error", body: "model crashed" });
  });

  test("in-flight streaming reply appended when live.text is non-blank", () => {
    const live: LiveAgg = { ...NO_LIVE, tokens: 7, text: "partial token stream" };
    const blocks = transcriptBlocks({
      invokeid: IID,
      scrollback: [entry(IID, ev("llm/user-message", { invokeid: IID, text: "go" }))],
      live,
      now: () => TS,
    });
    const last = blocks[blocks.length - 1]!;
    expect(last).toMatchObject({ dir: "reply", label: "assistant", body: "partial token stream" });
    expect(last.meta["streaming?"]).toBe(true);
    expect(last.meta.out).toBe(7);
    expect(last.meta.tps).toBeNull(); // live-agg has no output-tps
  });

  test("blank/whitespace live.text adds NO in-flight block", () => {
    const live: LiveAgg = { ...NO_LIVE, text: "   " };
    const blocks = transcriptBlocks({
      invokeid: IID,
      scrollback: [entry(IID, ev("llm/user-message", { invokeid: IID, text: "go" }))],
      live,
      now: () => TS,
    });
    expect(blocks.every((b) => b.meta["streaming?"] !== true)).toBe(true);
  });

  test("only this invokeid's scrollback contributes (other sources filtered)", () => {
    const blocks = transcriptBlocks({
      invokeid: IID,
      scrollback: [
        entry("other", ev("llm/user-message", { invokeid: "other", text: "not mine" })),
        entry(IID, ev("llm/user-message", { invokeid: IID, text: "mine" })),
      ],
      live: NO_LIVE,
      now: () => TS,
    });
    expect(blocks).toHaveLength(1);
    expect(blocks[0]!.body).toBe("mine");
  });

  test("one event shared across several scrollback entries is rendered ONCE (dedup by ev)", () => {
    const shared = ev("llm/user-message", { invokeid: IID, text: "once" });
    const blocks = transcriptBlocks({
      invokeid: IID,
      scrollback: [entry(IID, shared), entry(IID, shared), entry(IID, shared)],
      live: NO_LIVE,
      now: () => TS,
    });
    expect(blocks).toHaveLength(1);
  });

  test("full SENT->REPLY structure is chronological with system first", () => {
    const blocks = transcriptBlocks({
      invokeid: IID,
      scrollback: [
        entry(IID, ev("llm/request", { invokeid: IID, "system-preview": "sys" }, TS)),
        entry(IID, ev("llm/user-message", { invokeid: IID, text: "u" }, TS + 1)),
        entry(IID, ev("llm/response", { invokeid: IID, content: [{ type: "text", text: "r" }] }, TS + 2)),
      ],
      live: NO_LIVE,
      now: () => TS,
    });
    expect(blocks.map((b) => [b.dir, b.label])).toEqual([
      ["sent", "system"],
      ["sent", "user"],
      ["reply", "assistant"],
    ]);
  });

  test("blob readers (when injected) override inline previews", () => {
    const blobs: BlobReaders = {
      system: () => "FULL SYSTEM PROMPT FROM BLOB",
      content: () => [{ type: "text", text: "FULL ASSISTANT FROM BLOB" }],
    };
    const blocks = transcriptBlocks({
      invokeid: IID,
      scrollback: [
        entry(IID, ev("llm/request", { invokeid: IID, "io/ref": "r1", "system-preview": "snippet" })),
        entry(IID, ev("llm/response", { invokeid: IID, "io/ref": "r2", content: [{ type: "text", text: "snippet" }] })),
      ],
      live: NO_LIVE,
      blobs,
      now: () => TS,
    });
    expect(blocks[0]!.body).toBe("FULL SYSTEM PROMPT FROM BLOB");
    expect(blocks[1]!.body).toBe("FULL ASSISTANT FROM BLOB");
  });
});

describe("transcriptBlocks — over the haiku fixture", () => {
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

  test("planner has a coherent SENT->REPLY transcript with a response", () => {
    const blocks = transcriptBlocks({
      invokeid: "planner",
      scrollback: state.scrollback,
      live: liveAgg(state.live["planner"]?.sessions),
      now: () => TS,
    });
    expect(blocks.length).toBeGreaterThan(0);
    expect(blocks.some((b) => b.dir === "reply")).toBe(true);
  });

  test("poet2 error went to the :error lane, not the invokeid source -> 0 blocks", () => {
    const blocks = transcriptBlocks({
      invokeid: "poet2",
      scrollback: state.scrollback,
      live: liveAgg(state.live["poet2"]?.sessions),
      now: () => TS,
    });
    expect(blocks).toHaveLength(0);
  });
});
