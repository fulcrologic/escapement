// Pin timezone BEFORE any Date-touching import (header/footer ts is local-zone).
process.env.TZ = "UTC";

import { describe, expect, test } from "bun:test";
import { Shell } from "../../src/ui/Shell";
import { LivePanel } from "../../src/ui/LivePanel";
import { LogPane } from "../../src/ui/LogPane";
import {
  computeLayout,
  NARROW_THRESHOLD,
  LIVE_MIN_W,
} from "../../src/ui/layout";
import { makeTheme } from "../../src/domain/theme";
import { stateFromFixture, renderFrame } from "./_helpers";

const STATE = stateFromFixture("haiku-sample.jsonl");

function shell(termWidth: number, theme = makeTheme("none")) {
  return (
    <Shell
      state={STATE}
      theme={theme}
      termWidth={termWidth}
      termHeight={20}
      chartName="escapement.runner/chart"
      sessionShort="889d14f8"
      elapsedMs={1351}
      livePane={(c) => <LivePanel ctx={c} tick={0} height={24} />}
      logPane={(c) => (
        <LogPane
          entries={c.state.scrollback}
          theme={c.theme}
          width={c.width}
          height={8}
          scrollOffset={0}
        />
      )}
    />
  );
}

describe("Shell layout — pure mode decision (computeLayout)", () => {
  test("two-pane: ~50/50 with LIVE floored at LIVE_MIN_W", () => {
    const r = computeLayout({ termWidth: 130, focus: "live", maximized: false });
    expect(r.mode).toBe("two-pane");
    expect(r.showLive && r.showLog).toBe(true);
    expect(r.liveWidth).toBeGreaterThanOrEqual(LIVE_MIN_W);
    expect(r.liveWidth + r.logWidth).toBe(130);
  });

  test("narrow: below threshold → single stacked focused pane", () => {
    const r = computeLayout({
      termWidth: NARROW_THRESHOLD - 1,
      focus: "log",
      maximized: false,
    });
    expect(r.mode).toBe("narrow");
    expect(r.showLog).toBe(true);
    expect(r.showLive).toBe(false);
  });

  test("maximized: focused pane fills the body", () => {
    const r = computeLayout({ termWidth: 130, focus: "live", maximized: true });
    expect(r.mode).toBe("maximized");
    expect(r.showLive).toBe(true);
    expect(r.showLog).toBe(false);
  });
});

describe("Shell layout — render-tree text snapshots (no model)", () => {
  test("two-pane (130 cols): LIVE + LOG side by side", async () => {
    const { frame } = await renderFrame(() => shell(130), {
      width: 130,
      height: 20,
    });
    expect(frame).toMatchSnapshot();
    // both pane titles present in a wide layout.
    expect(frame).toContain("LIVE");
    expect(frame).toContain("LOG");
  });

  test("narrow (80 cols): single stacked pane (focused = log)", async () => {
    const { frame } = await renderFrame(() => shell(80), {
      width: 80,
      height: 20,
    });
    expect(frame).toMatchSnapshot();
    expect(frame).toContain("LOG");
  });
});
