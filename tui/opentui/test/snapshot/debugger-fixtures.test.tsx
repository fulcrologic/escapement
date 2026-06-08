// Pin timezone before any Date-touching import (parity with the other snaps).
process.env.TZ = "UTC";

/**
 * Fixture-driven snapshots for the time-travel debugger surfaces (task 013).
 * These fold the committed JSONL fixtures through the REAL transport + store
 * (via `stateFromFixture`) and render the actual `DomainState.debug` slice —
 * complementing 010/011's hand-built-state snapshots by proving the wire→domain
 * fold feeds the panes the exact shape they render.
 *
 * Covered: the conversation action menu (open), the re-run form in its three
 * shapes (multi-turn w/ turn selector + full model dropdown, single-turn w/ the
 * dropdown only, and the paused breakpoint controls), driven off `debugger.jsonl`
 * / `debugger-branch.jsonl`. All fixtures use opaque session-ids + fixed integer
 * ts (no wall-clock); the form/menu carry no clock-driven cells.
 */

import { describe, expect, test } from "bun:test";
import { ConversationMenu } from "../../src/ui/ConversationMenu";
import { RerunForm, BreakpointControls } from "../../src/ui/Debugger";
import { makeTheme } from "../../src/domain/theme";
import type { DebugState } from "../../src/domain/store";
import { renderFrame, stateFromFixture } from "./_helpers";

/** The paused-at-turn debug slice folded from the main fixture. */
const PAUSED: DebugState = stateFromFixture("debugger.jsonl").debug!;
/** Same slice narrowed to a single captured turn (no turn selector branch). */
const SINGLE_TURN: DebugState = {
  ...PAUSED,
  conversation: {
    ...PAUSED.conversation!,
    turns: [PAUSED.conversation!.turns[0]!],
  },
};

describe("debugger fixtures — snapshots", () => {
  test("conversation action menu (open) renders Transcript-first", async () => {
    const { frame } = await renderFrame(
      () => (
        <ConversationMenu
          theme={makeTheme("none")}
          width={60}
          onTranscript={() => {}}
          onRerun={() => {}}
          onBreak={() => {}}
          ref={(h) => h.open("planner")}
        />
      ),
      { width: 64, height: 10 },
    );
    expect(frame).toMatchSnapshot();
  });

  test("re-run form (multi-turn): turn selector + full model dropdown", async () => {
    const { frame } = await renderFrame(
      () => (
        <RerunForm
          debug={PAUSED}
          theme={makeTheme("none")}
          width={70}
          onRun={() => {}}
        />
      ),
      { width: 74, height: 34 },
    );
    expect(frame).toMatchSnapshot();
  });

  test("re-run form (single-turn): dropdown only, no turn selector", async () => {
    const { frame } = await renderFrame(
      () => (
        <RerunForm
          debug={SINGLE_TURN}
          theme={makeTheme("none")}
          width={70}
          onRun={() => {}}
        />
      ),
      { width: 74, height: 28 },
    );
    expect(frame).toMatchSnapshot();
  });

  test("breakpoint controls: paused at turn (armed) from fixture", async () => {
    const { frame } = await renderFrame(
      () => (
        <BreakpointControls debug={PAUSED} theme={makeTheme("none")} width={70} />
      ),
      { width: 74, height: 3 },
    );
    expect(frame).toMatchSnapshot();
  });
});
