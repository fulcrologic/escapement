/**
 * Shared snapshot-test helpers (task 016). DETERMINISTIC — no model, no
 * network, no wall-clock. Loads a fixture JSONL through the REAL transport
 * decoder + store reducer, then exposes the assembled `DomainState`.
 *
 * Determinism notes:
 *  - `tsToHms` / inspector ts use the LOCAL timezone; every snapshot test file
 *    pins `process.env.TZ = "UTC"` at its top BEFORE importing anything that
 *    touches `Date`. (Importing this file does NOT set TZ — set it in the test
 *    module's first line so it wins the import order.)
 *  - The LIVE shimmer is driven by an injected `tick` (a frame counter), never
 *    a clock — pass a fixed `tick` so the shimmer cell is pinned.
 *  - `stripFrame` collapses trailing whitespace so the committed text snapshot
 *    is reviewable and not sensitive to right-pad width.
 */

import { readFileSync } from "fs";
import { join } from "path";
import { decodeFrame, type ForwardFrame } from "../../src/transport/wire";
import {
  reduceFrames,
  initialDomainState,
  type DomainState,
} from "../../src/domain/store";
import { lineText, type StyledLine } from "../../src/ui/styled";
import { testRender } from "@opentui/solid";
import type { JSX } from "solid-js";

/**
 * Mount a component through the REAL OpenTUI reconciler, render ONE frame, and
 * return the right-stripped char frame + the raw setup (for captureSpans).
 *
 * Each call creates a fresh renderer and destroys it before returning, so test
 * cases never share renderer/yoga-WASM state (avoids a teardown race that can
 * surface as a transient yoga layout fault when many renderers churn in one
 * file). Use the returned `spans` snapshot eagerly — the renderer is gone after.
 */
export async function renderFrame(
  node: () => JSX.Element,
  size: { width: number; height: number },
): Promise<{ frame: string; spans: unknown }> {
  const setup = await testRender(node, size);
  try {
    await setup.renderOnce();
    const frame = stripFrame(setup.captureCharFrame());
    const spans = setup.captureSpans();
    return { frame, spans };
  } finally {
    setup.renderer.destroy();
    // let the destroy settle before the next renderer mounts.
    await new Promise((r) => setTimeout(r, 0));
  }
}

/** Decode a fixture JSONL (relative to test/fixtures) into forward frames. */
export function loadFrames(fixture: string): ForwardFrame[] {
  const text = readFileSync(
    join(import.meta.dir, "../fixtures", fixture),
    "utf8",
  );
  return text
    .split("\n")
    .map((l) => l.trim())
    .filter((l) => l.length > 0)
    .map((l) => decodeFrame(l))
    .filter((f): f is ForwardFrame => f != null);
}

/** Fold a fixture JSONL into the assembled domain state (transport→store). */
export function stateFromFixture(fixture: string): DomainState {
  return reduceFrames(loadFrames(fixture), initialDomainState());
}

/** Render styled lines to a plain multi-line string (text snapshot). */
export function linesToText(lines: StyledLine[]): string {
  return lines.map((l) => lineText(l).replace(/\s+$/u, "")).join("\n");
}

/** Right-strip every row of a captured char frame so snapshots stay tidy. */
export function stripFrame(frame: string): string {
  return frame
    .split("\n")
    .map((r) => r.replace(/\s+$/u, ""))
    .join("\n")
    .replace(/\n+$/u, "\n");
}
