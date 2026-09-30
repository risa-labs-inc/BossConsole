import { describe, it, expect } from "vitest";
import { escapeTex, toBibtex, toBibtexEntry } from "../src/bibtex.js";
import type { Source } from "../src/types.js";

function source(over: Partial<Source>): Source {
  return {
    id: "src-1",
    citeKey: "vaswani2017attention",
    type: "paper",
    title: "Attention Is All You Need",
    url: "https://arxiv.org/abs/1706.03762",
    authors: ["Vaswani, Ashish", "Shazeer, Noam"],
    container: "NeurIPS",
    publishedDate: "2017-06-12",
    accessedDate: "2024-01-01T00:00:00.000Z",
    doi: "10.48550/arXiv.1706.03762",
    tags: [],
    quotes: [],
    createdAt: "2024-01-01T00:00:00.000Z",
    updatedAt: "2024-01-01T00:00:00.000Z",
    ...over,
  };
}

describe("escapeTex", () => {
  it("escapes special characters", () => {
    expect(escapeTex("A & B 50% #1 $x_y")).toBe("A \\& B 50\\% \\#1 \\$x\\_y");
  });

  it("escapes a backslash without double-escaping its braces", () => {
    expect(escapeTex("a\\b")).toBe("a\\textbackslash{}b");
  });

  it("escapes every special character in a single pass", () => {
    expect(escapeTex('C\\^~ 50%_a{b}')).toBe(
      "C\\textbackslash{}\\textasciicircum{}\\textasciitilde{} 50\\%\\_a\\{b\\}",
    );
  });
});

describe("toBibtexEntry", () => {
  it("renders a paper as @article with a journal", () => {
    const entry = toBibtexEntry(source({}));
    expect(entry).toContain("@article{vaswani2017attention,");
    expect(entry).toContain("title = {Attention Is All You Need}");
    expect(entry).toContain("author = {Vaswani, Ashish and Shazeer, Noam}");
    expect(entry).toContain("journal = {NeurIPS}");
    expect(entry).toContain("year = {2017}");
    expect(entry).toContain("doi = {10.48550/arXiv.1706.03762}");
  });

  it("renders a book as @book with a publisher", () => {
    const entry = toBibtexEntry(
      source({ type: "book", citeKey: "knuth1997art", container: "Addison-Wesley", doi: undefined }),
    );
    expect(entry).toContain("@book{knuth1997art,");
    expect(entry).toContain("publisher = {Addison-Wesley}");
  });

  it("renders a webpage as @misc with howpublished url", () => {
    const entry = toBibtexEntry(
      source({ type: "webpage", citeKey: "web2023", container: "Example", doi: undefined }),
    );
    expect(entry).toContain("@misc{web2023,");
    expect(entry).toContain("howpublished = {\\url{https://arxiv.org/abs/1706.03762}}");
  });

  it("omits the year when there is no date", () => {
    const entry = toBibtexEntry(source({ publishedDate: undefined }));
    expect(entry).not.toContain("year =");
  });
});

describe("toBibtex", () => {
  it("joins multiple entries and ends with a newline", () => {
    const out = toBibtex([source({}), source({ citeKey: "b2020", type: "book" })]);
    expect(out.match(/@/g)).toHaveLength(2);
    expect(out.endsWith("\n")).toBe(true);
  });
  it("returns empty string for no sources", () => {
    expect(toBibtex([])).toBe("");
  });
});
