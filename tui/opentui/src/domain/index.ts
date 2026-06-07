/**
 * Domain layer barrel. Pure logic (aggregate/transcript/entries/wrap/time +
 * the pure `reduceFrame` reducer) is importable with NO OpenTUI/Solid; the
 * Solid wiring (`createDomainStore`, `liveGroups`) lives in `solid-store`.
 *
 * (theme.ts is OWNED BY TASK 007 — not re-exported here to avoid a clash.)
 */

export * from "./types";
export * from "./time";
export * from "./wrap";
export * from "./aggregate";
export * from "./entries";
export * from "./transcript";
export * from "./store";
export * from "./solid-store";
