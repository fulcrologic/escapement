/**
 * LOG pane — the scrollable event scrollback, parity-matched to the JLine TUI
 * LOG pane (`escapement.tui.log/log-pane-lines` + `log-entry->line`, fed by
 * `entries-for`). Each row is a role-hued source tag + a status-colored glyph +
 * a one-line summary, prefixed by a dim timestamp, truncated to the pane
 * interior width. A cursor-selected row is reverse-video highlighted.
 *
 * SCROLL MODEL (faithful to the Clojure original): `scrollOffset` is the number
 * of entries scrolled UP from the newest. `0` = bottom / live tail (newest
 * entry visible at the bottom). Increasing the offset reveals older entries.
 * The offset is clamped to `[0, max(0, total - rows)]`. The position indicator
 * is `{pos,total}` where `pos` is the 1-based absolute index of the bottom-most
 * visible entry (== total when fully scrolled to the tail), `0/0` when empty.
 *
 * `:llm/delta` events are NEVER in the scrollback (the store routes deltas to
 * the LIVE map only — task 006), so they cannot appear here.
 *
 * The rendering core ({@link logPaneModel}) is a PURE function of
 * (entries, theme, width, rows, scrollOffset, cursorIdx) for the snapshot tests
 * (task 016). Scroll + cursor are signal-driven (props) so task 012 can map
 * j/k/g/G/PgUp/PgDn (scroll/cursor) and Enter (open the selected invocation).
 */

import { For, createMemo, type JSX } from "solid-js";
import type { Theme } from "../domain/theme";
import type { EntrySource, ScrollbackEntry } from "../domain/types";
import { tsToHms, shortInvokeid } from "../domain/time";
import { truncateDisplay } from "../domain/wrap";

/** The semantic lane names (non-invokeid sources). Their fixed hues come from the theme. */
const LANE_NAMES = new Set(["chart", "human", "debug", "error", "viz"]);

/** Display tag for an entry's source — short invokeid, or the lane name. */
export function logSourceTag(source: EntrySource): string {
  if (LANE_NAMES.has(source)) return source;
  // an invokeid string → short form (last path segment, capped)
  return shortInvokeid(source) ?? String(source);
}

/** Scroll position indicator, mirroring `log-pane-lines`'s `:scroll`. */
export interface LogScroll {
  /** 1-based absolute index of the bottom-most visible entry; 0 when empty. */
  pos: number;
  /** Total scrollback entry count. */
  total: number;
}

/** One rendered LOG row, ready to drop into a `<text>`. */
export interface LogRow {
  /** Absolute index into the scrollback (for cursor math / drill-in). */
  abs: number;
  /** Dim timestamp (hh:mm:ss). */
  ts: string;
  /** Role-hued source tag. */
  tag: string;
  /** Foreground hex for the tag, or undefined (terminal default). */
  tagFg: string | undefined;
  /** Status glyph (or `·`). */
  glyph: string;
  /** Glyph color — error red on the error lane, else undefined (plain). */
  glyphFg: string | undefined;
  /** One-line summary (already truncated upstream by entries-for). */
  summary: string;
  /** True when this row is the cursor-selected one (reverse-video). */
  selected: boolean;
}

/** Output of the pure render core. */
export interface LogPaneModel {
  rows: LogRow[];
  scroll: LogScroll;
}

/**
 * Pure render core. Computes the visible window of the scrollback for the given
 * interior height + scroll offset, the per-row styling tokens, and the scroll
 * indicator. 1:1 with `log-pane-lines` (the window math) + `log-entry->line`
 * (the styling) — but keeps styling as data (the component emits `<span>`s).
 */
export function logPaneModel(
  entries: readonly ScrollbackEntry[],
  theme: Theme,
  rows: number,
  scrollOffset: number,
  cursorIdx?: number | null,
): LogPaneModel {
  const total = entries.length;
  const room = Math.max(0, rows | 0);
  const maxOff = Math.max(0, total - room);
  const off = Math.min(Math.max(0, scrollOffset | 0), maxOff);
  const end = Math.max(0, total - off);
  const start = Math.max(0, end - room);

  const out: LogRow[] = [];
  for (let i = start; i < end; i++) {
    const e = entries[i];
    if (!e) continue;
    const source = e.source;
    out.push({
      abs: i,
      ts: tsToHms(e.ev?.ts),
      tag: logSourceTag(source),
      tagFg: theme.roleColor(source) ?? undefined,
      glyph: e.glyph || "·",
      glyphFg: source === "error" ? (theme.statusColor("error") ?? undefined) : undefined,
      summary: e.summary ?? "",
      selected: cursorIdx != null && i === cursorIdx,
    });
  }

  return {
    rows: out,
    scroll: { pos: total === 0 ? 0 : end, total },
  };
}

export interface LogPaneProps {
  /** The full scrollback (newest last); deltas already excluded by the store. */
  entries: readonly ScrollbackEntry[];
  theme: Theme;
  /** Interior width in columns (box width minus borders). */
  width: number;
  /** Visible interior rows. */
  height: number;
  /** Scroll offset from the tail (0 = bottom/newest). Signal-driven (task 012). */
  scrollOffset?: number;
  /** Absolute index of the cursor-selected entry, or null (no selection). */
  cursorIdx?: number | null;
  /** Whether the pane is focused — only show the cursor highlight when focused. */
  focused?: boolean;
  /** Receives the computed scroll indicator so the Shell can render `⇅ pos/total`. */
  onScroll?: (s: LogScroll) => void;
}

/**
 * The LOG pane content (no border — the Shell's `<Pane>` draws the frame). A
 * fixed-height column of role-hued rows computed from the scroll/cursor signals.
 */
export function LogPane(props: LogPaneProps): JSX.Element {
  const model = createMemo(() => {
    const m = logPaneModel(
      props.entries,
      props.theme,
      props.height,
      props.scrollOffset ?? 0,
      props.focused ? props.cursorIdx : null,
    );
    props.onScroll?.(m.scroll);
    return m;
  });

  return (
    <box flexDirection="column" flexGrow={1} width={props.width}>
      <For each={model().rows}>
        {(row) => {
          // Layout: `<ts> <tag> <glyph> <summary>`, fit to interior width.
          const line = `${row.ts} ${row.tag} ${row.glyph} ${row.summary}`;
          const fit = truncateDisplay(line, props.width);
          if (row.selected) {
            // Reverse-video the whole fitted row (parity with `reverse-on-s`).
            return (
              <text>
                <span style={{ reverse: true }}>{fit}</span>
              </text>
            );
          }
          // Re-segment the fitted line back into ts / tag / glyph / summary so
          // each gets its own hue. We rebuild from the known prefix widths
          // rather than re-truncating each segment, to keep the row exactly
          // `width` columns (the way `log-entry->line` colors in place).
          const tsSeg = row.ts;
          const tagSeg = row.tag;
          const glyphSeg = row.glyph;
          // remainder of the fitted line after `ts + " " + tag + " " + glyph + " "`
          const prefixLen = tsSeg.length + 1 + tagSeg.length + 1 + glyphSeg.length + 1;
          const summarySeg = fit.length > prefixLen ? fit.slice(prefixLen) : "";
          return (
            <text>
              <span style={{ fg: props.theme.fg("timestamp") }}>{tsSeg}</span>
              <span>{" "}</span>
              <span style={{ fg: row.tagFg }}>{tagSeg}</span>
              <span>{" "}</span>
              <span style={{ fg: row.glyphFg }}>{glyphSeg}</span>
              <span>{" "}</span>
              <span>{summarySeg}</span>
            </text>
          );
        }}
      </For>
    </box>
  );
}
