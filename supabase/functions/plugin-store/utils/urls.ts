/**
 * URLs the store is willing to hold and hand to clients.
 *
 * `homepageUrl` and `iconUrl` are set by whoever publishes a plugin and are served to every client, so
 * what a client does with them is the store's problem too: a web store renders the homepage as a
 * link, a desktop client opens it, an icon is fetched. `z.string().url()` is `new URL()` and accepts
 * `javascript:`, `data:`, `file:` and `smb:`, none of which is a place a plugin lives.
 *
 * Allowed: `http:` and `https:`, with a host, no embedded credentials (a `user:pass@` prefix is how
 * `https://trusted.example@evil.example/` gets read as the wrong host), and a length a browser would
 * accept. Everything else is refused, not rewritten.
 */

const MAX_URL_LENGTH = 2048

/** Whether [value] is an http(s) URL with a host, no credentials and a sane length. */
export function isHttpUrl(value: unknown): value is string {
  if (typeof value !== "string" || value.length === 0 || value.length > MAX_URL_LENGTH) return false
  let parsed: URL
  try {
    parsed = new URL(value)
  } catch {
    return false
  }
  return (parsed.protocol === "http:" || parsed.protocol === "https:") &&
    parsed.hostname !== "" &&
    parsed.username === "" &&
    parsed.password === ""
}

/** [value] when it is an http(s) URL, otherwise null. */
export function httpUrlOrNull(value: unknown): string | null {
  return isHttpUrl(value) ? value : null
}

/** [value] when it is an http(s) URL, otherwise [fallback]. */
export function httpUrlOr(value: unknown, fallback: string): string {
  return isHttpUrl(value) ? value : fallback
}
