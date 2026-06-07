// Pin timezone BEFORE any Date-touching import (ts->hms is local-zone).
process.env.TZ = "UTC";

import { describe, expect, test } from "bun:test";
import { transcriptLines, transcriptPage } from "../../src/ui/Transcript";
import { transcriptBlocks } from "../../src/domain/transcript";
import { liveAgg } from "../../src/domain/aggregate";
import { makeTheme } from "../../src/domain/theme";
import { lineText } from "../../src/ui/styled";
import type { TranscriptBlock } from "../../src/domain/types";
import { stateFromFixture, linesToText } from "./_helpers";

const STATE = stateFromFixture("haiku-sample.jsonl");
// Fixed `now` so any streaming-meta t/s is deterministic (no in-flight here).
const NOW = 1780798850300;

function blocksFor(iid: string) {
  return transcriptBlocks({
    invokeid: iid,
    scrollback: STATE.scrollback,
    live: liveAgg(STATE.live[iid]?.sessions),
    now: () => NOW,
  });
}

describe("Transcript — themed SENT/REPLY render snapshots (no model)", () => {
  test("planner transcript: SENT/REPLY blocks, hairline, meta, body wrap", () => {
    const blocks = blocksFor("planner");
    const lines = transcriptLines(blocks, makeTheme("none"), 60);
    const text = linesToText(lines);
    expect(text).toMatchSnapshot();
    // Load-bearing structure:
    expect(text).toContain("REPLY"); // a reply lane header
    expect(text.split("\n").some((l) => /^─+$/u.test(l.trim()))).toBe(true); // hairline rule
  });

  test("transcriptPage head summary (iid · models · N replies)", () => {
    const blocks = blocksFor("planner");
    const page = transcriptPage(blocks, makeTheme("none"), 60, {
      invokeid: "planner",
      models: ["gemma3:1b"],
      nReplies: blocks.filter((b) => b.dir === "reply").length,
    });
    const text = linesToText(page);
    expect(text).toMatchSnapshot();
    expect(lineText(page[0]!)).toContain("planner");
    expect(lineText(page[0]!)).toContain("gemma3:1b");
    expect(lineText(page[0]!)).toContain("replies");
  });

  test("empty transcript (poet2 error → 0 blocks) renders the no-turns notice", () => {
    const blocks = blocksFor("poet2");
    expect(blocks).toHaveLength(0);
    const page = transcriptPage(blocks, makeTheme("none"), 60, {
      invokeid: "poet2",
      models: [],
      nReplies: 0,
    });
    expect(linesToText(page)).toContain("no turns recorded");
  });

  test("in-flight streaming block carries the ▏ cursor + streaming meta", () => {
    // Synthesize an in-flight reply block (streaming?=true).
    const blocks: TranscriptBlock[] = [
      {
        dir: "reply",
        ts: "06:20:50",
        label: "assistant",
        sublabel: null,
        role: "planner",
        body: "partial haiku line",
        meta: { "streaming?": true, out: 7, tps: 12.3 },
      },
    ];
    const text = linesToText(transcriptLines(blocks, makeTheme("none"), 60));
    expect(text).toMatchSnapshot();
    expect(text).toContain("▏"); // in-flight cursor on the last body line
    expect(text).toContain("streaming");
    expect(text).toContain("out:7");
    expect(text).toContain("12.3 t/s");
  });
});
