/**
 * session-fork Edge Function - server entrypoint. The logic lives in ./app.ts so the tests can
 * drive it without a listener. Deno.serve is unconditional, as in the sibling functions: the edge
 * runtime may import this module rather than run it as main.
 */
import { createClient } from "@supabase/supabase-js"
import { createHandler } from "./app.ts"

const url = Deno.env.get("SUPABASE_URL") ?? ""
const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? ""

Deno.serve(createHandler(() => createClient(url, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } })))
