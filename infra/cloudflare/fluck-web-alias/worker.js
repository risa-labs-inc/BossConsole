// Existing Fluck alias proxy, preserved from its deployed version.
const APP_ASSOCIATION = {"applinks": {"details": [{"appIDs": ["7X4CJM22GN.app.fluck.ios"], "components": [{"/": "/auth", "comment": "Fluck portal sign-in return"}, {"/": "/auth/callback", "comment": "Fluck sign-in callback"}]}]}, "webcredentials": {"apps": ["7X4CJM22GN.app.fluck.ios"]}};
var __defProp = Object.defineProperty;
var __name = (target, value) => __defProp(target, "name", { value, configurable: true });

// worker.js
var ORIGIN = "https://api.risaboss.com";
var BASE = "/functions/v1/fluck-web";
function isOwnCookie(setCookie, host) {
  for (const attr of setCookie.split(";").slice(1)) {
    const eq = attr.indexOf("=");
    if (eq < 0 || attr.slice(0, eq).trim().toLowerCase() !== "domain") continue;
    const domain = attr.slice(eq + 1).trim().replace(/^\./, "").toLowerCase();
    if (domain !== host.toLowerCase()) return false;
  }
  return true;
}
__name(isOwnCookie, "isOwnCookie");
var worker_default = {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (!["fluck.risaboss.com", "fluck.ai", "www.fluck.ai"].includes(url.hostname)) {
      return new Response("Unknown host", {status: 421});
    }
    if (url.hostname === "www.fluck.ai" || url.protocol !== "https:") {
      if (url.hostname === "www.fluck.ai") url.hostname = "fluck.ai";
      url.protocol = "https:";
      return Response.redirect(url.toString(), 308);
    }
    if (
        ["/.well-known/apple-app-site-association", "/apple-app-site-association"].includes(url.pathname)) {
      if (!["GET", "HEAD"].includes(request.method)) {
        return new Response(null, {status: 405, headers: {Allow: "GET, HEAD"}});
      }
      return new Response(request.method === "HEAD" ? null : JSON.stringify(APP_ASSOCIATION), {
        headers: {"Content-Type": "application/json", "Cache-Control": "public, max-age=300", "X-Content-Type-Options": "nosniff"}
      });
    }
    const base = url.hostname === "fluck.ai" ? "/functions/v1/fluck-ai" : BASE;
    const target = ORIGIN + base + (url.pathname === "/" ? "" : url.pathname) + url.search;
    const headers = new Headers(request.headers);
    headers.delete("Host");
    headers.delete("X-Fluck-Web-Alias-Secret");
    headers.delete("X-Fluck-Web-Client-Ip");
    headers.set("X-Forwarded-Proto", "https");
    headers.set("X-Forwarded-Host", url.host);
    headers.set("X-Fluck-Web-Alias", url.host);
    const secret = env?.FLUCK_WEB_ALIAS_SECRET;
    if (secret) headers.set("X-Fluck-Web-Alias-Secret", secret);
    const ip = request.headers.get("CF-Connecting-IP");
    if (ip) headers.set("X-Fluck-Web-Client-Ip", ip);
    const init = { method: request.method, headers, redirect: "manual" };
    if (request.method !== "GET" && request.method !== "HEAD") {
      init.body = request.body;
      init.duplex = "half";
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
  }
};
export {
  worker_default as default,
  isOwnCookie
};
