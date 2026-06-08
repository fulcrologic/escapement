/**
 * Escapement OpenTUI sidecar — application entry.
 *
 * Builds the Theme ONCE (task 007 — the per-invokeid hue palette must persist
 * for the whole session), creates the domain store (task 006) wired to the
 * transport EventSource (live WS or offline replay, task 005), drives a 1 Hz
 * elapsed clock, and renders the responsive layout Shell (task 008).
 *
 * The LIVE / LOG panes + inspector overlay are filled by tasks 009 / 010 / 011;
 * here they are empty placeholder slots so the shell boots and reflows on a
 * replayed fixture with no pane content (and no crash).
 *
 * Run:
 *   bun run dev                                                  # live WS
 *   OPENTUI_REPLAY=test/fixtures/haiku-sample.jsonl bun run dev  # offline replay
 */

import { render, useKeyboard, useRenderer, useTerminalDimensions } from "@opentui/solid";
import { createSignal, onCleanup, onMount, Show, type JSX } from "solid-js";
import { createEventSource } from "./transport";
import { createDomainStore } from "./domain/solid-store";
import { makeThemeFromEnv, type Theme } from "./domain/theme";
import { Shell, type ShellControls } from "./ui/Shell";
import { HEADER_H, FOOTER_H } from "./ui/layout";
import { LogPane } from "./ui/LogPane";
import { LivePanel } from "./ui/LivePanel";
import { createTick } from "./ui/live/tick";
import { Inspector, type InspectorControls } from "./ui/Inspector";
import { copyToClipboard } from "./ui/inspector/artifacts";
import {
  debugFromState,
  RerunForm,
  MessageEditorOverlay,
  BreakpointControls,
  BranchErrorBanner,
  isPausedAtTurn,
  type DebugFormHook,
} from "./ui/Debugger";
import { liveRowIndex } from "./ui/live/rows";
import { liveGroups } from "./domain/solid-store";
import { liveCursorInvokeid, liveCursorSession } from "./ui/LivePanel";
import { liveNodeRef, liveNodeRefForSession } from "./domain/aggregate";
import { makeKeyHandler, type LiveCursor, type LogScrollModel } from "./input/keybindings";
import { makeControlDispatch, makeDebugDispatch, type ModalHook } from "./input/dispatch";
import { Modals } from "./ui/Modals";
import { ConversationMenu, type ConversationMenuHook } from "./ui/ConversationMenu";

/** Best-effort chart name from the env (the sidecar parent may set it). */
function chartNameFromEnv(): string {
  return process.env["OPENTUI_CHART"] ?? "session";
}

/** Short session id from the env (the sidecar parent sets it, task 004). */
function sessionShortFromEnv(): string {
  return process.env["OPENTUI_SESSION_SHORT"] ?? "";
}

/** Full (opaque) session id — the source session a `rerun-from` branches from.
 *  The bb sidecar exports `ESCAPEMENT_SESSION_ID` (see tui/opentui/sidecar.clj).
 *  Empty in replay/dev when unset (the back-channel is a no-op there anyway). */
function sessionIdFromEnv(): string {
  return process.env["ESCAPEMENT_SESSION_ID"] ?? "";
}

/** Shared session-dir (set by the bb sidecar parent, task 004) for artifacts.
 *  The sidecar exports `ESCAPEMENT_SESSION_DIR` (see opentui_sidecar.clj);
 *  `OPENTUI_SESSION_DIR` is accepted as a dev/replay fallback. */
function sessionDirFromEnv(): string | null {
  return (
    process.env["ESCAPEMENT_SESSION_DIR"] ??
    process.env["OPENTUI_SESSION_DIR"] ??
    null
  );
}

/**
 * Keep-alive "finished" banner (#2 / Task 002). Shown once the agent pushes
 * `run-finished`: the renderer stays live so the last frame + inspector remain
 * browsable; this single-row banner tells the user how to quit. Parity with the
 * JLine `await-quit!` scrollback notice ("press Ctrl-C to quit").
 */
function FinishedBanner(props: { theme: Theme; width: number }): JSX.Element {
  const fg = props.theme.fg("status/done");
  return (
    <box
      width={props.width}
      height={1}
      backgroundColor={props.theme.fg("border-dim") ?? undefined}
    >
      <text wrapMode="none">
        <span style={{ fg, bold: true }}>
          {" ✓ Run finished — press Ctrl-C to quit "}
        </span>
      </text>
    </box>
  );
}

const App = () => {
  const renderer = useRenderer();
  const dims = useTerminalDimensions();

  // Theme + store + source are built ONCE, outside any reactive scope.
  const theme = makeThemeFromEnv();
  const source = createEventSource();
  const store = createDomainStore(source);

  const startTs = Date.now();
  const [elapsedMs, setElapsedMs] = createSignal(0);

  // Frame tick (~30fps) driving the LIVE shimmer — deterministic, not wall-clock.
  const tick = createTick();

  // LOG pane scroll/cursor state (signal-driven; the keymap below drives them).
  // `logScroll` = entries scrolled UP from the tail (0 = newest at bottom).
  const [logScroll, setLogScroll] = createSignal(0);
  const [logCursor, setLogCursor] = createSignal<number | null>(null);
  // LIVE drill-in cursor (index into the visible LIVE rows).
  const [liveCursorRow, setLiveCursorRow] = createSignal(0);
  // Visible interior rows for the LOG pane: term height minus header, footer,
  // and the pane's own 2 border rows. Clamped non-negative.
  const logHeight = () => Math.max(0, dims().height - HEADER_H - FOOTER_H - 2);

  // LIVE visible-row targets (parallel to the rendered rows) for cursor → drill-in.
  const liveTargets = () => liveRowIndex(liveGroups(store.state.live));
  const logMaxOffset = () =>
    Math.max(0, store.state.scrollback.length - logHeight());

  // Known LOG lane names (non-invokeid sources). Anything else is an invokeid.
  const LOG_LANES = new Set(["chart", "human", "debug", "error", "viz"]);

  // Scroll indicators for the LIVE / LOG frames (task 010's requested seam).
  // LIVE auto-follows (scrollbox) so it has no discrete pos/total here.
  const logScrollIndicator = () => {
    const total = store.state.scrollback.length;
    if (total === 0) return { pos: 0, total: 0 };
    const pos = Math.max(0, total - logScroll());
    return { pos, total };
  };

  // task 012 will drive these via useKeyboard; we hold the handle here.
  let controls: ShellControls | undefined;
  let inspector: InspectorControls | undefined;
  // Mirror of the inspector's open state so the Shell's overlay slot returns
  // null (⇒ LIVE/LOG split shows) when the inspector is closed.
  const [inspectorOpen, setInspectorOpen] = createSignal(false);
  const sessionDir = sessionDirFromEnv();
  // Copy through the renderer's native OSC-52 path (coordinated with the render
  // output); fall back to a raw stdout write if it throws/returns false.
  const copyText = (text: string) => {
    try {
      if (renderer.copyToClipboardOSC52(text)) return;
    } catch {
      // fall through to the stdout writer
    }
    copyToClipboard(text);
  };
  // Inspector overlay interior height (term minus header/footer/borders).
  const overlayHeight = () => Math.max(1, dims().height - HEADER_H - FOOTER_H - 2);

  // Keep-alive (#2 / Task 002): when the agent reaches final-config it pushes a
  // `run-finished` control frame. We must NOT tear down — instead hold the last
  // live frame and show a "press Ctrl-C to quit" banner so a fast/erroring chart
  // leaves a browsable frame on screen. The renderer already stays alive on WS
  // close (`exitOnCtrlC:false`, no auto-destroy), so we only flip a flag here;
  // the process exits on the user's Ctrl-C (keybindings → `quit`).
  const [finished, setFinished] = createSignal(false);

  onMount(() => {
    source.start();
    const offControl = source.onControl((op) => {
      if (op === "run-finished") setFinished(true);
    });
    const clock = setInterval(() => setElapsedMs(Date.now() - startTs), 1000);
    onCleanup(() => {
      clearInterval(clock);
      offControl();
      store.dispose();
    });
  });

  // Forward control / interrupt / quit ops to the agent over the back-channel
  // (no-op + false on a replay source). Esc → ui-interrupt, Ctrl-C → ui-quit,
  // s/c/p/P → step/continue/pause/arm.
  const control = makeControlDispatch(source);
  // Parametrized debugger senders (request-conversation / rerun-from, task 012).
  const debugDispatch = makeDebugDispatch(source);
  const sessionId = sessionIdFromEnv();

  // Time-travel debugger hooks (task 012). The conversation menu + the re-run
  // form are both MODAL-tier surfaces (above mission-control); their imperative
  // handles are captured via `ref` and dispatched among in the composed modal
  // hook below. `debugFormOpen` mirrors the form's open state so its render slot
  // mounts only while active.
  let menuHook: ConversationMenuHook | undefined;
  let formHook: DebugFormHook | undefined;
  // The re-run form mounts only while active; gate its render slot on this. The
  // ConversationMenu self-hides when closed, so it needs no render gate — the
  // keymap reads `menuHook.isOpen()` (a reactive component signal) directly.
  const [debugFormOpen, setDebugFormOpen] = createSignal(false);
  // Mirrors the re-run form's full-text message editor open state, so the body
  // (overlay) slot can render the big MessageEditorOverlay reactively.
  const [msgEditorOpen, setMsgEditorOpen] = createSignal(false);
  // The CHILD session-id of the row a re-run was launched from (the multiplex
  // sibling under the cursor at `o`-menu time). Captured at `onMenuRerun` and
  // shipped as the `rerun-from` session-id so the agent seeds the branch from
  // THAT session's node-entry checkpoint (a multi-session run keys node-entry
  // checkpoints by child session, not the root). Null ⇒ fall back to root.
  const [rerunSession, setRerunSession] = createSignal<string | null>(null);

  // Human-input modal hook (task 013). The Modals component sets this via ref;
  // the keymap consults it as the top-precedence tier (when no overlay is open).
  let modalHook: ModalHook | undefined;
  // Locally dismissed prompt-id: the store keeps `state.prompt` set after we
  // answer (the agent emits a `human-input/answer` event but not a clearing
  // frame), so we hide the modal once its answer/cancel is sent.
  const [dismissedPromptId, setDismissedPromptId] = createSignal<string | null>(null);
  // The currently OPEN prompt: state.prompt unless we've dismissed that id.
  const openPrompt = () => {
    const p = store.state.prompt;
    if (!p) return null;
    return p["prompt-id"] === dismissedPromptId() ? null : p;
  };
  // Send the wire `answer` message over the back-channel (no-op under replay).
  const sendAnswer = (promptId: string, value: unknown) => {
    source.send({ kind: "answer", "prompt-id": promptId, value });
    setDismissedPromptId(promptId);
  };
  const sendCancel = (promptId: string) => {
    source.send({ kind: "answer", "prompt-id": promptId, cancelled: true });
    setDismissedPromptId(promptId);
  };

  // LIVE drill-in cursor model (clamped against the visible row count).
  const liveCursorModel: LiveCursor = {
    row: liveCursorRow,
    setRow: (u) => setLiveCursorRow((r) => u(r)),
    rowCount: () => liveTargets().length,
    targetInvokeid: () => liveTargets()[liveCursorRow()]?.invokeid ?? null,
  };

  // LOG scroll model (offset increases toward older entries; 0 = tail).
  const logScrollModel: LogScrollModel = {
    offset: logScroll,
    setOffset: (u) => setLogScroll((o) => u(o)),
    maxOffset: logMaxOffset,
    pageRows: logHeight,
    // Enter on a LOG entry: open the transcript for its source invokeid (if any).
    // With no explicit cursor the selected entry is the newest visible (tail).
    openSelected: () => {
      const sb = store.state.scrollback;
      const idx = logCursor() ?? sb.length - 1 - logScroll();
      const entry = sb[idx];
      const src = entry?.source;
      if (src && typeof src === "string" && !LOG_LANES.has(src)) {
        // Inspector mounts only while open; if closed, open it first so its
        // controls ref is live, then drill into the transcript on next frame.
        setInspectorOpen(true);
        inspector?.openTranscriptFor(src);
      }
    },
  };

  // --- Conversation-menu action callbacks (task 010 fires exactly one) ------
  // Transcript: reuse the inspector's open-then-drill path (same as Enter).
  const onMenuTranscript = (invokeid: string) => {
    setInspectorOpen(true);
    inspector?.openTranscriptFor(invokeid);
  };
  // Re-run: request the editable conversation + the model catalog, then open the
  // form once those frames have populated `debug.conversation`/`debug.modelCatalog`.
  // We flip the render gate now; RerunForm renders nothing until the conversation
  // arrives (its `isOpen()` is false meanwhile, so the modal tier stays inert and
  // mission-control keeps working until the form is actually live).
  const onMenuRerun = (invokeid: string) => {
    // The selected row's CHILD session (multiplex sibling). Captured now so the
    // re-run seeds from the right sub-chart's node-entry checkpoint; remembered
    // for `onFormRun` below. Null for a plain single-session invocation.
    const session = liveCursorSession(store.state.live, liveCursorRow());
    setRerunSession(session);
    // Resolve the capture coordinates (stamped from llm/request) so the agent can
    // read the captured turns; without them the conversation comes back empty
    // (the form would show no system/messages). Prefer the SELECTED session's
    // coordinates (parallel poets share an invokeid; `liveNodeRef` would pick a
    // sibling), falling back to the invokeid's most-recent session. See wire §9.1.
    debugDispatch.requestConversation(
      invokeid,
      liveNodeRefForSession(store.state.live, invokeid, session) ??
        liveNodeRef(store.state.live, invokeid),
    );
    control("request-model-catalog");
    setDebugFormOpen(true);
  };
  // Break: arm the per-turn LLM breakpoint (payload-free; invokeid is context).
  const onMenuBreak = (_invokeid: string) => {
    control("arm-llm-breakpoint");
  };

  // --- Re-run form Run handler: ship the edited branch as `rerun-from` --------
  const onFormRun = (payload: {
    invokeid: string;
    nodeId: string;
    visit: number;
    turn: number;
    overrides: import("./transport/wire").RerunOverrides;
  }) => {
    debugDispatch.rerunFrom({
      // The forked branch seeds from the SELECTED row's session (a sub-chart
      // child in a multi-session run); fall back to the root session for a plain
      // single-session invocation.
      sessionId: rerunSession() ?? sessionId,
      invokeid: payload.invokeid,
      nodeId: payload.nodeId,
      visit: payload.visit,
      turn: payload.turn,
      overrides: payload.overrides,
    });
    setDebugFormOpen(false);
  };
  const onFormCancel = () => setDebugFormOpen(false);

  // Composed MODAL-tier hook: menu → form → human prompt, in priority order.
  // While any is open the keymap routes keys here first (returning true consumes).
  const composedModal: ModalHook = {
    isOpen: () =>
      (menuHook?.isOpen() ?? false) ||
      (debugFormOpen() && (formHook?.isOpen() ?? false)) ||
      (modalHook?.isOpen() ?? false),
    handleKey: (key) => {
      if (menuHook?.isOpen()) return menuHook.handleKey(key);
      if (debugFormOpen() && formHook?.isOpen()) return formHook.handleKey(key);
      return modalHook?.handleKey(key) ?? false;
    },
  };

  const handleKey = makeKeyHandler({
    shell: () => controls,
    inspector: () => inspector,
    inspectorOpen,
    toggleInspector: () => setInspectorOpen((o) => !o),
    openTranscript: (iid) => {
      // Open the overlay first (Inspector mounts only while open), then drill in.
      setInspectorOpen(true);
      inspector?.openTranscriptFor(iid);
    },
    openArtifactsView: () => {
      // Open the overlay first (Inspector mounts only while open), then switch.
      setInspectorOpen(true);
      inspector?.setView("artifacts");
    },
    liveCursor: liveCursorModel,
    logScroll: logScrollModel,
    control,
    // `v` visualize: the JLine TUI renders a d2 statechart diagram. The sidecar
    // owns a text terminal and can't draw d2 graphics, so for parity we open the
    // Inspector's Status view — the live-configuration readout (active states /
    // mode / step-budget) — which is the compact equivalent (documented in
    // Debugger.tsx + docs/opentui-ui.md).
    visualize: () => {
      setInspectorOpen(true);
      inspector?.setView("status");
    },
    quit: () => {
      source.stop();
      // The sidecar OWNS its terminal restore: destroy() pops the kitty keyboard
      // protocol, leaves the alt-screen, and shows the cursor. We then exit under
      // our own control so the process is gone before the agent's back-channel
      // teardown can SIGTERM us mid-destroy() — a race that would leave the kitty
      // keyboard protocol enabled and break keyboard handling desktop-wide (e.g.
      // Hyprland window switching) until the user runs `reset`. queueMicrotask
      // lets destroy()'s synchronous restore bytes flush to the TTY first.
      renderer.destroy();
      queueMicrotask(() => process.exit(0));
    },
    // Modal tier: the composed conversation-menu → re-run-form → human-prompt
    // hook (task 012/013). While any is open (and the inspector closed) keys
    // route to its handleKey in priority order.
    modal: composedModal,
    // Time-travel debugger mission-control bindings (task 012): `o` opens the
    // conversation menu for the selected LIVE row; n/b/c walk/release the turn
    // gate while paused-at-turn.
    debug: {
      openMenu: () => {
        const iid = liveCursorInvokeid(store.state.live, liveCursorRow());
        if (iid) menuHook?.open(iid);
      },
      pausedAtTurn: () => isPausedAtTurn(store.state.debug),
      turnNext: () => control("turn-next"),
      turnBack: () => control("turn-back"),
      turnContinue: () => control("continue"),
    },
  });

  useKeyboard((key) => handleKey(key));

  return (
    <Shell
      state={store.state}
      theme={theme}
      termWidth={dims().width}
      termHeight={dims().height}
      chartName={chartNameFromEnv()}
      sessionShort={sessionShortFromEnv()}
      elapsedMs={elapsedMs()}
      ref={(c) => (controls = c)}
      logScroll={logScrollIndicator}
      overlay={(ctx) =>
        debugFormOpen() && msgEditorOpen() && formHook?.editorOpen() ? (
          <MessageEditorOverlay
            theme={ctx.theme}
            hook={formHook!}
            width={ctx.width}
            height={overlayHeight()}
          />
        ) : inspectorOpen() ? (
          <Inspector
            state={ctx.state}
            theme={ctx.theme}
            width={ctx.width}
            height={overlayHeight()}
            sessionDir={sessionDir}
            copyText={copyText}
            debug={debugFromState(ctx.state.debug)}
            ref={(c) => (inspector = c)}
            open={inspectorOpen}
            setOpen={setInspectorOpen}
          />
        ) : null
      }
      modal={(ctx) => (
        <>
          {/* Paused-at-turn breakpoint legend (self-hides unless parked/armed). */}
          <BreakpointControls
            theme={ctx.theme}
            debug={ctx.state.debug}
            width={ctx.width}
          />
          {/* Re-run failure banner (self-hides unless a rerun-from failed). */}
          <BranchErrorBanner
            theme={ctx.theme}
            debug={ctx.state.debug}
            width={ctx.width}
          />
          {/* Conversation action menu (self-hides when closed). */}
          <ConversationMenu
            theme={ctx.theme}
            width={ctx.width}
            onTranscript={onMenuTranscript}
            onRerun={onMenuRerun}
            onBreak={onMenuBreak}
            ref={(h) => (menuHook = h)}
          />
          {/* Re-run debug form (mounts only while active; self-hides until the
              conversation frame arrives). */}
          <Show when={debugFormOpen()}>
            <RerunForm
              theme={ctx.theme}
              debug={ctx.state.debug}
              width={ctx.width}
              onRun={onFormRun}
              onCancel={onFormCancel}
              onEditorOpenChange={setMsgEditorOpen}
              ref={(h) => (formHook = h)}
            />
          </Show>
          <Modals
            prompt={openPrompt()}
            theme={ctx.theme}
            width={ctx.width}
            onAnswer={sendAnswer}
            onCancel={sendCancel}
            ref={(h) => (modalHook = h)}
          />
          {/* Keep-alive "finished" banner (#2). Hidden while a human prompt is
              up so it never overlaps the modal. */}
          <Show when={finished() && !openPrompt()}>
            <FinishedBanner theme={ctx.theme} width={ctx.width} />
          </Show>
        </>
      )}
      livePane={(ctx) => (
        <LivePanel ctx={ctx} tick={tick()} cursorRow={liveCursorRow()} height={logHeight()} />
      )}
      logPane={(ctx) => (
        <LogPane
          entries={ctx.state.scrollback}
          theme={ctx.theme}
          width={ctx.width}
          height={logHeight()}
          scrollOffset={logScroll()}
          cursorIdx={logCursor()}
          focused={ctx.focused}
        />
      )}
    />
  );
};

render(App, {
  targetFps: 30,
  exitOnCtrlC: false, // we stop the source before destroying the renderer
  // Leave mouse tracking OFF: the UI is entirely keyboard-driven (like the
  // JLine TUI), and enabling mouse capture steals click-drag from the terminal
  // so the user can no longer select/copy text the normal way.
  useMouse: false,
});
