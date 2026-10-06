import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import * as path from "node:path";
import { extractDoi, extractMetadata, hostnameOf } from "../src/metadata.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const fixture = (name: string) => readFileSync(path.join(here, "fixtures", name), "utf8");

describe("extractMetadata: OpenGraph article", () => {
  const meta = extractMetadata(fixture("article.html"), "https://news.example.com/rag");
  it("prefers og:title over <title>", () => {
    expect(meta.title).toBe("The Rise of Retrieval-Augmented Generation");
  });
  it("collects author + article:author", () => {
    expect(meta.authors).toEqual(["Ada Lovelace", "Alan Turing"]);
  });
  it("reads the published time", () => {
    expect(meta.publishedDate).toBe("2023-05-14T09:30:00Z");
  });
  it("uses og:site_name as container", () => {
    expect(meta.container).toBe("Example News");
  });
  it("classifies it as an article", () => {
    expect(meta.type).toBe("article");
  });
  it("captures the description as summary", () => {
    expect(meta.summary).toContain("RAG");
  });
});

describe("extractMetadata: Google Scholar / highwire tags", () => {
  const meta = extractMetadata(fixture("scholar.html"), "https://arxiv.org/abs/1706.03762");
  it("reads the citation title", () => {
    expect(meta.title).toBe("Attention Is All You Need");
  });
  it("collects every citation_author in order", () => {
    expect(meta.authors).toEqual(["Vaswani, Ashish", "Shazeer, Noam", "Parmar, Niki"]);
  });
  it("reads the journal title", () => {
    expect(meta.container).toBe("Advances in Neural Information Processing Systems");
  });
  it("reads the DOI", () => {
    expect(meta.doi).toBe("10.48550/arXiv.1706.03762");
  });
  it("classifies a page with citation tags/DOI as a paper", () => {
    expect(meta.type).toBe("paper");
  });
});

describe("extractMetadata: JSON-LD", () => {
  const meta = extractMetadata(fixture("jsonld.html"), "https://research.example.com/gnn");
  it("reads headline, authors and date from @graph", () => {
    expect(meta.title).toBe("Graph Neural Networks in Practice");
    expect(meta.authors).toEqual(["Grace Hopper", "Katherine Johnson"]);
    expect(meta.publishedDate).toBe("2021-11-02");
  });
  it("reads the publisher as container", () => {
    expect(meta.container).toBe("Research Weekly");
  });
  it("classifies ScholarlyArticle as a paper", () => {
    expect(meta.type).toBe("paper");
  });
});

describe("extractMetadata: degenerate input", () => {
  it("falls back to <title> and hostname", () => {
    const meta = extractMetadata("<title>Bare Page</title>", "https://www.example.org/x");
    expect(meta.title).toBe("Bare Page");
    expect(meta.container).toBe("example.org");
    expect(meta.type).toBe("webpage");
    expect(meta.authors).toEqual([]);
  });
  it("does not throw on empty html", () => {
    expect(() => extractMetadata("", "not a url")).not.toThrow();
  });
});

describe("helpers", () => {
  it("extractDoi finds a DOI in a string", () => {
    expect(extractDoi("see doi:10.1000/xyz123 for details")).toBe("10.1000/xyz123");
    expect(extractDoi("no doi here")).toBeUndefined();
  });
  it("hostnameOf strips www", () => {
    expect(hostnameOf("https://www.nature.com/articles/x")).toBe("nature.com");
    expect(hostnameOf("garbage")).toBeUndefined();
  });
});
