/**
 * Conversation action menu — a small modal selection list opened by "acting" on
 * an LLM conversation row in the LIVE pane or an invocation row in the Inspector
 * (task 010). It is the entry point to the time-travel debugger from a specific
 * `invokeid`.
 *
 * Items (in order):
 *   - **Transcript**            → open the existing transcript pager (no new
 *                                 behavior; parity with the old Enter drill-in).
 *   - **Re-run from here…**     → enter debug mode: request the conversation
 *                                 (`request-conversation`) + the model catalog
 *                                 (`request-model-catalog`), then open the debug
 *                                 form (task 011) once data arrives.
 *   - **Break before next LLM** → arm the LLM breakpoint (`arm-llm-breakpoint`).
 *   - **Cancel**                → close the menu (also Esc).
 *
 * This component is RENDER-ONLY. It owns the cursor/wrap select logic (ported
 * from `Modals.tsx`'s select tier) and exposes an imperative
 * {@link ConversationMenuHook} (via `ref`) so task 012 can wire the open/close
 * keybindings and route keys here while the menu is the active (modal-tier)
 * layer. The actual op-send + debug-form open live behind the action callbacks
 * (props), which task 012 supplies — this file never touches the wire.
 *
 * Open/close + actions (the contract task 012 wires):
 *   - `hook.isOpen()` / `hook.open(invokeid)` / `hook.close()` —
 *     open targets a specific conversation by `invokeid`; close dismisses.
 *   - `hook.handleKey(key)` — ↑/↓/j/k/←/→ move (wrap), Enter selects, Esc
 *     cancels; returns true when consumed.
 *   - `props.onTranscript(invokeid)` — fires for the **Transcript** item.
 *   - `props.onRerun(invokeid)`      — fires for **Re-run from here…**.
 *   - `props.onBreak(invokeid)`      — fires for **Break before next LLM**.
 *   - `props.onCancel?()`            — fires on Cancel/Esc (optional).
 *
 * Selecting an item invokes the matching callback with the open `invokeid` and
 * then closes the menu (Transcript/Re-run/Break) — Cancel just closes.
 */

import { For, Show, createSignal, type JSX } from "solid-js";
import type { Theme } from "../domain/theme";
import type { KeyEvent } from "../input/dispatch";
import { truncateDisplay } from "../domain/wrap";

/** The selectable actions, in render order. */
export type ConversationAction = "transcript" | "rerun" | "break" | "cancel";

interface MenuItem {
  action: ConversationAction;
  label: string;
}

/** Fixed item list — Transcript first (parity with the prior Enter drill-in). */
const ITEMS: MenuItem[] = [
  { action: "transcript", label: "Transcript" },
  { action: "rerun", label: "Re-run from here…" },
  { action: "break", label: "Break before next LLM" },
  { action: "cancel", label: "Cancel" },
];

/**
 * Imperative handle task 012 binds the open/close keybindings + key routing to.
 * Mirrors {@link ModalHook} but adds a target `invokeid` (the conversation the
 * menu acts on) and an explicit `open`/`close`.
 */
export interface ConversationMenuHook {
  /** Is the menu currently open? (modal-tier gate for the keymap.) */
  isOpen: () => boolean;
  /** Open the menu targeting a specific conversation (resets the cursor). */
  open: (invokeid: string) => void;
  /** Close the menu without firing an action. */
  close: () => void;
  /** The conversation the menu is acting on, or null when closed. */
  invokeid: () => string | null;
  /** Handle a key while the menu is the active tier. Return true if consumed. */
  handleKey: (key: KeyEvent) => boolean;
}

export interface ConversationMenuProps {
  theme: Theme;
  /** Interior width (terminal columns) for truncation. */
  width: number;
  /** Transcript: open the existing transcript pager for this invokeid. */
  onTranscript: (invokeid: string) => void;
  /** Re-run: request conversation + model catalog, then open the debug form. */
  onRerun: (invokeid: string) => void;
  /** Break: arm the LLM breakpoint. */
  onBreak: (invokeid: string) => void;
  /** Cancel/Esc: closed without an action (optional). */
  onCancel?: () => void;
  /** Receive the imperative {@link ConversationMenuHook} for the keymap (task 012). */
  ref?: (hook: ConversationMenuHook) => void;
}

/**
 * The conversation action menu. Renders nothing when closed; while open it is a
 * bordered select list the host slots above the footer (same placement as the
 * human {@link Modals}).
 */
export function ConversationMenu(props: ConversationMenuProps): JSX.Element {
  const [target, setTarget] = createSignal<string | null>(null);
  const [cursor, setCursor] = createSignal(0);

  const open = () => target() != null;

  /** Move the cursor with wrap (parity with the JLine/Modals `mod`). */
  function moveCursor(delta: number): void {
    const n = ITEMS.length;
    setCursor((i) => ((i + delta) % n + n) % n);
  }

  /** Fire the action for `item` against the open invokeid, then close. */
  function choose(item: MenuItem): void {
    const iid = target();
    switch (item.action) {
      case "transcript":
        if (iid) props.onTranscript(iid);
        break;
      case "rerun":
        if (iid) props.onRerun(iid);
        break;
      case "break":
        if (iid) props.onBreak(iid);
        break;
      case "cancel":
        props.onCancel?.();
        break;
    }
    close();
  }

  function close(): void {
    setTarget(null);
    setCursor(0);
  }

  /** Handle a key while THIS menu is the active tier. Return true if consumed. */
  function handleKey(key: KeyEvent): boolean {
    if (!open()) return false;
    const name = key.name;
    if (name === "escape") {
      props.onCancel?.();
      close();
      return true;
    }
    if (name === "up" || name === "k" || name === "left") {
      moveCursor(-1);
      return true;
    }
    if (name === "down" || name === "j" || name === "right") {
      moveCursor(1);
      return true;
    }
    if (name === "return" || name === "enter") {
      const item = ITEMS[cursor()];
      if (item) choose(item);
      return true;
    }
    return true; // swallow stray keys while the menu is up (modal tier)
  }

  const hook: ConversationMenuHook = {
    isOpen: open,
    open: (invokeid: string) => {
      setTarget(invokeid);
      setCursor(0);
    },
    close,
    invokeid: target,
    handleKey,
  };
  props.ref?.(hook);

  // --- rendering ---
  const fg = (k: Parameters<Theme["fg"]>[0]) => props.theme.fg(k);
  const cut = (s: string) => truncateDisplay(s, Math.max(1, props.width));

  return (
    <Show when={open()}>
      <box
        border
        borderStyle="rounded"
        borderColor={fg("border-focus")}
        backgroundColor={props.theme.bg("overlay-bg") ?? undefined}
        flexDirection="column"
        flexShrink={0}
        width="100%"
      >
        <text>
          <span style={{ fg: fg("title"), bold: true }}>
            {cut(` ▸ conversation ${target() ?? ""} `)}
          </span>
        </text>
        <For each={ITEMS}>
          {(item, i) => {
            // Highlight the cursor row with a box-level background BAR (parity
            // with LivePanel's selected drill-in row). A span-level `inverse`
            // leaves background residue on previously-hovered rows when the
            // cursor moves — the box bg repaints the full row width each render.
            const sel = () => i() === cursor();
            return (
              <box
                width="100%"
                backgroundColor={
                  (sel()
                    ? props.theme.bg("selection-bg")
                    : props.theme.bg("overlay-bg")) ?? undefined
                }
              >
                <text>
                  <span style={{ fg: fg(sel() ? "phase-current" : "phase-upcoming") }}>
                    {cut(`   ${sel() ? "▸ " : "  "}${item.label}`)}
                  </span>
                </text>
              </box>
            );
          }}
        </For>
        <text>
          <span style={{ fg: fg("border-dim") }}>
            {"   ↑/↓ move · Enter select · Esc cancel"}
          </span>
        </text>
      </box>
    </Show>
  );
}
