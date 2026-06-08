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
import type { RerunOverrides } from "../transport/wire";

/**
 * A control op understood by the agent's WS control handler (tasks 004 / 006).
 *
 * The first group are the original mission-control ops. The second group are
 * the time-travel debugger ops (task 001 wire contract §9): `arm-llm-breakpoint`
 * arms the per-turn LLM gate; `turn-next`/`turn-back` walk the parked turn
 * pointer; `request-model-catalog`/`request-conversation` fetch the dropdown +
 * editable transcript backing the re-run form; `rerun-from` ships an edited
 * branch. `continue` is shared — agent-side it is routed to whichever gate is
 * engaged (per-event step gate OR the turn gate).
 */
export type ControlOp =
  | "pause"
  | "step"
  | "continue"
  | "arm"
  | "ui-interrupt"
  | "ui-quit"
  // --- time-travel debugger (wire §9) ---
  | "arm-llm-breakpoint"
  | "turn-next"
  | "turn-back"
  | "request-model-catalog";

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

/** Branch coordinate + edited payload for a `rerun-from` send (task 012). */
export interface RerunFromArgs {
  /** Source session being branched (opaque; from `ESCAPEMENT_SESSION_ID`). */
  sessionId: string;
  invokeid: string;
  nodeId: string;
  visit: number;
  /** Resume turn (top-level, NOT inside overrides). */
  turn: number;
  overrides?: RerunOverrides;
}

/**
 * The parametrized debugger senders that need more than a bare op string. These
 * build the `request-conversation` / `rerun-from` frames (task 008 shapes) and
 * push them over the same back-channel. Returns whether each frame was delivered
 * (false on a replay source / closed socket — the keypress then just no-ops).
 */
export interface DebugDispatch {
  /** Ask the agent for an invocation's editable conversation (turns). The
   *  capture coordinates `{nodeId, visit}` (resolved sidecar-side from the
   *  llm/request fold) ride alongside the invokeid so the agent reads the
   *  captured turns directly (wire §9.1); omit ⇒ agent falls back to visit 0. */
  requestConversation: (
    invokeid: string,
    ref?: { nodeId: string; visit: number } | null,
  ) => boolean;
  /** Ship an edited branch to re-run from a captured turn. */
  rerunFrom: (args: RerunFromArgs) => boolean;
}

export function makeDebugDispatch(
  source: Pick<EventSource, "send">,
): DebugDispatch {
  return {
    requestConversation: (invokeid, ref) =>
      source.send({
        kind: "control",
        op: "request-conversation",
        invokeid,
        ...(ref ? { "node-id": ref.nodeId, visit: ref.visit } : {}),
      }),
    rerunFrom: (args: RerunFromArgs) => {
      const frame: OutboundFrame = {
        kind: "control",
        op: "rerun-from",
        "session-id": args.sessionId,
        invokeid: args.invokeid,
        "node-id": args.nodeId,
        visit: args.visit,
        turn: args.turn,
        ...(args.overrides ? { overrides: args.overrides } : {}),
      };
      return source.send(frame);
    },
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
 *
 * The debugger surfaces (the conversation menu + the re-run form, task 012) are
 * ALSO modal-tier: they sit above mission-control so their navigation/editing is
 * captured and never leaks. They are gated behind `modalOpen` here — `main.tsx`
 * folds "menu open || form open || human prompt up" into the single `modalOpen`
 * signal, and `keybindings.ts` dispatches among them in priority order while in
 * the `modal` tier. Per the JLine "inspector over modal" rule, an open overlay
 * still wins; a debugger surface is normally used with the overlay CLOSED.
 */
export function resolveMode(opts: {
  modalOpen: boolean;
  overlayOpen: boolean;
}): InputMode {
  if (opts.overlayOpen) return "overlay";
  if (opts.modalOpen) return "modal";
  return "mission-control";
}
