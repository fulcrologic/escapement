/**
 * The single event-source interface the UI domain layer (task 006) subscribes
 * to. Both the live WebSocket client (`ws-client.ts`) and the offline replay
 * driver (`replay.ts`) implement it, so live / replay are swappable WITHOUT
 * touching any UI code -- the domain store only ever sees this interface.
 *
 * Forward frames (agent -> UI) arrive via the `frame` listener. Connection
 * lifecycle is reported via the `status` listener. The back-channel
 * (UI -> agent: answers + control) is `send()`; replay's `send` is a no-op.
 */

import type { ForwardFrame, OutboundFrame } from "./wire";

export type ConnectionStatus =
  | "idle" // constructed, not yet started
  | "connecting" // dialing / first attempt
  | "open" // receiving frames
  | "reconnecting" // dropped, backing off + retrying (live only)
  | "closed" // intentionally stopped, or replay exhausted
  | "error"; // unrecoverable failure

export type FrameListener = (frame: ForwardFrame) => void;
export type StatusListener = (status: ConnectionStatus, detail?: string) => void;
/** Notified on a frame that failed to decode (live can keep going). */
export type DecodeErrorListener = (err: Error, raw: string) => void;
/**
 * Notified on an AGENT→UI control frame (`kind:"control"`). Normally control
 * flows UI→agent, but the agent pushes ONE control op — `run-finished` — when a
 * chart reaches final-config under keep-alive (wire doc §6 / Task 002). The op
 * (and the raw frame, for `final-config`) is surfaced here so the app can hold a
 * "finished — press Ctrl-C to quit" frame instead of tearing down.
 */
export type ControlListener = (op: string, frame: Record<string, unknown>) => void;

export interface EventSource {
  /** Begin connecting / replaying. Idempotent: a second call is a no-op while active. */
  start(): void;
  /** Stop and release resources (close socket / cancel timer). */
  stop(): void;
  /** Current connection status. */
  readonly status: ConnectionStatus;
  /** Send a back-channel frame (answer/control). No-op + false for replay. */
  send(frame: OutboundFrame): boolean;

  onFrame(fn: FrameListener): () => void;
  onStatus(fn: StatusListener): () => void;
  onDecodeError(fn: DecodeErrorListener): () => void;
  /** Subscribe to agent→UI control ops (e.g. `run-finished`). */
  onControl(fn: ControlListener): () => void;
}

/**
 * Tiny listener-set base both transports extend. Keeps the pub/sub plumbing
 * (and the unsubscribe contract) in one place so ws-client / replay only
 * implement transport mechanics.
 */
export abstract class BaseEventSource implements EventSource {
  protected _status: ConnectionStatus = "idle";
  private frameListeners = new Set<FrameListener>();
  private statusListeners = new Set<StatusListener>();
  private decodeErrorListeners = new Set<DecodeErrorListener>();
  private controlListeners = new Set<ControlListener>();

  get status(): ConnectionStatus {
    return this._status;
  }

  onFrame(fn: FrameListener): () => void {
    this.frameListeners.add(fn);
    return () => this.frameListeners.delete(fn);
  }
  onStatus(fn: StatusListener): () => void {
    this.statusListeners.add(fn);
    return () => this.statusListeners.delete(fn);
  }
  onDecodeError(fn: DecodeErrorListener): () => void {
    this.decodeErrorListeners.add(fn);
    return () => this.decodeErrorListeners.delete(fn);
  }
  onControl(fn: ControlListener): () => void {
    this.controlListeners.add(fn);
    return () => this.controlListeners.delete(fn);
  }

  protected emitFrame(frame: ForwardFrame): void {
    for (const fn of this.frameListeners) fn(frame);
  }
  protected emitStatus(status: ConnectionStatus, detail?: string): void {
    this._status = status;
    for (const fn of this.statusListeners) fn(status, detail);
  }
  protected emitDecodeError(err: Error, raw: string): void {
    for (const fn of this.decodeErrorListeners) fn(err, raw);
  }
  protected emitControl(op: string, frame: Record<string, unknown>): void {
    for (const fn of this.controlListeners) fn(op, frame);
  }

  abstract start(): void;
  abstract stop(): void;
  abstract send(frame: OutboundFrame): boolean;
}
