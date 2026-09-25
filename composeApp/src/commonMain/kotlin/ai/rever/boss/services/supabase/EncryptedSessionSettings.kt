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
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
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
 *  - `session-store.key` — a random 32-byte AES key, base64, published atomically while
 *    holding the persistent `session-store.key.lock` file lock. Both files are owner-only
 *    on POSIX from their first byte; concurrent creators adopt the same completed key;
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
 * Fail-closed against plaintext: if the JVM cannot provide AES-GCM construction throws, and
 * this class never falls back to storing plaintext under any circumstance. A key file that
 * will not decode no longer throws — the store is a cache of a session, not durable data,
 * so the key is regenerated, the previous ciphertext reads as empty, and the user signs in
 * again rather than the client refusing to build. A removal whose persist fails destroys
 * the store before rethrowing, so sign-out can never leave a revoked refresh token on disk
 * to be auto-loaded at the next start.
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
     *
     * [supabaseUrl] must be the URL the Supabase client is initialized with: supabase-kt
     * 3.8.0 derives its default settings keys from that URL, so production tokens sit under
     * `sb-<normalized-url>-session` and `sb-<normalized-url>-supabase_code_verifier`, not
     * under the bare `SETTINGS_KEY` constants, which are only constructor defaults (see
     * [legacySettingsKeyNames]). The bare constants are still probed for tokens left behind
     * by supabase-kt versions old enough to have used them as the whole key.
     */
    internal fun migrateFrom(
        legacyStore: Preferences,
        supabaseUrl: String,
    ) {
        for (canonicalName in listOf(SettingsSessionManager.SETTINGS_KEY, SettingsCodeVerifierCache.SETTINGS_KEY)) {
            val migrated =
                migrateLegacyEntry(
                    legacyStore,
                    canonicalName = canonicalName,
                    legacyNames = legacySettingsKeyNames(supabaseUrl, canonicalName),
                )
            if (!migrated) return
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
     * Migrates one logical entry and reports whether the pass may continue. The first
     * [legacyNames] candidate holding a value is the copy that survives; once the encrypted
     * store holds the value (copied now, or from an earlier run), every candidate that still
     * holds plaintext is destroyed, so both the URL-qualified and the bare legacy shape are
     * revoked by a successful pass.
     */
    private fun migrateLegacyEntry(
        legacyStore: Preferences,
        canonicalName: String,
        legacyNames: List<String>,
    ): Boolean {
        val populated = legacyNames.filter { legacyStore.get(it, null) != null }
        // legacySettingsKeyNames lists the URL-qualified key first: that is the shape the
        // supabase-kt defaults write, so it wins over a stale bare-key leftover.
        val legacyValue = if (populated.isEmpty()) null else legacyStore.get(populated.first(), null)
        if (legacyValue == null) return true
        return try {
            if (getStringOrNull(canonicalName) == null) {
                putString(canonicalName, legacyValue)
            }
            populated.forEach { name -> legacyStore.remove(name) }
            true
        } catch (e: IllegalStateException) {
            // The encrypted store refused the write (fail-closed); keep the plaintext
            // copy so the user is logged out only if this failure is permanent.
            warnLegacySessionKept(canonicalName, e)
            false
        } catch (e: IOException) {
            warnLegacySessionKept(canonicalName, e)
            false
        }
    }

    private fun warnLegacySessionKept(
        canonicalName: String,
        failure: Exception,
    ) {
        logger.warn(
            LogCategory.AUTH,
            "Keeping the legacy session for the next start after the encrypted copy failed",
            mapOf("key" to canonicalName, "reason" to failure::class.simpleName),
        )
    }

    /**
     * Loads the wrapping key, creating it on first use under a shared file lock:
     * the app and the `BOSS llm-token` CLI both reach
     * [createEncryptedSessionSettings] against the same directory, and a plain
     * last-write-wins would let each process generate its own key and silently orphan the
     * other's store at the next start. An existing key that cannot be used no longer
     * throws: the store is a cache of a session, not durable data, so it is regenerated
     * (the old ciphertext then reads as empty and the user signs in again) rather than
     * disabling the whole Supabase client over a truncated or half-synced key file.
     */
    private fun loadOrCreateKey(keyFile: File): SecretKeySpec =
        synchronized(keyCreationLock) {
            // Never replace or delete this sidecar: all processes must lock the same inode.
            // The JVM monitor prevents overlapping FileLocks between threads in this process.
            val lockFile = File(keyFile.absolutePath + ".lock")
            createKeyFileExclusively(lockFile)
            FileChannel.open(lockFile.toPath(), StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { loadOrCreateKeyLocked(keyFile) }
            }
        }

    private fun loadOrCreateKeyLocked(keyFile: File): SecretKeySpec {
        readKeyBytes(keyFile)?.let { return SecretKeySpec(it, "AES") }
        if (keyFile.isFile) {
            logger.warn(
                LogCategory.AUTH,
                "The session encryption key is unusable; regenerating it. The stored session " +
                    "will not decrypt, so sign-in is required.",
                mapOf("keyFile" to keyFile.path),
            )
        }
        val fresh = ByteArray(KEY_BYTES).also(secureRandom::nextBytes)
        keyFile.atomicWriteText(base64Encoder.encodeToString(fresh))
        return SecretKeySpec(fresh, "AES")
    }

    /** The bytes of a usable existing key file, or null when the file is absent or unusable. */
    private fun readKeyBytes(keyFile: File): ByteArray? {
        if (!keyFile.isFile) return null
        return try {
            base64Decoder.decode(keyFile.readText().trim()).takeIf { it.size == KEY_BYTES }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Creates the persistent lock file owner-only; an existing sidecar is reused as-is.
     */
    private fun createKeyFileExclusively(keyFile: File): Boolean {
        keyFile.parentFile?.mkdirs()
        return try {
            val path = keyFile.toPath()
            if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
                // Owner-only from the file's first byte, matching atomicWriteText's contract.
                Files.createFile(
                    path,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                )
            } else {
                Files.createFile(path)
            }
            true
        } catch (_: FileAlreadyExistsException) {
            false
        }
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

    /**
     * Applies a mutation and persists it. [removal] marks the sign-out shapes (`remove`,
     * `clear`), which must never fail open: when the revoking rewrite cannot land, the
     * ciphertext is destroyed before rethrowing, so a token that memory already reports
     * gone cannot sit on disk and be auto-loaded at the next start (review #2 on #918).
     */
    private fun mutate(
        removal: Boolean,
        block: () -> Unit,
    ) {
        synchronized(lock) {
            block()
            try {
                persist()
            } catch (e: IOException) {
                if (removal) destroyStoreAfterFailedRemoval(e)
                throw e
            } catch (e: IllegalStateException) {
                if (removal) destroyStoreAfterFailedRemoval(e)
                throw e
            }
        }
    }

    /** Best-effort revocation when the revoking rewrite could not be persisted. */
    private fun destroyStoreAfterFailedRemoval(failure: Exception) {
        if (!storeFile.delete()) {
            logger.warn(
                LogCategory.AUTH,
                "A failed removal could not destroy the session store on disk",
                mapOf("reason" to failure::class.simpleName),
            )
        }
    }

    override val keys: Set<String>
        get() = read { entries.keys.toSet() }

    override val size: Int
        get() = read { entries.size }

    override fun clear() {
        mutate(removal = true) { entries.clear() }
    }

    override fun remove(key: String) {
        mutate(removal = true) { entries.remove(key) }
    }

    override fun hasKey(key: String): Boolean = read { entries.containsKey(key) }

    override fun putString(
        key: String,
        value: String,
    ) {
        mutate(removal = false) { entries[key] = value }
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
        val keyCreationLock = Any()
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val IV_BYTES = 12
        const val KEY_BYTES = 32
        const val SEPARATOR = ":"
    }
}

/**
 * The legacy plaintext key names [EncryptedSessionSettings.migrateFrom] must probe for one
 * logical entry, derived exactly the way supabase-kt 3.8.0 derives its default settings
 * keys (tag 3.8.0, `Auth/src/settingsMain/.../SettingsUtil.kt`):
 *  - `SupabaseClientBuilder.build()` stores the URL scheme-stripped:
 *    `supabaseUrl.split("//").last()`;
 *  - `createDefaultSettingsKey` prefixes `sb-` and turns every `/` and `.` into `-` after
 *    removing one trailing `/`;
 *  - `Auth.createDefaultSessionManager` / `Auth.createDefaultCodeVerifierCache` then append
 *    the bare `SETTINGS_KEY` constants, so production tokens sit under
 *    `sb-<normalized-url>-session` and `sb-<normalized-url>-supabase_code_verifier` — the
 *    bare constants alone are only the constructor defaults and never the default keys.
 *
 * The bare constant stays the second candidate for tokens written by supabase-kt versions
 * old enough to have used it as the whole key (3.8.0 still self-migrates those on its own).
 */
private fun legacySettingsKeyNames(
    supabaseUrl: String,
    settingsKey: String,
): List<String> {
    val normalizedUrl = supabaseUrl.split("//").last().removeSuffix("/")
    val qualifiedName = "sb-$normalizedUrl".replace('/', '-').replace('.', '-') + "-$settingsKey"
    return listOf(qualifiedName, settingsKey)
}

/**
 * Builds the Auth persistence backend: an AES-GCM-encrypted store under BOSS's data
 * directory (`~/.boss/supabase`), seeded once from the plaintext `java.util.prefs` store
 * the supabase-kt defaults wrote to before BossConsole#846.
 *
 * @param storeDirectory the directory holding [STORE_FILE_NAME] and [KEY_FILE_NAME].
 * @param legacyStore the pre-fix backend to migrate from and clean out; the default is the
 *   exact `Preferences.userRoot()` node supabase-kt's default `Settings()` resolves to.
 * @param supabaseUrl the URL the Supabase client is initialized with; the seed derives the
 *   real URL-qualified legacy key names from it, so the migration only runs when it is
 *   provided. Production passes it from `SupabaseConfig.initialize` — the one place that
 *   knows the URL the client is built with.
 */
internal fun createEncryptedSessionSettings(
    storeDirectory: File = BossDirectories.resolve("supabase"),
    legacyStore: Preferences = Preferences.userRoot(),
    supabaseUrl: String? = null,
): Settings {
    val settings =
        EncryptedSessionSettings(
            storeFile = File(storeDirectory, STORE_FILE_NAME),
            keyFile = File(storeDirectory, KEY_FILE_NAME),
        )
    if (supabaseUrl != null) {
        settings.migrateFrom(legacyStore, supabaseUrl)
    }
    return settings
}
