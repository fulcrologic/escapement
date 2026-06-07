/**
 * Domain smoke: fold the recorded haiku fixture through the PURE reducer and
 * print the derived live map + live-agg rollups + scrollback/invocation counts.
 * No OpenTUI, no Solid, no model. Full unit tests are task 015 — this is just a
 * sanity check that the port produces a sane live map (deltas excluded from
 * scrollback, per-session counters, status transitions).
 *
 *   bun run scripts/domain-smoke.ts [path/to/fixture.jsonl]
 */

import { decodeFrame } from "../src/transport/wire";
import { reduceFrame, initialDomainState } from "../src/domain/store";
import { liveGroups } from "../src/domain/solid-store";
import { liveTps, liveCount } from "../src/domain/aggregate";
import { transcriptBlocks } from "../src/domain/transcript";
import { liveAgg } from "../src/domain/aggregate";

const path = process.argv[2] ?? "test/fixtures/haiku-sample.jsonl";
const text = await Bun.file(path).text();

let state = initialDomainState();
let decoded = 0;
let skipped = 0;
for (const line of text.split("\n")) {
  if (line.trim().length === 0) continue;
  const frame = decodeFrame(line);
  if (!frame) {
    skipped++;
    continue;
  }
  state = reduceFrame(state, frame);
  decoded++;
}

console.log(`fixture: ${path}`);
console.log(`frames decoded: ${decoded}  skipped: ${skipped}`);
console.log(`scrollback entries: ${state.scrollback.length}`);
console.log(`invocations: ${state.invocations.length}`);
console.log(`inspector events: ${state.events.length}`);
console.log(`active config: ${JSON.stringify(state.config)}`);
console.log("");

console.log("=== LIVE groups (sorted, in-flight on top) ===");
for (const g of liveGroups(state.live)) {
  console.log(
    `  [${g.iid}] status=${g.status} n=${g.n} active=${g["n-active"]} done=${g["n-done"]} ` +
      `tokens=${g.tokens} tps=${g.tps.toFixed(1)} model=${g.model ?? "-"}`,
  );
  for (const [sid, v] of Object.entries(g.sessions)) {
    console.log(
      `      └ ${sid} status=${v.status} chunks=${v.chunks} tok=${liveCount(v)} tps=${liveTps(v).toFixed(1)}`,
    );
  }
}

// Assert deltas never reached scrollback.
const deltaInScrollback = state.scrollback.some((e) => e.ev?.event === "llm/delta");
console.log("");
console.log(`deltas in scrollback (must be false): ${deltaInScrollback}`);

// Sample transcript blocks for the first invocation that has one.
const firstIid = liveGroups(state.live)[0]?.iid;
if (firstIid) {
  const blocks = transcriptBlocks({
    invokeid: firstIid,
    scrollback: state.scrollback,
    live: liveAgg(state.live[firstIid]?.sessions),
    now: () => 0,
  });
  console.log("");
  console.log(`=== transcript blocks for [${firstIid}] (${blocks.length}) ===`);
  for (const b of blocks) {
    console.log(`  ${b.dir.toUpperCase().padEnd(5)} ${b.label}${b.sublabel ? "/" + b.sublabel : ""}  body=${JSON.stringify(b.body.slice(0, 40))}`);
  }
}

if (decoded === 0) {
  console.error("ERROR: zero frames decoded");
  process.exit(1);
}
