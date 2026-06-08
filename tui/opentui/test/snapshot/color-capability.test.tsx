// Pin timezone BEFORE any Date-touching import.
process.env.TZ = "UTC";

import { describe, expect, test } from "bun:test";
import { testRender } from "@opentui/solid";
import type { CapturedFrame, CapturedSpan } from "@opentui/core";
import { LivePanel } from "../../src/ui/LivePanel";
import { makeTheme } from "../../src/domain/theme";
import type { PaneContext } from "../../src/ui/Shell";
import { stateFromFixture } from "./_helpers";

/** Distinct non-blank foreground colors across every captured span. */
function distinctFgColors(frame: CapturedFrame): Set<string> {
  const set = new Set<string>();
  for (const line of frame.lines) {
    for (const span of line.spans as CapturedSpan[]) {
      if (span.text.trim().length === 0) continue; // ignore blank padding
      const fg = span.fg;
      set.add(`${fg.r},${fg.g},${fg.b}`);
    }
  }
  return set;
}

async function liveFgColors(capability: "none" | "truecolor"): Promise<Set<string>> {
  const ctx: PaneContext = {
    state: stateFromFixture("live-streaming.jsonl"),
    theme: makeTheme(capability),
    focused: true,
    width: 60,
  };
  const setup = await testRender(() => <LivePanel ctx={ctx} tick={3} height={24} />, {
    width: 60,
    height: 14,
  });
  try {
    await setup.renderOnce();
    return distinctFgColors(setup.captureSpans());
  } finally {
    setup.renderer.destroy();
    await new Promise((r) => setTimeout(r, 0));
  }
}

describe("color capability — truecolor vs none parity", () => {
  test("none capability → a SINGLE foreground color (zero color, theme-none parity)", async () => {
    const colors = await liveFgColors("none");
    // With the none theme the panel emits no per-role/status hues — every
    // non-blank glyph falls back to the one default terminal foreground.
    expect(colors.size).toBe(1);
  });

  test("truecolor capability → MANY distinct hues (role + status + metric)", async () => {
    const colors = await liveFgColors("truecolor");
    // Role hues, status colors and the metric grey are all distinct.
    expect(colors.size).toBeGreaterThan(3);
  });

  test("the none theme reports colored=false; truecolor reports true", () => {
    expect(makeTheme("none").colored).toBe(false);
    expect(makeTheme("truecolor").colored).toBe(true);
  });
});
