/**
 * Bibliographic metadata extraction from an HTML page.
 *
 * `extractMetadata(html, url)` is a **pure** function: give it the raw HTML and
 * the URL it came from and it returns the best title / authors / date /
 * container / DOI it can find. It reads, in order of trust:
 *
 *   1. Highwire / Google Scholar `citation_*` tags (publishers, arXiv, PubMed)
 *   2. JSON-LD (`schema.org` Article / ScholarlyArticle / NewsArticle ...)
 *   3. OpenGraph / Twitter card / Dublin Core / standard `<meta>` tags
 *   4. The `<title>` / first `<h1>` as a last resort
 *
 * Keeping it pure and network-free is what makes `cite_url` testable against
 * fixture HTML with no live requests.
 */
import { parse, type HTMLElement } from "node-html-parser";
import type { SourceType } from "./types.js";

export interface ExtractedMetadata {
  title?: string;
  authors: string[];
  container?: string;
  publishedDate?: string;
  doi?: string;
  summary?: string;
  type: SourceType;
}

function clean(value: string | undefined | null): string | undefined {
  if (value == null) return undefined;
  const collapsed = value.replace(/\s+/g, " ").trim();
  return collapsed.length > 0 ? collapsed : undefined;
}

/** All `content` values for meta tags matching any of `keys` (name or property). */
function metaAll(root: HTMLElement, keys: string[]): string[] {
  const wanted = new Set(keys.map((k) => k.toLowerCase()));
  const out: string[] = [];
  for (const el of root.querySelectorAll("meta")) {
    const key = (el.getAttribute("name") ?? el.getAttribute("property") ?? "").toLowerCase();
    if (!key || !wanted.has(key)) continue;
    const content = clean(el.getAttribute("content"));
    if (content) out.push(content);
  }
  return out;
}

function metaFirst(root: HTMLElement, keys: string[]): string | undefined {
  return metaAll(root, keys)[0];
}

interface JsonLdNode {
  "@type"?: string | string[];
  name?: string;
  headline?: string;
  author?: unknown;
  creator?: unknown;
  datePublished?: string;
  dateCreated?: string;
  publisher?: unknown;
  description?: string;
  isPartOf?: unknown;
  [key: string]: unknown;
}

function collectJsonLdNodes(root: HTMLElement): JsonLdNode[] {
  const nodes: JsonLdNode[] = [];
  for (const script of root.querySelectorAll('script[type="application/ld+json"]')) {
    const raw = script.textContent?.trim();
    if (!raw) continue;
    try {
      const parsed = JSON.parse(raw);
      const queue: unknown[] = Array.isArray(parsed) ? [...parsed] : [parsed];
      while (queue.length > 0) {
        const item = queue.shift();
        if (!item || typeof item !== "object") continue;
        const obj = item as JsonLdNode;
        if (Array.isArray(obj["@graph"])) queue.push(...(obj["@graph"] as unknown[]));
        nodes.push(obj);
      }
    } catch {
      // Malformed JSON-LD is common in the wild; skip it silently.
    }
  }
  return nodes;
}

/** Normalise a schema.org author/creator field (string | object | array). */
function jsonLdNames(value: unknown): string[] {
  if (!value) return [];
  if (typeof value === "string") return [value];
  if (Array.isArray(value)) return value.flatMap(jsonLdNames);
  if (typeof value === "object") {
    const name = (value as { name?: unknown }).name;
    if (typeof name === "string") return [name];
  }
  return [];
}

const ARTICLE_TYPES = new Set([
  "article",
  "newsarticle",
  "blogposting",
  "report",
  "techarticle",
]);
const PAPER_TYPES = new Set([
  "scholarlyarticle",
  "article", // upgraded to paper when citation_* / doi present (see below)
]);

function inferType(opts: {
  hasCitationTags: boolean;
  hasDoi: boolean;
  jsonLdTypes: string[];
  ogType?: string;
}): SourceType {
  const lower = opts.jsonLdTypes.map((t) => t.toLowerCase());
  if (opts.hasCitationTags || opts.hasDoi) return "paper";
  if (lower.some((t) => PAPER_TYPES.has(t) && t === "scholarlyarticle")) return "paper";
  if (lower.some((t) => t === "book")) return "book";
  if (lower.some((t) => t === "report")) return "report";
  if (lower.some((t) => ARTICLE_TYPES.has(t))) return "article";
  if (opts.ogType && opts.ogType.toLowerCase().includes("article")) return "article";
  return "webpage";
}

export function hostnameOf(url: string): string | undefined {
  try {
    return new URL(url).hostname.replace(/^www\./, "");
  } catch {
    return undefined;
  }
}

export function extractMetadata(html: string, url: string): ExtractedMetadata {
  const root = parse(html, { comment: false });

  // --- Authors -------------------------------------------------------------
  const citationAuthors = metaAll(root, ["citation_author", "citation_authors"]);
  const jsonLd = collectJsonLdNodes(root);
  const jsonLdAuthors = jsonLd.flatMap((n) => [
    ...jsonLdNames(n.author),
    ...jsonLdNames(n.creator),
  ]);
  const metaAuthors = metaAll(root, [
    "author",
    "article:author",
    "dc.creator",
    "dcterms.creator",
    "parsely-author",
  ]);
  // Some sites cram multiple authors into one meta tag separated by ; or ,.
  const authors = dedupeAuthors(
    [...citationAuthors, ...jsonLdAuthors, ...metaAuthors].flatMap(splitAuthorField),
  );

  // --- Title ---------------------------------------------------------------
  // Prefer any node's `headline` (articles) over any node's `name` (which a
  // WebSite/Organization node in an @graph also carries and would otherwise win).
  const jsonLdTitle =
    jsonLd.map((n) => clean(n.headline)).find(Boolean) ??
    jsonLd.map((n) => clean(n.name)).find(Boolean);
  const title =
    metaFirst(root, ["citation_title"]) ??
    metaFirst(root, ["og:title", "twitter:title"]) ??
    jsonLdTitle ??
    clean(root.querySelector("title")?.textContent) ??
    clean(root.querySelector("h1")?.textContent);

  // --- Date ----------------------------------------------------------------
  const jsonLdDate = jsonLd
    .map((n) => clean(n.datePublished) ?? clean(n.dateCreated))
    .find(Boolean);
  const timeAttr = root.querySelector("time[datetime]")?.getAttribute("datetime");
  const publishedDate =
    metaFirst(root, ["citation_publication_date", "citation_date"]) ??
    metaFirst(root, [
      "article:published_time",
      "date",
      "dc.date",
      "dcterms.date",
      "dc.date.issued",
      "prism.publicationdate",
      "sailthru.date",
    ]) ??
    jsonLdDate ??
    clean(timeAttr);

  // --- Container (journal / site / publisher) ------------------------------
  const jsonLdPublisher = jsonLd.map((n) => jsonLdNames(n.publisher)[0]).find(Boolean);
  const container =
    metaFirst(root, ["citation_journal_title", "citation_conference_title"]) ??
    metaFirst(root, ["og:site_name"]) ??
    metaFirst(root, ["dc.publisher", "dcterms.publisher", "citation_publisher"]) ??
    jsonLdPublisher ??
    hostnameOf(url);

  // --- DOI -----------------------------------------------------------------
  const jsonLdDoi = jsonLd
    .map((n) => {
      const id = n["@id"] ?? n["identifier"];
      return typeof id === "string" ? id : undefined;
    })
    .map((s) => extractDoi(s))
    .find(Boolean);
  const doi =
    normaliseDoi(metaFirst(root, ["citation_doi", "dc.identifier", "doi", "prism.doi"])) ??
    jsonLdDoi ??
    extractDoi(url);

  // --- Summary -------------------------------------------------------------
  const summary =
    metaFirst(root, ["og:description", "twitter:description", "description"]) ??
    metaFirst(root, ["citation_abstract", "dc.description"]) ??
    jsonLd.map((n) => clean(n.description)).find(Boolean);

  const type = inferType({
    hasCitationTags: citationAuthors.length > 0 || Boolean(metaFirst(root, ["citation_title"])),
    hasDoi: Boolean(doi),
    jsonLdTypes: jsonLd.flatMap((n) =>
      Array.isArray(n["@type"]) ? n["@type"] : n["@type"] ? [n["@type"]] : [],
    ),
    ogType: metaFirst(root, ["og:type"]),
  });

  return { title, authors, container, publishedDate, doi, summary, type };
}

/** Split "Ada Lovelace; Alan Turing" or "Lovelace, Ada and Turing, Alan". */
function splitAuthorField(field: string): string[] {
  // Preserve "Last, First" by only splitting on ';', ' and ', or '&'.
  const parts = field
    .split(/\s*;\s*|\s+and\s+|\s*&\s*/i)
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
  return parts.length > 0 ? parts : [field.trim()];
}

function dedupeAuthors(authors: string[]): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const a of authors) {
    const key = a.toLowerCase().replace(/[^a-z0-9]/g, "");
    if (!key || seen.has(key)) continue;
    seen.add(key);
    out.push(a);
  }
  return out;
}

const DOI_RE = /10\.\d{4,9}\/[-._;()/:a-zA-Z0-9]+/;

export function extractDoi(value: string | undefined): string | undefined {
  if (!value) return undefined;
  const match = value.match(DOI_RE);
  return match ? match[0].replace(/[.,;]+$/, "") : undefined;
}

function normaliseDoi(value: string | undefined): string | undefined {
  return extractDoi(value);
}
