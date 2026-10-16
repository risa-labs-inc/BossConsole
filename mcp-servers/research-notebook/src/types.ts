/**
 * Data model for the Research Notebook.
 *
 * A notebook is a single JSON document (plus generated Markdown / BibTeX
 * exports) that lives inside the researcher's project. It records **sources**
 * (things you cite) and **notes** (your own writing), with notes linked to the
 * sources they draw on. Everything here is plain data with no behaviour so it
 * serializes cleanly and is trivial to test.
 */

/** Bibliographic kind of a source. Drives the BibTeX entry type. */
export type SourceType =
  | "article" // journalistic / web article
  | "paper" // academic paper / preprint
  | "webpage" // a generic web page
  | "book"
  | "report"
  | "other";

export const SOURCE_TYPES: readonly SourceType[] = [
  "article",
  "paper",
  "webpage",
  "book",
  "report",
  "other",
];

/** An excerpt copied from a source, optionally with a page/location. */
export interface Quote {
  text: string;
  /** Page number or location label (e.g. "p. 42", "§3.1"). Optional. */
  page?: string;
  /** Why this excerpt matters, in your own words. Optional. */
  note?: string;
  addedAt: string; // ISO 8601
}

/** Something you cite: a paper, an article, a book, a web page. */
export interface Source {
  id: string; // stable internal id, e.g. "src-1a2b3c4d"
  /** Human-friendly citation key, e.g. "smith2020attention". Unique. */
  citeKey: string;
  type: SourceType;
  title: string;
  url?: string;
  authors: string[];
  /** Journal, publisher, or site name (BibTeX journal/publisher/howpublished). */
  container?: string;
  /** Publication date as an ISO date or a free-form year/label. */
  publishedDate?: string;
  /** When you added it to the notebook. Absent on hand-edited entries. */
  accessedDate?: string; // ISO 8601
  doi?: string;
  tags: string[];
  quotes: Quote[];
  /** A short summary of the source in your own words. */
  summary?: string;
  createdAt: string;
  updatedAt: string;
}

/** A piece of your own research writing, linked to the sources behind it. */
export interface Note {
  id: string; // "note-1a2b3c4d"
  title: string;
  /** Markdown body. */
  content: string;
  /** ids of sources this note draws on. */
  sourceIds: string[];
  tags: string[];
  createdAt: string;
  updatedAt: string;
}

/** The full on-disk notebook document. */
export interface NotebookData {
  /** Schema version, so older notebooks can be migrated forward. */
  version: 1;
  /** Optional project/topic title for exports. */
  title?: string;
  sources: Source[];
  notes: Note[];
}

export function emptyNotebook(title?: string): NotebookData {
  return { version: 1, title, sources: [], notes: [] };
}
