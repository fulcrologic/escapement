/**
 * Header strip — port of `escapement.tui.phase/header-lines` (+ `sibling-strip`).
 *
 * Three content rows, drawn inside a bordered box by {@link Shell}:
 *   1. `escapement · <chart>` (bold) + `· <session>` (dim)        ◷ <elapsed> (right)
 *   2. breadcrumb `▶ a › b › c` (or `▶ —`)                  N LLMs · N act · A t/s (right)
 *   3. sibling strip (sliding window centered on current, `…` overflow) OR the
 *      `states: [...]` fallback when no explicit phase model is present.
 *
 * Unlike the Clojure original we don't walk the statechart here — the domain
 * store (task 006) carries `state.phase` (from explicit `phase` snapshots) and
 * `state.config`; we derive breadcrumb/siblings from those, falling back to the
 * raw-config `states:` line exactly like `:fallback?`.
 *
 * Each line is a flex `<text>` of `<span>`s so per-segment theming works; the
 * left/right justification is done with a Yoga `flexDirection="row"` +
 * `justifyContent="space-between"` rather than manual space padding.
 */

import { For, Show, createMemo } from "solid-js";
import type { DomainState } from "../domain/store";
import type { Theme } from "../domain/theme";
import { liveTps } from "../domain/aggregate";
import type { LiveSession } from "../domain/types";
import { displayWidth, truncateDisplay } from "../domain/wrap";

// --- pure phase model from the store (subset of `phase-model`) -------------

interface Sibling {
  id: string;
  current: boolean;
}

export interface HeaderModel {
  chartName: string;
  sessionShort: string;
  /** breadcrumb ids (already short-labelled). */
  breadcrumb: string[];
  /** sibling strip, or null when we fall back to `states:`. */
  siblings: Sibling[] | null;
  /** raw config (the `states:` fallback / metrics context). */
  rawConfig: string[];
  fallback: boolean;
  nLlm: number;
  nActive: number;
  aggTps: number;
}

const ACTIVE_STATUSES = new Set(["streaming", "waiting"]);

function phaseLabel(id: string): string {
  return id.replace(/^:/, "");
}

function allSessions(state: DomainState): LiveSession[] {
  const out: LiveSession[] = [];
  for (const g of Object.values(state.live)) {
    for (const s of Object.values(g.sessions)) out.push(s);
  }
  return out;
}

/** Pure header model — testable, no Solid/OpenTUI. */
export function headerModel(
  state: DomainState,
  chartName: string,
  sessionShort: string,
): HeaderModel {
  const sessions = allSessions(state);
  const active = sessions.filter((s) => ACTIVE_STATUSES.has(s.status));
  const aggTps = active.reduce((acc, s) => acc + liveTps(s), 0);

  const config = state.config ?? [];
  const phase = state.phase;
  const current = phase?.config?.[phase.config.length - 1] ?? config[config.length - 1];

  let breadcrumb: string[] = [];
  let siblings: Sibling[] | null = null;
  let fallback = true;

  if (phase && (phase.breadcrumb?.length || phase.siblings?.length)) {
    breadcrumb = (phase.breadcrumb ?? []).map(phaseLabel);
    if (phase.siblings && phase.siblings.length > 0) {
      siblings = phase.siblings.map((id) => ({ id, current: id === current }));
    }
    fallback = false;
  }

  return {
    chartName,
    sessionShort,
    breadcrumb,
    siblings,
    rawConfig: config,
    fallback,
    nLlm: sessions.length,
    nActive: active.length,
    aggTps,
  };
}

// --- sibling strip sliding window (port of `sibling-strip`) ----------------

type StripKind = "phase-current" | "phase-done" | "phase-upcoming";
export interface StripPiece {
  text: string;
  kind: StripKind;
}
export interface SiblingStrip {
  leftEllipsis: boolean;
  rightEllipsis: boolean;
  pieces: StripPiece[]; // joined by " · "
}

const SEP = " · ";

/**
 * Symmetric sliding window centered on the current sibling, grown until the
 * next addition would overflow `width` display columns, with `…` markers on
 * each overflowing edge. Faithful port of `sibling-strip`'s loop.
 */
export function siblingStrip(
  siblings: Sibling[],
  width: number,
): SiblingStrip | null {
  if (siblings.length === 0 || width <= 0) return null;
  const n = siblings.length;
  let curI = siblings.findIndex((s) => s.current);
  if (curI < 0) curI = 0;

  const pieceText = (i: number): string => {
    const s = siblings[i]!;
    const lbl = phaseLabel(s.id);
    return s.current ? `◉ ${lbl}` : lbl;
  };

  const fits = (
    lo: number,
    hi: number,
    leftEll: boolean,
    rightEll: boolean,
  ): boolean => {
    const plains: string[] = [];
    for (let i = lo; i <= hi; i++) plains.push(pieceText(i));
    const joined = plains.join(SEP);
    const ellL = leftEll ? "… " : "";
    const ellR = rightEll ? " …" : "";
    return displayWidth(ellL + joined + ellR) <= width;
  };

  let lo = curI;
  let hi = curI;
  // Alternate left/right expansion until neither fits.
  for (;;) {
    const le = lo > 0;
    const re = hi < n - 1;
    const growL = le && fits(lo - 1, hi, true, re);
    const growR = re && fits(lo, hi + 1, le, true);
    if (growL) lo--;
    else if (growR) hi++;
    else break;
  }

  const pieces: StripPiece[] = [];
  for (let i = lo; i <= hi; i++) {
    const s = siblings[i]!;
    const kind: StripKind = s.current
      ? "phase-current"
      : i < curI
        ? "phase-done"
        : "phase-upcoming";
    pieces.push({ text: pieceText(i), kind });
  }
  return { leftEllipsis: lo > 0, rightEllipsis: hi < n - 1, pieces };
}

// --- elapsed clock ---------------------------------------------------------

/** M:SS, or H:MM:SS past an hour (port of `elapsed-clock`). */
export function elapsedClock(ms: number): string {
  const secs = Math.floor(Math.max(0, ms) / 1000);
  const h = Math.floor(secs / 3600);
  const m = Math.floor((secs % 3600) / 60);
  const sec = secs % 60;
  const p2 = (x: number) => String(x).padStart(2, "0");
  return h > 0 ? `${h}:${p2(m)}:${p2(sec)}` : `${m}:${p2(sec)}`;
}

// --- component -------------------------------------------------------------

export interface HeaderProps {
  state: DomainState;
  theme: Theme;
  chartName: string;
  sessionShort: string;
  /** ms since session start (driven by the Shell clock). */
  elapsedMs: number;
  /** interior width in columns (box width minus its 2 border cols). */
  width: number;
}

export function Header(props: HeaderProps) {
  const model = createMemo(() =>
    headerModel(props.state, props.chartName, props.sessionShort),
  );
  const elapsed = createMemo(() => elapsedClock(props.elapsedMs));

  const breadcrumbText = createMemo(() => {
    const ids = model().breadcrumb;
    return ids.length > 0 ? `▶ ${ids.join(" › ")}` : "▶ —";
  });

  const metricsText = createMemo(() => {
    const m = model();
    return `${m.nLlm} LLMs · ${m.nActive} act · ${Math.round(m.aggTps)} t/s`;
  });

  const strip = createMemo(() => {
    const m = model();
    if (!m.siblings) return null;
    return siblingStrip(m.siblings, Math.max(0, props.width));
  });

  const fallbackLine = createMemo(() =>
    truncateDisplay(`states: [${model().rawConfig.join(" ")}]`, Math.max(0, props.width)),
  );

  const t = () => props.theme;

  return (
    <box flexDirection="column" width={props.width}>
      {/* line 1: title + session ............ clock */}
      <box flexDirection="row" justifyContent="space-between" width={props.width}>
        <text>
          <span style={{ fg: t().fg("chart-name"), bold: true }}>
            escapement · {model().chartName}
          </span>
          <span> </span>
          <span style={{ fg: t().fg("session-id") }}>· {model().sessionShort}</span>
        </text>
        <text>
          <span style={{ fg: t().fg("timestamp") }}>◷ {elapsed()}</span>
        </text>
      </box>

      {/* line 2: breadcrumb ............ metrics */}
      <box flexDirection="row" justifyContent="space-between" width={props.width}>
        <text>
          <span style={{ fg: t().fg("phase-current") }}>{breadcrumbText()}</span>
        </text>
        <text>
          <span style={{ fg: t().fg("metric") }}>{metricsText()}</span>
        </text>
      </box>

      {/* line 3: sibling strip OR states fallback */}
      <box flexDirection="row" width={props.width}>
        <Show
          when={strip()}
          fallback={
            <text>
              <span style={{ fg: t().fg("phase-upcoming") }}>{fallbackLine()}</span>
            </text>
          }
        >
          {(s: () => SiblingStrip) => (
            <text>
              <Show when={s().leftEllipsis}>
                <span style={{ fg: t().fg("phase-upcoming") }}>… </span>
              </Show>
              <For each={s().pieces}>
                {(piece, i) => (
                  <>
                    <Show when={i() > 0}>
                      <span>{SEP}</span>
                    </Show>
                    <span style={{ fg: t().fg(piece.kind) }}>{piece.text}</span>
                  </>
                )}
              </For>
              <Show when={s().rightEllipsis}>
                <span style={{ fg: t().fg("phase-upcoming") }}> …</span>
              </Show>
            </text>
          )}
        </Show>
      </box>
    </box>
  );
}
