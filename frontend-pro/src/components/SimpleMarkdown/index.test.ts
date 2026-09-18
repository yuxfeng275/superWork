import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const source = readFileSync(
  join(dirname(fileURLToPath(import.meta.url)), "index.tsx"),
  "utf8"
);

describe("SimpleMarkdown", () => {
  it("renders bold, lists and @mentions as simple html", () => {
    expect(source).toMatch(/class="sw-md-mention"/);
    expect(source).toMatch(/\/todos\?userId=/);
    expect(source).toMatch(/history.push\(href\)/);
    expect(source).toMatch(/dangerouslySetInnerHTML/);
    expect(source).toMatch(/<strong>\$1<\/strong>/);
  });
});
