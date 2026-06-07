// Pin timezone before any Date-touching import (parity with the other snaps).
process.env.TZ = "UTC";

import { describe, expect, test } from "bun:test";
import { Modals } from "../../src/ui/Modals";
import { makeTheme } from "../../src/domain/theme";
import type { PromptEnvelope } from "../../src/transport/wire";
import type { ModalHook, KeyEvent } from "../../src/input/dispatch";
import { renderFrame } from "./_helpers";

/** Build a `prompt` wire envelope (wire doc §5.1). */
function prompt(
  type: PromptEnvelope["type"],
  opts: Record<string, unknown>,
  id = `${type}#1`,
): PromptEnvelope {
  return { kind: "prompt", "prompt-id": id, invokeid: type, type, opts };
}

const TEXT = prompt("text", { prompt: "What's your name?" });
const CONFIRM = prompt("confirm", { prompt: "Proceed?", default: true });
const SELECT = prompt("select", {
  prompt: "Which haiku wins?",
  options: [
    { label: "poet1", value: "poet1" },
    { label: "poet2", value: "poet2" },
  ],
});
const MULTI = prompt("multi", {
  prompt: "Pick colors",
  options: [
    { label: "Blue", value: "blue" },
    { label: "Green", value: "green" },
    { label: "Red", value: "red" },
  ],
});

const key = (name: string, mods: Partial<KeyEvent> = {}): KeyEvent => ({
  name,
  ...mods,
});

/**
 * Mount a modal, capture its ModalHook ref + the answer/cancel the back-channel
 * would send, drive `keys`, and return the wire message that was emitted.
 */
async function drive(
  p: PromptEnvelope,
  keys: KeyEvent[],
): Promise<{ promptId: string; value?: unknown; cancelled?: boolean } | null> {
  let hook: ModalHook | undefined;
  let sent:
    | { promptId: string; value?: unknown; cancelled?: boolean }
    | null = null;
  await renderFrame(
    () => (
      <Modals
        prompt={p}
        theme={makeTheme("none")}
        width={60}
        onAnswer={(promptId, value) => (sent = { promptId, value })}
        onCancel={(promptId) => (sent = { promptId, cancelled: true })}
        ref={(h) => (hook = h)}
      />
    ),
    { width: 64, height: 10 },
  );
  // The hook is set during render; drive keys after the frame is captured.
  for (const k of keys) hook!.handleKey(k);
  return sent;
}

describe("Modals — render snapshots (text-only frame)", () => {
  for (const [name, p] of [
    ["text", TEXT],
    ["confirm", CONFIRM],
    ["select", SELECT],
    ["multi", MULTI],
  ] as const) {
    test(`${name} renders prompt + affordances`, async () => {
      const { frame } = await renderFrame(
        () => (
          <Modals
            prompt={p}
            theme={makeTheme("none")}
            width={60}
            onAnswer={() => {}}
            onCancel={() => {}}
          />
        ),
        { width: 64, height: 10 },
      );
      expect(frame).toMatchSnapshot();
    });
  }
});

describe("Modals — key → wire answer round-trip (UI half)", () => {
  test("text: type + Enter → {value:string}", async () => {
    const sent = await drive(TEXT, [
      key("a"),
      key("space"),
      key("b"),
      key("enter"),
    ]);
    expect(sent).toEqual({ promptId: "text#1", value: "a b" });
  });

  test("text: backspace deletes last char", async () => {
    const sent = await drive(TEXT, [key("a"), key("b"), key("backspace"), key("enter")]);
    expect(sent).toEqual({ promptId: "text#1", value: "a" });
  });

  test("text: Esc → cancelled", async () => {
    const sent = await drive(TEXT, [key("h"), key("escape")]);
    expect(sent).toEqual({ promptId: "text#1", cancelled: true });
  });

  test("confirm: blank Enter → default (true)", async () => {
    const sent = await drive(CONFIRM, [key("enter")]);
    expect(sent).toEqual({ promptId: "confirm#1", value: true });
  });

  test("confirm: 'n' → false", async () => {
    const sent = await drive(CONFIRM, [key("n")]);
    expect(sent).toEqual({ promptId: "confirm#1", value: false });
  });

  test("confirm: 'y' → true", async () => {
    const sent = await drive(prompt("confirm", { prompt: "x", default: false }), [
      key("y"),
    ]);
    expect(sent).toEqual({ promptId: "confirm#1", value: true });
  });

  test("select: down + Enter → second option's value", async () => {
    const sent = await drive(SELECT, [key("down"), key("enter")]);
    expect(sent).toEqual({ promptId: "select#1", value: "poet2" });
  });

  test("select: wraps with up from first → last", async () => {
    const sent = await drive(SELECT, [key("up"), key("enter")]);
    expect(sent).toEqual({ promptId: "select#1", value: "poet2" });
  });

  test("select: Esc → cancelled", async () => {
    const sent = await drive(SELECT, [key("escape")]);
    expect(sent).toEqual({ promptId: "select#1", cancelled: true });
  });

  test("multi: space toggles, Enter → sorted vector of values", async () => {
    // toggle row 0 (Blue), move to row 2 (Red), toggle, submit.
    const sent = await drive(MULTI, [
      key("space"),
      key("down"),
      key("down"),
      key("space"),
      key("enter"),
    ]);
    expect(sent).toEqual({ promptId: "multi#1", value: ["blue", "red"] });
  });

  test("multi: no selection → empty vector", async () => {
    const sent = await drive(MULTI, [key("enter")]);
    expect(sent).toEqual({ promptId: "multi#1", value: [] });
  });

  test("multi: Esc → cancelled", async () => {
    const sent = await drive(MULTI, [key("escape")]);
    expect(sent).toEqual({ promptId: "multi#1", cancelled: true });
  });
});
