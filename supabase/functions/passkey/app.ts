/**
 * Passkey Edge Function - routing.
 *
 * Kept apart from index.ts so tests can drive `app.request()` without binding a
 * listener, matching crash-report / organisation / latest-release. The test
 * suite imports this module to exercise the real entrypoint handlers -
 * including the global error handler and /maintenance/cleanup - which is what
 * pins "server diagnostics never reach a response body".
 *
 * The service-role client is created lazily (and is replaceable via
 * setPasskeyClientForTests) so the app can be driven with a stub in tests the
 * way the organisation function does; in production it is created once on the
 * first request from the same environment variables as before.
 */

import { OpenAPIHono } from "@hono/zod-openapi"
import { swaggerUI } from "@hono/swagger-ui"
import { cors } from "hono/cors"
import auth from "./routes/auth.ts"
import register from "./routes/register.ts"
import management from "./routes/management.ts"
import mobile from "./routes/mobile.ts"
import type { PasskeyContext } from "./types/context.ts"
import { cleanupExpiredChallenges } from "./utils/challenge.ts"
import { authFailureDetails } from "./utils/logging.ts"
import { passkeyClient } from "./utils/client.ts"

const app = new OpenAPIHono<{ Variables: PasskeyContext }>().basePath("/passkey")

// CORS configuration
app.use("*", cors({
  origin: ['boss://authenticate', 'http://localhost:3000', 'https://risaboss.com'],
  allowMethods: ['POST', 'GET', 'OPTIONS'],
  allowHeaders: ['Content-Type', 'Authorization'],
  exposeHeaders: ['Content-Length'],
  maxAge: 600,
  credentials: true,
}))

// Inject Supabase client into context
app.use("*", async (ctx, next) => {
  ctx.set("supabase", passkeyClient())
  await next()
})

// Mount routes
app.route("/auth", auth)
app.route("/register", register)
app.route("/manage", management)
app.route("/", mobile) // Mobile routes are mounted at root since paths are /register/mobile and /auth/mobile

// Health check endpoint
app.get("/health", (ctx) => {
  return ctx.json({ status: "healthy", timestamp: new Date().toISOString() }, 200)
})

// Maintenance endpoint to cleanup expired challenges (can be called by cron/scheduler)
app.post("/maintenance/cleanup", async (ctx) => {
  const supabase = ctx.get("supabase")
  const result = await cleanupExpiredChallenges(supabase)

  if (result.success) {
    return ctx.json({ message: "Cleanup completed successfully" }, 200)
  } else {
    // The failure stays in the logs (redacted one frame down); a raw
    // database message must never be handed to an unauthenticated caller.
    return ctx.json({ error: 'Internal server error' }, 500)
  }
})

// OpenAPI documentation
app.doc("/openapi", {
  openapi: "3.1.0",
  info: {
    title: "BOSS Passkey API",
    version: "1.0.0",
    description: "WebAuthn/Passkey authentication API for BOSS application"
  },
  servers: [
    {
      url: "https://api.risaboss.com/functions/v1/passkey",
      description: "Production server"
    },
    {
      url: "http://localhost:54321/functions/v1/passkey",
      description: "Local development server"
    }
  ],
  tags: [
    {
      name: "Authentication",
      description: "WebAuthn authentication endpoints"
    },
    {
      name: "Registration",
      description: "Passkey registration endpoints"
    },
    {
      name: "Management",
      description: "Passkey management endpoints"
    },
    {
      name: "Mobile",
      description: "Mobile HTML pages for cross-device WebAuthn flows"
    }
  ]
})

// Swagger UI
app.get("/doc", swaggerUI({ url: "/functions/v1/passkey/openapi" }))

// 404 handler
app.notFound((ctx) => {
  return ctx.json({ error: "Not Found" }, 404)
})

// Global error handler: redacted logs, generic body.
app.onError((err, ctx) => {
  console.error('Global error:', authFailureDetails(err))
  return ctx.json({ error: 'Internal server error' }, 500)
})

export { app }
