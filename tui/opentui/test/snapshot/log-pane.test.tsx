// Pin timezone BEFORE any Date-touching import (ts->hms is local-zone).
process.env.TZ = "UTC";

import { describe, expect, test } from "bun:test";
import { LogPane, logPaneModel } from "../../src/ui/LogPane";
import { makeTheme } from "../../src/domain/theme";
import { stateFromFixture, renderFrame } from "./_helpers";

const STATE = stateFromFixture("haiku-sample.jsonl");

describe("LogPane — render-tree text snapshots (no model)", () => {
  test("tail window (offset 0): newest entries at bottom, glyphs + tags + summaries", async () => {
    const theme = makeTheme("none"); // text-only frame
    const { frame } = await renderFrame(
      () => (
        <LogPane
          entries={STATE.scrollback}
          theme={theme}
          width={70}
          height={8}
          scrollOffset={0}
        />
      ),
      { width: 70, height: 8 },
    );
    expect(frame).toMatchSnapshot();
    // never shows deltas (store routes them to LIVE only — task 006 invariant)
    expect(frame).not.toContain("llm/delta");
  });

  test("scrolled up (offset 6): older window revealed", async () => {
    const theme = makeTheme("none");
    const { frame } = await renderFrame(
      () => (
        <LogPane
          entries={STATE.scrollback}
          theme={theme}
          width={70}
          height={8}
          scrollOffset={6}
        />
      ),
      { width: 70, height: 8 },
    );
    expect(frame).toMatchSnapshot();
  });

  test("cursor-selected row marked exactly once (pure model parity)", () => {
    const theme = makeTheme("truecolor");
    const m = logPaneModel(
      STATE.scrollback,
      theme,
      6,
      0,
      STATE.scrollback.length - 1,
    );
    expect(m.rows.filter((r) => r.selected)).toHaveLength(1);
    expect(m.rows.find((r) => r.selected)!.abs).toBe(
      STATE.scrollback.length - 1,
    );
  });
});

describe("LogPane — pure model (scroll window + indicator)", () => {
  test("offset 0 → indicator pos==total, bottom row is newest", () => {
    const theme = makeTheme("none");
    const total = STATE.scrollback.length;
    const m = logPaneModel(STATE.scrollback, theme, 8, 0);
    expect(m.scroll).toEqual({ pos: total, total });
    expect(m.rows.at(-1)!.abs).toBe(total - 1);
    expect(m.rows[0]!.abs).toBe(total - 8);
  });

  test("over-scroll clamps to the top window", () => {
    const theme = makeTheme("none");
    const total = STATE.scrollback.length;
    const m = logPaneModel(STATE.scrollback, theme, 5, 9999);
    expect(m.rows[0]!.abs).toBe(0);
    expect(m.scroll).toEqual({ pos: 5, total });
  });

  test("empty scrollback → 0/0, no rows", () => {
    const theme = makeTheme("none");
    const m = logPaneModel([], theme, 8, 0);
    expect(m.rows).toHaveLength(0);
    expect(m.scroll).toEqual({ pos: 0, total: 0 });
  });

  test("role hues + error-lane glyph color (truecolor)", () => {
    const theme = makeTheme("truecolor");
    const m = logPaneModel(STATE.scrollback, theme, 24, 0);
    for (const r of m.rows) {
      expect(r.tag.length).toBeGreaterThan(0);
      // glyphFg is only set on the error lane (port of log-entry->line gcode).
      if (r.glyphFg !== undefined) expect(r.tag).toBe("error");
    }
  });
});
