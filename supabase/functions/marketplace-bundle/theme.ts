/**
 * Max theme validation: a TypeScript port of boss-plugin-max themes/ThemePackage.kt (validate a theme.json and its
 * art.svg and woff2 fonts). The same rules run here at publish and again in Max at install, so the port keeps the
 * Kotlin semantics where they could differ from JavaScript's:
 *
 *   - Kotlin's trim()/isBlank() use Char.isWhitespace (Unicode space separators, \t \n \v \f \r and U+001C..U+001F,
 *     not U+FEFF), and its regexes' \s is ASCII only; both are spelled out below rather than left to JS's \s/trim().
 *   - Strings are walked by UTF-16 unit, as Kotlin's Char loops do.
 *   - Error messages carry the same meanings and wording. Two deliberate differences, both so that nothing from the
 *     upload is echoed back (this function's rule, see manifest.ts): a token value is not quoted in "is not a colour",
 *     and an unknown key is named only when it is a plain identifier.
 *
 * Known gap: JSON `1.0` for schema is accepted here (JavaScript cannot tell it from 1); kotlinx's intOrNull refuses it.
 */
import { BundleError } from "./manifest.ts";

const fail = (message: string): never => {
  throw new BundleError("invalid_theme", message);
};

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => typeof v === "object" && v !== null && !Array.isArray(v);

/** Role tokens a theme may set (ThemePackage.ROLES keys). */
export const ROLES = [
  "bg",
  "surface",
  "surface-2",
  "surface-3",
  "border",
  "border-strong",
  "text",
  "text-2",
  "text-3",
  "accent",
  "accent-hover",
  "accent-soft",
  "accent-soft-2",
  "on-accent",
  "focus",
  "link-them",
  "bubble-me",
  "on-bubble-me",
  "bubble-them",
  "on-bubble-them",
  "danger",
  "warning",
  "success",
];
export const REQUIRED = [
  "bg",
  "surface",
  "text",
  "accent",
  "on-accent",
  "bubble-me",
  "on-bubble-me",
  "bubble-them",
  "on-bubble-them",
];
/** Foreground/background pairs that must reach MIN_CONTRAST in both schemes (when both are set). */
export const CONTRAST_PAIRS: Array<[string, string]> = [
  ["text", "bg"],
  ["text", "surface"],
  ["text-2", "surface"],
  ["text-3", "bg"],
  ["on-accent", "accent"],
  ["on-bubble-me", "bubble-me"],
  ["on-bubble-them", "bubble-them"],
];
export const MIN_CONTRAST = 4.5;
export const RADII = ["sharp", "default", "soft", "round"];

export const THEME_ID = /^[a-z][a-z0-9-]{1,30}$/;
export const MAX_JSON = 32 * 1024;
export const MAX_ART = 32 * 1024;
export const MAX_FONT = 150 * 1024;
export const MAX_BACKDROP = 800;
export const MAX_LAYERS = 4;
export const FONT_PATH = /^fonts\/[A-Za-z0-9_-]{1,48}\.woff2$/;

/** Java's \s: ASCII whitespace only. */
const JWS = "[ \\t\\n\\x0B\\f\\r]";
const HEX = /^#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/;
const NUM = "-?(?:\\d+\\.?\\d*|\\.\\d+)%?";
const FUNC = new RegExp(
  `^(rgba?|hsla?)\\(${JWS}*${NUM}(?:deg)?${JWS}*(?:,${JWS}*|${JWS}+)${NUM}${JWS}*(?:,${JWS}*|${JWS}+)` +
    `${NUM}${JWS}*(?:(?:,|/)${JWS}*${NUM}${JWS}*)?\\)$`,
);
const GRADIENT_NUMBER = new RegExp(`^${NUM}(?:deg|px|turn)?$`);
/** Words a gradient may use besides colours and numbers. */
const WORDS = new Set([
  "to",
  "at",
  "top",
  "bottom",
  "left",
  "right",
  "center",
  "circle",
  "ellipse",
  "closest-side",
  "closest-corner",
  "farthest-side",
  "farthest-corner",
  "transparent",
]);
const BANNED = [
  "url(",
  ";",
  "{",
  "}",
  "\\",
  "@",
  "<",
  ">",
  "/*",
  "*/",
  "!",
  "expression",
  "image-set",
  "var(",
  "attr(",
  '"',
  "'",
];

/** Kotlin Char.isWhitespace. */
// deno-lint-ignore no-control-regex
const ktWhitespace = (c: string) => /^[\t\n\u000B\f\r\u001C-\u001F\p{Zs}\u2028\u2029]$/u.test(c);
/** Kotlin String.trim(). */
export function ktTrim(s: string): string {
  let a = 0;
  let b = s.length;
  while (a < b && ktWhitespace(s[a])) a++;
  while (b > a && ktWhitespace(s[b - 1])) b--;
  return s.slice(a, b);
}
const ktBlank = (s: string) => ktTrim(s).length === 0;
/** Kotlin Char.isISOControl. */
const isoControl = (c: number) => c <= 0x1f || (c >= 0x7f && c <= 0x9f);

/** No control or bidi-override characters (ThemePackage.clean). */
function clean(s: string): boolean {
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    if (isoControl(c) || (c >= 0x202a && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069)) return false;
  }
  return true;
}

export function isColour(v: string): boolean {
  return v.length <= 64 &&
    (HEX.test(v) || FUNC.test(ktTrim(v).toLowerCase()) || v === "transparent");
}

/** Splits on top-level commas; null when parentheses don't balance. */
function splitTop(s: string): string[] | null {
  const out: string[] = [];
  let depth = 0;
  let start = 0;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (c === "(") depth++;
    else if (c === ")") {
      depth--;
      if (depth < 0) return null;
    } else if (c === "," && depth === 0) {
      out.push(s.substring(start, i));
      start = i + 1;
    }
  }
  if (depth !== 0) return null;
  out.push(s.substring(start));
  return out;
}

/** Space-separated tokens, keeping `rgba(...)` whole; null when parentheses don't balance. */
function tokens(s: string): string[] | null {
  const out: string[] = [];
  let cur = "";
  let depth = 0;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (c === "(") {
      depth++;
      cur += c;
    } else if (c === ")") {
      depth--;
      if (depth < 0) return null;
      cur += c;
    } else if (ktWhitespace(c) && depth === 0) {
      if (cur.length > 0) {
        out.push(cur);
        cur = "";
      }
    } else cur += c;
  }
  if (depth !== 0) return null;
  if (cur.length > 0) out.push(cur);
  return out;
}

function isGradient(layer: string): boolean {
  const t = ktTrim(layer);
  let name: string;
  if (t.startsWith("linear-gradient(")) name = "linear-gradient(";
  else if (t.startsWith("radial-gradient(")) name = "radial-gradient(";
  else return false;
  if (!t.endsWith(")")) return false;
  const inner = t.substring(name.length, t.length - 1);
  const parts = splitTop(inner);
  if (!parts || parts.length < 2) return false;
  let colours = 0;
  for (let i = 0; i < parts.length; i++) {
    const toks = tokens(ktTrim(parts[i]));
    if (!toks) return false;
    let hasColour = false;
    for (const tok of toks) {
      if (isColour(tok)) hasColour = true;
      else if (GRADIENT_NUMBER.test(tok)) { /* a number */ }
      else if (WORDS.has(tok)) { /* a position word */ }
      else return false;
    }
    if (hasColour) colours++;
    else if (i !== 0) return false;
  }
  return colours >= 2;
}

/** One backdrop value: 1..MAX_LAYERS linear/radial gradients of colours, numbers and position words. */
export function isBackdrop(v: string): boolean {
  if (ktBlank(v) || v.length > MAX_BACKDROP) return false;
  const lower = v.toLowerCase();
  if (BANNED.some((b) => lower.includes(b))) return false;
  for (let i = 0; i < lower.length; i++) if (isoControl(lower.charCodeAt(i))) return false;
  const layers = splitTop(lower);
  if (!layers || layers.length < 1 || layers.length > MAX_LAYERS) return false;
  return layers.every(isGradient);
}

/** Kotlin removeSuffix("%").removeSuffix("deg").toDoubleOrNull() on a NUM-shaped token. */
function num(x: string): number | null {
  let s = x.endsWith("%") ? x.slice(0, -1) : x;
  s = s.endsWith("deg") ? s.slice(0, -3) : s;
  if (s.length === 0 || !/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(s)) return null;
  return Number(s);
}
const coerce = (v: number) => Math.min(1, Math.max(0, v));

/** sRGB of a colour (alpha ignored), or null for one that can't be read (then contrast can't be proven). */
export function rgb(v: string): [number, number, number] | null {
  const s = ktTrim(v).toLowerCase();
  if (HEX.test(s)) {
    const h = s.substring(1);
    const full = h.length === 3 || h.length === 4
      ? [...h.slice(0, 3)].map((c) => c + c).join("")
      : h.slice(0, 6);
    return [
      parseInt(full.slice(0, 2), 16) / 255,
      parseInt(full.slice(2, 4), 16) / 255,
      parseInt(full.slice(4, 6), 16) / 255,
    ];
  }
  const m = /^(rgba?|hsla?)\(([^\n\r\u0085\u2028\u2029]*)\)$/.exec(s);
  if (!m) return null;
  // deno-lint-ignore no-control-regex
  const nums = m[2].split(/[,/ \t\n\x0B\f\r]+/).filter((x) => x.length > 0);
  if (nums.length < 3) return null;
  if (m[1].startsWith("rgb")) {
    const ch = (x: string) => {
      const n = num(x);
      return n === null ? null : coerce(x.endsWith("%") ? n / 100 : n / 255);
    };
    const r = ch(nums[0]);
    const g = ch(nums[1]);
    const b = ch(nums[2]);
    return r === null || g === null || b === null ? null : [r, g, b];
  }
  const h0 = num(nums[0]);
  const s0 = num(nums[1]);
  const l0 = num(nums[2]);
  if (h0 === null || s0 === null || l0 === null) return null;
  const hh = ((h0 % 360) + 360) % 360;
  const ss = coerce(s0 / 100);
  const ll = coerce(l0 / 100);
  const c = (1 - Math.abs(2 * ll - 1)) * ss;
  const x = c * (1 - Math.abs((hh / 60) % 2 - 1));
  const mm = ll - c / 2;
  const [r, g, b] = hh < 60
    ? [c, x, 0]
    : hh < 120
    ? [x, c, 0]
    : hh < 180
    ? [0, c, x]
    : hh < 240
    ? [0, x, c]
    : hh < 300
    ? [x, 0, c]
    : [c, 0, x];
  return [r + mm, g + mm, b + mm];
}

function luminance([r, g, b]: [number, number, number]): number {
  const lin = (v: number) => (v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4));
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
}

/** WCAG contrast ratio of two colours, or null when either can't be read. */
export function contrast(a: string, b: string): number | null {
  const ra = rgb(a);
  const rb = rgb(b);
  if (!ra || !rb) return null;
  const la = luminance(ra);
  const lb = luminance(rb);
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

/** An SVG usable as a CSS background: no script, foreign content, event handlers or outside references. */
export function safeSvg(bytes: Uint8Array): boolean {
  if (bytes.length > MAX_ART) return false;
  const lower = new TextDecoder("utf-8").decode(bytes).toLowerCase();
  if (!lower.includes("<svg")) return false;
  const banned = [
    "<script",
    "<foreignobject",
    "<iframe",
    "<object",
    "<embed",
    "<!entity",
    "<!doctype",
    "javascript:",
    "url(",
    "@import",
    "<image",
    "<use",
    "<animate",
    "<set",
  ];
  if (banned.some((b) => lower.includes(b))) return false;
  // deno-lint-ignore no-control-regex
  if (/[ \t\n\x0B\f\r]on[a-z]+[ \t\n\x0B\f\r]*=/.test(lower)) return false;
  // href / xlink:href only to a local fragment.
  const href =
    // deno-lint-ignore no-control-regex
    /(?:xlink:)?href[ \t\n\x0B\f\r]*=[ \t\n\x0B\f\r]*(["'])([^\n\r\u0085\u2028\u2029]*?)\1/g;
  for (const m of lower.matchAll(href)) if (!m[2].startsWith("#")) return false;
  return true;
}

export function isWoff2(bytes: Uint8Array): boolean {
  return bytes.length >= 12 && bytes.length <= MAX_FONT && bytes[0] === 0x77 && bytes[1] === 0x4f &&
    bytes[2] === 0x46 && bytes[3] === 0x32;
}

/** A key named in a message only when it is a plain identifier: nothing else from the upload is echoed. */
const keyName = (k: string) => (/^[A-Za-z0-9_-]{1,40}$/.test(k) ? k : "(a key)");

function only(o: Obj, keys: string[], where: string): void {
  const k = Object.keys(o).find((x) => !keys.includes(x));
  if (k !== undefined) fail(`${where}.${keyName(k)} is not allowed.`);
}

const str = (
  o: Obj,
  k: string,
): string | null => (typeof o[k] === "string" ? o[k] as string : null);

export interface Tones {
  page: string;
  sent: string;
  received: string;
}

export interface ThemePackage {
  id: string;
  name: string;
  summary: string;
  authorName: string;
  authorUrl: string | null;
  light: Record<string, string>;
  dark: Record<string, string>;
  backdropLight: string | null;
  backdropDark: string | null;
  radius: string;
  preview: { light: Tones; dark: Tones };
  fonts: Record<string, string>;
  art: string | null;
}

function scheme(e: unknown, which: string): Record<string, string> {
  if (!isObj(e)) return fail(`tokens.${which} is required.`);
  const out: Record<string, string> = {};
  for (const [k, v] of Object.entries(e)) {
    if (!ROLES.includes(k)) fail(`tokens.${which}.${keyName(k)} is not a theme token.`);
    if (typeof v !== "string") fail(`tokens.${which}.${k} must be a colour.`);
    const s = ktTrim(v as string);
    if (!isColour(s)) {
      fail(`tokens.${which}.${k} is not a colour (#hex, rgb(), rgba(), hsl() or hsla()).`);
    }
    out[k] = s;
  }
  const missing = REQUIRED.filter((r) => !(r in out)).sort();
  if (missing.length > 0) fail(`tokens.${which} needs ${missing.join(", ")}.`);
  return out;
}

function tones(e: unknown, which: string): Tones {
  if (!isObj(e)) return fail(`preview.${which} is required.`);
  only(e, ["page", "sent", "received"], `preview.${which}`);
  const page = str(e, "page");
  if (page === null) fail(`preview.${which}.page is required.`);
  if (!isColour(page!) && !isBackdrop(page!)) fail(`preview.${which}.page: a colour or gradient.`);
  const sent = str(e, "sent");
  if (sent === null || !(isColour(sent) || isBackdrop(sent))) {
    fail(`preview.${which}.sent: a colour or gradient.`);
  }
  const received = str(e, "received");
  if (received === null || !(isColour(received) || isBackdrop(received))) {
    fail(`preview.${which}.received: a colour or gradient.`);
  }
  return { page: page!, sent: sent!, received: received! };
}

const AUTHOR_URL =
  /^https:\/\/[A-Za-z0-9.-]+(?::\d+)?(?:\/[A-Za-z0-9._~:/?#\[\]@!$&'()*+,;=%-]*)?$/;

/**
 * Parses and checks a theme (ThemePackage.parse). `files` are the bundle's other files by relative path (art and
 * fonts). Throws BundleError("invalid_theme") naming the first problem.
 */
export function parseTheme(
  text: string,
  files: Map<string, Uint8Array> = new Map(),
  expectedId: string | null = null,
): ThemePackage {
  if (new TextEncoder().encode(text).length > MAX_JSON) fail("theme.json is larger than 32 KB.");
  let root: unknown;
  try {
    root = JSON.parse(text);
  } catch {
    root = null;
  }
  if (!isObj(root)) return fail("theme.json is not a JSON object.");
  only(root, [
    "schema",
    "id",
    "name",
    "summary",
    "author",
    "tokens",
    "backdrop",
    "radius",
    "fonts",
    "art",
    "preview",
  ], "theme");
  if (root.schema !== 1) fail("schema must be 1.");
  const id = str(root, "id");
  if (id === null) fail("id is required.");
  if (!THEME_ID.test(id!)) {
    fail("id: 2 to 31 lowercase letters, digits and dashes, starting with a letter.");
  }
  if (expectedId !== null && id !== expectedId) fail(`id must be \`${expectedId}\`.`);
  const nameRaw = str(root, "name");
  const name = nameRaw === null ? null : ktTrim(nameRaw);
  if (name === null || name.length < 1 || name.length > 40 || !clean(name)) {
    fail("name: 1 to 40 characters.");
  }
  const summaryRaw = str(root, "summary");
  const summary = summaryRaw === null ? null : ktTrim(summaryRaw);
  if (summary === null || summary.length > 200 || !clean(summary)) {
    fail("summary: up to 200 characters.");
  }
  const author = root.author;
  if (!isObj(author)) return fail('author is required: {"name": ...}.');
  only(author, ["name", "url"], "author");
  const authorNameRaw = str(author, "name");
  const authorName = authorNameRaw === null ? null : ktTrim(authorNameRaw);
  if (
    authorName === null || authorName.length < 1 || authorName.length > 60 || !clean(authorName)
  ) {
    fail("author.name: 1 to 60 characters.");
  }
  const authorUrlRaw = str(author, "url");
  const authorUrl = authorUrlRaw === null ? null : ktTrim(authorUrlRaw);
  if (authorUrl !== null && (!AUTHOR_URL.test(authorUrl) || authorUrl.length > 200)) {
    fail("author.url must be an https address.");
  }
  const tokensObj = root.tokens;
  if (!isObj(tokensObj)) {
    return fail('tokens is required: {"light": {...}, "dark": {...}}.');
  }
  only(tokensObj, ["light", "dark"], "tokens");
  const light = scheme(tokensObj.light, "light");
  const dark = scheme(tokensObj.dark, "dark");
  let backdropLight: string | null = null;
  let backdropDark: string | null = null;
  if ("backdrop" in root) {
    const backdrop = root.backdrop;
    if (!isObj(backdrop)) return fail("backdrop must be an object.");
    only(backdrop, ["light", "dark"], "backdrop");
    backdropLight = str(backdrop, "light");
    if (backdropLight !== null && !isBackdrop(backdropLight)) {
      fail(
        `backdrop.light: only linear-/radial-gradients of colours (no url, at most ${MAX_LAYERS} layers).`,
      );
    }
    backdropDark = str(backdrop, "dark");
    if (backdropDark !== null && !isBackdrop(backdropDark)) {
      fail(
        `backdrop.dark: only linear-/radial-gradients of colours (no url, at most ${MAX_LAYERS} layers).`,
      );
    }
  }
  const radius = str(root, "radius") ?? "default";
  if (!RADII.includes(radius)) fail("radius: sharp, default, soft or round.");
  const fonts: Record<string, string> = {};
  if ("fonts" in root) {
    const f = root.fonts;
    if (!isObj(f)) return fail("fonts must be an object.");
    only(f, ["body", "heading"], "fonts");
    for (const [role, v] of Object.entries(f)) {
      if (typeof v !== "string") fail(`fonts.${role} must be a path.`);
      const path = v as string;
      if (!FONT_PATH.test(path)) fail(`fonts.${role}: a fonts/<name>.woff2 file.`);
      const bytes = files.get(path);
      if (!bytes) fail(`fonts.${role}: ${path} is not in the theme.`);
      if (!isWoff2(bytes!)) fail(`fonts.${role}: ${path} is not a woff2 font of at most 150 KB.`);
      fonts[role] = path;
    }
  }
  const art = str(root, "art");
  if (art !== null) {
    if (art !== "art.svg") fail("art: the only art file is art.svg.");
    const bytes = files.get(art);
    if (!bytes) fail("art: art.svg is not in the theme.");
    if (!safeSvg(bytes!)) {
      fail("art.svg was refused: at most 32 KB, no scripts, links, images or event handlers.");
    }
  }
  const preview = root.preview;
  if (!isObj(preview)) return fail("preview is required.");
  only(preview, ["light", "dark"], "preview");
  for (const [schemeName, tokensOf] of [["light", light], ["dark", dark]] as const) {
    for (const [fg, bg] of CONTRAST_PAIRS) {
      const a = tokensOf[fg];
      const b = tokensOf[bg];
      if (a === undefined || b === undefined) continue;
      const c = contrast(a, b);
      if (c === null) fail(`${schemeName}: ${fg} and ${bg} must be readable colours.`);
      if (c! < MIN_CONTRAST) {
        fail(
          `${schemeName}: ${fg} on ${bg} is ${
            c!.toFixed(2)
          }:1; it must be at least ${MIN_CONTRAST}:1.`,
        );
      }
    }
  }
  return {
    id: id!,
    name: name!,
    summary: summary!,
    authorName: authorName!,
    authorUrl,
    light,
    dark,
    backdropLight,
    backdropDark,
    radius,
    preview: { light: tones(preview.light, "light"), dark: tones(preview.dark, "dark") },
    fonts,
    art,
  };
}
