package ai.rever.boss.services.supabase

import com.russhwolf.settings.Settings
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SettingsCodeVerifierCache
import io.github.jan.supabase.auth.SettingsSessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the security contract of [EncryptedSessionSettings] (BossConsole#846): the session
 * tokens must be the one thing in `~/.boss` that is never written as plaintext, and Auth
 * must actually receive this backend — the default supabase-kt one is
 * `java.util.prefs.Preferences`, a library-owned plaintext store outside the app's data
 * directory.
 *
 * Most cases build the store over explicit files in a temp directory rather than going
 * through the factory, so the crypto and on-disk format are pinned independently of where
 * production puts the files. The wiring cases then pin that production wiring: one by
 * execution against the built client, one by source — the same "read the source" stance
 * `SupabaseWiringTest` documents for services with no injection seam. The migration cases
 * seed the legacy `Preferences` node with the literal URL-qualified key names supabase-kt
 * 3.8.0 actually writes (`sb-<normalized-url>-session`), never a derivation shared with
 * the production code, so they break loudly if either side drifts from the real shape.
 */
class EncryptedSessionSettingsTest {
    @TempDir
    lateinit var temporary: File

    private fun newStore(): EncryptedSessionSettings =
        EncryptedSessionSettings(
            storeFile = File(temporary, STORE_FILE_NAME),
            keyFile = File(temporary, KEY_FILE_NAME),
        )

    /** A fresh instance over the same files — what a restart would read. */
    private fun reopenedStore(): EncryptedSessionSettings = newStore()

    @Test
    fun `another process publishing an empty key is awaited without replacing its key`() {
        val key = File(temporary, KEY_FILE_NAME)
        val source = File(requireNotNull(javaClass.getResource("/SessionKeyLockHolder.java")).toURI())
        val javaExecutable = File(System.getProperty("java.home"), "bin/java").absolutePath
        val process =
            ProcessBuilder(javaExecutable, source.absolutePath, key.absolutePath)
                .redirectErrorStream(true)
                .start()
        val workers =
            java.util.concurrent.Executors
                .newFixedThreadPool(2)
        try {
            val ready = workers.submit<String> { process.inputStream.bufferedReader().readLine() }
            assertEquals("locked", ready.get(30, java.util.concurrent.TimeUnit.SECONDS))
            val starting = java.util.concurrent.CountDownLatch(1)
            val pending =
                workers.submit<EncryptedSessionSettings> {
                    starting.countDown()
                    newStore()
                }
            assertTrue(starting.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFailsWith<java.util.concurrent.TimeoutException> {
                pending.get(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            assertEquals(0L, key.length())
            process.outputStream.write(1)
            process.outputStream.flush()
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            val settings = pending.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(Base64.getEncoder().encodeToString(ByteArray(32)), key.readText())
            settings.putString("token", "shared-session")
            assertEquals("shared-session", reopenedStore().getStringOrNull("token"))
        } finally {
            process.destroyForcibly()
            workers.shutdownNow()
        }
    }

    @Test
    fun `concurrent constructors share a key even when replacing an empty key`() {
        for (emptyKey in listOf(false, true)) {
            val directory = File(temporary, "concurrent-$emptyKey").apply { mkdirs() }
            val key = File(directory, KEY_FILE_NAME)
            if (emptyKey) key.createNewFile()
            val workers =
                java.util.concurrent.Executors
                    .newFixedThreadPool(8)
            val start = java.util.concurrent.CountDownLatch(1)
            try {
                val stores =
                    (1..8).map { index ->
                        workers.submit<EncryptedSessionSettings> {
                            start.await()
                            EncryptedSessionSettings(File(directory, "store-$index"), key)
                        }
                    }
                start.countDown()
                stores.forEachIndexed { index, future ->
                    val store = future.get(10, java.util.concurrent.TimeUnit.SECONDS)
                    store.putString("token", "test-session-$index")
                    val reopened = EncryptedSessionSettings(File(directory, "store-${index + 1}"), key)
                    assertEquals("test-session-$index", reopened.getStringOrNull("token"))
                }
            } finally {
                workers.shutdownNow()
            }
        }
    }

    @Test
    fun `session strings roundtrip and survive reopening the store`() {
        val settings = newStore()
        settings.putString(SettingsSessionManager.SETTINGS_KEY, sessionJson)

        assertEquals(sessionJson, settings.getStringOrNull(SettingsSessionManager.SETTINGS_KEY))
        // A fresh instance can only produce this from disk, so persistence itself roundtrips.
        assertEquals(sessionJson, reopenedStore().getStringOrNull(SettingsSessionManager.SETTINGS_KEY))
    }

    @Test
    fun `typed settings values ride the encrypted roundtrip`() {
        val settings = newStore()
        settings.putInt(SettingsCodeVerifierCache.SETTINGS_KEY, 42)
        settings.putBoolean("flag", true)
        settings.putLong("stamp", 1_730_000_000_000L)
        settings.putDouble("ratio", 0.25)
        settings.putFloat("weight", 1.5f)

        assertEquals(42, settings.getInt(SettingsCodeVerifierCache.SETTINGS_KEY, -1))
        assertEquals(42, settings.getIntOrNull(SettingsCodeVerifierCache.SETTINGS_KEY))
        assertTrue(settings.getBoolean("flag", false))
        assertEquals(1_730_000_000_000L, settings.getLong("stamp", 0L))
        assertEquals(0.25, settings.getDouble("ratio", -1.0))
        assertEquals(1.5f, settings.getFloat("weight", -1f))
        // Absent keys report defaults, never a decrypted mix-up.
        assertEquals(7, settings.getInt("missing", 7))
        assertNull(settings.getIntOrNull("missing"))
        assertEquals(
            setOf(SettingsCodeVerifierCache.SETTINGS_KEY, "flag", "stamp", "ratio", "weight"),
            settings.keys,
        )
        assertEquals(5, settings.size)

        val reopened = reopenedStore()
        assertEquals(42, reopened.getInt(SettingsCodeVerifierCache.SETTINGS_KEY, -1))
        assertTrue(reopened.getBoolean("flag", false))
    }

    @Test
    fun `the persisted bytes are ciphertext, not the session`() {
        newStore().putString(SettingsSessionManager.SETTINGS_KEY, sessionJson)
        val raw = File(temporary, STORE_FILE_NAME).readText()

        assertFalse(raw.contains(ACCESS_TOKEN), "the access token must not survive in the clear")
        assertFalse(raw.contains(REFRESH_TOKEN), "the refresh token must not survive in the clear")
        assertFalse(raw.contains("refresh_token"), "field names must not survive in the clear")
        assertFalse(
            raw.contains(SettingsSessionManager.SETTINGS_KEY),
            "even the settings key name must not survive in the clear",
        )
        assertFalse(
            raw.contains(File(temporary, KEY_FILE_NAME).readText()),
            "the wrapping key must not appear inside the store it wraps",
        )
    }

    @Test
    fun `the key file is created owner-only on posix filesystems`() {
        newStore().putString(SettingsSessionManager.SETTINGS_KEY, sessionJson)
        val keyFile = File(temporary, KEY_FILE_NAME)
        assertTrue(keyFile.isFile, "the key must exist before the first session write")

        // The #860 hardening bar: state files are owner-only, and the AES key is the one
        // file where that matters most. Skipped where permissions do not exist (Windows).
        if (Files.getFileAttributeView(keyFile.toPath(), PosixFileAttributeView::class.java) != null) {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(keyFile.toPath()),
                "the session encryption key must be 0600",
            )
        }
    }

    @Test
    fun `an undecodable key file regenerates instead of failing construction`() {
        newStore().putString(SettingsSessionManager.SETTINGS_KEY, sessionJson)
        // The shape a truncated write or half-synced home directory leaves behind.
        File(temporary, KEY_FILE_NAME).writeText("not-valid-base64!!")

        // Review #3 on #918: construction used to throw and park the client in
        // AuthState.Error with no recovery. The store is a cache of a session: regenerate
        // the key, read the orphaned ciphertext as empty, and have the user sign in again.
        val after = reopenedStore()
        assertEquals(0, after.size, "the orphaned session must read as empty, not garbage")
        assertNull(after.getStringOrNull(SettingsSessionManager.SETTINGS_KEY))
        after.putString(SettingsSessionManager.SETTINGS_KEY, sessionJson)
        assertEquals(sessionJson, reopenedStore().getStringOrNull(SettingsSessionManager.SETTINGS_KEY))
    }

    @Test
    fun `a wrong-length key file regenerates instead of failing construction`() {
        newStore().putString(SettingsSessionManager.SETTINGS_KEY, sessionJson)
        File(temporary, KEY_FILE_NAME).writeText(Base64.getEncoder().encodeToString(ByteArray(16)))

        val after = reopenedStore()
        assertEquals(0, after.size)
        assertNull(after.getStringOrNull(SettingsSessionManager.SETTINGS_KEY))
    }

    @Test
    fun `a tampered store decrypts to nothing instead of garbage`() {
        newStore().putString(SettingsSessionManager.SETTINGS_KEY, sessionJson)
        val storeFile = File(temporary, STORE_FILE_NAME)
        val line = storeFile.readText().lines().first()
        val separatorAt = line.indexOf(':')
        // Flip one ciphertext character while keeping the base64 shape, so the change
        // reaches the GCM tag check rather than the line parser.
        val originalFirst = line[separatorAt + 1]
        val replacement = if (originalFirst == 'Z') 'Y' else 'Z'
        storeFile.writeText(line.substring(0, separatorAt + 1) + replacement + line.substring(separatorAt + 2))

        assertNull(reopenedStore().getStringOrNull(SettingsSessionManager.SETTINGS_KEY))
    }

    @Test
    fun `remove and clear purge the state from disk`() {
        val settings = newStore()
        settings.putString(SettingsSessionManager.SETTINGS_KEY, sessionJson)
        settings.putString(SettingsCodeVerifierCache.SETTINGS_KEY, "pkce-verifier-secret")

        settings.remove(SettingsSessionManager.SETTINGS_KEY)
        assertNull(settings.getStringOrNull(SettingsSessionManager.SETTINGS_KEY))
        assertEquals("pkce-verifier-secret", settings.getStringOrNull(SettingsCodeVerifierCache.SETTINGS_KEY))

        settings.clear()
        assertNull(settings.getStringOrNull(SettingsCodeVerifierCache.SETTINGS_KEY))
        assertEquals(0, settings.size)
        val reopened = reopenedStore()
        assertEquals(0, reopened.size)
        assertFalse(
            File(temporary, STORE_FILE_NAME).readText().contains("pkce-verifier-secret"),
            "a cleared store must not keep the secrets on disk",
        )
    }

    @Test
    fun `a removal whose persist fails destroys the store instead of failing open`() {
        val settings = newStore()
        settings.putString(SettingsSessionManager.SETTINGS_KEY, sessionJson)
        val storeFile = File(temporary, STORE_FILE_NAME)

        // Make the revoking rewrite impossible: the atomic temp+move cannot replace a
        // directory, so persist() raises IOException - the shape a full or read-only
        // filesystem produces.
        storeFile.delete()
        storeFile.mkdirs()
        assertTrue(storeFile.isDirectory)

        assertFailsWith<IOException> { settings.remove(SettingsSessionManager.SETTINGS_KEY) }
        // Review #2 on #918: sign-out must never fail open. The removed token may not stay
        // on disk for the next start to auto-load, so the store is destroyed, not kept.
        assertFalse(storeFile.exists(), "the failed removal must destroy the store file")
        assertNull(settings.getStringOrNull(SettingsSessionManager.SETTINGS_KEY))
    }

    @Test
    fun `auth receives the encrypted backend as its session store`() {
        val injected = newStore()
        try {
            SupabaseConfig.initialize("https://bossconsole.test.supabase.co", "test-anon-key", injected)
            val auth = SupabaseConfig.client.auth

            val sessionManager = auth.sessionManager
            assertTrue(
                sessionManager is SettingsSessionManager,
                "Auth must persist through a Settings-backed session manager",
            )
            assertSame(injected, (sessionManager as SettingsSessionManager).wrappedSettings())

            val codeVerifierCache = auth.codeVerifierCache
            assertTrue(codeVerifierCache is SettingsCodeVerifierCache)
            assertSame(injected, (codeVerifierCache as SettingsCodeVerifierCache).wrappedSettings())
        } finally {
            SupabaseConfig.clear()
        }
    }

    @Test
    fun `the production client defaults to the encrypted backend`() {
        val source = sourceFile("SupabaseConfig.kt").readText()
        assertTrue(
            source.contains("sessionSettings: Settings? = null"),
            "initialize must default to building the encrypted backend itself",
        )
        assertTrue(
            source.contains("createEncryptedSessionSettings(supabaseUrl = fullUrl)"),
            "the legacy migration must derive the URL-qualified keys from the initialized URL",
        )
        assertTrue(
            source.contains("sessionManager = SettingsSessionManager(sessionBackend)"),
            "Auth must persist its session through the encrypted backend",
        )
        assertTrue(
            source.contains("codeVerifierCache = SettingsCodeVerifierCache(sessionBackend)"),
            "Auth must persist its PKCE verifier through the encrypted backend",
        )
        assertFalse(
            Regex("""SettingsSessionManager\(\s*\)""").containsMatchIn(source),
            "a no-arg SettingsSessionManager would silently fall back to java.util.prefs",
        )
        assertFalse(
            Regex("""SettingsCodeVerifierCache\(\s*\)""").containsMatchIn(source),
            "a no-arg SettingsCodeVerifierCache would silently fall back to java.util.prefs",
        )
    }

    @Test
    fun `the supabase-kt defaults match the migration's key and node assumptions`() {
        // The migration fixtures and legacySettingsKeyNames are two independent copies of
        // the same belief about supabase-kt 3.8.0: default settings keys are URL-qualified
        // and the default Settings() resolves to Preferences.userRoot(). This pins both
        // against the library itself, so a dependency bump that changes either breaks here
        // instead of silently leaving plaintext tokens behind again (review #1 on #918).
        val client =
            createSupabaseClient(supabaseUrl = LEGACY_SUPABASE_URL, supabaseKey = "test-anon-key") {
                install(Auth)
            }
        val sessionManager = client.auth.sessionManager as SettingsSessionManager
        val codeVerifierCache = client.auth.codeVerifierCache as SettingsCodeVerifierCache

        assertEquals(
            LEGACY_SESSION_KEY,
            sessionManager.reflectedString("key"),
            "supabase-kt's default session key must stay the URL-qualified shape the migration probes",
        )
        assertEquals(
            LEGACY_VERIFIER_KEY,
            codeVerifierCache.reflectedString("key"),
            "supabase-kt's default verifier key must stay the URL-qualified shape the migration probes",
        )
        assertEquals(
            Preferences.userRoot().absolutePath(),
            preferencesNodeBehind(sessionManager.wrappedSettings()).absolutePath(),
            "the default Settings() must keep resolving to the userRoot node the migration reads",
        )
    }

    @Test
    fun `legacy plaintext sessions migrate in and the plaintext copy is destroyed`() {
        val legacy = Preferences.userNodeForPackage(EncryptedSessionSettingsTest::class.java)
        legacy.clear()
        // Seeded with the REAL 3.8.0 key shapes, as literals: supabase-kt's defaults persist
        // under URL-qualified names, so probing the bare SETTINGS_KEY constants (as the
        // first cut of this migration did) would leave production tokens behind. Literals,
        // not a derivation call, so the fixture cannot silently follow the code under test.
        legacy.put(LEGACY_SESSION_KEY, sessionJson)
        legacy.put(LEGACY_VERIFIER_KEY, "legacy-verifier-value")
        try {
            val settings =
                createEncryptedSessionSettings(
                    storeDirectory = temporary,
                    legacyStore = legacy,
                    supabaseUrl = LEGACY_SUPABASE_URL,
                )

            // Auth reads the encrypted store through the bare SETTINGS_KEY constants
            // (SettingsSessionManager over the injected backend uses its default key), so
            // the migration must expose the values under those names.
            assertEquals(sessionJson, settings.getStringOrNull(SettingsSessionManager.SETTINGS_KEY))
            assertEquals(
                "legacy-verifier-value",
                settings.getStringOrNull(SettingsCodeVerifierCache.SETTINGS_KEY),
            )
            assertNull(legacy.get(LEGACY_SESSION_KEY, null), "the plaintext session must be revoked")
            assertNull(legacy.get(LEGACY_VERIFIER_KEY, null), "the plaintext verifier must be revoked")
            assertTrue(legacy.keys().isEmpty(), "no legacy plaintext may survive the migration")
            val raw = File(temporary, STORE_FILE_NAME).readText()
            assertFalse(raw.contains(ACCESS_TOKEN))
            assertFalse(raw.contains(REFRESH_TOKEN))
        } finally {
            cleanUpLegacyNode(legacy)
        }
    }

    @Test
    fun `the url-qualified legacy session wins over a bare leftover`() {
        val legacy = Preferences.userNodeForPackage(EncryptedSessionSettingsTest::class.java)
        legacy.clear()
        legacy.put(LEGACY_SESSION_KEY, sessionJson)
        // A bare leftover only exists for ancient supabase-kt versions; the URL-qualified
        // token is the one production wrote last, so it must win the conflict.
        legacy.put(SettingsSessionManager.SETTINGS_KEY, """{"stale":"bare-key-session"}""")
        try {
            val settings =
                createEncryptedSessionSettings(
                    storeDirectory = temporary,
                    legacyStore = legacy,
                    supabaseUrl = LEGACY_SUPABASE_URL,
                )

            assertEquals(
                sessionJson,
                settings.getStringOrNull(SettingsSessionManager.SETTINGS_KEY),
                "the URL-qualified token is the fresher copy; it must win",
            )
            assertNull(legacy.get(LEGACY_SESSION_KEY, null))
            assertNull(legacy.get(SettingsSessionManager.SETTINGS_KEY, null))
            assertTrue(legacy.keys().isEmpty(), "both legacy shapes must be destroyed")
        } finally {
            cleanUpLegacyNode(legacy)
        }
    }

    @Test
    fun `bare legacy keys still migrate for ancient supabase-kt versions`() {
        val legacy = Preferences.userNodeForPackage(EncryptedSessionSettingsTest::class.java)
        legacy.clear()
        legacy.put(SettingsSessionManager.SETTINGS_KEY, sessionJson)
        legacy.put(SettingsCodeVerifierCache.SETTINGS_KEY, "bare-verifier-value")
        try {
            val settings =
                createEncryptedSessionSettings(
                    storeDirectory = temporary,
                    legacyStore = legacy,
                    supabaseUrl = LEGACY_SUPABASE_URL,
                )

            assertEquals(sessionJson, settings.getStringOrNull(SettingsSessionManager.SETTINGS_KEY))
            assertEquals(
                "bare-verifier-value",
                settings.getStringOrNull(SettingsCodeVerifierCache.SETTINGS_KEY),
            )
            assertTrue(legacy.keys().isEmpty(), "the bare plaintext must be revoked too")
        } finally {
            cleanUpLegacyNode(legacy)
        }
    }

    @Test
    fun `an already-encrypted session wins over the stale legacy copy`() {
        val legacy = Preferences.userNodeForPackage(EncryptedSessionSettingsTest::class.java)
        legacy.clear()
        legacy.put(LEGACY_SESSION_KEY, """{"stale":"legacy-value"}""")
        try {
            newStore().putString(SettingsSessionManager.SETTINGS_KEY, sessionJson)

            val settings =
                createEncryptedSessionSettings(
                    storeDirectory = temporary,
                    legacyStore = legacy,
                    supabaseUrl = LEGACY_SUPABASE_URL,
                )
            assertEquals(
                sessionJson,
                settings.getStringOrNull(SettingsSessionManager.SETTINGS_KEY),
                "the encrypted session must win; the legacy copy is never an overwrite",
            )
            assertNull(
                legacy.get(LEGACY_SESSION_KEY, null),
                "the stale plaintext copy must still be destroyed",
            )
            assertTrue(legacy.keys().isEmpty())
        } finally {
            cleanUpLegacyNode(legacy)
        }
    }

    /**
     * The migration targets the real `Preferences.userRoot()` in production; these cases
     * pin it against an isolated node so the runner's own preferences are never touched.
     */
    private fun cleanUpLegacyNode(legacy: Preferences) {
        try {
            legacy.clear()
            legacy.flush()
        } catch (e: BackingStoreException) {
            fail("could not clean up the test prefs node: ${e::class.simpleName}")
        }
    }

    /** [SettingsSessionManager] keeps its Settings private; the pin has to look inside. */
    private fun Any.wrappedSettings(): Settings {
        val field =
            javaClass
                .getDeclaredField("settings")
                .apply { isAccessible = true }
        return field.get(this) as Settings
    }

    /** The private settings key [SettingsSessionManager]/[SettingsCodeVerifierCache] use. */
    private fun Any.reflectedString(name: String): String {
        val field = javaClass.getDeclaredField(name).apply { isAccessible = true }
        return field.get(this) as String
    }

    /** The `java.util.prefs` node behind the library's default `Settings()`. */
    private fun preferencesNodeBehind(settings: Settings): Preferences {
        val field =
            settings.javaClass.declaredFields
                .firstOrNull { Preferences::class.java.isAssignableFrom(it.type) }
                ?.apply { isAccessible = true }
                ?: fail("no java.util.prefs.Preferences field inside ${settings.javaClass.name}")
        return field.get(settings) as Preferences
    }

    private fun sourceFile(name: String): File {
        // Tests run from an unspecified working directory, so walk up to the repo root.
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "$PACKAGE_DIR/$name")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        fail("could not locate $PACKAGE_DIR/$name from ${File(".").absolutePath}")
    }

    private companion object {
        const val PACKAGE_DIR = "composeApp/src/commonMain/kotlin/ai/rever/boss/services/supabase"
        const val STORE_FILE_NAME = "session-store.enc"
        const val KEY_FILE_NAME = "session-store.key"

        /** The URL the migration derives the legacy keys for; pinned, never read from config. */
        const val LEGACY_SUPABASE_URL = "https://bossconsole.test.supabase.co"

        /**
         * The exact supabase-kt 3.8.0 default key shapes (`SettingsUtil.kt`: `sb-` + the
         * scheme-stripped URL with `/` and `.` dashed, then the bare SETTINGS_KEY constant):
         * `sb-bossconsole-test-supabase-co-session` for the session and
         * `sb-bossconsole-test-supabase-co-supabase_code_verifier` for the PKCE verifier,
         * for [LEGACY_SUPABASE_URL]. Pinned as literals so the fixtures prove the migration
         * reads the keys production actually wrote, rather than whatever it derives itself.
         */
        const val LEGACY_SESSION_KEY = "sb-bossconsole-test-supabase-co-session"
        const val LEGACY_VERIFIER_KEY = "sb-bossconsole-test-supabase-co-supabase_code_verifier"

        /** Distinctive fake JWTs: if either leaked into a file, these strings would be found. */
        const val ACCESS_TOKEN = "fake-access-jwt-3f2b9d4c8a1e0f6d.payload-SIGNATURE-6f0e"
        const val REFRESH_TOKEN = "fake-refresh-jwt-9c8d7e6f5a4b3210.lives-forever-if-leaked"
        val sessionJson =
            """{"access_token":"$ACCESS_TOKEN","refresh_token":"$REFRESH_TOKEN","token_type":"bearer"}"""
    }
}
