// Pin timezone BEFORE any Date-touching import (inspector ts is local-zone).
process.env.TZ = "UTC";

import { describe, expect, test } from "bun:test";
import {
  invocationListLines,
  chartViewLines,
  statusViewLines,
  currentEventRows,
  type InspectorProps,
} from "../../src/ui/Inspector";
import { makeTheme } from "../../src/domain/theme";
import { stateFromFixture, linesToText } from "./_helpers";

const STATE = stateFromFixture("haiku-sample.jsonl");

function props(overrides: Partial<InspectorProps> = {}): InspectorProps {
  return {
    state: STATE,
    theme: makeTheme("none"),
    width: 70,
    height: 20,
    sessionDir: null,
    open: () => true,
    setOpen: () => {},
    ...overrides,
  };
}

describe("Inspector — list-view render snapshots (no model)", () => {
  test("invocations list: glyph/status/tokens/model columns, cursor on row 0", () => {
    const text = linesToText(invocationListLines(props(), 0));
    expect(text).toMatchSnapshot();
    // poet2 errored, poet1/planner present.
    expect(text).toContain("poet2");
    expect(text).toContain("poet1");
    expect(text).toContain("planner");
    expect(text).toContain("tok");
  });

  test("chart view: active-states header + newest-first event rows", () => {
    const rows = currentEventRows(STATE.events);
    const text = linesToText(chartViewLines(props(), rows, 0));
    expect(text).toMatchSnapshot();
    expect(text).toContain("active states");
    expect(text).toContain("recent events");
  });

  test("status view: active states / mode / step-budget / dirs", () => {
    const text = linesToText(
      statusViewLines(
        props({
          sessionDir: "/tmp/sess-FIXED",
          debug: { mode: "paused", stepBudget: 3, pauseOnNextExternal: true },
        }),
      ),
    );
    expect(text).toMatchSnapshot();
    expect(text).toContain("active states:");
    expect(text).toContain("mode:");
    expect(text).toContain("step-budget:");
    expect(text).toContain("/tmp/sess-FIXED/artifacts/");
  });

  test("empty invocations → placeholder", () => {
    const empty = props({ state: { ...STATE, invocations: [] } });
    expect(linesToText(invocationListLines(empty, 0))).toContain(
      "no LLM invocations yet",
    );
  });
});
