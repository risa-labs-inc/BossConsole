package ai.rever.boss.services.auth

import kotlinx.coroutines.CoroutineScope

/**
 * Starts the `BOSS_SESSION_IMPORT` hook in [scope]: while signed out, a session is adopted from a
 * single-use refresh-token file. A no-op when the variable is unset. Call after the Supabase client
 * is initialised.
 */
internal expect fun startSessionFileImport(scope: CoroutineScope)
