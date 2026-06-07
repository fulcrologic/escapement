/**
 * Determinate completion bar — `done/total` of the cells filled. Only GROUP
 * headers (a role with >1 concurrent session) get one: there the total is
 * known (number of sessions), so the bar is honest. A single streaming LLM has
 * no known token total and gets a {@link Shimmer} instead.
 *
 * Parity with `escapement.tui.live/completion-bar`: filled `█` cells
 * (`bar-filled`, or a role hue) vs empty `░` (`bar-empty`); `floor(frac*width)`
 * fill; responsive width via {@link liveBarWidthFor}.
 */

import { For } from "solid-js";
import type { Theme } from "../../domain/theme";

const FILLED = "█";
const EMPTY = "░";

/** Default bar width; shrinks on narrow panes. Port of `live-bar-width` (10). */
export const LIVE_BAR_WIDTH = 10;

/**
 * Responsive bar width: 10 cells, reduced on narrow interiors so the bar never
 * crowds out the role name / metrics. Minimum 4. Port of `live-bar-width-for`.
 */
export function liveBarWidthFor(interiorW: number): number {
  const base =
    interiorW >= 50 ? LIVE_BAR_WIDTH : interiorW >= 36 ? 8 : interiorW >= 28 ? 6 : 4;
  return Math.max(4, Math.min(base, Math.max(4, interiorW - 24)));
}

/** Number of filled cells for `done/total` over `width`. Pure (task 016). */
export function barFill(done: number, total: number, width: number): number {
  if (width <= 0) return 0;
  const d = Math.max(0, done ?? 0);
  const t = Math.max(0, total ?? 0);
  const frac = t > 0 ? Math.min(d, t) / t : 0.0;
  return Math.min(width, Math.max(0, Math.floor(frac * width)));
}

export function CompletionBar(props: {
  theme: Theme;
  done: number;
  total: number;
  width: number;
  /** Override `bar-filled` with a role hue (hex) to tint the fill. */
  filledColor?: string | null;
}) {
  const nfill = () => barFill(props.done, props.total, props.width);
  const cells = () =>
    props.width <= 0 ? [] : Array.from({ length: props.width }, (_, i) => i);
  const filledFg = () => props.filledColor ?? props.theme.fg("bar-filled");
  return (
    <text wrapMode="none">
      <For each={cells()}>
        {(i) =>
          i < nfill() ? (
            <span style={{ fg: filledFg() }}>{FILLED}</span>
          ) : (
            <span style={{ fg: props.theme.fg("bar-empty") }}>{EMPTY}</span>
          )
        }
      </For>
    </text>
  );
}
