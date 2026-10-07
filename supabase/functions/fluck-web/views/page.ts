/**
 * The single page this function serves (structure copied from live-sessions/views/page.ts).
 *
 * States driven by the inline, nonce-stamped script: sign-in, "check your email", loading,
 * "opening" (exactly one Fluck online, or ?instance=<id> named one), and the list. No external
 * asset and no third-party script, so the CSP stays `default-src 'none'`.
 *
 * Opening a Fluck embeds the URL /api/open returns (`<endpoint>/#/t/<ticket>`) in a full-viewport
 * iframe, as live-sessions does with its viewer, so the address bar stays on fluck.risaboss.com.
 * Framing needs no cookie: the Fluck redeems the ticket and keeps its session token in the
 * frame's own sessionStorage, and it allows framing only by https://fluck.risaboss.com. Opening
 * pushes a history entry, so "back" closes the frame and shows the list; the ticket is single-use
 * and never enters the address bar, so a reload shows the list too. The frame talks back with
 * postMessage (onFrameMessage): hello, signed out, switch Fluck, and its title.
 *
 * Fallback for Flucks older than framing: a framing-capable Fluck posts `fluck-hello` as soon as
 * its script starts, before redeeming the ticket. If none arrives within HELLO_TIMEOUT_MS the
 * Fluck is assumed to refuse framing (frame-ancestors 'none'), so it never ran and the ticket is
 * still unredeemed: the frame closes and the page navigates top-level to the same URL, first
 * replacing its own history entry with `?list=1` so "back" shows the list instead of reopening.
 *
 * `?instance=<id>` (the Fluck's own "Sign in with BOSS" button) survives sign-in in
 * localStorage for 15 minutes: the magic link opens in a new tab and the OAuth hop leaves
 * the page. Storage may be unavailable; the list is the fallback.
 *
 * Token handling is live-sessions': the fragment is posted once to /api/session, becomes
 * HttpOnly cookies, and leaves the address bar. The page never holds a token.
 */

import { esc, jsonForScript } from "../utils/html.ts"

export interface PageModel {
  basePath: string
  liveWindowSeconds: number
}

/** The Fluck mark (chevron and caret), from fluck-agent-webchat webchat-brand/logo.svg. */
export const FLUCK_MARK =
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" role="img" aria-label="Fluck"><rect width="32" height="32" rx="8" fill="#0f5bff"/><g transform="translate(4 4)" fill="#fff"><path d="M3.1 4.9 11.2 12 3.1 19.1V15.6L6.79 12 3.1 8.4Z"/><path d="M17.25 4.6h3.7l-1.5 14.8h-3.7Z"/></g></svg>`

const FAVICON = `data:image/svg+xml,${encodeURIComponent(FLUCK_MARK)}`

const STYLES = `
  :root {
    --ink: #05070B; --raised: #0E141E; --line: #1C2432; --line-strong: #5A6474;
    --text: #E6EBF2; --text-2: #9AA6B8; --signal: #0F5BFF; --signal-text: #88A9FF;
    --ok: #3DDC97; --danger: #FF5C5C; --wash: rgba(15, 91, 255, 0.12);
  }
  @media (prefers-color-scheme: light) {
    :root {
      --ink: #F4F6FA; --raised: #FFFFFF; --line: #DCE2EB; --line-strong: #868E9B;
      --text: #0B1220; --text-2: #4B5565; --signal: #0F5BFF; --signal-text: #0B45C2;
      --ok: #0F8A5F; --wash: rgba(15, 91, 255, 0.08);
    }
  }
  * { box-sizing: border-box; }
  html, body { margin: 0; padding: 0; background-color: var(--ink); color: var(--text);
    font: 15px/1.5 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif; }
  main { max-width: 560px; margin: 0 auto; padding: 32px 16px 48px; }
  header { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 20px; }
  .brand { display: flex; align-items: center; gap: 12px; }
  .brand svg { width: 32px; height: 32px; flex: none; }
  h1 { font-size: 20px; margin: 0; letter-spacing: -0.2px; }
  .sub { color: var(--text-2); font-size: 13px; }
  .card { background-color: var(--raised); border: 1px solid var(--line); border-radius: 10px; padding: 20px; }
  label { display: block; font-size: 13px; color: var(--text-2); margin-bottom: 6px; }
  input[type=email] { width: 100%; font: inherit; color: var(--text); background-color: var(--ink);
    border: 1px solid var(--line-strong); border-radius: 7px; padding: 10px 12px; }
  input[type=email]:focus-visible, button:focus-visible, a.btn:focus-visible { outline: 2px solid var(--signal); outline-offset: 2px; }
  button, a.btn { font: inherit; font-weight: 600; border-radius: 7px; padding: 10px 16px; cursor: pointer;
    border: 1px solid var(--signal); background-color: var(--signal); color: #FFFFFF; text-decoration: none; display: inline-block; }
  button.secondary, a.btn.secondary { background-color: transparent; color: var(--text-2); border-color: var(--line-strong); }
  button:disabled { opacity: 0.5; cursor: default; }
  .row { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-top: 12px; }
  .hidden { display: none !important; }
  .notice { border-left: 3px solid var(--signal); padding: 8px 12px; color: var(--text-2); margin-bottom: 14px; background-color: var(--wash); border-radius: 0 7px 7px 0; }
  .notice.error { border-left-color: var(--danger); }
  ul.instances { list-style: none; margin: 0; padding: 0; }
  ul.instances li { display: flex; justify-content: space-between; align-items: center; gap: 12px;
    padding: 14px 0; border-top: 1px solid var(--line); }
  ul.instances li:first-child { border-top: 0; padding-top: 0; }
  ul.instances li > div { min-width: 0; }
  .name { font-weight: 600; overflow-wrap: anywhere; }
  .meta { color: var(--text-2); font-size: 13px; overflow-wrap: anywhere; }
  .dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 6px; vertical-align: 1px; background-color: var(--line-strong); }
  .dot.on { background-color: var(--ok); }
  footer { margin-top: 28px; color: var(--text-2); font-size: 12px; text-align: center; }
  a { color: var(--signal-text); }
  /* Embedded Fluck: the page becomes a thin bar over a full-height frame (as live-sessions). */
  body.viewing { overflow: hidden; }
  body.viewing main { max-width: none; padding: 0; height: 100vh; display: flex; flex-direction: column; overflow: hidden; }
  body.viewing header, body.viewing #notice, body.viewing .card, body.viewing footer { display: none; }
  #viewer { display: none; flex: 1; flex-direction: column; min-height: 0; }
  body.viewing #viewer { display: flex; }
  #viewerbar { display: flex; align-items: center; gap: 10px; padding: 6px 12px; background-color: var(--raised); border-bottom: 1px solid var(--line); font-size: 13px; }
  #viewerbar .name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  #viewerbar button { padding: 5px 10px; font-size: 12px; }
  #fluckframe { flex: 1; width: 100%; border: 0; background-color: var(--ink); }
  .providers { display: grid; gap: 10px; }
  a.btn.provider { display: flex; align-items: center; justify-content: center; gap: 10px;
    background-color: transparent; color: var(--text); border-color: var(--line-strong); }
  a.btn.provider.apple { background-color: var(--text); color: var(--ink); border-color: var(--text); }
  a.btn.provider svg { width: 18px; height: 18px; flex: none; }
  .or { display: flex; align-items: center; gap: 10px; color: var(--text-2); font-size: 13px; margin: 16px 0; }
  .or::before, .or::after { content: ""; flex: 1; border-top: 1px solid var(--line); }
`

/** Browser script. Plain ES2017, no build step; config comes from the #cfg JSON block. */
const SCRIPT = `
(function () {
  "use strict";
  var cfg = JSON.parse(document.getElementById("cfg").textContent);
  var base = cfg.basePath;
  var $ = function (id) { return document.getElementById(id); };
  var INSTANCE_RE = /^[A-Za-z0-9._:-]{1,128}$/;
  var OPEN_URL_RE = /^https:\\/\\/[A-Za-z0-9.-]+(:[0-9]{1,5})?\\/#\\/t\\/[A-Za-z0-9_-]{43}$/;
  var WANT_KEY = "fluck-web.instance", WANT_TTL_MS = 15 * 60 * 1000;
  var TITLE_MAX = 120, PAGE_TITLE = "Fluck", HELLO_TIMEOUT_MS = 8000;
  var pollTimer = null, openTimer = null, csrf = "", opening = false, requestGeneration = 0;
  var viewing = null; // { url, label } while a Fluck is framed
  var helloTimer = null; // pending top-level fallback until the frame says fluck-hello
  var params = new URLSearchParams(location.search);
  // ?list=1 (older Fluck builds link back with it), or a reload of the entry a framed Fluck
  // pushed: show the list, never auto-open on this load.
  var autoOpenDone = params.get("list") === "1";
  if (history.state && history.state.view === "fluck") {
    autoOpenDone = true;
    history.replaceState(null, "", location.pathname + location.search);
  }
  var wanted = null;

  function show(id) {
    ["signin", "sent", "loading", "list", "opening"].forEach(function (s) {
      $(s).classList.toggle("hidden", s !== id);
    });
  }
  function notice(text, kind) {
    var n = $("notice");
    n.textContent = text || "";
    n.className = "notice" + (kind ? " " + kind : "");
    n.classList.toggle("hidden", !text);
  }
  function store(fn) { try { return fn(window.localStorage); } catch (_) { return null; } }

  // ?instance=<id>: remember it across sign-in, then take it out of the address bar.
  (function readWanted() {
    var id = params.get("instance");
    if (id && INSTANCE_RE.test(id)) {
      wanted = id;
      autoOpenDone = false;
      store(function (s) { s.setItem(WANT_KEY, JSON.stringify({ id: id, at: Date.now() })); });
    } else {
      var saved = store(function (s) { return JSON.parse(s.getItem(WANT_KEY) || "null"); });
      if (saved && typeof saved.id === "string" && INSTANCE_RE.test(saved.id) && Date.now() - saved.at < WANT_TTL_MS) wanted = saved.id;
    }
    if (params.has("instance") || params.has("list")) {
      params.delete("instance"); params.delete("list");
      var rest = params.toString();
      history.replaceState(null, "", location.pathname + (rest ? "?" + rest : "") + location.hash);
    }
  })();
  function forgetWanted() { wanted = null; store(function (s) { s.removeItem(WANT_KEY); }); }

  var OAUTH_ERRORS = {
    cancelled: "Sign-in was cancelled.",
    expired: "That sign-in took too long or was opened in another browser. Please try again.",
    failed: "Sign-in failed. Please try again.",
    rate_limited: "Too many attempts. Try again in a few minutes."
  };
  function oauthErrorNotice() {
    var p = new URLSearchParams(location.search);
    var code = p.get("oauth_error");
    if (!code) return;
    p.delete("oauth_error");
    var rest = p.toString();
    history.replaceState(null, "", location.pathname + (rest ? "?" + rest : ""));
    notice(Object.prototype.hasOwnProperty.call(OAUTH_ERRORS, code) ? OAUTH_ERRORS[code] : OAUTH_ERRORS.failed, "error");
  }
  async function harvestFragment() {
    var h = (location.hash || "").replace(/^#/, "");
    if (!h) return false;
    var p = new URLSearchParams(h);
    history.replaceState(null, "", location.pathname + location.search);
    if (p.get("access_token")) {
      try {
        var r = await api("/api/session", { method: "POST", body: { access_token: p.get("access_token"), refresh_token: p.get("refresh_token") || "" } });
        if (r.ok) return true;
        notice("That sign-in link could not be verified. Request a new one.", "error");
      } catch (_) { notice("Network error while signing in.", "error"); }
    } else if (p.get("error_description") || p.get("error")) {
      notice(p.get("error_description") || p.get("error"), "error");
    }
    return false;
  }

  function ago(iso) {
    var s = Math.max(0, Math.round((Date.now() - Date.parse(iso)) / 1000));
    if (s < 60) return s + " s ago";
    var m = Math.round(s / 60); if (m < 60) return m + " min ago";
    var h = Math.round(m / 60); if (h < 48) return h + " h ago";
    return Math.round(h / 24) + " d ago";
  }
  function title(i) { return i.agent_name + " on " + i.label; }

  async function api(path, opts) {
    opts = opts || {};
    var headers = Object.assign({ "Accept": "application/json" }, opts.headers || {});
    if (opts.body) headers["Content-Type"] = "application/json";
    return fetch(base + path, { method: opts.method || "GET", headers: headers,
      body: opts.body ? JSON.stringify(opts.body) : undefined, credentials: "same-origin" });
  }

  function cancelOpenTimer() { if (openTimer) { clearTimeout(openTimer); openTimer = null; } }

  async function signOut() {
    stopPolling(); cancelOpenTimer(); requestGeneration++;
    if (viewing) closeFrame(false);
    try { await api("/api/logout", { method: "POST", body: {} }); } catch (_) {}
    notice(""); show("signin");
  }

  async function loadInstances(isPoll) {
    var gen = ++requestGeneration;
    if (!isPoll) show("loading");
    var r = await api("/api/instances");
    if (gen !== requestGeneration) return;
    if (r.status === 401) { stopPolling(); show("signin"); return; }
    if (!r.ok) { notice("Could not load your Flucks. Retrying.", "error"); show("list"); startPolling(); return; }
    var data = await r.json();
    if (gen !== requestGeneration) return;
    csrf = data.csrf || "";
    $("who").textContent = data.email || "";
    render(data.instances || []);
  }

  function render(instances) {
    if (viewing || opening || openTimer) return; // closeFrame reloads the list
    if (!autoOpenDone) {
      autoOpenDone = true;
      var online = instances.filter(function (i) { return i.online; });
      if (wanted) {
        var hit = instances.filter(function (i) { return i.instance_id === wanted; })[0];
        forgetWanted();
        if (hit && hit.online) { openInstance(hit); return; }
        if (hit) notice(title(hit) + " is offline. It will appear as online when its BOSS is running.", null);
        else notice("That Fluck is not signed in with this account.", "error");
      } else if (online.length === 1) {
        var only = online[0];
        $("opening-name").textContent = title(only);
        show("opening");
        $("opening-now").onclick = function () { cancelOpenTimer(); openInstance(only); };
        openTimer = setTimeout(function () { openTimer = null; openInstance(only); }, 1500);
        return;
      }
    }
    var ul = $("instances");
    ul.innerHTML = "";
    $("empty").classList.toggle("hidden", instances.length > 0);
    instances.forEach(function (i) {
      var li = document.createElement("li");
      var left = document.createElement("div");
      var name = document.createElement("div"); name.className = "name"; name.textContent = i.agent_name;
      var meta = document.createElement("div"); meta.className = "meta";
      var dot = document.createElement("span"); dot.className = "dot" + (i.online ? " on" : "");
      meta.appendChild(dot);
      meta.appendChild(document.createTextNode(i.label + " · " + (i.online ? "online" : "last seen " + ago(i.last_seen_at))));
      left.appendChild(name); left.appendChild(meta);
      var btn = document.createElement("button");
      btn.type = "button"; btn.textContent = "Open"; btn.disabled = !i.online;
      if (!i.online) btn.className = "secondary";
      btn.addEventListener("click", function () { openInstance(i); });
      li.appendChild(left); li.appendChild(btn);
      ul.appendChild(li);
    });
    show("list");
    startPolling();
  }

  async function openInstance(i) {
    if (opening) return;
    opening = true; cancelOpenTimer(); stopPolling();
    $("opening-name").textContent = title(i);
    show("opening");
    try {
      var r = await api("/api/open", { method: "POST", headers: { "X-Fluck-Web-CSRF": csrf }, body: { instance_id: i.instance_id } });
      if (r.status === 401) { opening = false; show("signin"); return; }
      var data = await r.json().catch(function () { return {}; });
      if (r.ok && typeof data.url === "string" && OPEN_URL_RE.test(data.url)) {
        opening = false;
        openFrame(data.url, title(i));
        return;
      }
      opening = false;
      if (r.status === 409) notice(title(i) + " just went offline. Try again when it is back.", "error");
      else if (r.status === 429) notice("Too many attempts. Wait a minute and try again.", "error");
      else notice("Could not open " + title(i) + ". Try again.", "error");
      await loadInstances(false);
    } catch (_) {
      opening = false;
      notice("Network error while opening " + title(i) + ".", "error");
      loadInstances(false).catch(function () {});
    }
  }

  // The Fluck is embedded in an iframe rather than navigated to, so the address bar stays on
  // this page and "back" is instant. The Fluck allows framing only by this origin; the frame
  // tells us when it signs out, when the user wants another Fluck, and its title.
  // The on-screen keyboard is only visible to the TOP document: on iOS the layout viewport never
  // shrinks, only window.visualViewport does, and a cross-origin iframe sees neither. While a
  // Fluck is framed the page sizes <main> to the visual viewport and follows its offset, so the
  // chat's composer sits just above the keyboard. Android already shrinks the window (no-op).
  function fitViewport() {
    var m = document.querySelector("main");
    if (!viewing) { m.style.height = ""; m.style.transform = ""; return; }
    var vv = window.visualViewport;
    if (!vv) { m.style.height = window.innerHeight + "px"; m.style.transform = ""; return; }
    m.style.height = Math.round(vv.height) + "px";
    m.style.transform = vv.offsetTop ? "translateY(" + Math.round(vv.offsetTop) + "px)" : "";
  }
  if (window.visualViewport) {
    window.visualViewport.addEventListener("resize", fitViewport);
    window.visualViewport.addEventListener("scroll", fitViewport);
  }
  window.addEventListener("resize", fitViewport);

  function openFrame(url, label) {
    if (viewing) return;
    requestGeneration++;
    viewing = { url: url, label: label };
    stopPolling(); cancelOpenTimer();
    notice("");
    $("viewer-name").textContent = label;
    $("fluckframe").setAttribute("src", url);
    document.body.classList.add("viewing");
    fitViewport();
    // Same URL, no ticket: a reload of this entry shows the list (see the history.state check).
    try { history.pushState({ view: "fluck" }, "", location.pathname + location.search); } catch (_) {}
    helloTimer = setTimeout(function () { helloTimer = null; navigateTopLevel(url); }, HELLO_TIMEOUT_MS);
  }
  function cancelHelloTimer() { if (helloTimer) { clearTimeout(helloTimer); helloTimer = null; } }
  // An older Fluck refused the frame and never redeemed the ticket: open it the pre-iframe way.
  function navigateTopLevel(url) {
    closeFrame(false);
    // Back from the Fluck lands on the list, not on another auto-open.
    history.replaceState(null, "", location.pathname + "?list=1");
    location.assign(url);
  }
  // reload: true refetches the list behind a loading state, "quiet" refetches it in place,
  // false leaves it to the caller.
  function closeFrame(reload) {
    if (!viewing) return;
    cancelHelloTimer();
    requestGeneration++;
    viewing = null;
    $("fluckframe").setAttribute("src", "about:blank");
    document.body.classList.remove("viewing");
    document.title = PAGE_TITLE;
    fitViewport();
    autoOpenDone = true; // do not bounce straight back into a Fluck that was just closed
    if (reload === "quiet") { show("list"); loadInstances(true).catch(function () {}); }
    else if (reload !== false) loadInstances(false).catch(function () {});
  }
  function frameTitle(t) {
    var clean = String(t).replace(/[\\u0000-\\u001f\\u007f]/g, " ").replace(/\\s+/g, " ").trim().slice(0, TITLE_MAX);
    return clean || PAGE_TITLE;
  }
  function onFrameMessage(ev) {
    var frame = $("fluckframe");
    if (!viewing || !frame.contentWindow || ev.source !== frame.contentWindow || ev.origin !== new URL(viewing.url).origin) return;
    var d = ev.data;
    if (!d || typeof d !== "object") return;
    if (d.type === "fluck-hello") cancelHelloTimer();
    else if (d.type === "fluck-signed-out") closeFrame(true);
    else if (d.type === "fluck-switch") closeFrame("quiet");
    else if (d.type === "fluck-title" && typeof d.title === "string") document.title = frameTitle(d.title);
  }
  window.addEventListener("message", onFrameMessage);
  window.addEventListener("popstate", function () { if (viewing) closeFrame(true); });

  function startPolling() {
    stopPolling();
    pollTimer = setInterval(function () {
      if (document.visibilityState === "visible") loadInstances(true).catch(function () {});
    }, 10000);
  }
  function stopPolling() { if (pollTimer) { clearInterval(pollTimer); pollTimer = null; } }

  $("signin-form").addEventListener("submit", async function (ev) {
    ev.preventDefault();
    var email = $("email").value.trim();
    if (!email) return;
    $("send").disabled = true;
    try {
      var r = await api("/api/otp", { method: "POST", body: { email: email } });
      if (r.status === 429) { notice("Too many attempts. Try again in a few minutes.", "error"); return; }
      if (!r.ok) { notice("Could not send the link. Try again.", "error"); return; }
      $("sent-email").textContent = email;
      notice("");
      show("sent");
    } catch (_) { notice("Network error.", "error"); } finally { $("send").disabled = false; }
  });
  $("sent-back").addEventListener("click", function () { show("signin"); });
  $("refresh").addEventListener("click", function () { loadInstances(false).catch(function () {}); });
  $("signout").addEventListener("click", function () { signOut(); });
  $("opening-cancel").addEventListener("click", function () {
    cancelOpenTimer();
    loadInstances(false).catch(function () {});
  });
  $("viewer-back").addEventListener("click", function () { closeFrame(true); });
  // Restored from bfcache with no Fluck framed: refresh the list, do not reopen.
  window.addEventListener("pageshow", function (ev) {
    if (ev.persisted && !viewing) { opening = false; autoOpenDone = true; loadInstances(false).catch(function () {}); }
  });
  document.addEventListener("visibilitychange", function () {
    if (document.visibilityState === "visible" && !$("list").classList.contains("hidden")) loadInstances(true).catch(function () {});
  });

  // Boot
  oauthErrorNotice();
  harvestFragment().then(function () {
    return loadInstances(false);
  }).catch(function () { notice("Network error.", "error"); show("signin"); });
})();
`

const GOOGLE_MARK =
  `<svg viewBox="0 0 18 18" aria-hidden="true">` +
  `<path fill="#4285F4" d="M17.64 9.2c0-.64-.06-1.25-.16-1.84H9v3.48h4.84a4.14 4.14 0 0 1-1.8 2.72v2.26h2.92c1.7-1.57 2.68-3.87 2.68-6.62z"/>` +
  `<path fill="#34A853" d="M9 18c2.43 0 4.47-.8 5.96-2.18l-2.92-2.26c-.8.54-1.83.86-3.04.86-2.34 0-4.33-1.58-5.04-3.71H.96v2.33A9 9 0 0 0 9 18z"/>` +
  `<path fill="#FBBC05" d="M3.96 10.71A5.41 5.41 0 0 1 3.68 9c0-.59.1-1.17.28-1.71V4.96H.96A9 9 0 0 0 0 9c0 1.45.35 2.83.96 4.04l3-2.33z"/>` +
  `<path fill="#EA4335" d="M9 3.58c1.32 0 2.5.45 3.44 1.35l2.58-2.59A9 9 0 0 0 .96 4.96l3 2.33C4.67 5.16 6.66 3.58 9 3.58z"/>` +
  `</svg>`

const APPLE_MARK =
  `<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M16.37 1.43c0 1.14-.49 2.27-1.18 3.08-.74.9-1.99 1.57-2.99 1.57-.12 0-.23-.02-.3-.03-.01-.06-.04-.22-.04-.39 0-1.15.57-2.27 1.21-2.98.8-.94 2.14-1.64 3.25-1.68.03.13.05.28.05.43zm4.34 15.59c-.03.07-.46 1.58-1.52 3.12-.95 1.34-1.94 2.71-3.43 2.71-1.52 0-1.9-.88-3.63-.88-1.7 0-2.3.91-3.67.91-1.38 0-2.33-1.26-3.43-2.8C3.74 18.26 2.7 15.45 2.7 12.8c0-4.28 2.8-6.55 5.55-6.55 1.45 0 2.68.95 3.6.95.87 0 2.22-1.01 3.9-1.01.61 0 2.89.06 4.37 2.19-.13.09-2.38 1.37-2.38 4.19 0 3.26 2.85 4.32 2.95 4.38z"/></svg>`

export function fluckPage(model: PageModel, nonce: string): string {
  const cfg = { basePath: model.basePath, liveWindowSeconds: model.liveWindowSeconds }
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<meta name="robots" content="noindex, nofollow">
<title>Fluck</title>
<link rel="icon" type="image/svg+xml" href="${esc(FAVICON)}">
<style nonce="${esc(nonce)}">${STYLES}</style>
</head>
<body>
<!--email_off-->
<main>
  <header>
    <div class="brand">${FLUCK_MARK}<div><h1>Fluck</h1><div class="sub">Your Fluck, from any browser</div></div></div>
    <div class="sub" id="who"></div>
  </header>
  <div id="notice" class="notice hidden"></div>

  <section id="signin" class="card hidden">
    <div class="providers">
      <a id="oauth-google" class="btn provider" href="${esc(model.basePath)}/api/oauth/google">${GOOGLE_MARK}Continue with Google</a>
      <a id="oauth-apple" class="btn provider apple" href="${esc(model.basePath)}/api/oauth/apple">${APPLE_MARK}Continue with Apple</a>
    </div>
    <div class="or">or</div>
    <form id="signin-form" autocomplete="on">
      <label for="email">Email</label>
      <input id="email" name="email" type="email" required autocomplete="email" inputmode="email" placeholder="you@company.com">
      <div class="row">
        <button id="send" type="submit">Email me a sign-in link</button>
        <span class="sub">Use the account your BOSS is signed in with.</span>
      </div>
    </form>
  </section>

  <section id="sent" class="card hidden">
    <div class="name">Check your email</div>
    <p class="sub">We sent a sign-in link to <strong id="sent-email"></strong>. Open it in this browser and your Flucks will appear here.</p>
    <div class="row"><button id="sent-back" class="secondary" type="button">Use a different email</button></div>
  </section>

  <section id="loading" class="card hidden"><div class="sub">Loading your Flucks…</div></section>

  <section id="opening" class="card hidden">
    <div class="name">Opening <span id="opening-name"></span>…</div>
    <p class="sub">You will land signed in on its web chat.</p>
    <div class="row">
      <button id="opening-now" type="button">Open now</button>
      <button id="opening-cancel" class="secondary" type="button">Show the list instead</button>
    </div>
  </section>

  <section id="list" class="card hidden">
    <h2 class="name">Your Flucks</h2>
    <div id="empty" class="hidden">
      <p class="name">No Flucks yet</p>
      <p class="sub">Turn on Web chat in Fluck → Settings, and choose a way to reach it. It appears here within half a minute.</p>
    </div>
    <ul id="instances" class="instances"></ul>
    <div class="row">
      <button id="refresh" class="secondary" type="button">Refresh</button>
      <button id="signout" class="secondary" type="button">Sign out</button>
      <span class="sub">A Fluck shows as offline about ${esc(String(model.liveWindowSeconds))} seconds after its BOSS stops.</span>
    </div>
  </section>

  <div id="viewer">
    <div id="viewerbar">
      <button id="viewer-back" class="secondary" type="button">&#8592; Flucks</button>
      <span class="name" id="viewer-name"></span>
    </div>
    <iframe id="fluckframe" title="Fluck" allow="clipboard-read; clipboard-write; fullscreen" allowfullscreen src="about:blank"></iframe>
  </div>

  <footer>Only you can see this list. Each Fluck admits only the BOSS account it is signed in as.</footer>
</main>
<!--/email_off-->
<script id="cfg" type="application/json">${jsonForScript(cfg)}</script>
<script nonce="${esc(nonce)}">${SCRIPT}</script>
</body>
</html>`
}
