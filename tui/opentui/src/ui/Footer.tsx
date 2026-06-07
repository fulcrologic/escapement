/**
 * Footer keybinding-hint row — port of `escapement.tui/footer-text`.
 *
 * Static contextual text driven by focus / maximized / debug state. Tasks
 * 012/013/014 may swap variants (modal / inspector) by passing a different
 * `text` or by branching on the same inputs; the mission-control set lives in
 * {@link footerText} so it stays a pure, testable string.
 */

import { createMemo } from "solid-js";
import type { Theme } from "../domain/theme";
import { truncateDisplay } from "../domain/wrap";
import type { Focus } from "./layout";

export interface FooterInput {
  focus: Focus;
  maximized: boolean;
  debug: boolean;
  narrow: boolean;
}

/** Mission-control footer hint string (pure port of `footer-text`). */
export function footerText({ focus, maximized, debug }: FooterInput): string {
  const live = focus === "live";
  const pane = live ? "LIVE" : "LOG";
  const other = live ? "LOG" : "LIVE";
  const ctrl = debug ? " · s/c/p/P ctrl" : "";
  const viz = debug ? " · v viz" : "";
  return (
    ` ${pane}${maximized ? " (max)" : ""}` +
    (live ? " · j/k select" : " · ⇅ scroll") +
    " · Enter transcript" +
    (maximized
      ? ` · Esc restore split · Tab → ${other}`
      : ` · m maximize · Tab → ${other}`) +
    " · ? inspector · a artifacts" +
    ctrl +
    viz +
    (maximized ? "" : " · Esc interrupt") +
    " · Ctrl-C quit"
  );
}

export interface FooterProps {
  theme: Theme;
  width: number;
  focus: Focus;
  maximized: boolean;
  debug: boolean;
  narrow: boolean;
}

export function Footer(props: FooterProps) {
  const text = createMemo(() =>
    truncateDisplay(
      footerText({
        focus: props.focus,
        maximized: props.maximized,
        debug: props.debug,
        narrow: props.narrow,
      }),
      Math.max(0, props.width),
    ),
  );
  return (
    <box width={props.width} height={1}>
      <text>
        <span style={{ fg: props.theme.fg("timestamp") }}>{text()}</span>
      </text>
    </box>
  );
}
