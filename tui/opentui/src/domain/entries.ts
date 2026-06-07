/**
 * `entries-for` + scrollback helpers, ported from `escapement.tui`
 * (`entries-for` ~175, `update-invocation-history` ~1623,
 * `debug-event-of-interest?` ~1616).
 *
 * PURE: maps one decoded transcript event to a vector of scrollback entries
 * (one per logical line). Keyword names arrive as strings WITHOUT a leading
 * colon (wire doc §2), so `event` is e.g. "runner/started" and a response
 * block `type` is "text"/"thinking"/"tool_use", `stop-reason` is "end_turn".
 *
 * `prStr` reproduces Clojure's `pr-str` closely enough for the summary lines
 * the snapshot tests assert on (vectors as `[a b]`, strings quoted, keywords as
 * `:kw`, maps as `{:k v}`). Values here originate from JSON so we only see
 * strings / numbers / bools / null / arrays / objects.
 */

import type { EntrySource, EventLike, InvocationEntry, ScrollbackEntry } from "./types";
import { truncate } from "./wrap";

function dataStr(v: unknown): string | undefined {
  return v === undefined || v === null ? undefined : String(v);
}

/**
 * Approximate Clojure `pr-str` for JSON-origin values. Strings that look like a
 * serialized keyword (start with `:`) are printed bare (the agent already
 * pr-str'd them upstream — e.g. config vectors arrive as plain strings like
 * "run"); plain strings are quoted. Arrays → space-separated in `[]`; objects →
 * `{k v}` pairs.
 */
export function prStr(v: unknown): string {
  if (v === null || v === undefined) return "nil";
  if (typeof v === "boolean") return v ? "true" : "false";
  if (typeof v === "number") return String(v);
  if (typeof v === "string") {
    // A keyword that was stringified upstream keeps its colon; print it bare.
    // Everything else is a real string → quote it (Clojure pr-str of a string).
    return v.startsWith(":") ? v : JSON.stringify(v);
  }
  if (Array.isArray(v)) {
    return "[" + v.map(prStr).join(" ") + "]";
  }
  if (typeof v === "object") {
    const parts: string[] = [];
    for (const [k, val] of Object.entries(v as Record<string, unknown>)) {
      // map keys are keyword names → print as :key
      parts.push(":" + k + " " + prStr(val));
    }
    return "{" + parts.join(", ") + "}";
  }
  return String(v);
}

const HUMAN_EVENTS = new Set([
  "human-input/start",
  "human-input/answer",
  "human-input/cancelled",
  "human-input/error",
  "human-input/validation-failed",
  "human-input/interrupted",
]);
const ERROR_EVENTS = new Set([
  "llm/error",
  "llm/model-down",
  "llm/model-policy-empty",
  "runner/error",
  "runner/aborted",
]);
const DEBUG_EVENTS = new Set([
  "debug/awaiting-quit",
  "debug/awaiting-step",
  "runner/started",
  "runner/start-config",
  "runner/done",
]);

/** The source lane for an event when it carries no invokeid. */
function laneFor(event: string): EntrySource {
  if (HUMAN_EVENTS.has(event)) return "human";
  if (ERROR_EVENTS.has(event)) return "error";
  if (DEBUG_EVENTS.has(event)) return "debug";
  return "chart";
}

const RESP_BLOCK_TYPES = new Set(["text", "thinking", "tool_use"]);

/**
 * Return scrollback entries for transcript event `ev`. Returns [] when the
 * event has no scrollback relevance. Port of `entries-for`.
 */
export function entriesFor(ev: EventLike): ScrollbackEntry[] {
  const { event, data } = ev;
  const iid = dataStr(data["invokeid"]);
  const src: EntrySource = iid ?? laneFor(event);

  switch (event) {
    case "runner/started":
      return [
        {
          source: "debug",
          glyph: "·",
          ev,
          summary: `runner started session=${dataStr(data["session-id"])} chart=${dataStr(data["chart-id"])}`,
        },
      ];
    case "runner/start-config":
      return [
        {
          source: "debug",
          glyph: "·",
          ev,
          summary: `start config=${prStr(data["config"])}`,
        },
      ];
    case "runner/event-processed":
      return [
        {
          source: "chart",
          glyph: "·",
          ev,
          summary: `${dataStr(data["event-name"]) ?? ""}  ${prStr(data["config-before"])} → ${prStr(data["config-after"])}`,
        },
      ];
    case "runner/done":
      return [
        {
          source: "debug",
          glyph: "·",
          ev,
          summary: `done final=${prStr(data["final-config"])}`,
        },
      ];
    case "runner/aborted":
      return [
        { source: "error", glyph: "⚠", ev, summary: `aborted ${dataStr(data["reason"]) ?? ""}` },
      ];
    case "runner/error":
      return [
        {
          source: "error",
          glyph: "⚠",
          ev,
          summary: `ERROR ${truncate(asStr(data["message"]), 200)}`,
        },
      ];

    case "llm/start":
      return [
        {
          source: src,
          glyph: "·",
          ev,
          summary: `invocation start session=${dataStr(data["session-id"])}`,
        },
      ];
    case "llm/worker-exit":
      return [
        {
          source: src,
          glyph: "·",
          ev,
          summary: `invocation exit reason=${dataStr(data["reason"])}`,
        },
      ];
    case "llm/user-message":
      return [
        { source: src, glyph: "▸", ev, summary: truncate(asStr(data["text"]), 240) },
      ];

    case "llm/request":
      return [
        {
          source: src,
          glyph: "·",
          ev,
          summary:
            "req " +
            (data["model"] != null ? `model=${dataStr(data["model"])} ` : "") +
            `n-messages=${dataStr(data["n-messages"])}`,
        },
      ];

    case "llm/response": {
      const blocks = (data["content"] as unknown[] | undefined) ?? [];
      const usage = (data["usage"] as Record<string, unknown> | undefined) ?? {};
      const stop = dataStr(data["stop-reason"]);
      const entries: ScrollbackEntry[] = [];
      for (const b0 of blocks) {
        const b = b0 as Record<string, unknown>;
        const t = b["type"] as string;
        if (!RESP_BLOCK_TYPES.has(t)) continue;
        if (t === "text") {
          entries.push({ source: src, glyph: "◂", ev, block: b, summary: truncate(asStr(b["text"]), 240) });
        } else if (t === "thinking") {
          entries.push({ source: src, glyph: "…", ev, block: b, summary: truncate(asStr(b["thinking"]), 240) });
        } else {
          entries.push({
            source: src,
            glyph: "⚙",
            ev,
            block: b,
            summary: `${dataStr(b["name"]) ?? ""} ${truncate(prStr(b["input"] ?? {}), 200)}`,
          });
        }
      }
      const tail: ScrollbackEntry = {
        source: src,
        glyph: stop === "end_turn" ? "✓" : "·",
        ev,
        summary:
          `resp stop=${stop}` +
          (data["model"] != null ? ` model=${dataStr(data["model"])}` : "") +
          ` tokens=in:${usage["input-tokens"] ?? "?"}/out:${usage["output-tokens"] ?? "?"}` +
          (data["output-tps"] != null ? ` ${dataStr(data["output-tps"])}t/s` : "") +
          (data["elapsed-ms"] != null ? ` ${dataStr(data["elapsed-ms"])}ms` : ""),
      };
      entries.push(tail);
      return entries;
    }

    case "llm/tool-result":
      return [
        {
          source: src,
          glyph: "↩",
          ev,
          summary:
            `${dataStr(data["tool"]) ?? ""}` +
            (data["is-error"] ? " (ERROR)" : "") +
            `  ${truncate(asStr(data["content-preview"]), 200)}`,
        },
      ];

    case "llm/context-warning":
      return [
        {
          source: src,
          glyph: "⚠",
          ev,
          summary: `context ${Math.trunc(100 * ((data["used-frac"] as number | undefined) ?? 0))}%`,
        },
      ];

    case "llm/error":
      return [
        { source: "error", glyph: "⚠", ev, summary: `llm error ${truncate(asStr(data["message"]), 200)}` },
      ];

    case "llm/model-down":
      return [
        {
          source: "error",
          glyph: "⚠",
          ev,
          summary: `model-down ${dataStr(data["model"]) ?? "<default>"} — ${truncate(asStr(data["message"]), 120)}`,
        },
      ];

    case "llm/model-policy-empty":
      return [
        {
          source: "error",
          glyph: "⚠",
          ev,
          summary:
            `model policy ${prStr(data["policy"])} filter empty` +
            (data["strict?"] ? " (strict: node failed)" : ""),
        },
      ];

    case "human-input/start":
      return [
        {
          source: "human",
          glyph: "?",
          ev,
          summary:
            `prompt kind=${dataStr(data["kind"])}` +
            (data["prompt"] != null ? ` : ${truncate(asStr(data["prompt"]), 200)}` : ""),
        },
      ];

    case "human-input/answer":
      return [
        {
          source: "human",
          glyph: "!",
          ev,
          summary:
            `answer kind=${dataStr(data["kind"])}` +
            ("answer" in data ? ` = ${truncate(prStr(data["answer"]), 200)}` : ""),
        },
      ];

    case "human-input/cancelled":
      return [{ source: "human", glyph: "⚠", ev, summary: "cancelled" }];

    case "human-input/error":
      return [
        { source: "error", glyph: "⚠", ev, summary: `human ERROR ${truncate(asStr(data["message"]), 200)}` },
      ];

    case "checkpoint/written":
    case "runner/tick":
      return [];

    case "debug/awaiting-quit":
      return [
        { source: "debug", glyph: "·", ev, summary: asStr(data["msg"]) || "Press Ctrl-C to quit." },
      ];
    case "debug/awaiting-step":
      return [
        {
          source: "debug",
          glyph: "·",
          ev,
          summary:
            `PAUSED on event=${dataStr(data["event-name"])}` +
            (data["external?"] ? " (external)" : ""),
        },
      ];

    default:
      return [
        {
          source: src,
          glyph: "·",
          ev,
          summary: `${event || "unknown"} ${truncate(prStr(data ?? {}), 200)}`,
        },
      ];
  }
}

function asStr(v: unknown): string {
  return v === undefined || v === null ? "" : String(v);
}

/** True for events the inspector keeps in its ring buffer. Port of `debug-event-of-interest?`. */
export function debugEventOfInterest(ev: EventLike): boolean {
  const e = ev.event;
  if (e === "runner/event-processed") return true;
  // namespace == "debug"
  return e.startsWith("debug/");
}

/**
 * Fold an `llm/start` / `llm/worker-exit` event into the invocations history
 * (newest first, capped 200). Port of `update-invocation-history`.
 */
export function updateInvocationHistory(
  history: InvocationEntry[],
  ev: EventLike,
): InvocationEntry[] {
  const e = ev.event;
  const d = ev.data;
  const ts = ev.ts ?? Date.now();
  if (e === "llm/start") {
    const entry: InvocationEntry = {
      invokeid: dataStr(d["invokeid"]) ?? null,
      "session-id": d["session-id"],
      "started-ms": ts,
      "ended-ms": null,
      reason: null,
    };
    return [entry, ...history].slice(0, 200);
  }
  if (e === "llm/worker-exit") {
    const invokeid = dataStr(d["invokeid"]) ?? null;
    const reason = d["reason"];
    return history.map((row) =>
      row.invokeid === invokeid && row["ended-ms"] == null
        ? { ...row, "ended-ms": ts, reason }
        : row,
    );
  }
  return history;
}
