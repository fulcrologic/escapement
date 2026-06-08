/**
 * Styled-line primitives shared by the inspector / transcript / pager (task 011).
 *
 * The JLine TUI builds each overlay line as a width-correct string carrying SGR
 * escapes (theme paint). OpenTUI renders styling via `<span style={…}>` rather
 * than raw SGR, so here a "line" is a list of {@link StyledSpan}s and a "page"
 * is a list of {@link StyledLine}s. Builders (Transcript / Inspector) produce
 * these pure arrays; {@link StyledLines} renders them as a column of `<text>`
 * rows. Keeping the model as plain data (not JSX) makes the builders pure and
 * snapshot-testable (task 016): a snapshot can assert the `.text` of each span.
 */

import { For, type JSX } from "solid-js";
import type { StyleSpec } from "../domain/theme";

/** One styled run of text within a line. */
export interface StyledSpan {
  text: string;
  /** foreground hex, or null/undefined ⇒ terminal default. */
  fg?: string | null;
  bg?: string | null;
  bold?: boolean;
  italic?: boolean;
  underline?: boolean;
  /** reverse-video (used for the selection cursor highlight). */
  reverse?: boolean;
}

/** One physical line = a list of spans (already width-fitted by the builder). */
export type StyledLine = StyledSpan[];

/** Convenience: a plain (unstyled) span. */
export function plain(text: string): StyledSpan {
  return { text };
}

/** A span carrying a decoded {@link StyleSpec} (fg/bg/bold/…). */
export function styled(text: string, spec: StyleSpec | null | undefined): StyledSpan {
  if (!spec) return { text };
  return {
    text,
    fg: spec.fg,
    bg: spec.bg ?? undefined,
    bold: spec.bold || undefined,
    italic: spec.italic || undefined,
    underline: spec.underline || undefined,
  };
}

/** A span with just a foreground hue. */
export function fgSpan(text: string, fg: string | null | undefined): StyledSpan {
  return { text, fg };
}

/** Total display length of a line as a plain string (sum of span texts). */
export function lineText(line: StyledLine): string {
  let s = "";
  for (const sp of line) s += sp.text;
  return s;
}

/** Render a single styled line as a `<text>` of `<span>`s. */
export function StyledLineView(props: { line: StyledLine }): JSX.Element {
  return (
    <text>
      <For each={props.line}>
        {(sp) => (
          <span
            style={{
              fg: sp.fg ?? undefined,
              bg: sp.bg ?? undefined,
              bold: sp.bold,
              italic: sp.italic,
              underline: sp.underline,
              // OpenTUI's createTextAttributes reads `inverse`, NOT `reverse`;
              // passing `reverse` is silently dropped (no selection highlight).
              inverse: sp.reverse,
            }}
          >
            {sp.text.length > 0 ? sp.text : " "}
          </span>
        )}
      </For>
    </text>
  );
}

/** Render a column of styled lines (one `<text>` per line). */
export function StyledLines(props: { lines: StyledLine[] }): JSX.Element {
  return (
    <For each={props.lines}>{(line) => <StyledLineView line={line} />}</For>
  );
}
