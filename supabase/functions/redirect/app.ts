/**
 * Redirect Edge Function
 *
 * Converts Supabase magic link URLs to per-app deep links — `boss://` for BOSS
 * Console, `bossterm://` for BossTerm (risa-labs-inc/BossConsole#787; both apps
 * share this Supabase project's user pool). Used in email templates to redirect
 * users from email clients to the desktop app.
 *
 * Routes:
 * - GET /?token=<token>&type=<type> - Redirect with explicit token (simple)
 * - GET /?url=<supabase-confirmation-url> - Redirect with full Supabase URL (recommended)
 * - GET /health - Health check
 *
 * App selection (first match wins):
 * - `app=bossterm` query param
 * - `redirect_to` query param (or the redirect_to embedded in the `url=`
 *   confirmation URL) whose scheme is `bossterm://` — BossTerm requests OTPs
 *   with redirect_to=bossterm://auth/verify, so the confirmation URL carries it
 * - default: BOSS Console (`boss://`), unchanged behavior
 *
 * Deep links:
 * - BOSS:     boss://auth/verify?token=<token_hash>&type=<type>
 * - BossTerm: bossterm://auth/verify?token_hash=<token_hash>&type=<type>
 *   (`type` passes through VERBATIM — a brand-new user's first link verifies as
 *   `signup`, not `magiclink`)
 *
 * SECURITY: this function must stay a PURE redirect — it never calls GoTrue's
 * verify endpoint itself, so email-scanner link prefetch (Outlook SafeLinks
 * etc.) cannot consume the single-use token.
 *
 * Usage in email template:
 * Wrap {{ .ConfirmationURL }} with redirect function:
 * https://api.risaboss.com/functions/v1/redirect?url={{ .ConfirmationURL }}
 */

import { OpenAPIHono } from "@hono/zod-openapi"
import { cors } from "hono/cors"

export const app = new OpenAPIHono().basePath("/redirect")

// CORS configuration
app.use("*", cors({
  origin: "*",
  allowMethods: ["GET", "OPTIONS"],
  allowHeaders: ["Content-Type"],
  maxAge: 600,
}))

/** Per-app branding + deep-link shape. */
interface Brand {
  /** Deep-link scheme the app registered with the OS. */
  scheme: string
  /** Query param name the app's deep-link parser expects the token hash under. */
  tokenParam: string
  name: string
  tagline: string
  appLabel: string
  footerLine: string
  copyright: string
}

const BRANDS: Record<"boss" | "bossterm" | "web", Brand> = {
  web: {
    scheme: "https",
    tokenParam: "token",
    name: "BossTerm Live Sessions",
    tagline: "Your shared terminals, in the browser",
    appLabel: "Live Sessions",
    footerLine: "BossTerm - by Risa Labs",
    copyright: "© 2025 Risa Labs. All rights reserved.",
  },
  boss: {
    scheme: "boss",
    tokenParam: "token",
    name: "BOSS Console",
    tagline: "Business Operating System as Service",
    appLabel: "BOSS App",
    footerLine: "BOSS Console - Business Operating System as Service",
    copyright: "© 2025 BOSS. All rights reserved.",
  },
  bossterm: {
    scheme: "bossterm",
    tokenParam: "token_hash",
    name: "BossTerm",
    tagline: "Modern Terminal Emulator",
    appLabel: "BossTerm",
    footerLine: "BossTerm - by Risa Labs",
    copyright: "© 2025 Risa Labs. All rights reserved.",
  },
}

// Canonical BossTerm redirect_to — the single source of truth for the contract shared in THREE
// places that must change in lockstep: this check, the GoTrue allow-list (config.toml
// additional_redirect_urls), and BOTH magic-link email templates' `eq .RedirectTo "…"` predicate.
// Exact-match is intentional: GoTrue only allow-lists this exact URL, so a normalized/
// trailing-slash variant never reaches here. If the canonical value ever changes, update all three.
const BOSSTERM_REDIRECT = "bossterm://auth/verify"

// The live-sessions web page (functions/live-sessions) signs users in by magic link too, but its
// redirect_to is an https URL back to itself, not an app scheme. For that arm this function does
// NOT rewrite anything: it bounces to the UNCHANGED GoTrue confirmation URL, which verifies the
// token and 302s to <base>/auth#access_token=... . Still a pure redirect (no verify call here).
// EXACT match, like BOSSTERM_REDIRECT: GoTrue allow-lists exactly these values, so a variant never
// verifies anyway, and matching exactly keeps this arm from forwarding anything GoTrue would not.
// Lockstep set is FOUR places: these two values, config.toml additional_redirect_urls,
// live-sessions/utils/config.ts DEFAULT_BASE_PATH (+ LIVE_SESSIONS_PUBLIC_BASE_URL), and both
// email templates' `eq .RedirectTo` predicate.
export const LIVE_SESSIONS_REDIRECTS: ReadonlySet<string> = new Set([
  "https://cli.risaboss.com/auth", // vanity host: a Cloudflare Worker proxies it onto the function
  "https://api.risaboss.com/functions/v1/live-sessions/auth",
  "http://127.0.0.1:54321/functions/v1/live-sessions/auth", // local `supabase start` stack
])

export function isLiveSessionsRedirect(redirectTo: string | undefined): boolean {
  return !!redirectTo && LIVE_SESSIONS_REDIRECTS.has(redirectTo)
}

/**
 * Generates HTML page that auto-redirects to the app's deep link, with a manual
 * fallback button for browsers that block custom-scheme redirects.
 * Design matches the magic-link email template.
 */

/**
 * Escape a value for an HTML double-quoted attribute. `deepLink` carries
 * caller-controlled token/type, and encodeURIComponent does NOT escape ' ! ~ * ( ) - .
 * so it must be output-escaped per context (this and jsStringLiteral) — otherwise a
 * crafted ?token=...'... reflects XSS onto this origin.
 */
export function htmlAttr(s: string): string {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;")
}

/**
 * Safe JS string literal (double-quoted, with `<` escaped so it can't end the <script>).
 * CAVEAT: JSON.stringify does NOT escape the U+2028/U+2029 line separators, which are illegal
 * raw inside a JS string literal. Safe HERE because every value reaches it via encodeURIComponent
 * (which percent-encodes those) — do NOT reuse this on un-encoded input without also escaping
 *  / .
 */
export function jsStringLiteral(s: string): string {
  return JSON.stringify(s).replace(/</g, "\\u003c")
}

function generateRedirectPage(deepLink: string, brand: Brand): string {
  return `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Opening ${brand.appLabel}</title>
    <style>
        body {
            margin: 0;
            padding: 0;
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
            background-color: #1a1a1a;
            display: flex;
            align-items: center;
            justify-content: center;
            min-height: 100vh;
        }
        .container {
            width: 100%;
            max-width: 600px;
            background-color: #2B2B2B;
            border-radius: 8px;
            box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
            margin: 20px;
        }
        .header {
            padding: 40px 40px 30px;
            text-align: center;
            border-bottom: 1px solid #4D4D4D;
        }
        .header h1 {
            margin: 0;
            color: #F2F2F2;
            font-size: 28px;
            font-weight: 600;
            letter-spacing: -0.5px;
        }
        .header p {
            margin: 8px 0 0;
            color: #AAAAAA;
            font-size: 14px;
            letter-spacing: 0.5px;
        }
        .content {
            padding: 40px;
            text-align: center;
        }
        .content h2 {
            margin: 0 0 16px;
            color: #F2F2F2;
            font-size: 24px;
            font-weight: 600;
        }
        .content p {
            margin: 0 0 24px;
            color: #AAAAAA;
            font-size: 16px;
            line-height: 1.5;
        }
        .spinner {
            border: 3px solid #4D4D4D;
            border-radius: 50%;
            border-top: 3px solid #3592C4;
            width: 50px;
            height: 50px;
            animation: spin 1s linear infinite;
            margin: 30px auto;
        }
        @keyframes spin {
            0% { transform: rotate(0deg); }
            100% { transform: rotate(360deg); }
        }
        .button {
            display: inline-block;
            padding: 16px 48px;
            background-color: #3592C4;
            color: #FFFFFF;
            text-decoration: none;
            border-radius: 6px;
            font-size: 16px;
            font-weight: 600;
            letter-spacing: 0.3px;
            box-shadow: 0 2px 8px rgba(53, 146, 196, 0.3);
            transition: background-color 0.2s;
        }
        .button:hover {
            background-color: #2d7aa8;
        }
        .notice {
            margin: 30px 40px;
            padding: 16px 20px;
            background-color: #3C3F41;
            border-radius: 6px;
            border-left: 3px solid #43A047;
        }
        .notice p:first-child {
            margin: 0;
            color: #F2F2F2;
            font-size: 13px;
            font-weight: 600;
        }
        .notice p:last-child {
            margin: 8px 0 0;
            color: #AAAAAA;
            font-size: 13px;
            line-height: 1.5;
        }
        .footer {
            padding: 30px 40px;
            text-align: center;
            border-top: 1px solid #4D4D4D;
        }
        .footer p:first-child {
            margin: 0;
            color: #AAAAAA;
            font-size: 13px;
            line-height: 1.5;
        }
        .footer p:last-child {
            margin: 12px 0 0;
            color: #666666;
            font-size: 12px;
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>${brand.name}</h1>
            <p>${brand.tagline}</p>
        </div>
        <div class="content">
            <h2>Opening ${brand.appLabel}</h2>
            <div class="spinner"></div>
            <p>Redirecting you to ${brand.name}...</p>
            <p style="margin-bottom: 16px; color: #AAAAAA; font-size: 14px;">If the app doesn't open automatically:</p>
            <a href="${htmlAttr(deepLink)}" class="button">Open ${brand.appLabel}</a>
        </div>
        <div class="notice">
            <p>🔒 Secure Authentication</p>
            <p>You're being securely redirected to ${brand.name}. This link is valid for one-time use only.</p>
        </div>
        <div class="footer">
            <p>${brand.footerLine}</p>
            <p>${brand.copyright}</p>
        </div>
    </div>
    <script>
        // Single redirect after a brief delay to show the page
        setTimeout(() => {
            window.location.href = ${jsStringLiteral(deepLink)};
        }, 1000);
    </script>
</body>
</html>`
}

// Health check endpoint
app.get("/health", (c) => {
  return c.json({ status: "healthy", timestamp: new Date().toISOString() }, 200)
})

// Main redirect endpoint
app.get("/", (c) => {
  // Token: explicit param first (`token`, with `token_hash` accepted as the
  // BossTerm-contract alias), else extracted from the full Supabase
  // confirmation URL passed as `url=` (the email-template case).
  let token = c.req.query("token") || c.req.query("token_hash")
  // Which name the confirmation URL used; the web arm hands the value back to GoTrue, where the
  // name is meaningful (token vs token_hash), so it must not be rewritten.
  let tokenParamFromUrl: "token" | "token_hash" | null = null
  let type = c.req.query("type") || "magiclink"
  let redirectTo = c.req.query("redirect_to")
  const confirmationUrl = c.req.query("url")

  if (!token) {
    const url = c.req.query("url")
    if (url) {
      try {
        const parsedUrl = new URL(url)
        token = parsedUrl.searchParams.get("token") || parsedUrl.searchParams.get("token_hash") || undefined
        if (!parsedUrl.searchParams.get("token") && parsedUrl.searchParams.get("token_hash")) tokenParamFromUrl = "token_hash"
        // Fall back to the already-captured top-level `type`, NOT a hardcoded "magiclink".
        // GoTrue renders the email link with text/template, so {{ .ConfirmationURL }} is NOT
        // percent-encoded: its &type=&redirect_to= split into top-level params on this request,
        // and `type` (e.g. "signup" for a first-time user) was captured above. The parsed `url`
        // here is truncated at the first & and has no type — hardcoding "magiclink" would send a
        // first-signup token with the wrong type, which GoTrue rejects. (redirect_to below already
        // prefers the top-level value for the same reason.)
        type = parsedUrl.searchParams.get("type") || type
        redirectTo = redirectTo || parsedUrl.searchParams.get("redirect_to") || undefined
      } catch (_e) {
        // Invalid URL format
      }
    }
  }

  if (!token) {
    return c.json({
      error: "Missing 'token' parameter",
      usage: "/?token=<token|token_hash>&type=<type>[&app=bossterm|&redirect_to=bossterm://auth/verify] OR /?url=<supabase-confirmation-url>",
      example: "/?token=abc123&type=magiclink&app=bossterm",
    }, 400)
  }

  // Which app asked for this sign-in? Explicit `app` param wins; otherwise the OTP request's
  // redirect_to identifies it (BossTerm sends redirect_to=bossterm://auth/verify), matched
  // against the canonical BOSSTERM_REDIRECT constant. This signal is presentational and
  // intentionally unauthenticated: it only picks brand text and which FIRST-PARTY scheme
  // (boss:// vs bossterm://) the page bounces to — it can never redirect the single-use token
  // to a non-first-party target.
  // Web (live-sessions) arm: GoTrue must do the verify+redirect itself, so hand the browser the
  // confirmation URL exactly as the email carried it. The template renders {{ .ConfirmationURL }}
  // unencoded, so its &type=&redirect_to= landed as top-level params here; rebuild the URL from
  // them rather than trusting the truncated `url=` value. Only a first-party GoTrue host is ever
  // emitted: the origin comes from `url=` after an allow-list check, never from the caller freely.
  if (isLiveSessionsRedirect(redirectTo)) {
    const origin = firstPartyGoTrueOrigin(confirmationUrl)
    if (!origin) return c.json({ error: "Unsupported confirmation URL host" }, 400)
    const tokenParam = tokenParamFromUrl ?? (c.req.query("token") ? "token" : c.req.query("token_hash") ? "token_hash" : "token")
    const target = `${origin}/auth/v1/verify?${tokenParam}=${encodeURIComponent(token)}&type=${encodeURIComponent(type)}` +
      `&redirect_to=${encodeURIComponent(redirectTo!)}`
    return c.html(generateRedirectPage(target, BRANDS.web))
  }

  const appKey = c.req.query("app") === "bossterm" || redirectTo === BOSSTERM_REDIRECT
    ? "bossterm"
    : "boss"
  const brand = BRANDS[appKey]

  // Build deep link. `type` is passed through verbatim (new users verify as `signup`).
  const deepLink =
    `${brand.scheme}://auth/verify?${brand.tokenParam}=${encodeURIComponent(token)}&type=${encodeURIComponent(type)}`

  // Return HTML redirect page
  const html = generateRedirectPage(deepLink, brand)
  return c.html(html)
})

/**
 * Origin of the GoTrue confirmation URL, if it is one of ours. The email carries
 * `https://api.risaboss.com/auth/v1/verify?...` (or the local stack at 127.0.0.1:54321, the
 * address config.toml and the local template use); anything else - a redirect-through-us to an
 * attacker host - is refused, so the web arm can only ever bounce to these first-party GoTrue
 * origins.
 */
const GOTRUE_ORIGINS = new Set(["https://api.risaboss.com", "http://127.0.0.1:54321"])

export function firstPartyGoTrueOrigin(confirmationUrl: string | undefined): string | null {
  if (!confirmationUrl) return null
  try {
    const u = new URL(confirmationUrl)
    return GOTRUE_ORIGINS.has(u.origin) ? u.origin : null
  } catch {
    return null
  }
}

// 404 handler
app.notFound((c) => {
  return c.json({ error: "Not Found" }, 404)
})

// Global error handler
app.onError((err, c) => {
  console.error("Global error:", err)
  return c.json({ error: "Internal server error" }, 500)
})
