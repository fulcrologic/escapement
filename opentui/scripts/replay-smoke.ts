/**
 * Headless replay smoke (task 005 acceptance): feed the fixture through the
 * real ReplaySource + wire decoder and print decoded-envelope counts. No
 * renderer, no agent, no model. Proves the transport -> decode loop.
 *
 *   bun run scripts/replay-smoke.ts [path]
 */

import { ReplaySource } from "../src/transport/replay";

const path = process.argv[2] ?? "test/fixtures/haiku-sample.jsonl";

const byKind: Record<string, number> = {};
const byEvent: Record<string, number> = {};
let total = 0;
let decodeErrors = 0;

const source = new ReplaySource({ path, timing: "instant" });

source.onFrame((frame) => {
  total += 1;
  byKind[frame.kind] = (byKind[frame.kind] ?? 0) + 1;
  if (frame.kind === "event") {
    byEvent[frame.event] = (byEvent[frame.event] ?? 0) + 1;
  }
});
source.onDecodeError((err, raw) => {
  decodeErrors += 1;
  console.error(`decode error: ${err.message}\n  raw: ${raw.slice(0, 120)}`);
});

let finalStatus = "";
source.onStatus((s, d) => {
  if (s === "closed") finalStatus = d ?? "";
});

source.start();

console.log(`replay smoke: ${path}`);
console.log(`  decoded frames: ${total}`);
console.log(`  decode errors:  ${decodeErrors}`);
console.log(`  final status:   ${finalStatus}`);
console.log("  by kind:");
for (const [k, n] of Object.entries(byKind).sort()) {
  console.log(`    ${k.padEnd(8)} ${n}`);
}
console.log("  by event:");
for (const [e, n] of Object.entries(byEvent).sort((a, b) => b[1] - a[1])) {
  console.log(`    ${e.padEnd(26)} ${n}`);
}

// Non-zero exit if nothing decoded (so CI can gate on it).
if (total === 0) {
  console.error("FAIL: zero frames decoded");
  process.exit(1);
}
