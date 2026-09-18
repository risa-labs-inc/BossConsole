package ai.rever.boss.services.supabase

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.russhwolf.settings.Settings
import io.github.jan.supabase.auth.SettingsCodeVerifierCache
import io.github.jan.supabase.auth.SettingsSessionManager
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The encrypted session store, inside BOSS's own data directory. */
private const val STORE_FILE_NAME = "session-store.enc"

/** The AES key file that wraps the session store, created owner-only like every other state file. */
private const val KEY_FILE_NAME = "session-store.key"

/**
 * A [Settings] backend that persists the Supabase session — the access + refresh JWTs, plus
 * the PKCE code verifier — AES-256-GCM encrypted inside BOSS's own data directory.
 *
 * BossConsole#846: `install(Auth)` used to leave supabase-kt's defaults in place, and those
 * persist through `Settings()` — `java.util.prefs.Preferences` on the JVM. The highest-value
 * secret in the app (a live refresh token) sat as plaintext under `~/.java/.userPrefs`
 * (XML), the macOS plist, or the Windows registry, in a library-owned store outside the
 * `~/.boss` directory whose files this app otherwise keeps owner-only.
 *
 * Storage, both files under the directory [createEncryptedSessionSettings] resolves:
 *  - `session-store.key` — a random 32-byte AES key, base64, created through
 *    [atomicWriteText] so it lands 0600 on POSIX from its first byte, same contract as every
 *    other state file (see `AtomicFileWrite.kt`);
 *  - `session-store.enc` — one `base64(key):base64(iv + ciphertext)` line per entry, so
 *    even key names are opaque. Every persisted write draws a fresh 96-bit IV.
 *
 * A machine-local key file is deliberately chosen over shelling out to OS keystores — the
 * trade `ChromiumCrypto` already documents: DPAPI has no JDK binding at all, and the
 * keyring CLIs are read-only in practice here. It moves the tokens out of a library-owned,
 * cloud-synced store and into owner-only files under `~/.boss`, matching the hardening bar
 * of the atomic write helper. Anything running as this user can still read both files, so
 * revocation remains `signOut`.
 *
 * Fail-closed: if the JVM cannot provide AES-GCM, or the key file is unusable, construction
 * throws. This class never falls back to storing plaintext under any circumstance.
 */
@Suppress("TooManyFunctions") // Settings mandates 23 members; the interface's contract, not this class's design.
internal class EncryptedSessionSettings(
    private val storeFile: File,
    keyFile: File,
) : Settings {
    private val logger = BossLogger.forComponent("EncryptedSessionSettings")

    private val lock = Any()
    private val entries = LinkedHashMap<String, String>()
    private val secureRandom = SecureRandom()
    private val base64Encoder = Base64.getEncoder()
    private val base64Decoder = Base64.getDecoder()

    /** Loaded before anything else so a missing or corrupt key fails construction, not first use. */
    private val secretKey: SecretKeySpec = loadOrCreateKey(keyFile)

    init {
        // Probe before the first real use: a JVM without AES-GCM must never see a session
        // persisted through this class.
        newCipher()
        loadFromDisk()
    }

    /**
     * One-shot bridge off the plaintext backend (BossConsole#846): copies whatever the
     * supabase-kt defaults persisted under `java.util.prefs` into this encrypted store, then
     * destroys the plaintext copy so the upgrade itself is the revocation. Copy first,
     * destroy second — an aborted migration must never be the end of the user's only
     * session; a failed copy is retried on the next start instead.
     */
    internal fun migrateFrom(legacyStore: Preferences) {
        val keyNames =
            listOf(
                SettingsSessionManager.SETTINGS_KEY,
                SettingsCodeVerifierCache.SETTINGS_KEY,
            )
        for (name in keyNames) {
            val legacyValue = legacyStore.get(name, null) ?: continue
            try {
                if (getStringOrNull(name) == null) {
                    putString(name, legacyValue)
                }
                legacyStore.remove(name)
            } catch (e: IllegalStateException) {
                // The encrypted store refused the write (fail-closed); keep the plaintext
                // copy so the user is logged out only if this failure is permanent.
                logger.warn(
                    LogCategory.AUTH,
                    "Keeping the legacy session for the next start after the encrypted copy failed",
                    mapOf("key" to name, "reason" to e::class.simpleName),
                )
                return
            } catch (e: IOException) {
                logger.warn(
                    LogCategory.AUTH,
                    "Keeping the legacy session for the next start after the encrypted copy could not be written",
                    mapOf("key" to name, "reason" to e::class.simpleName),
                )
                return
            }
        }
        try {
            // remove() only marks the node dirty; flush() forces the plaintext out of the
            // backing XML/plist/registry now rather than at JVM exit.
            legacyStore.flush()
        } catch (e: BackingStoreException) {
            logger.warn(
                LogCategory.AUTH,
                "The legacy session store may outlive removal on disk until the next start",
                mapOf("reason" to e::class.simpleName),
            )
        }
    }

    /**
     * Loads the wrapping key, creating it on first use. The write goes through
     * [atomicWriteText] so the key file is owner-only (0600) on POSIX from its first byte.
     */
    private fun loadOrCreateKey(keyFile: File): SecretKeySpec {
        if (keyFile.isFile) {
            val bytes = decodeBase64Strict(keyFile.readText())
            if (bytes.size != KEY_BYTES) {
                error("The session encryption key is corrupt: ${keyFile.path}")
            }
            return SecretKeySpec(bytes, "AES")
        }
        val bytes = ByteArray(KEY_BYTES).also(secureRandom::nextBytes)
        keyFile.atomicWriteText(base64Encoder.encodeToString(bytes))
        return SecretKeySpec(bytes, "AES")
    }

    /**
     * Reads the persisted entries into memory. Entries that fail to authenticate are
     * dropped, never handed back — tampered data must decrypt to nothing, not to garbage
     * that could be mistaken for a session.
     */
    @Suppress("LoopWithTooManyJumpStatements") // One jump per shape of corrupt line, fail-closed.
    private fun loadFromDisk() {
        if (!storeFile.isFile) return
        for (rawLine in storeFile.readLines()) {
            if (rawLine.isBlank()) continue
            val separatorAt = rawLine.indexOf(SEPARATOR)
            if (separatorAt <= 0) {
                logger.debug(LogCategory.AUTH, "Dropping an unrecognised line from the session store")
                continue
            }
            val name = decodeBase64OrNull(rawLine.substring(0, separatorAt))?.toString(Charsets.UTF_8) ?: continue
            val value = decrypt(rawLine.substring(separatorAt + SEPARATOR.length)) ?: continue
            entries[name] = value
        }
    }

    /** Rewrites the whole store atomically; the two-entry ceiling keeps this cheap. */
    private fun persist() {
        val lines =
            entries.map { (name, value) ->
                base64Encoder.encodeToString(name.toByteArray(Charsets.UTF_8)) + SEPARATOR + encrypt(value)
            }
        storeFile.atomicWriteText(lines.joinToString(separator = "\n"))
    }

    private fun encrypt(plainText: String): String {
        val iv = ByteArray(IV_BYTES).also(secureRandom::nextBytes)
        val cipher = newCipher()
        try {
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            return base64Encoder.encodeToString(iv + cipherText)
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("Encrypting the session failed; refusing to write plaintext", e)
        }
    }

    /** Returns null for an entry that fails authentication or decoding. */
    @Suppress("ReturnCount") // Every malformed blob must yield null; fail-closed guards.
    private fun decrypt(encoded: String): String? {
        val blob = decodeBase64OrNull(encoded) ?: return null
        if (blob.size <= IV_BYTES) return null
        return try {
            val cipher = newCipher()
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, blob.copyOf(IV_BYTES)))
            cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES).toString(Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            // AEADBadTagException lands here: the entry did not authenticate.
            logger.debug(
                LogCategory.AUTH,
                "Dropping an undecryptable entry from the session store",
                mapOf("reason" to e::class.simpleName),
            )
            null
        }
    }

    /** AES-GCM or nothing: surfaces unavailable crypto as a construction-time failure. */
    private fun newCipher(): Cipher =
        try {
            Cipher.getInstance(TRANSFORMATION)
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException(
                "AES-GCM is unavailable on this JVM; refusing to persist the Supabase session in plaintext",
                e,
            )
        }

    private fun decodeBase64Strict(text: String): ByteArray =
        try {
            base64Decoder.decode(text.trim())
        } catch (e: IllegalArgumentException) {
            // A key file that will not decode is unusable; fail closed rather than risk
            // regenerating a key that would orphan the stored session.
            throw IllegalStateException("The session encryption key is not valid base64", e)
        }

    private fun decodeBase64OrNull(text: String): ByteArray? =
        try {
            base64Decoder.decode(text.trim())
        } catch (e: IllegalArgumentException) {
            logger.debug(
                LogCategory.AUTH,
                "Dropping a malformed entry from the session store",
                mapOf("reason" to e::class.simpleName),
            )
            null
        }

    private fun <T> read(block: () -> T): T = synchronized(lock) { block() }

    private fun mutate(block: () -> Unit) {
        synchronized(lock) {
            block()
            persist()
        }
    }

    override val keys: Set<String>
        get() = read { entries.keys.toSet() }

    override val size: Int
        get() = read { entries.size }

    override fun clear() {
        mutate { entries.clear() }
    }

    override fun remove(key: String) {
        mutate { entries.remove(key) }
    }

    override fun hasKey(key: String): Boolean = read { entries.containsKey(key) }

    override fun putString(
        key: String,
        value: String,
    ) {
        mutate { entries[key] = value }
    }

    override fun getString(
        key: String,
        defaultValue: String,
    ): String = read { entries[key] ?: defaultValue }

    override fun getStringOrNull(key: String): String? = read { entries[key] }

    override fun putInt(
        key: String,
        value: Int,
    ) {
        putString(key, value.toString())
    }

    override fun getInt(
        key: String,
        defaultValue: Int,
    ): Int = getIntOrNull(key) ?: defaultValue

    override fun getIntOrNull(key: String): Int? = read { entries[key] }?.toIntOrNull()

    override fun putLong(
        key: String,
        value: Long,
    ) {
        putString(key, value.toString())
    }

    override fun getLong(
        key: String,
        defaultValue: Long,
    ): Long = getLongOrNull(key) ?: defaultValue

    override fun getLongOrNull(key: String): Long? = read { entries[key] }?.toLongOrNull()

    override fun putFloat(
        key: String,
        value: Float,
    ) {
        putString(key, value.toString())
    }

    override fun getFloat(
        key: String,
        defaultValue: Float,
    ): Float = getFloatOrNull(key) ?: defaultValue

    override fun getFloatOrNull(key: String): Float? = read { entries[key] }?.toFloatOrNull()

    override fun putDouble(
        key: String,
        value: Double,
    ) {
        putString(key, value.toString())
    }

    override fun getDouble(
        key: String,
        defaultValue: Double,
    ): Double = getDoubleOrNull(key) ?: defaultValue

    override fun getDoubleOrNull(key: String): Double? = read { entries[key] }?.toDoubleOrNull()

    override fun putBoolean(
        key: String,
        value: Boolean,
    ) {
        putString(key, value.toString())
    }

    override fun getBoolean(
        key: String,
        defaultValue: Boolean,
    ): Boolean = getBooleanOrNull(key) ?: defaultValue

    override fun getBooleanOrNull(key: String): Boolean? = read { entries[key] }?.toBooleanStrictOrNull()

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val IV_BYTES = 12
        const val KEY_BYTES = 32
        const val SEPARATOR = ":"
    }
}

/**
 * Builds the Auth persistence backend: an AES-GCM-encrypted store under BOSS's data
 * directory (`~/.boss/supabase`), seeded once from the plaintext `java.util.prefs` store
 * the supabase-kt defaults wrote to before BossConsole#846.
 *
 * @param storeDirectory the directory holding [STORE_FILE_NAME] and [KEY_FILE_NAME].
 * @param legacyStore the pre-fix backend to migrate from and clean out; the default is the
 *   exact `Preferences.userRoot()` node supabase-kt's default `Settings()` resolves to.
 */
internal fun createEncryptedSessionSettings(
    storeDirectory: File = BossDirectories.resolve("supabase"),
    legacyStore: Preferences = Preferences.userRoot(),
): Settings {
    val settings =
        EncryptedSessionSettings(
            storeFile = File(storeDirectory, STORE_FILE_NAME),
            keyFile = File(storeDirectory, KEY_FILE_NAME),
        )
    settings.migrateFrom(legacyStore)
    return settings
}
