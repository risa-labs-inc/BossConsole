/**
 * Passkey Edge Function - server entrypoint.
 *
 * All routing lives in ./app.ts, kept in a separate module so the test suite
 * (tests/app.test.ts) can import `app` and drive it via app.request() WITHOUT
 * starting a listener.
 *
 * Deno.serve is called UNCONDITIONALLY (matching the sibling latest-release /
 * redirect / crash-report / organisation / plugin-store functions). Do NOT gate
 * it behind `import.meta.main`: the Supabase edge runtime may load a function
 * by importing its module rather than running it as the main entrypoint, in
 * which case `import.meta.main` is false and the server would never bind - the
 * function would deploy but 503.
 *
 * Route inventory (all mounted under /passkey in app.ts):
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
import { app } from "./app.ts"

Deno.serve(app.fetch)
