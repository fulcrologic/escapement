/**
 * Frame-tick signal for the LIVE panel's indeterminate animations (shimmer)
 * and any clock-driven repaint that must NOT couple to token arrival.
 *
 * Parity note: the JLine TUI advances the shimmer by a frame COUNTER, never a
 * wall-clock RNG, so the animation is reproducible. We mirror that here: a
 * single shared Solid signal incremented on a ~30fps `setInterval`. Geometry
 * (shimmer position, bar width) stays a PURE function of `(tick, state, width)`
 * so task 016 can snapshot a fixed tick deterministically.
 *
 * Tokens update via store reactivity independently — the OpenTUI frame loop
 * paints those; we never re-render per token. The tick only drives the slide.
 */

import { createSignal, onCleanup } from "solid-js";

/** Default tick cadence (~30fps) — bounded so the shimmer slides smoothly. */
export const TICK_INTERVAL_MS = 1000 / 30;

/**
 * A shared, lazily-started frame tick. The first consumer starts the timer;
 * the signal increments forever (bounded to a large modulus to avoid overflow
 * over a very long session). Returns the reader.
 */
export function createTick(intervalMs: number = TICK_INTERVAL_MS): () => number {
  const [tick, setTick] = createSignal(0);
  const id = setInterval(
    () => setTick((t) => (t + 1) % 0x7fffffff),
    intervalMs,
  );
  onCleanup(() => clearInterval(id));
  return tick;
}
