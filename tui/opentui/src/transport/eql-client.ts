/**
 * EQL/HTTP client for the agent's `POST /api` surface.
 *
 * Per wire doc §1/§6, the WebSocket back-channel is the PRIMARY path for
 * control + answers; this EQL client is the documented SECONDARY/fallback and
 * the path for one-shot reads (e.g. `:chart/definition` for the visualizer,
 * live debugger polls). It is intentionally THIN -- used only by the
 * control/human tasks (006/014) when WS is unavailable.
 *
 * The server (`escapement.ui.server`) speaks **transit+json** over `POST /api`:
 * the request body is an EQL query, the response is a transit-encoded result.
 * To avoid pulling the full transit-js stack into the Bun bundle (wire doc §2:
 * "no transit-js on the hot path"), this module ships a MINIMAL transit+json
 * codec covering only the EQL shapes the UI needs: keywords, vectors, maps,
 * join-maps `{kw [..]}`, and scalars. It is NOT a general transit
 * implementation -- if a resolver returns an exotic transit tag this will
 * surface it as a raw `["~#tag", value]` pair for the caller to handle.
 */

// --- Minimal transit+json codec -------------------------------------------
//
// transit+json ground rules used here:
//  - keyword  -> "~:foo/bar"  (string with "~:" prefix)
//  - symbol   -> "~$sym"
//  - a map is `["^ ", k1, v1, k2, v2, ...]` (cmap) OR a plain JSON object whose
//    keys are strings; resolver outputs use the array map form for kw keys.
//  - vectors  -> JSON arrays.
//  - scalars  -> JSON scalars; strings starting with "~" are escaped as "~~".

/** A decoded transit keyword, kept distinct from plain strings. */
export class Keyword {
  constructor(public readonly name: string) {}
  toString(): string {
    return ":" + this.name;
  }
}

export function kw(name: string): Keyword {
  return new Keyword(name.startsWith(":") ? name.slice(1) : name);
}

function encodeKey(k: unknown): string {
  if (k instanceof Keyword) return "~:" + k.name;
  if (typeof k === "string") return k.startsWith("~") ? "~~" + k.slice(1) : k;
  return String(k);
}

function encodeValue(v: unknown): unknown {
  if (v instanceof Keyword) return "~:" + v.name;
  if (v === null || v === undefined) return null;
  if (Array.isArray(v)) return v.map(encodeValue);
  if (typeof v === "string") return v.startsWith("~") ? "~~" + v.slice(1) : v;
  if (v instanceof Map) {
    // Map preserves Keyword keys (needed for EQL mutations `{kw {..}}`).
    const out: unknown[] = ["^ "];
    for (const [k, val] of v.entries()) {
      out.push(encodeKey(k), encodeValue(val));
    }
    return out;
  }
  if (typeof v === "object") {
    // Plain object -> transit cmap array form; string keys are encoded as-is.
    const out: unknown[] = ["^ "];
    for (const [k, val] of Object.entries(v as Record<string, unknown>)) {
      out.push(encodeKey(k), encodeValue(val));
    }
    return out;
  }
  return v;
}

/** Encode an EQL query (a JS value tree) to a transit+json string. */
export function encodeTransit(query: unknown): string {
  return JSON.stringify(encodeValue(query));
}

function decodeScalar(s: string): unknown {
  if (s.startsWith("~:")) return new Keyword(s.slice(2));
  if (s.startsWith("~$")) return s.slice(2); // symbol -> name string
  if (s.startsWith("~~")) return "~" + s.slice(2);
  return s;
}

/** Decode a parsed transit+json value (the result of JSON.parse). */
export function decodeTransit(node: unknown): unknown {
  if (typeof node === "string") return decodeScalar(node);
  if (Array.isArray(node)) {
    if (node[0] === "^ ") {
      // cmap: ["^ ", k1, v1, ...]
      const m: Record<string, unknown> = {};
      for (let i = 1; i < node.length; i += 2) {
        const rawKey = decodeTransit(node[i]);
        const key = rawKey instanceof Keyword ? rawKey.name : String(rawKey);
        m[key] = decodeTransit(node[i + 1]);
      }
      return m;
    }
    return node.map(decodeTransit);
  }
  if (node && typeof node === "object") {
    // Plain JSON object map (string keys).
    const m: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(node as Record<string, unknown>)) {
      m[k] = decodeTransit(v);
    }
    return m;
  }
  return node;
}

// --- Client ----------------------------------------------------------------

export interface EqlClientOptions {
  /** Base origin of the agent api-server, e.g. "http://127.0.0.1:3737". */
  baseUrl: string;
  /** Optional fetch override (tests). */
  fetchImpl?: typeof fetch;
}

export class EqlClient {
  private endpoint: string;
  private fetchImpl: typeof fetch;

  constructor(opts: EqlClientOptions) {
    this.endpoint = opts.baseUrl.replace(/\/$/, "") + "/api";
    this.fetchImpl = opts.fetchImpl ?? fetch;
  }

  /**
   * Run an EQL query against `POST /api`. `query` is a JS value tree using
   * `kw(...)` for keywords (e.g. `[kw("chart/definition")]`) and a `Map` with
   * a `Keyword` key for a mutation (`new Map([[kw("escapement.control/pause"),
   * {}]])`). Returns the decoded result map.
   */
  async query(query: unknown): Promise<unknown> {
    const res = await this.fetchImpl(this.endpoint, {
      method: "POST",
      headers: {
        "Content-Type": "application/transit+json",
        Accept: "application/transit+json",
      },
      body: encodeTransit(query),
    });
    if (!res.ok) {
      throw new Error(`EQL POST /api failed: HTTP ${res.status}`);
    }
    const parsed = JSON.parse(await res.text());
    return decodeTransit(parsed);
  }

  /** Fetch the full chart definition once (for the visualizer; wire doc §4). */
  async chartDefinition(): Promise<unknown> {
    const result = (await this.query([kw("chart/definition")])) as Record<string, unknown>;
    return result?.["chart/definition"];
  }

  /**
   * Fire a control mutation as the EQL fallback for the WS `control` op
   * (wire doc §6). `op` is the full ns op string, e.g.
   * "escapement.control/pause". `params` carries e.g. {n: 1} for step.
   */
  async control(op: string, params: Record<string, unknown> = {}): Promise<unknown> {
    const mutation = new Map<Keyword, Record<string, unknown>>([[kw(op), params]]);
    return this.query([mutation]);
  }
}
