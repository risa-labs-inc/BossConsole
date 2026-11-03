/** Max Web Edge Function - server entrypoint. The alias lives in ./app.ts so tests can import it. */
import { maxWebFetch } from "./app.ts"

Deno.serve(maxWebFetch)
