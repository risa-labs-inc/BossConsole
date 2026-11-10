/**
 * The page chrome shared by `fluck-vault` and `fluck-oauth`: one stylesheet, one document shell,
 * the result and error pages, and the validation of the two request supplied fields that
 * personalise them.
 *
 * ## Static by construction
 *
 * `STYLE` is a constant and the CSP pins it by SHA-256, so nothing may ever be interpolated into
 * it and no element may carry a `style` attribute. Everything that varies per request (agent
 * name, return URL) goes into the HTML body, escaped with `escapeHtml`.
 */

/** What every page calls the agent when the install did not send a usable name. */
export const FALLBACK_AGENT = "your agent"

export const STYLE = `*{box-sizing:border-box}
:root{--bg:#f6f7f9;--card:#fff;--line:#e4e7ec;--h:#101828;--body:#475467;--muted:#667085;
--label:#344054;--field:#fff;--fline:#d0d5dd;--ro:#f2f4f7;--accent:#2f5bea;--accent-h:#2549c9;
--ring:rgba(47,91,234,.18);--err:#d92d20;--ok:#12b76a;--ok-bg:#ecfdf3;--err-bg:#fef3f2;
--info:#667085;--info-bg:#f2f4f7}
@media(prefers-color-scheme:dark){:root{--bg:#0d0f12;--card:#16191e;--line:#2a2f37;--h:#f2f4f7;
--body:#b0b8c4;--muted:#98a2b3;--label:#cfd4dc;--field:#0f1216;--fline:#3a404a;--ro:#1c2026;
--accent:#5b7cff;--accent-h:#4a69e8;--ring:rgba(91,124,255,.3);--err:#f97066;
--ok-bg:rgba(18,183,106,.16);--err-bg:rgba(249,112,102,.16);--info:#98a2b3;--info-bg:#232830}}
body{margin:0;padding:32px 16px 48px;background:var(--bg);color:var(--body);
font:15px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif}
.wrap{max-width:440px;margin:0 auto}
.brand{display:flex;align-items:center;gap:8px;margin:0 0 16px;color:var(--muted);
font-size:13px;font-weight:600}
.mark{width:22px;height:22px;display:block}
.mark rect{fill:currentColor}
.mark path{fill:none;stroke:var(--bg);stroke-width:2.2;stroke-linejoin:round;stroke-linecap:round}
.card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:28px;
box-shadow:0 1px 2px rgba(16,24,40,.06)}
@media(max-width:399px){.card{padding:20px}}
.eyebrow{margin:0 0 6px;font-size:12px;font-weight:500;color:var(--muted)}
h1{margin:0 0 8px;font-size:22px;line-height:1.3;font-weight:600;color:var(--h)}
p{margin:0 0 20px}
.status{width:40px;height:40px;border-radius:50%;display:flex;align-items:center;
justify-content:center;margin:0 0 16px}
.status svg{width:20px;height:20px;fill:none;stroke:currentColor;stroke-width:2.2;
stroke-linecap:round;stroke-linejoin:round}
.ok{background:var(--ok-bg);color:var(--ok)}
.bad{background:var(--err-bg);color:var(--err)}
.info{background:var(--info-bg);color:var(--info)}
.sec{margin:22px 0 12px;font-size:13px;font-weight:600;color:var(--h)}
.sec:first-child{margin-top:0}
.f{display:block;margin:0 0 14px}
.f span{display:block;margin:0 0 6px;font-size:13px;font-weight:500;color:var(--label)}
input{display:block;width:100%;height:44px;padding:0 12px;font-size:16px;
font-family:inherit;color:var(--h);background:var(--field);border:1px solid var(--fline);
border-radius:8px}
input:focus{outline:0;border-color:var(--accent);box-shadow:0 0 0 3px var(--ring)}
input[readonly]{background:var(--ro);color:var(--muted)}
.row{display:flex;gap:12px}
.row .f{flex:1;min-width:0}
@media(max-width:359px){.row{display:block}}
.err{min-height:1.2em;margin:0 0 12px;font-size:13px;color:var(--err)}
.btn{display:flex;align-items:center;justify-content:center;width:100%;height:44px;margin:0;
padding:0 16px;font-size:15px;font-weight:600;font-family:inherit;border-radius:8px;
text-decoration:none;cursor:pointer;border:1px solid transparent}
.btn:focus-visible{outline:0;box-shadow:0 0 0 3px var(--ring)}
.btn-primary{background:var(--accent);color:#fff}
.btn-primary:hover{background:var(--accent-h)}
.btn-secondary{background:transparent;color:var(--h);border-color:var(--fline)}
.btn-secondary:hover{background:var(--ro)}
.btn:disabled{opacity:.6;cursor:default}
.cancel{display:block;margin:14px 0 0;text-align:center;font-size:13px;color:var(--muted)}
.muted{margin:14px 0 0;text-align:center;font-size:13px;color:var(--muted)}
.note{margin:20px 0 0;font-size:13px;color:var(--muted)}
.foot{display:flex;align-items:center;justify-content:center;gap:6px;margin:16px 0 0;
font-size:12px;color:var(--muted)}
.foot svg{width:12px;height:12px;fill:none;stroke:currentColor;stroke-width:2;
stroke-linecap:round;stroke-linejoin:round}`

/** Base64 SHA-256 of a string, in the shape a CSP hash source wants. */
export async function sha256Base64(value: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(value) as BufferSource,
  )
  let binary = ""
  for (const byte of new Uint8Array(digest)) binary += String.fromCharCode(byte)
  return btoa(binary)
}

export async function staticCsp(): Promise<string> {
  const style = await sha256Base64(STYLE)
  return [
    "default-src 'none'",
    `style-src 'sha256-${style}'`,
    "form-action 'none'",
    "frame-ancestors 'none'",
    "base-uri 'none'",
  ].join("; ")
}

/** Computed once at load, for callers that build a response synchronously. */
export const STATIC_CSP = await staticCsp()

export function escapeHtml(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;")
}

// --------------------------------------------------------------------------------------------
// The two optional fields
// --------------------------------------------------------------------------------------------

const MAX_AGENT_NAME = 40
const MAX_RETURN_URL = 512
const CONTROL = new RegExp("[\\u0000-\\u001f\\u007f-\\u009f\\u2028\\u2029]")
const LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]"])

/** Trimmed, 1 to 40 characters, no control characters; anything else is null. */
export function parseAgentName(value: unknown): string | null {
  if (typeof value !== "string") return null
  const name = value.trim()
  if (CONTROL.test(name)) return null
  const length = [...name].length
  return length >= 1 && length <= MAX_AGENT_NAME ? name : null
}

/**
 * An absolute `https:` URL, or `http:` on a loopback host, with no credentials. Null otherwise.
 *
 * https to ANY host is acceptable because the value is only ever settable by a registered
 * install's signed request or signed state, never by a browser or a link.
 */
export function parseReturnUrl(value: unknown): string | null {
  if (typeof value !== "string" || value.length === 0 || value.length > MAX_RETURN_URL) return null
  let url: URL
  try {
    url = new URL(value)
  } catch {
    return null
  }
  if (url.username !== "" || url.password !== "") return null
  const allowed = url.protocol === "https:" ||
    (url.protocol === "http:" && LOOPBACK_HOSTS.has(url.hostname))
  if (!allowed || url.href.length > MAX_RETURN_URL) return null
  return url.href
}

/**
 * Both fields at once. Absent is silent; present and unusable logs one line and is dropped, so
 * a bad value never costs the caller its request or its sign in.
 */
export function parseReturnFields(
  agentName: unknown,
  returnUrl: unknown,
  log: (line: string) => void,
): { agentName: string | null; returnUrl: string | null } {
  const name = parseAgentName(agentName)
  const url = parseReturnUrl(returnUrl)
  const present = (v: unknown) => v !== undefined && v !== null
  const dropped = [
    present(agentName) && name === null ? "agentName" : "",
    present(returnUrl) && url === null ? "returnUrl" : "",
  ].filter(Boolean)
  if (dropped.length > 0) log(`ignored invalid ${dropped.join(" and ")}`)
  return { agentName: name, returnUrl: url }
}

/** `your agent` / `Your agent`, or the name as given. */
export function agentRef(name: string | null | undefined): { lower: string; upper: string } {
  const clean = parseAgentName(name)
  return clean ? { lower: clean, upper: clean } : { lower: FALLBACK_AGENT, upper: "Your agent" }
}

// --------------------------------------------------------------------------------------------
// Document
// --------------------------------------------------------------------------------------------

const MARK =
  `<svg class="mark" viewBox="0 0 24 24" aria-hidden="true"><rect width="24" height="24" rx="6"/><path d="M8.5 6v12M8.5 6h3.8a3 3 0 0 1 0 6H8.5m0 0h4.3a3 3 0 0 1 0 6H8.5"/></svg>`
const LOCK =
  `<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/></svg>`
const ICONS = {
  ok: `<path d="M5 12.5l4.5 4.5L19 7.5"/>`,
  bad: `<path d="M12 7v6M12 17h.01"/>`,
  info: `<path d="M12 11v6M12 7h.01"/>`,
}

export const FOOTER_SECURED = "Secured by BOSS."
export const FOOTER_ENCRYPTED = "Secured by BOSS. Encrypted on this device."

export interface ShellOptions {
  title: string
  body: string
  footer: string
  /** Already validated. Emitted as a meta refresh after two seconds. */
  refreshTo?: string | null
  /** The one pinned inline script, if the page has one. */
  script?: string
}

export function shell(options: ShellOptions): string {
  const refresh = options.refreshTo
    ? `<meta http-equiv="refresh" content="2;url=${escapeHtml(options.refreshTo)}">`
    : ""
  return `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<meta name="robots" content="noindex,nofollow">${refresh}
<title>${escapeHtml(options.title)}</title><style>${STYLE}</style></head>
<body><div class="wrap"><div class="brand">${MARK}<span>BOSS</span></div>
<main class="card">${options.body}</main>
<div class="foot">${LOCK}<span>${escapeHtml(options.footer)}</span></div></div>${
    options.script ? `<script>${options.script}</script>` : ""
  }</body></html>`
}

export interface ResultOptions {
  tone: "ok" | "bad" | "info"
  title: string
  text: string
  /** Unvalidated is fine: it is checked again here. Null or absent: a fixed, request free page. */
  returnUrl?: string | null
  /** Success pages send the person back after two seconds; error pages never do. */
  redirect?: boolean
}

/** A page with no form: one status, one sentence, and a way back when there is one. */
export function resultHtml(options: ResultOptions): string {
  const back = parseReturnUrl(options.returnUrl)
  const redirect = options.redirect === true && back !== null
  const actions = back === null
    ? options.tone === "ok" ? `<p class="muted">You can close this page.</p>` : ""
    : redirect
    ? `<a class="btn btn-primary" href="${escapeHtml(back)}">Back to chat</a>
<p class="muted">Taking you back to the chat…</p>`
    : `<a class="btn btn-secondary" href="${escapeHtml(back)}">Back to chat</a>`
  const body = `<div class="status ${options.tone}"><svg viewBox="0 0 24 24" aria-hidden="true">${
    ICONS[options.tone]
  }</svg></div><h1>${escapeHtml(options.title)}</h1><p>${escapeHtml(options.text)}</p>${actions}`
  return shell({
    title: options.title,
    body,
    footer: FOOTER_SECURED,
    refreshTo: redirect ? back : null,
  })
}
