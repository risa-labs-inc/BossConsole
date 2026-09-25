/**
 * `fluck-oauth` - server entrypoint.
 *
 * All routing, verification and output lives in ./app.ts, kept in a separate module so the
 * test suite can import `createHandler` and drive it with fakes WITHOUT starting a listener or
 * reaching a database.
 *
 * `Deno.serve` is called UNCONDITIONALLY, matching the sibling functions. Do NOT gate it behind
 * `import.meta.main`: the Supabase edge runtime may load a function by importing its module
 * rather than running it as the main entrypoint, in which case `import.meta.main` is false and
 * the server never binds, so the function deploys and then 503s.
 */
import { createClient } from "@supabase/supabase-js"
import { createHandler, type NonceClaim, type StoreRequest } from "./app.ts"

const client = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  { auth: { persistSession: false, autoRefreshToken: false } },
)

Deno.serve(createHandler({
  env: (name) => Deno.env.get(name),
  fetch,
  now: () => Date.now(),
  // Route and outcome only. Never a token, a code, a state or an email.
  log: (line) => console.log(`[fluck-oauth] ${line}`),

  /**
   * One nonce, claimed once.
   *
   * The whole decision is inside the database function, not here: two callbacks racing on the
   * same link are two concurrent transactions, and only an INSERT that the primary key
   * arbitrates gives one of them a definite answer. A check followed by an insert from this
   * process would let both through.
   */
  async claimNonce(nonce: string, expiresAtSeconds: number): Promise<NonceClaim> {
    const { data, error } = await client.rpc("fluck_oauth_claim_nonce", {
      p_nonce: nonce,
      p_expires_at: new Date(expiresAtSeconds * 1000).toISOString(),
    })
    if (error) return "unavailable"
    return data === true ? "claimed" : "replay"
  },

  /**
   * Write the refresh token as the BOSS user named in the state.
   *
   * Through an RPC rather than a client side insert, for two reasons that both have to hold at
   * once: the value must be encrypted by `public.encrypt_text` inside the database, where the
   * master key lives, and the replace of any earlier grant for the same key must happen in the
   * SAME transaction as the insert. A delete followed by an insert from here would leave a
   * window in which the plugin's poll finds nothing and gives up.
   */
  async storeRefreshToken(request: StoreRequest): Promise<boolean> {
    const { data, error } = await client.rpc("fluck_oauth_store_secret", {
      p_user_id: request.userId,
      p_website: request.website,
      p_username: request.username,
      p_secret: request.refreshToken,
      p_notes: request.notes,
    })
    return !error && typeof data === "string" && data.length > 0
  },
}))
