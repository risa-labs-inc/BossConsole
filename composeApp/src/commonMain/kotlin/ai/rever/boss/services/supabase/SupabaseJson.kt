package ai.rever.boss.services.supabase

import kotlinx.serialization.json.Json

/**
 * The decoder for every Supabase RPC response in this package.
 *
 * `ignoreUnknownKeys` is not a convenience, it is a requirement of how BOSS ships.
 * The database is migrated ahead of the desktop app and installed copies keep talking
 * to it, so a client will meet columns it does not model. The kotlinx default is strict
 * and treats those as a hard error - and because these RPCs return LISTS, one unmodelled
 * key does not drop a field, it throws and takes the whole page with it.
 *
 * That is not hypothetical. The organisation migration extended four secret RPCs at once
 * (`get_user_secrets`, `search_user_secrets`, `get_user_secrets_with_shared`,
 * `get_secret_shares`) and every already-installed build started answering
 * "Encountered an unknown key 'org_id'". The secret panels rendered empty with nothing
 * but a WARN in the log.
 *
 * It lives here rather than inside one service on purpose: the hazard is a property of
 * the DEPLOYMENT, not of any single file, so every service that decodes a server response
 * is exposed to it equally. `RoleService` and `RoleCreationService` had not been bitten
 * only because `roles` and `permissions` had not been extended yet.
 *
 * ## What this buys, and what it costs
 *
 * Additive schema changes become safe. In exchange, **renames become silent** for any
 * field carrying a default: rename `metadata` server-side and decoding now succeeds with
 * the 2FA metadata quietly missing, rather than failing loudly.
 *
 * That is the right trade here - a loud failure means every secret vanishes - but it makes
 * "additive only, never rename" a contract the database side has to keep. Renaming a
 * projected column is a breaking change for every installed build and needs the field kept
 * as an alias until those builds age out. Do not read this instance as blanket tolerance
 * of schema drift.
 */
internal val supabaseJson = Json { ignoreUnknownKeys = true }
