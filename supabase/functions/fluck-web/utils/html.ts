/**
 * Output-escaping primitives (copied from organisation/utils/html.ts; there is
 * no _shared/ directory, each function is self-contained by convention).
 */

/** HTML text/attribute escaping, applied to EVERY interpolation. */
export function esc(value: unknown): string {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;")
}

/** JSON safe to embed inside a <script> block (`<`, U+2028, U+2029 escaped). */
export function jsonForScript(value: unknown): string {
  return JSON.stringify(value ?? null)
    .replace(/</g, "\\u003c")
    .replace(/\u2028/g, "\\u2028")
    .replace(/\u2029/g, "\\u2029")
}

/** A fresh CSP nonce. 128 bits, base64url, one per response. */
export function cspNonce(): string {
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  return btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "")
}
