/**
 * LIVE pane — the streaming-token mission-control panel, at parity with the
 * JLine `escapement.tui.live/live-pane-lines`.
 *
 * One GROUP per invokeid/role, sorted so in-flight groups stay on top (status
 * rank asc, then recency — via `liveGroups`). A role with a single session
 * renders one flat line (role-hued name · status glyph · label/SHIMMER · tokens
 * · tok/s · model). A role with concurrent sessions (multiplex children)
 * renders a bold group header (name · ◇ · `done/total done` · determinate
 * COMPLETION BAR · tokens) plus its sessions indented
 * `├`/`└ <sid> <glyph> <label> <tok> <t/s>`, capped at `LIVE_GROUP_CHILDREN`
 * with a `└ …+N more sessions` roll-up. Groups capped at `LIVE_MAX_GROUPS`.
 *
 * Rows + the cursor→drill-in `liveRowIndex` come from the SAME `liveRows`
 * expansion (live/rows.ts) so the visible rows and the drill-in target stay in
 * lockstep. Token throughput is render-decoupled: the store reactivity paints
 * counts as they fold; only the shimmer slides on a frame `tick` (live/tick.ts).
 *
 * Sticky bottom: rows live in `<scrollbox stickyScroll stickyStart="bottom">`
 * so the panel follows the newest activity; an in-flight cursor `▏` marks rows
 * that are actively streaming.
 */

import { For, Show, createMemo, type JSX } from "solid-js";
import type { PaneContext } from "./Shell";
import type { Theme } from "../domain/theme";
import type { LiveGroup } from "../domain/solid-store";
import { liveGroups } from "../domain/solid-store";
import { liveCount, liveTps } from "../domain/aggregate";
import { shortInvokeid } from "../domain/time";
import type { LiveSession, LiveStatus } from "../domain/types";
import { Shimmer } from "./live/Shimmer";
import { CompletionBar, liveBarWidthFor } from "./live/CompletionBar";
import { liveRows, type LiveRow } from "./live/rows";

/** Port of `live-status`: [glyph, label, theme status key] per session. */
function liveStatus(
  v: LiveSession,
): [string, string, "streaming" | "done" | "waiting" | "error" | "idle"] {
  switch (v.status as LiveStatus) {
    case "streaming":
      return [v.kind === "thinking" ? "…" : "◂", "streaming", "streaming"];
    case "waiting":
      return ["◷", "waiting", "waiting"];
    case "error":
      return ["✗", "error", "error"];
    case "done":
      return ["✓", "done", "done"];
    default:
      return ["·", String(v.status ?? "—"), "idle"];
  }
}

/** Left-pad/clip a label to a fixed display width (ASCII columns). */
function padR(s: string, n: number): string {
  if (s.length >= n) return s.slice(0, n);
  return s + " ".repeat(n - s.length);
}

/** `%5d tok  %5.1f t/s` right-hand metric tail, matching the JLine columns. */
function metricTail(tok: number, tps: number): string {
  const t = String(Math.trunc(tok)).padStart(5, " ");
  const r = tps.toFixed(1).padStart(5, " ");
  return `  ${t} tok  ${r} t/s`;
}

/**
 * Fixed-width MODEL column (`  %-14s`), placed before the tok/t-s tail. Shown
 * on each INDIVIDUAL session row (single + child) since concurrent children
 * can run different models; the group header passes `null` (it aggregates rows
 * whose models may differ) to render a blank column and keep tok aligned.
 * Mirrors the JLine `modelcol` in `escapement.tui.live/live-pane-lines`.
 */
function modelCol(model?: string | null): string {
  return `  ${padR(model ?? "", 14)}`;
}

/**
 * In-flight cursor for a streaming session (parity with session.tsx). Rendered
 * as a `<text>` (not a bare `<span>`) because it sits at the ROW/box level —
 * a sibling of the row's `<text>` inside a `flexDirection="row"` box — and a
 * `<span>` must have a `<text>` parent (orphan-text error otherwise).
 */
function Cursor(props: { theme: Theme; on: boolean }): JSX.Element {
  return (
    <Show when={props.on}>
      <text wrapMode="none">
        <span style={{ fg: props.theme.fg("status/streaming") }}>{" ▏"}</span>
      </text>
    </Show>
  );
}

/** A single-session (host / lone planner) row. */
function SingleRow(props: {
  theme: Theme;
  iid: string;
  session: LiveSession;
  tick: number;
  barWidth: number;
  cursor: boolean;
}): JSX.Element {
  const v = () => props.session;
  const [glyph, label, statusKey] = liveStatus(props.session);
  const streaming = () => v().status === "streaming";
  const name = shortInvokeid(props.iid) ?? "?";
  return (
    <box flexDirection="row">
      <text wrapMode="none">
        <span> </span>
        <span style={{ fg: props.theme.roleColor(props.iid) ?? undefined }}>
          {padR(name, 12)}
        </span>
        <span> </span>
        <span style={{ fg: props.theme.statusColor(statusKey) ?? undefined }}>
          {glyph}
        </span>
        <span> </span>
      </text>
      <Show
        when={streaming()}
        fallback={
          <text wrapMode="none">
            <span style={{ fg: props.theme.statusColor(statusKey) ?? undefined }}>
              {label}
            </span>
          </text>
        }
      >
        <Shimmer
          theme={props.theme}
          width={props.barWidth}
          tick={props.tick}
          lastTs={v()["last-ts"] ?? 0}
        />
      </Show>
      <text wrapMode="none">
        <span style={{ fg: props.theme.fg("metric") }}>{modelCol(v().model)}</span>
        <span style={{ fg: props.theme.fg("metric") }}>
          {metricTail(liveCount(v()), liveTps(v()))}
        </span>
      </text>
      <Cursor theme={props.theme} on={props.cursor} />
    </box>
  );
}

/** A multi-session group header row (name · ◇ · done/total · bar · metrics). */
function GroupHeaderRow(props: {
  theme: Theme;
  group: LiveGroup;
  done: number;
  total: number;
  barWidth: number;
}): JSX.Element {
  const g = () => props.group;
  const name = shortInvokeid(g().iid) ?? "?";
  const [, , statusKey] = liveStatus({ status: g().status } as LiveSession);
  const role = () => props.theme.roleColor(g().iid) ?? undefined;
  return (
    <box flexDirection="row">
      <text wrapMode="none">
        <span> </span>
        <span style={{ fg: role(), bold: true }}>{padR(name, 12)}</span>
        <span> </span>
        <span style={{ fg: props.theme.statusColor(statusKey) ?? undefined }}>◇</span>
        <span> </span>
        <span style={{ fg: role(), bold: true }}>
          {`${props.done}/${props.total} done `}
        </span>
      </text>
      <CompletionBar
        theme={props.theme}
        done={props.done}
        total={props.total}
        width={props.barWidth}
        filledColor={role()}
      />
      <text wrapMode="none">
        <span style={{ fg: props.theme.fg("metric") }}>{modelCol(null)}</span>
        <span style={{ fg: props.theme.fg("metric"), bold: true }}>
          {` ${String(Math.trunc(g().tokens)).padStart(5, " ")} tok`}
        </span>
      </text>
    </box>
  );
}

/** An indented child session row under a group. */
function ChildRow(props: {
  theme: Theme;
  iid: string;
  session: LiveSession;
  last: boolean;
  cursor: boolean;
}): JSX.Element {
  const v = () => props.session;
  const [glyph, label, statusKey] = liveStatus(props.session);
  const role = () => props.theme.roleColor(props.iid) ?? undefined;
  const sid = () =>
    String(v().session).replace(/^:/, "").replace(/^multiplex\./, "").slice(0, 16);
  return (
    <box flexDirection="row">
      <text wrapMode="none">
        <span>{"  "}</span>
        <span style={{ fg: role() }}>{props.last ? "└" : "├"}</span>
        <span> </span>
        <span style={{ fg: role() }}>{padR(sid(), 13)}</span>
        <span> </span>
        <span style={{ fg: props.theme.statusColor(statusKey) ?? undefined }}>
          {glyph}
        </span>
        <span> </span>
        <span style={{ fg: props.theme.statusColor(statusKey) ?? undefined }}>
          {padR(label, 9)}
        </span>
        <span style={{ fg: props.theme.fg("metric") }}>{modelCol(v().model)}</span>
        <span style={{ fg: props.theme.fg("metric") }}>
          {metricTail(liveCount(v()), liveTps(v()))}
        </span>
      </text>
      <Cursor theme={props.theme} on={props.cursor} />
    </box>
  );
}

/** The `└ …+N more sessions` roll-up. */
function MoreRow(props: { theme: Theme; iid: string; more: number }): JSX.Element {
  return (
    <text wrapMode="none">
      <span>{"  "}</span>
      <span style={{ fg: props.theme.roleColor(props.iid) ?? undefined }}>└</span>
      <span style={{ fg: props.theme.fg("metric") }}>
        {` …+${props.more} more sessions`}
      </span>
    </text>
  );
}

/**
 * Render one logical row. `selected` reverse-videos the whole row (the LIVE
 * drill-in cursor, parity with the JLine `reverse-on-s`); the in-flight `▏`
 * cursor still marks streaming rows independently.
 */
function Row(props: {
  theme: Theme;
  row: LiveRow;
  tick: number;
  width: number;
  selected: boolean;
}) {
  const inner = () => rowInner(props);
  // OpenTUI `<box>` has no reverse-video; mark the selected drill-in row with a
  // focus-accent background bar (the closest box-level analogue of the JLine
  // `reverse-on-s` whole-row highlight).
  const selBg = () => props.theme.fg("border-focus") ?? undefined;
  return (
    <Show when={props.selected} fallback={inner()}>
      <box flexDirection="row" backgroundColor={selBg()}>
        {inner()}
      </box>
    </Show>
  );
}

function rowInner(props: { theme: Theme; row: LiveRow; tick: number; width: number }) {
  const r = props.row;
  const bw = liveBarWidthFor(props.width);
  switch (r.kind) {
    case "single":
      return (
        <SingleRow
          theme={props.theme}
          iid={r.group.iid}
          session={r.session}
          tick={props.tick}
          barWidth={bw}
          cursor={r.session.status === "streaming"}
        />
      );
    case "group-header":
      return (
        <GroupHeaderRow
          theme={props.theme}
          group={r.group}
          done={r.done}
          total={r.total}
          barWidth={bw}
        />
      );
    case "child":
      return (
        <ChildRow
          theme={props.theme}
          iid={r.group.iid}
          session={r.session}
          last={r.last}
          cursor={r.session.status === "streaming"}
        />
      );
    case "more":
      return <MoreRow theme={props.theme} iid={r.group.iid} more={r.more} />;
  }
}

/**
 * The LIVE pane content. Mounted in the Shell's `livePane` slot; reads the
 * reactive `ctx.state.live` via `liveGroups`/`liveRows` and the session Theme.
 * `tick` drives the shimmer (frame-counter, not wall-clock).
 */
export function LivePanel(props: {
  ctx: PaneContext;
  tick: number;
  /** Selected row index (LIVE drill-in cursor). Only highlights when the pane
   *  is focused; null / out-of-range ⇒ no selection. */
  cursorRow?: number | null;
}) {
  const rows = createMemo(() => liveRows(liveGroups(props.ctx.state.live)));
  const selectedIdx = () =>
    props.ctx.focused && props.cursorRow != null ? props.cursorRow : -1;
  return (
    <box flexGrow={1} flexBasis={0} width={props.ctx.width} flexDirection="column">
      <Show
        when={rows().length > 0}
        fallback={
          <text wrapMode="none">
            <span style={{ fg: props.ctx.theme.fg("status/idle") }}>
              {"  (no live activity yet)"}
            </span>
          </text>
        }
      >
        <scrollbox
          stickyScroll={true}
          stickyStart="bottom"
          scrollbarOptions={{ visible: false }}
          contentOptions={{ flexDirection: "column" }}
        >
          <For each={rows()}>
            {(row, i) => (
              <Row
                theme={props.ctx.theme}
                row={row}
                tick={props.tick}
                width={props.ctx.width}
                selected={i() === selectedIdx()}
              />
            )}
          </For>
        </scrollbox>
      </Show>
    </box>
  );
}
