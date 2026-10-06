/**
 * The Research Notebook store.
 *
 * A `Notebook` owns one JSON document on disk (`notebook.json`) inside a
 * directory the researcher chooses (their project, by default). It provides the
 * create / read / update / delete and search operations the MCP tools expose.
 *
 * Everything that touches the outside world - the filesystem, the clock, id
 * generation - is injected, so the whole class runs deterministically against a
 * temp directory in tests.
 */
import { promises as fs } from "node:fs";
import { randomBytes } from "node:crypto";
import * as path from "node:path";
import {
  type NotebookData,
  type Note,
  type Quote,
  type Source,
  type SourceType,
  SOURCE_TYPES,
  emptyNotebook,
} from "./types.js";
import { uniqueCiteKey } from "./citekey.js";

export interface NotebookDeps {
  /** Directory that holds `notebook.json` and generated exports. */
  dir: string;
  /** Clock, injectable for deterministic timestamps in tests. */
  now?: () => Date;
  /** Random suffix generator for ids, injectable for tests. */
  makeId?: () => string;
}

export const NOTEBOOK_FILENAME = "notebook.json";

export interface AddSourceInput {
  title: string;
  type?: SourceType;
  url?: string;
  authors?: string[];
  container?: string;
  publishedDate?: string;
  doi?: string;
  tags?: string[];
  summary?: string;
}

export interface RemoveSourceResult {
  removed: Source;
  /** Notes that still referenced the removed source (link was dropped). */
  affectedNotes: string[];
}

export class NotebookError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "NotebookError";
  }
}

/** Normalise a URL for de-duplication: drop fragment, tracking params, trailing slash. */
export function normaliseUrl(url: string): string {
  try {
    const u = new URL(url);
    u.hash = "";
    u.hostname = u.hostname.replace(/^www\./, "").toLowerCase();
    const drop = [...u.searchParams.keys()].filter(
      (k) => k.startsWith("utm_") || k === "fbclid" || k === "gclid" || k === "ref",
    );
    for (const k of drop) u.searchParams.delete(k);
    let out = u.toString();
    if (out.endsWith("/") && u.pathname !== "/") out = out.slice(0, -1);
    return out;
  } catch {
    return url.trim();
  }
}

export class Notebook {
  private readonly dir: string;
  private readonly now: () => Date;
  private readonly makeId: () => string;
  private data: NotebookData;

  private constructor(deps: NotebookDeps, data: NotebookData) {
    this.dir = deps.dir;
    this.now = deps.now ?? (() => new Date());
    this.makeId = deps.makeId ?? (() => randomBytes(4).toString("hex"));
    this.data = data;
  }

  get filePath(): string {
    return path.join(this.dir, NOTEBOOK_FILENAME);
  }

  get directory(): string {
    return this.dir;
  }

  /** Load the notebook from disk, creating an empty one if none exists. */
  static async open(deps: NotebookDeps): Promise<Notebook> {
    await fs.mkdir(deps.dir, { recursive: true });
    const filePath = path.join(deps.dir, NOTEBOOK_FILENAME);
    let data: NotebookData;
    try {
      const raw = await fs.readFile(filePath, "utf8");
      data = migrate(JSON.parse(raw));
    } catch (err) {
      if ((err as NodeJS.ErrnoException).code === "ENOENT") {
        data = emptyNotebook();
      } else if (err instanceof SyntaxError) {
        throw new NotebookError(
          `notebook.json at ${filePath} is not valid JSON. Fix or remove it before continuing.`,
        );
      } else {
        throw err;
      }
    }
    return new Notebook(deps, data);
  }

  private timestamp(): string {
    return this.now().toISOString();
  }

  private async persist(): Promise<void> {
    const tmp = `${this.filePath}.tmp`;
    await fs.writeFile(tmp, JSON.stringify(this.data, null, 2) + "\n", "utf8");
    await fs.rename(tmp, this.filePath); // atomic replace
  }

  /** Write an arbitrary export file next to the notebook and return its path. */
  async writeExport(filename: string, contents: string): Promise<string> {
    const target = path.join(this.dir, filename);
    await fs.writeFile(target, contents, "utf8");
    return target;
  }

  snapshot(): NotebookData {
    return structuredClone(this.data);
  }

  setTitle(title: string): Promise<void> {
    this.data.title = title;
    return this.persist();
  }

  get title(): string | undefined {
    return this.data.title;
  }

  allCiteKeys(): Set<string> {
    return new Set(this.data.sources.map((s) => s.citeKey));
  }

  // --- Sources -------------------------------------------------------------

  findByUrl(url: string): Source | undefined {
    const key = normaliseUrl(url);
    return this.data.sources.find((s) => s.url && normaliseUrl(s.url) === key);
  }

  resolveSource(idOrKey: string): Source | undefined {
    return this.data.sources.find((s) => s.id === idOrKey || s.citeKey === idOrKey);
  }

  private requireSource(idOrKey: string): Source {
    const src = this.resolveSource(idOrKey);
    if (!src) throw new NotebookError(`No source found with id or cite key "${idOrKey}".`);
    return src;
  }

  async addSource(input: AddSourceInput): Promise<Source> {
    const title = input.title?.trim();
    if (!title) throw new NotebookError("A source needs a non-empty title.");
    const ts = this.timestamp();
    const citeKey = uniqueCiteKey(
      {
        authors: input.authors,
        publishedDate: input.publishedDate,
        title,
        container: input.container,
      },
      this.allCiteKeys(),
    );
    const source: Source = {
      id: `src-${this.makeId()}`,
      citeKey,
      type: input.type ?? "webpage",
      title,
      url: input.url?.trim() || undefined,
      authors: dedupeStrings(input.authors ?? []),
      container: input.container?.trim() || undefined,
      publishedDate: input.publishedDate?.trim() || undefined,
      accessedDate: ts,
      doi: input.doi?.trim() || undefined,
      tags: normaliseTags(input.tags),
      quotes: [],
      summary: input.summary?.trim() || undefined,
      createdAt: ts,
      updatedAt: ts,
    };
    this.data.sources.push(source);
    await this.persist();
    return source;
  }

  async updateSource(
    idOrKey: string,
    patch: Partial<AddSourceInput>,
  ): Promise<Source> {
    const src = this.requireSource(idOrKey);
    if (patch.title !== undefined) {
      const t = patch.title.trim();
      if (!t) throw new NotebookError("Title cannot be blank.");
      src.title = t;
    }
    if (patch.type !== undefined) src.type = patch.type;
    if (patch.url !== undefined) src.url = patch.url.trim() || undefined;
    if (patch.authors !== undefined) src.authors = dedupeStrings(patch.authors);
    if (patch.container !== undefined) src.container = patch.container.trim() || undefined;
    if (patch.publishedDate !== undefined) src.publishedDate = patch.publishedDate.trim() || undefined;
    if (patch.doi !== undefined) src.doi = patch.doi.trim() || undefined;
    if (patch.tags !== undefined) src.tags = normaliseTags(patch.tags);
    if (patch.summary !== undefined) src.summary = patch.summary.trim() || undefined;
    src.updatedAt = this.timestamp();
    await this.persist();
    return src;
  }

  async addQuote(
    idOrKey: string,
    quote: { text: string; page?: string; note?: string },
  ): Promise<Source> {
    const src = this.requireSource(idOrKey);
    const text = quote.text?.trim();
    if (!text) throw new NotebookError("A quote needs non-empty text.");
    src.quotes.push({
      text,
      page: quote.page?.trim() || undefined,
      note: quote.note?.trim() || undefined,
      addedAt: this.timestamp(),
    });
    src.updatedAt = this.timestamp();
    await this.persist();
    return src;
  }

  async removeSource(idOrKey: string): Promise<RemoveSourceResult> {
    const src = this.requireSource(idOrKey);
    this.data.sources = this.data.sources.filter((s) => s.id !== src.id);
    const affectedNotes: string[] = [];
    for (const note of this.data.notes) {
      if (note.sourceIds.includes(src.id)) {
        note.sourceIds = note.sourceIds.filter((sid) => sid !== src.id);
        note.updatedAt = this.timestamp();
        affectedNotes.push(note.id);
      }
    }
    await this.persist();
    return { removed: src, affectedNotes };
  }

  listSources(filter?: { tag?: string }): Source[] {
    let list = [...this.data.sources];
    if (filter?.tag) {
      const tag = filter.tag.toLowerCase();
      list = list.filter((s) => s.tags.includes(tag));
    }
    return list.sort((a, b) => a.citeKey.localeCompare(b.citeKey));
  }

  // --- Notes ---------------------------------------------------------------

  private validateSourceIds(sourceIds: string[]): string[] {
    const resolved: string[] = [];
    for (const ref of sourceIds) {
      const src = this.resolveSource(ref);
      if (!src) throw new NotebookError(`Cannot link note to unknown source "${ref}".`);
      if (!resolved.includes(src.id)) resolved.push(src.id);
    }
    return resolved;
  }

  resolveNote(id: string): Note | undefined {
    return this.data.notes.find((n) => n.id === id);
  }

  private requireNote(id: string): Note {
    const note = this.resolveNote(id);
    if (!note) throw new NotebookError(`No note found with id "${id}".`);
    return note;
  }

  async addNote(input: {
    title: string;
    content: string;
    sourceIds?: string[];
    tags?: string[];
  }): Promise<Note> {
    const title = input.title?.trim();
    if (!title) throw new NotebookError("A note needs a non-empty title.");
    const ts = this.timestamp();
    const note: Note = {
      id: `note-${this.makeId()}`,
      title,
      content: input.content ?? "",
      sourceIds: this.validateSourceIds(input.sourceIds ?? []),
      tags: normaliseTags(input.tags),
      createdAt: ts,
      updatedAt: ts,
    };
    this.data.notes.push(note);
    await this.persist();
    return note;
  }

  async updateNote(
    id: string,
    patch: { title?: string; content?: string; sourceIds?: string[]; tags?: string[] },
  ): Promise<Note> {
    const note = this.requireNote(id);
    if (patch.title !== undefined) {
      const t = patch.title.trim();
      if (!t) throw new NotebookError("Title cannot be blank.");
      note.title = t;
    }
    if (patch.content !== undefined) note.content = patch.content;
    if (patch.sourceIds !== undefined) note.sourceIds = this.validateSourceIds(patch.sourceIds);
    if (patch.tags !== undefined) note.tags = normaliseTags(patch.tags);
    note.updatedAt = this.timestamp();
    await this.persist();
    return note;
  }

  async removeNote(id: string): Promise<Note> {
    const note = this.requireNote(id);
    this.data.notes = this.data.notes.filter((n) => n.id !== note.id);
    await this.persist();
    return note;
  }

  listNotes(filter?: { tag?: string; sourceId?: string }): Note[] {
    let list = [...this.data.notes];
    if (filter?.tag) {
      const tag = filter.tag.toLowerCase();
      list = list.filter((n) => n.tags.includes(tag));
    }
    if (filter?.sourceId) {
      const src = this.resolveSource(filter.sourceId);
      const id = src?.id ?? filter.sourceId;
      list = list.filter((n) => n.sourceIds.includes(id));
    }
    return list.sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
  }

  // --- Search & stats ------------------------------------------------------

  search(query: string): { sources: Source[]; notes: Note[] } {
    const q = query.trim().toLowerCase();
    if (!q) return { sources: [], notes: [] };
    const sources = this.data.sources.filter((s) =>
      [
        s.title,
        s.citeKey,
        s.container ?? "",
        s.summary ?? "",
        s.authors.join(" "),
        s.tags.join(" "),
        s.quotes.map((quote) => `${quote.text} ${quote.note ?? ""}`).join(" "),
      ]
        .join(" \n ")
        .toLowerCase()
        .includes(q),
    );
    const notes = this.data.notes.filter((n) =>
      [n.title, n.content, n.tags.join(" ")].join(" \n ").toLowerCase().includes(q),
    );
    return { sources, notes };
  }

  stats(): {
    sources: number;
    notes: number;
    quotes: number;
    tags: string[];
    byType: Record<string, number>;
  } {
    const tags = new Set<string>();
    const byType: Record<string, number> = {};
    let quotes = 0;
    for (const s of this.data.sources) {
      s.tags.forEach((t) => tags.add(t));
      quotes += s.quotes.length;
      byType[s.type] = (byType[s.type] ?? 0) + 1;
    }
    for (const n of this.data.notes) n.tags.forEach((t) => tags.add(t));
    return {
      sources: this.data.sources.length,
      notes: this.data.notes.length,
      quotes,
      tags: [...tags].sort(),
      byType,
    };
  }
}

function normaliseTags(tags: string[] | undefined): string[] {
  return dedupeStrings((tags ?? []).map((t) => t.trim().toLowerCase()).filter((t) => t.length > 0));
}

function dedupeStrings(values: string[]): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const v of values) {
    const trimmed = v.trim();
    if (!trimmed) continue;
    const key = trimmed.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(trimmed);
  }
  return out;
}

/** Bring an on-disk notebook up to the current schema version. */
function migrate(data: unknown): NotebookData {
  if (!data || typeof data !== "object") return emptyNotebook();
  const obj = data as Partial<NotebookData>;
  const sources: Source[] = [];
  const notes: Note[] = [];
  const dropped: string[] = [];
  (Array.isArray(obj.sources) ? obj.sources : []).forEach((entry, i) => {
    const normalised = normaliseSource(entry);
    if (normalised) sources.push(normalised);
    else dropped.push(`sources[${i}]`);
  });
  (Array.isArray(obj.notes) ? obj.notes : []).forEach((entry, i) => {
    const normalised = normaliseNote(entry);
    if (normalised) notes.push(normalised);
    else dropped.push(`notes[${i}]`);
  });
  // Fail closed, like the corrupt-JSON path: an entry the normaliser cannot
  // read is a hand-editing mistake, and persisting the document without it
  // would make the loss permanent on the next write.
  if (dropped.length > 0) {
    throw new NotebookError(
      `notebook.json has ${dropped.length} unrecognised entr${
        dropped.length === 1 ? "y" : "ies"
      } (${dropped.join(", ")}). Every source and note needs a non-empty "id"; ` +
        "fix or remove them before continuing.",
    );
  }
  return {
    version: 1,
    title: typeof obj.title === "string" ? obj.title : undefined,
    sources,
    notes,
  };
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value)
    ? value.filter((v): v is string => typeof v === "string")
    : [];
}

function optionalString(value: unknown): string | undefined {
  return typeof value === "string" && value.length > 0 ? value : undefined;
}

function optionalTimestamp(value: unknown): string {
  return typeof value === "string" && value.length > 0 ? value : new Date(0).toISOString();
}

/**
 * Normalise one hand-edited source entry so that reads (stats, exports, search)
 * never meet a missing field, and writes see the same shape the write paths
 * produce: tags trimmed/lowercased/deduped, authors deduped. Unknown keys
 * (e.g. a hand-added "isbn") are preserved so a load/save cycle is lossless.
 */
function normaliseSource(entry: unknown): Source | undefined {
  if (!entry || typeof entry !== "object") return undefined;
  const raw = entry as Partial<Source>;
  if (typeof raw.id !== "string" || raw.id.length === 0) return undefined;
  return {
    ...raw,
    id: raw.id,
    citeKey:
      typeof raw.citeKey === "string" && raw.citeKey.length > 0
        ? raw.citeKey
        : `source-${raw.id}`,
    type: (SOURCE_TYPES as readonly string[]).includes(raw.type ?? "")
      ? (raw.type as SourceType)
      : "webpage",
    title: typeof raw.title === "string" ? raw.title : "",
    url: optionalString(raw.url),
    authors: dedupeStrings(stringArray(raw.authors)),
    container: optionalString(raw.container),
    publishedDate: optionalString(raw.publishedDate),
    accessedDate: optionalString(raw.accessedDate),
    doi: optionalString(raw.doi),
    tags: normaliseTags(stringArray(raw.tags)),
    quotes: Array.isArray(raw.quotes)
      ? raw.quotes.filter(
          (q): q is Quote =>
            Boolean(q) && typeof q === "object" && typeof (q as Quote).text === "string",
        )
      : [],
    summary: optionalString(raw.summary),
    createdAt: optionalTimestamp(raw.createdAt),
    updatedAt: optionalTimestamp(raw.updatedAt),
  };
}

/**
 * Normalise one hand-edited note entry so reads never meet a missing field;
 * unknown keys are preserved as for sources.
 */
function normaliseNote(entry: unknown): Note | undefined {
  if (!entry || typeof entry !== "object") return undefined;
  const raw = entry as Partial<Note>;
  if (typeof raw.id !== "string" || raw.id.length === 0) return undefined;
  return {
    ...raw,
    id: raw.id,
    title: typeof raw.title === "string" ? raw.title : "",
    content: typeof raw.content === "string" ? raw.content : "",
    sourceIds: stringArray(raw.sourceIds),
    tags: normaliseTags(stringArray(raw.tags)),
    createdAt: optionalTimestamp(raw.createdAt),
    updatedAt: optionalTimestamp(raw.updatedAt),
  };
}
