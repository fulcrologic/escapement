/**
 * Indeterminate-progress SHIMMER — one bright `▰` cell sliding through dim `▱`
 * cells. Used for a SINGLE streaming LLM where no token total is known (so a
 * determinate completion bar would be dishonest).
 *
 * Parity with `escapement.tui.live/shimmer`: the bright cell position is
 * `(tick + lastTs/100) % width` — a deterministic frame-counter slide, NOT a
 * wall-clock RNG, so snapshots (task 016) are reproducible at a fixed tick.
 * Bright = `status/streaming`, dim cells = `bar-empty`.
 */

import { For } from "solid-js";
import type { Theme } from "../../domain/theme";

const BRIGHT = "▰";
const DIM = "▱";

/**
 * Pure shimmer position — the index of the bright cell. Mirrors `live-tick`
 * (`tick + (last-ts quot 100)`) folded into `shimmer`'s `(mod tick width)`.
 * Exported so task 016 can assert geometry without rendering.
 */
export function shimmerPos(tick: number, lastTs: number, width: number): number {
  if (width <= 0) return 0;
  const frame = Math.trunc(tick) + Math.trunc((lastTs ?? 0) / 100);
  return ((frame % width) + width) % width;
}

export function Shimmer(props: {
  theme: Theme;
  width: number;
  tick: number;
  lastTs: number;
}) {
  const pos = () => shimmerPos(props.tick, props.lastTs, props.width);
  const cells = () =>
    props.width <= 0 ? [] : Array.from({ length: props.width }, (_, i) => i);
  return (
    <text wrapMode="none">
      <For each={cells()}>
        {(i) =>
          i === pos() ? (
            <span style={{ fg: props.theme.fg("status/streaming") }}>{BRIGHT}</span>
          ) : (
            <span style={{ fg: props.theme.fg("bar-empty") }}>{DIM}</span>
          )
        }
      </For>
    </text>
  );
}
