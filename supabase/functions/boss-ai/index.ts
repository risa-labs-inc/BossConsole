import { createClient } from "@supabase/supabase-js"
import { createHandler } from "./app.ts"

const client = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  {
    auth: { persistSession: false, autoRefreshToken: false },
  },
)
Deno.serve(createHandler({
  async sessionUser(token) {
    const { data, error } = await client.auth.getUser(token)
    return error || !data.user || data.user.is_anonymous ? null : data.user.id
  },
  async rpc(name, params) {
    const { data, error } = await client.rpc(name, params)
    if (error) {
      // SQLSTATE only: PostgREST messages/details can contain input or secrets.
      const code = /^[A-Z0-9]{5}$/.test(error.code ?? "") ? error.code : "unknown"
      console.error(JSON.stringify({ event: "boss_ai_database_error", code }))
      throw new Error("BOSS AI database operation failed")
    }
    return data
  },
  secret: (name) => Deno.env.get(name),
  fetch,
}))
