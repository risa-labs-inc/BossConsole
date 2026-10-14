/**
 * cli.risaboss.com/<path>  ->  https://api.risaboss.com/functions/v1/live-sessions/<path>
 *
 * A transparent same-host proxy. The function must be told it is served here
 * (LIVE_SESSIONS_PUBLIC_BASE_URL=https://cli.risaboss.com, LIVE_SESSIONS_PUBLIC_BASE_PATH=/),
 * so the cookies it sets carry Path=/ and the magic-link redirect_to is https://cli.risaboss.com/auth.
 *
 * What is forwarded untouched, and why it matters:
 *   - Cookie / Set-Cookie: the HttpOnly session cookies. No Domain attribute is set by the
 *     function, so the browser scopes them to cli.risaboss.com, which is what we want.
 *   - Sec-Fetch-Site: the function's cross-site refusal reads it.
 *   - Authorization, Content-Type, Accept: the page's API calls.
 * X-Forwarded-Proto is set to https so the function issues __Secure- cookies.
 */
const ORIGIN = "https://api.risaboss.com";
const BASE = "/functions/v1/live-sessions";

export default {
  async fetch(request) {
    const url = new URL(request.url);
    const target = ORIGIN + BASE + (url.pathname === "/" ? "" : url.pathname) + url.search;
    const headers = new Headers(request.headers);
    headers.set("X-Forwarded-Proto", "https");
    headers.set("X-Forwarded-Host", url.host);
    // Tells the function this request arrived through the alias, so it serves the page rather
    // than redirecting to the vanity host (gateways rewrite X-Forwarded-Host, so it is not a
    // reliable signal on its own).
    headers.set("X-Live-Sessions-Alias", url.host);
    // The visitor's address, for the function's per-client rate limits: Cloudflare sets this on
    // our inbound request but not on the subrequest.
    const ip = request.headers.get("CF-Connecting-IP");
    if (ip) headers.set("X-Live-Sessions-Client-Ip", ip);
    const init = { method: request.method, headers, redirect: "manual" };
    if (request.method !== "GET" && request.method !== "HEAD") init.body = request.body;
    let upstream;
    try {
      upstream = await fetch(target, init);
    } catch (e) {
      return new Response(JSON.stringify({ error: "upstream" }), { status: 502, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } });
    }
    // Copy the response as-is; Set-Cookie can appear several times, and Headers preserves that.
    const out = new Headers(upstream.headers);
    return new Response(upstream.body, { status: upstream.status, statusText: upstream.statusText, headers: out });
  },
};
