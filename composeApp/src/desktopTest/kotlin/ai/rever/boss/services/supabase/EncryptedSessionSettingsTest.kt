package ai.rever.boss.services.supabase

import com.russhwolf.settings.Settings
import io.github.jan.supabase.auth.SettingsCodeVerifierCache
import io.github.jan.supabase.auth.SettingsSessionManager
import io.github.jan.supabase.auth.auth
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * `SupabaseWiringTest` documents for services with no injection seam.
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
            source.contains("sessionSettings: Settings = createEncryptedSessionSettings()"),
            "initialize must default to the encrypted backend",
        )
        assertTrue(
            source.contains("sessionManager = SettingsSessionManager(sessionSettings)"),
            "Auth must persist its session through the encrypted backend",
        )
        assertTrue(
            source.contains("codeVerifierCache = SettingsCodeVerifierCache(sessionSettings)"),
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
    fun `legacy plaintext sessions migrate in and the plaintext copy is destroyed`() {
        val legacy = Preferences.userNodeForPackage(EncryptedSessionSettingsTest::class.java)
        legacy.clear()
        legacy.put(SettingsSessionManager.SETTINGS_KEY, sessionJson)
        legacy.put(SettingsCodeVerifierCache.SETTINGS_KEY, "legacy-verifier-value")
        try {
            val settings = createEncryptedSessionSettings(storeDirectory = temporary, legacyStore = legacy)

            assertEquals(sessionJson, settings.getStringOrNull(SettingsSessionManager.SETTINGS_KEY))
            assertEquals("legacy-verifier-value", settings.getStringOrNull(SettingsCodeVerifierCache.SETTINGS_KEY))
            assertNull(legacy.get(SettingsSessionManager.SETTINGS_KEY, null), "the plaintext session must be revoked")
            assertNull(legacy.get(SettingsCodeVerifierCache.SETTINGS_KEY, null))
            val raw = File(temporary, STORE_FILE_NAME).readText()
            assertFalse(raw.contains(ACCESS_TOKEN))
            assertFalse(raw.contains(REFRESH_TOKEN))
        } finally {
            cleanUpLegacyNode(legacy)
        }
    }

    @Test
    fun `an already-encrypted session wins over the stale legacy copy`() {
        val legacy = Preferences.userNodeForPackage(EncryptedSessionSettingsTest::class.java)
        legacy.clear()
        legacy.put(SettingsSessionManager.SETTINGS_KEY, """{"stale":"legacy-value"}""")
        try {
            newStore().putString(SettingsSessionManager.SETTINGS_KEY, sessionJson)

            val settings = createEncryptedSessionSettings(storeDirectory = temporary, legacyStore = legacy)
            assertEquals(
                sessionJson,
                settings.getStringOrNull(SettingsSessionManager.SETTINGS_KEY),
                "the encrypted session must win; the legacy copy is never an overwrite",
            )
            assertNull(
                legacy.get(SettingsSessionManager.SETTINGS_KEY, null),
                "the stale plaintext copy must still be destroyed",
            )
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

        /** Distinctive fake JWTs: if either leaked into a file, these strings would be found. */
        const val ACCESS_TOKEN = "fake-access-jwt-3f2b9d4c8a1e0f6d.payload-SIGNATURE-6f0e"
        const val REFRESH_TOKEN = "fake-refresh-jwt-9c8d7e6f5a4b3210.lives-forever-if-leaked"
        val sessionJson =
            """{"access_token":"$ACCESS_TOKEN","refresh_token":"$REFRESH_TOKEN","token_type":"bearer"}"""
    }
}
