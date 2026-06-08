import { describe, expect, test } from "bun:test";
import { render, renderCached } from "../../src/domain/markdown";
import { makeTheme } from "../../src/domain/theme";
import { lineText } from "../../src/ui/styled";

const theme = makeTheme("truecolor");
const lines = (md: string, w = 60) => render(md, theme, w).map(lineText);

describe("markdown render — block constructs", () => {
  test("heading strips the # marker and keeps the text", () => {
    expect(lines("## Theme")).toEqual(["Theme"]);
  });

  test("inline bold/italic/code delimiters are stripped from the text", () => {
    expect(lines("**Theme:** First Snow")).toEqual(["Theme: First Snow"]);
    expect(lines("a `code` b")).toEqual(["a code b"]);
  });

  test("blockquote gets the `▏` gutter", () => {
    const out = lines("> quoted");
    expect(out[0]).toContain("▏");
    expect(out[0]).toContain("quoted");
  });

  test("list item renders a bullet + text", () => {
    const out = lines("- item one");
    expect(out[0]).toContain("•");
    expect(out[0]).toContain("item one");
  });

  test("ordered list keeps its numeric marker", () => {
    expect(lines("1. first")[0]).toContain("1.");
  });

  test("horizontal rule fills the width with ─", () => {
    expect(lines("---", 10)).toEqual(["──────────"]);
  });

  test("blank lines are preserved between paragraphs", () => {
    expect(lines("a\n\nb")).toEqual(["a", "", "b"]);
  });
});

describe("markdown render — styling (spans)", () => {
  test("a heading run carries the md-h2 foreground", () => {
    const spans = render("## Theme", theme, 60)[0]!;
    expect(spans.some((s) => s.fg === theme.style("md-h2").fg)).toBe(true);
  });

  test("**bold** run carries the md-bold style", () => {
    const spans = render("x **b** y", theme, 60)[0]!;
    expect(spans.some((s) => s.fg === theme.style("md-bold").fg)).toBe(true);
  });
});

describe("markdown render — fenced code", () => {
  test("a ```markdown fence renders its body as markdown (heading unwrapped)", () => {
    expect(lines("```markdown\n## Title\n```")).toEqual(["Title"]);
  });

  test("a plain code fence renders a dim ▏ gutter, not markdown", () => {
    const out = lines("```\n## not-a-heading\n```");
    expect(out[0]).toContain("▏");
    expect(out[0]).toContain("## not-a-heading");
  });

  test("an unterminated fence (still streaming) still renders its body", () => {
    const out = lines("```\ncode so far");
    expect(out[0]).toContain("code so far");
  });
});

describe("markdown render — GFM tables", () => {
  test("a pipe row + separator renders a box-drawn table (header + data rows)", () => {
    const out = lines("| Name | Num |\n| :--- | --: |\n| Bob | 5 |\n| Alexander | 100 |", 80);
    expect(out).toEqual([
      "┌───────────┬─────┐",
      "│ Name      │ Num │",
      "├───────────┼─────┤",
      "│ Bob       │   5 │",
      "│ Alexander │ 100 │",
      "└───────────┴─────┘",
    ]);
  });

  test("columns honor :--- / --: / :-: alignment (left / right / center)", () => {
    const out = lines("| L | R | C |\n| :--- | --: | :-: |\n| a | a | a |\n| xxxx | xxxx | xxxx |", 80);
    // data row for the short value: left = no leading pad, right = leading pad, center = balanced.
    expect(out[3]).toBe("│ a    │    a │  a   │");
  });

  test("the header row is rendered bold (md-bold style)", () => {
    const spans = render("| H |\n| --- |\n| v |", theme, 80);
    const headerLine = spans[1]!; // 0 = top border, 1 = header
    const bold = theme.style("md-bold");
    expect(headerLine.some((s) => s.text.includes("H") && s.fg === bold.fg)).toBe(true);
  });

  test("a pipe line NOT followed by a separator is a normal paragraph, not a table", () => {
    expect(lines("a | b", 80)).toEqual(["a | b"]);
  });

  test("leading/trailing pipes are optional and inline markup in cells is styled-stripped", () => {
    const out = lines("h1 | h2\n--- | ---\n**b** | `c`", 80);
    expect(out).toEqual([
      "┌────┬────┐",
      "│ h1 │ h2 │",
      "├────┼────┤",
      "│ b  │ c  │",
      "└────┴────┘",
    ]);
  });

  test("ragged data rows are padded to the column count", () => {
    const out = lines("| a | b |\n| --- | --- |\n| 1 |", 80);
    // the short row's missing second cell becomes an empty, padded cell.
    expect(out[3]).toBe("│ 1 │   │");
  });
});

describe("renderCached — finalized-body memoization", () => {
  test("returns the SAME array instance for an identical [capability, width, md]", () => {
    const a = renderCached("## Cached body", theme, 60);
    const b = renderCached("## Cached body", theme, 60);
    expect(b).toBe(a); // cache hit ⇒ referential identity
  });

  test("width or capability or body changes are distinct cache entries", () => {
    const base = renderCached("para", theme, 60);
    expect(renderCached("para", theme, 40)).not.toBe(base); // width differs
    expect(renderCached("other", theme, 60)).not.toBe(base); // body differs
    expect(renderCached("para", makeTheme("none"), 60)).not.toBe(base); // capability differs
  });

  test("cached output equals an uncached render", () => {
    const body = "**bold** and a\n\n- list";
    expect(renderCached(body, theme, 50).map(lineText)).toEqual(
      render(body, theme, 50).map(lineText),
    );
  });
});

describe("markdown render — wrapping", () => {
  test("a long paragraph wraps to the width budget", () => {
    const out = render("one two three four five six", theme, 10).map(lineText);
    expect(out.length).toBeGreaterThan(1);
    for (const l of out) expect(l.length).toBeLessThanOrEqual(10);
  });
});
