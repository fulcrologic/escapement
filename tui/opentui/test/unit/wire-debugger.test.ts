/**
 * Unit tests for the time-travel debugger wire additions (wire doc §9):
 * forward-frame decode (`model-catalog`, `conversation`, extended `debug`,
 * the `replay/source` marker) and outbound control-op encode (`rerun-from`,
 * `request-conversation`, breakpoint ops). Mirrors the existing decode style:
 * unknown kinds throw, known new kinds round-trip.
 */
import { describe, expect, test } from "bun:test";
import {
  decodeFrame,
  encodeFrame,
  WireDecodeError,
  type ConversationEnvelope,
  type DebugEnvelope,
  type EventEnvelope,
  type ModelCatalogEnvelope,
  type OutboundFrame,
} from "../../src/transport/wire";
import { makeDebugDispatch } from "../../src/input/dispatch";

describe("wire — debugger forward-frame decode (§9.2/§9.3/§9.4)", () => {
  test("decodes a model-catalog frame into a typed shape", () => {
    const raw = JSON.stringify({
      kind: "model-catalog",
      aliases: [
        {
          alias: "smart",
          targets: [
            { provider: "openai", model: "gpt-4o" },
            { provider: "anthropic", model: "claude-opus-4-8" },
          ],
        },
        { alias: "fast", targets: [{ provider: "ollama", model: "gemma3:1b" }] },
      ],
      preferences: ["smart", "fast"],
    });
    const frame = decodeFrame(raw) as ModelCatalogEnvelope;
    expect(frame.kind).toBe("model-catalog");
    expect(frame.aliases).toHaveLength(2);
    expect(frame.aliases[0]!.alias).toBe("smart");
    expect(frame.aliases[0]!.targets[0]).toEqual({ provider: "openai", model: "gpt-4o" });
    expect(frame.preferences).toEqual(["smart", "fast"]);
  });

  test("decodes a conversation frame with opaque ids + ordered turns", () => {
    const raw = JSON.stringify({
      kind: "conversation",
      invokeid: "planner",
      "node-id": "route-planner",
      visit: 0,
      turns: [
        {
          turn: 0,
          model: "gpt-4o",
          system: "You are a planner.",
          messages: [
            { role: "user", text: "hi" },
            { role: "assistant", text: "hello" },
          ],
        },
      ],
    });
    const frame = decodeFrame(raw) as ConversationEnvelope;
    expect(frame.kind).toBe("conversation");
    expect(frame.invokeid).toBe("planner");
    expect(frame["node-id"]).toBe("route-planner");
    expect(frame.visit).toBe(0);
    expect(frame.turns[0]!.messages[1]).toEqual({ role: "assistant", text: "hello" });
  });

  test("extended debug frame carries the new optional debugger-state fields", () => {
    const raw = JSON.stringify({
      kind: "debug",
      paused: true,
      "step-budget": 0,
      mode: "paused-at-turn",
      "turn-index": 2,
      "breakpoint-armed": true,
      branch: {
        "session-id": "session/branch-1",
        parent: "session/root",
        "branch-point": { "node-id": "route-planner", visit: 0, turn: 2 },
      },
    });
    const frame = decodeFrame(raw) as DebugEnvelope;
    expect(frame.mode).toBe("paused-at-turn");
    expect(frame["turn-index"]).toBe(2);
    expect(frame["breakpoint-armed"]).toBe(true);
    expect(frame.branch?.["branch-point"].turn).toBe(2);
  });

  test("plain debug frame (no new keys) still decodes; new fields absent", () => {
    const raw = JSON.stringify({ kind: "debug", paused: false, "step-budget": 1 });
    const frame = decodeFrame(raw) as DebugEnvelope;
    expect(frame.mode).toBeUndefined();
    expect(frame.branch).toBeUndefined();
  });

  test("event frame carries the replay/source marker inside data", () => {
    const raw = JSON.stringify({
      kind: "event",
      seq: 412,
      ts: 1780798849355,
      event: "llm/tool-result",
      data: { tool: "read-file", "replay/source": "captured" },
    });
    const frame = decodeFrame(raw) as EventEnvelope;
    expect(frame.data["replay/source"]).toBe("captured");
  });

  test("unknown forward kind still throws (defensive, unchanged)", () => {
    expect(() => decodeFrame(JSON.stringify({ kind: "bogus-frame" }))).toThrow(
      WireDecodeError,
    );
  });
});

describe("wire — debugger outbound control-op encode (§9.1)", () => {
  test("rerun-from encodes opaque ids + override draft verbatim", () => {
    const frame: OutboundFrame = {
      kind: "control",
      op: "rerun-from",
      "session-id": "session/root",
      invokeid: "planner",
      "node-id": "route-planner",
      visit: 0,
      turn: 2,
      overrides: {
        alias: "smart",
        provider: "openai",
        model: "gpt-4o",
        temperature: 0.7,
        system: "You are …",
        messages: [{ role: "user", text: "edited" }],
      },
    };
    const round = JSON.parse(encodeFrame(frame));
    expect(round.op).toBe("rerun-from");
    expect(round["session-id"]).toBe("session/root");
    expect(round["node-id"]).toBe("route-planner");
    expect(round.overrides.temperature).toBe(0.7);
    expect(round.overrides.messages[0]).toEqual({ role: "user", text: "edited" });
  });

  test("request-conversation encodes its invokeid", () => {
    const frame: OutboundFrame = {
      kind: "control",
      op: "request-conversation",
      invokeid: "planner",
    };
    expect(JSON.parse(encodeFrame(frame))).toEqual({
      kind: "control",
      op: "request-conversation",
      invokeid: "planner",
    });
  });

  test("request-conversation carries resolved node-id/visit when provided (§9.1)", () => {
    // The sidecar resolves {nodeId,visit} from the llm/request fold so the agent
    // reads the captured turns directly (else it returns an empty editor).
    const sent: unknown[] = [];
    const dispatch = makeDebugDispatch({ send: (f) => (sent.push(f), true) });
    dispatch.requestConversation("planner", { nodeId: ":writer", visit: 2 });
    expect(sent[0]).toEqual({
      kind: "control",
      op: "request-conversation",
      invokeid: "planner",
      "node-id": ":writer",
      visit: 2,
    });
    // Omitting the ref sends just the invokeid (agent falls back to visit 0).
    dispatch.requestConversation("planner", null);
    expect(sent[1]).toEqual({
      kind: "control",
      op: "request-conversation",
      invokeid: "planner",
    });
  });

  test("payload-free debugger ops encode as bare control frames", () => {
    for (const op of [
      "arm-llm-breakpoint",
      "turn-next",
      "turn-back",
      "request-model-catalog",
    ] as const) {
      const frame: OutboundFrame = { kind: "control", op };
      expect(JSON.parse(encodeFrame(frame))).toEqual({ kind: "control", op });
    }
  });
});
