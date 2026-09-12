/**
 * BibTeX export.
 *
 * Renders sources as BibTeX entries keyed by their cite key, so a researcher
 * can drop the output straight into a LaTeX / Overleaf bibliography. Entry
 * types are chosen for broad compatibility with classic BibTeX (no biblatex-only
 * types), and every value is TeX-escaped.
 */
import type { Source, SourceType } from "./types.js";
import { yearOf } from "./citekey.js";

const ENTRY_TYPE: Record<SourceType, string> = {
  paper: "article",
  article: "misc",
  webpage: "misc",
  book: "book",
  report: "techreport",
  other: "misc",
};

/** One TeX escape per special character, applied in a single pass so that
 *  backslashes introduced by one replacement are never re-escaped. */
const TEX_ESCAPES: Record<string, string> = {
  "\\": "\\textbackslash{}",
  "&": "\\&",
  "%": "\\%",
  "$": "\\$",
  "#": "\\#",
  "_": "\\_",
  "{": "\\{",
  "}": "\\}",
  "~": "\\textasciitilde{}",
  "^": "\\textasciicircum{}",
};

/** Escape the characters that are special in TeX. */
export function escapeTex(value: string): string {
  return value.replace(/[\\&%$#_{}~^]/g, (ch) => TEX_ESCAPES[ch]!);
}

function field(name: string, value: string | undefined): string | undefined {
  if (!value) return undefined;
  return `  ${name} = {${escapeTex(value)}}`;
}

/** A field whose value is emitted verbatim (URLs, `\url{}`), not TeX-escaped. */
function rawField(name: string, value: string | undefined): string | undefined {
  if (!value) return undefined;
  return `  ${name} = {${value}}`;
}

export function toBibtexEntry(source: Source): string {
  const entryType = ENTRY_TYPE[source.type] ?? "misc";
  const year = yearOf(source.publishedDate);
  const fields: (string | undefined)[] = [
    field("title", source.title),
    source.authors.length > 0 ? field("author", source.authors.join(" and ")) : undefined,
  ];

  if (entryType === "article") {
    fields.push(field("journal", source.container));
  } else if (entryType === "book" || entryType === "techreport") {
    fields.push(field("publisher", source.container));
    fields.push(field("institution", source.type === "report" ? source.container : undefined));
  } else {
    // @misc: record where it was published. `\url{}` must not be TeX-escaped.
    if (source.url) fields.push(rawField("howpublished", `\\url{${source.url}}`));
    else fields.push(field("howpublished", source.container));
  }

  if (year !== "nd") fields.push(field("year", year));
  fields.push(field("doi", source.doi));
  if (entryType !== "misc") fields.push(rawField("url", source.url));
  if (source.accessedDate) {
    fields.push(field("urldate", source.accessedDate.slice(0, 10)));
  }

  const rendered = fields.filter((f): f is string => Boolean(f)).join(",\n");
  return `@${entryType}{${source.citeKey},\n${rendered}\n}`;
}

export function toBibtex(sources: Source[]): string {
  if (sources.length === 0) return "";
  return sources.map(toBibtexEntry).join("\n\n") + "\n";
}
