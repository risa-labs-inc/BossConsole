/**
 * `fluck-vault` — server entrypoint.
 *
 * All routing, verification and output lives in ./app.ts, kept in a separate module so the test
 * suite can import `createHandler` and drive it with fakes WITHOUT starting a listener or
 * reaching a database.
 *
 * `Deno.serve` is called UNCONDITIONALLY, matching the sibling functions. Do NOT gate it behind
 * `import.meta.main`: the Supabase edge runtime may load a function by importing its module
 * rather than running it as the main entrypoint, in which case `import.meta.main` is false and
 * the server never binds, so the function deploys and then 503s.
 *
 * Note what the service role key is used for here and what it is NOT used for. It calls three
 * RPCs that stage and describe opaque ciphertext. It touches `public.secrets` nowhere, and the
 * migration grants it nothing there beyond what `service_role` already had. This function
 * cannot read a secret, cannot decrypt one, and cannot open anything it writes.
 */
import { createClient } from "@supabase/supabase-js"
import {
  type ClaimedItem,
  createHandler,
  type CreateRequest,
  type StoreRequest,
  type StoreResult,
  type VaultRequestRow,
} from "./app.ts"

const client = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  { auth: { persistSession: false, autoRefreshToken: false } },
)

interface DescribeRow {
  ws: string
  purpose: string
  kind: string | null
  alias: string | null
  purchase_id: string | null
  merchant: string | null
  brand: string | null
  last4: string | null
  total_cents: number | null
  currency: string | null
}

Deno.serve(createHandler({
  env: (name) => Deno.env.get(name),
  now: () => Date.now(),
  // Route, purpose, workspace prefix, jti, outcome. Never a body, never a claim, never a
  // merchant, and above all never anything typed on the page.
  log: (line) => console.log(`[fluck-vault] ${line}`),

  /**
   * The non secret half of a request row, for rendering.
   *
   * Through an RPC rather than a table select so the "live, unspent, unexpired" predicate is
   * one definition in the database rather than a filter this process could get subtly wrong,
   * and so the columns a page can see are fixed by the function's return type. A select could
   * be widened by a careless `*` later; a return type cannot.
   */
  async describeRequest(jti: string): Promise<VaultRequestRow | null> {
    const { data, error } = await client.rpc("fluck_vault_describe", { p_jti: jti })
    if (error || !Array.isArray(data) || data.length === 0) return null
    const row = data[0] as DescribeRow
    if (row.purpose !== "vault" && row.purpose !== "cvv") return null
    const kind = row.kind === "password" || row.kind === "card" ? row.kind : null
    return {
      jti,
      ws: row.ws,
      purpose: row.purpose,
      kind,
      alias: row.alias,
      purchaseId: row.purchase_id,
      merchant: row.merchant,
      brand: row.brand,
      last4: row.last4,
      totalCents: row.total_cents,
      currency: row.currency,
    }
  },

  /**
   * Consume the link and stage the sealed value.
   *
   * One RPC, not a read and then a write. Two POSTs racing on one link are two concurrent
   * transactions, and only the conditional UPDATE inside the database can give one of them a
   * definite answer; a check followed by an insert from this process would let both through
   * and stage the value twice. The `ttlMinutes` the handler computed is deliberately NOT sent:
   * the database derives it from the request's own purpose, so a request body cannot buy itself
   * a longer life at rest.
   */
  async store(request: StoreRequest): Promise<StoreResult> {
    const { data, error } = await client.rpc("fluck_vault_store", {
      p_jti: request.jti,
      // PostgREST renders a bytea argument from a hex string with the `\x` prefix. The blob is
      // base64 on the wire from the browser and hex from here, which is only an encoding: what
      // reaches the column is the same bytes the page produced.
      p_ciphertext: "\\x" + toHex(request.ciphertext),
      p_cookie_hash: request.cookieHash,
    })
    if (error) return { outcome: "unavailable", kind: null }
    if (data === "card" || data === "password" || data === "cvv") {
      return { outcome: "stored", kind: data }
    }
    return { outcome: "gone", kind: null }
  },

  /**
   * Write the request row for a link the DGX is about to sign.
   *
   * Reached from `POST /requests`, which is authenticated by an Ed25519 signature rather than
   * by a Supabase credential: the DGX deliberately holds no service role key, so this process
   * is the only thing in the system with one, and all it can do with it is move opaque blobs.
   */
  async createRequest(request: CreateRequest): Promise<boolean> {
    const { data, error } = await client.rpc("fluck_vault_create", {
      p_jti: request.jti,
      p_ws: request.ws,
      p_purpose: request.purpose,
      p_kind: request.kind,
      p_alias: request.alias,
      p_purchase_id: request.purchaseId,
      p_merchant: request.merchant,
      p_brand: request.brand,
      p_last4: request.last4,
      p_total_cents: request.totalCents,
      p_currency: request.currency,
      p_expires_at: new Date(request.expiresAt * 1000).toISOString(),
    })
    if (error) return false
    return data === true
  },

  /**
   * Drain one workspace's queue.
   *
   * The RPC returns and DELETES in one statement, so a blob is in flight or in the DGX's
   * memory, never both. `ciphertext` arrives from PostgREST as `\x…` hex and goes out as
   * base64, which is transport: the bytes are the ones the page produced and nothing in this
   * process can open them.
   */
  async claimInbox(ws: string): Promise<ClaimedItem[]> {
    const { data, error } = await client.rpc("fluck_vault_claim", { p_ws: ws })
    if (error || !Array.isArray(data)) return []
    return data.map((row: Record<string, unknown>) => ({
      id: String(row.id),
      jti: String(row.jti),
      purpose: row.purpose === "cvv" ? "cvv" as const : "vault" as const,
      kind: typeof row.kind === "string" ? row.kind : null,
      alias: typeof row.alias === "string" ? row.alias : null,
      purchaseId: typeof row.purchase_id === "string" ? row.purchase_id : null,
      ciphertext: fromHex(String(row.ciphertext)),
      createdAt: String(row.created_at),
    }))
  },
}))

/** PostgREST `\x…` hex to base64. Transport only; this process cannot open the bytes. */
function fromHex(value: string): string {
  const hex = value.startsWith("\\x") ? value.slice(2) : value
  let binary = ""
  for (let i = 0; i + 1 < hex.length; i += 2) {
    binary += String.fromCharCode(parseInt(hex.slice(i, i + 2), 16))
  }
  return btoa(binary)
}

/** base64 to hex. The blob is opaque at both ends; this is transport, not a transformation. */
function toHex(base64: string): string {
  const binary = atob(base64)
  let out = ""
  for (let i = 0; i < binary.length; i++) {
    out += binary.charCodeAt(i).toString(16).padStart(2, "0")
  }
  return out
}
