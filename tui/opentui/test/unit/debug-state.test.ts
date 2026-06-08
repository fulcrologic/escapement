/**
 * Unit tests for the time-travel debugger DOMAIN state (task 009): the pure
 * reducers that fold the extended `debug` frame plus the `model-catalog` and
 * `conversation` forward frames into `DomainState.debug`. These exercise
 * `reduceFrame` / `reduceFrames` with zero Solid / IO, asserting:
 *  - default fallbacks when the wire omits time-travel keys,
 *  - wire hyphenated-key → domain camelCase translation,
 *  - the preserve-across-frames contract (debug never clobbers catalog/
 *    conversation; catalog/conversation never clobber debug).
 */
import { describe, expect, test } from "bun:test";
import {
  initialDomainState,
  reduceConversation,
  reduceDebug,
  reduceFrame,
  reduceFrames,
  reduceModelCatalog,
} from "../../src/domain/store";
import type {
  ConversationEnvelope,
  DebugEnvelope,
  ModelCatalogEnvelope,
} from "../../src/transport/wire";

function debugFrame(extra: Partial<DebugEnvelope> = {}): DebugEnvelope {
  return {
    kind: "debug",
    paused: false,
    "step-budget": 0,
    ...extra,
  } as DebugEnvelope;
}

const CATALOG: ModelCatalogEnvelope = {
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
};

const CONVERSATION: ConversationEnvelope = {
  kind: "conversation",
  invokeid: "planner#1",
  "node-id": "n:plan",
  visit: 0,
  turns: [
    {
      turn: 0,
      model: "gpt-4o",
      system: "You are helpful.",
      messages: [
        { role: "user", text: "hi" },
        { role: "assistant", text: "hello" },
      ],
    },
    { turn: 1, model: "gpt-4o", system: "You are helpful.", messages: [] },
  ],
};

describe("reduceDebug — base + time-travel fields", () => {
  test("omitted time-travel keys fall back to safe defaults", () => {
    const s = reduceDebug(initialDomainState(), debugFrame());
    expect(s.debug).toEqual({
      paused: false,
      stepBudget: 0,
      config: undefined,
      mode: "running",
      branch: null,
      turnIndex: null,
      breakpointArmed: false,
      modelCatalog: null,
      conversation: null,
    });
  });

  test("translates wire hyphenated keys + branch into camelCase domain shape", () => {
    const s = reduceDebug(
      initialDomainState(),
      debugFrame({
        paused: true,
        "step-budget": 3,
        config: ["root", "plan"],
        mode: "paused-at-turn",
        "turn-index": 2,
        "breakpoint-armed": true,
        branch: {
          "session-id": "branch:abc",
          parent: "sess:root",
          "branch-point": { "node-id": "n:plan", visit: 0, turn: 2 },
        },
      }),
    );
    expect(s.debug).toEqual({
      paused: true,
      stepBudget: 3,
      config: ["root", "plan"],
      mode: "paused-at-turn",
      turnIndex: 2,
      breakpointArmed: true,
      branch: {
        sessionId: "branch:abc",
        parent: "sess:root",
        branchPoint: { nodeId: "n:plan", visit: 0, turn: 2 },
      },
      modelCatalog: null,
      conversation: null,
    });
  });

  test("turn-index:null and branch:null normalize to null", () => {
    const s = reduceDebug(
      initialDomainState(),
      debugFrame({ "turn-index": null, branch: null }),
    );
    expect(s.debug!.turnIndex).toBeNull();
    expect(s.debug!.branch).toBeNull();
  });

  test("preserves config from a prior snapshot when omitted", () => {
    const a = reduceDebug(initialDomainState(), debugFrame({ config: ["x"] }));
    const b = reduceDebug(a, debugFrame({ paused: true }));
    expect(b.debug!.config).toEqual(["x"]);
  });
});

describe("reduceModelCatalog", () => {
  test("folds aliases/preferences into debug.modelCatalog", () => {
    const s = reduceModelCatalog(initialDomainState(), CATALOG);
    expect(s.debug!.modelCatalog).toEqual({
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
    // base debug fields stay at defaults (no debug frame seen yet)
    expect(s.debug!.mode).toBe("running");
    expect(s.debug!.conversation).toBeNull();
  });

  test("absent preferences default to []", () => {
    const s = reduceModelCatalog(initialDomainState(), {
      kind: "model-catalog",
      aliases: [],
    } as ModelCatalogEnvelope);
    expect(s.debug!.modelCatalog!.preferences).toEqual([]);
  });
});

describe("reduceConversation", () => {
  test("folds the editable transcript into debug.conversation", () => {
    const s = reduceConversation(initialDomainState(), CONVERSATION);
    expect(s.debug!.conversation).toEqual({
      invokeid: "planner#1",
      nodeId: "n:plan",
      visit: 0,
      turns: [
        {
          turn: 0,
          model: "gpt-4o",
          system: "You are helpful.",
          messages: [
            { role: "user", text: "hi" },
            { role: "assistant", text: "hello" },
          ],
        },
        { turn: 1, model: "gpt-4o", system: "You are helpful.", messages: [] },
      ],
    });
  });
});

describe("cross-frame preserve contract (via reduceFrames)", () => {
  test("a debug frame does NOT clobber a previously-loaded catalog/conversation", () => {
    const s = reduceFrames([
      CATALOG,
      CONVERSATION,
      debugFrame({ mode: "branch-running", paused: true }),
    ]);
    expect(s.debug!.mode).toBe("branch-running");
    expect(s.debug!.modelCatalog!.aliases).toHaveLength(2);
    expect(s.debug!.conversation!.invokeid).toBe("planner#1");
  });

  test("catalog/conversation frames do NOT clobber prior debug state", () => {
    const s = reduceFrames([
      debugFrame({
        mode: "paused-at-turn",
        "turn-index": 1,
        "breakpoint-armed": true,
        config: ["root"],
      }),
      CATALOG,
      CONVERSATION,
    ]);
    expect(s.debug!.mode).toBe("paused-at-turn");
    expect(s.debug!.turnIndex).toBe(1);
    expect(s.debug!.breakpointArmed).toBe(true);
    expect(s.debug!.config).toEqual(["root"]);
    expect(s.debug!.modelCatalog).not.toBeNull();
    expect(s.debug!.conversation).not.toBeNull();
  });

  test("reduceFrame dispatch routes the new kinds and is pure (input not mutated)", () => {
    const init = initialDomainState();
    const frozen = JSON.stringify(init);
    reduceFrame(init, CATALOG);
    reduceFrame(init, CONVERSATION);
    reduceFrame(init, debugFrame({ mode: "branch-running" }));
    expect(JSON.stringify(init)).toBe(frozen);
  });
});
