/**
 * Central keyboard map — the single `useKeyboard` dispatcher wiring ALL input
 * (focus / nav / scroll / view-switch / maximize / control / Esc / Ctrl-C) to
 * the store actions, the Shell/Inspector controls, and the agent control
 * back-channel. Parity target: the JLine `escapement.tui/input-loop` +
 * `dispatch-key` + `handle-debug-key!`.
 *
 * Mode precedence (see dispatch.ts `resolveMode`): modal > overlay >
 * mission-control. OpenTUI already handles raw mode, ESC disambiguation, and the
 * Kitty protocol, so we only read `key.name` / modifiers — we do NOT reimplement
 * the JLine `key-from-bytes` byte parser.
 *
 * Full mission-control key set:
 *   Tab          focus cycle (LIVE↔LOG)
 *   m            maximize toggle
 *   Esc          restore split (if maximized) else ui-interrupt
 *   Ctrl-C       ui-quit (+ teardown)
 *   ?            toggle inspector overlay  (host-owned, see note)
 *   j/k/g/G      LIVE: move drill-in cursor   LOG: scroll
 *   PgUp/PgDn    scroll focused pane by a page
 *   Home/End     scroll focused pane to top / bottom
 *   Enter        LIVE: open selected row's transcript   LOG: open invocation
 *   s/c/p/P      step / continue / pause / arm   (control back-channel)
 *   v            visualize                       (control back-channel — task 014)
 *   o            open conversation action menu for the selected LIVE row (task 012)
 *   n/b/c        paused-at-turn: turn-next / turn-back / continue (task 012)
 *
 * Time-travel debugger (task 012): `o` opens the conversation action menu
 * (Transcript / Re-run / Break) for the selected LIVE LLM row; the menu + the
 * re-run form route at the MODAL tier (above mission-control) via the composed
 * `modal` hook so their nav/editing never leaks. The `n`/`b`/`c` keys walk /
 * release the turn gate ONLY while paused-at-turn; otherwise `n`/`b` no-op and
 * `c` keeps its plain `continue` meaning. See `tasks/012-results.md` for the
 * full keymap (key → action → tier).
 *
 * Overlay (inspector open) key set is delegated to InspectorControls; pager vs
 * list sub-routing keys off `inspector.inPager()`.
 */

import type { ShellControls } from "../ui/Shell";
import type { InspectorControls } from "../ui/Inspector";
import {
  resolveMode,
  type ControlOp,
  type KeyEvent,
  type ModalHook,
} from "./dispatch";

/** A LIVE drill-in target the keymap resolves from the selected cursor row. */
export interface LiveCursor {
  /** Current cursor row index (clamped against the visible row count). */
  row: () => number;
  setRow: (updater: (r: number) => number) => void;
  /** Visible LIVE row count (for clamping g/G and j/k). */
  rowCount: () => number;
  /** invokeid for the row at the current cursor (for Enter drill-in), or null. */
  targetInvokeid: () => string | null;
}

/** LOG pane scroll model (0 = newest at bottom; increasing = older). */
export interface LogScrollModel {
  offset: () => number;
  setOffset: (updater: (o: number) => number) => void;
  /** Max scroll offset = max(0, scrollback.length - visibleRows). */
  maxOffset: () => number;
  /** Visible rows (one page). */
  pageRows: () => number;
  /** Open the invocation for the entry currently at the cursor / bottom. */
  openSelected: () => void;
}

export interface KeybindingDeps {
  /** Shell focus / maximize handle (task 008). */
  shell: () => ShellControls | undefined;
  /** Inspector overlay handle (task 011); only present while mounted (open). */
  inspector: () => InspectorControls | undefined;
  /** Host-owned inspector open signal (`?` toggles it — see note below). */
  inspectorOpen: () => boolean;
  toggleInspector: () => void;
  /** Open the inspector to a specific invocation's transcript (LIVE/LOG Enter
   *  drill-in). The host opens the overlay then drills in (Inspector mounts only
   *  while open, so the host owns the open-then-drill sequencing). */
  openTranscript: (invokeid: string) => void;
  /** Open the inspector straight to the session-wide Artifacts view (`a`). */
  openArtifactsView: () => void;

  /** LIVE drill-in cursor model. */
  liveCursor: LiveCursor;
  /** LOG scroll model. */
  logScroll: LogScrollModel;

  /** Forward a control op to the agent (s/c/p/P/v + Esc/Ctrl-C). */
  control: (op: ControlOp) => void;
  /** Send `visualize` — task 014 owns the actual call; routed as a control op. */
  visualize?: () => void;

  /** Tear down the renderer + transport on Ctrl-C / ui-quit. */
  quit: () => void;

  /** Optional human-modal hook (task 013). When absent, the modal tier is inert. */
  modal?: ModalHook;

  /** Time-travel debugger bindings (task 012). When absent these keys no-op. */
  debug?: DebugKeybindings;
}

/**
 * Mission-control-tier debugger bindings (task 012). The conversation menu + the
 * re-run form are routed via the modal {@link ModalHook} (composed in main.tsx);
 * these are the keys that LIVE at mission-control tier: opening the menu for the
 * selected row, and the paused-at-turn breakpoint walk (next / back / continue).
 */
export interface DebugKeybindings {
  /**
   * Open the conversation action menu for the currently-selected row (the LIVE
   * cursor's LLM row in the LIVE pane). No-op when there is no resolvable target.
   * Called only at mission-control tier; once open the menu captures keys at the
   * modal tier (via the composed `modal` hook).
   */
  openMenu: () => void;
  /** Is the agent paused at an LLM turn gate? (gates the n/b/c keys.) */
  pausedAtTurn: () => boolean;
  /** Walk the parked turn pointer forward (`turn-next`). */
  turnNext: () => void;
  /** Walk the parked turn pointer backward (`turn-back`). */
  turnBack: () => void;
  /** Resume from the turn gate (`continue`). */
  turnContinue: () => void;
}

/** Is this key Ctrl-C? */
function isCtrlC(key: KeyEvent): boolean {
  return !!key.ctrl && key.name === "c";
}

function isEnter(key: KeyEvent): boolean {
  return key.name === "return" || key.name === "enter";
}

/**
 * Build the central `useKeyboard` handler. Pass the returned fn straight to
 * OpenTUI's `useKeyboard`.
 */
export function makeKeyHandler(deps: KeybindingDeps): (key: KeyEvent) => void {
  /** Route the inspector-overlay tier (task 011 controls). */
  function handleOverlay(key: KeyEvent, insp: InspectorControls): void {
    if (insp.inPager()) {
      // Pager: scroll group + two-stage Esc/back (Esc closes pager, not overlay).
      switch (key.name) {
        case "j":
        case "down":
          insp.scroll.lineDown();
          break;
        case "k":
        case "up":
          insp.scroll.lineUp();
          break;
        case "pagedown":
        case "b":
          insp.scroll.pageDown();
          break;
        case "pageup":
          insp.scroll.pageUp();
          break;
        case "g":
        case "home":
          insp.scroll.top();
          break;
        case "G":
        case "end":
          insp.scroll.bottom();
          break;
        case "escape":
        case "h":
        case "backspace":
          insp.back(); // pops the pager (two-stage: pager → list → close)
          break;
      }
      return;
    }
    // List view: views / nav / drill / artifacts / pop.
    switch (key.name) {
      case "1":
        insp.setView("invocations");
        break;
      case "2":
        insp.setView("chart");
        break;
      case "3":
        insp.setView("status");
        break;
      case "4":
      case "a":
        insp.setView("artifacts");
        break;
      case "y":
        insp.copySelectedPath();
        break;
      case "Y":
        insp.copyDir();
        break;
      case "j":
      case "down":
        insp.cursorDown();
        break;
      case "k":
      case "up":
        insp.cursorUp();
        break;
      case "g":
      case "home":
        insp.cursorTop();
        break;
      case "G":
      case "end":
        insp.cursorBottom();
        break;
      case "o":
        insp.openArtifacts();
        break;
      case "h":
      case "backspace":
        insp.back(); // pops artifact list → closes overlay
        break;
      case "escape":
        insp.close();
        break;
      default:
        if (isEnter(key)) insp.enter();
    }
  }

  /** Route the mission-control tier (focus model + control keys). */
  function handleMissionControl(key: KeyEvent, shell: ShellControls): void {
    const live = () => shell.focus() === "live";

    switch (key.name) {
      case "tab":
        shell.toggleFocus();
        return;
      case "m":
        shell.toggleMaximize();
        return;
      case "escape":
        // Esc restores a maximized split, otherwise interrupts the agent.
        if (shell.maximized()) shell.setMaximized(false);
        else deps.control("ui-interrupt");
        return;

      // --- scroll the focused pane (page / extremes) ---
      case "pageup":
        scrollPane(shell, "page-up");
        return;
      case "pagedown":
        scrollPane(shell, "page-down");
        return;
      case "home":
        scrollPane(shell, "top");
        return;
      case "end":
        scrollPane(shell, "bottom");
        return;

      // --- j/k/g/G: LIVE cursor move, LOG scroll ---
      case "j":
      case "down":
        if (live()) moveLiveCursor("down");
        else scrollPane(shell, "down");
        return;
      case "k":
      case "up":
        if (live()) moveLiveCursor("up");
        else scrollPane(shell, "up");
        return;
      case "g":
        if (live()) moveLiveCursor("top");
        else scrollPane(shell, "top");
        return;
      case "G":
        if (live()) moveLiveCursor("bottom");
        else scrollPane(shell, "bottom");
        return;

      // --- control keys (forwarded to the agent) ---
      case "s":
        deps.control("step");
        return;
      case "c":
        // `c` = continue. When parked at an LLM turn gate this resumes the turn
        // gate (`turn-next`/`turn-back` walk; `continue` releases) — agent-side
        // `continue` is routed to whichever gate is engaged (wire §9), so the
        // same key serves both the per-event step gate and the turn gate.
        if (deps.debug?.pausedAtTurn()) deps.debug.turnContinue();
        else deps.control("continue");
        return;
      case "p":
        deps.control("pause");
        return;
      case "P":
        deps.control("arm");
        return;
      case "v":
        deps.visualize?.();
        return;
      case "a":
        deps.openArtifactsView();
        return;

      // --- conversation action menu (Transcript / Re-run / Break) ------------
      // `o` ("open actions") opens the menu for the selected LIVE LLM row. Item 1
      // is Transcript, so the prior Enter drill-in stays one keystroke away while
      // Enter itself keeps its direct-drill behavior (acceptance criterion).
      case "o":
        if (live()) deps.debug?.openMenu();
        return;

      // --- paused-at-turn breakpoint walk (only while parked at a turn gate) --
      case "n":
        if (deps.debug?.pausedAtTurn()) deps.debug.turnNext();
        return;
      case "b":
        if (deps.debug?.pausedAtTurn()) deps.debug.turnBack();
        return;
    }

    if (isEnter(key)) {
      if (live()) {
        const iid = deps.liveCursor.targetInvokeid();
        if (iid) deps.openTranscript(iid);
      } else {
        deps.logScroll.openSelected();
      }
    }
  }

  function moveLiveCursor(dir: "up" | "down" | "top" | "bottom"): void {
    const n = deps.liveCursor.rowCount();
    const clamp = (r: number) => (n <= 0 ? 0 : Math.max(0, Math.min(n - 1, r)));
    deps.liveCursor.setRow((r) => {
      switch (dir) {
        case "up":
          return clamp(r - 1);
        case "down":
          return clamp(r + 1);
        case "top":
          return 0;
        case "bottom":
          return clamp(n - 1);
      }
    });
  }

  function scrollPane(
    shell: ShellControls,
    dir: "up" | "down" | "page-up" | "page-down" | "top" | "bottom",
  ): void {
    // Only the LOG pane has a scroll model here; LIVE auto-follows (scrollbox).
    if (shell.focus() !== "log") return;
    const m = deps.logScroll;
    const max = m.maxOffset();
    const page = Math.max(1, m.pageRows());
    const clamp = (o: number) => Math.max(0, Math.min(max, o));
    // offset increases UP (toward older entries), decreases toward the tail.
    switch (dir) {
      case "up":
        m.setOffset((o) => clamp(o + 1));
        break;
      case "down":
        m.setOffset((o) => clamp(o - 1));
        break;
      case "page-up":
        m.setOffset((o) => clamp(o + page));
        break;
      case "page-down":
        m.setOffset((o) => clamp(o - page));
        break;
      case "top":
        m.setOffset(() => max);
        break;
      case "bottom":
        m.setOffset(() => 0);
        break;
    }
  }

  return (key: KeyEvent) => {
    // Ctrl-C always wins: quit cleanly regardless of mode.
    if (isCtrlC(key)) {
      key.preventDefault?.();
      deps.control("ui-quit");
      deps.quit();
      return;
    }

    // `?` toggles the inspector overlay. It MUST stay host-owned: the Inspector
    // (and its controls ref) is unmounted while closed, so `?` can't live inside
    // it. (Task 011 note.)
    if (key.name === "?") {
      deps.toggleInspector();
      return;
    }

    const mode = resolveMode({
      modalOpen: deps.modal?.isOpen() ?? false,
      overlayOpen: deps.inspectorOpen(),
    });

    switch (mode) {
      case "overlay": {
        const insp = deps.inspector();
        if (insp) handleOverlay(key, insp);
        return;
      }
      case "modal": {
        deps.modal?.handleKey(key);
        return;
      }
      case "mission-control": {
        const shell = deps.shell();
        if (shell) handleMissionControl(key, shell);
        return;
      }
    }
  };
}
