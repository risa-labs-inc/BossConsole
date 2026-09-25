import { describe, it, expect, beforeEach } from "vitest";
import { promises as fs } from "node:fs";
import { readFileSync } from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { Notebook } from "../src/notebook.js";
import { createServer, isFetchableUrl, type FetchFn } from "../src/server.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const fixture = (name: string) => readFileSync(path.join(here, "fixtures", name), "utf8");

/** A fetch stub that maps known URLs to fixtures and 404s everything else. */
interface FetchCall {
  url: string;
  init?: { headers?: Record<string, string>; signal?: AbortSignal };
}
const fetchCalls: FetchCall[] = [];
const fakeFetch: FetchFn = async (url, init) => {
  fetchCalls.push({ url, init });
  const map: Record<string, string> = {
    "https://arxiv.org/abs/1706.03762": fixture("scholar.html"),
    "https://news.example.com/rag": fixture("article.html"),
  };
  const body = map[url];
  if (body === undefined) {
    return { ok: false, status: 404, text: async () => "" };
  }
  return { ok: true, status: 200, text: async () => body };
};

interface CallResult {
  content: { type: string; text?: string }[];
  structuredContent?: Record<string, unknown>;
  isError?: boolean;
}

async function connectedClient(dir: string): Promise<{ client: Client }> {
  const notebook = await Notebook.open({
    dir,
    now: () => new Date("2024-01-01T00:00:00.000Z"),
  });
  const server = createServer({ notebook, fetchFn: fakeFetch });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  const client = new Client({ name: "test-client", version: "1.0.0" });
  await Promise.all([client.connect(clientTransport), server.connect(serverTransport)]);
  return { client };
}

async function call(
  client: Client,
  name: string,
  args: Record<string, unknown> = {},
): Promise<CallResult> {
  return (await client.callTool({ name, arguments: args })) as unknown as CallResult;
}

function textOf(result: CallResult): string {
  return result.content.map((c) => c.text ?? "").join("\n");
}

describe("MCP server end to end", () => {
  let dir: string;
  beforeEach(async () => {
    dir = await fs.mkdtemp(path.join(os.tmpdir(), "rn-server-"));
    fetchCalls.length = 0;
  });

  it("exposes the full tool set", async () => {
    const { client } = await connectedClient(dir);
    const { tools } = await client.listTools();
    const names = tools.map((t) => t.name).sort();
    expect(names).toContain("cite_url");
    expect(names).toContain("generate_outline");
    expect(names).toContain("export_bibtex");
    expect(names.length).toBe(17);
  });

  it("cite_url captures a paper with extracted metadata", async () => {
    const { client } = await connectedClient(dir);
    const res = await call(client, "cite_url", {
      url: "https://arxiv.org/abs/1706.03762",
      quote: "self-attention",
      tags: ["transformers"],
    });
    expect(res.isError).toBeFalsy();
    const text = textOf(res);
    expect(text).toContain("vaswani2017attention");
    expect(text).toContain("Attention Is All You Need");
    const src = res.structuredContent?.source as { citeKey: string; quotes: unknown[] };
    expect(src.citeKey).toBe("vaswani2017attention");
    expect(src.quotes).toHaveLength(1);
  });

  it("cite_url de-duplicates the same URL", async () => {
    const { client } = await connectedClient(dir);
    await call(client, "cite_url", { url: "https://arxiv.org/abs/1706.03762" });
    const again = await call(client, "cite_url", {
      url: "https://www.arxiv.org/abs/1706.03762?utm_source=x",
      quote: "added later",
    });
    expect(again.structuredContent?.deduped).toBe(true);
    expect(textOf(again)).toContain("Already saved");
    // The list still has exactly one source.
    const list = await call(client, "list_sources");
    expect((list.structuredContent?.sources as unknown[]).length).toBe(1);
  });

  it("cite_url reports a clean error when the fetch fails", async () => {
    const { client } = await connectedClient(dir);
    const res = await call(client, "cite_url", { url: "https://news.example.com/missing" });
    expect(res.isError).toBe(true);
    expect(textOf(res)).toContain("Fetch failed");
  });

  it("supports the full notes -> outline -> export workflow", async () => {
    const { client } = await connectedClient(dir);
    await call(client, "cite_url", {
      url: "https://arxiv.org/abs/1706.03762",
      tags: ["transformers"],
    });
    await call(client, "add_note", {
      title: "Self-attention scales",
      content: "Parallelism is the key advantage.",
      sources: ["vaswani2017attention"],
      tags: ["transformers"],
    });

    const outline = await call(client, "generate_outline", { write: true });
    const outlineText = textOf(outline);
    expect(outlineText).toContain("### Transformers");
    expect(outlineText).toContain("[@vaswani2017attention]");
    // write:true should have produced a file on disk.
    await expect(fs.stat(path.join(dir, "outline.md"))).resolves.toBeDefined();

    const bib = await call(client, "export_bibtex", { write: true });
    expect(textOf(bib)).toContain("@article{vaswani2017attention");
    await expect(fs.stat(path.join(dir, "references.bib"))).resolves.toBeDefined();

    const stats = await call(client, "notebook_stats");
    expect(stats.structuredContent?.sources).toBe(1);
    expect(stats.structuredContent?.notes).toBe(1);
  });

  it("search finds captured content", async () => {
    const { client } = await connectedClient(dir);
    await call(client, "cite_url", { url: "https://arxiv.org/abs/1706.03762" });
    const res = await call(client, "search", { query: "attention" });
    expect((res.structuredContent?.sources as unknown[]).length).toBe(1);
  });

  it("returns a tool error for an unknown source", async () => {
    const { client } = await connectedClient(dir);
    const res = await call(client, "get_source", { source: "does-not-exist" });
    expect(res.isError).toBe(true);
    expect(textOf(res)).toContain("No source found");
  });

  it("add_source + add_quote + remove_source unlinks notes", async () => {
    const { client } = await connectedClient(dir);
    const added = await call(client, "add_source", {
      title: "Manual Book",
      type: "book",
      authors: ["Donald Knuth"],
      publishedDate: "1997",
      container: "Addison-Wesley",
    });
    const key = (added.structuredContent?.source as { citeKey: string }).citeKey;
    await call(client, "add_note", { title: "N", content: "c", sources: [key] });
    const removed = await call(client, "remove_source", { source: key });
    expect(textOf(removed)).toContain("Unlinked from 1 note");
  });

  it("does not claim readOnlyHint on tools that can write files", async () => {
    const { client } = await connectedClient(dir);
    const { tools } = await client.listTools();
    const byName = new Map(tools.map((t) => [t.name, t]));
    for (const name of ["export_bibtex", "export_markdown", "generate_outline"]) {
      expect(byName.get(name)?.annotations?.readOnlyHint, name).not.toBe(true);
    }
    expect(byName.get("notebook_stats")?.annotations?.readOnlyHint).toBe(true);
  });

  it("cite_url passes a timeout signal to fetch", async () => {
    const { client } = await connectedClient(dir);
    await call(client, "cite_url", { url: "https://arxiv.org/abs/1706.03762" });
    const entry = fetchCalls.find((c) => c.url === "https://arxiv.org/abs/1706.03762");
    expect(entry?.init?.signal).toBeInstanceOf(AbortSignal);
  });

  it("cite_url refuses loopback, private, link-local and non-http(s) URLs", async () => {
    const { client } = await connectedClient(dir);
    for (const url of [
      "http://127.0.0.1:8080/admin",
      "http://localhost:8080/",
      "http://169.254.169.254/latest/meta-data/",
      "http://10.1.2.3/secret",
      "http://192.168.1.1/",
      "file:///etc/passwd",
    ]) {
      const res = await call(client, "cite_url", { url });
      expect(res.isError, url).toBe(true);
      expect(textOf(res), url).toContain("Refusing to fetch");
    }
    // Nothing was attempted: the stub never saw these URLs.
    expect(fetchCalls).toHaveLength(0);
  });
});

describe("isFetchableUrl", () => {
  it("allows public http/https URLs", () => {
    expect(isFetchableUrl("https://arxiv.org/abs/1706.03762")).toBe(true);
    expect(isFetchableUrl("http://example.com/a?b=1")).toBe(true);
    expect(isFetchableUrl("https://8.8.8.8/")).toBe(true);
  });

  it("rejects other schemes and invalid URLs", () => {
    expect(isFetchableUrl("file:///etc/passwd")).toBe(false);
    expect(isFetchableUrl("ftp://example.com/x")).toBe(false);
    expect(isFetchableUrl("not a url")).toBe(false);
  });

  it("rejects loopback, link-local and private address literals", () => {
    expect(isFetchableUrl("http://127.0.0.1/")).toBe(false);
    expect(isFetchableUrl("http://0.0.0.0:8080/")).toBe(false);
    expect(isFetchableUrl("http://10.0.0.1/")).toBe(false);
    expect(isFetchableUrl("http://172.16.0.1/")).toBe(false);
    expect(isFetchableUrl("http://172.31.255.255/")).toBe(false);
    expect(isFetchableUrl("http://169.254.169.254/")).toBe(false);
    expect(isFetchableUrl("http://192.168.0.1/")).toBe(false);
    expect(isFetchableUrl("http://172.32.0.1/")).toBe(true);
    expect(isFetchableUrl("http://169.255.0.1/")).toBe(true);
  });
});
