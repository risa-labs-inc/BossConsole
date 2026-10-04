/**
 * The single page this function serves.
 *
 * One document, three states driven by the inline script: sign-in form (no
 * token), "opening" (exactly one live session), list (several or none). The
 * script is nonce-stamped; there is no external asset and no third-party
 * script, which is what lets the CSP stay `default-src 'none'`.
 *
 * Palette mirrors BOSS Blueprint (organisation/views/layout.ts is the source
 * of the values). Wrapped in Cloudflare's `<!--email_off-->` because
 * api.risaboss.com sits behind Cloudflare whose Email Obfuscation would inject
 * a script the CSP blocks.
 *
 * Google / Apple sign-in never touches page script: the two buttons are plain links to
 * /api/oauth/{google|apple}, and the server does the whole PKCE exchange (app.ts), landing back
 * here signed in or with `?oauth_error=<code>`, which the script turns into a notice.
 *
 * Token handling, in order, and why:
 *   1. GoTrue's implicit-flow redirect lands on `/auth#access_token=…`. The
 *      fragment never reaches this server, so the page reads it itself.
 *   2. It posts the pair ONCE to /api/session, which verifies it with GoTrue and
 *      sets HttpOnly cookies scoped to this function's path (utils/cookies.ts).
 *      Then the fragment is stripped with history.replaceState so the bearer
 *      leaves the address bar and history. The page holds no token afterwards.
 *   3. Every data call is same-origin (`connect-src 'self'`) with
 *      credentials: "same-origin"; the server reads the cookie, rotates it via
 *      the refresh cookie when expired, and forwards the JWT to PostgREST,
 *      which validates it and applies RLS.
 */

import { esc, jsonForScript } from "../utils/html.ts"

export interface PageModel {
  basePath: string
  liveWindowSeconds: number
}

const STYLES = `
  :root {
    --ink: #05070B; --raised: #0E141E; --line: #1C2432; --line-strong: #5A6474;
    --text: #E6EBF2; --text-2: #9AA6B8; --signal: #0F5BFF; --signal-text: #88A9FF;
    --ok: #3DDC97; --warn: #F2A93B; --danger: #FF5C5C; --wash: rgba(15, 91, 255, 0.12);
  }
  @media (prefers-color-scheme: light) {
    :root {
      --ink: #F4F6FA; --raised: #FFFFFF; --line: #DCE2EB; --line-strong: #868E9B;
      --text: #0B1220; --text-2: #4B5565; --signal: #0F5BFF; --signal-text: #0B45C2;
      --wash: rgba(15, 91, 255, 0.08);
    }
  }
  * { box-sizing: border-box; }
  html, body { margin: 0; padding: 0; background-color: var(--ink); color: var(--text);
    font: 15px/1.5 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif; }
  main { max-width: 640px; margin: 0 auto; padding: 32px 16px 48px; }
  header { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin-bottom: 20px; }
  h1 { font-size: 20px; margin: 0; letter-spacing: -0.2px; }
  h2 { font-size: 16px; margin: 0 0 4px; }
  .session-group + .session-group { margin-top: 24px; padding-top: 20px; border-top: 1px solid var(--line); }
  .session-group > .sub { margin-bottom: 16px; }
  .sub { color: var(--text-2); font-size: 13px; }
  .card { background-color: var(--raised); border: 1px solid var(--line); border-radius: 10px; padding: 20px; }
  .card + .card { margin-top: 12px; }
  label { display: block; font-size: 13px; color: var(--text-2); margin-bottom: 6px; }
  input[type=email] { width: 100%; font: inherit; color: var(--text); background-color: var(--ink);
    border: 1px solid var(--line-strong); border-radius: 7px; padding: 10px 12px; }
  input[type=email]:focus-visible { outline: 2px solid var(--signal); outline-offset: 1px; }
  button, a.btn { font: inherit; font-weight: 600; border-radius: 7px; padding: 10px 16px; cursor: pointer;
    border: 1px solid var(--signal); background-color: var(--signal); color: #FFFFFF; text-decoration: none; display: inline-block; }
  button.secondary, a.btn.secondary { background-color: transparent; color: var(--text-2); border-color: var(--line-strong); }
  button:disabled { opacity: 0.6; cursor: default; }
  .row { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-top: 12px; }
  .hidden { display: none !important; }
  .notice { border-left: 3px solid var(--warn); padding: 8px 12px; color: var(--text-2); margin-bottom: 14px; background-color: var(--wash); border-radius: 0 7px 7px 0; }
  .notice.error { border-left-color: var(--danger); }
  .notice.ok { border-left-color: var(--ok); }
  ul.sessions { list-style: none; margin: 0; padding: 0; }
  ul.sessions li { display: flex; justify-content: space-between; align-items: center; gap: 12px;
    padding: 14px 0; border-top: 1px solid var(--line); }
  ul.sessions li:first-child { border-top: 0; padding-top: 0; }
  .name { font-weight: 600; }
  .meta { color: var(--text-2); font-size: 13px; }
  .pill { display: inline-block; font-size: 11px; padding: 1px 8px; border-radius: 999px; border: 1px solid var(--line-strong); color: var(--text-2); margin-left: 6px; vertical-align: middle; }
  .pill.ok { border-color: var(--ok); color: var(--ok); }
  .pill.warn { border-color: var(--warn); color: var(--warn); }
  code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; color: var(--signal-text); }
  footer { margin-top: 28px; color: var(--text-2); font-size: 12px; text-align: center; }
  a { color: var(--signal-text); }
  /* Embedded viewer: the page becomes a thin bar over a full-height frame. */
  body.viewing { overflow: hidden; }
  body.viewing main { max-width: none; padding: 0; height: 100vh; display: flex; flex-direction: column; overflow: hidden; }
  body.viewing header, body.viewing #notice, body.viewing .card, body.viewing footer { display: none; }
  #viewer { display: none; flex: 1; flex-direction: column; min-height: 0; }
  body.viewing #viewer { display: flex; }
  #viewerbar { display: flex; align-items: center; gap: 10px; padding: 6px 12px; background-color: var(--raised); border-bottom: 1px solid var(--line); font-size: 13px; }
  #viewerbar .name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  #viewerbar button, #viewerbar a.btn { padding: 5px 10px; font-size: 12px; }
  #viewerframe { flex: 1; width: 100%; border: 0; background-color: #000; }
  .providers { display: grid; gap: 10px; }
  a.btn.provider { display: flex; align-items: center; justify-content: center; gap: 10px;
    background-color: transparent; color: var(--text); border-color: var(--line-strong); }
  a.btn.provider.apple { background-color: var(--text); color: var(--ink); border-color: var(--text); }
  a.btn.provider svg { width: 18px; height: 18px; flex: none; }
  .or { display: flex; align-items: center; gap: 10px; color: var(--text-2); font-size: 13px; margin: 16px 0; }
  .or::before, .or::after { content: ""; flex: 1; border-top: 1px solid var(--line); }
`

/**
 * Browser script. Plain ES2017, no build step. Reads config from the
 * `#cfg` JSON block so the template never interpolates into JS directly.
 */
const SCRIPT = `
(function () {
  "use strict";
  var cfg = JSON.parse(document.getElementById("cfg").textContent);
  var base = cfg.basePath;
  var $ = function (id) { return document.getElementById(id); };
  var pollTimer = null, openTimer = null, cancelledAutoOpen = false;
  var sessionRequestGeneration = 0;

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
  // 0. A Google / Apple sign-in that did not complete comes back with ?oauth_error=<code>. Only
  //    known codes are shown, as fixed text; the parameter then leaves the address bar. So does
  //    the fragment: GoTrue repeats its error there, a browser carries a fragment across the
  //    server's redirect, and step 1 would otherwise replace this notice with the provider's text.
  var OAUTH_ERRORS = {
    cancelled: "Sign-in was cancelled.",
    expired: "That sign-in took too long or was opened in another browser. Please try again.",
    failed: "Sign-in failed. Please try again.",
    rate_limited: "Too many attempts. Try again in a few minutes."
  };
  function oauthErrorNotice() {
    var params = new URLSearchParams(location.search);
    var code = params.get("oauth_error");
    if (!code) return;
    params.delete("oauth_error");
    var rest = params.toString();
    history.replaceState(null, "", location.pathname + (rest ? "?" + rest : ""));
    notice(Object.prototype.hasOwnProperty.call(OAUTH_ERRORS, code) ? OAUTH_ERRORS[code] : OAUTH_ERRORS.failed, "error");
  }
  // 1. Harvest GoTrue's implicit-flow fragment: hand the tokens to the server (which turns
  //    them into HttpOnly cookies), then strip the fragment from the URL. Resolves to true when
  //    a session was established.
  async function harvestFragment() {
    var h = (location.hash || "").replace(/^#/, "");
    if (!h) return false;
    var p = new URLSearchParams(h);
    history.replaceState(null, "", location.pathname + location.search);
    if (p.get("access_token")) {
      try {
        var r = await api("/api/session", { method: "POST", body: { access_token: p.get("access_token"), refresh_token: p.get("refresh_token") || "" } });
        if (r.ok) return true;
        notice("Sign-in link could not be verified (HTTP " + r.status + "). Request a new one.", "error");
      } catch (_) { notice("Network error while signing in.", "error"); }
    } else if (p.get("error_description") || p.get("error")) {
      notice(p.get("error_description") || p.get("error"), "error");
    }
    return false;
  }

  function esc(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
    });
  }
  function ago(iso) {
    var s = Math.max(0, Math.round((Date.now() - Date.parse(iso)) / 1000));
    if (s < 60) return s + "s ago";
    var m = Math.round(s / 60); if (m < 60) return m + " min ago";
    var h = Math.round(m / 60); if (h < 48) return h + " h ago";
    return Math.round(h / 24) + " d ago";
  }
  // Tags the share link so the viewer knows to come back here when the session ends, instead
  // of leaving a "you can close this tab" overlay. The tag is a fixed marker the viewer matches
  // exactly; it never carries a URL. Query, not fragment: the fragment is the E2E secret.
  function safeHttpUrl(u) {
    try {
      var x = new URL(u);
      if (x.protocol !== "https:" && x.protocol !== "http:") return null;
      x.searchParams.set("from", "live-sessions");
      return x.href;
    } catch (_) { return null; }
  }

  // The cookies are HttpOnly, so there is nothing to attach: same-origin credentials do it.
  async function api(path, opts) {
    opts = opts || {};
    var headers = Object.assign({ "Accept": "application/json" }, opts.headers || {});
    if (opts.body) headers["Content-Type"] = "application/json";
    return fetch(base + path, { method: opts.method || "GET", headers: headers,
      body: opts.body ? JSON.stringify(opts.body) : undefined, credentials: "same-origin" });
  }

  async function signOut(msg) {
    stopPolling();
    sessionRequestGeneration++;
    if (openTimer) { clearTimeout(openTimer); openTimer = null; }
    if (viewing) closeSession("", false);
    terminalPreferences = null; preferencesOwner = null;
    try { await api("/api/logout", { method: "POST" }); } catch (_) {}
    show("signin");
    if (msg) notice(msg, "error");
  }

  // 401 means no usable cookie AND no refreshable one (the server already tried); back to the form.
  async function loadSessions(isPoll) {
    var requestGeneration = ++sessionRequestGeneration;
    if (!isPoll) show("loading");
    var r = await api("/api/sessions?terminal_preferences=1&app_sessions=1");
    if (requestGeneration !== sessionRequestGeneration) return;
    if (r.status === 401) {
      terminalPreferences = null; preferencesOwner = null;
      // Keep whatever the landing already said (an expired-link error from GoTrue, a failed
      // /api/session); wiping it here left the user at a blank form with no reason.
      stopPolling(); show("signin");
      return;
    }
    if (!r.ok) { notice("Could not load sessions (HTTP " + r.status + "). Retrying…", "error"); show("list"); return; }
    var data = await r.json();
    if (requestGeneration !== sessionRequestGeneration) return;
    acceptTerminalPreferences(data);
    render(data.sessions || [], data.email || "", data.app_sessions || []);
  }

  // The viewer is embedded in an iframe rather than navigated to, so the address bar stays on
  // this page and "back" is instant. The host allows framing only for the account link and only
  // by this origin; the frame tells us when the session ends (see onFrameMessage).
  var viewing = null; // { url, label }
  var terminalPreferences = null;
  var preferencesOwner = null;
  function acceptTerminalPreferences(data) {
    var owner = data.terminal_preferences_owner || null;
    if (!owner || owner !== preferencesOwner) terminalPreferences = {unfocused_mode: "batch", unfocused_fps: 4, revision: 0};
    if (viewing && preferencesOwner && owner !== preferencesOwner) closeSession("");
    preferencesOwner = owner;
    var value = data.terminal_preferences;
    if (owner && value && ["batch", "preview"].indexOf(value.unfocused_mode) >= 0 &&
        Number.isInteger(value.unfocused_fps) && value.unfocused_fps >= 1 && value.unfocused_fps <= 30 &&
        Number.isSafeInteger(value.revision) && value.revision >= terminalPreferences.revision) {
      terminalPreferences = value;
    }
  }
  function postTerminalPreferences() {
    var frame = $("viewerframe");
    if (!viewing || !terminalPreferences || !frame.contentWindow) return;
    frame.contentWindow.postMessage({type: "bossterm-terminal-preferences", preferences: terminalPreferences}, new URL(viewing.url).origin);
  }
  async function refreshTerminalPreferences() {
    var owner = viewing;
    if (!owner) return;
    var requestGeneration = ++sessionRequestGeneration;
    var response = await api("/api/sessions?terminal_preferences=1");
    if (viewing !== owner || requestGeneration !== sessionRequestGeneration) return;
    if (response.status === 401) { terminalPreferences = null; preferencesOwner = null; closeSession("", false); show("signin"); return; }
    if (!response.ok) return;
    var data = await response.json();
    if (viewing !== owner || requestGeneration !== sessionRequestGeneration) return;
    acceptTerminalPreferences(data);
    postTerminalPreferences();
  }
  $("viewerframe").addEventListener("load", function () {
    postTerminalPreferences();
    refreshTerminalPreferences().catch(function () {});
  });
  setInterval(function () {
    if (viewing && document.visibilityState === "visible") refreshTerminalPreferences().catch(function () {});
  }, 60000);
  window.addEventListener("focus", function () { refreshTerminalPreferences().catch(function () {}); });
  // The on-screen keyboard is only visible to the TOP document: on iOS the layout viewport
  // never shrinks, only window.visualViewport does, and a cross-origin iframe sees neither. The
  // viewer pins its key bar to ITS bottom edge, so while a session is embedded the page sizes
  // <main> to the visual viewport and follows its offset; the frame's bottom then sits just above
  // the keyboard and the bar rides it, exactly as when Android resizes a window. Android already
  // shrinks the window, where this is a harmless no-op.
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

  function openSession(url, label) {
    if (viewing) return;
    sessionRequestGeneration++;
    viewing = { url: url, label: label };
    stopPolling();
    if (openTimer) { clearTimeout(openTimer); openTimer = null; }
    $("viewer-name").textContent = label;
    $("viewer-newtab").setAttribute("href", url);
    $("viewerframe").setAttribute("src", url);
    document.body.classList.add("viewing");
    fitViewport();
    try { history.pushState({ view: "session" }, "", location.pathname + location.search); } catch (_) {}
  }
  function closeSession(reasonText, reload) {
    if (!viewing) return;
    sessionRequestGeneration++;
    viewing = null;
    $("viewerframe").setAttribute("src", "about:blank");
    document.body.classList.remove("viewing");
    fitViewport();
    cancelledAutoOpen = true; // do not bounce straight back into a session that just ended
    if (reasonText) notice(reasonText, null);
    if (reload !== false) loadSessions(false).catch(function () {});
  }
  function onFrameMessage(ev) {
    var frame = $("viewerframe");
    if (!viewing || !frame.contentWindow || ev.source !== frame.contentWindow || ev.origin !== new URL(viewing.url).origin) return;
    var d = ev.data;
    if (!d || d.type !== "bossterm-session-ended") return;
    closeSession(d.reason === "user" ? "" : "The session ended. Pick another one or wait for it to come back.");
  }
  window.addEventListener("message", onFrameMessage);
  window.addEventListener("popstate", function () { if (viewing) closeSession(""); });

  function render(sessions, email, appSessions) {
    appSessions = appSessions || [];
    $("who").textContent = email || "";
    if (viewing) return; // the frame is up; leave the list alone until it closes (closeSession reloads)
    var ul = $("sessions");
    var appUl = $("app-sessions");
    ul.innerHTML = "";
    appUl.innerHTML = "";
    if (sessions.length === 1 && appSessions.length === 0 && !cancelledAutoOpen && !openTimer) {
      var s = sessions[0], url = safeHttpUrl(s.control_url);
      if (url) {
        var label = s.device_name + (s.session_name && s.session_name !== s.device_name ? " · " + s.session_name : "");
        $("opening-device").textContent = label;
        $("opening-link").setAttribute("href", url);
        show("opening");
        openTimer = setTimeout(function () { openTimer = null; openSession(url, label); }, 1500);
        return;
      }
    }
    if (sessions.length === 0) {
      ul.innerHTML = '<li><div><div class="name">No live terminal sessions</div><div class="meta">Share a terminal from BossTerm or BossConsole to see it here.</div></div></li>';
    }
    if (appSessions.length === 0) {
      appUl.innerHTML = '<li><div><div class="name">No live BossConsole sessions</div><div class="meta">Open BossConsole and sign in with this account to see it here.</div></div></li>';
    }
    sessions.forEach(function (s) {
      var url = safeHttpUrl(s.control_url); if (!url) return;
      var li = document.createElement("li");
      li.innerHTML =
        '<div><div class="name">' + esc(s.device_name) +
        (s.session_name && s.session_name !== s.device_name ? ' <span class="meta">' + esc(s.session_name) + "</span>" : "") +
        (s.secure ? '<span class="pill ok">E2E ' + esc(s.e2e_code || "") + "</span>" : '<span class="pill warn">not encrypted</span>') +
        "</div>" +
        '<div class="meta">' + esc(s.scope === "WINDOW" ? "Whole window" : s.scope === "ALL" ? "All windows" : "One tab") +
        " · started " + esc(ago(s.started_at)) + " · seen " + esc(ago(s.last_seen_at)) + "</div></div>" +
        '<a class="btn" rel="noreferrer">Open</a>';
      var a = li.querySelector("a");
      a.setAttribute("href", url);
      a.addEventListener("click", function (ev) {
        ev.preventDefault();
        openSession(url, s.device_name + (s.session_name && s.session_name !== s.device_name ? " · " + s.session_name : ""));
      });
      ul.appendChild(li);
    });
    appSessions.forEach(function (s) {
      if (!s || typeof s.name !== "string" || !/^[0-9a-f-]{36}$/i.test(s.session_id)) return;
      var li = document.createElement("li");
      li.innerHTML = '<div><div class="name">' + esc(s.name) + '<span class="pill ok">BossConsole</span></div><div class="meta">Shared application windows · encrypted media</div></div><a class="btn" rel="noopener noreferrer" target="_blank">Open</a>';
      li.querySelector("a").setAttribute("href", base + "/app-viewer/?session=" + encodeURIComponent(s.session_id));
      appUl.appendChild(li);
    });
    show("list");
    startPolling();
  }

  function startPolling() {
    stopPolling();
    pollTimer = setInterval(function () {
      if (document.visibilityState === "visible") loadSessions(true).catch(function () {});
    }, 10000);
  }
  function stopPolling() { if (pollTimer) { clearInterval(pollTimer); pollTimer = null; } }

  // Wiring
  $("signin-form").addEventListener("submit", async function (ev) {
    ev.preventDefault();
    var email = $("email").value.trim();
    if (!email) return;
    $("send").disabled = true;
    try {
      var r = await api("/api/otp", { method: "POST", body: { email: email }, auth: false });
      if (r.status === 429) { notice("Too many attempts. Try again in a few minutes.", "error"); return; }
      if (!r.ok) { notice("Could not send the link (HTTP " + r.status + ").", "error"); return; }
      $("sent-email").textContent = email;
      notice("");
      show("sent");
    } finally { $("send").disabled = false; }
  });
  $("sent-back").addEventListener("click", function () { show("signin"); });
  $("refresh").addEventListener("click", function () { loadSessions(false).catch(function () {}); });
  $("signout").addEventListener("click", function () { signOut(); });
  $("opening-link").addEventListener("click", function (ev) {
    ev.preventDefault();
    if (openTimer) { clearTimeout(openTimer); openTimer = null; }
    openSession($("opening-link").getAttribute("href"), $("opening-device").textContent);
  });
  $("viewer-back").addEventListener("click", function () { closeSession(""); });
  $("opening-cancel").addEventListener("click", function (ev) {
    ev.preventDefault();
    cancelledAutoOpen = true;
    if (openTimer) { clearTimeout(openTimer); openTimer = null; }
    loadSessions(false).catch(function () {});
  });
  document.addEventListener("visibilitychange", function () {
    if (document.visibilityState === "visible" && viewing) refreshTerminalPreferences().catch(function () {});
    if (document.visibilityState === "visible" && !$("list").classList.contains("hidden")) {
      loadSessions(true).catch(function () {});
    }
  });

  // Boot: establish the cookie session from a fragment if there is one, then ask the server.
  // A 401 there is the ordinary "not signed in" answer and shows the form.
  oauthErrorNotice();
  harvestFragment().then(function () {
    return loadSessions(false);
  }).catch(function () { notice("Network error.", "error"); show("signin"); });
})();
`

/** Google's "G" in its brand colours; inline SVG so no image source is needed. */
const GOOGLE_MARK =
  `<svg viewBox="0 0 18 18" aria-hidden="true">` +
  `<path fill="#4285F4" d="M17.64 9.2c0-.64-.06-1.25-.16-1.84H9v3.48h4.84a4.14 4.14 0 0 1-1.8 2.72v2.26h2.92c1.7-1.57 2.68-3.87 2.68-6.62z"/>` +
  `<path fill="#34A853" d="M9 18c2.43 0 4.47-.8 5.96-2.18l-2.92-2.26c-.8.54-1.83.86-3.04.86-2.34 0-4.33-1.58-5.04-3.71H.96v2.33A9 9 0 0 0 9 18z"/>` +
  `<path fill="#FBBC05" d="M3.96 10.71A5.41 5.41 0 0 1 3.68 9c0-.59.1-1.17.28-1.71V4.96H.96A9 9 0 0 0 0 9c0 1.45.35 2.83.96 4.04l3-2.33z"/>` +
  `<path fill="#EA4335" d="M9 3.58c1.32 0 2.5.45 3.44 1.35l2.58-2.59A9 9 0 0 0 .96 4.96l3 2.33C4.67 5.16 6.66 3.58 9 3.58z"/>` +
  `</svg>`

/** The Apple logo, drawn in the button's text colour. */
const APPLE_MARK =
  `<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M16.37 1.43c0 1.14-.49 2.27-1.18 3.08-.74.9-1.99 1.57-2.99 1.57-.12 0-.23-.02-.3-.03-.01-.06-.04-.22-.04-.39 0-1.15.57-2.27 1.21-2.98.8-.94 2.14-1.64 3.25-1.68.03.13.05.28.05.43zm4.34 15.59c-.03.07-.46 1.58-1.52 3.12-.95 1.34-1.94 2.71-3.43 2.71-1.52 0-1.9-.88-3.63-.88-1.7 0-2.3.91-3.67.91-1.38 0-2.33-1.26-3.43-2.8C3.74 18.26 2.7 15.45 2.7 12.8c0-4.28 2.8-6.55 5.55-6.55 1.45 0 2.68.95 3.6.95.87 0 2.22-1.01 3.9-1.01.61 0 2.89.06 4.37 2.19-.13.09-2.38 1.37-2.38 4.19 0 3.26 2.85 4.32 2.95 4.38z"/></svg>`

export function livePage(model: PageModel, nonce: string): string {
  const cfg = { basePath: model.basePath, liveWindowSeconds: model.liveWindowSeconds }
  const notice = `<div id="notice" class="notice hidden"></div>`
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<meta name="robots" content="noindex, nofollow">
<title>BOSS Live Sessions</title>
<link rel="icon" type="image/svg+xml" href="${esc(model.basePath)}/app-viewer/boss-logo.svg">
<style nonce="${esc(nonce)}">${STYLES}</style>
</head>
<body>
<!--email_off-->
<main>
  <header>
    <div><h1>BOSS live sessions</h1><div class="sub">Your BossTerm terminals and BossConsole windows, using the same BOSS account</div></div>
    <div class="sub" id="who"></div>
  </header>
  ${notice}

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
        <span class="sub">Use the same account as BossTerm and BossConsole.</span>
      </div>
    </form>
  </section>

  <section id="sent" class="card hidden">
    <div class="name">Check your email</div>
    <p class="sub">We sent a sign-in link to <strong id="sent-email"></strong>. Open it on this device and your live sessions will appear here.</p>
    <div class="row"><button id="sent-back" class="secondary" type="button">Use a different email</button></div>
  </section>

  <section id="loading" class="card hidden"><div class="sub">Loading your sessions…</div></section>

  <section id="opening" class="card hidden">
    <div class="name">Opening <span id="opening-device"></span>…</div>
    <p class="sub">Your only live session. If nothing happens, use the button.</p>
    <div class="row">
      <a id="opening-link" class="btn" rel="noreferrer" href="#">Open session</a>
      <a id="opening-cancel" class="btn secondary" href="#">Show the list instead</a>
    </div>
  </section>

  <section id="list" class="card hidden">
    <section class="session-group" aria-labelledby="bossterm-heading">
      <h2 id="bossterm-heading">BossTerm live sessions</h2>
      <div class="sub">Terminals shared from BossTerm or inside BossConsole</div>
      <ul id="sessions" class="sessions"></ul>
    </section>
    <section class="session-group" aria-labelledby="bossconsole-heading">
      <h2 id="bossconsole-heading">BossConsole live sessions</h2>
      <div class="sub">Shared BossConsole application windows</div>
      <ul id="app-sessions" class="sessions"></ul>
    </section>
    <div class="row">
      <button id="refresh" class="secondary" type="button">Refresh</button>
      <button id="signout" class="secondary" type="button">Sign out</button>
      <span class="sub">Terminal sessions disappear about ${esc(String(model.liveWindowSeconds))} seconds after sharing stops or the app closes.</span>
    </div>
  </section>

  <div id="viewer">
    <div id="viewerbar">
      <button id="viewer-back" class="secondary" type="button">&#8592; Sessions</button>
      <span class="name" id="viewer-name"></span>
      <a id="viewer-newtab" class="btn secondary" target="_blank" rel="noopener noreferrer" href="#">Open in new tab</a>
    </div>
    <iframe id="viewerframe" title="Shared terminal" allow="clipboard-write; fullscreen" allowfullscreen src="about:blank"></iframe>
  </div>

  <footer>Only you can see this list. Links open the live share-viewer end to end encrypted when the badge shows <code>E2E</code>.</footer>
</main>
<!--/email_off-->
<script id="cfg" type="application/json">${jsonForScript(cfg)}</script>
<script nonce="${esc(nonce)}">${SCRIPT}</script>
</body>
</html>`
}
