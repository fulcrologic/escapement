/**
 * LIVE pane — the streaming-token mission-control panel, at parity with the
 * JLine `escapement.tui.live/live-pane-lines`.
 *
 * One GROUP per invokeid/role. Top-level units (multiplex phases + non-mux
 * groups) are interleaved by the shared `compareLiveOrder` (in-flight first,
 * then most-recent activity first) — the single live-ordering source of truth,
 * the same comparator `liveGroups` uses. Each top-level group renders in ONE hue
 * (the resolved `groupHueKey`, threaded as `hue` to every descendant row so a
 * header and all its children/connectors share one color — no per-call rainbow).
 * A role with a single session
 * renders one flat line (role-hued name · status glyph · label/SHIMMER · tokens
 * · tok/s · model). A role with concurrent sessions (multiplex children)
 * renders a bold group header (name · ◇ · `done/total done` · determinate
 * COMPLETION BAR · tokens) plus its sessions indented
 * `├`/`└ <sid> <glyph> <label> <tok> <t/s>`, capped at `LIVE_GROUP_CHILDREN`
 * with a `└ …+N more sessions` roll-up. All groups render — the sticky-bottom
 * `<scrollbox>` owns overflow, so no group is silently dropped.
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

import { For, Show, createMemo, createEffect, type JSX } from "solid-js";
import type { ScrollBoxRenderable } from "@opentui/core";
import type { PaneContext } from "./Shell";
import type { Theme } from "../domain/theme";
import type { LiveGroup } from "../domain/solid-store";
import { liveGroups } from "../domain/solid-store";
import { liveCount, liveTps, shortSession } from "../domain/aggregate";
import { shortInvokeid } from "../domain/time";
import type { LiveSession, LiveStatus } from "../domain/types";
import { Shimmer } from "./live/Shimmer";
import { CompletionBar, liveBarWidthFor } from "./live/CompletionBar";
import { groupHueKey, liveRows, liveRowIndex, type LiveRow } from "./live/rows";
import type { LiveMap } from "../domain/types";
import type { ChildCall, ChildNode, PhaseNode } from "./live/tree";
import { displayWidth } from "../domain/wrap";

/** Number of a child's calls that have finished (for its `done/total` rollup). */
function doneCalls(child: ChildNode): number {
  return child.calls.filter((c) => c.session.status === "done").length;
}

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

/**
 * Right-pad/clip a label to a fixed DISPLAY width (terminal columns), so emoji/
 * CJK/grapheme clusters don't drift the column. Clips by trimming code units
 * until the display width fits, then pads with spaces.
 */
function padR(s: string, n: number): string {
  if (displayWidth(s) > n) {
    let cut = s;
    while (cut.length > 0 && displayWidth(cut) > n) cut = cut.slice(0, -1);
    s = cut;
  }
  const pad = n - displayWidth(s);
  return pad > 0 ? s + " ".repeat(pad) : s;
}

/** `%5d tok  %5.1f t/s` right-hand metric tail, matching the JLine columns. */
function metricTail(tok: number, tps: number): string {
  const t = String(Math.trunc(tok)).padStart(5, " ");
  const r = tps.toFixed(1).padStart(5, " ");
  return `  ${t} tok  ${r} t/s`;
}

/** A duration in seconds with one decimal, e.g. "0.3s"/"5.5s"; "" when unknown. */
function fmtDur(ms?: number | null): string {
  if (ms === undefined || ms === null) return "";
  return `${(ms / 1000).toFixed(1)}s`;
}

/**
 * Single timing column `<ttft> → <total>`: time-to-first-token (how long the row
 * WAITED for the model to produce its first token — engine-measured, so it fills
 * for streamed AND non-streamed turns) and the total turn elapsed. The arrow only
 * appears once wait is known; a lone total reads cleanly. Both right-padded to a
 * fixed width so the column aligns across rows. The whole tail is one labelled
 * unit (` ttft→tot`) so the two values stay in ONE column, not split apart.
 * Dropped on narrow panes via {@link showTimingFor}.
 */
function timingTail(waitMs?: number | null, elapsedMs?: number | null): string {
  const wv = fmtDur(waitMs);
  const ev = fmtDur(elapsedMs);
  if (!wv && !ev) return "";
  const w = wv.padStart(5, " ");
  const e = ev.padStart(5, " ");
  const sep = wv ? "→" : " ";
  return `  ${w} ${sep} ${e} ttft→tot`;
}

/** Show the timing column only when the pane is wide enough to spare ~22 cols.
 *  92 cols clears the widest row (child: ~91 cols with the model column at 18),
 *  so the timing tail shows in a 200-col two-pane split (LIVE inner ≈ 98),
 *  not just when the pane is maximized. */
function showTimingFor(iw: number): boolean {
  return iw >= 92;
}

/**
 * `provider/model` label for a session row, e.g. "ollama/gemma3:1b". Falls back
 * to model-only when no provider (backend-default pick), and "" when neither is
 * known yet (a still-waiting row). Mirrors the JLine `pm` helper.
 */
function pmLabel(v?: LiveSession | null): string {
  const m = v?.model ?? undefined;
  const p = v?.provider ?? undefined;
  if (p && m) return `${p}/${m}`;
  if (m) return m;
  return "";
}

/**
 * Responsive MODEL-column width (display columns), placed before the tok/t-s
 * tail. Dropped entirely on narrow panes so the role name / completion bar are
 * never crowded out. Mirrors the JLine `model-w` breakpoints in
 * `escapement.tui.live/live-pane-lines`.
 */
function modelWidthFor(iw: number): number {
  if (iw >= 100) return 24;
  if (iw >= 88) return 18;
  if (iw >= 76) return 12;
  return 0;
}

/**
 * MODEL column at a responsive width. Shown on each INDIVIDUAL session row
 * (single + child) since concurrent children can run different models/providers;
 * the group header passes "" (it aggregates rows whose models may differ) to
 * render a blank column and keep tok aligned. `w <= 0` ⇒ no column at all.
 * Mirrors the JLine `modelcol`.
 */
function modelCol(label: string | null | undefined, w: number): string {
  if (w <= 0) return "";
  return `  ${padR(label ?? "", w)}`;
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
  /** Resolved group hue (one per top-level group; see `groupHueKey`). */
  hue: string | undefined;
  iid: string;
  session: LiveSession;
  tick: number;
  barWidth: number;
  modelW: number;
  showTiming: boolean;
  cursor: boolean;
}): JSX.Element {
  const v = () => props.session;
  const [glyph, label, statusKey] = liveStatus(props.session);
  const streaming = () => v().status === "streaming";
  // When a lone session is a multiplex child (e.g. the secret Muse or Critique,
  // which run inside ONE poet's session), prefix the invokeid with the child
  // label so you can see WHICH poet it served — `poets.2.muse` /
  // `poets.4.critique`. Parent-session singles (planner / host) stay bare.
  const iidShort = shortInvokeid(props.iid) ?? "?";
  const sid = String(props.session.session ?? "").replace(/^:/, "");
  const prefixed = sid.startsWith("multiplex.");
  const name = prefixed ? `${shortSession(sid)}.${iidShort}` : iidShort;
  // Only the prefixed labels (poets.N.critique = up to 16) need the wider
  // column; bare singles (planner / host) keep the original 12 so their rows
  // stay aligned with the group headers.
  const nameW = prefixed ? 16 : 12;
  return (
    <box flexDirection="row">
      <text wrapMode="none">
        <span> </span>
        <span style={{ fg: props.hue }}>
          {padR(name, nameW)}
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
        <span style={{ fg: props.theme.fg("metric") }}>{modelCol(pmLabel(v()), props.modelW)}</span>
        <span style={{ fg: props.theme.fg("metric") }}>
          {metricTail(liveCount(v()), liveTps(v()))}
        </span>
        <Show when={props.showTiming}>
          <span style={{ fg: props.theme.fg("metric") }}>
            {timingTail(v()["wait-ms"], v()["elapsed-ms"])}
          </span>
        </Show>
      </text>
      <Cursor theme={props.theme} on={props.cursor} />
    </box>
  );
}

/**
 * Width of the fixed PRE-BAR text block on a group header row: the leading
 * space + name(12) + " ◇ " + the `done/total done ` label, padded so the
 * completion bar always starts at the SAME column across all groups (#7,
 * mirroring the JLine `head-left` block padded to a fixed `left-w`). Computed
 * from the WIDEST `done/total` label the panel will show so digit-width
 * differences (e.g. `9/9` vs `12/30`) don't shift the bar.
 *
 * Layout: `" " + name(12) + " " + ◇ + " "` = 1+12+1+1+1 = 16 columns, then the
 * label. The label `"<d>/<t> done "` has a fixed suffix `" done "` (6 cols) plus
 * the `<d>/<t>` numerator/denominator. We pad the label to `labelW` so the bar
 * column is stable.
 */

/** Display width of the `done/total done ` label region (variable digits). */
function groupLabelWidth(done: number, total: number): number {
  return `${done}/${total} done `.length;
}

/** A multi-session group header row (name · ◇ · done/total · bar · metrics). */
function GroupHeaderRow(props: {
  theme: Theme;
  /** Resolved group hue (one per top-level group; see `groupHueKey`). */
  hue: string | undefined;
  group: LiveGroup;
  done: number;
  total: number;
  barWidth: number;
  modelW: number;
  /** Fixed display width for the `done/total done ` label so the bar aligns
   *  across groups (#7). Defaults to this group's own label width. */
  labelWidth?: number;
}): JSX.Element {
  const g = () => props.group;
  const name = shortInvokeid(g().iid) ?? "?";
  const [, , statusKey] = liveStatus({ status: g().status } as LiveSession);
  const role = () => props.hue;
  const labelW = () =>
    props.labelWidth ?? groupLabelWidth(props.done, props.total);
  const label = () => padR(`${props.done}/${props.total} done `, labelW());
  return (
    <box flexDirection="row">
      <text wrapMode="none">
        <span> </span>
        <span style={{ fg: role(), bold: true }}>{padR(name, 12)}</span>
        <span> </span>
        <span style={{ fg: props.theme.statusColor(statusKey) ?? undefined }}>◇</span>
        <span> </span>
        <span style={{ fg: role(), bold: true }}>{label()}</span>
      </text>
      <CompletionBar
        theme={props.theme}
        done={props.done}
        total={props.total}
        width={props.barWidth}
        filledColor={role()}
      />
      <text wrapMode="none">
        <span style={{ fg: props.theme.fg("metric") }}>{modelCol(null, props.modelW)}</span>
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
  /** Resolved group hue (one per top-level group; see `groupHueKey`). */
  hue: string | undefined;
  session: LiveSession;
  last: boolean;
  modelW: number;
  showTiming: boolean;
  cursor: boolean;
}): JSX.Element {
  const v = () => props.session;
  const [glyph, label, statusKey] = liveStatus(props.session);
  const role = () => props.hue;
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
        <span style={{ fg: props.theme.fg("metric") }}>{modelCol(pmLabel(v()), props.modelW)}</span>
        <span style={{ fg: props.theme.fg("metric") }}>
          {metricTail(liveCount(v()), liveTps(v()))}
        </span>
        <Show when={props.showTiming}>
          <span style={{ fg: props.theme.fg("metric") }}>
            {timingTail(v()["wait-ms"], v()["elapsed-ms"])}
          </span>
        </Show>
      </text>
      <Cursor theme={props.theme} on={props.cursor} />
    </box>
  );
}

/** The `└ …+N more sessions` roll-up. */
function MoreRow(props: {
  theme: Theme;
  /** Resolved group hue (one per top-level group; see `groupHueKey`). */
  hue: string | undefined;
  more: number;
  noun?: string;
}): JSX.Element {
  return (
    <text wrapMode="none">
      <span>{"  "}</span>
      <span style={{ fg: props.hue }}>└</span>
      <span style={{ fg: props.theme.fg("metric") }}>
        {` …+${props.more} more ${props.noun ?? "sessions"}`}
      </span>
    </text>
  );
}

/**
 * Multiplex PHASE header (level 0): the multiplex's id (e.g. `poets`) with a
 * children completion bar — reuses {@link GroupHeaderRow} by synthesizing a
 * LiveGroup keyed on the phase, so its styling/alignment stay identical.
 */
function PhaseHeaderRow(props: {
  theme: Theme;
  /** Resolved group hue (one per top-level group; see `groupHueKey`). */
  hue: string | undefined;
  phase: PhaseNode;
  done: number;
  total: number;
  barWidth: number;
  modelW: number;
  labelWidth?: number;
}): JSX.Element {
  const group = () =>
    ({ ...props.phase.agg, iid: props.phase.phase, sessions: {} }) as LiveGroup;
  return (
    <GroupHeaderRow
      theme={props.theme}
      hue={props.hue}
      group={group()}
      done={props.done}
      total={props.total}
      barWidth={props.barWidth}
      modelW={props.modelW}
      labelWidth={props.labelWidth}
    />
  );
}

/**
 * A multiplex CHILD header (level 1): one poet/judge that ran SEVERAL calls,
 * e.g. `poets.1` with `muse + 3 haiku + critique`. Shows the child label and a
 * `done/total calls` rollup; the individual calls render beneath as {@link CallRow}.
 */
function ChildHeaderRow(props: {
  theme: Theme;
  /** Resolved group hue (one per top-level group; see `groupHueKey`). */
  hue: string | undefined;
  child: ChildNode;
  last: boolean;
}): JSX.Element {
  const c = () => props.child;
  const role = () => props.hue;
  const [, , statusKey] = liveStatus({ status: c().agg.status } as LiveSession);
  return (
    <text wrapMode="none">
      <span>{"  "}</span>
      <span style={{ fg: role() }}>{props.last ? "└" : "├"}</span>
      <span> </span>
      <span style={{ fg: role(), bold: true }}>{padR(c().label, 14)}</span>
      <span> </span>
      <span style={{ fg: props.theme.statusColor(statusKey) ?? undefined }}>◇</span>
      <span> </span>
      <span style={{ fg: role(), bold: true }}>
        {`${doneCalls(c())}/${c().calls.length} calls`}
      </span>
      <span style={{ fg: props.theme.fg("metric") }}>
        {`  ${String(Math.trunc(c().agg.tokens)).padStart(5, " ")} tok`}
      </span>
    </text>
  );
}

/** Shared body for an indented call/child-leaf row at a given indent + label. */
function LeafRow(props: {
  theme: Theme;
  indent: string;
  connector: string;
  /** Resolved group hue (one per top-level group; see `groupHueKey`). */
  hue: string | undefined;
  label: string;
  labelW: number;
  session: LiveSession;
  modelW: number;
  showTiming: boolean;
  cursor: boolean;
}): JSX.Element {
  const v = () => props.session;
  const [glyph, label, statusKey] = liveStatus(props.session);
  const role = () => props.hue;
  return (
    <box flexDirection="row">
      <text wrapMode="none">
        <span>{props.indent}</span>
        <span style={{ fg: role() }}>{props.connector}</span>
        <span> </span>
        <span style={{ fg: role() }}>{padR(props.label, props.labelW)}</span>
        <span> </span>
        <span style={{ fg: props.theme.statusColor(statusKey) ?? undefined }}>{glyph}</span>
        <span> </span>
        <span style={{ fg: props.theme.statusColor(statusKey) ?? undefined }}>
          {padR(label, 9)}
        </span>
        <span style={{ fg: props.theme.fg("metric") }}>{modelCol(pmLabel(v()), props.modelW)}</span>
        <span style={{ fg: props.theme.fg("metric") }}>
          {metricTail(liveCount(v()), liveTps(v()))}
        </span>
        <Show when={props.showTiming}>
          <span style={{ fg: props.theme.fg("metric") }}>
            {timingTail(v()["wait-ms"], v()["elapsed-ms"])}
          </span>
        </Show>
      </text>
      <Cursor theme={props.theme} on={props.cursor} />
    </box>
  );
}

/** A single call (level 2) beneath a child header, labelled by its invokeid. */
function CallRow(props: {
  theme: Theme;
  /** Resolved group hue (one per top-level group; see `groupHueKey`). */
  hue: string | undefined;
  call: ChildCall;
  last: boolean;
  modelW: number;
  showTiming: boolean;
}): JSX.Element {
  return (
    <LeafRow
      theme={props.theme}
      indent={"    "}
      connector={props.last ? "└" : "├"}
      hue={props.hue}
      label={shortInvokeid(props.call.iid) ?? "?"}
      labelW={12}
      session={props.call.session}
      modelW={props.modelW}
      showTiming={props.showTiming}
      cursor={props.call.session.status === "streaming"}
    />
  );
}

/** A collapsed child (level 1) with a single call — e.g. one judge. */
function ChildSingleRow(props: {
  theme: Theme;
  /** Resolved group hue (one per top-level group; see `groupHueKey`). */
  hue: string | undefined;
  child: ChildNode;
  call: ChildCall;
  last: boolean;
  modelW: number;
  showTiming: boolean;
}): JSX.Element {
  return (
    <LeafRow
      theme={props.theme}
      indent={"  "}
      connector={props.last ? "└" : "├"}
      hue={props.hue}
      label={props.child.label}
      labelW={14}
      session={props.call.session}
      modelW={props.modelW}
      showTiming={props.showTiming}
      cursor={props.call.session.status === "streaming"}
    />
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
  groupLabelW: number;
}) {
  const inner = () => rowInner(props);
  // OpenTUI `<box>` has no reverse-video; mark the selected drill-in row with a
  // dark background bar (the closest box-level analogue of the JLine
  // `reverse-on-s` whole-row highlight). Stays dark so the row's bright/colored
  // foregrounds keep contrast — a light accent bar washes them out.
  const selBg = () => props.theme.bg("selection-bg") ?? undefined;
  return (
    <Show when={props.selected} fallback={inner()}>
      <box flexDirection="row" backgroundColor={selBg()}>
        {inner()}
      </box>
    </Show>
  );
}

function rowInner(props: {
  theme: Theme;
  row: LiveRow;
  tick: number;
  width: number;
  groupLabelW: number;
}) {
  const r = props.row;
  const bw = liveBarWidthFor(props.width);
  const mw = modelWidthFor(props.width);
  const st = showTimingFor(props.width);
  // ONE hue per top-level group: resolve the row's group-hue key (the single
  // source of truth, {@link groupHueKey}) to a color ONCE here, and pass that
  // resolved color into every row component. No component derives its own hue —
  // that prevents the per-call "rainbow" regression.
  const hue = props.theme.roleColor(groupHueKey(r)) ?? undefined;
  switch (r.kind) {
    case "single":
      return (
        <SingleRow
          theme={props.theme}
          hue={hue}
          iid={r.group.iid}
          session={r.session}
          tick={props.tick}
          barWidth={bw}
          modelW={mw}
          showTiming={st}
          cursor={r.session.status === "streaming"}
        />
      );
    case "group-header":
      return (
        <GroupHeaderRow
          theme={props.theme}
          hue={hue}
          group={r.group}
          done={r.done}
          total={r.total}
          barWidth={bw}
          modelW={mw}
          labelWidth={props.groupLabelW}
        />
      );
    case "child":
      return (
        <ChildRow
          theme={props.theme}
          hue={hue}
          session={r.session}
          last={r.last}
          modelW={mw}
          showTiming={st}
          cursor={r.session.status === "streaming"}
        />
      );
    case "more":
      return <MoreRow theme={props.theme} hue={hue} more={r.more} />;
    case "phase-header":
      return (
        <PhaseHeaderRow
          theme={props.theme}
          hue={hue}
          phase={r.phase}
          done={r.done}
          total={r.total}
          barWidth={bw}
          modelW={mw}
          labelWidth={props.groupLabelW}
        />
      );
    case "child-header":
      return <ChildHeaderRow theme={props.theme} hue={hue} child={r.child} last={r.last} />;
    case "call":
      return (
        <CallRow theme={props.theme} hue={hue} call={r.call} last={r.last} modelW={mw} showTiming={st} />
      );
    case "child-single":
      return (
        <ChildSingleRow
          theme={props.theme}
          hue={hue}
          child={r.child}
          call={r.call}
          last={r.last}
          modelW={mw}
          showTiming={st}
        />
      );
    case "phase-more":
      return (
        <MoreRow theme={props.theme} hue={hue} more={r.more} noun="children" />
      );
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
  /** Interior pane height (rows) — a DEFINITE bound for the `<scrollbox>`. Without
   *  it the sticky-bottom scrollbox grows to its full content height and overflows
   *  the pane UPWARD, drawing the top rows (e.g. the `host` session) behind the
   *  header where they can be cursor-selected but not seen. Bounding it makes the
   *  box clip + scroll internally instead. Same value as `logHeight()`. */
  height: number;
}) {
  const rows = createMemo(() => liveRows(liveGroups(props.ctx.state.live)));
  // Fixed `done/total done ` label width = the widest such label across all
  // group-header rows, so every group's completion bar starts at the SAME
  // column (#7 — mirror JLine's fixed `left-w`).
  const groupLabelW = createMemo(() => {
    let w = 0;
    for (const r of rows()) {
      if (r.kind === "group-header" || r.kind === "phase-header") {
        w = Math.max(w, groupLabelWidth(r.done, r.total));
      }
    }
    return w;
  });
  // One predicate for "the drill-in cursor is active": it gates BOTH the
  // selected-row highlight (`selectedIdx`) and the scroll-follow (`following`),
  // so they can never disagree. Active ⇔ the pane is focused and a cursor is set.
  const cursorActive = () => props.ctx.focused && props.cursorRow != null;
  const selectedIdx = () => (cursorActive() ? props.cursorRow! : -1);

  // Scroll-follow the drill-in cursor (R2). Every LIVE row is exactly one line
  // tall, so a row index maps 1:1 to a scroll line. When the pane is focused
  // and a cursor is set we drive `scrollTop` so the selected row is always
  // inside the visible window `[top, top + height)` — scrolling UP when the
  // cursor is above the window and DOWN when below — and disengage sticky
  // scroll so the box does not snap back to the bottom. When there is no cursor
  // (null / unfocused) we re-enable `stickyScroll` + `stickyStart="bottom"` so
  // the pane keeps following the newest activity. Bounding the box height (==
  // `logHeight()`, the LIVE interior) guarantees no row paints behind the
  // top/bottom border at any offset.
  let boxEl: ScrollBoxRenderable | undefined;
  const following = cursorActive;
  createEffect(() => {
    const cur = props.cursorRow;
    const n = rows().length;
    const h = Math.max(1, props.height);
    if (!boxEl || !following() || cur == null) return;
    const row = Math.max(0, Math.min(cur, n - 1));
    // Minimal scroll: only move enough to bring `row` into view, keeping the
    // current offset otherwise (so up/down both work without recentering).
    const top = boxEl.scrollTop ?? 0;
    let next = top;
    if (row < top) next = row;
    else if (row > top + h - 1) next = row - h + 1;
    // Clamp to the scrollable range.
    const max = Math.max(0, n - h);
    next = Math.max(0, Math.min(next, max));
    if (next !== top) {
      if (typeof boxEl.scrollTo === "function") boxEl.scrollTo({ x: 0, y: next });
      else boxEl.scrollTop = next;
    }
  });
  return (
    <box height={props.height} width={props.ctx.width} flexDirection="column">
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
          ref={boxEl}
          height={props.height}
          stickyScroll={!following()}
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
                groupLabelW={groupLabelW()}
              />
            )}
          </For>
        </scrollbox>
      </Show>
    </box>
  );
}

/**
 * Resolve the conversation `invokeid` the LIVE cursor row points at — the seam
 * task 012 uses to open the {@link ConversationMenu} for the selected LLM row.
 * Mirrors the Enter drill-in: it reads the SAME `liveRowIndex` order as the
 * rendered rows (so the cursor and the resolved target stay in lockstep), then
 * returns that row's `RowTarget.invokeid`. Returns null when no row is selected
 * (null/out-of-range cursor) so the caller can no-op the menu open.
 */
export function liveCursorInvokeid(
  live: LiveMap,
  cursorRow: number | null | undefined,
): string | null {
  if (cursorRow == null) return null;
  const targets = liveRowIndex(liveGroups(live));
  return targets[cursorRow]?.invokeid ?? null;
}

/**
 * The selected row's child SESSION id (the multiplex sibling under the cursor),
 * read from the SAME `liveRowIndex` order as {@link liveCursorInvokeid}. A
 * re-run seeds from this session's node-entry checkpoint, so it must be the
 * exact sibling — not the invokeid's representative session. Null when no row is
 * selected or the group had no session.
 */
export function liveCursorSession(
  live: LiveMap,
  cursorRow: number | null | undefined,
): string | null {
  if (cursorRow == null) return null;
  const targets = liveRowIndex(liveGroups(live));
  return targets[cursorRow]?.session ?? null;
}
