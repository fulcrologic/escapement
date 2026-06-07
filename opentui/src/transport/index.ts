/**
 * Transport selection. The UI (task 006) calls `createEventSource()` and gets
 * back the shared `EventSource` interface -- live WS or offline replay,
 * decided by env, without the UI knowing which.
 *
 * Selection:
 *  - OPENTUI_REPLAY=<path>  -> ReplaySource over that JSONL fixture.
 *  - OPENTUI_WS_URL=<url>   -> WsClient to that endpoint.
 *  - else                   -> WsClient to ws://127.0.0.1:<OPENTUI_WS_PORT|3737>/ws
 *
 * Replay timing/loop tuning via OPENTUI_REPLAY_TIMING (instant|paced|wallclock)
 * and OPENTUI_REPLAY_LOOP=1.
 */

import type { EventSource } from "./event-source";
import { ReplaySource, type ReplayTiming } from "./replay";
import { WsClient } from "./ws-client";

export * from "./wire";
export * from "./event-source";
export { ReplaySource } from "./replay";
export { WsClient } from "./ws-client";

export interface CreateEventSourceOptions {
  /** Override OPENTUI_REPLAY. */
  replayPath?: string;
  /** Override the live WS URL. */
  wsUrl?: string;
}

export function createEventSource(
  env: Record<string, string | undefined> = process.env,
  opts: CreateEventSourceOptions = {},
): EventSource {
  const replayPath = opts.replayPath ?? env.OPENTUI_REPLAY;
  if (replayPath) {
    const timing = (env.OPENTUI_REPLAY_TIMING as ReplayTiming) ?? "paced";
    return new ReplaySource({
      path: replayPath,
      timing,
      loop: env.OPENTUI_REPLAY_LOOP === "1",
    });
  }

  const url =
    opts.wsUrl ??
    env.OPENTUI_WS_URL ??
    `ws://127.0.0.1:${env.OPENTUI_WS_PORT ?? "3737"}/ws`;
  return new WsClient({ url });
}
