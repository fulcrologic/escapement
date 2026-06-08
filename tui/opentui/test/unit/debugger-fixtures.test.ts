/**
 * Fixture-driven reducer folds for the time-travel debugger (task 013). Unlike
 * `debug-state.test.ts` (which builds wire envelopes inline), these load the
 * committed JSONL fixtures through the REAL transport decoder + store reducer
 * (`stateFromFixture`) and assert the assembled `DomainState.debug` — i.e. the
 * exact path the snapshot tests render from, so a fixture drift is caught here
 * with a precise diff rather than as an opaque snapshot mismatch.
 *
 * Determinism: fixtures use opaque session-ids and fixed integer ts; no
 * wall-clock. TZ is irrelevant here (no Date formatting), but pinned for parity.
 */
process.env.TZ = "UTC";

import { describe, expect, test } from "bun:test";
import { stateFromFixture } from "../snapshot/_helpers";

describe("debugger fixtures — paused-at-turn fold", () => {
  const state = stateFromFixture("debugger.jsonl");
  const d = state.debug!;

  test("debug slice is populated and paused at the turn gate", () => {
    expect(d).not.toBeNull();
    expect(d.mode).toBe("paused-at-turn");
    expect(d.paused).toBe(true);
    expect(d.turnIndex).toBe(1);
    expect(d.breakpointArmed).toBe(true);
    expect(d.branch).toBeNull();
  });

  test("model-catalog folded with prefs preserved across the later debug frame", () => {
    expect(d.modelCatalog).not.toBeNull();
    expect(d.modelCatalog!.preferences).toEqual(["fast", "smart"]);
    expect(d.modelCatalog!.aliases.map((a) => a.alias)).toEqual([
      "smart",
      "fast",
    ]);
    // hyphenated wire targets survive as camelCase value objects.
    expect(d.modelCatalog!.aliases[0]!.targets).toEqual([
      { provider: "openai", model: "gpt-4o" },
      { provider: "anthropic", model: "claude-sonnet" },
    ]);
  });

  test("conversation folded with camelCase keys + every turn's messages", () => {
    expect(d.conversation).not.toBeNull();
    expect(d.conversation!.invokeid).toBe("planner");
    expect(d.conversation!.nodeId).toBe("node/plan");
    expect(d.conversation!.visit).toBe(0);
    expect(d.conversation!.turns.map((t) => t.turn)).toEqual([0, 1]);
    expect(d.conversation!.turns[1]!.messages).toEqual([
      { role: "system", text: "You are a planner." },
      { role: "user", text: "Draft a plan." },
      { role: "assistant", text: "Step one." },
    ]);
  });

  test("replay/source rides on scrollback entries (captured vs live), not on debug", () => {
    const sources = state.scrollback.map(
      (e) => (e.ev.data as Record<string, unknown>)["replay/source"],
    );
    expect(sources).toEqual(["captured", "captured", "live"]);
    // It is NOT lifted into the debug slice.
    expect((d as unknown as Record<string, unknown>)["replay/source"]).toBeUndefined();
  });
});

describe("debugger fixtures — branch-running fold", () => {
  const state = stateFromFixture("debugger-branch.jsonl");
  const d = state.debug!;

  test("mode + branch coordinate fold to camelCase", () => {
    expect(d.mode).toBe("branch-running");
    expect(d.paused).toBe(false);
    expect(d.turnIndex).toBeNull();
    expect(d.branch).toEqual({
      sessionId: "session/bbbb",
      parent: "session/aaaa",
      branchPoint: { nodeId: "node/plan", visit: 0, turn: 0 },
    });
  });

  test("catalog + conversation still preserved alongside the branch debug frame", () => {
    expect(d.modelCatalog!.preferences).toEqual(["fast"]);
    expect(d.conversation!.turns).toHaveLength(1);
  });
});
