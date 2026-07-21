/**
 * Plugin Store Edge Function
 *
 * Provides a remote plugin store API for BOSS application
 *
 * Routes:
 * - GET  /plugin-store/list            - List all plugins with pagination
 * - POST /plugin-store/search          - Search plugins with filters
 * - GET  /plugin-store/:pluginId       - Get plugin details with all versions
 * - GET  /plugin-store/:pluginId/download        - Download latest JAR
 * - GET  /plugin-store/:pluginId/download/:ver   - Download specific version
 * - POST /plugin-store/:pluginId/rate  - Rate a plugin (auth required)
 * - GET  /plugin-store/:pluginId/rating          - Get user's rating
 * - DELETE /plugin-store/:pluginId/rating        - Delete user's rating
 * - GET  /plugin-store/:pluginId/ratings         - Get all ratings
 * - POST /plugin-store/publish         - Publish new plugin (auth required via JWT or API key)
 * - POST /plugin-store/github          - Simplified: publish from GitHub URL (auth required via JWT or API key)
 * - POST /plugin-store/:pluginId/version         - Publish new version (auth required via JWT or API key)
 * - POST /plugin-store/version/finalize          - Finalize after JAR upload (auth required via JWT or API key)
 * - GET  /plugin-store/tags/popular    - Get popular tags
 * - GET  /plugin-store/health          - Health check
 * - GET  /plugin-store/doc             - Swagger UI documentation
 * - GET  /plugin-store/openapi         - OpenAPI specification (JSON)
 *
 * API Key Routes (requires JWT auth, not API keys):
 * - POST   /plugin-store/api-keys      - Create a new API key
 * - GET    /plugin-store/api-keys      - List user's API keys (masked)
 * - DELETE /plugin-store/api-keys/:keyId         - Revoke an API key
 *
 * Admin Routes (requires is_admin=true in JWT):
 * - POST   /plugin-store/admin/:pluginId/publish - Enable/disable a plugin
 * - DELETE /plugin-store/admin/:pluginId         - Delete a plugin
 * - POST   /plugin-store/admin/:pluginId/verify  - Verify/unverify a plugin
 */

import { OpenAPIHono } from "@hono/zod-openapi"
import { swaggerUI } from "@hono/swagger-ui"
import { cors } from "hono/cors"
import { createClient } from "@supabase/supabase-js"
import browse from "./routes/browse.ts"
import download from "./routes/download.ts"
import rating from "./routes/rating.ts"
import publish from "./routes/publish.ts"
import admin from "./routes/admin.ts"
import apiKeys from "./routes/api-keys.ts"
import type { PluginStoreContext } from "./types/context.ts"

const app = new OpenAPIHono<{ Variables: PluginStoreContext }>().basePath("/plugin-store")
const supabaseUrl = Deno.env.get("SUPABASE_URL") || ""
const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || ""

// Create Supabase client with service role key
const supabase = createClient(supabaseUrl, supabaseServiceKey)

// CORS configuration - allow BOSS client and localhost for development
app.use("*", cors({
  origin: ['boss://plugins', 'http://localhost:3000', 'https://risaboss.com'],
  allowMethods: ['POST', 'GET', 'DELETE', 'OPTIONS'],
  allowHeaders: ['Content-Type', 'Authorization', 'apikey', 'X-API-Key'],
  exposeHeaders: ['Content-Length'],
  maxAge: 600,
  credentials: true,
}))

// Inject Supabase client into context
app.use("*", async (ctx, next) => {
  ctx.set("supabase", supabase)
  await next()
})

// Health check endpoint - must be before other routes
app.get("/health", (ctx) => {
  return ctx.json({
    status: "healthy",
    service: "plugin-store",
    timestamp: new Date().toISOString()
  }, 200)
})

// Mount routes
// Note: Order matters - more specific routes first, wildcard routes last
app.route("/", admin)          // /admin/:pluginId/publish, /admin/:pluginId, /admin/:pluginId/verify
app.route("/", apiKeys)        // /api-keys (POST, GET, DELETE)
app.route("/version", publish) // /version/finalize
app.route("/", publish)        // /publish, /github, /:pluginId/version
app.route("/", download)       // /:pluginId/download, /:pluginId/download/:version
app.route("/", rating)         // /:pluginId/rate, /:pluginId/rating, /:pluginId/ratings
app.route("/", browse)         // /list, /search, /tags/popular, /:pluginId (wildcard last)

// OpenAPI documentation
app.doc("/openapi", {
  openapi: "3.1.0",
  info: {
    title: "BOSS Plugin Store API",
    version: "1.0.0",
    description: "Remote plugin store API for BOSS application. Enables browsing, downloading, rating, and publishing plugins."
  },
  servers: [
    {
      url: "https://api.risaboss.com/functions/v1/plugin-store",
      description: "Production server"
    },
    {
      url: "http://127.0.0.1:54321/functions/v1/plugin-store",
      description: "Local development server"
    }
  ],
  tags: [
    {
      name: "Browse",
      description: "Browse and search plugins"
    },
    {
      name: "Download",
      description: "Download plugin JAR files"
    },
    {
      name: "Rating",
      description: "Rate and review plugins"
    },
    {
      name: "Publish",
      description: "Publish plugins and versions (requires authentication via JWT or API key)"
    },
    {
      name: "API Keys",
      description: "Manage API keys for CI/CD publishing (requires JWT authentication)"
    },
    {
      name: "Admin",
      description: "Admin-only plugin management (requires admin role)"
    }
  ]
})

// Swagger UI
app.get("/doc", swaggerUI({ url: "/functions/v1/plugin-store/openapi" }))

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
