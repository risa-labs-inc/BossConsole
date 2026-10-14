/**
 * Fluck Web Edge Function - server entrypoint. Routing lives in ./app.ts so tests can import
 * `app` without starting a listener. Deno.serve is unconditional (see live-sessions/index.ts).
 */
import { app } from "./app.ts"

Deno.serve(app.fetch)
