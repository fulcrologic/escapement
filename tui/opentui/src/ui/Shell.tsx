/**
 * Layout shell — the Yoga/flexbox top-level frame, parity-matched to
 * `escapement.tui` mission-control (header strip + responsive LIVE/LOG body +
 * footer). This file owns ONLY the responsive skeleton + focus/maximize
 * signals + themed, focus-aware bordered pane frames; the pane *content* is
 * injected by tasks 009 (LIVE), 010 (LOG) and 011 (inspector overlay) through
 * the render-prop slots below.
 *
 * Structure (root `<box flexDirection="column">`):
 *   ┌ Header box (border, height HEADER_H) ─ 3 content rows (phase strip)
 *   │ optional PAUSED banner row
 *   ├ Body (flexGrow:1) ─ two-pane | narrow | maximized, per {@link computeLayout}
 *   └ Footer row (height FOOTER_H)
 *
 * Responsive behavior matches the JLine compositor: ≥100 cols → LIVE/LOG split
 * (LIVE floored at 40 cols), <100 cols → single stacked pane, `maximized` →
 * focused pane fills the body. Focus + maximize are plain Solid signals so task
 * 012 can drive them from `useKeyboard`.
 */

import { Show, createMemo, createSignal, type JSX } from "solid-js";
import type { DomainState } from "../domain/store";
import type { Theme } from "../domain/theme";
import { Header } from "./Header";
import { Footer } from "./Footer";
import { PausedBanner } from "./Debugger";
import {
  computeLayout,
  HEADER_H,
  type Focus,
  type LayoutMode,
} from "./layout";

/** Per-pane render context handed to the slot render-props (tasks 009/010/011). */
export interface PaneContext {
  state: DomainState;
  theme: Theme;
  /** True when THIS pane currently has focus. */
  focused: boolean;
  /** Interior width in columns (box width minus the 2 border columns). */
  width: number;
}

/** Imperative handle for task 012 to drive focus/maximize from keybindings. */
export interface ShellControls {
  focus: () => Focus;
  setFocus: (f: Focus) => void;
  toggleFocus: () => void;
  maximized: () => boolean;
  setMaximized: (m: boolean) => void;
  toggleMaximize: () => void;
  mode: () => LayoutMode;
}

export interface ShellProps {
  state: DomainState;
  theme: Theme;
  /** Terminal columns (from `useTerminalDimensions`) — drives responsive mode. */
  termWidth: number;
  /** Terminal rows — reserved for future row-aware logic; not required here. */
  termHeight?: number;
  chartName: string;
  sessionShort: string;
  /** ms since session start; the Shell does not own the clock (testable). */
  elapsedMs: number;
  /** Force the footer's debugger hint set on (task 014). When omitted the
   *  Shell derives it from `state.debug != null` (a debug controller is live). */
  debug?: boolean;
  /** @deprecated The PAUSED banner now derives from `state.debug.paused`
   *  (live `debug` snapshots); this prop is ignored. Kept for call-site compat. */
  paused?: boolean;

  /** LIVE pane slot (task 009). */
  livePane: (ctx: PaneContext) => JSX.Element;
  /** LOG pane slot (task 010). */
  logPane: (ctx: PaneContext) => JSX.Element;
  /** Inspector/transcript fullscreen overlay slot (task 011); null ⇒ hidden. */
  overlay?: (ctx: PaneContext) => JSX.Element | null;
  /** Human-input modal slot (task 013); rendered above the footer. null ⇒ hidden. */
  modal?: (ctx: PaneContext) => JSX.Element | null;

  /** Scroll indicators `⇅ pos/total` for the LIVE / LOG frames (task 012,
   *  per task 010's request). Threaded into the matching `<Pane scroll=…>`. */
  liveScroll?: () => { pos: number; total: number } | undefined;
  logScroll?: () => { pos: number; total: number } | undefined;

  /** Receive the focus/maximize controls (task 012). */
  ref?: (controls: ShellControls) => void;
}

/** Focus-aware bordered pane wrapper — the `draw-box` analogue. */
function Pane(props: {
  theme: Theme;
  title: string;
  focused: boolean;
  /** width including borders, or undefined to flex-grow. */
  width?: number;
  grow?: boolean;
  /** scroll indicator `⇅ pos/total`, shown right-aligned in the bottom border. */
  scroll?: { pos: number; total: number };
  children: JSX.Element;
}) {
  const borderColor = () =>
    props.focused
      ? props.theme.fg("border-focus")
      : props.theme.fg("border-dim");
  const scrollLabel = () =>
    props.scroll ? `⇅ ${props.scroll.pos}/${props.scroll.total}` : undefined;

  return (
    <box
      border
      borderStyle={props.focused ? "heavy" : "rounded"}
      borderColor={borderColor()}
      title={props.title}
      titleAlignment="left"
      bottomTitle={scrollLabel()}
      bottomTitleAlignment="right"
      flexDirection="column"
      flexGrow={props.grow ? 1 : 0}
      flexShrink={props.grow ? 1 : 0}
      flexBasis={props.grow ? 0 : undefined}
      width={props.width}
      height="100%"
    >
      {props.children}
    </box>
  );
}

export function Shell(props: ShellProps) {
  const [focus, setFocus] = createSignal<Focus>("log");
  const [maximized, setMaximized] = createSignal(false);

  const layout = createMemo(() =>
    computeLayout({
      termWidth: props.termWidth,
      focus: focus(),
      maximized: maximized(),
    }),
  );

  const controls: ShellControls = {
    focus,
    setFocus,
    toggleFocus: () => setFocus((f) => (f === "live" ? "log" : "live")),
    maximized,
    setMaximized,
    toggleMaximize: () => setMaximized((m) => !m),
    mode: () => layout().mode,
  };
  props.ref?.(controls);

  // interior widths (box width minus 2 border columns)
  const liveInner = () => Math.max(0, layout().liveWidth - 2);
  const logInner = () => Math.max(0, layout().logWidth - 2);
  const fullInner = () => Math.max(0, props.termWidth - 2);

  const ctx = (pane: Focus, width: number): PaneContext => ({
    state: props.state,
    theme: props.theme,
    focused: focus() === pane,
    width,
  });

  return (
    <box flexDirection="column" width="100%" height="100%">
      {/* --- header strip --- */}
      <box
        border
        borderStyle="rounded"
        borderColor={props.theme.fg("border-dim")}
        height={HEADER_H}
        flexShrink={0}
        flexDirection="column"
      >
        <Header
          state={props.state}
          theme={props.theme}
          chartName={props.chartName}
          sessionShort={props.sessionShort}
          elapsedMs={props.elapsedMs}
          width={fullInner()}
        />
      </box>

      {/* --- live PAUSED banner (task 014); self-hides when not paused --- */}
      <PausedBanner theme={props.theme} debug={props.state.debug} width={fullInner()} />

      {/* --- body --- */}
      <box flexGrow={1} flexBasis={0} flexDirection="row" width="100%">
        <Show
          when={props.overlay?.(ctx(focus(), fullInner()))}
          fallback={
            <>
              <Show when={layout().showLive}>
                <Pane
                  theme={props.theme}
                  title="LIVE"
                  focused={focus() === "live"}
                  width={layout().mode === "two-pane" ? layout().liveWidth : undefined}
                  grow={layout().mode !== "two-pane"}
                  scroll={props.liveScroll?.()}
                >
                  {props.livePane(
                    ctx("live", layout().mode === "two-pane" ? liveInner() : fullInner()),
                  )}
                </Pane>
              </Show>
              <Show when={layout().showLog}>
                <Pane
                  theme={props.theme}
                  title="LOG"
                  focused={focus() === "log"}
                  width={layout().mode === "two-pane" ? layout().logWidth : undefined}
                  grow={layout().mode !== "two-pane"}
                  scroll={props.logScroll?.()}
                >
                  {props.logPane(
                    ctx("log", layout().mode === "two-pane" ? logInner() : fullInner()),
                  )}
                </Pane>
              </Show>
            </>
          }
        >
          {(el: () => JSX.Element) => el()}
        </Show>
      </box>

      {/* --- human-input modal (task 013), above the footer --- */}
      {/* Rendered directly (not wrapped in <Show>): the modal slot always
          returns a self-hiding fragment, so the slot's children manage their own
          visibility. Wrapping it in `<Show when={props.modal?.(…)}>` evaluated the
          render function TWICE (once for the `when` truthiness check, once for the
          child), mounting two component instances. Imperatively-opened children
          (the conversation menu + re-run form, reached via a `ref` hook) then bound
          the ref to one instance while the OTHER was the one actually rendered, so
          opening them did nothing on screen. A single direct render keeps one
          instance the ref binds to. */}
      {props.modal?.(ctx(focus(), fullInner()))}

      {/* --- footer --- */}
      <Footer
        theme={props.theme}
        width={props.termWidth}
        focus={focus()}
        maximized={maximized()}
        debug={props.debug ?? props.state.debug != null}
        narrow={layout().mode === "narrow"}
      />
    </box>
  );
}
