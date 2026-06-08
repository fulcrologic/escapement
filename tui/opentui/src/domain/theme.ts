/**
 * Semantic color theme for the OpenTUI sidecar — a faithful port of the JLine
 * TUI's `escapement.tui.theme` (see `src/escapement/tui/theme.clj`).
 *
 * The JLine theme expresses colors as raw SGR code strings (the digits between
 * `\e[` and `m`, e.g. `"38;5;110"` or `"1;97"`). OpenTUI renders truecolor
 * natively and wants colors as hex/RGBA + separate emphasis flags, so each
 * JLine semantic key is decoded here into a structured {@link StyleSpec}
 * (foreground hex, optional background hex, bold/italic/underline). The 256
 * ramp's palette indices are converted to their exact truecolor RGB so the
 * OpenTUI frame matches what the JLine 256-color terminal would have shown.
 *
 * Capability tiers (`truecolor`/`256`/`16`/`none`) mirror `color-capability`
 * exactly, including `NO_COLOR` / non-TTY / `dumb` → no color. Under the
 * `none` tier every resolver returns `null` (no color) — parity with
 * `theme-none`.
 *
 * The per-invokeid hue palette (`role-*`) reproduces `allocate-color`'s
 * round-robin assignment so a given agent gets the SAME hue it would have in
 * the JLine TUI. Allocation state is held in a passed-in/returned allocator
 * (no module-global mutable state) so snapshot tests stay reproducible.
 *
 * Everything here is pure and testable (task 015). The views call
 * {@link makeTheme} once (from detected capability) and then use
 * `themeColor` / `statusColor` / the allocator's `roleColor`.
 */

import { RGBA, type ColorInput } from "@opentui/core";

// ---------------------------------------------------------------------------
// Capability detection — port of `color-capability` (theme.clj ~71)
// ---------------------------------------------------------------------------

export type Capability = "truecolor" | "256" | "16" | "none";

export interface CapabilityEnv {
  /** `NO_COLOR` — set to ANY value (even "") ⇒ no color (no-color.org). */
  noColor?: string | undefined;
  /** `TERM`. */
  term?: string | undefined;
  /** `COLORTERM`. */
  colorterm?: string | undefined;
}

/**
 * Pure capability detector. Mirrors `color-capability` in theme.clj:
 *
 *  - `NO_COLOR` present (any value) ⇒ `none`
 *  - not a TTY ⇒ `none`
 *  - `TERM` nil / "" / "dumb" ⇒ `none`
 *  - `COLORTERM` = truecolor / 24bit ⇒ `truecolor`
 *  - `TERM` matches `*256color*` or `*-direct*` ⇒ `256`
 *  - otherwise ⇒ `16`
 *
 * `noColor` is treated as "present" when the key is set, regardless of value —
 * matching Clojure's `(some? no-color)` on `(System/getenv "NO_COLOR")`. We
 * model "unset" as `undefined`; an empty string counts as present.
 */
export function colorCapability(env: CapabilityEnv, tty: boolean): Capability {
  const term = env.term != null ? env.term.toLowerCase() : undefined;
  const ct = env.colorterm != null ? env.colorterm.toLowerCase() : undefined;

  if (env.noColor !== undefined) return "none";
  if (!tty) return "none";
  if (term == null || term === "" || term === "dumb") return "none";
  if (ct != null && (ct === "truecolor" || ct === "24bit")) return "truecolor";
  if (term.includes("256color") || term.includes("-direct")) return "256";
  return "16";
}

/**
 * Detect capability from the real Bun process environment + stdout TTY-ness.
 * The sidecar owns the TTY, so this runs in the Bun process. Kept thin so the
 * pure {@link colorCapability} stays the unit-testable core.
 */
export function detectCapability(): Capability {
  const env = (globalThis as any).process?.env ?? {};
  const tty = Boolean((globalThis as any).process?.stdout?.isTTY);
  return colorCapability(
    { noColor: env.NO_COLOR, term: env.TERM, colorterm: env.COLORTERM },
    tty,
  );
}

// ---------------------------------------------------------------------------
// SGR / 256-index → truecolor conversion
// ---------------------------------------------------------------------------

/** A resolved style: foreground (+ optional bg) hex strings + emphasis flags. */
export interface StyleSpec {
  /** Foreground as `#rrggbb`, or `null` for "no color" (terminal default). */
  fg: string | null;
  /** Background as `#rrggbb`, or `null` for none. */
  bg: string | null;
  bold: boolean;
  italic: boolean;
  underline: boolean;
}

const NO_STYLE: StyleSpec = {
  fg: null,
  bg: null,
  bold: false,
  italic: false,
  underline: false,
};

/**
 * 16-color palette (codes 0–15) as RGB triplets.
 *
 * NB: this is deliberately NOT the dim standard-VGA palette (where blue `4`
 * is `#000080`). The JLine TUI emits these as bare SGR codes (`34`, `32`, …)
 * and the *terminal* renders them with its own — typically bright, dark-bg-
 * legible — palette. OpenTUI renders truecolor, so we must bake in an
 * equivalently legible palette or the per-invokeid role hues (which use these
 * base codes) wash out to near-invisible navy/maroon on a dark background.
 * Values are the widely-used modern terminal scheme (VS Code / xterm-modern),
 * chosen for good contrast on dark while preserving hue identity.
 */
const ANSI16: ReadonlyArray<readonly [number, number, number]> = [
  [0, 0, 0], // 0  black
  [205, 49, 49], // 1  red
  [13, 188, 121], // 2  green
  [229, 229, 16], // 3  yellow
  [36, 114, 200], // 4  blue
  [188, 63, 188], // 5  magenta
  [17, 168, 205], // 6  cyan
  [229, 229, 229], // 7  white
  [102, 102, 102], // 8  bright black
  [241, 76, 76], // 9  bright red
  [35, 209, 139], // 10 bright green
  [245, 245, 67], // 11 bright yellow
  [59, 142, 234], // 12 bright blue
  [214, 112, 214], // 13 bright magenta
  [41, 184, 219], // 14 bright cyan
  [255, 255, 255], // 15 bright white
];

/** Convert a 0–255 xterm palette index to an RGB triplet (standard ramp). */
export function ansi256ToRgb(index: number): [number, number, number] {
  if (index < 16) {
    const c = ANSI16[index]!;
    return [c[0], c[1], c[2]];
  }
  if (index >= 232) {
    const v = 8 + (index - 232) * 10;
    return [v, v, v];
  }
  let i = index - 16;
  const r = Math.floor(i / 36);
  const g = Math.floor((i % 36) / 6);
  const b = i % 6;
  const ch = (x: number) => (x === 0 ? 0 : 55 + x * 40);
  return [ch(r), ch(g), ch(b)];
}

function toHex([r, g, b]: [number, number, number]): string {
  const h = (n: number) => n.toString(16).padStart(2, "0");
  return `#${h(r)}${h(g)}${h(b)}`;
}

/**
 * SGR foreground codes 30–37 / 90–97 mapped to a 16-color palette index, so we
 * can render the 16-color JLine theme in truecolor too. (Backgrounds 40–47 /
 * 100–107 map the same way.)
 */
function sgrFgToIndex(code: number): number | null {
  if (code >= 30 && code <= 37) return code - 30;
  if (code >= 90 && code <= 97) return code - 90 + 8;
  return null;
}
function sgrBgToIndex(code: number): number | null {
  if (code >= 40 && code <= 47) return code - 40;
  if (code >= 100 && code <= 107) return code - 100 + 8;
  return null;
}

/**
 * Decode a JLine SGR code string (the digits between `\e[` and `m`) into a
 * {@link StyleSpec} with truecolor fg/bg. Handles the subset the theme uses:
 *
 *  - `1` bold, `3` italic, `4` underline
 *  - `30..37` / `90..97` foreground (16-color)
 *  - `40..47` / `100..107` background (16-color)
 *  - `38;5;N` foreground (256-palette, converted to truecolor)
 *  - `48;5;N` background (256-palette, converted to truecolor)
 *
 * An empty / nil code string ⇒ {@link NO_STYLE}.
 */
export function decodeSgr(code: string | null | undefined): StyleSpec {
  if (code == null || code === "") return { ...NO_STYLE };
  const parts = code.split(";").map((p) => parseInt(p, 10));
  const out: StyleSpec = { ...NO_STYLE };
  for (let i = 0; i < parts.length; i++) {
    const n = parts[i]!;
    if (n === 1) out.bold = true;
    else if (n === 3) out.italic = true;
    else if (n === 4) out.underline = true;
    else if (n === 38 && parts[i + 1] === 5) {
      out.fg = toHex(ansi256ToRgb(parts[i + 2]!));
      i += 2;
    } else if (n === 48 && parts[i + 1] === 5) {
      out.bg = toHex(ansi256ToRgb(parts[i + 2]!));
      i += 2;
    } else {
      const fgIdx = sgrFgToIndex(n);
      if (fgIdx != null) {
        out.fg = toHex(ansi256ToRgb(fgIdx));
        continue;
      }
      const bgIdx = sgrBgToIndex(n);
      if (bgIdx != null) out.bg = toHex(ansi256ToRgb(bgIdx));
    }
  }
  return out;
}

// ---------------------------------------------------------------------------
// Semantic theme maps — ported 1:1 from `theme-256` / `theme-16` (theme.clj)
// ---------------------------------------------------------------------------

/** Every semantic theme key (the union of `theme-256`'s keys). */
export type ThemeKey =
  | "border-dim"
  | "border-focus"
  | "selection-bg"
  | "overlay-bg"
  | "title"
  | "chart-name"
  | "session-id"
  | "timestamp"
  | "metric"
  | "phase-current"
  | "phase-done"
  | "phase-upcoming"
  | "status/streaming"
  | "status/done"
  | "status/waiting"
  | "status/error"
  | "status/idle"
  | "bar-filled"
  | "bar-empty"
  | "sent-tag"
  | "reply-tag"
  | "md-h1"
  | "md-h2"
  | "md-h3"
  | "md-bold"
  | "md-italic"
  | "md-code"
  | "md-quote"
  | "md-rule"
  | "md-bullet"
  | "md-link"
  | "code-fence"
  | "code-plain"
  | "code-comment"
  | "code-string"
  | "code-number"
  | "code-keyword";

type SgrMap = Record<ThemeKey, string>;

/** 256-color (and truecolor) SGR codes — verbatim from `theme-256`. */
const THEME_256: SgrMap = {
  "border-dim": "38;5;240",
  "border-focus": "38;5;111",
  // Dark selection bar behind the LIVE drill-in cursor row (reverse-video
  // analogue). Must stay dark so the row's bright/colored fgs keep contrast —
  // the light `border-focus` accent does not.
  "selection-bg": "48;5;24",
  // Opaque fill for modal overlays (conversation menu / re-run form / human
  // prompt) so they fully occlude the panes behind them — the panes render a
  // fixed row count and overflow past their shrunk boxes when an overlay opens.
  "overlay-bg": "48;5;234",
  title: "1;38;5;231",
  "chart-name": "1;38;5;231",
  "session-id": "38;5;244",
  timestamp: "38;5;244",
  metric: "38;5;150",
  "phase-current": "1;38;5;117",
  "phase-done": "38;5;108",
  "phase-upcoming": "38;5;240",
  "status/streaming": "38;5;51",
  "status/done": "38;5;71",
  "status/waiting": "38;5;179",
  "status/error": "38;5;167",
  "status/idle": "38;5;240",
  "bar-filled": "38;5;71",
  "bar-empty": "38;5;238",
  "sent-tag": "1;38;5;231;48;5;24",
  "reply-tag": "1;38;5;231;48;5;65",
  "md-h1": "1;38;5;39",
  "md-h2": "1;38;5;75",
  "md-h3": "1;38;5;111",
  "md-bold": "1;38;5;231",
  "md-italic": "3;38;5;253",
  "md-code": "38;5;215;48;5;236",
  "md-quote": "3;38;5;108",
  "md-rule": "38;5;240",
  "md-bullet": "38;5;39",
  "md-link": "4;38;5;75",
  "code-fence": "38;5;240",
  "code-plain": "38;5;252",
  "code-comment": "38;5;245",
  "code-string": "38;5;150",
  "code-number": "38;5;215",
  "code-keyword": "38;5;176",
};

/** 16-color fallback SGR codes — verbatim from `theme-16`. */
const THEME_16: SgrMap = {
  "border-dim": "90",
  "border-focus": "94",
  "selection-bg": "44",
  "overlay-bg": "40",
  title: "1;97",
  "chart-name": "1;97",
  "session-id": "90",
  timestamp: "90",
  metric: "32",
  "phase-current": "1;96",
  "phase-done": "32",
  "phase-upcoming": "90",
  "status/streaming": "96",
  "status/done": "32",
  "status/waiting": "33",
  "status/error": "31",
  "status/idle": "90",
  "bar-filled": "32",
  "bar-empty": "90",
  "sent-tag": "1;97;44",
  "reply-tag": "1;97;42",
  "md-h1": "1;94",
  "md-h2": "1;96",
  "md-h3": "1;36",
  "md-bold": "1;97",
  "md-italic": "3;37",
  "md-code": "33",
  "md-quote": "3;32",
  "md-rule": "90",
  "md-bullet": "94",
  "md-link": "4;36",
  "code-fence": "90",
  "code-plain": "37",
  "code-comment": "90",
  "code-string": "32",
  "code-number": "33",
  "code-keyword": "35",
};

/** All theme keys, in declaration order (parity with `theme-keys`). */
export const THEME_KEYS = Object.keys(THEME_256) as ThemeKey[];

/** Select the raw SGR map for a capability. `none` ⇒ all-empty. */
function sgrMapFor(cap: Capability): SgrMap {
  switch (cap) {
    case "truecolor":
    case "256":
      return THEME_256;
    case "16":
      return THEME_16;
    case "none":
    default: {
      const empty = {} as SgrMap;
      for (const k of THEME_KEYS) empty[k] = "";
      return empty;
    }
  }
}

// ---------------------------------------------------------------------------
// Per-invokeid hue palette — port of `allocate-color` (theme.clj ~245)
// ---------------------------------------------------------------------------

/**
 * Round-robin invokeid palette, SGR foreground codes, SAME order as
 * `invokeid-palette` in theme.clj (cyan, magenta, yellow, green, blue + bright
 * red/cyan/magenta/yellow/green).
 */
export const INVOKEID_PALETTE: ReadonlyArray<string> = [
  "36", // cyan
  "35", // magenta
  "33", // yellow
  "32", // green
  "34", // blue
  "91", // bright red
  "96", // bright cyan
  "95", // bright magenta
  "93", // bright yellow
  "92", // bright green
];

/** Fixed hues for well-known sources — verbatim from theme.clj. */
export const CHART_COLOR = "90"; // bright black / dim grey
export const HUMAN_COLOR = "97"; // bright white
export const ERROR_COLOR = "31"; // red
export const DEBUG_COLOR = "90"; // dim

/** A well-known (keyword) role source, mirroring theme.clj's keywords. */
export type WellKnownRole = "chart" | "human" | "error" | "debug";

/** A color source: an opaque invokeid string OR a well-known role. */
export type RoleSource = string | WellKnownRole;

const WELL_KNOWN: Record<WellKnownRole, string> = {
  chart: CHART_COLOR,
  human: HUMAN_COLOR,
  error: ERROR_COLOR,
  debug: DEBUG_COLOR,
};

function isWellKnown(s: string): s is WellKnownRole {
  return s === "chart" || s === "human" || s === "error" || s === "debug";
}

/**
 * Per-invokeid hue allocator. Holds the round-robin state (`nextIdx` +
 * `invokeid → SGR code`) so allocation is stable across the session, exactly
 * like `allocate-color` threading state through the TUI state map. Kept as an
 * explicit object (not a module global) so snapshot tests are reproducible.
 *
 * Construct via {@link makeTheme}; `roleColor` / `roleStyle` allocate on first
 * sight of an invokeid and return the SAME hue thereafter.
 */
export class RolePalette {
  private nextIdx = 0;
  private readonly assigned = new Map<string, string>();
  /** When false (capability `none`), all role lookups return no-color. */
  readonly enabled: boolean;

  constructor(enabled: boolean) {
    this.enabled = enabled;
  }

  /**
   * SGR code (digits, e.g. "36") for a source, allocating round-robin for a
   * fresh invokeid string. Well-known roles return their fixed code. Returns
   * `""` when colors are disabled (parity with `role-sgr` returning "").
   */
  sgrFor(source: RoleSource): string {
    if (!this.enabled || source == null) return "";
    if (isWellKnown(source)) return WELL_KNOWN[source];
    const existing = this.assigned.get(source);
    if (existing != null) return existing;
    const code =
      INVOKEID_PALETTE[this.nextIdx % INVOKEID_PALETTE.length]!;
    this.assigned.set(source, code);
    this.nextIdx += 1;
    return code;
  }

  /** Foreground hex for a source (`#rrggbb`) or `null` when disabled. */
  roleColor(source: RoleSource): string | null {
    const sgr = this.sgrFor(source);
    return sgr === "" ? null : decodeSgr(sgr).fg;
  }

  /** Full {@link StyleSpec} for a role source (fg only; bold etc. unused). */
  roleStyle(source: RoleSource): StyleSpec {
    return decodeSgr(this.sgrFor(source));
  }
}

// ---------------------------------------------------------------------------
// Theme facade — the small API the views consume
// ---------------------------------------------------------------------------

/** Live status keywords, mirroring `status-color`'s cases. */
export type StatusKeyword =
  | "streaming"
  | "done"
  | "waiting"
  | "error"
  | "idle"
  | "exit";

const STATUS_TO_KEY: Record<StatusKeyword, ThemeKey> = {
  streaming: "status/streaming",
  done: "status/done",
  waiting: "status/waiting",
  error: "status/error",
  idle: "status/idle",
  exit: "status/idle",
};

/**
 * Resolved theme: the capability-aware facade the panes use. Holds decoded
 * {@link StyleSpec}s for every semantic key plus a {@link RolePalette} for
 * per-invokeid hues. `colored` is false under the `none` tier (parity with
 * `theme-color?`), letting callers skip wrapping entirely.
 */
export interface Theme {
  readonly capability: Capability;
  /** True when the theme actually emits color (not the `none` tier). */
  readonly colored: boolean;
  /** Stateful per-invokeid hue allocator (round-robin). */
  readonly palette: RolePalette;

  /** Decoded style for a semantic key. `none` tier ⇒ {@link NO_STYLE}. */
  style(key: ThemeKey): StyleSpec;
  /** Foreground hex for a semantic key, or `null` (no color). */
  themeColor(key: ThemeKey): string | null;
  /** Foreground hex for a live status keyword, or `null`. */
  statusColor(status: StatusKeyword): string | null;
  /** Decoded style for a live status keyword. */
  statusStyle(status: StatusKeyword): StyleSpec;
  /** Foreground hex for a role source (allocates per invokeid), or `null`. */
  roleColor(source: RoleSource): string | null;
  /** As {@link Theme.themeColor} but returns an OpenTUI {@link ColorInput} or `undefined`. */
  fg(key: ThemeKey): ColorInput | undefined;
  /** Background hex for a semantic key, or `null`. */
  bg(key: ThemeKey): string | null;
}

/**
 * Build the resolved {@link Theme} for a capability. Decodes every semantic
 * key once (truecolor hex) and wires a fresh {@link RolePalette}. This is the
 * single entry point views call (typically `makeTheme(detectCapability())`).
 */
export function makeTheme(capability: Capability): Theme {
  const sgr = sgrMapFor(capability);
  const colored = capability !== "none";
  const decoded = {} as Record<ThemeKey, StyleSpec>;
  for (const k of THEME_KEYS) decoded[k] = decodeSgr(sgr[k]);
  const palette = new RolePalette(colored);

  const style = (key: ThemeKey): StyleSpec => decoded[key] ?? { ...NO_STYLE };
  const statusStyle = (status: StatusKeyword): StyleSpec =>
    style(STATUS_TO_KEY[status] ?? "status/idle");

  return {
    capability,
    colored,
    palette,
    style,
    statusStyle,
    themeColor: (key) => style(key).fg,
    statusColor: (status) => statusStyle(status).fg,
    roleColor: (source) => palette.roleColor(source),
    fg: (key) => style(key).fg ?? undefined,
    bg: (key) => style(key).bg,
  };
}

/** Convenience: build the theme from the live Bun process environment. */
export function makeThemeFromEnv(): Theme {
  return makeTheme(detectCapability());
}

/** Re-export so views can construct OpenTUI colors without a second import. */
export { RGBA };
export type { ColorInput };
