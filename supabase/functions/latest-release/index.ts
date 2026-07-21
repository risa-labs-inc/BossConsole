/**
 * Latest-Release Edge Function — server entrypoint.
 *
 * All routing / release lookup / asset selection lives in ./app.ts, kept in a separate
 * module so the test suite (tests/latest-release.test.ts) can import `app` and drive it
 * via app.request() WITHOUT starting a listener.
 *
 * Deno.serve is called UNCONDITIONALLY here (matching the sibling redirect / passkey /
 * plugin-store functions). Do NOT gate it behind `import.meta.main`: the Supabase edge
 * runtime may load a function by importing its module rather than running it as the main
 * entrypoint, in which case `import.meta.main` is false and the server would never bind —
 * the function would deploy but 503.
 */
import { app } from "./app.ts"

Deno.serve(app.fetch)
