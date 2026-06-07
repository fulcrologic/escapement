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
import { createSignal, onCleanup, onMount } from "solid-js";
import { createEventSource } from "./transport";
import { createDomainStore } from "./domain/solid-store";
import { makeThemeFromEnv } from "./domain/theme";
import { Shell, type ShellControls } from "./ui/Shell";
import { HEADER_H, FOOTER_H } from "./ui/layout";
import { LogPane } from "./ui/LogPane";
import { LivePanel } from "./ui/LivePanel";
import { createTick } from "./ui/live/tick";
import { Inspector, type InspectorControls } from "./ui/Inspector";
import { copyToClipboard } from "./ui/inspector/artifacts";
import { debugFromState } from "./ui/Debugger";
import { liveRowIndex } from "./ui/live/rows";
import { liveGroups } from "./domain/solid-store";
import { makeKeyHandler, type LiveCursor, type LogScrollModel } from "./input/keybindings";
import { makeControlDispatch, type ModalHook } from "./input/dispatch";
import { Modals } from "./ui/Modals";

/** Best-effort chart name from the env (the sidecar parent may set it). */
function chartNameFromEnv(): string {
  return process.env["OPENTUI_CHART"] ?? "session";
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

  onMount(() => {
    source.start();
    const clock = setInterval(() => setElapsedMs(Date.now() - startTs), 1000);
    onCleanup(() => {
      clearInterval(clock);
      store.dispose();
    });
  });

  // Forward control / interrupt / quit ops to the agent over the back-channel
  // (no-op + false on a replay source). Esc → ui-interrupt, Ctrl-C → ui-quit,
  // s/c/p/P → step/continue/pause/arm.
  const control = makeControlDispatch(source);

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
      renderer.destroy();
    },
    // Human-input modal tier (task 013). `isOpen()` gates on the open prompt so
    // a dismissed/answered prompt no longer captures keys; while open (and the
    // inspector closed) every key routes to the modal's handleKey.
    modal: {
      isOpen: () => modalHook?.isOpen() ?? false,
      handleKey: (key) => modalHook?.handleKey(key) ?? false,
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
      sessionShort={""}
      elapsedMs={elapsedMs()}
      ref={(c) => (controls = c)}
      logScroll={logScrollIndicator}
      overlay={(ctx) =>
        inspectorOpen() ? (
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
        <Modals
          prompt={openPrompt()}
          theme={ctx.theme}
          width={ctx.width}
          onAnswer={sendAnswer}
          onCancel={sendCancel}
          ref={(h) => (modalHook = h)}
        />
      )}
      livePane={(ctx) => (
        <LivePanel ctx={ctx} tick={tick()} cursorRow={liveCursorRow()} />
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
