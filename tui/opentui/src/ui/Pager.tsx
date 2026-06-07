/**
 * Scrollable pager — port of the JLine inspector's pager (`render-pager-lines`
 * + the `:offset`/`:follow?` scroll model in `inspector.clj`'s `render-overlay!`).
 *
 * Used for three content kinds (task 011):
 *   - a per-invocation transcript (SENT/REPLY blocks), which REBUILDS while the
 *     invocation streams so in-flight tokens appear live, and auto-FOLLOWS the
 *     bottom until the user scrolls up (`G`/End re-arms follow);
 *   - an artifact file's text;
 *   - a chart event's pretty-printed detail.
 *
 * Content is a list of {@link StyledLine}s (already width-fitted by the builder,
 * never truncated — long logical lines are pre-wrapped). The pager renders them
 * into a `<scrollbox>` and drives scroll from a controlled `offset` signal so
 * task 012's keybindings (PgUp/PgDn/j/k/b/g/G/Esc) can drive it, while
 * `stickyScroll` keeps a streaming transcript pinned to the bottom.
 *
 * Scroll is line-based: `offset` is the 0-based index of the first visible line.
 * We translate it to `scrollbox.scrollTop` (each line is one row). `follow`
 * means "track the bottom"; any manual scroll-up detaches it (the keymap calls
 * `pageUp`/`lineUp`, which clear follow), and scrolling back to the bottom (or
 * `G`/End) re-arms it.
 */

import { createEffect, type JSX } from "solid-js";
import type { Theme } from "../domain/theme";
import { StyledLines, type StyledLine } from "./styled";

/** A pager's controllable scroll state (owned by Inspector, driven by task 012). */
export interface PagerScroll {
  /** 0-based first-visible line index. */
  offset: () => number;
  setOffset: (n: number) => void;
  /** true ⇒ pinned to the bottom (streaming transcripts). */
  follow: () => boolean;
  setFollow: (b: boolean) => void;
  /** visible row budget (set by the pager on mount/resize). */
  viewportRows: () => number;
  /** total content lines (set by the pager each render). */
  total: () => number;
}

/** Clamp + scroll helpers task 012 binds keys to. They operate on a {@link PagerScroll}. */
export function pagerLineDown(s: PagerScroll, n = 1) {
  const max = Math.max(0, s.total() - s.viewportRows());
  const next = Math.min(max, s.offset() + n);
  s.setOffset(next);
  s.setFollow(next >= max);
}
export function pagerLineUp(s: PagerScroll, n = 1) {
  s.setFollow(false);
  s.setOffset(Math.max(0, s.offset() - n));
}
export function pagerPageDown(s: PagerScroll) {
  pagerLineDown(s, Math.max(1, s.viewportRows() - 1));
}
export function pagerPageUp(s: PagerScroll) {
  pagerLineUp(s, Math.max(1, s.viewportRows() - 1));
}
export function pagerTop(s: PagerScroll) {
  s.setFollow(false);
  s.setOffset(0);
}
export function pagerBottom(s: PagerScroll) {
  s.setFollow(true);
  s.setOffset(Math.max(0, s.total() - s.viewportRows()));
}

export interface PagerProps {
  theme: Theme;
  /** Title rendered in the box header (e.g. "<invokeid> · transcript"). */
  title: string;
  /** The full content, pre-wrapped to width; never truncated. */
  lines: StyledLine[];
  /** Controlled scroll state. */
  scroll: PagerScroll;
  /** Visible interior height in rows (Shell body minus borders); optional. */
  viewportRows?: number;
}

/**
 * The pager view. A bordered `<box>` titled with the content label + the
 * `⇅ pos/total` indicator, containing a focused `<scrollbox>` of the content.
 */
export function Pager(props: PagerProps): JSX.Element {
  let boxEl: any;

  // Keep the scroll state's `total` in sync with the content.
  createEffect(() => {
    props.scroll.total(); // track
    // total is pushed by the parent (Inspector) before render; here we ensure
    // a follow pager stays pinned to the new bottom as content grows.
    if (props.scroll.follow()) {
      const max = Math.max(0, props.lines.length - props.scroll.viewportRows());
      props.scroll.setOffset(max);
    }
  });

  // Translate the controlled offset into scrollbox.scrollTop.
  createEffect(() => {
    const off = props.scroll.offset();
    if (boxEl && typeof boxEl.scrollTo === "function") {
      boxEl.scrollTo({ x: 0, y: off });
    } else if (boxEl) {
      boxEl.scrollTop = off;
    }
  });

  const scrollLabel = () => {
    const total = props.lines.length;
    const rows = props.scroll.viewportRows();
    const pos = Math.min(total, props.scroll.offset() + Math.min(rows, total));
    return `⇅ ${pos}/${total}`;
  };

  return (
    <box
      border
      borderStyle="heavy"
      borderColor={props.theme.fg("border-focus")}
      title={props.title}
      titleAlignment="left"
      bottomTitle={scrollLabel()}
      bottomTitleAlignment="right"
      flexGrow={1}
      flexDirection="column"
      width="100%"
      height="100%"
    >
      <scrollbox
        ref={boxEl}
        focused
        scrollY
        scrollX={false}
        stickyScroll={props.scroll.follow()}
        stickyStart="bottom"
        flexGrow={1}
        width="100%"
      >
        <StyledLines lines={props.lines} />
      </scrollbox>
    </box>
  );
}
