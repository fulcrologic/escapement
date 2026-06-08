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
 * Coarse ordering bucket: `0` = still in flight (streaming/waiting), `1` =
 * terminal (done/error). The PRIMARY key of the live ordering (see
 * {@link compareLiveOrder}): in-flight units/children sort above terminal ones.
 */
export function liveBucket(status: LiveStatus | undefined): number {
  return status === "streaming" || status === "waiting" ? 0 : 1;
}

/** Anything sortable by the live ordering: a status + a last-activity ts. */
export interface LiveOrderable {
  status?: LiveStatus;
  "last-ts"?: number;
}

/**
 * THE single comparator for top-level LIVE ordering — the one source of truth
 * shared by every sort that orders live units (the top-level interleave in
 * `liveRows`, and `liveGroups`). Port of JLINE's `(sort-by [status-rank
 * (- last-ts)])` (`escapement.tui.live/live-display-lines`, live.clj:132): order
 * in-flight units above terminal ones ({@link liveBucket}), then most-recent
 * activity first (`last-ts` DESC). So once a phase's later work finishes the
 * long-lived `host` floats to the top, matching JLINE. Whole blocks may re-order
 * as work completes — that is JLINE's recency behavior and is intended.
 */
export function compareLiveOrder(a: LiveOrderable, b: LiveOrderable): number {
  const r = liveBucket(a.status) - liveBucket(b.status);
  if (r !== 0) return r;
  return (b["last-ts"] ?? 0) - (a["last-ts"] ?? 0);
}

/**
 * Stable first-appearance key for a session: the `llm/start` timestamp, falling
 * back to the first-delta anchor, then last activity. Monotonic per session and
 * unaffected by later token activity, so sorting by it never reshuffles a row.
 */
export function sessionStartKey(v: Partial<LiveSession>): number {
  return v["start-ts"] ?? v["first-ts"] ?? v["last-ts"] ?? 0;
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
 * Resolve an invokeid's capture coordinates `{nodeId, visit}` from the live map
 * (stamped from the `llm/request` event, aggregate fold above). Returns the most
 * recent session's coordinates — for a parallel multiplex group every child
 * shares one node-id; visit is the node-entry counter. Returns null when the
 * invokeid was never seen with a node-id (e.g. a pre-coordinate recording), in
 * which case `request-conversation` falls back to its `visit 0` default and the
 * agent yields an empty editor rather than a wrong path.
 */
export function liveNodeRef(
  live: LiveMap,
  invokeid: string | null,
): { nodeId: string; visit: number } | null {
  if (!invokeid) return null;
  const sessions = live[String(invokeid)]?.sessions;
  if (!sessions) return null;
  let best: LiveSession | null = null;
  for (const s of Object.values(sessions)) {
    if (s.nodeId === undefined) continue;
    if (!best || (s["last-ts"] ?? 0) >= (best["last-ts"] ?? 0)) best = s;
  }
  if (!best || best.nodeId === undefined) return null;
  return { nodeId: best.nodeId, visit: best.visit ?? 0 };
}

/**
 * Resolve the capture coordinates `{nodeId, visit}` for ONE specific session of
 * an invokeid group. Parallel multiplex children share an invokeid but are
 * distinct sub-chart sessions (e.g. `multiplex.poets.4`); each has its OWN
 * node-entry checkpoint keyed by {session, node-id, visit}. A re-run must target
 * the SELECTED row's session — `liveNodeRef` (most-recent-session) would pick a
 * sibling poet's coordinates. Returns null when the session is unknown or never
 * carried a node-id.
 */
export function liveNodeRefForSession(
  live: LiveMap,
  invokeid: string | null,
  session: string | null,
): { nodeId: string; visit: number } | null {
  if (!invokeid || !session) return null;
  const s = live[String(invokeid)]?.sessions?.[String(session)];
  if (!s || s.nodeId === undefined) return null;
  return { nodeId: s.nodeId, visit: s.visit ?? 0 };
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
        "start-ts": ts,
        "last-ts": ts,
        status: "waiting",
        text: "",
        model: (data["model"] as string | null | undefined) ?? undefined,
        provider: (data["provider"] as string | null | undefined) ?? undefined,
        session: sid,
      });

    case "llm/request": {
      // The resolved model/provider land here — emitted right after the request
      // is dispatched, BEFORE the first delta. Stamp them onto the (still
      // `waiting`) session so the `provider/model` column fills in during the
      // wait for the first token, without flipping status or disturbing the
      // recency/start keys. `llm/start` always precedes `llm/request` (with a
      // matching session-id), so we ONLY enrich an existing session — never
      // manufacture one (a request whose session-id we don't recognize, e.g. a
      // pre-`:session-id` recording, must not split a session in two).
      if (!cur) return live;
      return put({
        ...cur,
        model: (data["model"] as string | null | undefined) ?? cur.model,
        provider:
          (data["provider"] as string | null | undefined) ?? cur.provider,
        // Capture coordinates for the debugger's request-conversation (wire §9.1).
        nodeId: (data["node-id"] as string | undefined) ?? cur.nodeId,
        visit: (data["visit"] as number | undefined) ?? cur.visit,
      });
    }

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
        provider: (data["provider"] as string | null | undefined) ?? undefined,
        session: sid,
        kind,
      };
      if (first) {
        next["first-ts"] = ts;
        // Freeze time-to-first-token: gap between llm/start and this first delta.
        // base.start-ts is absent only when the start event was missed; fall back
        // to base.first-ts (the llm/delta-synthesized start) ⇒ 0 wait.
        const startTs = base["start-ts"] ?? base["first-ts"] ?? ts;
        next["wait-ms"] = Math.max(0, ts - startTs);
      }
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
        // A non-streaming turn emits no llm/delta, so model/provider were never
        // stamped on the entry — the response is the first event carrying them.
        // Apply here (keeping any already set) so the `provider/model` column is
        // populated on done rows, not just streaming ones. Mirrors the JLine
        // `:llm/response` handler in escapement.tui.
        model:
          (data["model"] as string | null | undefined) ?? base.model,
        provider:
          (data["provider"] as string | null | undefined) ?? base.provider,
      };
      // A non-streaming turn emits no llm/delta, so `tokens` was never folded
      // from a delta's usage and `chunks` stayed 0 — liveCount would read 0.
      // The response's usage carries the authoritative output-tokens; apply it
      // here (also for streaming turns, as the final count). Mirrors JLine.
      const respUsage = data["usage"] as Record<string, unknown> | undefined;
      const respToks =
        respUsage && respUsage["output-tokens"] !== undefined
          ? (respUsage["output-tokens"] as number)
          : undefined;
      if (respToks !== undefined) next.tokens = respToks;
      if (data["output-tps"] !== undefined && data["output-tps"] !== null) {
        next["real-tps"] = data["output-tps"] as number;
      }
      if (data["elapsed-ms"] !== undefined && data["elapsed-ms"] !== null) {
        next["elapsed-ms"] = data["elapsed-ms"] as number;
      }
      // Engine-measured time-to-first-token (llm.clj). Authoritative for BOTH
      // streamed and non-streamed turns, unlike the client-side delta estimate
      // (which a non-streaming turn never produces). Prefer it; keep any
      // streaming estimate as the fallback when the engine didn't supply one.
      if (data["wait-ms"] !== undefined && data["wait-ms"] !== null) {
        next["wait-ms"] = data["wait-ms"] as number;
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
