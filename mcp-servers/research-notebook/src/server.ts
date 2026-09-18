/**
 * MCP server wiring.
 *
 * `createServer` builds an `McpServer` and registers the Research Notebook
 * tools against a `Notebook` instance. Both the notebook and the `fetch`
 * implementation are injected so the whole server can be driven in-memory from
 * tests with no filesystem surprises and no network.
 */
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { Notebook, NotebookError } from "./notebook.js";
import { extractMetadata } from "./metadata.js";
import { toBibtex } from "./bibtex.js";
import { exportBibliographyMarkdown, generateOutline, renderReference } from "./markdown.js";
import { SOURCE_TYPES, type Note, type Source, type SourceType } from "./types.js";

export type FetchFn = (
  url: string,
  init?: { headers?: Record<string, string>; signal?: AbortSignal },
) => Promise<{ ok: boolean; status: number; text: () => Promise<string> }>;

export interface CreateServerOptions {
  notebook: Notebook;
  /** Injectable fetch; defaults to the global fetch. */
  fetchFn?: FetchFn;
  name?: string;
  version?: string;
}

interface ToolResult {
  content: { type: "text"; text: string }[];
  structuredContent?: Record<string, unknown>;
  isError?: boolean;
  // The SDK's CallToolResult carries an index signature; matching it keeps our
  // handler return type assignable without casts.
  [key: string]: unknown;
}

function ok(text: string, structured?: Record<string, unknown>): ToolResult {
  return { content: [{ type: "text", text }], structuredContent: structured };
}

function fail(message: string): ToolResult {
  return { content: [{ type: "text", text: `Error: ${message}` }], isError: true };
}

/** Wrap a handler so NotebookError becomes a clean tool error, not a crash. */
async function guard(fn: () => Promise<ToolResult>): Promise<ToolResult> {
  try {
    return await fn();
  } catch (err) {
    if (err instanceof NotebookError) return fail(err.message);
    return fail(err instanceof Error ? err.message : String(err));
  }
}

// --- Rendering for the text channel ---------------------------------------

function sourceLine(s: Source): string {
  const authors = s.authors.length > 0 ? s.authors.join(", ") : "Unknown author";
  const date = s.publishedDate ? ` (${s.publishedDate})` : "";
  const tags = s.tags.length > 0 ? `  #${s.tags.join(" #")}` : "";
  return `[${s.citeKey}] ${s.title} - ${authors}${date}${tags}`;
}

function sourceDetail(s: Source): string {
  const lines = [
    `id: ${s.id}`,
    `cite key: ${s.citeKey}`,
    `type: ${s.type}`,
    `title: ${s.title}`,
    `authors: ${s.authors.join(", ") || "(none)"}`,
  ];
  if (s.container) lines.push(`container: ${s.container}`);
  if (s.publishedDate) lines.push(`published: ${s.publishedDate}`);
  if (s.doi) lines.push(`doi: ${s.doi}`);
  if (s.url) lines.push(`url: ${s.url}`);
  if (s.tags.length) lines.push(`tags: ${s.tags.join(", ")}`);
  if (s.summary) lines.push(`summary: ${s.summary}`);
  if (s.quotes.length) {
    lines.push(`quotes (${s.quotes.length}):`);
    for (const q of s.quotes) {
      lines.push(`  - "${q.text}"${q.page ? ` (${q.page})` : ""}`);
      if (q.note) lines.push(`    note: ${q.note}`);
    }
  }
  return lines.join("\n");
}

function noteLine(n: Note): string {
  const tags = n.tags.length > 0 ? `  #${n.tags.join(" #")}` : "";
  const links = n.sourceIds.length > 0 ? `  (${n.sourceIds.length} source link(s))` : "";
  return `${n.id}: ${n.title}${links}${tags}`;
}

function noteDetail(nb: Notebook, n: Note): string {
  const keys = n.sourceIds
    .map((id) => nb.resolveSource(id)?.citeKey ?? id)
    .join(", ");
  const lines = [
    `id: ${n.id}`,
    `title: ${n.title}`,
    n.tags.length ? `tags: ${n.tags.join(", ")}` : undefined,
    n.sourceIds.length ? `sources: ${keys}` : undefined,
    "",
    n.content || "(no content)",
  ].filter((l): l is string => l !== undefined);
  return lines.join("\n");
}

const DEFAULT_HEADERS = {
  "User-Agent":
    "boss-research-notebook-mcp/1.0 (+https://bossconsole.ai; citation capture)",
  Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
};

/** How long cite_url waits for a page before giving up. */
export const CITE_FETCH_TIMEOUT_MS = 30_000;

/**
 * Whether cite_url may fetch a URL. The agent chooses the URL, so the server
 * must not let it reach loopback, link-local (cloud metadata) or private
 * addresses: only http/https, and no private IP literals (hostnames that
 * resolve to private addresses still need care - see review follow-ups).
 */
export function isFetchableUrl(url: string): boolean {
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return false;
  }
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return false;
  const host = parsed.hostname.replace(/^\[|\]$/g, "");
  if (host === "localhost") return false;
  const ipv4 = host.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (ipv4) {
    const first = Number(ipv4[1]);
    const second = Number(ipv4[2]);
    if (first === 0 || first === 10 || first === 127) return false; // 0/8, 10/8, loopback
    if (first === 169 && second === 254) return false; // link-local / metadata
    if (first === 172 && second >= 16 && second <= 31) return false; // 172.16/12
    if (first === 192 && second === 168) return false; // 192.168/16
  }
  return true;
}

export function createServer(options: CreateServerOptions): McpServer {
  const nb = options.notebook;
  const fetchFn: FetchFn = options.fetchFn ?? (globalThis.fetch as unknown as FetchFn);

  const server = new McpServer({
    name: options.name ?? "research-notebook",
    version: options.version ?? "1.0.0",
  });

  const sourceRef = z
    .string()
    .describe("A source id (src-...) or its cite key (e.g. vaswani2017attention).");
  const tagsSchema = z.array(z.string()).optional().describe("Lowercase topic tags.");
  const typeSchema = z
    .enum(SOURCE_TYPES as unknown as [SourceType, ...SourceType[]])
    .optional()
    .describe("Bibliographic type. Defaults to webpage, or paper for pages with DOIs.");

  // 1. cite_url ------------------------------------------------------------
  server.registerTool(
    "cite_url",
    {
      title: "Cite a web page",
      description:
        "Fetch a URL, extract its bibliographic metadata (title, authors, date, journal/site, DOI) " +
        "and save it as a source. If the URL is already saved, it is not duplicated; any quote/note " +
        "you pass is added to the existing source instead. This is the fastest way to capture a " +
        "citation while browsing.",
      inputSchema: {
        url: z.string().url().describe("The page to cite."),
        quote: z.string().optional().describe("An excerpt to store with the source."),
        note: z.string().optional().describe("Your comment on the quote, or a one-line summary."),
        tags: tagsSchema,
        type: typeSchema,
      },
      annotations: { readOnlyHint: false, openWorldHint: true },
    },
    async ({ url, quote, note, tags, type }) =>
      guard(async () => {
        const existing = nb.findByUrl(url);
        if (existing) {
          if (quote) await nb.addQuote(existing.citeKey, { text: quote, note });
          if (tags && tags.length) {
            await nb.updateSource(existing.citeKey, {
              tags: [...existing.tags, ...tags],
            });
          }
          const refreshed = nb.resolveSource(existing.citeKey)!;
          return ok(
            `Already saved as [${refreshed.citeKey}].` +
              (quote ? " Added your quote to it." : "") +
              `\n\n${sourceDetail(refreshed)}`,
            { source: refreshed, deduped: true },
          );
        }

        let html: string;
        try {
          if (!isFetchableUrl(url)) {
            return fail(
              `Refusing to fetch ${url}: cite_url only fetches http/https addresses ` +
                "outside loopback, private and link-local ranges. Record it with add_source instead.",
            );
          }
          const res = await fetchFn(url, {
            headers: DEFAULT_HEADERS,
            signal: AbortSignal.timeout(CITE_FETCH_TIMEOUT_MS),
          });
          if (!res.ok) return fail(`Fetch failed for ${url} (HTTP ${res.status}).`);
          html = await res.text();
        } catch (err) {
          return fail(
            `Could not fetch ${url}: ${err instanceof Error ? err.message : String(err)}. ` +
              "You can still record it with add_source.",
          );
        }

        const meta = extractMetadata(html, url);
        const source = await nb.addSource({
          title: meta.title ?? url,
          type: type ?? meta.type,
          url,
          authors: meta.authors,
          container: meta.container,
          publishedDate: meta.publishedDate,
          doi: meta.doi,
          summary: note ?? meta.summary,
          tags,
        });
        if (quote) await nb.addQuote(source.citeKey, { text: quote, note });
        const saved = nb.resolveSource(source.citeKey)!;
        return ok(`Saved [${saved.citeKey}].\n\n${sourceDetail(saved)}`, {
          source: saved,
          deduped: false,
        });
      }),
  );

  // 2. add_source ----------------------------------------------------------
  server.registerTool(
    "add_source",
    {
      title: "Add a source manually",
      description:
        "Record a source by hand - a book, a paper you have the details for, or a page you cannot " +
        "fetch. Use this when cite_url is not possible.",
      inputSchema: {
        title: z.string().describe("Title of the work."),
        type: typeSchema,
        url: z.string().optional(),
        authors: z.array(z.string()).optional().describe('Authors, "First Last" or "Last, First".'),
        container: z.string().optional().describe("Journal, publisher, or site name."),
        publishedDate: z.string().optional().describe("Year or ISO date."),
        doi: z.string().optional(),
        tags: tagsSchema,
        summary: z.string().optional().describe("A short summary in your own words."),
      },
    },
    async (args) =>
      guard(async () => {
        const source = await nb.addSource(args);
        return ok(`Added [${source.citeKey}].\n\n${sourceDetail(source)}`, { source });
      }),
  );

  // 3. add_quote -----------------------------------------------------------
  server.registerTool(
    "add_quote",
    {
      title: "Add a quote to a source",
      description: "Attach an excerpt (optionally with a page/location and your comment) to a source.",
      inputSchema: {
        source: sourceRef,
        text: z.string().describe("The quoted excerpt."),
        page: z.string().optional().describe('Page or location, e.g. "p. 12" or "§3.2".'),
        note: z.string().optional().describe("Why this excerpt matters."),
      },
    },
    async ({ source, text, page, note }) =>
      guard(async () => {
        const updated = await nb.addQuote(source, { text, page, note });
        return ok(`Added quote to [${updated.citeKey}] (${updated.quotes.length} total).`, {
          source: updated,
        });
      }),
  );

  // 4. add_note ------------------------------------------------------------
  server.registerTool(
    "add_note",
    {
      title: "Add a research note",
      description:
        "Write a research note in Markdown and link it to the sources it draws on. Notes are the " +
        "raw material generate_outline turns into a literature-review scaffold.",
      inputSchema: {
        title: z.string(),
        content: z.string().describe("Markdown body of the note."),
        sources: z.array(z.string()).optional().describe("Source ids or cite keys to link."),
        tags: tagsSchema,
      },
    },
    async ({ title, content, sources, tags }) =>
      guard(async () => {
        const note = await nb.addNote({ title, content, sourceIds: sources, tags });
        return ok(`Added note ${note.id}: "${note.title}".`, { note });
      }),
  );

  // 5. update_note ---------------------------------------------------------
  server.registerTool(
    "update_note",
    {
      title: "Update a research note",
      description: "Edit a note's title, content, tags, or linked sources. Omitted fields are unchanged.",
      inputSchema: {
        id: z.string().describe("Note id (note-...)."),
        title: z.string().optional(),
        content: z.string().optional(),
        sources: z.array(z.string()).optional().describe("Replaces the linked sources."),
        tags: tagsSchema,
      },
    },
    async ({ id, title, content, sources, tags }) =>
      guard(async () => {
        const note = await nb.updateNote(id, { title, content, sourceIds: sources, tags });
        return ok(`Updated note ${note.id}.`, { note });
      }),
  );

  // 6. update_source -------------------------------------------------------
  server.registerTool(
    "update_source",
    {
      title: "Update a source",
      description: "Correct or enrich a source's bibliographic fields. Omitted fields are unchanged.",
      inputSchema: {
        source: sourceRef,
        title: z.string().optional(),
        type: typeSchema,
        url: z.string().optional(),
        authors: z.array(z.string()).optional(),
        container: z.string().optional(),
        publishedDate: z.string().optional(),
        doi: z.string().optional(),
        tags: tagsSchema,
        summary: z.string().optional(),
      },
    },
    async ({ source, ...patch }) =>
      guard(async () => {
        const updated = await nb.updateSource(source, patch);
        return ok(`Updated [${updated.citeKey}].\n\n${sourceDetail(updated)}`, { source: updated });
      }),
  );

  // 7. list_sources --------------------------------------------------------
  server.registerTool(
    "list_sources",
    {
      title: "List sources",
      description: "List all saved sources, optionally filtered by tag.",
      inputSchema: { tag: z.string().optional() },
      annotations: { readOnlyHint: true },
    },
    async ({ tag }) =>
      guard(async () => {
        const sources = nb.listSources({ tag });
        const text =
          sources.length === 0
            ? "No sources yet."
            : `${sources.length} source(s):\n` + sources.map(sourceLine).join("\n");
        return ok(text, { sources });
      }),
  );

  // 8. list_notes ----------------------------------------------------------
  server.registerTool(
    "list_notes",
    {
      title: "List notes",
      description: "List research notes, optionally filtered by tag or by a linked source.",
      inputSchema: {
        tag: z.string().optional(),
        source: z.string().optional().describe("Only notes linked to this source id/cite key."),
      },
      annotations: { readOnlyHint: true },
    },
    async ({ tag, source }) =>
      guard(async () => {
        const notes = nb.listNotes({ tag, sourceId: source });
        const text =
          notes.length === 0
            ? "No notes yet."
            : `${notes.length} note(s):\n` + notes.map(noteLine).join("\n");
        return ok(text, { notes });
      }),
  );

  // 9. get_source ----------------------------------------------------------
  server.registerTool(
    "get_source",
    {
      title: "Get a source",
      description: "Show the full detail of one source, including its quotes.",
      inputSchema: { source: sourceRef },
      annotations: { readOnlyHint: true },
    },
    async ({ source }) =>
      guard(async () => {
        const s = nb.resolveSource(source);
        if (!s) return fail(`No source found with id or cite key "${source}".`);
        return ok(sourceDetail(s), { source: s });
      }),
  );

  // 10. get_note -----------------------------------------------------------
  server.registerTool(
    "get_note",
    {
      title: "Get a note",
      description: "Show the full content of one research note.",
      inputSchema: { id: z.string() },
      annotations: { readOnlyHint: true },
    },
    async ({ id }) =>
      guard(async () => {
        const n = nb.resolveNote(id);
        if (!n) return fail(`No note found with id "${id}".`);
        return ok(noteDetail(nb, n), { note: n });
      }),
  );

  // 11. search -------------------------------------------------------------
  server.registerTool(
    "search",
    {
      title: "Search the notebook",
      description:
        "Full-text search across sources (title, authors, journal, tags, quotes, summary) and notes " +
        "(title, content, tags). Returns matching cite keys and note ids.",
      inputSchema: { query: z.string().describe("Text to search for.") },
      annotations: { readOnlyHint: true },
    },
    async ({ query }) =>
      guard(async () => {
        const { sources, notes } = nb.search(query);
        const lines: string[] = [];
        lines.push(`Sources (${sources.length}):`);
        lines.push(...(sources.length ? sources.map((s) => `  ${sourceLine(s)}`) : ["  (none)"]));
        lines.push(`Notes (${notes.length}):`);
        lines.push(...(notes.length ? notes.map((n) => `  ${noteLine(n)}`) : ["  (none)"]));
        return ok(lines.join("\n"), { sources, notes });
      }),
  );

  // 12. remove_source ------------------------------------------------------
  server.registerTool(
    "remove_source",
    {
      title: "Remove a source",
      description:
        "Delete a source. Any notes that linked to it keep their text; the broken link is dropped " +
        "and reported.",
      inputSchema: { source: sourceRef },
      annotations: { destructiveHint: true },
    },
    async ({ source }) =>
      guard(async () => {
        const { removed, affectedNotes } = await nb.removeSource(source);
        const suffix =
          affectedNotes.length > 0
            ? ` Unlinked from ${affectedNotes.length} note(s): ${affectedNotes.join(", ")}.`
            : "";
        return ok(`Removed [${removed.citeKey}].${suffix}`, { removed, affectedNotes });
      }),
  );

  // 13. remove_note --------------------------------------------------------
  server.registerTool(
    "remove_note",
    {
      title: "Remove a note",
      description: "Delete a research note.",
      inputSchema: { id: z.string() },
      annotations: { destructiveHint: true },
    },
    async ({ id }) =>
      guard(async () => {
        const removed = await nb.removeNote(id);
        return ok(`Removed note ${removed.id}: "${removed.title}".`, { removed });
      }),
  );

  // 14. export_bibtex ------------------------------------------------------
  server.registerTool(
    "export_bibtex",
    {
      title: "Export BibTeX",
      description:
        "Render sources as BibTeX. Optionally filter by tag and/or write the result to " +
        "references.bib next to the notebook.",
      inputSchema: {
        tag: z.string().optional(),
        write: z.boolean().optional().describe("Also write references.bib to the notebook folder."),
      },
      // No readOnlyHint: with write:true this tool writes references.bib, and
      // clients use the hint to auto-approve tools without asking.
    },
    async ({ tag, write }) =>
      guard(async () => {
        const sources = nb.listSources({ tag });
        const bib = toBibtex(sources);
        let wrote = "";
        if (write) {
          const p = await nb.writeExport("references.bib", bib);
          const label = sources.length === 1 ? "entry" : "entries";
          wrote = `\n\nWrote ${sources.length} ${label} to ${p}`;
        }
        return ok((bib || "% No sources to export.") + wrote, {
          bibtex: bib,
          count: sources.length,
        });
      }),
  );

  // 15. export_markdown ----------------------------------------------------
  server.registerTool(
    "export_markdown",
    {
      title: "Export a Markdown bibliography",
      description:
        "Render a Markdown bibliography. Set annotated to include summaries and quotes. Optionally " +
        "filter by tag and/or write references.md next to the notebook.",
      inputSchema: {
        annotated: z.boolean().optional(),
        tag: z.string().optional(),
        title: z.string().optional(),
        write: z.boolean().optional().describe("Also write references.md to the notebook folder."),
      },
      // No readOnlyHint: with write:true this tool writes references.md.
    },
    async ({ annotated, tag, title, write }) =>
      guard(async () => {
        const md = exportBibliographyMarkdown(nb.snapshot(), { annotated, tag, title });
        let wrote = "";
        if (write) {
          const p = await nb.writeExport("references.md", md);
          wrote = `\n\nWrote ${p}`;
        }
        return ok(md + wrote, { markdown: md });
      }),
  );

  // 16. generate_outline ---------------------------------------------------
  server.registerTool(
    "generate_outline",
    {
      title: "Generate a literature-review outline",
      description:
        "Assemble your notes into a Markdown literature-review scaffold: notes grouped by theme, " +
        "each threaded with the cite keys it draws on, plus a References section and a 'not yet " +
        "written up' list. Optionally filter by tag and/or write outline.md next to the notebook.",
      inputSchema: {
        tag: z.string().optional(),
        title: z.string().optional(),
        write: z.boolean().optional().describe("Also write outline.md to the notebook folder."),
      },
      // No readOnlyHint: with write:true this tool writes outline.md.
    },
    async ({ tag, title, write }) =>
      guard(async () => {
        const md = generateOutline(nb.snapshot(), { tag, title });
        let wrote = "";
        if (write) {
          const p = await nb.writeExport("outline.md", md);
          wrote = `\n\nWrote ${p}`;
        }
        return ok(md + wrote, { outline: md });
      }),
  );

  // 17. notebook_stats -----------------------------------------------------
  server.registerTool(
    "notebook_stats",
    {
      title: "Notebook statistics",
      description: "Summarise the notebook: counts of sources, notes, quotes, tags, and types.",
      inputSchema: {},
      annotations: { readOnlyHint: true },
    },
    async () =>
      guard(async () => {
        const stats = nb.stats();
        const byType = Object.entries(stats.byType)
          .map(([k, v]) => `${k}: ${v}`)
          .join(", ");
        const text = [
          `Notebook: ${nb.title ?? "(untitled)"}  @ ${nb.directory}`,
          `Sources: ${stats.sources}  Notes: ${stats.notes}  Quotes: ${stats.quotes}`,
          `Types: ${byType || "(none)"}`,
          `Tags: ${stats.tags.length ? stats.tags.join(", ") : "(none)"}`,
        ].join("\n");
        return ok(text, stats);
      }),
  );

  return server;
}

/** Exposed for tests / callers that want a one-line human citation. */
export { renderReference };
