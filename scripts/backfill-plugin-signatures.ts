#!/usr/bin/env -S deno run --allow-env --allow-net
/**
 * One-time backfill: sign every plugin_versions row that has a sha256 but no
 * signature, using the store signing key. After this completes, the host's
 * unsigned-plugin warning path only triggers for genuinely unsigned uploads
 * and enforcement can be flipped to hard-fail.
 *
 * The signature is RSASSA-PKCS1-v1_5/SHA-256 over the UTF-8 bytes of the
 * canonical version anchor `pluginId|version|sha256` (lowercase hex digest)
 * — the same scheme as the publish function
 * (supabase/functions/plugin-store/utils/signing.ts). Binding identity and
 * version prevents substitution among store-signed artifacts.
 *
 * `--re-sign-all` re-signs EVERY row with a sha256 (not just unsigned ones)
 * — required when the signing scheme or key changes.
 *
 * Provenance caveat: the backfill signs the STORED sha256 as-is. For
 * GitHub-path publishes that hash was computed server-side, but rows
 * finalized through the API-key upload route before server-side recompute
 * shipped carry a client-supplied hash the store never bound to bytes. The
 * host still recomputes the local JAR hash at install (a swapped JAR is
 * caught either way), but to make the signature attest server-observed
 * bytes, run `--verify-bytes` afterwards: it re-downloads every signed
 * version's JAR, recomputes the hash, and reports any row whose stored
 * sha256 does not match the actual bytes (a mismatch is an integrity
 * incident to investigate, not auto-fix).
 *
 * Usage:
 *   SUPABASE_URL=https://api.risaboss.com \
 *   SUPABASE_SERVICE_ROLE_KEY=... \
 *   PLUGIN_SIGNING_PRIVATE_KEY="$(cat ~/.boss-secrets/plugin-signing/plugin-signing-private.pem)" \
 *   deno run --allow-env --allow-net scripts/backfill-plugin-signatures.ts [--dry-run|--verify-bytes]
 */

const SUPABASE_URL = Deno.env.get('SUPABASE_URL')
const SERVICE_KEY = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')
const PRIVATE_KEY_PEM = Deno.env.get('PLUGIN_SIGNING_PRIVATE_KEY')
const DRY_RUN = Deno.args.includes('--dry-run')
const VERIFY_BYTES = Deno.args.includes('--verify-bytes')
const RE_SIGN_ALL = Deno.args.includes('--re-sign-all')

if (!SUPABASE_URL || !SERVICE_KEY || !PRIVATE_KEY_PEM) {
  console.error('Missing SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, or PLUGIN_SIGNING_PRIVATE_KEY')
  Deno.exit(1)
}

const body = PRIVATE_KEY_PEM
  .replace('-----BEGIN PRIVATE KEY-----', '')
  .replace('-----END PRIVATE KEY-----', '')
  .replace(/\s/g, '')
const der = Uint8Array.from(atob(body), (c) => c.charCodeAt(0))
const key = await crypto.subtle.importKey(
  'pkcs8',
  der,
  { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
  false,
  ['sign'],
)

// Must stay in lockstep with versionAnchor in utils/signing.ts and
// PluginStoreTrust.versionAnchor in BossConsole.
function versionAnchor(pluginId: string, version: string, sha256Hex: string): string {
  return `${pluginId}|${version}|${sha256Hex.toLowerCase()}`
}

async function sign(pluginId: string, version: string, sha256Hex: string): Promise<string> {
  const data = new TextEncoder().encode(versionAnchor(pluginId, version, sha256Hex))
  const sig = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, data)
  return btoa(String.fromCharCode(...new Uint8Array(sig)))
}

const headers = {
  apikey: SERVICE_KEY,
  Authorization: `Bearer ${SERVICE_KEY}`,
  'Content-Type': 'application/json',
}

// --verify-bytes: re-download every signed version's JAR, recompute its
// SHA-256, and report rows whose stored hash doesn't match the bytes.
if (VERIFY_BYTES) {
  let ok = 0
  const mismatches: string[] = []
  const failures: string[] = []
  let lastId = ''
  for (;;) {
    const cursor = lastId ? `&id=gt.${lastId}` : ''
    const resp = await fetch(
      `${SUPABASE_URL}/rest/v1/plugin_versions?signature=not.is.null&select=id,version,sha256,jar_path&order=id&limit=100${cursor}`,
      { headers },
    )
    if (!resp.ok) {
      console.error(`Fetch failed: ${resp.status} ${await resp.text()}`)
      Deno.exit(1)
    }
    const rows: { id: string; version: string; sha256: string; jar_path: string }[] = await resp.json()
    if (rows.length === 0) break

    for (const row of rows) {
      // The plain object endpoint serves private-bucket objects when the
      // service-role key is in the Authorization header (spot-verified live:
      // 200 + hash match). If storage ever stops honoring that, switch to
      // /storage/v1/object/authenticated/plugin-jars/… — a 404/400 here is
      // reported as "unfetchable", which means NOT verified.
      const url = row.jar_path.startsWith('https://')
        ? row.jar_path
        : `${SUPABASE_URL}/storage/v1/object/plugin-jars/${row.jar_path}`
      try {
        const jarResp = await fetch(url, {
          headers: row.jar_path.startsWith('https://') ? {} : headers,
        })
        if (!jarResp.ok || !jarResp.body) {
          failures.push(`${row.id} (v${row.version}): fetch ${jarResp.status}`)
          await jarResp.body?.cancel()
          continue
        }
        // Buffers the JAR (fine for a local one-off; largest plugin jars are
        // ~100MB) — WebCrypto digest needs the full input.
        const digest = new Uint8Array(
          await crypto.subtle.digest('SHA-256', await new Response(jarResp.body).arrayBuffer()),
        )
        const actual = Array.from(digest).map((b) => b.toString(16).padStart(2, '0')).join('')
        if (actual === row.sha256.toLowerCase()) {
          ok++
        } else {
          mismatches.push(`${row.id} (v${row.version}): stored=${row.sha256} actual=${actual}`)
        }
      } catch (e) {
        failures.push(`${row.id} (v${row.version}): ${(e as Error).message}`)
      }
    }
    lastId = rows[rows.length - 1].id
    console.log(`verified ${ok} so far (${mismatches.length} mismatches, ${failures.length} fetch failures)`)
  }

  console.log(`\nverify-bytes done: ${ok} match, ${mismatches.length} MISMATCH, ${failures.length} unfetchable`)
  for (const m of mismatches) console.error(`MISMATCH ${m}`)
  for (const f of failures) console.warn(`unfetchable ${f}`)
  Deno.exit(mismatches.length > 0 ? 2 : 0)
}

// Keyset-paginate over unsigned versions ordered by id. Unlike offset paging
// (which breaks as signed rows drop out of the filter) or refetch-page-0
// (which loops forever on skip-only rows and double-counts them), advancing
// past the last seen id visits every row exactly once regardless of whether
// it was signed or skipped — in dry-run and real mode alike.
let updated = 0
let skipped = 0
let lastId = ''
const filter = RE_SIGN_ALL ? '' : 'signature=is.null&'
for (;;) {
  const cursor = lastId ? `&id=gt.${lastId}` : ''
  const resp = await fetch(
    `${SUPABASE_URL}/rest/v1/plugin_versions?${filter}select=id,version,sha256,plugins(plugin_id)&order=id&limit=200${cursor}`,
    { headers },
  )
  if (!resp.ok) {
    console.error(`Fetch failed: ${resp.status} ${await resp.text()}`)
    Deno.exit(1)
  }
  const rows: { id: string; version: string; sha256: string | null; plugins: { plugin_id: string } | null }[] =
    await resp.json()
  if (rows.length === 0) break

  for (const row of rows) {
    if (!row.sha256 || row.sha256.length !== 64) {
      console.warn(`skip ${row.id} (v${row.version}): missing/malformed sha256`)
      skipped++
      continue
    }
    if (!row.plugins?.plugin_id) {
      console.warn(`skip ${row.id} (v${row.version}): no parent plugin row`)
      skipped++
      continue
    }
    const signature = await sign(row.plugins.plugin_id, row.version, row.sha256)
    if (DRY_RUN) {
      console.log(`[dry-run] would sign ${row.id} (${row.plugins.plugin_id} v${row.version})`)
      updated++
      continue
    }
    const upd = await fetch(`${SUPABASE_URL}/rest/v1/plugin_versions?id=eq.${row.id}`, {
      method: 'PATCH',
      headers,
      body: JSON.stringify({ signature }),
    })
    if (!upd.ok) {
      console.error(`update failed for ${row.id}: ${upd.status} ${await upd.text()}`)
      Deno.exit(1)
    }
    updated++
  }

  lastId = rows[rows.length - 1].id
}

console.log(`done: ${updated} signed${DRY_RUN ? ' (dry-run)' : ''}, ${skipped} skipped`)
