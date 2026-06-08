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

import { For, Show, createEffect, createSignal } from "solid-js";
import type { DebugState } from "../domain/store";
import type {
  CatalogAliasModel,
  ConversationMessage,
  ConversationTurnModel,
  ModelCatalog,
} from "../domain/types";
import type { Theme } from "../domain/theme";
import type { KeyEvent, ModalHook } from "../input/dispatch";
import type { RerunOverrides, WireMessage } from "../transport/wire";
import { truncateDisplay, wrapDisplay } from "../domain/wrap";

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

/**
 * One-row banner shown when a `rerun-from` (Ctrl-R) failed to start a branch, so
 * the action is never a silent no-op. Self-hides when there is no error.
 */
export function BranchErrorBanner(props: {
  theme: Theme;
  debug: DebugState | null | undefined;
  width: number;
}) {
  return (
    <Show when={props.debug?.branchError}>
      <box
        height={1}
        flexShrink={0}
        backgroundColor={props.theme.bg("status/error") ?? undefined}
      >
        <text>
          <span style={{ fg: props.theme.fg("status/error"), bold: true }}>
            {truncateDisplay(
              `  ⚠ re-run: ${props.debug?.branchError ?? ""}`,
              Math.max(0, props.width),
            )}
          </span>
        </text>
      </box>
    </Show>
  );
}

// ===========================================================================
// Time-travel debug form (task 011)
// ===========================================================================
//
// The debug form is the *write* side of the time-travel debugger. It is driven
// purely from `DebugState` (009): `debug.conversation` (the captured turns to
// edit) + `debug.modelCatalog` (the alias/target dropdown). The form holds its
// OWN edit buffers (system / messages / temperature / model-target / resume
// turn) in Solid signals, derives the `rerun-from` `overrides` payload from
// them, and hands that to an `onRun` callback — task 012 wires the actual WS
// send + binds the field-navigation keys to the exposed {@link DebugFormHook}.
//
// Keep the logic pure & exported where possible so 012 can drive it headlessly.

/** One selectable model row in the dropdown: an alias's expanded target. */
export interface ModelTargetRow {
  /** The alias this target belongs to (e.g. "smart"). */
  alias: string;
  provider: string;
  model: string;
  /** True for the first target of each alias (the alias header row). */
  aliasHead: boolean;
  /** Preference rank of the owning alias (lower = preferred); -1 if unranked. */
  prefRank: number;
}

/**
 * Flatten a {@link ModelCatalog} into the COMPLETE ordered dropdown list: every
 * alias and EACH of its `{provider, model}` targets (the user's explicit ask).
 * Aliases are ordered by `preferences` first (then catalog order); within an
 * alias, targets keep their catalog order. Each row carries `aliasHead` so the
 * view can group, and `prefRank` for the preference badge.
 */
export function modelTargetRows(catalog: ModelCatalog | null | undefined): ModelTargetRow[] {
  if (!catalog) return [];
  const prefs = catalog.preferences ?? [];
  const rank = (a: string): number => {
    const i = prefs.indexOf(a);
    return i < 0 ? Number.MAX_SAFE_INTEGER : i;
  };
  const aliases: CatalogAliasModel[] = catalog.aliases
    .map((a, i) => ({ a, i }))
    .sort((x, y) => {
      const rx = rank(x.a.alias);
      const ry = rank(y.a.alias);
      if (rx !== ry) return rx - ry;
      return x.i - y.i; // stable: keep catalog order among unranked / equal-rank
    })
    .map((p) => p.a);
  const rows: ModelTargetRow[] = [];
  for (const a of aliases) {
    const pr = prefs.indexOf(a.alias);
    a.targets.forEach((t, i) => {
      rows.push({
        alias: a.alias,
        provider: t.provider,
        model: t.model,
        aliasHead: i === 0,
        prefRank: pr,
      });
    });
  }
  return rows;
}

/** A human label for a dropdown row: `alias · provider/model`. */
export function modelTargetLabel(row: ModelTargetRow): string {
  return `${row.alias} · ${row.provider}/${row.model}`;
}

/** The editable fields, in tab/cycle order. */
export type DebugField = "turn" | "model" | "temperature" | "system" | "messages";

/** Imperative handle the keymap (task 012) drives. Mirrors {@link ModalHook}. */
export interface DebugFormHook extends ModalHook {
  /** Move focus between fields (forward when delta>0). */
  cycleField: (delta: number) => void;
  /** The currently focused field. */
  field: () => DebugField;
  /** Build the `rerun-from` overrides from the current buffers (for 012/tests). */
  overrides: () => RerunOverrides;
  /**
   * True while the full-text message editor is open. When true the host renders
   * the big {@link MessageEditorOverlay} in the body (overlay) slot instead of
   * the cramped inline row, and the form body is suppressed.
   */
  editorOpen: () => boolean;
  /** The full message list being edited (the resume turn's prefix). */
  editorMessages: () => ConversationMessage[];
  /** Index of the message currently in the edit buffer. */
  editorIndex: () => number;
  /** The live edit buffer for the focused message. */
  editorBuffer: () => string;
}

/**
 * Assemble the `rerun-from` `overrides` payload from the form's edit buffers.
 * Every field is OPTIONAL on the wire (absent ⇒ "keep captured value"), so we
 * only emit a key when the user actually diverged from the captured turn:
 *  - `provider`+`model` (+ `alias`) only when a model target is chosen;
 *  - `temperature` only when a finite number was entered;
 *  - `system` only when it differs from the captured system prompt;
 *  - `messages` (the edited prefix up to & incl. the resume turn) only when any
 *    message text/role was edited.
 */
export function assembleOverrides(input: {
  /** The captured turn we resume from (source of "unchanged" baselines). */
  turn: ConversationTurnModel | undefined;
  /** Chosen model target, or null to keep captured. */
  model: ModelTargetRow | null;
  /** Raw temperature buffer ("" ⇒ unset). */
  temperature: string;
  /** Edited system prompt. */
  system: string;
  /** Edited messages (role + text) up to & incl. the resume turn. */
  messages: ConversationMessage[];
}): RerunOverrides {
  const o: RerunOverrides = {};
  if (input.model) {
    o.provider = input.model.provider;
    o.model = input.model.model;
    o.alias = input.model.alias;
  }
  const t = Number(input.temperature.trim());
  if (input.temperature.trim() !== "" && Number.isFinite(t)) o.temperature = t;
  const capturedSystem = input.turn?.system ?? "";
  if (input.system !== capturedSystem) o.system = input.system;
  const capturedMsgs = input.turn?.messages ?? [];
  const changed =
    input.messages.length !== capturedMsgs.length ||
    input.messages.some((m, i) => {
      const c = capturedMsgs[i];
      return !c || c.text !== m.text || c.role !== m.role;
    });
  if (changed) {
    o.messages = input.messages.map(
      (m): WireMessage => ({ role: m.role, text: m.text }),
    );
  }
  return o;
}

/** Is `name` a single printable char to append to a text buffer (parity w/ Modals)? */
function printable(key: KeyEvent): string | null {
  const n = key.name;
  if (!n) return null;
  if (key.ctrl || key.meta) return null;
  if (n === "space") return " ";
  if (n.length === 1) return n;
  return null;
}

export interface RerunFormProps {
  theme: Theme;
  debug: DebugState | null | undefined;
  /** Interior width (columns) for truncation. */
  width: number;
  /**
   * Run handler — task 012 sends the `rerun-from` frame. Receives the branch
   * coordinate (from `debug.conversation`) + the assembled overrides + the
   * chosen resume turn. STUBBED by 012; the form just assembles & calls it.
   */
  onRun: (payload: {
    invokeid: string;
    nodeId: string;
    visit: number;
    turn: number;
    overrides: RerunOverrides;
  }) => void;
  /** Close the form without running (Esc). */
  onCancel?: () => void;
  /**
   * Notified (reactively) whenever the full-text message editor opens/closes, so
   * the host can render the big {@link MessageEditorOverlay} in the body slot.
   */
  onEditorOpenChange?: (open: boolean) => void;
  /** Receive the imperative {@link DebugFormHook} for the keymap (task 012). */
  ref?: (hook: DebugFormHook) => void;
}

const FIELD_ORDER: DebugField[] = ["turn", "model", "temperature", "system", "messages"];

/**
 * The re-run-from debug form. Renders the turn selector (when >1 turn), the
 * model dropdown (complete alias+target list), temperature, system-prompt, and
 * editable messages, plus a Run hint. Driven entirely from `debug.conversation`
 * + `debug.modelCatalog`. Renders nothing when no conversation is loaded.
 */
export function RerunForm(props: RerunFormProps): import("solid-js").JSX.Element {
  // NOTE: these are plain derivations (not createMemo) so the imperative
  // `handleKey`/`overrides` hooks keep working after the test renderer's
  // reactive root is disposed (memos stop recomputing once disposed).
  const conv = () => props.debug?.conversation ?? null;
  const turns = (): ConversationTurnModel[] => conv()?.turns ?? [];
  const rows = () => modelTargetRows(props.debug?.modelCatalog);

  // --- edit state ---------------------------------------------------------
  // `fieldRaw` is the user's last-requested field; `field()` clamps it to the
  // set actually SHOWN (the turn selector is hidden when the conversation has
  // ≤1 turn, so focus must never rest on it — otherwise up/down hit the
  // `n <= 1` no-op below and the whole form looks unresponsive on open).
  const [fieldRaw, setFieldRaw] = createSignal<DebugField>("turn");
  const [turnSel, setTurnSel] = createSignal(0);
  const [modelSel, setModelSel] = createSignal<number>(-1); // -1 = keep captured
  const [temperature, setTemperature] = createSignal("");
  const [system, setSystem] = createSignal<string | null>(null);
  const [msgs, setMsgs] = createSignal<ConversationMessage[] | null>(null);
  const [msgSel, setMsgSel] = createSignal(0);
  // Full-text message editor overlay. A message's text is too long to edit on the
  // single truncated row, so Enter on a selected message opens this editor showing
  // the WHOLE message (wrapped), edited as a multi-line buffer; Ctrl-S saves back
  // into `msgs`, Esc discards. `editingMsg` is the index being edited, or null.
  const [editingMsg, setEditingMsg] = createSignal<number | null>(null);
  const [editBuf, setEditBuf] = createSignal("");

  /** The turn we resume from (the selected row, default the FIRST turn). */
  const resumeTurn = (): ConversationTurnModel | undefined => {
    const t = turns();
    if (t.length === 0) return undefined;
    const i = Math.min(Math.max(0, turnSel()), t.length - 1);
    return t[i];
  };

  // Effective (possibly-edited) buffers, falling back to the captured turn.
  const sysBuf = () => system() ?? resumeTurn()?.system ?? "";
  const msgBuf = (): ConversationMessage[] => msgs() ?? resumeTurn()?.messages ?? [];
  const chosenModel = (): ModelTargetRow | null => {
    const i = modelSel();
    return i >= 0 ? (rows()[i] ?? null) : null;
  };

  const overrides = (): RerunOverrides =>
    assembleOverrides({
      turn: resumeTurn(),
      model: chosenModel(),
      temperature: temperature(),
      system: sysBuf(),
      messages: msgBuf(),
    });

  /** Fields actually rendered — the turn selector is dropped at ≤1 turn. */
  const shownFields = (): DebugField[] =>
    turns().length > 1 ? FIELD_ORDER : FIELD_ORDER.filter((f) => f !== "turn");

  /** Effective focused field, clamped to the shown set (never the hidden turn). */
  const field = (): DebugField => {
    const shown = shownFields();
    const f = fieldRaw();
    return shown.includes(f) ? f : shown[0]!;
  };

  function cycleField(delta: number): void {
    const shown = shownFields();
    const i = shown.indexOf(field());
    const n = shown.length;
    setFieldRaw(shown[((i + delta) % n + n) % n]!);
  }

  function run(): void {
    const c = conv();
    const t = resumeTurn();
    if (!c || !t) return;
    props.onRun({
      invokeid: c.invokeid,
      nodeId: c.nodeId,
      visit: c.visit,
      turn: t.turn,
      overrides: overrides(),
    });
  }

  /** Open the full-text editor for the currently-selected message. */
  function openMsgEditor(): void {
    const base = msgBuf();
    if (base.length === 0) return;
    const i = Math.min(Math.max(0, msgSel()), base.length - 1);
    setEditBuf(base[i]!.text);
    setEditingMsg(i);
  }

  /** Commit the current edit buffer back into `msgs` (without closing). */
  function commitEditBuf(): void {
    const i = editingMsg();
    const base = msgBuf();
    if (i != null && i < base.length) {
      setMsgs(base.map((m, j) => (j === i ? { ...m, text: editBuf() } : m)));
    }
  }

  /** Save the buffer, then move the editor to message `i` (loading its text). */
  function jumpToMessage(i: number): void {
    commitEditBuf();
    const base = msgBuf();
    if (base.length === 0) return;
    const clamped = ((i % base.length) + base.length) % base.length;
    setEditBuf(base[clamped]!.text);
    setEditingMsg(clamped);
  }

  /** Keys while the full-text message editor overlay is open. */
  function handleEditorKey(key: KeyEvent): boolean {
    const name = key.name;
    if (name === "escape") {
      setEditingMsg(null); // discard the current buffer, close the editor
      return true;
    }
    // Tab / Shift-Tab jump to the next/previous message in the transcript,
    // saving the current buffer first so edits accumulate as you move across
    // the whole conversation before completing.
    if (name === "tab") {
      jumpToMessage((editingMsg() ?? 0) + (key.shift ? -1 : 1));
      return true;
    }
    // Ctrl-R / Ctrl-Enter saves + RUNS the branch straight from the editor.
    if (key.ctrl && (name === "r")) {
      commitEditBuf();
      setEditingMsg(null);
      run();
      return true;
    }
    // Ctrl-S / Ctrl-Enter saves the buffer back and closes the editor.
    if (key.ctrl && (name === "s" || name === "return" || name === "enter")) {
      commitEditBuf();
      setEditingMsg(null);
      return true;
    }
    if (name === "return" || name === "enter") {
      setEditBuf((b) => b + "\n");
      return true;
    }
    if (name === "backspace") {
      setEditBuf((b) => b.slice(0, -1));
      return true;
    }
    const ch = printable(key);
    if (ch != null) setEditBuf((b) => b + ch);
    return true;
  }

  /** Re-baseline edit buffers when the resume turn changes. */
  function rebaseline(): void {
    setSystem(null);
    setMsgs(null);
    setMsgSel(0);
    setEditingMsg(null);
  }

  function handleKey(key: KeyEvent): boolean {
    // The full-text message editor is a focused sub-overlay: while it's open it
    // swallows ALL keys (Tab/Ctrl-R included) until Ctrl-S saves or Esc cancels.
    if (editingMsg() != null) return handleEditorKey(key);
    const name = key.name;
    if (name === "escape") {
      props.onCancel?.();
      return true;
    }
    // Ctrl-R (or Ctrl-Enter) = Run from any field.
    if (key.ctrl && (name === "r" || name === "return" || name === "enter")) {
      run();
      return true;
    }
    // Tab cycles fields.
    if (name === "tab") {
      cycleField(key.shift ? -1 : 1);
      return true;
    }

    switch (field()) {
      case "turn": {
        const n = turns().length;
        if (n <= 1) return true;
        if (name === "up" || name === "k" || name === "left") {
          setTurnSel((i) => (i - 1 + n) % n);
          rebaseline();
          return true;
        }
        if (name === "down" || name === "j" || name === "right") {
          setTurnSel((i) => (i + 1) % n);
          rebaseline();
          return true;
        }
        return true;
      }
      case "model": {
        const n = rows().length;
        if (name === "up" || name === "k" || name === "left") {
          setModelSel((i) => (i <= -1 ? n - 1 : i - 1));
          return true;
        }
        if (name === "down" || name === "j" || name === "right") {
          setModelSel((i) => (i >= n - 1 ? -1 : i + 1));
          return true;
        }
        return true;
      }
      case "temperature": {
        if (name === "backspace") {
          setTemperature((b) => b.slice(0, -1));
          return true;
        }
        const ch = printable(key);
        if (ch != null && /[0-9.\-]/.test(ch)) setTemperature((b) => b + ch);
        return true;
      }
      case "system": {
        if (name === "backspace") {
          setSystem((b) => (b ?? sysBuf()).slice(0, -1));
          return true;
        }
        if (name === "return" || name === "enter") {
          setSystem((b) => (b ?? sysBuf()) + "\n");
          return true;
        }
        const ch = printable(key);
        if (ch != null) setSystem((b) => (b ?? sysBuf()) + ch);
        return true;
      }
      case "messages": {
        const n = msgBuf().length;
        if (name === "up" || name === "k") {
          setMsgSel((i) => (n ? (i - 1 + n) % n : 0));
          return true;
        }
        if (name === "down" || name === "j") {
          setMsgSel((i) => (n ? (i + 1) % n : 0));
          return true;
        }
        // Enter opens the full-text editor for the selected message (inline
        // char-editing was cramped + truncated — the editor shows the whole text).
        if (name === "return" || name === "enter") {
          openMsgEditor();
          return true;
        }
        return true;
      }
    }
    return true;
  }

  const hook: DebugFormHook = {
    isOpen: () => conv() != null,
    handleKey,
    cycleField,
    field,
    overrides,
    editorOpen: () => editingMsg() != null,
    editorMessages: msgBuf,
    editorIndex: () => editingMsg() ?? 0,
    editorBuffer: editBuf,
  };
  props.ref?.(hook);
  // Notify the host (reactively) when the editor opens/closes so it can mount the
  // big body-slot overlay. Guarded so the test renderer (no callback) is unaffected.
  createEffect(() => props.onEditorOpenChange?.(editingMsg() != null));

  // --- rendering ----------------------------------------------------------
  const fg = (k: Parameters<Theme["fg"]>[0]) => props.theme.fg(k);
  const cut = (s: string) => truncateDisplay(s, Math.max(1, props.width));
  const active = (f: DebugField) => field() === f;
  // Highlight a selected row with a box-level background BAR (parity with
  // ConversationMenu / LivePanel). A span-level `inverse` leaves background
  // residue on previously-hovered rows when the cursor moves; repainting the
  // full row width with the box bg each render avoids it.
  const rowBg = (sel: boolean) =>
    (sel ? props.theme.bg("selection-bg") : props.theme.bg("overlay-bg")) ?? undefined;
  const head = (label: string, f: DebugField) => (
    <text>
      <span style={{ fg: active(f) ? fg("phase-current") : fg("title"), bold: true }}>
        {cut(`${active(f) ? "▸ " : "  "}${label}`)}
      </span>
    </text>
  );

  return (
    // While the full-text editor is open the host renders the big
    // MessageEditorOverlay in the body slot, so the small form here collapses to
    // nothing (otherwise it would double-render in the bottom modal strip).
    <Show when={conv() && editingMsg() == null}>
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
            {cut(` ⤴ Re-run from here · turn ${resumeTurn()?.turn ?? 0} `)}
          </span>
        </text>

        {/* turn selector (only when >1 turn) */}
        <Show when={turns().length > 1}>
          {head("Turn", "turn")}
          <box flexDirection="column">
            <For each={turns()}>
              {(t, i) => {
                const sel = () => i() === turnSel();
                return (
                  <box width="100%" backgroundColor={rowBg(sel())}>
                    <text>
                      <span style={{ fg: fg(sel() ? "phase-current" : "phase-upcoming") }}>
                        {cut(`     ${sel() ? "▸ " : "  "}turn ${t.turn} (${t.model})`)}
                      </span>
                    </text>
                  </box>
                );
              }}
            </For>
          </box>
        </Show>

        {/* model / provider dropdown — COMPLETE alias + target list */}
        {head("Model", "model")}
        <box flexDirection="column">
          <box width="100%" backgroundColor={rowBg(modelSel() < 0)}>
            <text>
              <span style={{ fg: fg(modelSel() < 0 ? "phase-current" : "phase-upcoming") }}>
                {cut(`     ${modelSel() < 0 ? "▸ " : "  "}(keep captured: ${resumeTurn()?.model ?? "?"})`)}
              </span>
            </text>
          </box>
          <For each={rows()}>
            {(r, i) => {
              const sel = () => i() === modelSel();
              return (
                <box width="100%" backgroundColor={rowBg(sel())}>
                  <text>
                    <span
                      style={{
                        fg: sel()
                          ? fg("phase-current")
                          : r.aliasHead
                            ? fg("md-bold")
                            : fg("phase-upcoming"),
                      }}
                    >
                      {cut(
                        `     ${sel() ? "▸ " : "  "}${modelTargetLabel(r)}${
                          r.prefRank >= 0 ? `  [pref ${r.prefRank}]` : ""
                        }`,
                      )}
                    </span>
                  </text>
                </box>
              );
            }}
          </For>
        </box>

        {/* temperature */}
        {head("Temperature", "temperature")}
        <text>
          <span style={{ fg: fg("metric") }}>{"     "}</span>
          <span style={{ fg: fg("md-bold") }}>{temperature() || "(default)"}</span>
          <Show when={active("temperature")}>
            <span style={{ fg: fg("phase-current") }}>{"█"}</span>
          </Show>
        </text>

        {/* system prompt */}
        {head("System prompt", "system")}
        <box flexDirection="column">
          <For each={sysBuf().split("\n")}>
            {(ln, i) => {
              const cursorHere = () =>
                active("system") && i() === sysBuf().split("\n").length - 1;
              return (
                <text>
                  {/* Leave a column for the cursor on its line so a long
                      single-line prompt doesn't push `█` onto the next row. */}
                  <span style={{ fg: fg("phase-upcoming") }}>
                    {truncateDisplay(
                      `     ${ln}`,
                      Math.max(1, props.width - (cursorHere() ? 1 : 0)),
                    )}
                  </span>
                  <Show when={cursorHere()}>
                    <span style={{ fg: fg("phase-current") }}>{"█"}</span>
                  </Show>
                </text>
              );
            }}
          </For>
        </box>

        {/* messages */}
        {head("Messages", "messages")}
        <box flexDirection="column">
          <For each={msgBuf()}>
            {(m, i) => {
              const sel = () => active("messages") && i() === msgSel();
              return (
                <box width="100%" backgroundColor={rowBg(sel())}>
                  <text>
                    <span style={{ fg: fg(sel() ? "phase-current" : "phase-upcoming") }}>
                      {cut(`     ${sel() ? "▸ " : "  "}[${m.role}] ${m.text}`)}
                    </span>
                  </text>
                </box>
              );
            }}
          </For>
        </box>

        <text>
          <span style={{ fg: fg("border-dim") }}>
            {cut("   Tab field · ↑/↓ move · Enter edit msg · type to edit · Ctrl-R run · Esc cancel")}
          </span>
        </text>
      </box>
    </Show>
  );
}

// ===========================================================================
// Full-screen message editor overlay (issue: bigger modal + jump across msgs)
// ===========================================================================

export interface MessageEditorOverlayProps {
  theme: Theme;
  /** The live re-run form hook (source of the editor's message/buffer state). */
  hook: DebugFormHook;
  /** Body interior width (columns). */
  width: number;
  /** Body interior height (rows) — the overlay fills the whole body. */
  height: number;
}

/**
 * The BIG, full-body message editor. Rendered in the host's body (overlay) slot
 * while {@link DebugFormHook.editorOpen} is true, so the whole (possibly long)
 * message is visible and editable instead of the cramped inline row. A left rail
 * lists every message in the transcript (current one highlighted) so the user can
 * Tab/Shift-Tab to jump across messages and edit several before completing. All
 * key handling stays in the form's `handleKey` (modal tier); this is the read
 * surface only.
 */
export function MessageEditorOverlay(
  props: MessageEditorOverlayProps,
): import("solid-js").JSX.Element {
  const fg = (k: Parameters<Theme["fg"]>[0]) => props.theme.fg(k);
  const cut = (s: string, w = props.width) => truncateDisplay(s, Math.max(1, w));
  const msgs = () => props.hook.editorMessages();
  const idx = () => props.hook.editorIndex();
  const cur = () => msgs()[idx()];
  // Wrap the edit buffer to the editor pane width; ≥1 line so the cursor shows.
  const lines = (): string[] => {
    const ls = wrapDisplay(Math.max(8, props.width - 4), "", props.hook.editorBuffer());
    return ls.length ? ls : [""];
  };
  const rowBg = (sel: boolean) =>
    (sel ? props.theme.bg("selection-bg") : props.theme.bg("overlay-bg")) ?? undefined;

  return (
    <box
      border
      borderStyle="rounded"
      borderColor={fg("border-focus")}
      backgroundColor={props.theme.bg("overlay-bg") ?? undefined}
      flexDirection="column"
      width="100%"
      height={props.height}
    >
      <text>
        <span style={{ fg: fg("title"), bold: true }}>
          {cut(
            ` ✎ Edit message ${idx() + 1}/${msgs().length} [${cur()?.role ?? ""}] `,
          )}
        </span>
      </text>

      {/* message rail — every message, current one highlighted (jump targets). */}
      <box flexDirection="column" flexShrink={0}>
        <For each={msgs()}>
          {(m, i) => {
            const sel = () => i() === idx();
            return (
              <box width="100%" backgroundColor={rowBg(sel())}>
                <text>
                  <span style={{ fg: fg(sel() ? "phase-current" : "phase-upcoming") }}>
                    {cut(`  ${sel() ? "▸ " : "  "}${i() + 1}. [${m.role}] ${m.text}`)}
                  </span>
                </text>
              </box>
            );
          }}
        </For>
      </box>

      {/* the big edit area for the focused message. */}
      <box
        flexGrow={1}
        flexDirection="column"
        backgroundColor={props.theme.bg("selection-bg") ?? undefined}
      >
        <For each={lines()}>
          {(ln, i) => (
            <text>
              <span style={{ fg: fg("md-bold") }}>{cut(`  ${ln}`)}</span>
              <Show when={i() === lines().length - 1}>
                <span style={{ fg: fg("phase-current") }}>{"█"}</span>
              </Show>
            </text>
          )}
        </For>
      </box>

      <text>
        <span style={{ fg: fg("border-dim") }}>
          {cut(
            "   type to edit · Enter newline · Tab/⇧Tab switch message · Ctrl-S done · Ctrl-R run · Esc cancel",
          )}
        </span>
      </text>
    </box>
  );
}

// ===========================================================================
// Breakpoint controls (task 011) — the paused-at-turn next/back/continue surface
// ===========================================================================

/** Pure: is the run parked at an LLM turn gate (next/back/continue apply)? */
export function isPausedAtTurn(debug: DebugState | null | undefined): boolean {
  return Boolean(debug && debug.mode === "paused-at-turn");
}

/** The breakpoint-controls legend (pure, testable). Shows the turn pointer + armed. */
export function breakpointControlsText(debug: DebugState | null | undefined): string {
  const idx = debug?.turnIndex;
  const where = idx != null ? `turn ${idx}` : "—";
  const armed = debug?.breakpointArmed ? " · armed" : "";
  return `  ◆ BREAK @ ${where}${armed}  —  n next · b back · c continue`;
}

export interface BreakpointControlsProps {
  theme: Theme;
  debug: DebugState | null | undefined;
  width: number;
}

/**
 * The breakpoint-controls row. Renders only while paused at a turn gate (or
 * while a breakpoint is armed, awaiting the gate). The KEYS (n/b/c →
 * turn-next/turn-back/continue) are bound by task 012; this is the read surface
 * showing the current `turnIndex` + armed state.
 */
export function BreakpointControls(props: BreakpointControlsProps): import("solid-js").JSX.Element {
  return (
    <Show when={isPausedAtTurn(props.debug) || props.debug?.breakpointArmed}>
      <box
        height={1}
        flexShrink={0}
        backgroundColor={props.theme.bg("status/waiting") ?? undefined}
      >
        <text>
          <span style={{ fg: props.theme.fg("status/waiting"), bold: true }}>
            {truncateDisplay(breakpointControlsText(props.debug), Math.max(0, props.width))}
          </span>
        </text>
      </box>
    </Show>
  );
}
