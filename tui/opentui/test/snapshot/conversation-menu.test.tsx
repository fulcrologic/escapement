// Pin timezone before any Date-touching import (parity with the other snaps).
process.env.TZ = "UTC";

import { describe, expect, test } from "bun:test";
import {
  ConversationMenu,
  type ConversationMenuHook,
} from "../../src/ui/ConversationMenu";
import { makeTheme } from "../../src/domain/theme";
import type { KeyEvent } from "../../src/input/dispatch";
import { renderFrame } from "./_helpers";

const key = (name: string, mods: Partial<KeyEvent> = {}): KeyEvent => ({
  name,
  ...mods,
});

const IID = "agent.42";

interface Fired {
  action: "transcript" | "rerun" | "break" | "cancel";
  invokeid?: string;
}

/**
 * Mount the menu, capture its hook, open it for `IID`, drive `keys`, and return
 * which action callback fired (with the invokeid it received).
 */
async function drive(keys: KeyEvent[]): Promise<Fired | null> {
  let hook: ConversationMenuHook | undefined;
  let fired: Fired | null = null;
  await renderFrame(
    () => (
      <ConversationMenu
        theme={makeTheme("none")}
        width={60}
        onTranscript={(invokeid) => (fired = { action: "transcript", invokeid })}
        onRerun={(invokeid) => (fired = { action: "rerun", invokeid })}
        onBreak={(invokeid) => (fired = { action: "break", invokeid })}
        onCancel={() => (fired = { action: "cancel" })}
        ref={(h) => (hook = h)}
      />
    ),
    { width: 64, height: 10 },
  );
  hook!.open(IID);
  for (const k of keys) hook!.handleKey(k);
  return fired;
}

describe("ConversationMenu — render snapshot", () => {
  test("open menu renders Transcript-first item list", async () => {
    let hook: ConversationMenuHook | undefined;
    const { frame } = await renderFrame(
      () => (
        <ConversationMenu
          theme={makeTheme("none")}
          width={60}
          onTranscript={() => {}}
          onRerun={() => {}}
          onBreak={() => {}}
          ref={(h) => {
            hook = h;
            h.open(IID);
          }}
        />
      ),
      { width: 64, height: 10 },
    );
    // hook.open() ran during render (in the ref), so the frame shows the menu.
    expect(hook!.isOpen()).toBe(true);
    expect(frame).toMatchSnapshot();
  });
});

describe("ConversationMenu — key → action round-trip (UI half)", () => {
  test("Enter on the first item → Transcript with the invokeid", async () => {
    const fired = await drive([key("enter")]);
    expect(fired).toEqual({ action: "transcript", invokeid: IID });
  });

  test("down + Enter → Re-run", async () => {
    const fired = await drive([key("down"), key("enter")]);
    expect(fired).toEqual({ action: "rerun", invokeid: IID });
  });

  test("down x2 + Enter → Break", async () => {
    const fired = await drive([key("down"), key("down"), key("enter")]);
    expect(fired).toEqual({ action: "break", invokeid: IID });
  });

  test("up wraps from first → Cancel (last) + Enter", async () => {
    const fired = await drive([key("up"), key("enter")]);
    expect(fired).toEqual({ action: "cancel" });
  });

  test("Esc → cancel", async () => {
    const fired = await drive([key("escape")]);
    expect(fired).toEqual({ action: "cancel" });
  });

  test("selecting an action closes the menu", async () => {
    let hook: ConversationMenuHook | undefined;
    await renderFrame(
      () => (
        <ConversationMenu
          theme={makeTheme("none")}
          width={60}
          onTranscript={() => {}}
          onRerun={() => {}}
          onBreak={() => {}}
          ref={(h) => (hook = h)}
        />
      ),
      { width: 64, height: 10 },
    );
    hook!.open(IID);
    expect(hook!.isOpen()).toBe(true);
    expect(hook!.invokeid()).toBe(IID);
    hook!.handleKey(key("enter"));
    expect(hook!.isOpen()).toBe(false);
    expect(hook!.invokeid()).toBe(null);
  });
});
