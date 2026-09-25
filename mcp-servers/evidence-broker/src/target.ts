/**
 * Egress target parsing and host allowlisting.
 *
 * The rule this module exists to enforce: a credential is scoped to a set of
 * hosts, and deciding whether a URL is one of them is a *parse*, never a
 * substring test. `"https://api.example.com.evil.test/".includes("api.example.com")`
 * is true, and that is the entire bug class.
 *
 * Everything here is default-deny: an input we cannot confidently parse is
 * refused rather than normalized into something that happens to match.
 */

export type TargetParse =
  | {
      ok: true;
      /** Lowercased, punycoded by URL, trailing root dot stripped. */
      host: string;
      protocol: "http:" | "https:";
      url: URL;
    }
  | { ok: false; reason: string };

/**
 * Parse an egress URL into the pieces the allowlist decision needs.
 *
 * Rejections that are deliberate, not oversights:
 *  - non-http(s) schemes, so `file:`, `data:` and friends cannot carry a secret
 *  - any userinfo at all. `https://api.example.com@evil.test/` has host
 *    `evil.test` while reading to a human as the trusted host. The value is
 *    never worth the ambiguity, so its mere presence is fatal.
 */
export function parseTarget(raw: string): TargetParse {
  if (typeof raw !== "string" || raw.trim() === "") {
    return { ok: false, reason: "empty target" };
  }

  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return { ok: false, reason: "not a parseable absolute URL" };
  }

  if (url.protocol !== "http:" && url.protocol !== "https:") {
    return { ok: false, reason: `scheme ${url.protocol} is not brokerable` };
  }

  if (url.username !== "" || url.password !== "") {
    return { ok: false, reason: "target carries userinfo, which is ambiguous about the real host" };
  }

  const host = normalizeHost(url.hostname);
  if (host === "") {
    return { ok: false, reason: "target has no host" };
  }

  return { ok: true, host, protocol: url.protocol, url };
}

/**
 * Lowercase and drop a single trailing root dot.
 *
 * `example.com.` and `example.com` name the same host, so an exact-match
 * allowlist has to see them as equal or the trailing dot is a trivial bypass.
 * `URL` already lowercases and punycodes, so this is belt-and-braces for
 * callers that pass a bare host.
 */
export function normalizeHost(host: string): string {
  let h = host.trim().toLowerCase();
  if (h.endsWith(".") && h.length > 1) h = h.slice(0, -1);
  return h;
}

/** An IP literal, including the bracketed form `URL.hostname` yields for IPv6. */
export function isIpLiteral(host: string): boolean {
  if (host.startsWith("[") && host.endsWith("]")) return true;
  // Dotted quad, each octet 0-255, no leading zeros (which some parsers read as octal).
  return /^(?:(?:0|[1-9]\d{0,2})\.){3}(?:0|[1-9]\d{0,2})$/.test(host)
    && host.split(".").every((o) => Number(o) <= 255);
}

/**
 * Validate one allowlist entry at config-load time, so a typo fails loudly at
 * startup instead of quietly widening the allowlist at request time.
 */
export function validateAllowlistEntry(entry: string): { ok: true; normalized: string } | { ok: false; reason: string } {
  const e = normalizeHost(entry);
  if (e === "") return { ok: false, reason: "empty host pattern" };

  if (e.startsWith("*.")) {
    const suffix = e.slice(2);
    if (suffix === "") return { ok: false, reason: "wildcard with no suffix" };
    if (suffix.includes("*")) return { ok: false, reason: "only one leading '*.' wildcard is supported" };
    if (isIpLiteral(suffix)) return { ok: false, reason: "wildcards are meaningless against an IP literal" };
    if (!suffix.includes(".")) {
      // `*.com` would hand over an entire TLD.
      return { ok: false, reason: "wildcard suffix must have at least two labels" };
    }
    return { ok: true, normalized: e };
  }

  if (e.includes("*")) return { ok: false, reason: "'*' is only allowed as a leading '*.' label" };
  if (e.includes("/") || e.includes(":")) {
    // A port or path here means the author expected matching we do not do.
    return { ok: false, reason: "host pattern must be a bare host, with no port or path" };
  }
  return { ok: true, normalized: e };
}

/**
 * Does `host` match a single allowlist entry?
 *
 * Exact entries match exactly. A `*.example.com` entry matches any strict
 * subdomain (`a.example.com`, `a.b.example.com`) but deliberately NOT the apex
 * `example.com` -- if you want the apex too, list it. Being explicit here is
 * worth more than the convenience.
 */
export function hostMatchesEntry(host: string, entry: string): boolean {
  const h = normalizeHost(host);
  const e = normalizeHost(entry);
  if (h === "" || e === "") return false;

  if (e.startsWith("*.")) {
    const suffix = e.slice(2);
    if (isIpLiteral(h)) return false;
    // The `.` is load-bearing: it forces the match onto a label boundary, so
    // `notexample.com` and `example.com.evil.test` both fail.
    return h.length > suffix.length + 1 && h.endsWith("." + suffix);
  }

  return h === e;
}

/** Default-deny across the whole allowlist. */
export function hostAllowed(host: string, allowedHosts: readonly string[]): boolean {
  if (allowedHosts.length === 0) return false;
  return allowedHosts.some((entry) => hostMatchesEntry(host, entry));
}
