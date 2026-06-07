import { afterAll, describe, expect, test } from "bun:test";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { makeBlobReaders } from "../../src/ui/inspector/artifacts";

// A captured response.edn is `(pr-str content)` of the full assistant content
// vector — keywords + EDN strings with escaped \n / \". The content blob reader
// must extract the FULL text (not the ≤80-char inline snippet) so the transcript
// renders the whole reply.
const sdir = mkdtempSync(join(tmpdir(), "esc-blob-"));
afterAll(() => rmSync(sdir, { recursive: true, force: true }));

function writeBlob(rel: string, edn: string): string {
  const p = join(sdir, rel);
  mkdirSync(join(p, ".."), { recursive: true });
  writeFileSync(p, edn, "utf8");
  return rel;
}

describe("makeBlobReaders.content", () => {
  test("extracts a full multi-paragraph :text block with escaped newlines", () => {
    const ref = writeBlob(
      "nodes/host/0/turns/0/response.edn",
      `[{:type :text, :text "## tournament-summary.md\\n\\n**Theme:** First Snow\\n\\nLong body that is well over eighty characters so the inline snippet would have truncated it."}]`,
    );
    const blobs = makeBlobReaders(sdir)!;
    const blocks = blobs.content!(ref) as Array<Record<string, unknown>>;
    expect(blocks).toHaveLength(1);
    expect(blocks[0]!.type).toBe("text");
    const text = blocks[0]!.text as string;
    expect(text).toContain("## tournament-summary.md");
    expect(text).toContain("**Theme:** First Snow");
    expect(text).toContain("\n"); // escaped \n decoded to a real newline
    expect(text.length).toBeGreaterThan(80);
  });

  test("returns ordered text + thinking blocks", () => {
    const ref = writeBlob(
      "nodes/host/0/turns/1/response.edn",
      `[{:type :thinking, :thinking "reasoning"} {:type :text, :text "answer"}]`,
    );
    const blocks = makeBlobReaders(sdir)!.content!(ref) as Array<Record<string, unknown>>;
    expect(blocks.map((b) => b.type)).toEqual(["thinking", "text"]);
    expect(blocks[0]!.thinking).toBe("reasoning");
    expect(blocks[1]!.text).toBe("answer");
  });

  test("returns null for a missing blob (⇒ inline-snippet fallback)", () => {
    expect(makeBlobReaders(sdir)!.content!("nope/missing.edn")).toBeNull();
  });
});
