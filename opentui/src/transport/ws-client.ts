/**
 * Live WebSocket transport (wire doc §1: http-kit WS, bidirectional).
 *
 * Forward frames (agent -> UI) arrive as WS text frames, one JSON object per
 * frame, decoded via `wire.decodeFrame`. The back-channel (answers + control)
 * goes back over the SAME socket via `send()`.
 *
 * Lifecycle: connect with exponential backoff + jitter, reconnect on drop,
 * stop cleanly. Implements the shared `EventSource` interface so the domain
 * layer is transport-agnostic.
 *
 * Uses Bun's global WebSocket (WHATWG), available in the Bun runtime.
 */

import { BaseEventSource } from "./event-source";
import { decodeFrame, encodeFrame, WireDecodeError, type OutboundFrame } from "./wire";

export interface WsClientOptions {
  /** Full ws:// or wss:// URL of the agent's WS endpoint (from task 004 argv/env). */
  url: string;
  /** Initial reconnect delay, ms (doubles each attempt up to maxBackoffMs). */
  baseBackoffMs?: number;
  maxBackoffMs?: number;
  /** Stop retrying after this many consecutive failures (0 = retry forever). */
  maxRetries?: number;
}

export class WsClient extends BaseEventSource {
  private url: string;
  private baseBackoffMs: number;
  private maxBackoffMs: number;
  private maxRetries: number;

  private ws: WebSocket | null = null;
  private retries = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private stopped = false;
  /** Buffer back-channel frames sent before the socket is open. */
  private outbox: string[] = [];

  constructor(opts: WsClientOptions) {
    super();
    this.url = opts.url;
    this.baseBackoffMs = opts.baseBackoffMs ?? 500;
    this.maxBackoffMs = opts.maxBackoffMs ?? 10_000;
    this.maxRetries = opts.maxRetries ?? 0;
  }

  start(): void {
    if (this.ws || this.reconnectTimer) return; // already active
    this.stopped = false;
    this.connect();
  }

  stop(): void {
    this.stopped = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.ws) {
      try {
        this.ws.close(1000, "client stop");
      } catch {
        /* ignore */
      }
      this.ws = null;
    }
    this.emitStatus("closed");
  }

  send(frame: OutboundFrame): boolean {
    const text = encodeFrame(frame);
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(text);
      return true;
    }
    // Not open yet -- buffer and flush on connect.
    this.outbox.push(text);
    return false;
  }

  private connect(): void {
    this.emitStatus(this.retries === 0 ? "connecting" : "reconnecting", this.url);
    let ws: WebSocket;
    try {
      ws = new WebSocket(this.url);
    } catch (e) {
      this.scheduleReconnect((e as Error).message);
      return;
    }
    this.ws = ws;

    ws.addEventListener("open", () => {
      this.retries = 0;
      this.emitStatus("open", this.url);
      // Flush any buffered back-channel frames.
      const pending = this.outbox.splice(0);
      for (const text of pending) ws.send(text);
    });

    ws.addEventListener("message", (ev: MessageEvent) => {
      const raw = typeof ev.data === "string" ? ev.data : String(ev.data);
      try {
        const frame = decodeFrame(raw);
        if (frame) this.emitFrame(frame);
      } catch (err) {
        if (err instanceof WireDecodeError) {
          this.emitDecodeError(err, err.raw);
        } else {
          this.emitDecodeError(err as Error, raw);
        }
      }
    });

    ws.addEventListener("close", () => {
      this.ws = null;
      if (this.stopped) return;
      this.scheduleReconnect("socket closed");
    });

    ws.addEventListener("error", (ev: Event) => {
      // `error` is usually followed by `close`; record detail, let close drive
      // the reconnect so we don't double-schedule.
      const detail = (ev as ErrorEvent)?.message ?? "ws error";
      this.emitStatus("error", detail);
    });
  }

  private scheduleReconnect(detail: string): void {
    if (this.stopped) return;
    this.retries += 1;
    if (this.maxRetries > 0 && this.retries > this.maxRetries) {
      this.emitStatus("error", `giving up after ${this.maxRetries} retries: ${detail}`);
      return;
    }
    const backoff = Math.min(
      this.maxBackoffMs,
      this.baseBackoffMs * 2 ** (this.retries - 1),
    );
    const jitter = Math.random() * backoff * 0.25;
    const delay = backoff + jitter;
    this.emitStatus("reconnecting", `${detail}; retry in ${Math.round(delay)}ms`);
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.connect();
    }, delay);
  }
}
