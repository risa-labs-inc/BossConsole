import { describe, it, expect } from "vitest";
import {
  exportBibliographyMarkdown,
  generateOutline,
  renderReference,
} from "../src/markdown.js";
import type { NotebookData, Source } from "../src/types.js";

function source(over: Partial<Source>): Source {
  return {
    id: over.id ?? "src-1",
    citeKey: over.citeKey ?? "vaswani2017attention",
    type: over.type ?? "paper",
    title: over.title ?? "Attention Is All You Need",
    url: over.url ?? "https://arxiv.org/abs/1706.03762",
    authors: over.authors ?? ["Vaswani, Ashish", "Shazeer, Noam", "Parmar, Niki"],
    container: over.container ?? "NeurIPS",
    publishedDate: over.publishedDate ?? "2017",
    accessedDate: "2024-01-01T00:00:00.000Z",
    doi: over.doi,
    tags: over.tags ?? [],
    quotes: over.quotes ?? [],
    summary: over.summary,
    createdAt: "2024-01-01T00:00:00.000Z",
    updatedAt: "2024-01-01T00:00:00.000Z",
  };
}

describe("renderReference", () => {
  it("formats 3+ authors with et al.", () => {
    expect(renderReference(source({}))).toContain("Vaswani, Ashish et al.");
  });
  it("includes year, italic title and container", () => {
    const ref = renderReference(source({}));
    expect(ref).toContain("(2017)");
    expect(ref).toContain("*Attention Is All You Need*");
    expect(ref).toContain("NeurIPS");
  });
  it("prefers a DOI link over the raw url", () => {
    expect(renderReference(source({ doi: "10.1/x" }))).toContain("https://doi.org/10.1/x");
  });
});

function notebook(over: Partial<NotebookData> = {}): NotebookData {
  return { version: 1, sources: [], notes: [], ...over };
}

describe("exportBibliographyMarkdown", () => {
  it("lists sources with cite keys", () => {
    const md = exportBibliographyMarkdown(notebook({ sources: [source({})] }));
    expect(md).toContain("# References");
    expect(md).toContain("**[vaswani2017attention]**");
  });
  it("annotated mode includes summaries and quotes", () => {
    const md = exportBibliographyMarkdown(
      notebook({
        sources: [
          source({
            summary: "A landmark paper.",
            quotes: [{ text: "self-attention", page: "p.3", addedAt: "2024-01-01T00:00:00Z" }],
          }),
        ],
      }),
      { annotated: true },
    );
    expect(md).toContain("A landmark paper.");
    expect(md).toContain("> self-attention (p.3)");
  });
  it("handles an empty notebook", () => {
    expect(exportBibliographyMarkdown(notebook())).toContain("_No sources recorded yet._");
  });
});

describe("generateOutline", () => {
  it("groups notes by theme, threads cite keys, lists references", () => {
    const s = source({ id: "src-1", citeKey: "vaswani2017attention", tags: ["transformers"] });
    const data = notebook({
      title: "My Survey",
      sources: [s],
      notes: [
        {
          id: "note-1",
          title: "Self-attention scales",
          content: "Key point about parallelism.\nMore detail.",
          sourceIds: ["src-1"],
          tags: ["transformers"],
          createdAt: "2024-01-01T00:00:00Z",
          updatedAt: "2024-01-01T00:00:00Z",
        },
      ],
    });
    const md = generateOutline(data);
    expect(md).toContain("# My Survey");
    expect(md).toContain("### Transformers");
    expect(md).toContain("**Self-attention scales** [@vaswani2017attention]");
    expect(md).toContain("Key point about parallelism.");
    expect(md).toContain("## References");
  });

  it("lists sources that have no note under 'not yet written up'", () => {
    const data = notebook({
      sources: [source({ id: "src-9", citeKey: "unread2020" })],
      notes: [],
    });
    const md = generateOutline(data);
    expect(md).toContain("## Sources not yet written up");
    expect(md).toContain("unread2020");
  });

  it("handles a notebook with no notes", () => {
    const md = generateOutline(notebook());
    expect(md).toContain("_No notes to outline yet");
  });
});
