import { describe, expect, test } from "bun:test";
import {
  colorCapability,
  decodeSgr,
  ansi256ToRgb,
  makeTheme,
  RolePalette,
  INVOKEID_PALETTE,
  CHART_COLOR,
  HUMAN_COLOR,
  ERROR_COLOR,
  DEBUG_COLOR,
  THEME_KEYS,
  type Capability,
  type ThemeKey,
} from "../../src/domain/theme";

describe("colorCapability — capability detection (port of color-capability)", () => {
  // The cond ordering: NO_COLOR > !tty > TERM nil/""/dumb > COLORTERM truecolor > 256 > 16.
  test("NO_COLOR present (any value, even empty) -> none, even on a good TTY", () => {
    expect(colorCapability({ noColor: "", term: "xterm-256color", colorterm: "truecolor" }, true)).toBe("none");
    expect(colorCapability({ noColor: "1", term: "xterm-256color" }, true)).toBe("none");
  });

  test("non-TTY -> none regardless of TERM/COLORTERM", () => {
    expect(colorCapability({ term: "xterm-256color", colorterm: "truecolor" }, false)).toBe("none");
  });

  test("TERM nil / empty / dumb -> none", () => {
    expect(colorCapability({ term: undefined }, true)).toBe("none");
    expect(colorCapability({ term: "" }, true)).toBe("none");
    expect(colorCapability({ term: "dumb" }, true)).toBe("none");
    expect(colorCapability({ term: "DUMB" }, true)).toBe("none"); // lower-cased
  });

  test("COLORTERM truecolor / 24bit -> truecolor", () => {
    expect(colorCapability({ term: "xterm", colorterm: "truecolor" }, true)).toBe("truecolor");
    expect(colorCapability({ term: "xterm", colorterm: "24bit" }, true)).toBe("truecolor");
    expect(colorCapability({ term: "xterm", colorterm: "TrueColor" }, true)).toBe("truecolor");
  });

  test("TERM *256color* or *-direct* -> 256 (when no truecolor COLORTERM)", () => {
    expect(colorCapability({ term: "xterm-256color" }, true)).toBe("256");
    expect(colorCapability({ term: "screen-256color" }, true)).toBe("256");
    expect(colorCapability({ term: "xterm-direct" }, true)).toBe("256");
  });

  test("plain color TERM -> 16", () => {
    expect(colorCapability({ term: "xterm" }, true)).toBe("16");
    expect(colorCapability({ term: "linux" }, true)).toBe("16");
  });

  test("COLORTERM truecolor beats a 256color TERM (cond ordering)", () => {
    expect(colorCapability({ term: "xterm-256color", colorterm: "truecolor" }, true)).toBe("truecolor");
  });
});

describe("ansi256ToRgb / decodeSgr — SGR -> truecolor", () => {
  test("256 grayscale ramp index 240 -> #585858 (matches the JLine border-dim)", () => {
    expect(ansi256ToRgb(240)).toEqual([88, 88, 88]);
    expect(decodeSgr("38;5;240").fg).toBe("#585858");
  });

  test("16-color base palette indices", () => {
    expect(ansi256ToRgb(0)).toEqual([0, 0, 0]);
    expect(ansi256ToRgb(15)).toEqual([255, 255, 255]);
  });

  test("6x6x6 cube index produces standard xterm rgb", () => {
    // idx 51 = cyan-ish in the cube
    const [r, g, b] = ansi256ToRgb(51);
    expect([r, g, b].every((c) => c >= 0 && c <= 255)).toBe(true);
  });

  test("bold/italic/underline flags decode", () => {
    expect(decodeSgr("1;38;5;231")).toMatchObject({ bold: true, italic: false, underline: false });
    expect(decodeSgr("3;38;5;253").italic).toBe(true);
    expect(decodeSgr("4;38;5;75").underline).toBe(true);
  });

  test("background via 48;5;N decodes to bg hex", () => {
    const s = decodeSgr("1;38;5;231;48;5;24"); // sent-tag
    expect(s.bold).toBe(true);
    expect(s.fg).not.toBeNull();
    expect(s.bg).not.toBeNull();
  });

  test("16-color fg codes 30-37 / 90-97 and bg 40-47 / 100-107", () => {
    // modern dark-bg-legible 16-color palette (VS Code / xterm-modern), not dim VGA
    expect(decodeSgr("31").fg).toBe("#cd3131"); // red
    expect(decodeSgr("91").fg).toBe("#f14c4c"); // bright red
    expect(decodeSgr("44").bg).not.toBeNull(); // blue bg
  });

  test("empty / nil code -> no style", () => {
    expect(decodeSgr("")).toEqual({ fg: null, bg: null, bold: false, italic: false, underline: false });
    expect(decodeSgr(null)).toEqual({ fg: null, bg: null, bold: false, italic: false, underline: false });
    expect(decodeSgr(undefined).fg).toBeNull();
  });
});

// Keys that intentionally carry only a background color (no foreground).
const BG_ONLY_KEYS = new Set(["selection-bg"]);
const keyColor = (t: ReturnType<typeof makeTheme>, k: ThemeKey) =>
  BG_ONLY_KEYS.has(k) ? t.bg(k) : t.themeColor(k);

describe("makeTheme — key resolution + none parity", () => {
  test("256/truecolor tiers resolve all keys to hex", () => {
    for (const cap of ["256", "truecolor"] as Capability[]) {
      const t = makeTheme(cap);
      expect(t.colored).toBe(true);
      for (const k of THEME_KEYS) {
        expect(keyColor(t, k)).toMatch(/^#[0-9a-f]{6}$/);
      }
    }
  });

  test("title is bold white in 256", () => {
    const t = makeTheme("256");
    expect(t.style("title")).toMatchObject({ bold: true, fg: "#ffffff" });
  });

  test("16 tier resolves all keys (16-color palette)", () => {
    const t = makeTheme("16");
    expect(t.colored).toBe(true);
    for (const k of THEME_KEYS) expect(keyColor(t, k)).toMatch(/^#[0-9a-f]{6}$/);
  });

  test("none tier: colored=false, every color resolver returns null/undefined", () => {
    const t = makeTheme("none");
    expect(t.colored).toBe(false);
    for (const k of THEME_KEYS) {
      expect(t.themeColor(k)).toBeNull();
      expect(t.fg(k)).toBeUndefined();
      expect(t.bg(k)).toBeNull();
    }
    expect(t.roleColor("planner")).toBeNull();
    expect(t.statusColor("streaming")).toBeNull();
  });

  test("statusColor maps each status; exit folds to idle", () => {
    const t = makeTheme("256");
    expect(t.statusColor("streaming")).toBe(t.themeColor("status/streaming"));
    expect(t.statusColor("done")).toBe(t.themeColor("status/done"));
    expect(t.statusColor("exit")).toBe(t.themeColor("status/idle"));
  });

  test("fg() returns ColorInput hex or undefined under none", () => {
    expect(makeTheme("256").fg("border-focus")).toMatch(/^#/);
    expect(makeTheme("none").fg("border-focus")).toBeUndefined();
  });
});

describe("RolePalette — per-invokeid hue round-robin", () => {
  test("allocates round-robin in invokeid-palette order", () => {
    const p = new RolePalette(true);
    expect(p.sgrFor("a")).toBe(INVOKEID_PALETTE[0]!);
    expect(p.sgrFor("b")).toBe(INVOKEID_PALETTE[1]!);
    expect(p.sgrFor("c")).toBe(INVOKEID_PALETTE[2]!);
  });

  test("assignment is STABLE per invokeid across re-lookups", () => {
    const p = new RolePalette(true);
    const a1 = p.sgrFor("alpha");
    p.sgrFor("beta");
    p.sgrFor("gamma");
    expect(p.sgrFor("alpha")).toBe(a1); // unchanged after others allocated
  });

  test("wraps at 10 (palette length) — 11th distinct invokeid reuses slot 0", () => {
    const p = new RolePalette(true);
    expect(INVOKEID_PALETTE.length).toBe(10);
    const codes = [];
    for (let i = 0; i < 11; i++) codes.push(p.sgrFor(`iid-${i}`));
    expect(codes[10]).toBe(codes[0]!); // 11th wraps to the first hue
    expect(codes[10]!).toBe(INVOKEID_PALETTE[0]!);
  });

  test("well-known roles return fixed hues (not the round-robin pool)", () => {
    const p = new RolePalette(true);
    expect(p.sgrFor("chart")).toBe(CHART_COLOR);
    expect(p.sgrFor("human")).toBe(HUMAN_COLOR);
    expect(p.sgrFor("error")).toBe(ERROR_COLOR);
    expect(p.sgrFor("debug")).toBe(DEBUG_COLOR);
    // well-known roles must NOT consume a round-robin slot:
    expect(p.sgrFor("first-real")).toBe(INVOKEID_PALETTE[0]!);
  });

  test("disabled palette (none capability) returns ''/null", () => {
    const p = new RolePalette(false);
    expect(p.sgrFor("anything")).toBe("");
    expect(p.roleColor("anything")).toBeNull();
    expect(p.sgrFor("chart")).toBe("");
  });

  test("roleColor decodes the allocated SGR to a hex fg", () => {
    const p = new RolePalette(true);
    expect(p.roleColor("x")).toMatch(/^#[0-9a-f]{6}$/);
  });
});
