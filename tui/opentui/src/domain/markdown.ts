/**
 * Lightweight Markdown → themed {@link StyledLine}s for the transcript body.
 * PURE port of `escapement.tui.markdown` (the JLine TUI's terminal markdown
 * renderer) into the sidecar's span model — instead of emitting SGR escape
 * strings, every styled run becomes a {@link StyledSpan} so OpenTUI renders it
 * via `<span style={…}>`.
 *
 * `render(md, theme, width)` turns a markdown string into a vector of
 * display-width-correct lines (≤ `width` terminal columns each). The caller
 * (Transcript) prefixes its own body-indent and appends the streaming cursor.
 *
 * Supported (line-oriented — each source line is its own block, so poem/verse
 * line-breaks are preserved):
 *     # / ## / ###…  headings (md-h1/h2/h3)
 *     ``` lang        fenced code → dim `▏` gutter (no per-token highlight)
 *     >  quote        blockquote (italic dim-green, `▏` gutter)
 *     - / * / 1.      list items (colored bullet + hanging indent)
 *     ---             horizontal rule
 *     (paragraph)     inline: **bold** *italic* _em_ `code` [text](url)
 *
 * A ```markdown / ```md fenced block renders its body AS markdown (models often
 * wrap a whole reply in one). Inline parsing is non-nesting + best-effort.
 */

import type { Theme, ThemeKey } from "./theme";
import { displayWidth, truncateDisplay } from "./wrap";
import { plain, styled, type StyledLine, type StyledSpan } from "../ui/styled";

/** A styled run from inline parsing: text + an optional theme key (null = plain). */
interface Span {
  text: string;
  key: ThemeKey | null;
}

/** A wrap token: one whitespace-free word carrying its inline theme key. */
interface Word {
  w: string;
  key: ThemeKey | null;
}

const BLANK: StyledLine = [plain("")];

/** Build a {@link StyledSpan} for `text` styled by theme key `key` (null ⇒ plain). */
function span(theme: Theme, key: ThemeKey | null, text: string): StyledSpan {
  return key ? styled(text, theme.style(key)) : plain(text);
}

// --- inline span parsing ---------------------------------------------------

/**
 * Parse inline markdown in `s` into ordered {@link Span}s. Order-preserving and
 * non-nesting (port of `inline-spans`): `**bold**`/`__bold__`, `` `code` ``,
 * `*italic*`/`_italic_`, and `[text](url)` links.
 */
function inlineSpans(s0: string): Span[] {
  const s = String(s0);
  const n = s.length;
  const out: Span[] = [];
  let buf = "";
  const flush = () => {
    if (buf.length > 0) {
      out.push({ text: buf, key: null });
      buf = "";
    }
  };
  const delim = (open: string, close: string, key: ThemeKey): [Span, number] | null => {
    const ol = open.length;
    if (s.substr(i, ol) !== open) return null;
    // reject the doubled-delimiter case (e.g. `****`) like the Clojure source.
    if (s.substr(i + ol, ol) === open) return null;
    const e = s.indexOf(close, i + ol);
    if (e === -1 || e <= i + ol - 1) return null;
    return [{ text: s.slice(i + ol, e), key }, e + close.length];
  };

  let i = 0;
  while (i < n) {
    let hit: [Span, number] | null = null;
    // link [text](url)
    if (s[i] === "[") {
      const rb = s.indexOf("]", i);
      if (rb !== -1 && rb + 1 < n && s[rb + 1] === "(") {
        const rp = s.indexOf(")", rb);
        if (rp !== -1) hit = [{ text: s.slice(i + 1, rb), key: "md-link" }, rp + 1];
      }
    }
    hit =
      hit ??
      delim("**", "**", "md-bold") ??
      delim("__", "__", "md-bold") ??
      delim("`", "`", "md-code") ??
      delim("*", "*", "md-italic") ??
      delim("_", "_", "md-italic");
    if (hit) {
      flush();
      out.push(hit[0]);
      i = hit[1];
    } else {
      buf += s[i];
      i++;
    }
  }
  flush();
  return out;
}

/** Flatten spans into word tokens (split on whitespace; carry the key). */
function spansToWords(spans: Span[]): Word[] {
  const out: Word[] = [];
  for (const sp of spans) {
    for (const w of String(sp.text).split(/\s+/)) {
      if (w.length > 0) out.push({ w, key: sp.key });
    }
  }
  return out;
}

/**
 * Greedy word-wrap styled tokens to `width` display columns, returning styled
 * lines. A word wider than the line is hard-split on display columns. Port of
 * `wrap-words` (each word keeps its own style across a line break).
 */
function wrapWords(theme: Theme, width0: number, words: Word[]): StyledLine[] {
  const width = Math.max(1, width0);
  if (words.length === 0) return [];
  const ws = words.slice();
  const out: StyledLine[] = [];
  let cur: StyledLine = [];
  let curw = 0;
  const flush = () => {
    out.push(cur);
    cur = [];
    curw = 0;
  };
  let idx = 0;
  while (idx < ws.length) {
    const { w, key } = ws[idx]!;
    const ww = displayWidth(w);
    if (ww > width) {
      // word longer than a whole line → hard char-split
      const room = Math.max(1, width - curw - (curw === 0 ? 0 : 1));
      const here = w.slice(0, Math.min(room, w.length));
      const left = w.slice(here.length);
      if (curw === 0) {
        cur.push(span(theme, key, here));
        flush();
        ws[idx] = { w: left, key };
      } else {
        flush();
      }
    } else if (curw + (curw === 0 ? 0 : 1) + ww <= width) {
      if (curw > 0) {
        cur.push(plain(" "));
        curw += 1;
      }
      cur.push(span(theme, key, w));
      curw += ww;
      idx++;
    } else {
      flush();
    }
  }
  if (cur.length > 0) out.push(cur);
  return out;
}

/**
 * Render one inline-markdown source line to wrapped styled lines at `width`
 * columns, each prefixed with `hang` (a plain indent). Port of `inline-line`.
 * An empty result becomes a single blank line.
 */
function inlineLine(theme: Theme, width: number, hang: string, s: string): StyledLine[] {
  const hw = displayWidth(hang);
  const lines = wrapWords(theme, Math.max(1, width - hw), spansToWords(inlineSpans(s)));
  if (lines.length === 0) return [BLANK.slice()];
  return lines.map((l) => (hang.length > 0 ? [plain(hang), ...l] : l));
}

/**
 * Re-style the plain runs of `line` with theme key `k`, leaving already
 * inline-styled runs intact. Approximates the JLine "paint the whole line with
 * the heading/quote SGR" behaviour (headings + blockquotes wrap inline spans).
 */
function paintLine(theme: Theme, k: ThemeKey, line: StyledLine): StyledLine {
  const spec = theme.style(k);
  return line.map((sp) =>
    sp.fg == null && !sp.bold && !sp.italic && !sp.underline
      ? styled(sp.text, spec)
      : sp,
  );
}

// --- block renderer --------------------------------------------------------

const FENCE_RE = /^\s*```+\s*(\S+)?\s*$/;
const HEADING_RE = /^(#{1,6})\s+(.*)$/;
const RULE_RE = /^\s*([-*_])(?:\s*\1){2,}\s*$/;
const QUOTE_RE = /^\s*>\s?(.*)$/;
const LIST_RE = /^(\s*)([-*+]|\d+[.)])\s+(.*)$/;
const NUM_MARKER_RE = /^\d+[.)]$/;

/** True when a fence info-string marks the block as Markdown (rendered as md). */
function markdownLang(lang: string | null | undefined): boolean {
  return !!lang && ["markdown", "md", "mkd", "mdown"].includes(lang.toLowerCase());
}

/** Render a fenced code block's `buf` as plain dim-gutter code (no highlight). */
function codeBlockLines(theme: Theme, width: number, buf: string[]): StyledLine[] {
  const fenceSpec = theme.style("code-fence");
  const codeSpec = theme.style("code-plain");
  const bar = styled("  ▏ ", fenceSpec);
  return buf.map((l) => [bar, styled(truncateDisplay(l, Math.max(1, width - 4)), codeSpec)]);
}

interface FenceState {
  lang: string | null;
  buf: string[];
}

/**
 * Render markdown `md` to a vector of themed, display-width-correct lines at
 * `width` terminal columns. Fenced code blocks render as plain dim-gutter code;
 * a ```markdown / ```md fence renders its body AS markdown.
 */
export function render(md: string, theme: Theme, width0: number): StyledLine[] {
  const width = Math.max(4, width0);
  const src = String(md).split(/\r?\n/);
  const out: StyledLine[] = [];
  let fence: FenceState | null = null;

  const closeFence = (f: FenceState) =>
    markdownLang(f.lang) ? render(f.buf.join("\n"), theme, width) : codeBlockLines(theme, width, f.buf);

  for (const line of src) {
    if (fence) {
      if (FENCE_RE.test(line)) {
        out.push(...closeFence(fence));
        fence = null;
      } else {
        fence.buf.push(line);
      }
      continue;
    }

    const fm = FENCE_RE.exec(line);
    if (fm) {
      fence = { lang: fm[1] ?? null, buf: [] };
      continue;
    }

    const hm = HEADING_RE.exec(line);
    if (hm) {
      const k: ThemeKey = hm[1]!.length === 1 ? "md-h1" : hm[1]!.length === 2 ? "md-h2" : "md-h3";
      for (const ln of inlineLine(theme, width, "", hm[2] ?? "")) out.push(paintLine(theme, k, ln));
      continue;
    }

    if (RULE_RE.test(line)) {
      out.push([styled("─".repeat(width), theme.style("md-rule"))]);
      continue;
    }

    const qm = QUOTE_RE.exec(line);
    if (qm) {
      const bar = styled("▏ ", theme.style("md-rule"));
      for (const ln of inlineLine(theme, Math.max(1, width - 2), "", qm[1] ?? "")) {
        out.push([bar, ...paintLine(theme, "md-quote", ln)]);
      }
      continue;
    }

    const lm = LIST_RE.exec(line);
    if (lm) {
      const lead = lm[1] ?? "";
      const marker = lm[2] ?? "-";
      const txt = lm[3] ?? "";
      const bullet = NUM_MARKER_RE.test(marker) ? marker : "•";
      const hang = " ".repeat(lead.length + bullet.length + 1);
      const lines = inlineLine(theme, width, hang, txt);
      lines.forEach((ln, i) => {
        if (i === 0) {
          // swap the leading hang indent for the bullet prefix.
          const rest = ln[0] && ln[0].text === hang ? ln.slice(1) : ln;
          out.push([plain(lead), styled(bullet, theme.style("md-bullet")), plain(" "), ...rest]);
        } else {
          out.push(ln);
        }
      });
      continue;
    }

    if (line.trim() === "") {
      out.push(BLANK.slice());
      continue;
    }

    // paragraph line
    for (const ln of inlineLine(theme, width, "", line)) out.push(ln);
  }

  // end of input: flush an unterminated fence (a still-streaming reply whose
  // closing ``` hasn't arrived yet) so its body still renders.
  if (fence) out.push(...closeFence(fence));
  return out;
}

// --- finalized-body cache --------------------------------------------------

/**
 * Memoize rendered lines for FINALIZED bodies, keyed by `[capability, width,
 * md]`. A finalized turn's body never changes, so re-rendering every prior turn
 * each frame (while a later turn streams, or on every pager repaint) is pure
 * waste — the symptom the JLine TUI calls the "can't page while streaming" lag.
 * Output depends on the theme only through the `md-*` style specs, which are
 * fully determined by `theme.capability`, so that string is a sound key marker.
 *
 * Bounded clear-on-overflow (matching `transcript.clj`'s `body-cache`). The
 * STREAMING tail must NOT come through here — its body grows each frame and
 * would fill the cache with dead keys; callers render the in-flight block via
 * {@link render} directly (see `Transcript.bodyLines`).
 */
const BODY_CACHE_MAX = 4096;
const bodyCache = new Map<string, StyledLine[]>();

/** Cached {@link render} for finalized bodies. See {@link bodyCache}. */
export function renderCached(md: string, theme: Theme, width: number): StyledLine[] {
  const key = `${theme.capability} ${width} ${md}`;
  const hit = bodyCache.get(key);
  if (hit) return hit;
  const lines = render(md, theme, width);
  if (bodyCache.size > BODY_CACHE_MAX) bodyCache.clear();
  bodyCache.set(key, lines);
  return lines;
}
