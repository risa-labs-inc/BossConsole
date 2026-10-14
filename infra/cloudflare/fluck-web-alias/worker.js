/**
 * fluck.risaboss.com/<path>  ->  https://api.risaboss.com/functions/v1/fluck-web/<path>
 *
 * A transparent same-host proxy. The function must be told it is served here
 * (FLUCK_WEB_PUBLIC_BASE_URL=https://fluck.risaboss.com, FLUCK_WEB_PUBLIC_BASE_PATH=/),
 * so the cookies it sets carry Path=/ and the magic-link redirect_to is https://fluck.risaboss.com/auth.
 *
 * What is forwarded untouched, and why it matters:
 *   - Cookie: the HttpOnly session cookies.
 *   - Sec-Fetch-Site and Origin: the function's cross-site refusal and the exact-Origin CSRF check read them.
 *   - Content-Type, Accept, X-Fluck-Web-CSRF: the page's API calls.
 * What is rewritten:
 *   - Host is dropped so the subrequest carries api.risaboss.com.
 *   - X-Fluck-Web-Alias-Secret and X-Fluck-Web-Client-Ip are stripped from the visitor and set
 *     here only: the secret (Worker secret FLUCK_WEB_ALIAS_SECRET, same value as the function's)
 *     is what makes the function trust the client IP and serve the page.
 *   - X-Forwarded-Proto is https so the function issues __Secure- cookies.
 *   - Set-Cookie: only the function's own (no Domain, or this host). Cloudflare's __cf_bm for
 *     api.risaboss.com is dropped; browsers would reject it here anyway.
 */
const ORIGIN = "https://api.risaboss.com";
const BASE = "/functions/v1/fluck-web";

/** True for a Set-Cookie line this host can own: no Domain attribute, or Domain = this host. */
export function isOwnCookie(setCookie, host) {
  for (const attr of setCookie.split(";").slice(1)) {
    const eq = attr.indexOf("=");
    if (eq < 0 || attr.slice(0, eq).trim().toLowerCase() !== "domain") continue;
    const domain = attr.slice(eq + 1).trim().replace(/^\./, "").toLowerCase();
    if (domain !== host.toLowerCase()) return false;
  }
  return true;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const target = ORIGIN + BASE + (url.pathname === "/" ? "" : url.pathname) + url.search;
    const headers = new Headers(request.headers);
    headers.delete("Host");
    headers.delete("X-Fluck-Web-Alias-Secret");
    headers.delete("X-Fluck-Web-Client-Ip");
    headers.set("X-Forwarded-Proto", "https");
    headers.set("X-Forwarded-Host", url.host);
    // Claims the alias; the function only believes it alongside the secret, and refuses (rather
    // than redirecting back here) when the secret is missing or wrong.
    headers.set("X-Fluck-Web-Alias", url.host);
    const secret = env?.FLUCK_WEB_ALIAS_SECRET;
    if (secret) headers.set("X-Fluck-Web-Alias-Secret", secret);
    // Cloudflare sets CF-Connecting-IP on our inbound request but not on the subrequest.
    const ip = request.headers.get("CF-Connecting-IP");
    if (ip) headers.set("X-Fluck-Web-Client-Ip", ip);
    const init = { method: request.method, headers, redirect: "manual" };
    if (request.method !== "GET" && request.method !== "HEAD") {
      init.body = request.body;
      init.duplex = "half"; // required by the Fetch standard for a streamed body
    }
    let upstream;
    try {
      upstream = await fetch(target, init);
    } catch (e) {
      console.error("fluck-web upstream fetch failed", e);
      return new Response(JSON.stringify({ error: "upstream" }), { status: 502, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } });
    }
    const out = new Headers(upstream.headers);
    out.delete("Set-Cookie");
    for (const c of upstream.headers.getSetCookie()) if (isOwnCookie(c, url.hostname)) out.append("Set-Cookie", c);
    return new Response(upstream.body, { status: upstream.status, statusText: upstream.statusText, headers: out });
  },
};
