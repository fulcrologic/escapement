/**
 * Human-input modals — port of the JLine `escapement.tui` modal region
 * (render ~888-935, `handle-modal-key` ~1067, `ask!`/`complete-modal!`) into a
 * Solid component (task 013). Four input kinds at parity with the JLine
 * `HumanRenderer`:
 *
 *   - **text**    `<input>`-style line buffer; Enter submits the string, Esc
 *                 cancels, Backspace deletes, printable chars append.
 *   - **confirm** prompt + `[Y/n]`/`[y/N]` default; y/n/Enter resolve a boolean,
 *                 Esc cancels (parity: blank Enter ⇒ the default).
 *   - **select**  inline option list; ↑/↓/j/k (and ←/→) move the cursor (wraps,
 *                 matching the JLine `mod`), Enter submits the chosen option's
 *                 **value**, Esc cancels.
 *   - **multi**   checkbox list; Space toggles `[x]`, Enter submits the vector of
 *                 chosen `value`s (sorted by index, parity with `complete-modal!`),
 *                 Esc cancels.
 *
 * Wiring: a `prompt` frame folds into `store.state.prompt` (task 006). The host
 * (main.tsx) passes that envelope here; on submit/cancel this component invokes
 * `onAnswer(value)` / `onCancel()`, which send the wire `answer` message
 * (`{prompt-id, value}` or `{prompt-id, cancelled:true}`) over the back-channel
 * (task 005 `source.send`). The agent (task 003) resolves/rejects the parked
 * promise keyed by `prompt-id`; Esc ⇒ `{:reason :cancelled}` (interrupt
 * semantics, R3/R13).
 *
 * Keymap precedence: this component exposes a {@link ModalHook} (isOpen /
 * handleKey) via `ref`, plugged into `makeKeyHandler`'s `deps.modal` (task 012).
 * While the modal is open AND the inspector is closed, every key routes here
 * (Ctrl-C / `?` are handled by the keymap BEFORE the modal tier, so the
 * inspector can still toggle OVER a modal — the modal stays pending beneath).
 *
 * Cursor visibility: shown for text/confirm (line-edit), hidden for select/multi
 * (parity with the JLine cursor placement, which only positions a cursor for the
 * text/confirm buffers).
 *
 * State is keyed by `prompt-id` and reset whenever a NEW prompt arrives, so a
 * superseded prompt never delivers stale buffer/cursor/checked state (no
 * deadlock — the previous prompt-id's answer simply never fires here; the agent
 * cancels the orphaned promise on its side).
 */

import { For, Show, createEffect, createMemo, createSignal } from "solid-js";
import type { PromptEnvelope } from "../transport/wire";
import type { Theme } from "../domain/theme";
import type { KeyEvent, ModalHook } from "../input/dispatch";
import { truncateDisplay } from "../domain/wrap";

/** A normalized `{label, value}` option (select / multi). */
interface ModalOption {
  label: string;
  value: unknown;
}

/** Pull the normalized option list out of the flat HumanRenderer opts map. */
function optionsOf(prompt: PromptEnvelope): ModalOption[] {
  const raw = prompt.opts?.["options"];
  if (!Array.isArray(raw)) return [];
  return raw.map((o) => {
    if (o && typeof o === "object") {
      const rec = o as Record<string, unknown>;
      return { label: String(rec["label"] ?? rec["value"] ?? ""), value: rec["value"] };
    }
    return { label: String(o), value: o };
  });
}

function promptText(prompt: PromptEnvelope): string {
  const p = prompt.opts?.["prompt"];
  return typeof p === "string" ? p : defaultPromptFor(prompt.type);
}

function defaultPromptFor(type: PromptEnvelope["type"]): string {
  switch (type) {
    case "text":
      return "?";
    case "confirm":
      return "Confirm?";
    case "select":
      return "Select:";
    case "multi":
      return "Select any:";
  }
}

function confirmDefault(prompt: PromptEnvelope): boolean {
  return Boolean(prompt.opts?.["default"]);
}

/** Is `name` a single printable character we should append to a text buffer? */
function printableChar(key: KeyEvent): string | null {
  const n = key.name;
  if (!n) return null;
  if (key.ctrl || key.meta) return null;
  if (n === "space") return " ";
  // OpenTUI reports single graphemes as the key name (incl. uppercase for shift).
  if (n.length === 1) return n;
  return null;
}

export interface ModalsProps {
  /** The open prompt (from `store.state.prompt`), or null. */
  prompt: PromptEnvelope | null;
  theme: Theme;
  /** Interior width (terminal columns) for truncation. */
  width: number;
  /** Submit: send the wire `answer` `{prompt-id, value}`. */
  onAnswer: (promptId: string, value: unknown) => void;
  /** Cancel: send `{prompt-id, cancelled:true}` (Esc ⇒ agent `:reason :cancelled`). */
  onCancel: (promptId: string) => void;
  /** Receive the imperative {@link ModalHook} for the keymap (task 012). */
  ref?: (hook: ModalHook) => void;
}

/**
 * The modal region. Renders nothing when no prompt is open. Anchored to a single
 * row (the JLine modal sits on the term's second-to-last row); here it's a
 * bordered one-to-few-line box the host slots above the footer.
 */
export function Modals(props: ModalsProps): import("solid-js").JSX.Element {
  // Per-prompt input state. Reset on a new prompt-id.
  const [buffer, setBuffer] = createSignal("");
  const [cursor, setCursor] = createSignal(0);
  const [checked, setChecked] = createSignal<Set<number>>(new Set());

  const open = () => props.prompt != null;
  const type = () => props.prompt?.type ?? null;
  const options = createMemo(() => (props.prompt ? optionsOf(props.prompt) : []));

  // Reset edit state whenever the prompt identity changes (new prompt-id).
  let lastId: string | null = null;
  createEffect(() => {
    const id = props.prompt?.["prompt-id"] ?? null;
    if (id !== lastId) {
      lastId = id;
      setBuffer("");
      setCursor(0);
      setChecked(new Set<number>());
    }
  });

  function submit(value: unknown): void {
    const p = props.prompt;
    if (p) props.onAnswer(p["prompt-id"], value);
  }
  function cancel(): void {
    const p = props.prompt;
    if (p) props.onCancel(p["prompt-id"]);
  }

  /** Move the select/multi cursor with wrap (parity with the JLine `mod`). */
  function moveCursor(delta: number): void {
    const n = options().length;
    if (n <= 0) return;
    setCursor((i) => ((i + delta) % n + n) % n);
  }

  /** Handle a key while THIS modal is the active tier. Return true if consumed. */
  function handleKey(key: KeyEvent): boolean {
    const p = props.prompt;
    if (!p) return false;
    const name = key.name;

    // Esc always cancels (parity: maps to interrupt / :reason :cancelled).
    if (name === "escape") {
      cancel();
      return true;
    }

    switch (p.type) {
      case "text": {
        if (name === "return" || name === "enter") {
          submit(buffer());
          return true;
        }
        if (name === "backspace") {
          setBuffer((b) => (b.length ? b.slice(0, -1) : b));
          return true;
        }
        const ch = printableChar(key);
        if (ch != null) {
          setBuffer((b) => b + ch);
          return true;
        }
        return true; // swallow stray keys while the modal is up
      }

      case "confirm": {
        if (name === "return" || name === "enter") {
          const b = buffer().trim();
          const v = b === ""
            ? confirmDefault(p)
            : /^y(es)?$/i.test(b)
              ? true
              : /^no?$/i.test(b)
                ? false
                : confirmDefault(p);
          submit(v);
          return true;
        }
        if (name === "y" || name === "Y") {
          submit(true);
          return true;
        }
        if (name === "n" || name === "N") {
          submit(false);
          return true;
        }
        if (name === "backspace") {
          setBuffer((b) => (b.length ? b.slice(0, -1) : b));
          return true;
        }
        const ch = printableChar(key);
        if (ch != null) setBuffer((b) => b + ch);
        return true;
      }

      case "select": {
        if (name === "up" || name === "k" || name === "left") {
          moveCursor(-1);
          return true;
        }
        if (name === "down" || name === "j" || name === "right") {
          moveCursor(1);
          return true;
        }
        if (name === "return" || name === "enter") {
          const opt = options()[cursor()];
          submit(opt ? opt.value : null);
          return true;
        }
        return true;
      }

      case "multi": {
        if (name === "up" || name === "k" || name === "left") {
          moveCursor(-1);
          return true;
        }
        if (name === "down" || name === "j" || name === "right") {
          moveCursor(1);
          return true;
        }
        if (name === "space") {
          const i = cursor();
          setChecked((s) => {
            const next = new Set<number>(s);
            if (next.has(i)) next.delete(i);
            else next.add(i);
            return next;
          });
          return true;
        }
        if (name === "return" || name === "enter") {
          const opts = options();
          const value = [...checked()]
            .sort((a, b) => a - b)
            .map((i) => opts[i]?.value);
          submit(value);
          return true;
        }
        return true;
      }
    }
    return true;
  }

  const hook: ModalHook = { isOpen: open, handleKey };
  props.ref?.(hook);

  // --- rendering ---
  const fg = (k: Parameters<Theme["fg"]>[0]) => props.theme.fg(k);
  const cut = (s: string) => truncateDisplay(s, Math.max(1, props.width));

  return (
    <Show when={props.prompt}>
      {(p: () => PromptEnvelope) => (
        <box
          border
          borderStyle="rounded"
          borderColor={fg("border-focus")}
          flexDirection="column"
          flexShrink={0}
          width="100%"
        >
          {/* prompt label */}
          <text>
            <span style={{ fg: fg("title"), bold: true }}>{cut(` ▸ ${promptText(p())} `)}</span>
          </text>

          <Switch_>
            {/* text / confirm: a line buffer (cursor shown) */}
            <Show when={p().type === "text" || p().type === "confirm"}>
              <text>
                <span style={{ fg: fg("metric") }}>
                  {p().type === "confirm"
                    ? `   ${confirmDefault(p()) ? "[Y/n]" : "[y/N]"} `
                    : "   "}
                </span>
                <span style={{ fg: fg("md-bold") }}>{buffer()}</span>
                <span style={{ fg: fg("phase-current") }}>{"█"}</span>
              </text>
            </Show>

            {/* select: inline option list, cursor reversed */}
            <Show when={p().type === "select"}>
              <box flexDirection="column">
                <For each={options()}>
                  {(opt, i) => (
                    <text>
                      <span
                        style={
                          i() === cursor()
                            ? { fg: fg("phase-current"), reverse: true }
                            : { fg: fg("phase-upcoming") }
                        }
                      >
                        {cut(`   ${i() === cursor() ? "▸ " : "  "}${opt.label}`)}
                      </span>
                    </text>
                  )}
                </For>
                <text>
                  <span style={{ fg: fg("border-dim") }}>{"   ↑/↓ move · Enter select · Esc cancel"}</span>
                </text>
              </box>
            </Show>

            {/* multi: checkbox list */}
            <Show when={p().type === "multi"}>
              <box flexDirection="column">
                <For each={options()}>
                  {(opt, i) => (
                    <text>
                      <span
                        style={
                          i() === cursor()
                            ? { fg: fg("phase-current"), reverse: true }
                            : { fg: fg("phase-upcoming") }
                        }
                      >
                        {cut(
                          `   ${i() === cursor() ? "▸" : " "} [${checked().has(i()) ? "x" : " "}] ${opt.label}`,
                        )}
                      </span>
                    </text>
                  )}
                </For>
                <text>
                  <span style={{ fg: fg("border-dim") }}>
                    {"   Space toggle · Enter submit · Esc cancel"}
                  </span>
                </text>
              </box>
            </Show>
          </Switch_>
        </box>
      )}
    </Show>
  );
}

/** Tiny passthrough so the three mutually-exclusive `<Show>`s read as a group. */
function Switch_(props: { children: import("solid-js").JSX.Element }): import("solid-js").JSX.Element {
  return <>{props.children}</>;
}
