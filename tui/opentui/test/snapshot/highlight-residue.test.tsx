// Regression guard for the selection-highlight RESIDUE bug: list/menu rows must
// highlight the cursor with a box-level full-width `selection-bg` BAR, and every
// OTHER row must carry the panel `overlay-bg` — NOT a span-level `inverse`
// (which left background residue on previously-hovered rows in the real
// terminal, and here would leave the selected row's bg = overlay-bg). Asserting
// the per-row BACKGROUND (color theme) catches a revert to `inverse`, which the
// char-frame snapshots (colorless `none` theme) cannot.
process.env.TZ = "UTC";

import { describe, expect, test } from "bun:test";
import {
  ConversationMenu,
  type ConversationMenuHook,
} from "../../src/ui/ConversationMenu";
import { makeTheme } from "../../src/domain/theme";
import { renderFrame } from "./_helpers";

interface Span {
  text: string;
  bg?: { buffer: Record<string, number> };
}
interface Spans {
  lines: { spans: Span[] }[];
}

const theme = makeTheme("256");

/** RGBA buffer → "#rrggbb" (matches theme.bg() hex). */
function bufHex(buf: Record<string, number> | undefined): string | null {
  if (!buf) return null;
  const h = (n: number) => n.toString(16).padStart(2, "0");
  return `#${h(buf["0"]!)}${h(buf["1"]!)}${h(buf["2"]!)}`;
}

/** Background hex of the widest span on the first line whose text includes `needle`. */
function rowBgHex(spans: Spans, needle: string): string | null {
  for (const line of spans.lines) {
    const joined = line.spans.map((s) => s.text).join("");
    if (!joined.includes(needle)) continue;
    const widest = line.spans.reduce(
      (a, b) => (b.text.length >= (a?.text.length ?? -1) ? b : a),
      undefined as Span | undefined,
    );
    return bufHex(widest?.bg?.buffer);
  }
  return null;
}

async function menuSpans(move: number): Promise<Spans> {
  let hook: ConversationMenuHook | undefined;
  const { spans } = await renderFrame(
    () => (
      <ConversationMenu
        theme={theme}
        width={50}
        onTranscript={() => {}}
        onRerun={() => {}}
        onBreak={() => {}}
        ref={(h) => {
          hook = h;
          h.open("x.1");
          for (let i = 0; i < move; i++) h.handleKey({ name: "down" });
        }}
      />
    ),
    { width: 54, height: 10 },
  );
  void hook;
  return spans as Spans;
}

describe("ConversationMenu — selection uses a bg BAR, no inverse residue", () => {
  const SELECTION = theme.bg("selection-bg");
  const OVERLAY = theme.bg("overlay-bg");

  test("cursor at row 0: Transcript bar = selection-bg, others = overlay-bg", async () => {
    const spans = await menuSpans(0);
    expect(rowBgHex(spans, "Transcript")).toBe(SELECTION);
    expect(rowBgHex(spans, "Re-run from here")).toBe(OVERLAY);
    expect(rowBgHex(spans, "Break before next LLM")).toBe(OVERLAY);
  });

  test("after moving down: highlight FOLLOWS the cursor, no residue on row 0", async () => {
    const spans = await menuSpans(1);
    // The previously-selected Transcript row must drop back to overlay-bg
    // (this is exactly what a span-`inverse` regression would leave wrong).
    expect(rowBgHex(spans, "Transcript")).toBe(OVERLAY);
    expect(rowBgHex(spans, "Re-run from here")).toBe(SELECTION);
  });
});
