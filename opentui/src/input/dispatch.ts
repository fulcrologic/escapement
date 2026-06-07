/**
 * Input dispatch seam — the control / interrupt / quit back-channel and the
 * "current mode" resolver that gives the keymap (keybindings.ts) an unambiguous
 * routing decision.
 *
 * Mode precedence mirrors the JLine `input-loop` exactly:
 *
 *   modal  >  inspector-overlay  >  mission-control
 *
 * - **modal** — a human prompt is up (task 013). The modal owns the keymap; the
 *   inspector may still be layered on top (the JLine handler routes to the
 *   inspector when both are open). Task 013 plugs a `ModalHook` in here; until
 *   then `modalOpen()` is always false and this tier is inert.
 * - **inspector-overlay** — the `?` inspector is open. Keys route to the
 *   `InspectorControls` handle (1/2/3 views, j/k/g/G nav, Enter drill, o
 *   artifacts, h/Backspace pop, pager scroll). Within the overlay, `inPager()`
 *   sub-routes to the pager scroll group vs the list nav group.
 * - **mission-control** — neither modal nor overlay: focus/maximize/scroll +
 *   control keys + Esc/Ctrl-C.
 *
 * The control keys (s/c/p/P) and Esc/Ctrl-C do NOT do their work here — they are
 * forwarded as wire `control` frames over the back-channel (task 005 `send`).
 * The agent maps them to `escapement.control/{pause,step,continue,arm}` /
 * `:ui.interrupt` / `:ui.quit` (task 004's WS handlers). This module only owns
 * the dispatch fn; the RPC semantics live agent-side.
 */

import type { EventSource, OutboundFrame } from "../transport";

/** A control op understood by the agent's WS control handler (task 004). */
export type ControlOp =
  | "pause"
  | "step"
  | "continue"
  | "arm"
  | "ui-interrupt"
  | "ui-quit";

/**
 * Sends a `control` frame back to the agent. Returns whether it was delivered
 * (false on a replay source / closed socket — keypresses then just no-op).
 */
export function makeControlDispatch(
  source: Pick<EventSource, "send">,
): (op: ControlOp, n?: number) => boolean {
  return (op: ControlOp, n?: number) => {
    const frame: OutboundFrame =
      op === "step" && n != null
        ? { kind: "control", op, n }
        : { kind: "control", op };
    return source.send(frame);
  };
}

/** The three keymap tiers, in precedence order. */
export type InputMode = "modal" | "overlay" | "mission-control";

/**
 * A modal hook the human-modals task (013) implements. While `isOpen()` is true
 * the keymap consults `handleKey` FIRST; returning true consumes the key. When
 * the inspector is ALSO open the modal does not consume — the keymap falls
 * through to the overlay (matching the JLine "inspector over modal" rule), which
 * is why `resolveMode` returns "overlay" if both are open.
 */
export interface ModalHook {
  isOpen: () => boolean;
  /** Handle a key while the modal is the active tier. Return true if consumed. */
  handleKey: (key: KeyEvent) => boolean;
}

/** Minimal shape of OpenTUI's parsed key event we depend on. */
export interface KeyEvent {
  name?: string;
  ctrl?: boolean;
  shift?: boolean;
  meta?: boolean;
  preventDefault?: () => void;
}

/**
 * Resolve the active tier. `modal > overlay > mission-control`, but when BOTH
 * modal and overlay are open the overlay wins the keymap (the JLine handler
 * routes keys to the inspector while a modal is layered beneath it).
 */
export function resolveMode(opts: {
  modalOpen: boolean;
  overlayOpen: boolean;
}): InputMode {
  if (opts.overlayOpen) return "overlay";
  if (opts.modalOpen) return "modal";
  return "mission-control";
}
