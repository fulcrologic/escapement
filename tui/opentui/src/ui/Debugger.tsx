/**
 * Debugger affordances (task 014) — the live pause/step/continue/arm surface.
 *
 * The control KEYS (s/c/p/P) are already wired in `input/keybindings.ts` →
 * `makeControlDispatch` → the WS `control` back-channel → the agent-side debug
 * controller (`escapement.debug.controller`). This component is the *read* side:
 * it renders the live debugger state the agent pushes back as `debug` snapshots
 * (`docs/opentui-wire.md` §6 forward push), mirrored into `state.debug` by the
 * store (`reduceDebug`). It surfaces:
 *
 *   - {@link PausedBanner} — the one-row PAUSED banner under the header (parity
 *     with the JLine `status-line` paused indicator), showing the step-budget
 *     and the control-key legend. Rendered by the Shell when `state.debug` says
 *     the run is paused.
 *
 * The fuller debugger READOUT (active states / mode / step-budget /
 * pause-on-ext) lives in the Inspector's **Status** view (1/2/3 → 3), which is
 * fed `state.debug` from the host. `v` (visualize) opens that view as the
 * compact live-configuration parity for the JLine d2 render — see the note in
 * `main.tsx`.
 *
 * `debugFromState` is the pure adaptor from the store's `DebugState` to the
 * Inspector's `debug` prop shape, kept here so both the banner and the Inspector
 * read a single source of truth.
 */

import { Show } from "solid-js";
import type { DebugState } from "../domain/store";
import type { Theme } from "../domain/theme";
import { truncateDisplay } from "../domain/wrap";

/** True when a debug controller is active AND the run is currently halted. */
export function isPaused(debug: DebugState | null | undefined): boolean {
  return Boolean(debug && debug.paused);
}

/** The PAUSED-banner legend line (pure, testable). */
export function pausedBannerText(debug: DebugState | null | undefined): string {
  const budget = debug?.stepBudget ?? 0;
  const budgetLabel = budget > 0 ? `  (step-budget ${budget})` : "";
  return `  ⏸  PAUSED${budgetLabel}  —  s step · c continue · p pause · P arm · v states`;
}

/**
 * Adapt the store's `DebugState` to the Inspector Status-view `debug` prop.
 * `mode` mirrors the controller's `:mode` (paused vs running); `stepBudget` and
 * `pauseOnNextExternal` round out the readout. (`pauseOnNextExternal` isn't on
 * the wire snapshot today — left undefined; the Status view shows `false`.)
 */
export function debugFromState(
  debug: DebugState | null | undefined,
): { mode: string; stepBudget: number; pauseOnNextExternal?: boolean } | undefined {
  if (!debug) return undefined;
  return {
    mode: debug.paused ? "paused" : "running",
    stepBudget: debug.stepBudget,
  };
}

export interface PausedBannerProps {
  theme: Theme;
  debug: DebugState | null | undefined;
  /** banner interior width (columns) for truncation. */
  width: number;
}

/**
 * The one-row PAUSED banner. Renders nothing unless the run is paused, so the
 * Shell can mount it unconditionally. Styled error-hued + bold to read as a
 * halt indicator (parity with the JLine paused status line).
 */
export function PausedBanner(props: PausedBannerProps) {
  return (
    <Show when={isPaused(props.debug)}>
      <box
        height={1}
        flexShrink={0}
        backgroundColor={props.theme.bg("status/error") ?? undefined}
      >
        <text>
          <span style={{ fg: props.theme.fg("status/error"), bold: true }}>
            {truncateDisplay(
              pausedBannerText(props.debug),
              Math.max(0, props.width),
            )}
          </span>
        </text>
      </box>
    </Show>
  );
}
