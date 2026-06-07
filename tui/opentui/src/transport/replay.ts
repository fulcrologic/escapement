/**
 * Offline replay transport: read a recorded JSONL fixture (wire doc §8) and
 * emit its envelopes through the SAME `EventSource` interface the live WS
 * client implements. This is the workhorse for offline UI dev + deterministic
 * snapshot tests (tasks 006-016): no live agent, no model.
 *
 * Fixture lines are pre-wrapped with `"kind":"event"` so replay and live share
 * one decoder path. Lines are fed in file order (already `seq`-ordered).
 *
 * Timing modes:
 *  - "instant" (default for tests): emit every frame synchronously on start,
 *    in order. Deterministic; no timers.
 *  - "paced": emit one frame per `intervalMs` tick to mimic a live stream for
 *    demos / eyeballing.
 *  - "wallclock": pace by the recorded `ts` deltas (capped), for realistic demos.
 *
 * The back-channel `send()` is a no-op (replay has no agent to answer to).
 */

import { BaseEventSource } from "./event-source";
import { decodeFrame, WireDecodeError, type OutboundFrame } from "./wire";

export type ReplayTiming = "instant" | "paced" | "wallclock";

export interface ReplayOptions {
  /** Path to the .jsonl fixture, or its already-read contents via `lines`. */
  path?: string;
  /** Pre-split lines (overrides `path`); used by tests to avoid disk. */
  lines?: string[];
  timing?: ReplayTiming;
  /** "paced": ms between frames. Default 40ms. */
  intervalMs?: number;
  /** "wallclock": cap any inter-frame delay to this many ms. Default 250ms. */
  maxStepMs?: number;
  /** Loop the fixture when exhausted (demo mode). Default false. */
  loop?: boolean;
}

/** Resolve fixture lines from `lines` or by reading `path` synchronously. */
function loadLines(opts: ReplayOptions): string[] {
  if (opts.lines) return opts.lines;
  if (!opts.path) {
    throw new Error("ReplaySource: provide either `path` or `lines`");
  }
  // Bun: fs.readFileSync is available; keep it sync for deterministic tests.
  const fs = require("node:fs") as typeof import("node:fs");
  const text = fs.readFileSync(opts.path, "utf8");
  return text.split("\n");
}

export class ReplaySource extends BaseEventSource {
  private opts: ReplayOptions;
  private lines: string[];
  private idx = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private running = false;

  constructor(opts: ReplayOptions) {
    super();
    this.opts = opts;
    this.lines = loadLines(opts);
  }

  start(): void {
    if (this.running) return;
    this.running = true;
    this.idx = 0;
    this.emitStatus("open", this.opts.path ?? "<replay>");

    const timing = this.opts.timing ?? "instant";
    if (timing === "instant") {
      // Drain synchronously, in order -- deterministic for snapshot tests.
      while (this.idx < this.lines.length) this.emitOne();
      this.finish();
    } else {
      this.scheduleNext();
    }
  }

  stop(): void {
    this.running = false;
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    this.emitStatus("closed");
  }

  /** Replay has no live agent; the back-channel is a no-op. */
  send(_frame: OutboundFrame): boolean {
    return false;
  }

  /** Decode + emit the line at `idx`, advancing it. Returns the decoded ts (or null). */
  private emitOne(): number | null {
    const raw = this.lines[this.idx];
    this.idx += 1;
    if (raw === undefined) return null;
    if (raw.trim().length === 0) return null;
    try {
      const frame = decodeFrame(raw);
      if (frame) {
        this.emitFrame(frame);
        const ts = (frame as { ts?: unknown }).ts;
        return typeof ts === "number" ? ts : null;
      }
    } catch (err) {
      if (err instanceof WireDecodeError) {
        this.emitDecodeError(err, err.raw);
      } else {
        this.emitDecodeError(err as Error, raw);
      }
    }
    return null;
  }

  private prevTs: number | null = null;

  private scheduleNext(): void {
    if (!this.running) return;
    if (this.idx >= this.lines.length) {
      if (this.opts.loop) {
        this.idx = 0;
        this.prevTs = null;
      } else {
        this.finish();
        return;
      }
    }

    const timing = this.opts.timing ?? "paced";
    let delay: number;
    if (timing === "wallclock") {
      // Peek the next frame's ts to compute the inter-frame delay.
      const ts = this.emitOne();
      if (this.prevTs !== null && ts !== null) {
        delay = Math.max(0, Math.min(ts - this.prevTs, this.opts.maxStepMs ?? 250));
      } else {
        delay = 0;
      }
      this.prevTs = ts ?? this.prevTs;
    } else {
      this.emitOne();
      delay = this.opts.intervalMs ?? 40;
    }

    this.timer = setTimeout(() => {
      this.timer = null;
      this.scheduleNext();
    }, delay);
  }

  private finish(): void {
    this.running = false;
    this.emitStatus("closed", "replay exhausted");
  }
}
