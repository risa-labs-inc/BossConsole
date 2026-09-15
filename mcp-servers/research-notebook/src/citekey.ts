/**
 * Citation-key generation.
 *
 * Produces stable, human-readable keys in the common author-year-word style
 * (e.g. `vaswani2017attention`) that BibTeX users expect, and disambiguates
 * collisions with a trailing letter (`smith2020a`, `smith2020b`). Pure and
 * deterministic so it is easy to test.
 */

const STOPWORDS = new Set([
  "a", "an", "the", "on", "of", "in", "to", "for", "and", "or", "with",
  "from", "by", "at", "as", "is", "are", "into", "over", "using", "via",
  "toward", "towards", "about", "how", "why", "what", "when",
]);

function asciiFold(input: string): string {
  // Strip diacritics (José -> Jose) then drop anything non-alphanumeric.
  return input
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "")
    .replace(/[^a-zA-Z0-9]/g, "")
    .toLowerCase();
}

/** Extract a surname from a name in either "Last, First" or "First Last" form. */
export function surnameOf(name: string): string {
  const trimmed = name.trim();
  if (!trimmed) return "";
  if (trimmed.includes(",")) {
    // "Vaswani, Ashish" -> "Vaswani"
    return asciiFold(trimmed.split(",")[0]!);
  }
  // "Ashish Vaswani" -> "Vaswani"; single token -> itself.
  const parts = trimmed.split(/\s+/);
  return asciiFold(parts[parts.length - 1]!);
}

/** Pull a 4-digit year from an ISO date or free-form string, if present. */
export function yearOf(publishedDate?: string): string {
  if (!publishedDate) return "nd"; // "no date"
  const match = publishedDate.match(/\b(1[5-9]\d{2}|20\d{2}|21\d{2})\b/);
  return match ? match[1]! : "nd";
}

/** First meaningful word of a title, for the tail of the key. */
export function titleWord(title: string): string {
  const words = title
    .split(/\s+/)
    .map((w) => asciiFold(w))
    .filter((w) => w.length > 0);
  const significant = words.find((w) => !STOPWORDS.has(w) && w.length > 1);
  return significant ?? words[0] ?? "";
}

export interface CiteKeyParts {
  authors?: string[];
  publishedDate?: string;
  title?: string;
  container?: string;
}

/** Build the base key (before collision suffixes). */
export function baseCiteKey(parts: CiteKeyParts): string {
  const author =
    (parts.authors && parts.authors.length > 0
      ? surnameOf(parts.authors[0]!)
      : "") ||
    (parts.container ? asciiFold(parts.container).slice(0, 12) : "") ||
    "anon";
  const year = yearOf(parts.publishedDate);
  const word = parts.title ? titleWord(parts.title) : "";
  return [author, year, word].filter((s) => s.length > 0).join("");
}

/**
 * Return a key not already present in `existing`. If the base collides, append
 * `a`, `b`, ... `z`, then `aa`, `ab`, ... to keep going.
 */
export function uniqueCiteKey(parts: CiteKeyParts, existing: Set<string>): string {
  const base = baseCiteKey(parts) || "source";
  if (!existing.has(base)) return base;
  let i = 0;
  // 0 -> "a", 25 -> "z", 26 -> "aa", ...
  const suffix = (n: number): string => {
    let s = "";
    let x = n;
    do {
      s = String.fromCharCode(97 + (x % 26)) + s;
      x = Math.floor(x / 26) - 1;
    } while (x >= 0);
    return s;
  };
  let candidate = base + suffix(i);
  while (existing.has(candidate)) {
    i += 1;
    candidate = base + suffix(i);
  }
  return candidate;
}
