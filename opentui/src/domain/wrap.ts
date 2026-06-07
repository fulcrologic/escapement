/**
 * Display-width-aware text utilities, ported from `escapement.tui.compositor`
 * (`display-width`, `truncate`, `truncate-display`, `collapse-ws`) and
 * `escapement.tui.transcript` (`wrap-display`).
 *
 * The originals operate on TRUE terminal columns: SGR escape sequences are
 * zero-width, wide glyphs (CJK / fullwidth / emoji) count as 2, the box-drawing
 * + block-element set counts as 1. We reproduce that exactly so transcript /
 * pager wrapping is parity-stable for the snapshot tests (task 016).
 *
 * Pure, no OpenTUI dependency — directly unit-testable (task 015).
 */

const ESC = 0x1b;

/**
 * True when codepoint `cp` occupies two terminal columns. Ported verbatim from
 * `compositor/wide-codepoint?` — box-drawing/block-element glyphs are
 * intentionally treated as NARROW.
 */
export function wideCodepoint(cp: number): boolean {
  return (
    (cp >= 0x1100 && cp <= 0x115f) || // Hangul Jamo
    (cp >= 0x2e80 && cp <= 0x303e) || // CJK radicals / Kangxi / punctuation
    (cp >= 0x3041 && cp <= 0x33ff) || // Hiragana..CJK compat
    (cp >= 0x3400 && cp <= 0x4dbf) || // CJK Ext A
    (cp >= 0x4e00 && cp <= 0x9fff) || // CJK Unified
    (cp >= 0xa000 && cp <= 0xa4cf) || // Yi
    (cp >= 0xac00 && cp <= 0xd7a3) || // Hangul syllables
    (cp >= 0xf900 && cp <= 0xfaff) || // CJK compat ideographs
    (cp >= 0xfe30 && cp <= 0xfe4f) || // CJK compat forms
    (cp >= 0xff00 && cp <= 0xff60) || // Fullwidth forms
    (cp >= 0xffe0 && cp <= 0xffe6) || // Fullwidth signs
    (cp >= 0x1f300 && cp <= 0x1faff) || // emoji / symbols & pictographs
    (cp >= 0x20000 && cp <= 0x3fffd) // CJK Ext B+
  );
}

/** Codepoint at UTF-16 index `i`, plus how many code units it consumed. */
function codePointAt(s: string, i: number): { cp: number; size: number } {
  const c = s.charCodeAt(i);
  if (c >= 0xd800 && c <= 0xdbff && i + 1 < s.length) {
    const lo = s.charCodeAt(i + 1);
    if (lo >= 0xdc00 && lo <= 0xdfff) {
      return { cp: (c - 0xd800) * 0x400 + (lo - 0xdc00) + 0x10000, size: 2 };
    }
  }
  return { cp: c, size: 1 };
}

/** Skip a CSI/SGR sequence starting at ESC index `i`; returns the next index. */
function skipEscape(s: string, i: number, len: number): number {
  if (i + 1 < len && s.charCodeAt(i + 1) === 0x5b /* [ */) {
    let k = i + 2;
    while (k < len) {
      const d = s.charCodeAt(k);
      if (d >= 0x40 && d <= 0x7e) return k + 1;
      k++;
    }
    return k;
  }
  return i + 1;
}

/**
 * Count terminal columns `s` occupies. SGR escapes are zero-width; wide glyphs
 * count as 2; everything else 1. Port of `compositor/display-width`.
 */
export function displayWidth(s: string): number {
  s = String(s);
  const n = s.length;
  let i = 0;
  let w = 0;
  while (i < n) {
    const c = s.charCodeAt(i);
    if (c === ESC) {
      i = skipEscape(s, i, n);
      continue;
    }
    const { cp, size } = codePointAt(s, i);
    w += wideCodepoint(cp) ? 2 : 1;
    i += size;
  }
  return w;
}

/** Collapse all control chars + whitespace runs to single spaces and trim. */
export function collapseWs(s: string): string {
  return String(s)
    .replace(/[\x00-\x1f\x7f\s]+/g, " ")
    .trim();
}

/**
 * Count-based truncate (NOT display-width): collapses whitespace, then if the
 * char count exceeds `n`, keeps `n-1` chars + "…". Port of `compositor/truncate`.
 */
export function truncate(s: string, n: number): string {
  const c = collapseWs(s);
  if (c.length <= n) return c;
  return c.slice(0, Math.max(0, n - 1)) + "…";
}

/**
 * Adjust `s` to occupy exactly `n` terminal columns: clipped (trailing "…")
 * when wider, space-padded right when narrower. Never splits an SGR escape;
 * a wide glyph straddling the boundary is dropped (padded). C0 control chars
 * (except ESC) become a space. Port of `compositor/truncate-display`.
 */
export function truncateDisplay(s: string, n: number): string {
  if (n <= 0) return "";
  s = String(s);
  const len = s.length;
  let out = "";
  let i = 0;
  let w = 0;
  while (i < len) {
    const c = s.charCodeAt(i);
    if (c === ESC) {
      const j = skipEscape(s, i, len);
      out += s.slice(i, j);
      i = j;
      continue;
    }
    let { cp, size } = codePointAt(s, i);
    const pair = size === 2;
    const control = !pair && (cp < 0x20 || cp === 0x7f);
    const ch = control ? " " : s.slice(i, i + size);
    if (control) cp = 0x20;
    const cw = wideCodepoint(cp) ? 2 : 1;
    const nxt = i + size;

    // Is there visible (non-escape) content after this glyph?
    let restContent = false;
    {
      let k = nxt;
      while (k < len) {
        if (s.charCodeAt(k) === ESC) {
          k = skipEscape(s, k, len);
        } else {
          restContent = true;
          break;
        }
      }
    }
    const limit = restContent ? n - 1 : n;

    if (w + cw <= limit) {
      out += ch;
      w += cw;
      i = nxt;
    } else {
      // overflow — pad up to n-1 then ellipsis, stop.
      for (let p = 0; p < n - 1 - w; p++) out += " ";
      if (n >= 1) out += "…";
      return out;
    }
  }
  // consumed all input — pad to n
  for (let p = 0; p < n - w; p++) out += " ";
  return out;
}

/**
 * Word-wrap plain `s` (no SGR) to `width` terminal columns using display-width,
 * preserving a fixed leading `indent` on every physical line. A word wider than
 * the line is hard char-split. Returns a vector of lines (no trailing pad).
 * Never returns [] for non-blank input. Port of `transcript/wrap-display`.
 */
export function wrapDisplay(width: number, indent: string, s: string): string[] {
  width = Math.max(1, width);
  const iw = displayWidth(indent);
  const avail = Math.max(1, width - iw);
  s = String(s).replace(/\s+$/, "");
  if (s.trim().length === 0) return [];

  const out: string[] = [];
  // str/split-lines: split on \n (and \r\n); Clojure drops a trailing newline.
  const logicalLines = s.split(/\r?\n/);
  for (const logical of logicalLines) {
    const words = logical.split(/\s+/).filter((w) => w.length > 0);
    if (words.length === 0) {
      out.push(indent);
      continue;
    }
    let ws = words.slice();
    let cur = "";
    while (ws.length > 0) {
      const w = ws[0]!;
      const ww = displayWidth(w);
      if (ww > avail) {
        // word longer than a whole line → hard split it
        if (cur.trim().length === 0) {
          const room = Math.max(1, avail - displayWidth(cur));
          const here = w.slice(0, Math.min(room, w.length));
          const left = w.slice(here.length);
          ws = [left, ...ws.slice(1)];
          cur = "";
          out.push(indent + here);
        } else {
          // cur non-blank: flush cur, KEEP ws (the long word is reprocessed
          // next iteration against an empty cur).
          out.push(indent + cur);
          cur = "";
        }
      } else if (
        displayWidth(cur) + (cur.trim().length === 0 ? 0 : 1) + ww <=
        avail
      ) {
        // fits on current line
        cur = cur.trim().length === 0 ? w : cur + " " + w;
        ws = ws.slice(1);
      } else {
        // flush and wrap
        out.push(indent + cur);
        cur = "";
      }
    }
    out.push(indent + cur);
  }
  return out;
}
