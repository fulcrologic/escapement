/**
 * Tiny shared helpers ported from `escapement.tui.util` (`ts->hms`,
 * `short-invokeid`). Pure.
 */

import { truncate } from "./wrap";

/**
 * Format a unix-ms timestamp as HH:MM:SS in the LOCAL timezone (matches the
 * Clojure `java.text.SimpleDateFormat "HH:mm:ss"` in the local zone).
 */
export function tsToHms(ts: number | undefined): string {
  const d = new Date(ts ?? Date.now());
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/**
 * Strip a namespace-style prefix from `id` (split on `/` or `.`, take the last
 * segment) and cap at ~10 chars. Port of `short-invokeid`.
 */
export function shortInvokeid(id: unknown): string | undefined {
  if (id === undefined || id === null) return undefined;
  const s = String(id);
  const parts = s.split(/[/.]/);
  const last = parts.length > 0 ? parts[parts.length - 1]! : s;
  return truncate(last.length > 0 ? last : s, 10);
}
