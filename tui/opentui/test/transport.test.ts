import { describe, expect, test } from "bun:test";
import { decodeFrame, encodeFrame, WireDecodeError } from "../src/transport/wire";
import { ReplaySource } from "../src/transport/replay";
import { BaseEventSource } from "../src/transport/event-source";
import type { OutboundFrame } from "../src/transport/wire";
import {
  EqlClient,
  decodeTransit,
  encodeTransit,
  kw,
  Keyword,
} from "../src/transport/eql-client";
import type { ForwardFrame } from "../src/transport/wire";

describe("wire decode", () => {
  test("decodes an event envelope", () => {
    const line = JSON.stringify({
      kind: "event",
      seq: 2,
      ts: 1780798849141,
      event: "llm/start",
      data: { invokeid: "planner", "session-id": "session/abc" },
    });
    const f = decodeFrame(line) as ForwardFrame & { kind: "event" };
    expect(f?.kind).toBe("event");
    expect(f.event).toBe("llm/start");
    expect(f.seq).toBe(2);
    expect(f.data.invokeid).toBe("planner");
  });

  test("skips serialize-error and blank lines", () => {
    expect(decodeFrame("")).toBeNull();
    expect(
      decodeFrame(JSON.stringify({ kind: "event", event: "transcript/serialize-error", data: {} })),
    ).toBeNull();
  });

  test("decodes phase + prompt frames", () => {
    expect(decodeFrame(JSON.stringify({ kind: "phase", config: ["run"] }))?.kind).toBe("phase");
    expect(
      decodeFrame(JSON.stringify({ kind: "prompt", "prompt-id": "p#1", invokeid: "ask", type: "text", opts: {} }))
        ?.kind,
    ).toBe("prompt");
  });

  test("throws on malformed json + unknown kind", () => {
    expect(() => decodeFrame("{not json")).toThrow(WireDecodeError);
    expect(() => decodeFrame(JSON.stringify({ kind: "weird" }))).toThrow(WireDecodeError);
  });

  test("encodes outbound frames", () => {
    expect(encodeFrame({ kind: "control", op: "pause" })).toBe('{"kind":"control","op":"pause"}');
  });
});

describe("replay (instant)", () => {
  test("drains the canonical fixture deterministically", () => {
    const source = new ReplaySource({ path: "test/fixtures/haiku-sample.jsonl", timing: "instant" });
    const kinds: Record<string, number> = {};
    let total = 0;
    let errs = 0;
    source.onFrame((f) => {
      total += 1;
      kinds[f.kind] = (kinds[f.kind] ?? 0) + 1;
    });
    source.onDecodeError(() => (errs += 1));
    source.start();
    expect(errs).toBe(0);
    expect(total).toBeGreaterThan(50);
    expect(kinds["event"]).toBeGreaterThan(50);
    // fixture also exercises phase/prompt routes per task 001 notes
    expect((kinds["phase"] ?? 0) + (kinds["prompt"] ?? 0)).toBeGreaterThan(0);
  });

  test("send() is a no-op for replay", () => {
    const source = new ReplaySource({ lines: [], timing: "instant" });
    expect(source.send({ kind: "control", op: "pause" })).toBe(false);
  });
});

describe("agent→UI control channel (#2 run-finished)", () => {
  // Minimal source exposing the protected emitControl so we can assert the
  // onControl pub/sub the ws-client uses to surface `run-finished` (Task 002).
  class FakeSource extends BaseEventSource {
    start(): void {}
    stop(): void {}
    send(_frame: OutboundFrame): boolean {
      return false;
    }
    fire(op: string, frame: Record<string, unknown>): void {
      this.emitControl(op, frame);
    }
  }

  test("onControl receives run-finished with the raw frame", () => {
    const src = new FakeSource();
    const seen: Array<{ op: string; frame: Record<string, unknown> }> = [];
    const off = src.onControl((op, frame) => seen.push({ op, frame }));
    src.fire("run-finished", {
      kind: "control",
      op: "run-finished",
      "final-config": "[:run]",
    });
    expect(seen).toHaveLength(1);
    expect(seen[0]!.op).toBe("run-finished");
    expect(seen[0]!.frame["final-config"]).toBe("[:run]");
    off();
    src.fire("run-finished", { kind: "control", op: "run-finished" });
    expect(seen).toHaveLength(1); // unsubscribed
  });
});

describe("transit codec", () => {
  test("round-trips keywords, vectors, maps", () => {
    const q = [kw("chart/definition")];
    expect(encodeTransit(q)).toBe('["~:chart/definition"]');
    const decoded = decodeTransit(JSON.parse('["^ ","~:chart/definition",["^ ","~:a",1]]'));
    expect(decoded).toEqual({ "chart/definition": { a: 1 } });
  });

  test("encodes a mutation with a keyword key via Map", () => {
    const mutation = new Map<Keyword, Record<string, unknown>>([[kw("escapement.control/step"), { n: 1 }]]);
    expect(encodeTransit([mutation])).toBe('[["^ ","~:escapement.control/step",["^ ","n",1]]]');
  });

  test("EqlClient.control posts the mutation and decodes the result", async () => {
    let sentBody = "";
    const fakeFetch = (async (_url: string, init: RequestInit) => {
      sentBody = init.body as string;
      return new Response('["^ ","~:escapement.control/step",["^ ","~:paused?",true]]', { status: 200 });
    }) as unknown as typeof fetch;
    const client = new EqlClient({ baseUrl: "http://127.0.0.1:3737", fetchImpl: fakeFetch });
    const res = (await client.control("escapement.control/step", { n: 2 })) as Record<string, unknown>;
    expect(sentBody).toContain("~:escapement.control/step");
    expect((res["escapement.control/step"] as Record<string, unknown>)["paused?"]).toBe(true);
  });
});
