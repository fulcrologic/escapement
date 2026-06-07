/**
 * Live-token aggregation, ported from `escapement.tui.live` (`status-rank`,
 * `live-count`, `live-tps`, `live-agg`, `short-session`) and the per-session
 * lifecycle fold from `escapement.tui` (`fold-live-event`).
 *
 * PURE + deterministic — no rendering, no wall-clock (timestamps come from the
 * envelopes). The shimmer/bar GEOMETRY is the view's job (task 009); this file
 * only produces the counters those views read.
 *
 * Keying: invokeid -> { sessions: { session-id -> LiveSession } }. Parallel
 * multiplex children share one invokeid; session-id disambiguates siblings.
 */

import type {
  EventLike,
  LiveAgg,
  LiveGroupEntry,
  LiveKind,
  LiveMap,
  LiveSession,
  LiveStatus,
} from "./types";

/** Max chars retained in a live entry's in-flight `text` partial. */
export const LIVE_PARTIAL_TAIL_CHARS = 4096;

/** Trim a string to its last `LIVE_PARTIAL_TAIL_CHARS` characters. */
export function capTail(t: string): string {
  const n = t.length;
  return n > LIVE_PARTIAL_TAIL_CHARS ? t.slice(n - LIVE_PARTIAL_TAIL_CHARS) : t;
}

/** Lower rank sorts first: in-flight stays on top, finished sinks below. */
export const STATUS_RANK: Record<LiveStatus, number> = {
  streaming: 0,
  waiting: 1,
  error: 2,
  done: 3,
};

export function statusRank(status: LiveStatus | undefined): number {
  return status !== undefined && status in STATUS_RANK
    ? STATUS_RANK[status]
    : 3;
}

/**
 * Best available token count: provider running output-tokens if streamed, else
 * the raw chunk count as a proxy. Port of `live-count`.
 */
export function liveCount(v: Partial<LiveSession>): number {
  return v.tokens ?? v.chunks ?? 0;
}

/**
 * Tokens/sec for a live entry. Prefers the LLM's TRUE rate (`real-tps`), else a
 * first→last delta-arrival estimate. Port of `live-tps`. Note `first-ts` is
 * re-anchored to the first delta by the fold, so this excludes time-to-first
 * -token. `(max 1 (- last first))` guards a zero/negative window.
 */
export function liveTps(v: Partial<LiveSession>): number {
  if (v["real-tps"] !== undefined && v["real-tps"] !== null) {
    return v["real-tps"]!;
  }
  const secs =
    Math.max(1, (v["last-ts"] ?? 0) - (v["first-ts"] ?? 0)) / 1000.0;
  return secs > 0 ? liveCount(v) / secs : 0.0;
}

/**
 * Aggregate one invokeid group's sessions into a summary. Port of `live-agg`.
 * `tps` is the SUM of children's real per-session rates (combined generation
 * rate, not a wall-clock figure deflated by sequential queueing).
 */
export function liveAgg(
  sessions: Record<string, LiveSession> | undefined,
): LiveAgg {
  const vs = Object.values(sessions ?? {});
  // best = the most in-flight session (lowest status rank).
  const best = vs
    .slice()
    .sort((a, b) => statusRank(a.status) - statusRank(b.status))[0];
  const tokens = vs.reduce((acc, v) => acc + liveCount(v), 0);
  const lastTs = vs.reduce((acc, v) => Math.max(acc, v["last-ts"] ?? 0), 0);
  const tps = vs.reduce((acc, v) => acc + liveTps(v), 0.0);
  const isActive = (s: LiveStatus | undefined) =>
    s === "streaming" || s === "waiting";
  // `some :model` — first non-nil model in iteration order.
  let model: string | null | undefined = undefined;
  for (const v of vs) {
    if (v.model !== undefined && v.model !== null) {
      model = v.model;
      break;
    }
  }
  return {
    tokens,
    tps,
    status: best?.status,
    n: vs.length,
    "n-active": vs.filter((v) => isActive(v.status)).length,
    "n-done": vs.filter((v) => v.status === "done").length,
    "last-ts": lastTs,
    model,
    text: best?.text,
  };
}

/**
 * True when an invocation is still actively streaming/waiting. Port of
 * `invokeid-live?`.
 */
export function invokeidLive(live: LiveMap, invokeid: string | null): boolean {
  if (!invokeid) return false;
  const sessions = live[String(invokeid)]?.sessions;
  if (!sessions) return false;
  return Object.values(sessions).some(
    (v) => v.status === "streaming" || v.status === "waiting",
  );
}

/**
 * Short label for a child session: drop the `multiplex.` prefix and leading
 * colon, cap at 16 chars (count-based truncate). Port of `short-session`.
 * (Imports truncate lazily to avoid a cycle; uses the count-based form which
 * matches the Clojure `cmp/truncate`.)
 */
export function shortSession(sid: unknown): string {
  let s = String(sid).replace(/^:/, "").replace(/^multiplex\./, "");
  // cmp/truncate: collapse-ws then cap to 16 with trailing "…".
  s = s.replace(/[\x00-\x1f\x7f\s]+/g, " ").trim();
  if (s.length <= 16) return s;
  return s.slice(0, Math.max(0, 16 - 1)) + "…";
}

// --- The per-session lifecycle fold (port of `fold-live-event`) ------------

function dataStr(v: unknown): string | undefined {
  return v === undefined || v === null ? undefined : String(v);
}

/**
 * Fold one transcript event into the `live` map (invokeid -> group). Returns a
 * NEW map (immutable update) — the store wraps this in `produce`/replacement.
 * Port of `fold-live-event` in `escapement.tui`.
 *
 * Wire note: keyword names arrive WITHOUT a leading colon, so `data.type` is
 * `"text-delta"`/`"thinking-delta"` and `stop-reason` is e.g. `"end_turn"`.
 * `session-id` is opaque.
 */
export function foldLiveEvent(live: LiveMap, ev: EventLike): LiveMap {
  const { event, data } = ev;
  const ts = ev.ts ?? Date.now();
  const iid = dataStr(data["invokeid"]);
  if (iid === undefined) return live;
  // Parallel multiplex children share one invokeid; session-id disambiguates.
  const sid = dataStr(data["session-id"]) ?? iid;

  const prevGroup: LiveGroupEntry = live[iid] ?? { sessions: {} };
  const cur: LiveSession | undefined = prevGroup.sessions[sid];

  const put = (next: LiveSession): LiveMap => ({
    ...live,
    [iid]: { ...prevGroup, sessions: { ...prevGroup.sessions, [sid]: next } },
  });

  switch (event) {
    case "llm/start":
      return put({
        chunks: 0,
        chars: 0,
        "first-ts": ts,
        "last-ts": ts,
        status: "waiting",
        text: "",
        model: (data["model"] as string | null | undefined) ?? undefined,
        session: sid,
      });

    case "llm/delta": {
      const base: LiveSession =
        cur ??
        ({
          chunks: 0,
          chars: 0,
          "first-ts": ts,
          "last-ts": ts,
          status: "waiting",
          text: "",
          session: sid,
        } as LiveSession);
      const usage = data["usage"] as Record<string, unknown> | undefined;
      const toks =
        usage && usage["output-tokens"] !== undefined
          ? (usage["output-tokens"] as number)
          : undefined;
      // Re-anchor `first-ts` to the FIRST delta (chunks was 0) so the rate
      // measures generation, not time-to-first-token.
      const first = (base.chunks ?? 0) === 0;
      const deltaText = (data["text"] as string | undefined) ?? "";
      const kind: LiveKind =
        data["type"] === "thinking-delta" ? "thinking" : "text";
      const next: LiveSession = {
        ...base,
        chunks: (base.chunks ?? 0) + 1,
        chars: (base.chars ?? 0) + deltaText.length,
        text: capTail((base.text ?? "") + deltaText),
        "last-ts": ts,
        status: "streaming",
        model: (data["model"] as string | null | undefined) ?? undefined,
        session: sid,
        kind,
      };
      if (first) next["first-ts"] = ts;
      if (toks !== undefined) next.tokens = toks;
      return put(next);
    }

    case "llm/response": {
      const base: LiveSession = cur ?? ({ session: sid } as LiveSession);
      const next: LiveSession = {
        ...base,
        status: "done",
        text: "",
        "last-ts": ts,
        reason: (data["stop-reason"] as string | undefined) ?? base.reason,
      };
      if (data["output-tps"] !== undefined && data["output-tps"] !== null) {
        next["real-tps"] = data["output-tps"] as number;
      }
      if (data["elapsed-ms"] !== undefined && data["elapsed-ms"] !== null) {
        next["elapsed-ms"] = data["elapsed-ms"] as number;
      }
      return put(next);
    }

    case "llm/error":
    case "llm/model-down": {
      const base: LiveSession = cur ?? ({ session: sid } as LiveSession);
      return put({
        ...base,
        status: "error",
        "last-ts": ts,
        reason:
          (data["message"] as string | undefined) ??
          (data["model"] as string | undefined) ??
          null,
      });
    }

    case "llm/worker-exit": {
      const base: LiveSession = cur ?? ({ session: sid } as LiveSession);
      const status: LiveStatus = cur?.status === "error" ? "error" : "done";
      return put({
        ...base,
        status,
        reason: (data["reason"] as string | undefined) ?? base.reason,
        text: "",
        "last-ts": ts,
      });
    }

    default:
      return live;
  }
}
