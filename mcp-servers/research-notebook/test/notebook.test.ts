import { describe, it, expect, beforeEach } from "vitest";
import { promises as fs } from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { Notebook, NotebookError, normaliseUrl } from "../src/notebook.js";
import { exportBibliographyMarkdown } from "../src/markdown.js";
import { toBibtex } from "../src/bibtex.js";

async function tempDir(): Promise<string> {
  return fs.mkdtemp(path.join(os.tmpdir(), "rn-test-"));
}

function deps(dir: string) {
  let counter = 0;
  return {
    dir,
    now: () => new Date("2024-01-01T12:00:00.000Z"),
    makeId: () => `id${++counter}`,
  };
}

describe("normaliseUrl", () => {
  it("drops fragment, tracking params, trailing slash, www", () => {
    expect(normaliseUrl("https://www.Example.com/a/?utm_source=x&ref=y#frag")).toBe(
      "https://example.com/a",
    );
  });
  it("keeps meaningful query params", () => {
    expect(normaliseUrl("https://example.com/a?id=7")).toBe("https://example.com/a?id=7");
  });
  it("passes through non-URLs", () => {
    expect(normaliseUrl("not a url")).toBe("not a url");
  });
});

describe("Notebook sources", () => {
  let dir: string;
  beforeEach(async () => {
    dir = await tempDir();
  });

  it("adds a source, generates a cite key, and persists to disk", async () => {
    const nb = await Notebook.open(deps(dir));
    const src = await nb.addSource({
      title: "Attention Is All You Need",
      authors: ["Ashish Vaswani"],
      publishedDate: "2017",
      url: "https://arxiv.org/abs/1706.03762",
    });
    expect(src.id).toBe("src-id1");
    expect(src.citeKey).toBe("vaswani2017attention");

    const onDisk = JSON.parse(await fs.readFile(path.join(dir, "notebook.json"), "utf8"));
    expect(onDisk.sources).toHaveLength(1);
    expect(onDisk.sources[0].citeKey).toBe("vaswani2017attention");
  });

  it("reloads persisted data from a fresh instance", async () => {
    const nb1 = await Notebook.open(deps(dir));
    await nb1.addSource({ title: "First" });
    const nb2 = await Notebook.open(deps(dir));
    expect(nb2.listSources()).toHaveLength(1);
    expect(nb2.listSources()[0]!.title).toBe("First");
  });

  it("disambiguates colliding cite keys", async () => {
    const nb = await Notebook.open(deps(dir));
    const a = await nb.addSource({ title: "Neural nets", authors: ["Smith"], publishedDate: "2020" });
    const b = await nb.addSource({ title: "Neural nets", authors: ["Smith"], publishedDate: "2020" });
    expect(a.citeKey).toBe("smith2020neural");
    expect(b.citeKey).toBe("smith2020neurala");
  });

  it("finds an existing source by normalised URL", async () => {
    const nb = await Notebook.open(deps(dir));
    await nb.addSource({ title: "X", url: "https://example.com/p/" });
    expect(nb.findByUrl("https://www.example.com/p?utm_source=z")).toBeDefined();
    expect(nb.findByUrl("https://other.com")).toBeUndefined();
  });

  it("resolves a source by id or cite key", async () => {
    const nb = await Notebook.open(deps(dir));
    const s = await nb.addSource({ title: "Y", authors: ["Doe"], publishedDate: "2019" });
    expect(nb.resolveSource(s.id)?.id).toBe(s.id);
    expect(nb.resolveSource(s.citeKey)?.id).toBe(s.id);
  });

  it("adds quotes to a source", async () => {
    const nb = await Notebook.open(deps(dir));
    const s = await nb.addSource({ title: "Q" });
    const updated = await nb.addQuote(s.citeKey, { text: "a key finding", page: "p. 3" });
    expect(updated.quotes).toHaveLength(1);
    expect(updated.quotes[0]!.text).toBe("a key finding");
    expect(updated.quotes[0]!.page).toBe("p. 3");
  });

  it("normalises and de-duplicates tags", async () => {
    const nb = await Notebook.open(deps(dir));
    const s = await nb.addSource({ title: "T", tags: ["NLP", "nlp", " ML "] });
    expect(s.tags).toEqual(["nlp", "ml"]);
  });

  it("rejects a blank title", async () => {
    const nb = await Notebook.open(deps(dir));
    await expect(nb.addSource({ title: "   " })).rejects.toBeInstanceOf(NotebookError);
  });

  it("rejects a quote on an unknown source", async () => {
    const nb = await Notebook.open(deps(dir));
    await expect(nb.addQuote("nope", { text: "x" })).rejects.toBeInstanceOf(NotebookError);
  });
});

describe("Notebook notes", () => {
  let dir: string;
  beforeEach(async () => {
    dir = await tempDir();
  });

  it("links a note to sources by cite key, resolving to the id", async () => {
    const nb = await Notebook.open(deps(dir));
    const s = await nb.addSource({ title: "Linked", authors: ["Ray"], publishedDate: "2018" });
    const unlinked = await nb.addNote({ title: "Thoughts", content: "Interesting angle." });
    expect(unlinked.sourceIds).toEqual([]);
    const linked = await nb.addNote({ title: "T2", content: "c", sourceIds: [s.citeKey] });
    expect(linked.sourceIds).toEqual([s.id]);
  });

  it("rejects linking to an unknown source", async () => {
    const nb = await Notebook.open(deps(dir));
    await expect(
      nb.addNote({ title: "T", content: "c", sourceIds: ["ghost"] }),
    ).rejects.toBeInstanceOf(NotebookError);
  });

  it("removing a source unlinks it from notes", async () => {
    const nb = await Notebook.open(deps(dir));
    const s = await nb.addSource({ title: "ToRemove" });
    const note = await nb.addNote({ title: "N", content: "c", sourceIds: [s.id] });
    const result = await nb.removeSource(s.id);
    expect(result.affectedNotes).toEqual([note.id]);
    expect(nb.resolveNote(note.id)!.sourceIds).toEqual([]);
  });

  it("filters notes by linked source and by tag", async () => {
    const nb = await Notebook.open(deps(dir));
    const s = await nb.addSource({ title: "S" });
    await nb.addNote({ title: "A", content: "", sourceIds: [s.id], tags: ["theme1"] });
    await nb.addNote({ title: "B", content: "", tags: ["theme2"] });
    expect(nb.listNotes({ sourceId: s.id })).toHaveLength(1);
    expect(nb.listNotes({ tag: "theme2" })).toHaveLength(1);
  });

  it("updates a note", async () => {
    const nb = await Notebook.open(deps(dir));
    const note = await nb.addNote({ title: "Old", content: "old" });
    const updated = await nb.updateNote(note.id, { title: "New", content: "new" });
    expect(updated.title).toBe("New");
    expect(updated.content).toBe("new");
  });
});

describe("Notebook search & stats", () => {
  let dir: string;
  beforeEach(async () => {
    dir = await tempDir();
  });

  it("searches across sources and notes", async () => {
    const nb = await Notebook.open(deps(dir));
    await nb.addSource({ title: "Transformers explained", tags: ["nlp"] });
    await nb.addNote({ title: "My take", content: "transformers are great" });
    const hit = nb.search("transformers");
    expect(hit.sources).toHaveLength(1);
    expect(hit.notes).toHaveLength(1);
    expect(nb.search("nothingmatches").sources).toHaveLength(0);
  });

  it("reports stats", async () => {
    const nb = await Notebook.open(deps(dir));
    const s = await nb.addSource({ title: "S", type: "paper", tags: ["a"] });
    await nb.addQuote(s.id, { text: "q" });
    await nb.addNote({ title: "N", content: "", tags: ["b"] });
    const stats = nb.stats();
    expect(stats.sources).toBe(1);
    expect(stats.notes).toBe(1);
    expect(stats.quotes).toBe(1);
    expect(stats.byType.paper).toBe(1);
    expect(stats.tags).toEqual(["a", "b"]);
  });
});

describe("Notebook resilience", () => {
  it("throws a clear error on corrupt notebook.json", async () => {
    const dir = await tempDir();
    await fs.writeFile(path.join(dir, "notebook.json"), "{ not json", "utf8");
    await expect(Notebook.open(deps(dir))).rejects.toBeInstanceOf(NotebookError);
  });

  it("creates the directory if missing", async () => {
    const dir = path.join(await tempDir(), "nested", "notebook-dir");
    const nb = await Notebook.open(deps(dir));
    await nb.addSource({ title: "X" });
    await expect(fs.stat(path.join(dir, "notebook.json"))).resolves.toBeDefined();
  });

  it("normalises a hand-edited notebook missing fields", async () => {
    const dir = await tempDir();
    await fs.writeFile(
      path.join(dir, "notebook.json"),
      JSON.stringify({
        version: 1,
        sources: [
          {
            id: "src-x",
            citeKey: "hand2020edited",
            type: "paper",
            title: "Hand edited",
            createdAt: "2024-01-01T00:00:00.000Z",
            updatedAt: "2024-01-01T00:00:00.000Z",
            tags: ["ML", " ml "],
            isbn: "978-3-16-148410-0",
          },
        ],
        notes: [
          {
            id: "note-x",
            title: "n",
            content: "c",
            createdAt: "2024-01-01T00:00:00.000Z",
            updatedAt: "2024-01-01T00:00:00.000Z",
          },
        ],
      }),
      "utf8",
    );
    const nb = await Notebook.open(deps(dir));
    expect(nb.stats()).toEqual({
      sources: 1,
      notes: 1,
      quotes: 0,
      tags: ["ml"],
      byType: { paper: 1 },
    });
    expect(nb.listSources()[0]!.authors).toEqual([]);
    // The stored tag is normalised like the write path does it, so a query
    // in any case finds it.
    expect(nb.listSources({ tag: "ML" })).toHaveLength(1);
    expect(nb.listNotes()[0]!.sourceIds).toEqual([]);
    expect(exportBibliographyMarkdown(nb.snapshot())).toContain("hand2020edited");
    // No accessedDate was stored, so the BibTeX export must not invent one.
    expect(toBibtex(nb.listSources())).not.toContain("urldate");
    // Unknown keys survive a load/save round trip.
    await nb.addSource({ title: "Another" });
    const reloaded = JSON.parse(await fs.readFile(path.join(dir, "notebook.json"), "utf8"));
    expect(reloaded.sources[0].isbn).toBe("978-3-16-148410-0");
  });

  it("refuses to load a notebook with unrecognised entries", async () => {
    const dir = await tempDir();
    await fs.writeFile(
      path.join(dir, "notebook.json"),
      JSON.stringify({
        version: 1,
        sources: [{ title: "no id" }, "junk"],
        notes: [{ id: "note-ok", title: "ok", content: "x" }],
      }),
      "utf8",
    );
    await expect(Notebook.open(deps(dir))).rejects.toBeInstanceOf(NotebookError);
  });
});

describe("Notebook normalisation details", () => {
  it("dedupes and trims hand-edited authors", async () => {
    const dir = await tempDir();
    await fs.writeFile(
      path.join(dir, "notebook.json"),
      JSON.stringify({
        version: 1,
        sources: [
          {
            id: "src-a",
            citeKey: "a2020",
            type: "paper",
            title: "A",
            authors: [" Smith", "Smith", "jane"],
            accessedDate: "2024-01-01T00:00:00.000Z",
            createdAt: "2024-01-01T00:00:00.000Z",
            updatedAt: "2024-01-01T00:00:00.000Z",
          },
        ],
        notes: [],
      }),
      "utf8",
    );
    const nb = await Notebook.open(deps(dir));
    expect(nb.listSources()[0]!.authors).toEqual(["Smith", "jane"]);
});
});
