/**
 * Transcript renderer — port of `escapement.tui.transcript`'s themed
 * `transcript-lines` (+ `header-line`, `fmt-reply-meta`) into {@link StyledLine}s
 * for the inspector pager (task 011).
 *
 * Renders the task-006 SENT/REPLY block model as a two-lane chat log:
 *   - a per-block HEADER line: a direction TAG (` ▸ SENT ` dim-bg / ` ◂ REPLY `
 *     role-hued), the ts, the role-hued label, an optional sublabel, and for
 *     REPLY blocks a meta segment (stop reason · in/out tokens · t/s — or
 *     `◂ streaming · out:N · …` while in-flight);
 *   - a dim hairline rule immediately before each REPLY header;
 *   - one blank line between turns;
 *   - the FULL body, 2-space indented and width-wrapped via `wrapDisplay`
 *     (never truncated);
 *   - an in-flight `▏` cursor appended to the streaming reply's last body line.
 *
 * This is a PURE builder: `transcriptLines(blocks, theme, width)` → StyledLine[].
 * The Pager renders the result. (Bodies are rendered as plain text — the JLine
 * markdown/code highlighting is a documented "optional upgrade"; parity of the
 * lanes/headers/wrapping/cursor comes first per the task.)
 */

import type { TranscriptBlock } from "../domain/types";
import type { StyleSpec, Theme } from "../domain/theme";
import { wrapDisplay } from "../domain/wrap";
import { fgSpan, plain, styled, type StyledLine, type StyledSpan } from "./styled";

const SENT_GLYPH = "▸";
const REPLY_GLYPH = "◂";
const CURSOR_GLYPH = "▏";
const BODY_INDENT = "  ";

/** `· <stop> · in:N out:M · <t/s>` or streaming `· ◂ streaming · out:N · <t/s>`. */
function fmtReplyMeta(meta: TranscriptBlock["meta"]): string {
  const parts: string[] = [];
  if (meta["streaming?"]) {
    parts.push(`${REPLY_GLYPH} streaming`);
    if (meta.out != null) parts.push(`out:${meta.out}`);
    if (meta.tps != null) parts.push(`${meta.tps.toFixed(1)} t/s`);
  } else {
    if (meta.stop) parts.push(meta.stop);
    if (meta.in != null || meta.out != null)
      parts.push(`in:${meta.in ?? "?"} out:${meta.out ?? "?"}`);
    if (meta.tps != null) parts.push(`${meta.tps.toFixed(1)} t/s`);
  }
  return parts.length ? ` · ${parts.join(" · ")}` : "";
}

/** Build the styled header line for one block. Port of `header-line`. */
function headerLine(theme: Theme, block: TranscriptBlock): StyledLine {
  const sent = block.dir === "sent";
  const glyph = sent ? SENT_GLYPH : REPLY_GLYPH;
  const lane = sent ? "SENT " : "REPLY";

  // Direction TAG: bold fg on a colored bg (sent-tag / reply-tag).
  const tagKey = sent ? "sent-tag" : "reply-tag";
  const tagSpec: StyleSpec = theme.style(tagKey);
  const tag: StyledSpan = {
    text: ` ${glyph} ${lane} `,
    fg: tagSpec.fg,
    bg: tagSpec.bg ?? undefined,
    bold: true,
  };

  // label: dim for SENT, role-hue for REPLY.
  const labelColor = sent
    ? theme.themeColor("phase-upcoming")
    : theme.roleColor(block.role);

  const sysSuffix =
    block["collapsible?"] && block.meta.chars != null
      ? ` · ${block.meta.chars} chars`
      : "";
  const subSeg = block.sublabel ? ` · ${block.sublabel}` : "";
  const metaSeg = block.dir === "reply" ? fmtReplyMeta(block.meta) : "";

  const tsSpec = theme.style("timestamp");
  const metricSpec = theme.style("metric");

  const line: StyledLine = [
    tag,
    plain(" "),
    styled(block.ts, tsSpec),
    plain(" · "),
    fgSpan(block.label, labelColor),
    styled(subSeg + sysSuffix, tsSpec),
    styled(metaSeg, metricSpec),
  ];
  return line;
}

/** Wrap a body string into 2-space-indented styled lines (plain fg). */
function bodyLines(body: string, width: number): StyledLine[] {
  const iw = Math.max(1, width);
  const wrapped = wrapDisplay(iw - BODY_INDENT.length, "", body);
  return wrapped.map((l) => [plain(BODY_INDENT + l)] as StyledLine);
}

/**
 * Render `blocks` into themed {@link StyledLine}s for an `interiorW`-column
 * overlay interior. Port of `transcript-lines`.
 */
export function transcriptLines(
  blocks: TranscriptBlock[],
  theme: Theme,
  interiorW: number,
): StyledLine[] {
  const iw = Math.max(1, interiorW);
  const dimSpec = theme.style("border-dim");
  const hairline: StyledLine = [styled("─".repeat(iw), dimSpec)];
  const blank: StyledLine = [plain("")];

  const out: StyledLine[] = [];
  blocks.forEach((block, i) => {
    const reply = block.dir === "reply";
    if (i > 0) out.push(blank);
    if (reply) out.push(hairline);
    out.push(headerLine(theme, block));

    const bls = bodyLines(block.body, iw);
    if (block.meta["streaming?"] && bls.length > 0) {
      // append the in-flight cursor to the last body line.
      const last = bls[bls.length - 1]!;
      bls[bls.length - 1] = [...last, fgSpan(CURSOR_GLYPH, theme.statusColor("streaming"))];
    }
    out.push(...bls);
  });
  return out;
}

/**
 * Build the full transcript page (header + blank + lines) for an invocation.
 * `models`/`nReplies` summarize the head line (model · N replies), mirroring
 * `invocation-transcript-colored-lines`.
 */
export function transcriptPage(
  blocks: TranscriptBlock[],
  theme: Theme,
  interiorW: number,
  opts: { invokeid: string; models: string[]; nReplies: number },
): StyledLine[] {
  const iw = Math.max(1, interiorW);
  const head: StyledLine = [
    styled(
      ` ${opts.invokeid} · ${opts.models.length ? opts.models.join(", ") : "—"}  ·  ${opts.nReplies} replies`,
      theme.style("timestamp"),
    ),
  ];
  if (blocks.length === 0) {
    return [head, [plain("")], [plain("(no turns recorded in the live buffer)")]];
  }
  return [head, [plain("")], ...transcriptLines(blocks, theme, iw)];
}
