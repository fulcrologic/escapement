// Pin timezone before any Date-touching import (parity with the other snaps).
process.env.TZ = "UTC";

import { describe, expect, test } from "bun:test";
import {
  RerunForm,
  MessageEditorOverlay,
  BreakpointControls,
  modelTargetRows,
  modelTargetLabel,
  assembleOverrides,
  breakpointControlsText,
  isPausedAtTurn,
  type DebugFormHook,
  type ModelTargetRow,
} from "../../src/ui/Debugger";
import { makeTheme } from "../../src/domain/theme";
import type { DebugState } from "../../src/domain/store";
import type { ModelCatalog } from "../../src/domain/types";
import type { KeyEvent } from "../../src/input/dispatch";
import type { RerunOverrides } from "../../src/transport/wire";
import { renderFrame } from "./_helpers";

const key = (name: string, mods: Partial<KeyEvent> = {}): KeyEvent => ({
  name,
  ...mods,
});

const CATALOG: ModelCatalog = {
  aliases: [
    {
      alias: "smart",
      targets: [
        { provider: "openai", model: "gpt-4o" },
        { provider: "anthropic", model: "claude-sonnet" },
      ],
    },
    { alias: "fast", targets: [{ provider: "ollama", model: "gemma3:1b" }] },
  ],
  preferences: ["fast", "smart"],
};

/** A DebugState with N captured turns + the catalog loaded. */
function debugState(opts: {
  turns?: number;
  mode?: DebugState["mode"];
  turnIndex?: number | null;
  breakpointArmed?: boolean;
}): DebugState {
  const turns = opts.turns ?? 2;
  return {
    paused: false,
    stepBudget: 0,
    mode: opts.mode ?? "running",
    branch: null,
    turnIndex: opts.turnIndex ?? null,
    breakpointArmed: opts.breakpointArmed ?? false,
    modelCatalog: CATALOG,
    conversation: {
      invokeid: "planner",
      nodeId: "node-1",
      visit: 0,
      turns: Array.from({ length: turns }, (_, i) => ({
        turn: i,
        model: "gpt-4o",
        system: `You are turn ${i}.`,
        messages: [
          { role: "system" as const, text: `sys ${i}` },
          { role: "user" as const, text: `hello ${i}` },
        ],
      })),
    },
  };
}

/** Mount the form, capture its hook ref + the run payload, drive keys. */
async function driveForm(
  debug: DebugState,
  keys: KeyEvent[],
): Promise<{
  run: Parameters<Parameters<typeof RerunForm>[0]["onRun"]>[0] | null;
  hook: DebugFormHook | undefined;
  cancelled: boolean;
}> {
  let hook: DebugFormHook | undefined;
  let run: any = null;
  let cancelled = false;
  await renderFrame(
    () => (
      <RerunForm
        debug={debug}
        theme={makeTheme("none")}
        width={70}
        onRun={(p) => (run = p)}
        onCancel={() => (cancelled = true)}
        ref={(h) => (hook = h)}
      />
    ),
    { width: 74, height: 28 },
  );
  for (const k of keys) hook!.handleKey(k);
  return { run, hook, cancelled };
}

describe("Debugger form — pure helpers", () => {
  test("modelTargetRows lists every alias + target, prefs first", () => {
    const rows = modelTargetRows(CATALOG);
    expect(rows.map(modelTargetLabel)).toEqual([
      "fast · ollama/gemma3:1b",
      "smart · openai/gpt-4o",
      "smart · anthropic/claude-sonnet",
    ]);
    expect(rows.map((r) => r.aliasHead)).toEqual([true, true, false]);
    expect(rows.map((r) => r.prefRank)).toEqual([0, 1, 1]);
  });

  test("modelTargetRows on null catalog ⇒ []", () => {
    expect(modelTargetRows(null)).toEqual([]);
  });

  test("assembleOverrides emits ONLY diverged keys", () => {
    const turn = {
      turn: 0,
      model: "gpt-4o",
      system: "orig sys",
      messages: [{ role: "user" as const, text: "hi" }],
    };
    // nothing changed ⇒ empty overrides
    expect(
      assembleOverrides({
        turn,
        model: null,
        temperature: "",
        system: "orig sys",
        messages: turn.messages,
      }),
    ).toEqual({});
    // everything changed
    const model: ModelTargetRow = {
      alias: "fast",
      provider: "ollama",
      model: "gemma3:1b",
      aliasHead: true,
      prefRank: 0,
    };
    const o: RerunOverrides = assembleOverrides({
      turn,
      model,
      temperature: "0.7",
      system: "new sys",
      messages: [{ role: "user", text: "edited" }],
    });
    expect(o).toEqual({
      alias: "fast",
      provider: "ollama",
      model: "gemma3:1b",
      temperature: 0.7,
      system: "new sys",
      messages: [{ role: "user", text: "edited" }],
    });
  });

  test("assembleOverrides ignores non-numeric temperature", () => {
    const o = assembleOverrides({
      turn: undefined,
      model: null,
      temperature: "abc",
      system: "",
      messages: [],
    });
    expect(o.temperature).toBeUndefined();
  });

  test("breakpointControlsText reflects turnIndex + armed", () => {
    expect(breakpointControlsText(debugState({ turnIndex: 2, breakpointArmed: true }))).toContain(
      "turn 2",
    );
    expect(breakpointControlsText(debugState({ turnIndex: 2, breakpointArmed: true }))).toContain(
      "armed",
    );
    expect(isPausedAtTurn(debugState({ mode: "paused-at-turn" }))).toBe(true);
    expect(isPausedAtTurn(debugState({ mode: "running" }))).toBe(false);
  });
});

describe("Debugger form — key → overrides round-trip (UI half)", () => {
  test("pick model target + Ctrl-R builds rerun payload", async () => {
    // single turn ⇒ form starts on "model" (turn field hidden); down selects row 0.
    const { run } = await driveForm(debugState({ turns: 1 }), [
      key("down"), // -1 -> 0 (fast · ollama/gemma3:1b)
      key("r", { ctrl: true }),
    ]);
    expect(run).not.toBeNull();
    expect(run!.invokeid).toBe("planner");
    expect(run!.turn).toBe(0);
    expect(run!.overrides).toEqual({
      alias: "fast",
      provider: "ollama",
      model: "gemma3:1b",
    });
  });

  test("edit temperature builds numeric override", async () => {
    const { hook } = await driveForm(debugState({ turns: 1 }), [
      key("tab"), // model -> temperature (single turn ⇒ starts on model)
      key("0"),
      key("."),
      key("9"),
    ]);
    expect(hook!.overrides()).toEqual({ temperature: 0.9 });
  });

  test("turn selector moves resume turn + re-baselines buffers", async () => {
    const { run } = await driveForm(debugState({ turns: 3 }), [
      key("down"), // turn 0 -> 1
      key("r", { ctrl: true }),
    ]);
    expect(run!.turn).toBe(1);
    expect(run!.overrides).toEqual({}); // no edits ⇒ empty
  });

  test("edit a message via the editor + run ships edited messages prefix", async () => {
    const { run } = await driveForm(debugState({ turns: 1 }), [
      key("tab"), // model -> temperature (single turn ⇒ starts on model)
      key("tab"), // -> system
      key("tab"), // -> messages
      key("enter"), // open the full-text editor for msg 0 ("sys 0")
      key("backspace"), // "sys 0" -> "sys "
      key("x"), // -> "sys x"
      key("s", { ctrl: true }), // Ctrl-S saves the buffer back
      key("r", { ctrl: true }), // Ctrl-R runs
    ]);
    expect(run!.overrides.messages).toEqual([
      { role: "system", text: "sys x" },
      { role: "user", text: "hello 0" },
    ]);
  });

  test("editor Tab jumps across messages, accumulating edits before run", async () => {
    const { run, hook } = await driveForm(debugState({ turns: 1 }), [
      key("tab"), // model -> temperature
      key("tab"), // -> system
      key("tab"), // -> messages
      key("enter"), // open editor on msg 0 ("sys 0")
      key("a"), // "sys 0" -> "sys 0a"
      key("tab"), // save msg 0, jump to msg 1 ("hello 0")
      key("b"), // "hello 0" -> "hello 0b"
      key("r", { ctrl: true }), // save msg 1 + run
    ]);
    // The editor reported message 1 as the focus before Ctrl-R closed it.
    expect(hook!.editorOpen()).toBe(false);
    expect(run!.overrides.messages).toEqual([
      { role: "system", text: "sys 0a" },
      { role: "user", text: "hello 0b" },
    ]);
  });

  test("editor exposes the live message list + buffer for the overlay", async () => {
    const { hook } = await driveForm(debugState({ turns: 1 }), [
      key("tab"),
      key("tab"),
      key("tab"), // -> messages
      key("enter"), // open editor on msg 0
    ]);
    expect(hook!.editorOpen()).toBe(true);
    expect(hook!.editorIndex()).toBe(0);
    expect(hook!.editorBuffer()).toBe("sys 0");
    expect(hook!.editorMessages().map((m) => m.role)).toEqual(["system", "user"]);
  });

  test("editor Esc discards the edit (no message override)", async () => {
    const { hook } = await driveForm(debugState({ turns: 1 }), [
      key("tab"),
      key("tab"),
      key("tab"), // -> messages
      key("enter"), // open editor
      key("backspace"),
      key("z"),
      key("escape"), // discard
    ]);
    expect(hook!.overrides().messages).toBeUndefined();
  });

  test("Esc cancels", async () => {
    const { cancelled, run } = await driveForm(debugState({}), [key("escape")]);
    expect(cancelled).toBe(true);
    expect(run).toBeNull();
  });
});

describe("Debugger form — render snapshots", () => {
  test("with multiple turns (turn selector shown)", async () => {
    const { frame } = await renderFrame(
      () => (
        <RerunForm
          debug={debugState({ turns: 2 })}
          theme={makeTheme("none")}
          width={70}
          onRun={() => {}}
        />
      ),
      { width: 74, height: 30 },
    );
    expect(frame).toMatchSnapshot();
  });

  test("single turn (no turn selector)", async () => {
    const { frame } = await renderFrame(
      () => (
        <RerunForm
          debug={debugState({ turns: 1 })}
          theme={makeTheme("none")}
          width={70}
          onRun={() => {}}
        />
      ),
      { width: 74, height: 26 },
    );
    expect(frame).toMatchSnapshot();
  });

  test("message editor overlay — big body view with message rail", async () => {
    let hook: DebugFormHook | undefined;
    // Mount the form, open the editor on msg 0, then render the overlay against
    // the same hook (its accessors back the overlay's read-only view).
    await renderFrame(
      () => (
        <RerunForm
          debug={debugState({ turns: 1 })}
          theme={makeTheme("none")}
          width={70}
          onRun={() => {}}
          ref={(h) => (hook = h)}
        />
      ),
      { width: 74, height: 28 },
    );
    for (const k of [key("tab"), key("tab"), key("tab"), key("enter")]) hook!.handleKey(k);
    const { frame } = await renderFrame(
      () => (
        <MessageEditorOverlay
          theme={makeTheme("none")}
          hook={hook!}
          width={70}
          height={20}
        />
      ),
      { width: 74, height: 24 },
    );
    expect(frame).toMatchSnapshot();
  });

  test("breakpoint controls — paused at turn", async () => {
    const { frame } = await renderFrame(
      () => (
        <BreakpointControls
          debug={debugState({ mode: "paused-at-turn", turnIndex: 1, breakpointArmed: true })}
          theme={makeTheme("none")}
          width={70}
        />
      ),
      { width: 74, height: 3 },
    );
    expect(frame).toMatchSnapshot();
  });
});
