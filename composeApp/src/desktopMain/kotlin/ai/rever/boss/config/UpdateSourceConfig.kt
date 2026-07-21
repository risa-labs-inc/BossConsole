package ai.rever.boss.config

/**
 * Configuration for the desktop app's update source (Supabase primary, GitHub backup).
 *
 * Values resolve through [ConfigLoader] (env var → system property → local.properties →
 * default). Supabase URL + anon key are reused from [SupabaseClientConfig].
 *
 * - `BOSS_UPDATE_APP_ID`        : discriminator in the shared `app_releases` table (default "boss")
 * - `BOSS_UPDATE_BUCKET`        : Supabase Storage bucket holding release binaries
 * - `BOSS_UPDATE_PRIMARY_SOURCE`: "supabase" (default, with GitHub fallback),
 *                                 "github" (GitHub only — legacy behavior),
 *                                 "supabase-only" (Supabase with no fallback — for testing)
 */
object UpdateSourceConfig {
    val appId: String by lazy {
        ConfigLoader.getConfig("BOSS_UPDATE_APP_ID") ?: "boss"
    }

    val bucket: String by lazy {
        ConfigLoader.getConfig("BOSS_UPDATE_BUCKET") ?: "app-releases"
    }

    val primarySource: String by lazy {
        (ConfigLoader.getConfig("BOSS_UPDATE_PRIMARY_SOURCE") ?: "supabase").lowercase()
    }

    /** PostgREST base, e.g. https://api.risaboss.com/rest/v1 */
    val restBaseUrl: String
        get() = "${SupabaseClientConfig.url.trimEnd('/')}/rest/v1"

    /** Public Storage object base, e.g. https://api.risaboss.com/storage/v1/object/public/<bucket> */
    val storagePublicBaseUrl: String
        get() = "${SupabaseClientConfig.url.trimEnd('/')}/storage/v1/object/public/$bucket"

    val supabaseUrl: String get() = SupabaseClientConfig.url
    val supabaseAnonKey: String get() = SupabaseClientConfig.anonKey
}
