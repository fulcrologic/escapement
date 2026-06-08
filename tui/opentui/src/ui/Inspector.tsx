/**
 * Inspector overlay — port of `escapement.tui.inspector`'s `render-overlay!`
 * (+ the open/transition helpers) into a Solid component (task 011).
 *
 * A fullscreen-under-header overlay (the Shell's `overlay` slot), toggled by `?`
 * (task 012). Three list views switched by 1/2/3:
 *   - Invocations (default): newest-first invocation history — role · status
 *     glyph+label · tokens · model; a selection cursor; Enter → transcript
 *     pager; `o`/Enter → artifacts drill-in.
 *   - Chart: recent statechart events (newest-first) — ts · event · config
 *     before/after; Enter → event-detail pager.
 *   - Status: active states, mode, step-budget, pause-on-ext, buffered events,
 *     session-dir, artifacts-dir.
 *
 * Drilling an invocation opens a {@link Pager} of its FULL transcript (built from
 * the task-006 block model + fs blob readers), which REBUILDS while the
 * invocation streams (carrying `live-invokeid`) so in-flight tokens appear live.
 * Artifact drill-in lists `<invokeid>.*` files from the shared session-dir and
 * opens them in a pager; `h`/Backspace pops back.
 *
 * The component owns its own view/cursor/focus/pager signals and exposes an
 * imperative {@link InspectorControls} handle (via `ref`) for task 012's
 * keybindings. Rendering of each view is a pure function of (state, selection)
 * so task 016 can snapshot it.
 */

import {
  Match,
  Show,
  Switch,
  createMemo,
  createSignal,
  type JSX,
} from "solid-js";
import type { DomainState } from "../domain/store";
import type { Theme } from "../domain/theme";
import { liveAgg, liveCount, shortSession } from "../domain/aggregate";
import { shortInvokeid } from "../domain/time";
import { transcriptBlocks } from "../domain/transcript";
import type { EventLike, InvocationEntry } from "../domain/types";
import { prStr } from "../domain/entries";
import { fgSpan, plain, styled, type StyledLine } from "./styled";
import { StyledLines } from "./styled";
import { transcriptPage } from "./Transcript";
import {
  Pager,
  pagerBottom,
  pagerLineDown,
  pagerLineUp,
  pagerPageDown,
  pagerPageUp,
  pagerTop,
  type PagerScroll,
} from "./Pager";
import {
  artifactsDir,
  copyToClipboard,
  fmtSize,
  humanSize,
  listAllArtifacts,
  listArtifacts,
  makeBlobReaders,
  readArtifactText,
  type ArtifactEntry,
} from "./inspector/artifacts";

export type InspectorView = "invocations" | "chart" | "status" | "artifacts";

/** A pager's content kind, for the live-rebuild decision + title. */
type PagerKind = "transcript" | "artifact" | "event";

interface PagerModel {
  kind: PagerKind;
  title: string;
  /** for transcript pagers: the invokeid to live-rebuild from. */
  liveInvokeid?: string;
  /** for artifact/event pagers: static pre-built lines. */
  staticLines?: StyledLine[];
}

/** Imperative handle task 012 binds keybindings to. */
export interface InspectorControls {
  isOpen: () => boolean;
  toggle: () => void;
  open: () => void;
  close: () => void;
  /** switch list view (1/2/3). */
  setView: (v: InspectorView) => void;
  view: () => InspectorView;
  /** move the list selection cursor. */
  cursorUp: () => void;
  cursorDown: () => void;
  cursorTop: () => void;
  cursorBottom: () => void;
  /** Enter: drill into the selected row (transcript / event detail / artifact). */
  enter: () => void;
  /** Open the inspector directly to a given invocation's transcript pager
   *  (used by the LIVE pane's Enter drill-in: select row → open its transcript). */
  openTranscriptFor: (invokeid: string) => void;
  /** The `invokeid` of the currently-selected invocation row, or null when not
   *  in the Invocations list view / no row is selected. The seam task 012 uses
   *  to open the ConversationMenu for the selected invocation. */
  selectedInvokeid: () => string | null;
  /** o: open artifacts drill-in for the selected invocation. */
  openArtifacts: () => void;
  /** y: copy the selected artifact's path (Artifacts view). */
  copySelectedPath: () => void;
  /** Y: copy the artifacts directory path (Artifacts view). */
  copyDir: () => void;
  /** h / Backspace: pop back (close pager → artifact list → list view). */
  back: () => void;
  /** is a pager currently open? */
  inPager: () => boolean;
  /** scroll the open pager (no-op if none). */
  scroll: {
    lineUp: () => void;
    lineDown: () => void;
    pageUp: () => void;
    pageDown: () => void;
    top: () => void;
    bottom: () => void;
  };
}

export interface InspectorProps {
  state: DomainState;
  theme: Theme;
  /** interior width in columns. */
  width: number;
  /** interior height in rows (for the pager viewport budget). */
  height?: number;
  /** shared session dir (OPENTUI_SESSION_DIR); enables artifacts + blob bodies. */
  sessionDir?: string | null;
  /** debugger status (task 014), shown in the Status view. */
  debug?: {
    mode?: string;
    stepBudget?: number;
    pauseOnNextExternal?: boolean;
  };
  /** Copy `text` to the system clipboard. Defaults to the OSC-52 stdout writer
   *  ({@link copyToClipboard}); the host passes the renderer's native
   *  `copyToClipboardOSC52` so the escape doesn't race the render thread. */
  copyText?: (text: string) => void;
  ref?: (controls: InspectorControls) => void;
  /**
   * Controlled open state. The host owns it so the overlay can be mounted
   * always (controls persist) while the Shell gates the body on `open`. The
   * Inspector renders its content only when `open()` is true.
   */
  open: () => boolean;
  setOpen: (b: boolean) => void;
}

const STATUS_GLYPH: Record<string, [string, string, string]> = {
  // reason -> [glyph, status-keyword, label]
  stopped: ["✓", "done", "done"],
  interrupted: ["✗", "error", "stop"],
  error: ["✗", "error", "error"],
};

/** Port of `status-glyph+kw`: map an invocation row to [glyph, statusKw, label]. */
function statusGlyphKw(row: InvocationEntry): [string, string, string] {
  if (row["ended-ms"] == null) return ["◂", "streaming", "live"];
  const reason = row.reason == null ? null : String(row.reason);
  if (reason && STATUS_GLYPH[reason]) return STATUS_GLYPH[reason]!;
  if (reason) return ["·", "done", reason];
  return ["✓", "done", "done"];
}

function pad(s: string, n: number): string {
  return s.length >= n ? s : s + " ".repeat(n - s.length);
}
function padNum(n: number, w: number): string {
  const s = String(n);
  return s.length >= w ? s : " ".repeat(w - s.length) + s;
}

export function Inspector(props: InspectorProps): JSX.Element {
  const open = () => props.open();
  const setOpen = (v: boolean | ((p: boolean) => boolean)) => {
    const next = typeof v === "function" ? v(props.open()) : v;
    props.setOpen(next);
  };
  const [view, setViewSig] = createSignal<InspectorView>("invocations");
  const [cursor, setCursor] = createSignal(0);
  // drill-in: the invocation whose artifacts we're browsing (null ⇒ list view).
  const [focusInvoke, setFocusInvoke] = createSignal<string | null>(null);
  const [pager, setPager] = createSignal<PagerModel | null>(null);
  // Transient "✓ copied: …" confirmation shown in the Artifacts view.
  const [copied, setCopied] = createSignal<string | null>(null);

  // pager scroll state
  const [pagerOffset, setPagerOffset] = createSignal(0);
  const [pagerFollow, setPagerFollow] = createSignal(true);
  const viewportRows = () => Math.max(1, (props.height ?? 24) - 2);

  const pagerScroll: PagerScroll = {
    offset: pagerOffset,
    setOffset: setPagerOffset,
    follow: pagerFollow,
    setFollow: setPagerFollow,
    viewportRows,
    total: () => pagerLines().length,
  };

  const blobs = createMemo(() => makeBlobReaders(props.sessionDir));

  // --- list-row models (newest-first) -------------------------------------

  const invocations = () => props.state.invocations;
  const eventRows = createMemo(() => currentEventRows(props.state.events));

  /** rows count for the current selectable list (for cursor clamping). */
  const rowCount = createMemo(() => {
    const fi = focusInvoke();
    if (fi) return listArtifacts(props.sessionDir, fi).length;
    switch (view()) {
      case "invocations":
        return invocations().length;
      case "chart":
        return eventRows().length;
      case "artifacts":
        return listAllArtifacts(props.sessionDir).length;
      default:
        return 0;
    }
  });

  const clampCursor = (n: number) => {
    const max = Math.max(0, rowCount() - 1);
    return Math.min(max, Math.max(0, n));
  };

  // --- transcript pager line building (live-rebuilds while streaming) ------

  const pagerLines = createMemo<StyledLine[]>(() => {
    const p = pager();
    if (!p) return [];
    if (p.kind === "transcript" && p.liveInvokeid) {
      const iid = p.liveInvokeid;
      const sessions = props.state.live[iid]?.sessions ?? {};
      const agg = liveAgg(sessions);
      const blocks = transcriptBlocks({
        invokeid: iid,
        scrollback: props.state.scrollback,
        live: agg,
        blobs: blobs(),
      });
      const models = modelsFor(props.state, iid, agg.model ?? null);
      const nReplies = blocks.filter((b) => b.dir === "reply").length;
      const interiorW = Math.max(20, props.width - 2);
      return transcriptPage(blocks, props.theme, interiorW, {
        invokeid: iid,
        models,
        nReplies,
      });
    }
    return p.staticLines ?? [];
  });

  // --- open/transition helpers (port of inspector.clj's open-*!) -----------

  function openTranscript(iid: string) {
    setPager({ kind: "transcript", title: `${iid} · transcript`, liveInvokeid: iid });
    setPagerFollow(true);
    setPagerOffset(0);
  }

  function openArtifactFile(entry: ArtifactEntry) {
    const text = readArtifactText(entry.path);
    const lines: StyledLine[] = (text ?? `Failed to read: ${entry.path}`)
      .split("\n")
      .map((l) => [plain(l)] as StyledLine);
    setPager({ kind: "artifact", title: entry.name, staticLines: lines });
    setPagerFollow(false);
    setPagerOffset(0);
  }

  function openEventDetail(ev: EventLike) {
    const text = prettyEvent(ev);
    const lines: StyledLine[] = text.split("\n").map((l) => [plain(l)] as StyledLine);
    setPager({
      kind: "event",
      title: `event ${ev.event}`,
      staticLines: lines,
    });
    setPagerFollow(false);
    setPagerOffset(0);
  }

  // --- imperative controls (task 012) -------------------------------------

  const controls: InspectorControls = {
    isOpen: open,
    toggle: () => setOpen((o) => !o),
    open: () => setOpen(true),
    close: () => {
      setOpen(false);
      setPager(null);
      setFocusInvoke(null);
    },
    view,
    setView: (v) => {
      setViewSig(v);
      setCursor(0);
      setFocusInvoke(null);
      setPager(null);
      setCopied(null);
    },
    cursorUp: () => setCursor((c) => clampCursor(c - 1)),
    cursorDown: () => setCursor((c) => clampCursor(c + 1)),
    cursorTop: () => setCursor(0),
    cursorBottom: () => setCursor(clampCursor(rowCount() - 1)),
    enter: () => {
      if (pager()) return; // already drilled
      const fi = focusInvoke();
      if (fi) {
        const arts = listArtifacts(props.sessionDir, fi);
        const a = arts[cursor()];
        if (a) openArtifactFile(a);
        return;
      }
      if (view() === "invocations") {
        const row = invocations()[cursor()];
        if (row?.invokeid) openTranscript(row.invokeid);
      } else if (view() === "chart") {
        const r = eventRows()[cursor()];
        if (r) openEventDetail(r.ev);
      } else if (view() === "artifacts") {
        const a = listAllArtifacts(props.sessionDir)[cursor()];
        if (a) openArtifactFile(a);
      }
    },
    selectedInvokeid: () => {
      // Only the Invocations list view (not drilled into artifacts/pager) maps a
      // cursor row to an invocation. Mirrors the `enter` drill target.
      if (pager() || focusInvoke()) return null;
      if (view() !== "invocations") return null;
      return invocations()[cursor()]?.invokeid ?? null;
    },
    openTranscriptFor: (invokeid: string) => {
      setOpen(true);
      setViewSig("invocations");
      setFocusInvoke(null);
      openTranscript(invokeid);
    },
    openArtifacts: () => {
      if (pager()) return;
      // In the session-wide Artifacts view, `o` opens the selected file.
      if (view() === "artifacts") {
        const a = listAllArtifacts(props.sessionDir)[cursor()];
        if (a) openArtifactFile(a);
        return;
      }
      if (view() !== "invocations") return;
      const row = invocations()[cursor()];
      if (row?.invokeid) {
        setFocusInvoke(row.invokeid);
        setCursor(0);
      }
    },
    copySelectedPath: () => {
      if (view() !== "artifacts") return;
      const a = listAllArtifacts(props.sessionDir)[cursor()];
      if (a) {
        (props.copyText ?? copyToClipboard)(a.path);
        setCopied(a.path);
      }
    },
    copyDir: () => {
      if (view() !== "artifacts") return;
      const dir = artifactsDir(props.sessionDir);
      if (dir) {
        (props.copyText ?? copyToClipboard)(dir);
        setCopied(dir);
      }
    },
    back: () => {
      if (pager()) {
        setPager(null);
        return;
      }
      if (focusInvoke()) {
        setFocusInvoke(null);
        setCursor(0);
        return;
      }
      setOpen(false);
    },
    inPager: () => pager() != null,
    scroll: {
      lineUp: () => pagerLineUp(pagerScroll),
      lineDown: () => pagerLineDown(pagerScroll),
      pageUp: () => pagerPageUp(pagerScroll),
      pageDown: () => pagerPageDown(pagerScroll),
      top: () => pagerTop(pagerScroll),
      bottom: () => pagerBottom(pagerScroll),
    },
  };
  props.ref?.(controls);

  // While a transcript pager is open AND still streaming, `pagerLines()`
  // recomputes reactively as the store updates (the live-rebuild), and the
  // Pager keeps it pinned via `follow`. Nothing extra needed here.

  // --- pure view-row builders (snapshot-testable) -------------------------

  const viewName = () =>
    view() === "invocations"
      ? "Invocations"
      : view() === "chart"
        ? "Chart"
        : view() === "artifacts"
          ? "Artifacts"
          : "Status";

  const listLines = createMemo<StyledLine[]>(() => {
    const fi = focusInvoke();
    if (fi) return artifactListLines(props, fi, cursor());
    switch (view()) {
      case "invocations":
        return invocationListLines(props, cursor());
      case "chart":
        return chartViewLines(props, eventRows(), cursor());
      case "status":
        return statusViewLines(props);
      case "artifacts":
        return artifactInfoLines(props, cursor(), copied());
      default:
        return [[plain(" (unknown view)")]];
    }
  });

  return (
    <Show when={open()}>
      <Switch>
        <Match when={pager()}>
          {(p: () => PagerModel) => (
            <Pager
              theme={props.theme}
              title={p().title}
              lines={pagerLines()}
              scroll={pagerScroll}
              viewportRows={viewportRows()}
            />
          )}
        </Match>
        <Match when={true}>
          <box
            border
            borderStyle="heavy"
            borderColor={props.theme.fg("border-focus")}
            title={`inspector · ${viewName()}${props.state.prompt ? " · 1 prompt waiting" : ""}`}
            titleAlignment="left"
            flexGrow={1}
            flexDirection="column"
            width="100%"
            height="100%"
          >
            <scrollbox focused scrollY scrollX={false} flexGrow={1} width="100%">
              <StyledLines lines={listLines()} />
            </scrollbox>
          </box>
        </Match>
      </Switch>
    </Show>
  );
}

// --- pure list-row builders ------------------------------------------------

export interface EventRow {
  ts?: number;
  eventName: string;
  configBefore?: unknown;
  configAfter?: unknown;
  ev: EventLike;
}

/** Port of `current-event-rows`: newest-first event detail rows. */
export function currentEventRows(events: EventLike[]): EventRow[] {
  return events
    .map((ev) => ({
      ts: ev.ts,
      eventName: String(
        (ev.data["event-name"] as unknown) ?? ev.event ?? "?",
      ),
      configBefore: ev.data["config-before"],
      configAfter: ev.data["config-after"],
      ev,
    }))
    .reverse();
}

function tsHmsMs(ts: number | undefined): string {
  if (ts == null) return "--:--:--.---";
  const d = new Date(ts);
  const p = (n: number, w = 2) => String(n).padStart(w, "0");
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.${p(d.getMilliseconds(), 3)}`;
}

/** Models seen for an invokeid (scrollback model fields, then live model). */
function modelsFor(
  state: DomainState,
  invokeid: string,
  liveModel: string | null,
): string[] {
  const seen = new Set<string>();
  for (const e of state.scrollback) {
    const m = (e.ev?.data?.["model"] as string | undefined) ?? undefined;
    if (m) seen.add(m);
  }
  const models = [...seen];
  if (models.length === 0 && liveModel) return [liveModel];
  return models;
}

/** Invocations list view rows. Selection cursor highlighted (reverse). */
export function invocationListLines(
  props: InspectorProps,
  cursor: number,
): StyledLine[] {
  const hist = props.state.invocations;
  if (hist.length === 0) return [[fgSpan(" (no LLM invocations yet)", props.theme.themeColor("border-dim"))]];
  return hist.map((row, i) => {
    const [glyph, statusKw, label] = statusGlyphKw(row);
    const iid = row.invokeid ?? "?";
    const sid = row["session-id"] == null ? "" : String(row["session-id"]);
    // Per-invocation tokens/model: finished rows carry their OWN frozen count
    // (set at worker-exit); still-streaming rows fall back to their specific
    // live session (keyed by session-id), NOT the shared role aggregate — so
    // concurrent same-role turns no longer all show identical numbers.
    const liveSess =
      sid && props.state.live[iid]?.sessions[sid]
        ? props.state.live[iid]!.sessions[sid]
        : undefined;
    const toks = Math.trunc(
      row.tokens ?? (liveSess ? liveCount(liveSess) : 0),
    );
    const model = row.model ?? liveSess?.model ?? "";
    const roleColor = props.theme.roleColor(iid);
    const statusColor = props.theme.statusColor(statusKw as any);
    const sel = i === cursor;
    const line: StyledLine = [
      plain(" "),
      { text: pad(shortInvokeid(iid) ?? "?", 13), fg: roleColor, reverse: sel },
      plain(" "),
      { text: pad(sid ? shortSession(sid).slice(0, 12) : "", 12), fg: props.theme.themeColor("session-id"), reverse: sel },
      plain("  "),
      { text: `${glyph} ${pad(label, 6)}`, fg: statusColor, reverse: sel },
      plain("  "),
      { text: `${padNum(toks, 5)} tok`, fg: props.theme.themeColor("metric"), reverse: sel },
      plain("  "),
      { text: model, fg: props.theme.themeColor("timestamp"), reverse: sel },
    ];
    return line;
  });
}

/** Chart view rows: active states header + newest-first events. */
export function chartViewLines(
  props: InspectorProps,
  rows: EventRow[],
  cursor: number,
): StyledLine[] {
  const tsSpec = props.theme.style("timestamp");
  const metricSpec = props.theme.style("metric");
  const sessSpec = props.theme.style("session-id");
  const active = props.state.config;
  const out: StyledLine[] = [
    [styled(" active states ", tsSpec), styled(prStr(active), metricSpec)],
    [styled("── recent events (newest first) ──", sessSpec)],
  ];
  rows.forEach((r, i) => {
    const sel = i === cursor;
    out.push([
      plain("  "),
      { ...styled(tsHmsMs(r.ts), tsSpec), reverse: sel },
      plain("  "),
      { text: pad(r.eventName, 22), reverse: sel },
      plain("  "),
      { ...styled(prStr(r.configBefore), sessSpec), reverse: sel },
      { ...styled("  →  ", tsSpec), reverse: sel },
      { ...styled(prStr(r.configAfter), metricSpec), reverse: sel },
    ]);
  });
  return out;
}

/** Status view rows (no selection). */
export function statusViewLines(props: InspectorProps): StyledLine[] {
  const tsSpec = props.theme.style("timestamp");
  const metricSpec = props.theme.style("metric");
  const active = props.state.config;
  const sdir = props.sessionDir ?? null;
  const line = (k: string, v: unknown): StyledLine => [
    plain(" "),
    styled(pad(k, 16), tsSpec),
    styled(String(v), metricSpec),
  ];
  const d = props.debug ?? {};
  return [
    line("active states:", prStr(active)),
    line("mode:", d.mode ?? "n/a"),
    line("step-budget:", d.stepBudget ?? 0),
    line("pause-on-ext?:", Boolean(d.pauseOnNextExternal)),
    line("buffered events:", props.state.events.length),
    line("session-dir:", sdir ?? "—"),
    line("artifacts-dir:", sdir ? `${sdir}/artifacts/` : "—"),
  ];
}

/** Artifact drill-in list rows for a focused invocation. */
export function artifactListLines(
  props: InspectorProps,
  invokeid: string,
  cursor: number,
): StyledLine[] {
  const arts = listArtifacts(props.sessionDir, invokeid);
  const header: StyledLine = [
    fgSpan(
      ` ${invokeid}  ── (Esc/h to go back, Enter/o to view) ──`,
      props.theme.themeColor("timestamp"),
    ),
  ];
  if (arts.length === 0) {
    return [header, [fgSpan("  (no artifacts captured for this invocation)", props.theme.themeColor("border-dim"))]];
  }
  const rows: StyledLine[] = arts.map((a, i) => {
    const sel = i === cursor;
    return [
      { text: `  ${pad(a.name.slice(0, 30), 30)}  ${fmtSize(a.size)}`, reverse: sel },
    ] as StyledLine;
  });
  return [header, ...rows];
}

/**
 * Session-wide Artifacts view: dir + total size header, then one selectable row
 * per artifact (name + human size). Port of inspector.clj's `:artifacts` branch.
 */
export function artifactInfoLines(
  props: InspectorProps,
  cursor: number,
  copied: string | null,
): StyledLine[] {
  const tsSpec = props.theme.style("timestamp");
  const metricSpec = props.theme.style("metric");
  const sessSpec = props.theme.style("session-id");
  const dir = artifactsDir(props.sessionDir);
  const arts = listAllArtifacts(props.sessionDir);
  const total = arts.reduce((acc, a) => acc + (a.size ?? 0), 0);
  const header: StyledLine[] = [
    [styled(" dir:  ", tsSpec), styled(dir ?? "—", metricSpec)],
    [
      styled(" size: ", tsSpec),
      styled(`${humanSize(total)}  (${arts.length} files)`, metricSpec),
    ],
    [
      styled(
        " ── j/k select · Enter/o open · y copy path · Y copy dir · Esc close ──",
        sessSpec,
      ),
    ],
  ];
  if (copied) {
    header.push([fgSpan(` ✓ copied: ${copied}`, props.theme.statusColor("done" as any))]);
  }
  if (arts.length === 0) {
    return [...header, [fgSpan("  (no artifacts in this session)", props.theme.themeColor("border-dim"))]];
  }
  const rows: StyledLine[] = arts.map((a, i) => {
    const sel = i === cursor;
    return [
      { text: `  ${pad(a.name.slice(0, 40), 40)}`, fg: props.theme.themeColor("metric"), reverse: sel },
      { text: `  ${humanSize(a.size)}`, fg: props.theme.themeColor("timestamp"), reverse: sel },
    ] as StyledLine;
  });
  return [...header, ...rows];
}

// --- event pretty-print (port of util/pretty for the event detail pager) ---

function prettyEvent(ev: EventLike): string {
  try {
    return JSON.stringify(ev, null, 2);
  } catch {
    return String(ev);
  }
}
