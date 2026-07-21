/**
 * Passkey Edge Function
 *
 * Provides WebAuthn/Passkey authentication endpoints for BOSS application
 *
 * Routes:
 * - POST /passkey/register/challenge - Generate registration challenge
 * - POST /passkey/register/complete - Complete passkey registration
 * - GET /passkey/register/mobile - Mobile registration HTML page
 * - POST /passkey/auth/challenge - Generate authentication challenge
 * - POST /passkey/auth/complete - Complete passkey authentication
 * - GET /passkey/auth/mobile - Mobile authentication HTML page
 * - GET /passkey/auth/status/:sessionId - Check authentication status
 * - POST /passkey/manage/list - List user passkeys
 * - POST /passkey/manage/delete - Delete a passkey
 * - POST /passkey/manage/update - Update passkey display name
 * - GET /passkey/health - Health check
 * - POST /passkey/maintenance/cleanup - Cleanup expired challenges (for scheduled jobs)
 * - GET /passkey/doc - Swagger UI documentation
 * - GET /passkey/openapi - OpenAPI specification (JSON)
 */

import { OpenAPIHono } from "@hono/zod-openapi"
import { swaggerUI } from "@hono/swagger-ui"
import { cors } from "hono/cors"
import { createClient } from "@supabase/supabase-js"
import auth from "./routes/auth.ts"
import register from "./routes/register.ts"
import management from "./routes/management.ts"
import mobile from "./routes/mobile.ts"
import type { PasskeyContext } from "./types/context.ts"
import { cleanupExpiredChallenges } from "./utils/challenge.ts"

const app = new OpenAPIHono<{ Variables: PasskeyContext }>().basePath("/passkey")
const supabaseUrl = Deno.env.get("SUPABASE_URL") || ""
const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || ""

// Create Supabase client with service role key
const supabase = createClient(supabaseUrl, supabaseServiceKey)

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
  ctx.set("supabase", supabase)
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
    return ctx.json({ error: result.error }, 500)
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

// Global error handler
app.onError((err, ctx) => {
  console.error('Global error:', err)
  return ctx.json({ error: err.message }, 500)
})

Deno.serve(app.fetch)
