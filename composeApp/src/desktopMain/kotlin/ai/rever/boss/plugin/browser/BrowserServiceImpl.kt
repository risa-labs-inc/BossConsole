package ai.rever.boss.plugin.browser

import ai.rever.boss.config.ConfigLoader
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogSanitizer
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.cookie.Cookie
import com.teamdev.jxbrowser.cookie.SameSite
import com.teamdev.jxbrowser.net.HttpHeader
import com.teamdev.jxbrowser.net.callback.BeforeStartTransactionCallback
import com.teamdev.jxbrowser.profile.Profile
import com.teamdev.jxbrowser.time.Timestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Tracks which application window owns each plugin-created browser handle.
 *
 * Closing a window atomically marks it closed before draining its handles. A
 * browser creation that finishes during teardown is therefore rejected instead
 * of escaping cleanup and retaining the destroyed AWT window.
 */
internal class BrowserWindowOwnershipRegistry {
    private data class WindowCreationState(
        var inFlightCreates: Int = 0,
        var closing: Boolean = false
    )

    private val lock = Any()
    private val ownerByBrowserId = mutableMapOf<String, String>()
    private val creationStateByWindowId = mutableMapOf<String, WindowCreationState>()
    private var closingAll = false

    fun tryBeginCreate(windowId: String): Boolean = synchronized(lock) {
        if (closingAll) return@synchronized false

        val state = creationStateByWindowId.getOrPut(windowId, ::WindowCreationState)
        if (state.closing) {
            false
        } else {
            state.inFlightCreates++
            true
        }
    }

    fun register(browserId: String, windowId: String): Boolean = synchronized(lock) {
        val state = creationStateByWindowId[windowId]
        if (closingAll || state == null || state.closing) {
            false
        } else {
            ownerByBrowserId[browserId] = windowId
            true
        }
    }

    fun finishCreate(windowId: String) = synchronized(lock) {
        val state = creationStateByWindowId[windowId] ?: return@synchronized
        check(state.inFlightCreates > 0) {
            "Browser creation finished without a matching start for window $windowId"
        }
        state.inFlightCreates--
        if (state.inFlightCreates == 0) {
            // The window-scoped BrowserService permanently rejects new work after
            // close, so no closed UUID needs to remain once admitted calls drain.
            creationStateByWindowId.remove(windowId, state)
        }
    }

    fun unregister(browserId: String) = synchronized(lock) {
        ownerByBrowserId.remove(browserId)
    }

    fun closeWindow(windowId: String): Set<String> = synchronized(lock) {
        val state = creationStateByWindowId.getOrPut(windowId, ::WindowCreationState)
        state.closing = true

        val ownedBrowserIds = mutableSetOf<String>()
        val owners = ownerByBrowserId.iterator()
        while (owners.hasNext()) {
            val (browserId, ownerWindowId) = owners.next()
            if (ownerWindowId == windowId) {
                ownedBrowserIds += browserId
                owners.remove()
            }
        }

        if (state.inFlightCreates == 0) {
            creationStateByWindowId.remove(windowId, state)
        }
        ownedBrowserIds
    }

    fun count(windowId: String): Int = synchronized(lock) {
        ownerByBrowserId.values.count { it == windowId }
    }

    fun closeAll() {
        synchronized(lock) {
            closingAll = true
            ownerByBrowserId.clear()
            creationStateByWindowId.values.forEach { it.closing = true }
            creationStateByWindowId.entries.removeAll { (_, state) ->
                state.inFlightCreates == 0
            }
        }
    }

    internal fun trackedWindowCount(): Int = synchronized(lock) {
        creationStateByWindowId.size
    }
}

/**
 * Desktop implementation of [BrowserService] that wraps [FluckEngine].
 *
 * Provides plugins with JxBrowser functionality without exposing JxBrowser types.
 * In addition to plain browser creation it offers optional **managed profiles**
 * (used by automation such as RPA): an isolated profile per browser, with auth
 * (cookie/header) seeding, ephemeral cleanup, and persistent named-profile reuse
 * under an LRU disk cap. These are opt-in via [BrowserConfig.profileName] /
 * [BrowserConfig.ephemeralProfile] / [BrowserConfig.auth]; general tabs leave
 * them unset and are unaffected.
 *
 * Singleton, shared across all plugins.
 */
object BrowserServiceImpl : BrowserService {

    private val logger = BossLogger.forComponent("BrowserServiceImpl")

    // Track active browser handles for resource management
    private val activeBrowsers = ConcurrentHashMap<String, BrowserHandleImpl>()

    // BrowserService is shared process-wide, but plugin handles belong to the
    // window whose PluginContext created them.
    private val browserOwners = BrowserWindowOwnershipRegistry()

    // Managed-profile bookkeeping for handles created on an isolated profile.
    private val managedByHandle = ConcurrentHashMap<String, ManagedRef>()

    // POST bodies captured from popup handoffs, awaiting consumption by the next
    // createBrowser call for the same URL. A FIFO queue per URL so that two
    // popups to the same destination (e.g. clicking Print twice in OncoEMR
    // before the first new tab finishes loading) don't clobber each other.
    // See [stashPopupPost].
    private val pendingPopupPosts = ConcurrentHashMap<String, ConcurrentLinkedDeque<PendingPopupPost>>()
    private const val PENDING_POPUP_TTL_MS = 10_000L

    private data class PendingPopupPost(
        val postData: ByteArray,
        val contentType: String,
        val createdAtMs: Long
    )

    override fun stashPopupPost(url: String, postData: ByteArray, contentType: String) {
        val now = System.currentTimeMillis()
        // compute (not computeIfAbsent + addLast) so the append is atomic with
        // the queue's membership in the map. Otherwise a concurrent consume or
        // GC that just observed an empty queue could remove the entry between
        // computeIfAbsent returning and addLast appending — orphaning the body
        // and reintroducing the regression this fix exists for.
        pendingPopupPosts.compute(url) { _, existing ->
            (existing ?: ConcurrentLinkedDeque()).also {
                it.addLast(PendingPopupPost(postData, contentType, now))
            }
        }
        // Opportunistic GC. Stash is infrequent (one call per popup), so the
        // map walk here is cheap relative to the cost of a background sweeper.
        gcStalePopupPosts(now)
    }

    private fun consumePopupPost(url: String): PendingPopupPost? {
        val now = System.currentTimeMillis()
        var entry: PendingPopupPost? = null
        // The whole drain-and-poll runs inside compute so a racing stash for
        // the same URL serializes on this key — preventing TOCTOU where we'd
        // remove a queue from the map after another thread had just appended.
        pendingPopupPosts.compute(url) { _, q ->
            if (q == null) return@compute null
            dropStaleHeads(q, now)
            entry = q.pollFirst()
            if (q.isEmpty()) null else q
        }
        return entry
    }

    private fun gcStalePopupPosts(now: Long) {
        for (key in pendingPopupPosts.keys) {
            pendingPopupPosts.compute(key) { _, q ->
                if (q == null) return@compute null
                dropStaleHeads(q, now)
                if (q.isEmpty()) null else q
            }
        }
    }

    private fun dropStaleHeads(queue: ConcurrentLinkedDeque<PendingPopupPost>, now: Long) {
        while (true) {
            val head = queue.peekFirst() ?: return
            if (now - head.createdAtMs <= PENDING_POPUP_TTL_MS) return
            queue.pollFirst()
        }
    }

    override fun isAvailable(): Boolean {
        return try {
            // Deliberately does NOT touch FluckEngine.engine: that getter performs
            // the full synchronous Chromium boot, and this method gets called from
            // browser-tab composition (the UI thread). A not-yet-initialized (or
            // closed-and-recreatable) engine reports available — initialization
            // happens lazily inside createBrowser, whose failure paths callers
            // already surface with retry UI.
            val initErr = FluckEngine.initError
            val available = initErr == null
            if (!available) {
                logger.warn(LogCategory.BROWSER, "BrowserService not available", mapOf(
                    "initError" to (initErr?.toString() ?: "none")
                ))
            }
            available
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "BrowserService not available - exception", mapOf(
                "error" to (e.message ?: "unknown"),
                "errorType" to e.javaClass.simpleName
            ))
            false
        }
    }

    override suspend fun createBrowser(config: BrowserConfig): BrowserHandle? =
        createBrowser(config, ownerWindowId = null)

    internal fun tryBeginBrowserCreation(windowId: String): Boolean =
        browserOwners.tryBeginCreate(windowId)

    internal suspend fun createBrowserForWindow(
        windowId: String,
        config: BrowserConfig
    ): BrowserHandle? = createBrowser(config, ownerWindowId = windowId)

    internal fun finishBrowserCreation(windowId: String) {
        browserOwners.finishCreate(windowId)
    }

    private suspend fun createBrowser(
        config: BrowserConfig,
        ownerWindowId: String?
    ): BrowserHandle? {
        // Declared outside the try so the outer catch can release the managed profile
        // if anything after newBrowser() throws (see the catch below).
        var managed: ManagedRef? = null
        var handleId: String? = null
        var tracked = false
        return try {
            val engine = FluckEngine.engine

            // Optionally run on an isolated managed profile (ephemeral or named),
            // seeding auth into it first. Null = the engine default profile (tabs).
            val m = acquireManagedProfile(config)
            managed = m
            val browser: Browser = try {
                m?.profile?.let { p ->
                    if (config.auth != null) seedAndAwait(p, config.auth)
                    else if (!m.ephemeral) installHeaderCallback(p, emptyMap()) // clear stale on named
                }
                if (m != null) m.profile.newBrowser() else engine.newBrowser()
            } catch (e: Throwable) {
                m?.let { releaseManaged(it) }
                managed = null // released here — don't double-release in the outer catch
                throw e
            }

            // Enable swipe navigation for touchscreen devices
            browser.settings().enableOverscrollHistoryNavigation()

            val generation = FluckEngine.currentEngineGeneration

            // If a popup handed off a POST body for this URL (form-submit target="_blank"),
            // consume it and replay on first navigation. Explicit config wins if set.
            val effectiveConfig = if (config.initialPostData == null && config.url.isNotBlank()) {
                consumePopupPost(config.url)?.let { entry ->
                    // copy() only carries data-class constructor params, so re-apply the
                    // post-construction managed-profile vars onto the copy — otherwise
                    // effectiveConfig.profileName/ephemeralProfile/auth silently reset.
                    config.copy(initialPostData = entry.postData, initialPostContentType = entry.contentType).also {
                        it.profileName = config.profileName
                        it.ephemeralProfile = config.ephemeralProfile
                        it.auth = config.auth
                    }
                } ?: config
            } else {
                config
            }

            val handle = BrowserHandleImpl(browser, effectiveConfig, generation)
            handleId = handle.id
            activeBrowsers[handle.id] = handle
            m?.let {
                managedByHandle[handle.id] = it
                // Record named-profile meta only after a successful open.
                if (it.namedId != null) {
                    meta[it.namedId] = NamedMeta(it.namedId, it.profileName, it.profile.path(), System.currentTimeMillis())
                    persistMeta()
                }
            }
            tracked = true // profile lifecycle now owned by disposeBrowser (via managedByHandle)

            if (ownerWindowId != null && !browserOwners.register(handle.id, ownerWindowId)) {
                // The window closed while browser creation was suspended. Dispose
                // synchronously so the handle cannot retain its destroyed AWT window.
                disposeTrackedBrowserBlocking(handle)
                logger.debug(LogCategory.BROWSER, "Discarded browser created for closed window", mapOf(
                    "handleId" to handle.id,
                    "windowId" to ownerWindowId
                ))
                return null
            }

            logger.info(LogCategory.BROWSER, "Browser created via BrowserService", mapOf(
                "handleId" to handle.id,
                "url" to config.url,
                "profile" to (managed?.profileName ?: "default"),
                "activeBrowsers" to activeBrowsers.size
            ))

            handle
        } catch (e: Exception) {
            logger.error(LogCategory.BROWSER, "Failed to create browser", error = e)
            // A throw after newBrowser() (overscroll / popup-replay / handle ctor / meta
            // persist) is caught only here; without releasing, a NAMED profile's fence
            // stays locked for the process lifetime (deadlock) and an EPHEMERAL profile
            // leaks. Only release if we never reached the tracked point.
            if (!tracked) {
                handleId?.let {
                    activeBrowsers.remove(it)
                    managedByHandle.remove(it)
                    browserOwners.unregister(it)
                }
                managed?.let { releaseManaged(it) }
            }
            null
        }
    }

    override suspend fun disposeBrowser(handle: BrowserHandle) {
        activeBrowsers.remove(handle.id)
        browserOwners.unregister(handle.id)
        try {
            handle.dispose()
        } finally {
            // Managed-profile cleanup must run even if dispose() throws — otherwise the
            // per-named-profile fence stays locked (deadlock) and ephemeral profiles
            // leak. Delete ephemeral profiles, refresh+evict named ones, release fence.
            managedByHandle.remove(handle.id)?.let { ref ->
                inUse.remove(ref.profileName)
                try {
                    if (ref.ephemeral) {
                        FluckEngine.deleteRpaProfile(ref.profile)
                    } else if (ref.namedId != null) {
                        meta[ref.namedId]?.let { m ->
                            val size = withContext(Dispatchers.IO) { dirSize(File(m.path)) }
                            meta[ref.namedId] = m.copy(lastUsedMs = System.currentTimeMillis(), diskBytes = size)
                        }
                        persistMeta()
                        evictIfNeeded()
                    }
                } finally {
                    ref.heldMutex?.unlock()
                }
            }
        }

        logger.debug(LogCategory.BROWSER, "Browser disposed via BrowserService", mapOf(
            "handleId" to handle.id,
            "remainingBrowsers" to activeBrowsers.size
        ))
    }

    override fun getActiveBrowserCount(): Int {
        return activeBrowsers.size
    }

    internal fun getActiveBrowserCountForWindow(windowId: String): Int =
        browserOwners.count(windowId)

    /** Return all active browser handles for internal lookup (e.g. RPA recorder). */
    internal fun getActiveHandles(): List<BrowserHandleImpl> = activeBrowsers.values.toList()

    /**
     * Dispose plugin browsers owned by one application window.
     *
     * Called before that window's AWT peer is destroyed. Handles owned by other
     * windows remain active.
     */
    internal fun disposeAllForWindow(windowId: String) {
        val ownedBrowserIds = browserOwners.closeWindow(windowId)
        var disposedCount = 0
        ownedBrowserIds.forEach { browserId ->
            val handle = activeBrowsers[browserId] ?: return@forEach
            try {
                if (disposeTrackedBrowserBlocking(handle)) {
                    disposedCount++
                }
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Error disposing browser for window", mapOf(
                    "handleId" to browserId,
                    "windowId" to windowId
                ), e)
            }
        }

        logger.info(LogCategory.BROWSER, "Window browsers disposed", mapOf(
            "windowId" to windowId,
            "count" to disposedCount
        ))
    }

    /**
     * Dispose all active browsers.
     *
     * Called during application shutdown to ensure clean cleanup.
     */
    fun disposeAll() {
        val count = activeBrowsers.size
        browserOwners.closeAll()
        activeBrowsers.values.toList().forEach { handle ->
            try {
                disposeTrackedBrowserBlocking(handle)
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Error disposing browser", error = e)
            }
        }
        activeBrowsers.clear()

        logger.info(LogCategory.BROWSER, "All browsers disposed", mapOf("count" to count))
    }

    private fun disposeTrackedBrowserBlocking(handle: BrowserHandleImpl): Boolean {
        if (!activeBrowsers.remove(handle.id, handle)) return false

        browserOwners.unregister(handle.id)
        try {
            handle.dispose()
        } finally {
            // Window/application teardown cannot suspend for profile accounting,
            // but it must release profile locks and delete ephemeral profiles.
            managedByHandle.remove(handle.id)?.let(::releaseManaged)
        }
        return true
    }

    // =======================================================================
    // Managed profiles (optional; used by automation/RPA). General tabs don't
    // touch any of this — it's gated on BrowserConfig.profileName/ephemeralProfile.
    // =======================================================================

    private const val EPHEMERAL_PREFIX = "rpa-eph-"
    private const val NAMED_PREFIX = "rpa-named-"
    private const val COOKIE_COMMIT_TIMEOUT_MS = 2_000

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val metaDir: File by lazy { BossDirectories.resolve("rpa-profiles").also { it.mkdirs() } }
    private val metaFile: File by lazy { File(metaDir, "meta.json") }
    // Bounds the persistent named profiles only (LRU-evicted). Ephemerals are bounded
    // by their lifecycle (deleted on dispose + reclaimed by the orphan sweep).
    private val diskCapBytes: Long by lazy {
        ConfigLoader.getConfig("BOSS_RPA_PROFILE_CAP_BYTES")?.toLongOrNull() ?: (2L * 1024 * 1024 * 1024)
    }

    // Immutable; updates go through replace-in-map (ConcurrentHashMap.put is atomic).
    @Serializable
    internal data class NamedMeta(val id: String, val name: String, val path: String, val lastUsedMs: Long, val diskBytes: Long = 0)

    internal fun encodeMeta(list: List<NamedMeta>): String = json.encodeToString(list)
    internal fun decodeMeta(text: String): List<NamedMeta> = json.decodeFromString(text)

    private val meta = ConcurrentHashMap<String, NamedMeta>()
    private val idMutexes = ConcurrentHashMap<String, Mutex>()
    private val inUse = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var loaded = false
    @Volatile private var sweptOrphans = false

    /** A handle's managed profile: its fence name, the JxBrowser profile, and (named only) lock + id. */
    private class ManagedRef(
        val profileName: String,
        val profile: Profile,
        val ephemeral: Boolean,
        val namedId: String?,
        val heldMutex: Mutex?,
    )

    /**
     * Resolve the isolated profile for [config] (null = default profile). For a
     * named profile this acquires the per-id fence (released on disposeBrowser),
     * so concurrent creates on the same name serialize. Ephemeral profiles get a
     * fresh generated name, deleted on dispose.
     */
    private suspend fun acquireManagedProfile(config: BrowserConfig): ManagedRef? {
        // Plain tabs (no profile requested) skip all managed-profile machinery.
        if (!config.ephemeralProfile && config.profileName == null) return null
        ensureLoaded()
        sweepOrphansOnce()
        return when {
            config.ephemeralProfile -> {
                val name = EPHEMERAL_PREFIX + UUID.randomUUID().toString().replace("-", "")
                inUse.add(name)
                ManagedRef(name, FluckEngine.newRpaProfile(name), ephemeral = true, namedId = null, heldMutex = null)
            }
            config.profileName != null -> {
                val namedId = config.profileName!!
                val fence = NAMED_PREFIX + sanitize(namedId)
                val mutex = mutexFor(namedId)
                mutex.lock()
                try {
                    inUse.add(fence)
                    val profile = FluckEngine.findProfile(fence) ?: run { evictIfNeeded(); FluckEngine.newRpaProfile(fence) }
                    ManagedRef(fence, profile, ephemeral = false, namedId = namedId, heldMutex = mutex)
                } catch (e: Throwable) {
                    inUse.remove(fence); mutex.unlock(); throw e
                }
            }
            else -> null
        }
    }

    /** Cleanup if the browser couldn't be created after the profile was acquired. */
    private fun releaseManaged(ref: ManagedRef) {
        inUse.remove(ref.profileName)
        if (ref.ephemeral) try { FluckEngine.deleteRpaProfile(ref.profile) } catch (_: Exception) {}
        ref.heldMutex?.unlock()
    }

    override suspend fun seedProfile(profileName: String, auth: BrowserAuthSpec?) {
        ensureLoaded()
        require(profileName.isNotBlank()) { "profileName must not be blank" }
        mutexFor(profileName).withLock {
            val fence = NAMED_PREFIX + sanitize(profileName)
            inUse.add(fence)
            try {
                val profile = FluckEngine.findProfile(fence) ?: run { evictIfNeeded(); FluckEngine.newRpaProfile(fence) }
                if (auth != null) {
                    val tmp = profile.newBrowser()
                    try { seedAndAwait(profile, auth) } finally { try { tmp.close() } catch (_: Exception) {} }
                } else {
                    installHeaderCallback(profile, emptyMap())
                }
                meta[profileName] = NamedMeta(profileName, fence, profile.path(), System.currentTimeMillis())
                persistMeta()
            } finally {
                inUse.remove(fence)
            }
        }
    }

    override fun deleteProfile(profileName: String): Boolean {
        ensureLoaded()
        val fence = NAMED_PREFIX + sanitize(profileName)
        val mutex = mutexFor(profileName)
        if (!mutex.tryLock()) return false
        try {
            if (inUse.contains(fence)) return false
            FluckEngine.findProfile(fence)?.let { FluckEngine.deleteRpaProfile(it) }
            meta.remove(profileName)
            persistMeta()
            return true
        } finally {
            mutex.unlock()
        }
    }

    override fun listProfiles(): List<BrowserProfileInfo> {
        ensureLoaded()
        return meta.values.map { BrowserProfileInfo(it.id, it.diskBytes, it.lastUsedMs, File(it.path).exists()) }
    }

    // ---- auth seeding (cookies + headers) ----

    private suspend fun seedAndAwait(profile: Profile, auth: BrowserAuthSpec?) {
        if (auth == null) return
        seedCookiesAndHeaders(profile, auth)
        val expected = auth.cookies.filter { it.url.isNotBlank() }.map { it.name }.toSet()
        if (expected.isEmpty()) return
        val store = profile.cookieStore()
        var waited = 0
        while (waited < COOKIE_COMMIT_TIMEOUT_MS) {
            val have = store.cookies().map { it.name() }.toSet()
            if (have.containsAll(expected)) break
            delay(100); waited += 100
        }
        if (waited >= COOKIE_COMMIT_TIMEOUT_MS) {
            val missing = expected - store.cookies().map { it.name() }.toSet()
            if (missing.isNotEmpty()) {
                logger.warn(
                    LogCategory.BROWSER,
                    "Cookie seeding timed out; some cookies never committed (likely a domain mismatch)",
                    mapOf("missing" to missing.joinToString(","), "profile" to profile.name())
                )
            }
        }
    }

    private fun seedCookiesAndHeaders(profile: Profile, auth: BrowserAuthSpec) {
        val store = profile.cookieStore()
        var set = 0
        for (c in auth.cookies) {
            if (c.url.isBlank()) continue
            try {
                val sameSite = c.sameSite.uppercase()
                // Chromium rejects SameSite=None cookies that aren't Secure — coerce so they seed.
                val secure = c.secure || sameSite == "NONE"
                if (sameSite == "NONE" && !c.secure) {
                    logger.debug(LogCategory.BROWSER, "Forcing Secure on a SameSite=None cookie (Chromium requires it)", mapOf("name" to c.name))
                }
                val builder = Cookie.newBuilder(cookieDomain(c.url))
                    .name(c.name).value(c.value).path(c.path.ifBlank { "/" })
                    .secure(secure).httpOnly(c.httpOnly)
                if (c.expirationEpochMs > 0) builder.expirationTime(Timestamp.fromMillis(c.expirationEpochMs))
                builder.sameSite(
                    when (sameSite) {
                        "NONE" -> SameSite.NONE
                        "STRICT" -> SameSite.STRICT_MODE
                        else -> SameSite.LAX_MODE
                    }
                )
                store.set(builder.build()); set++
            } catch (e: Exception) {
                logger.warn(
                    LogCategory.BROWSER,
                    "Cookie rejected",
                    mapOf("name" to c.name, "url" to LogSanitizer.maskUriParams(c.url), "error" to (e.message ?: "unknown"))
                )
            }
        }
        if (set > 0) try { store.persist() } catch (_: Exception) {}

        val headers = LinkedHashMap<String, String>()
        headers.putAll(auth.headers)
        auth.basicAuth?.let { ba ->
            val enc = Base64.getEncoder().encodeToString("${ba.username}:${ba.password}".toByteArray(Charsets.UTF_8))
            headers["Authorization"] = "Basic $enc"
        }
        installHeaderCallback(profile, headers)
    }

    /**
     * (Re)install the request-header callback, replacing any from a previous run on
     * a persistent profile. Empty [headers] = pass-through, clearing stale ones.
     */
    private fun installHeaderCallback(profile: Profile, headers: Map<String, String>) {
        try {
            profile.network().set(BeforeStartTransactionCallback::class.java, BeforeStartTransactionCallback { params ->
                val merged = ArrayList(params.httpHeaders())
                headers.forEach { (k, v) ->
                    merged.removeAll { it.name().equals(k, ignoreCase = true) }
                    merged.add(HttpHeader.of(k, v))
                }
                BeforeStartTransactionCallback.Response.override(merged)
            })
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Header injection failed", mapOf("error" to (e.message ?: "unknown")))
        }
    }

    /** URL or bare host -> cookie domain (JxBrowser's Cookie.newBuilder takes a domain). */
    internal fun cookieDomain(raw: String): String {
        var s = raw.trim()
        val scheme = s.indexOf("://"); if (scheme >= 0) s = s.substring(scheme + 3)
        s = s.substringBefore('/').substringBefore('?').substringBefore('#') // authority
        s = s.substringAfterLast('@') // drop userinfo (user:pass@) before the port split
        if (s.startsWith("[")) return s.substringBefore(']') + "]" // IPv6 literal: keep through ']'
        return s.substringBefore(':') // drop port
    }

    // ---- profile bookkeeping ----

    internal fun sanitize(id: String): String = id.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    // One mutex per distinct named profileId, cached for the process lifetime (few/long-lived ids).
    private fun mutexFor(id: String): Mutex = idMutexes.computeIfAbsent(id) { Mutex() }

    private fun sweepOrphansOnce() {
        if (sweptOrphans) return
        synchronized(this) {
            if (sweptOrphans) return
            FluckEngine.cleanupOrphanedRpaProfiles(EPHEMERAL_PREFIX)
            sweptOrphans = true
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                if (metaFile.exists()) decodeMeta(metaFile.readText()).forEach { meta[it.id] = it }
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Could not load managed-profile metadata", error = e)
            }
            loaded = true
        }
    }

    // Synchronized + atomic temp-file rename so concurrent writers can't interleave
    // and a crash mid-write can't truncate the live meta.json.
    @Synchronized
    private fun persistMeta() {
        try {
            val tmp = File(metaDir, "meta.json.tmp")
            tmp.writeText(encodeMeta(meta.values.toList()))
            try {
                Files.move(tmp.toPath(), metaFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                Files.move(tmp.toPath(), metaFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Could not persist managed-profile metadata", error = e)
        }
    }

    /**
     * Enforce the named-profile disk cap (LRU). Cheap gate on cached sizes; only
     * walks the FS when over cap. A freshly-created profile caches diskBytes=0 until
     * its first dispose refreshes it, so the cap can be transiently exceeded.
     */
    private suspend fun evictIfNeeded() = withContext(Dispatchers.IO) {
        try {
            if (meta.values.sumOf { it.diskBytes } <= diskCapBytes) return@withContext
            val candidates = meta.values.map { m ->
                val s = dirSize(File(m.path)); meta[m.id] = m.copy(diskBytes = s)
                EvictCandidate(m.id, m.name, m.lastUsedMs, s)
            }
            val victims = selectEvictionVictims(candidates, inUse.toSet(), diskCapBytes)
            if (victims.isNotEmpty()) {
                for (id in victims) {
                    val vm = mutexFor(id)
                    if (!vm.tryLock()) continue
                    try {
                        val m = meta[id] ?: continue
                        FluckEngine.findProfile(m.name)?.let { FluckEngine.deleteRpaProfile(it) }
                        meta.remove(id)
                        logger.info(LogCategory.BROWSER, "Evicted LRU managed profile", mapOf("id" to id))
                    } finally {
                        vm.unlock()
                    }
                }
                persistMeta()
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Managed-profile eviction failed", error = e)
        }
    }

    private fun dirSize(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    internal data class EvictCandidate(val id: String, val name: String, val lastUsedMs: Long, val sizeBytes: Long)

    /**
     * Pure LRU victim selection (engine-free, unit-tested): oldest-first, skipping
     * in-use names, until the projected total is at/below [capBytes]. Empty when under.
     */
    internal fun selectEvictionVictims(
        candidates: List<EvictCandidate>,
        inUseNames: Set<String>,
        capBytes: Long,
    ): List<String> {
        var total = candidates.sumOf { it.sizeBytes }
        if (total <= capBytes) return emptyList()
        val victims = mutableListOf<String>()
        for (c in candidates.filter { it.name !in inUseNames }.sortedBy { it.lastUsedMs }) {
            if (total <= capBytes) break
            victims.add(c.id)
            total -= c.sizeBytes
        }
        return victims
    }
}
