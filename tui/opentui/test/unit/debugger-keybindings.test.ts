/**
 * Keybinding round-trips for the time-travel debugger (task 013). Drives the
 * REAL central key handler (`makeKeyHandler`, keybindings.ts) wired to the REAL
 * dispatch builders (`makeControlDispatch` / `makeDebugDispatch`, dispatch.ts)
 * over a fake `EventSource.send` that records the `OutboundFrame`s. So a key →
 * agent-op regression (wrong op string, wrong payload, wrong gating) is caught
 * end to end: input event → control frame on the wire.
 *
 * Determinism: pure synchronous dispatch; no Date / network. TZ pinned for
 * parity with the snapshot tests that share fixtures.
 */
process.env.TZ = "UTC";

import { beforeEach, describe, expect, test } from "bun:test";
import { makeKeyHandler, type KeybindingDeps } from "../../src/input/keybindings";
import {
  makeControlDispatch,
  makeDebugDispatch,
  type KeyEvent,
} from "../../src/input/dispatch";
import type { OutboundFrame } from "../../src/transport/wire";
import type { ShellControls } from "../../src/ui/Shell";

const key = (name: string, mods: Partial<KeyEvent> = {}): KeyEvent => ({
  name,
  ...mods,
});

/** Records every frame the keymap sends. */
function recordingSource() {
  const sent: OutboundFrame[] = [];
  return { sent, send: (f: OutboundFrame) => (sent.push(f), true) };
}

/** A mission-control shell focused on the LIVE pane (so `o` resolves a target). */
function liveShell(): ShellControls {
  return {
    focus: () => "live",
    toggleFocus: () => {},
    maximized: () => false,
    setMaximized: () => {},
    toggleMaximize: () => {},
  } as unknown as ShellControls;
}

/** Build a key handler over a recording source + a togglable paused-at-turn gate. */
function harness(opts: { pausedAtTurn?: boolean } = {}) {
  const src = recordingSource();
  const control = makeControlDispatch(src);
  const debugDispatch = makeDebugDispatch(src);
  const openMenuCalls: string[] = [];

  const deps: KeybindingDeps = {
    shell: () => liveShell(),
    inspector: () => undefined,
    inspectorOpen: () => false,
    toggleInspector: () => {},
    openTranscript: () => {},
    openArtifactsView: () => {},
    liveCursor: {
      row: () => 0,
      setRow: () => {},
      rowCount: () => 1,
      targetInvokeid: () => "planner",
    },
    logScroll: {
      offset: () => 0,
      setOffset: () => {},
      maxOffset: () => 0,
      pageRows: () => 10,
      openSelected: () => {},
    },
    control: (op) => void control(op),
    quit: () => {},
    debug: {
      openMenu: () => openMenuCalls.push("open"),
      pausedAtTurn: () => opts.pausedAtTurn ?? false,
      turnNext: () => control("turn-next"),
      turnBack: () => control("turn-back"),
      turnContinue: () => control("continue"),
    },
  };
  return { src, debugDispatch, openMenuCalls, handle: makeKeyHandler(deps) };
}

describe("debugger keybindings — paused-at-turn breakpoint walk", () => {
  test("n → turn-next frame (only while paused)", () => {
    const { src, handle } = harness({ pausedAtTurn: true });
    handle(key("n"));
    expect(src.sent).toEqual([{ kind: "control", op: "turn-next" }]);
  });

  test("b → turn-back frame (only while paused)", () => {
    const { src, handle } = harness({ pausedAtTurn: true });
    handle(key("b"));
    expect(src.sent).toEqual([{ kind: "control", op: "turn-back" }]);
  });

  test("c while paused-at-turn → continue (turn gate)", () => {
    const { src, handle } = harness({ pausedAtTurn: true });
    handle(key("c"));
    expect(src.sent).toEqual([{ kind: "control", op: "continue" }]);
  });

  test("n/b are inert when NOT paused-at-turn", () => {
    const { src, handle } = harness({ pausedAtTurn: false });
    handle(key("n"));
    handle(key("b"));
    expect(src.sent).toEqual([]);
  });

  test("c when NOT paused-at-turn still sends the plain continue op", () => {
    const { src, handle } = harness({ pausedAtTurn: false });
    handle(key("c"));
    expect(src.sent).toEqual([{ kind: "control", op: "continue" }]);
  });
});

describe("debugger keybindings — arm + menu", () => {
  test("o opens the conversation menu (no wire frame; arm is fired by the menu)", () => {
    const { src, openMenuCalls, handle } = harness();
    handle(key("o"));
    expect(openMenuCalls).toEqual(["open"]);
    expect(src.sent).toEqual([]);
  });

  test("the menu's Break action → arm-llm-breakpoint frame (control dispatch)", () => {
    // The menu fires its action through the control dispatch (see 012 wiring);
    // assert the op the keymap's control() builder emits for it.
    const src = recordingSource();
    const control = makeControlDispatch(src);
    control("arm-llm-breakpoint");
    expect(src.sent).toEqual([{ kind: "control", op: "arm-llm-breakpoint" }]);
  });
});

describe("debugger keybindings — rerun-from outbound payload", () => {
  test("rerunFrom builds the full RerunFromFrame with overrides", () => {
    const { src, debugDispatch } = harness();
    debugDispatch.rerunFrom({
      sessionId: "session/aaaa",
      invokeid: "planner",
      nodeId: "node/plan",
      visit: 0,
      turn: 1,
      overrides: {
        alias: "fast",
        provider: "ollama",
        model: "gemma3:1b",
        temperature: 0.7,
        messages: [{ role: "user", text: "edited" }],
      },
    });
    expect(src.sent).toEqual([
      {
        kind: "control",
        op: "rerun-from",
        "session-id": "session/aaaa",
        invokeid: "planner",
        "node-id": "node/plan",
        visit: 0,
        turn: 1,
        overrides: {
          alias: "fast",
          provider: "ollama",
          model: "gemma3:1b",
          temperature: 0.7,
          messages: [{ role: "user", text: "edited" }],
        },
      },
    ]);
  });

  test("rerunFrom omits the overrides key entirely when none diverged", () => {
    const { src, debugDispatch } = harness();
    debugDispatch.rerunFrom({
      sessionId: "session/aaaa",
      invokeid: "planner",
      nodeId: "node/plan",
      visit: 0,
      turn: 0,
    });
    const frame = src.sent[0]!;
    expect(frame).toEqual({
      kind: "control",
      op: "rerun-from",
      "session-id": "session/aaaa",
      invokeid: "planner",
      "node-id": "node/plan",
      visit: 0,
      turn: 0,
    });
    expect("overrides" in frame).toBe(false);
  });

  test("requestConversation builds the request-conversation frame", () => {
    const { src, debugDispatch } = harness();
    debugDispatch.requestConversation("planner");
    expect(src.sent).toEqual([
      { kind: "control", op: "request-conversation", invokeid: "planner" },
    ]);
  });
});
