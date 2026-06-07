/**
 * Responsive layout-mode logic for the shell — the Yoga/flexbox analogue of
 * `escapement.tui.compositor/layout`. We do NOT reproduce the absolute
 * row/col rectangle math (Yoga handles geometry); we only port the *mode
 * decision* and the pane-width policy so the visual result matches the JLine
 * TUI:
 *
 *   - `narrow`    : term width < NARROW_THRESHOLD → single stacked body pane.
 *   - `maximized` : the focused pane fills the body (Esc/m toggle).
 *   - `two-pane`  : LIVE left + LOG right, ~50/50, LIVE floored at LIVE_MIN_W
 *                   columns and never allowed to starve LOG below LIVE_MIN_W.
 *
 * Constants mirror `compositor.clj` (`narrow-threshold` 100, `live-min-w` 40,
 * `header-h` 5, `footer-h` 1).
 */

/** Which body pane has focus (drives borders + maximize target). Task 012 toggles. */
export type Focus = "live" | "log";

/** Responsive layout mode. */
export type LayoutMode = "two-pane" | "narrow" | "maximized";

/** Below this terminal width we drop the split and stack a single pane. */
export const NARROW_THRESHOLD = 100;

/** Minimum width (columns) the LIVE pane gets in a two-pane split. */
export const LIVE_MIN_W = 40;

/** Header strip height (top border + 3 content rows + bottom border). */
export const HEADER_H = 5;

/** Footer height (single row). */
export const FOOTER_H = 1;

export interface LayoutResult {
  mode: LayoutMode;
  focus: Focus;
  /** Columns allotted to the LIVE pane in two-pane mode (incl. its border). */
  liveWidth: number;
  /** Columns allotted to the LOG pane in two-pane mode (incl. its border). */
  logWidth: number;
  /** True when LIVE should render (always, except maximized-on-log). */
  showLive: boolean;
  /** True when LOG should render (always, except maximized-on-live). */
  showLog: boolean;
}

export interface LayoutInput {
  termWidth: number;
  focus: Focus;
  maximized: boolean;
}

/**
 * Pure mode + width decision. Port of `compositor.clj/layout`'s `cond`:
 * maximized first, then narrow, then the 50/50 split with the LIVE floor.
 */
export function computeLayout({
  termWidth,
  focus,
  maximized,
}: LayoutInput): LayoutResult {
  const w = Math.max(1, termWidth || 80);

  if (maximized) {
    return {
      mode: "maximized",
      focus,
      liveWidth: w,
      logWidth: w,
      showLive: focus === "live",
      showLog: focus === "log",
    };
  }

  if (w < NARROW_THRESHOLD) {
    // Narrow: a single stacked pane (the focused one).
    return {
      mode: "narrow",
      focus,
      liveWidth: w,
      logWidth: w,
      showLive: focus === "live",
      showLog: focus === "log",
    };
  }

  // Two-pane: ~50/50, LIVE floored at LIVE_MIN_W, never starving LOG.
  let liveW = Math.max(LIVE_MIN_W, Math.floor(w / 2));
  liveW = Math.min(liveW, w - LIVE_MIN_W);
  const logW = w - liveW;
  return {
    mode: "two-pane",
    focus,
    liveWidth: liveW,
    logWidth: logW,
    showLive: true,
    showLog: true,
  };
}
