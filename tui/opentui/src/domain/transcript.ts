/**
 * Per-invocation SENT/REPLY transcript block model, ported from
 * `escapement.tui.transcript` (`transcript-blocks`, `response-blocks`).
 *
 * PURE: turns an invocation's buffered events into a vector of
 * `{dir, ts, label, sublabel, role, meta, body, collapsible?}` blocks in
 * chronological order, including the in-flight (streaming) reply. Defensive —
 * missing fields never throw.
 *
 * Blob reading (the captured request.edn / response.edn full bodies) is
 * OPTIONAL and injected: `BlobReaders`. When absent, we fall back to the inline
 * event snippets exactly like the Clojure `or` fallbacks — so this layer stays
 * pure + unit-testable with no filesystem (the snapshot tests inject readers
 * only when exercising the artifact path; task 011 wires the real fs readers).
 *
 * NOTE: this returns the PURE block model only; theme/width rendering
 * (`transcript-lines`) is the view's job (task 011) using `wrap.ts`.
 */

import type {
  EventLike,
  LiveAgg,
  ScrollbackEntry,
  TranscriptBlock,
} from "./types";
import { tsToHms } from "./time";
import { collapseWs } from "./wrap";

/** Optional captured-blob readers (best effort; inline snippets are fallback). */
export interface BlobReaders {
  /** Full system prompt from a request.edn blob, keyed by io/ref. */
  system?(ioRef: string | undefined): string | null | undefined;
  /** Full assistant content blocks from a response.edn blob. */
  content?(ioRef: string | undefined): unknown[] | null | undefined;
  /** Full text of a captured blob (tool-result body). */
  text?(ioRef: string | undefined): string | null | undefined;
}

/** Inputs the store provides; mirrors what `transcript-blocks` reads off `h`. */
export interface TranscriptInput {
  /** invokeid being rendered. */
  invokeid: string;
  /** All scrollback entries (we filter to this invokeid + distinct `ev`). */
  scrollback: ScrollbackEntry[];
  /** Live rollup for this invokeid's sessions (for the in-flight reply). */
  live: LiveAgg;
  /** Optional blob readers; falls back to inline data when absent. */
  blobs?: BlobReaders;
  /**
   * Clock for the in-flight reply's timestamp. Defaults to Date.now; tests can
   * pin it for determinism.
   */
  now?: () => number;
}

function asString(x: unknown): string {
  return x === undefined || x === null ? "" : String(x);
}

/** One-line, length-capped rendering of tool_use args. Port of `collapse-args`. */
function collapseArgs(s: string): string {
  const c = collapseWs(s);
  return c.length > 60 ? c.slice(0, 59) + "…" : c;
}

/**
 * `util/pretty` produces a pprint string; for the pure port we approximate with
 * a stable JSON rendering (the value is then collapsed to one line by
 * `collapseArgs`, so exact whitespace does not matter for the accent line).
 */
function pretty(x: unknown): string {
  try {
    return JSON.stringify(x ?? {}, null, 1);
  } catch {
    return String(x);
  }
}

const REPLY_BLOCK_TYPES = new Set(["text", "thinking", "tool_use"]);

/** Turn one llm/response event into REPLY blocks. Port of `response-blocks`. */
function responseBlocks(
  invokeid: string,
  ev: EventLike,
  blobs?: BlobReaders,
): TranscriptBlock[] {
  const data = ev.data;
  const hms = tsToHms(ev.ts);
  const usage = (data["usage"] as Record<string, unknown> | undefined) ?? {};
  const meta = {
    stop: (data["stop-reason"] as string | undefined) ?? null,
    in: (usage["input-tokens"] as number | undefined) ?? null,
    out: (usage["output-tokens"] as number | undefined) ?? null,
    tps: (data["output-tps"] as number | undefined) ?? null,
  };
  const ioRef = data["io/ref"] as string | undefined;
  const content =
    (blobs?.content?.(ioRef) ?? undefined) ??
    (data["content"] as unknown[] | undefined) ??
    [];

  const blocks: TranscriptBlock[] = [];
  for (const b0 of content) {
    const b = b0 as Record<string, unknown>;
    const t = b["type"] as string;
    if (!REPLY_BLOCK_TYPES.has(t)) continue;
    if (t === "text") {
      blocks.push({
        dir: "reply",
        ts: hms,
        label: "assistant",
        sublabel: null,
        role: invokeid,
        body: asString(b["text"]),
        meta: {},
      });
    } else if (t === "thinking") {
      // Encrypted-reasoning providers (e.g. OpenAI) can return a thinking block
      // whose plaintext is empty — only the encrypted signature survives. There
      // is nothing to show, so drop it. Dropping a leading empty thinking block
      // promotes the sibling text block to blocks[0], which then carries the
      // turn's usage meta below.
      const thinking = asString(b["thinking"]);
      if (thinking.trim() === "") continue;
      blocks.push({
        dir: "reply",
        ts: hms,
        label: "assistant",
        sublabel: "thinking",
        role: invokeid,
        body: thinking,
        meta: {},
      });
    } else {
      // tool_use
      const args = pretty(b["input"] ?? {});
      blocks.push({
        dir: "reply",
        ts: hms,
        label: "assistant",
        sublabel: `${asString(b["name"])}(${collapseArgs(args)})`,
        role: invokeid,
        body: "",
        meta: {},
      });
    }
  }
  if (blocks.length > 0) {
    const head = blocks[0]!;
    blocks[0] = { ...head, meta: { ...head.meta, ...meta } };
    return blocks;
  }
  // empty content (e.g. pure stop) → one bare reply header carrying meta.
  return [
    {
      dir: "reply",
      ts: hms,
      label: "assistant",
      sublabel: null,
      role: invokeid,
      body: "",
      meta,
    },
  ];
}

/**
 * Build the SENT/REPLY block vector for `invokeid`. Chronological; includes the
 * in-flight streaming reply (when present) as a `meta.streaming?` block.
 * Port of `transcript-blocks`.
 */
export function transcriptBlocks(input: TranscriptInput): TranscriptBlock[] {
  const { invokeid, scrollback, live, blobs } = input;
  const now = input.now ?? Date.now;

  // One transcript event yields several scrollback entries sharing the same
  // `ev`; dedup so a turn isn't rendered once per content block.
  const seen = new Set<EventLike>();
  const evs: EventLike[] = [];
  for (const e of scrollback) {
    if (e.source != null && String(e.source) === invokeid) {
      const ev = e.ev;
      if (ev && typeof ev === "object" && !seen.has(ev)) {
        seen.add(ev);
        evs.push(ev);
      }
    }
  }

  const firstReq = evs.find((e) => e.event === "llm/request");
  const reqData = firstReq?.data ?? {};
  const system =
    (blobs?.system?.(reqData["io/ref"] as string | undefined) ?? undefined) ??
    (reqData["system-preview"] as string | undefined) ??
    (reqData["system"] as string | undefined) ??
    null;
  const systemTs = tsToHms(firstReq?.ts);
  const sysBlock: TranscriptBlock | null =
    system != null
      ? {
          dir: "sent",
          ts: systemTs,
          label: "system",
          sublabel: null,
          role: invokeid,
          meta: { chars: asString(system).length },
          body: asString(system),
          "collapsible?": true,
        }
      : null;

  const bodyBlocks: TranscriptBlock[] = [];
  for (const ev of evs) {
    const { event, data } = ev;
    const hms = tsToHms(ev.ts);
    switch (event) {
      case "llm/user-message":
        bodyBlocks.push({
          dir: "sent",
          ts: hms,
          label: "user",
          sublabel: null,
          role: invokeid,
          meta: {},
          body: asString(data["text"]),
        });
        break;
      case "llm/tool-result": {
        const full =
          (blobs?.text?.(data["io/ref"] as string | undefined) ?? undefined) ??
          (data["content-preview"] as string | undefined) ??
          "";
        bodyBlocks.push({
          dir: "sent",
          ts: hms,
          label: "tool",
          sublabel:
            asString(data["tool"]) + (data["is-error"] ? " (ERROR)" : ""),
          role: invokeid,
          meta: {},
          body: asString(full),
        });
        break;
      }
      case "llm/response":
        bodyBlocks.push(...responseBlocks(invokeid, ev, blobs));
        break;
      case "llm/error":
        bodyBlocks.push({
          dir: "reply",
          ts: hms,
          label: "error",
          sublabel: null,
          role: invokeid,
          meta: {},
          body: asString(data["message"]),
        });
        break;
      default:
        // start/request/delta/retry/etc. fold into headers — skip.
        break;
    }
  }

  // In-flight reply: deltas for the current (not-yet-finalized) turn.
  const liveTxt = live.text;
  const pending: TranscriptBlock[] =
    liveTxt != null && liveTxt.trim().length > 0
      ? [
          {
            dir: "reply",
            ts: tsToHms(now()),
            label: "assistant",
            sublabel: null,
            role: invokeid,
            meta: {
              "streaming?": true,
              out: live.tokens,
              // live-agg has no :output-tps; the Clojure reads (:output-tps live)
              // which is nil on the agg map → null here.
              tps: null,
            },
            body: asString(liveTxt),
          },
        ]
      : [];

  const out: TranscriptBlock[] = [];
  if (sysBlock) out.push(sysBlock);
  out.push(...bodyBlocks, ...pending);
  return out;
}
