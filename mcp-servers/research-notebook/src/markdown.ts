/**
 * Markdown exports: a human-readable bibliography and a literature-review
 * outline scaffold assembled from the researcher's own notes.
 *
 * The outline is deliberately a *scaffold*, not prose: it groups the notes the
 * researcher already wrote under their tags, threads in the cite keys of the
 * sources each note draws on, and lists every source in a References section.
 * It gives an agent (or the researcher) a structured starting point instead of
 * a blank page.
 */
import type { NotebookData, Note, Source } from "./types.js";
import { yearOf } from "./citekey.js";

/** A one-line human citation, e.g. "Vaswani et al. (2017). *Attention...*. NeurIPS." */
export function renderReference(source: Source): string {
  const parts: string[] = [];
  if (source.authors.length > 0) parts.push(formatAuthors(source.authors));
  const year = yearOf(source.publishedDate);
  if (year !== "nd") parts.push(`(${year})`);
  const lead = parts.join(" ");

  const bits: string[] = [];
  if (lead) bits.push(lead.endsWith(".") ? lead : `${lead}.`);
  bits.push(`*${source.title.replace(/\*/g, "")}*.`);
  if (source.container) bits.push(`${source.container}.`);
  if (source.doi) bits.push(`https://doi.org/${source.doi}`);
  else if (source.url) bits.push(source.url);
  return bits.join(" ").replace(/\s+/g, " ").trim();
}

function formatAuthors(authors: string[]): string {
  if (authors.length === 1) return authors[0]!;
  if (authors.length === 2) return `${authors[0]} and ${authors[1]}`;
  return `${authors[0]} et al.`;
}

export interface MarkdownOptions {
  /** Include summaries and quotes under each source (annotated bibliography). */
  annotated?: boolean;
  /** Only include sources/notes carrying this tag. */
  tag?: string;
  title?: string;
}

function filterByTag<T extends { tags: string[] }>(items: T[], tag?: string): T[] {
  if (!tag) return items;
  const t = tag.toLowerCase();
  return items.filter((i) => i.tags.includes(t));
}

/** A stand-alone bibliography (optionally annotated) as Markdown. */
export function exportBibliographyMarkdown(
  data: NotebookData,
  opts: MarkdownOptions = {},
): string {
  const sources = filterByTag([...data.sources], opts.tag).sort((a, b) =>
    a.citeKey.localeCompare(b.citeKey),
  );
  const title = opts.title ?? data.title ?? "References";
  const lines: string[] = [`# ${title}`, ""];
  if (sources.length === 0) {
    lines.push("_No sources recorded yet._", "");
    return lines.join("\n");
  }
  for (const s of sources) {
    lines.push(`- **[${s.citeKey}]** ${renderReference(s)}`);
    if (opts.annotated) {
      if (s.summary) lines.push(`  - ${s.summary}`);
      for (const q of s.quotes) {
        const page = q.page ? ` (${q.page})` : "";
        lines.push(`  - > ${q.text}${page}`);
        if (q.note) lines.push(`    - ${q.note}`);
      }
    }
  }
  lines.push("");
  return lines.join("\n");
}

/**
 * A literature-review outline built from the notes. Notes are grouped by tag;
 * each note lists the cite keys it draws on; a References section closes it out.
 */
export function generateOutline(data: NotebookData, opts: MarkdownOptions = {}): string {
  const notes = filterByTag([...data.notes], opts.tag);
  const usedSourceIds = new Set(notes.flatMap((n) => n.sourceIds));
  const sourcesById = new Map(data.sources.map((s) => [s.id, s]));

  const title = opts.title ?? data.title ?? "Literature Review";
  const lines: string[] = [
    `# ${title}`,
    "",
    `_Draft outline generated from ${notes.length} note(s) and ${data.sources.length} source(s)._`,
    "",
  ];

  // Group notes by their first tag; notes with no tag go under "Unsorted".
  const groups = new Map<string, Note[]>();
  for (const note of notes) {
    const key = note.tags[0] ?? "unsorted";
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key)!.push(note);
  }

  if (notes.length === 0) {
    lines.push("_No notes to outline yet. Capture notes with `add_note` first._", "");
  } else {
    lines.push("## Themes", "");
    for (const theme of [...groups.keys()].sort()) {
      lines.push(`### ${titleCase(theme)}`, "");
      for (const note of groups.get(theme)!) {
        const keys = note.sourceIds
          .map((id) => sourcesById.get(id)?.citeKey)
          .filter((k): k is string => Boolean(k));
        const cites = keys.length > 0 ? ` [${keys.map((k) => `@${k}`).join(", ")}]` : "";
        lines.push(`- **${note.title}**${cites}`);
        const firstLine = note.content.split("\n").find((l) => l.trim().length > 0);
        if (firstLine) lines.push(`  ${firstLine.trim()}`);
      }
      lines.push("");
    }
  }

  // Sources captured but not yet written up - useful "still to read" list.
  const unusedSources = filterByTag([...data.sources], opts.tag).filter(
    (s) => !usedSourceIds.has(s.id),
  );
  if (unusedSources.length > 0) {
    lines.push("## Sources not yet written up", "");
    for (const s of unusedSources.sort((a, b) => a.citeKey.localeCompare(b.citeKey))) {
      lines.push(`- [${s.citeKey}] ${renderReference(s)}`);
    }
    lines.push("");
  }

  // Full reference list.
  const refs = filterByTag([...data.sources], opts.tag).sort((a, b) =>
    a.citeKey.localeCompare(b.citeKey),
  );
  lines.push("## References", "");
  if (refs.length === 0) {
    lines.push("_No sources recorded yet._", "");
  } else {
    for (const s of refs) lines.push(`- **[${s.citeKey}]** ${renderReference(s)}`);
    lines.push("");
  }

  return lines.join("\n");
}

function titleCase(value: string): string {
  return value
    .split(/[\s_-]+/)
    .map((w) => (w.length > 0 ? w[0]!.toUpperCase() + w.slice(1) : w))
    .join(" ");
}
