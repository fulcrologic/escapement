import { describe, expect, test } from "bun:test";
import {
  displayWidth,
  collapseWs,
  truncate,
  truncateDisplay,
  wrapDisplay,
  wideCodepoint,
} from "../../src/domain/wrap";

const SGR = "\x1b[31m"; // red
const RESET = "\x1b[0m";

describe("wideCodepoint", () => {
  test("CJK / fullwidth / emoji count as wide; ASCII + box-drawing as narrow", () => {
    expect(wideCodepoint("中".codePointAt(0)!)).toBe(true); // CJK
    expect(wideCodepoint("Ａ".codePointAt(0)!)).toBe(true); // fullwidth A
    expect(wideCodepoint("😀".codePointAt(0)!)).toBe(true); // emoji
    expect(wideCodepoint("A".codePointAt(0)!)).toBe(false);
    expect(wideCodepoint("│".codePointAt(0)!)).toBe(false); // box-drawing intentionally narrow
    expect(wideCodepoint("█".codePointAt(0)!)).toBe(false); // block element narrow
  });
});

describe("displayWidth — TRUE columns", () => {
  test("ASCII = char count", () => {
    expect(displayWidth("hello")).toBe(5);
  });
  test("SGR escapes are zero-width", () => {
    expect(displayWidth(`${SGR}hello${RESET}`)).toBe(5);
  });
  test("wide glyphs count as 2", () => {
    expect(displayWidth("中文")).toBe(4);
    expect(displayWidth("a中b")).toBe(4); // 1 + 2 + 1
  });
  test("emoji (astral) counts as 2, not its 2 UTF-16 units doubled", () => {
    expect(displayWidth("😀")).toBe(2);
    expect(displayWidth("x😀x")).toBe(4);
  });
  test("box-drawing border stays width 1 per cell", () => {
    expect(displayWidth("┌──┐")).toBe(4);
  });
});

describe("collapseWs", () => {
  test("collapses whitespace + control runs and trims", () => {
    expect(collapseWs("  a\t\n  b  ")).toBe("a b");
    expect(collapseWs("a\x00\x07b")).toBe("a b");
  });
});

describe("truncate — COUNT-based summarizer", () => {
  test("collapses ws then caps to n-1 + ellipsis", () => {
    expect(truncate("hello world", 100)).toBe("hello world");
    expect(truncate("hello world", 5)).toBe("hell…"); // 4 chars + …
  });
  test("never overflows the count budget", () => {
    const out = truncate("abcdefghij", 6);
    expect(out.length).toBe(6);
    expect(out.endsWith("…")).toBe(true);
  });
  test("uses CHAR count (not display width): wide chars count as 1 here", () => {
    // count-based: "中中中" is 3 chars, fits in n=3.
    expect(truncate("中中中", 3)).toBe("中中中");
  });
});

describe("truncateDisplay — COLUMN-based pad/clip (never overflows)", () => {
  test("pads narrower content to exactly n columns", () => {
    expect(truncateDisplay("ab", 5)).toBe("ab   ");
    expect(displayWidth(truncateDisplay("ab", 5))).toBe(5);
  });
  test("clips wider content with trailing ellipsis, exactly n columns", () => {
    const out = truncateDisplay("abcdefgh", 5);
    expect(displayWidth(out)).toBe(5);
    expect(out.endsWith("…")).toBe(true);
  });
  test("n<=0 -> empty string", () => {
    expect(truncateDisplay("abc", 0)).toBe("");
  });
  test("SGR escapes are preserved and not counted toward width", () => {
    const out = truncateDisplay(`${SGR}abc${RESET}`, 5);
    expect(out).toContain(SGR);
    expect(displayWidth(out)).toBe(5);
  });
  test("wide-glyph straddling the boundary is dropped (padded), never split", () => {
    // 中 = 2 cols. At width 3 with content "a中b" (a=1, 中=2, b=1 = 4 cols):
    // result must be exactly 3 display columns and never split the wide glyph.
    const out = truncateDisplay("a中b", 3);
    expect(displayWidth(out)).toBe(3);
  });
  test("C0 control chars (except ESC) become a space", () => {
    const out = truncateDisplay("a\tb", 3);
    expect(out).toBe("a b");
  });
  test("never exceeds n columns for any input", () => {
    for (const s of ["", "x", "中文字", `${SGR}wide中${RESET}`, "😀😀😀"]) {
      for (const n of [1, 2, 4, 8]) {
        expect(displayWidth(truncateDisplay(s, n))).toBeLessThanOrEqual(n);
      }
    }
  });
});

describe("displayWidth — grapheme-aware (#4 / #5)", () => {
  test("ZWJ family counts as ONE width-2 cluster, not its components summed", () => {
    // 👩‍👩‍👧‍👦 = woman+ZWJ+woman+ZWJ+girl+ZWJ+boy — one rendered cell-pair.
    expect(displayWidth("👩‍👩‍👧‍👦")).toBe(2);
    expect(displayWidth("a👩‍👩‍👧‍👦b")).toBe(4); // 1 + 2 + 1
  });
  test("skin-tone modifier adds no spurious columns", () => {
    // 👍🏽 = thumbs-up + medium skin tone — still one width-2 cluster.
    expect(displayWidth("👍🏽")).toBe(2);
    expect(displayWidth("x👍🏽x")).toBe(4);
  });
  test("VS-16 emoji-presentation selector keeps the glyph width 2", () => {
    // ❤️ = U+2764 (narrow on its own) + VS-16 → emoji cell (width 2).
    expect(displayWidth("❤️")).toBe(2);
  });
  test("flag (regional-indicator pair) is one width-2 cluster", () => {
    expect(displayWidth("🇯🇵")).toBe(2);
  });
  test("lone ESC does NOT eat the following char (#5 parity)", () => {
    // A bare ESC (not `\e[…`) is zero-width; the chars after it still count.
    expect(displayWidth("a\x1bb")).toBe(2);
    expect(displayWidth("\x1bhello")).toBe(5);
  });
  test("lone ESC inside truncateDisplay leaves the next char intact", () => {
    // "a\eb" → ESC is emitted verbatim (zero-width); a + b are kept.
    const out = truncateDisplay("a\x1bb", 3);
    expect(out).toContain("a");
    expect(out).toContain("b");
    expect(displayWidth(out)).toBe(3);
  });
  test("truncateDisplay measures a ZWJ family as one width-2 cluster", () => {
    const out = truncateDisplay("👩‍👩‍👧‍👦", 5);
    expect(displayWidth(out)).toBe(5); // padded to 5, family = 2 cols
    expect(out).toContain("👩‍👩‍👧‍👦"); // cluster kept whole
  });
});

describe("wrapDisplay — word wrap to columns with indent", () => {
  test("wraps on word boundaries within width", () => {
    const lines = wrapDisplay(10, "", "the quick brown fox");
    for (const l of lines) expect(displayWidth(l)).toBeLessThanOrEqual(10);
    expect(lines.join(" ").replace(/\s+/g, " ").trim()).toBe("the quick brown fox");
  });

  test("preserves the leading indent on every physical line", () => {
    const lines = wrapDisplay(12, "  ", "alpha beta gamma delta");
    for (const l of lines) expect(l.startsWith("  ")).toBe(true);
    for (const l of lines) expect(displayWidth(l)).toBeLessThanOrEqual(12);
  });

  test("hard-splits a word longer than the available width", () => {
    const lines = wrapDisplay(6, "", "supercalifragilistic");
    for (const l of lines) expect(displayWidth(l)).toBeLessThanOrEqual(6);
    expect(lines.join("")).toBe("supercalifragilistic"); // no chars lost
  });

  test("blank / whitespace-only input -> [] (never throws)", () => {
    expect(wrapDisplay(10, "", "")).toEqual([]);
    expect(wrapDisplay(10, "", "   \n  ")).toEqual([]);
  });

  test("explicit newlines split into logical lines", () => {
    const lines = wrapDisplay(20, "", "line one\nline two");
    expect(lines.some((l) => l.includes("line one"))).toBe(true);
    expect(lines.some((l) => l.includes("line two"))).toBe(true);
  });

  test("wide-char content respects display width when wrapping", () => {
    const lines = wrapDisplay(6, "", "中文 字符 测试");
    for (const l of lines) expect(displayWidth(l)).toBeLessThanOrEqual(6);
  });

  test("never returns [] for non-blank input", () => {
    expect(wrapDisplay(4, "", "x").length).toBeGreaterThan(0);
  });
});
