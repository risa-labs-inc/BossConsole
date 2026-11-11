package ai.rever.boss.plugin.browser.autofill

import ai.rever.boss.config.ConfigLoader
import ai.rever.boss.config.JxBrowserConfig
import ai.rever.boss.plugin.browser.BrowserInjectDispatcher
import ai.rever.boss.plugin.browser.BrowserServiceImpl
import ai.rever.boss.plugin.browser.BrowserSettings
import ai.rever.boss.plugin.browser.FluckEngine
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.services.auth.AuthStateManager
import ai.rever.boss.services.supabase.SecretService
import ai.rever.boss.services.supabase.models.SecretEntryWithSharing
import ai.rever.boss.services.supabase.models.UpdateSecretRequest
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.browser.callback.SaveCreditCardCallback
import com.teamdev.jxbrowser.browser.callback.SavePasswordCallback
import com.teamdev.jxbrowser.browser.callback.SaveUserDataProfileCallback
import com.teamdev.jxbrowser.browser.callback.UpdatePasswordCallback
import com.teamdev.jxbrowser.browser.callback.internal.ShowSuggestionsCallback
import com.teamdev.jxbrowser.browser.event.internal.SuggestionsHidden
import com.teamdev.jxbrowser.browser.event.internal.SuggestionsUpdated
import com.teamdev.jxbrowser.card.CreditCard
import com.teamdev.jxbrowser.engine.Engine
import com.teamdev.jxbrowser.engine.RenderingMode
import com.teamdev.jxbrowser.frame.Frame
import com.teamdev.jxbrowser.internal.rpc.Suggestion
import com.teamdev.jxbrowser.js.JsObject
import com.teamdev.jxbrowser.navigation.event.NavigationStarted
import com.teamdev.jxbrowser.password.PasswordRecord
import com.teamdev.jxbrowser.profile.Profile
import com.teamdev.jxbrowser.ui.KeyCode
import com.teamdev.jxbrowser.user.Address
import com.teamdev.jxbrowser.user.UserDataProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.time.YearMonth
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Native Chromium autofill backed by BOSS Secret Manager.
 *
 * SM is the source of truth; the default profile's PasswordStore / CreditCards / UserDataProfiles
 * are a mirror of it. The mirror only ever removes records it added ([MirrorIndex]) and removes all
 * of them on sign-out and quit. Saves go to SM first, then to Chromium. RPA / ephemeral / incognito
 * profiles and other engines are never touched. Values are never logged.
 */
object NativeAutofill {
    const val FLAG_PROP = "boss.browser.nativeAutofill"

    private val logger = BossLogger.forComponent("NativeAutofill")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val syncRequests = Channel<Unit>(Channel.CONFLATED)
    private val capture = CredentialCapture()
    private val fingerprints = ConcurrentHashMap<String, String>()
    private val attached = Collections.synchronizedMap(WeakHashMap<Browser, Boolean>())
    private val popups = ConcurrentHashMap<Browser, OpenSuggestions>()
    private val index by lazy { MirrorIndex(BossDirectories.resolve("native-autofill").resolve("mirror-index.json")) }

    @Volatile private var lastRows: List<SecretEntryWithSharing> = emptyList()

    @Volatile private var syncedUserId: String? = null

    @Volatile private var started = false

    // onEngineReady runs before FluckEngine publishes the engine, so keep our own reference.
    @Volatile private var readyEngine: Engine? = null

    private const val PERIODIC_SYNC_MS = 5 * 60_000L
    private const val PAGE_SIZE = 200
    private const val MAX_ROWS = 5_000

    val enabled: Boolean by lazy { !FluckEngine.isFalsyFlag(ConfigLoader.getConfig("BOSS_NATIVE_AUTOFILL")) }

    /** Publish the stand-down flag for plugin-side autofill, then start. Call before any browser exists. */
    fun publishFlag() {
        System.setProperty(FLAG_PROP, enabled.toString())
        if (enabled) start()
    }

    @OptIn(FlowPreview::class)
    @Synchronized
    private fun start() {
        if (started) return
        started = true
        scope.launch {
            for (req in syncRequests) {
                runCatching { syncNow() }.onFailure {
                    logger.warn(LogCategory.BROWSER, "Autofill mirror sync failed", mapOf("errorType" to it::class.simpleName))
                }
            }
        }
        scope.launch {
            AuthStateManager.currentUser.map { it?.id }.distinctUntilChanged().collect { id ->
                if (id == null) clear("sign-out") else requestSync()
            }
        }
        scope.launch { SecretService.changes.debounce(750).collect { requestSync() } }
        scope.launch {
            while (true) {
                delay(PERIODIC_SYNC_MS)
                requestSync()
            }
        }
    }

    fun requestSync() {
        if (enabled) syncRequests.trySend(Unit)
    }

    /** Called by FluckEngine once an engine is up (first boot and every recycle). */
    internal fun onEngineReady(engine: Engine) {
        if (!enabled) return
        readyEngine = engine
        runCatching { engine.profiles().defaultProfile().preferences().enableAutofill() }
        requestSync()
    }

    // ------------------------------------------------------------------ mirror

    private suspend fun syncNow() =
        mutex.withLock {
            val user = AuthStateManager.currentUser.value ?: return@withLock clearLocked("no user")
            if (syncedUserId != null && syncedUserId != user.id) clearLocked("user changed")
            val profile = mirrorProfile() ?: return@withLock
            // A failed read is never an empty vault: leave Chromium as it is.
            val rows =
                fetchAllRows() ?: return@withLock logger.warn(LogCategory.BROWSER, "Autofill mirror sync skipped: Secret Manager read failed")
            lastRows = rows
            syncedUserId = user.id
            val desired = AutofillMapping.map(rows)
            if (!profile.preferences().isAutofillEnabled) profile.preferences().enableAutofill()
            val stores = ChromiumStores(profile)
            val owned = index.keys(profile.path())
            val plan = MirrorDiff.plan(desired, stores.presentKeys(), owned, fingerprints)
            for (key in plan.remove) {
                runCatching { stores.remove(key) }
                index.remove(profile.path(), key)
                fingerprints.remove(key)
            }
            var written = 0
            for (item in plan.write) {
                val ok = runCatching { stores.write(item, replaceOwned = item.key in owned) }.isSuccess
                if (ok) {
                    index.add(profile.path(), item.key)
                    fingerprints[item.key] = item.fingerprint
                    written++
                }
            }
            index.save()
            logger.info(
                LogCategory.BROWSER,
                "Autofill mirror synced",
                mapOf(
                    "rows" to rows.size,
                    "mirrored" to desired.size,
                    "written" to written,
                    "removed" to plan.remove.size,
                    "skippedForeign" to plan.skippedForeign.size,
                ),
            )
        }

    private suspend fun fetchAllRows(): List<SecretEntryWithSharing>? {
        val out = mutableListOf<SecretEntryWithSharing>()
        var offset = 0
        while (offset < MAX_ROWS) {
            val page = SecretService.getUserSecretsWithSharingInfo(PAGE_SIZE, offset).getOrNull() ?: return null
            out += page.data
            if (!page.hasMore || page.data.isEmpty()) break
            offset += page.data.size
        }
        return out
    }

    private fun mirrorProfile(): Profile? =
        runCatching {
            (FluckEngine.currentEngine ?: readyEngine)?.takeIf { !it.isClosed }?.profiles()?.defaultProfile()
        }.getOrNull()

    private suspend fun clear(reason: String) = mutex.withLock { clearLocked(reason) }

    private fun clearLocked(reason: String) {
        capture.clear()
        lastRows = emptyList()
        syncedUserId = null
        val profile = mirrorProfile()
        val removed =
            if (profile == null) {
                0
            } else {
                val stores = ChromiumStores(profile)
                val keys = index.keys(profile.path())
                keys.forEach { key -> runCatching { stores.remove(key) } }
                index.clear(profile.path())
                keys.size
            }
        fingerprints.clear()
        runCatching { index.save() }
        if (removed > 0) logger.info(LogCategory.BROWSER, "Autofill mirror cleared", mapOf("reason" to reason, "removed" to removed))
    }

    /** Shutdown step; runs before the engine closes. */
    fun clearOnExit() {
        if (!enabled) return
        runBlocking { withTimeoutOrNull(3_000) { clear("quit") } }
    }

    // ------------------------------------------------------------------ browsers

    /**
     * Wire save callbacks, host-side capture and (HARDWARE_ACCELERATED only) the visible dropdown
     * into [browser]. [dropdown] is false for Swing popup windows, whose default popup is visible.
     */
    @Suppress("TooGenericExceptionCaught")
    internal fun attach(
        browser: Browser,
        dropdown: Boolean = true,
    ) {
        if (!enabled || !eligible(browser) || attached.put(browser, true) != null) return
        try {
            installSaveCallbacks(browser)
            installCapture(browser)
            if (dropdown && JxBrowserConfig.renderingMode == RenderingMode.HARDWARE_ACCELERATED) installDropdown(browser)
        } catch (e: Throwable) {
            if (e is Error && e !is LinkageError) throw e
            logger.warn(LogCategory.BROWSER, "Native autofill attach failed", mapOf("errorType" to e::class.simpleName))
        }
    }

    private fun eligible(browser: Browser): Boolean =
        runCatching {
            val profile = browser.profile()
            val engine = browser.engine()
            profile.isDefault && !profile.isIncognito && (engine === FluckEngine.currentEngine || engine === readyEngine)
        }.getOrDefault(false)

    private fun installCapture(browser: Browser) {
        val bridge =
            NativeCaptureBridge { payload ->
                val (user, pass) = CredentialCapture.parse(payload) ?: return@NativeCaptureBridge
                val url = runCatching { browser.url() }.getOrNull() ?: return@NativeCaptureBridge
                capture.record(url, user, pass)
            }
        val inject = { frame: Frame ->
            if (frame.isMain) {
                val slot = "b" + UUID.randomUUID().toString().replace("-", "").take(24)
                val window = frame.executeJavaScript<JsObject>("window")
                if (window != null) {
                    window.putProperty(slot, bridge)
                    frame.executeJavaScript<Any?>(
                        "(function(){var b=window.$slot;try{delete window.$slot;}catch(e){window.$slot=undefined;}" +
                            "(${CredentialCapture.SCRIPT})(b);})();",
                    )
                }
            }
        }
        BrowserInjectDispatcher.register(browser) { frame -> runCatching { inject(frame) } }
        scope.launch { runCatching { browser.mainFrame().ifPresent { inject(it) } } }
    }

    private fun installSaveCallbacks(browser: Browser) {
        browser.set(
            SavePasswordCallback::class.java,
            SavePasswordCallback { p, action ->
                onPasswordSave(browser, p.url(), p.login(), update = false, commit = action::save, never = action::neverSave, ignore = action::ignore)
            },
        )
        browser.set(
            UpdatePasswordCallback::class.java,
            UpdatePasswordCallback { p, action ->
                onPasswordSave(browser, p.url(), p.login(), update = true, commit = action::update, never = null, ignore = action::ignore)
            },
        )
        browser.set(
            SaveCreditCardCallback::class.java,
            SaveCreditCardCallback { p, action -> onCardSave(browser, p.creditCard(), action::save, action::decline) },
        )
        browser.set(
            SaveUserDataProfileCallback::class.java,
            SaveUserDataProfileCallback { p, action -> onAddressSave(browser, p.profile(), action::save, action::decline) },
        )
    }

    private fun canSave(): Boolean = BrowserSettings.offerToSavePasswords && AuthStateManager.currentUser.value != null

    private fun onPasswordSave(
        browser: Browser,
        url: String,
        login: String,
        update: Boolean,
        commit: () -> Unit,
        never: (() -> Unit)?,
        ignore: () -> Unit,
    ) {
        val origin = AutofillMapping.normalizeOrigin(url)
        if (!canSave() || origin == null) return safely(ignore)
        val captured = capture.take(url, login)
        if (captured == null) {
            logger.info(LogCategory.BROWSER, "Password save skipped: no captured value", mapOf("origin" to origin))
            return safely(ignore)
        }
        val key = loginKey(origin, login)
        val existing = findLoginRow(origin, login)
        if (existing != null && existing.password == captured.password) {
            index.add(currentProfilePath(), key)
            return safely(commit)
        }
        val host = origin.substringAfter("://")
        prompt(
            browser,
            title = if (update || existing != null) "Update password for $host?" else "Save password for $host?",
            detail = login,
            saveLabel = if (update || existing != null) "Update" else "Save",
            allowNever = never != null,
        ) { choice ->
            when (choice) {
                SaveChoice.SAVE ->
                    scope.launch {
                        val ok = writeLogin(origin, login, captured.password, existing)
                        if (ok) {
                            index.add(currentProfilePath(), key)
                            runCatching { index.save() }
                            safely(commit)
                            logger.info(LogCategory.BROWSER, "Password saved to Secret Manager", mapOf("origin" to origin, "updated" to (existing != null)))
                        } else {
                            safely(ignore)
                        }
                        requestSync()
                    }
                SaveChoice.NEVER -> safely(never ?: ignore)
                SaveChoice.NOT_NOW -> safely(ignore)
            }
        }
    }

    private fun findLoginRow(
        origin: String,
        login: String,
    ): SecretEntryWithSharing? =
        lastRows
            .filter { it.isOwner || it.canManageOrDeny }
            .firstOrNull { row ->
                val item = AutofillMapping.map(row.website, row.username, row.password, row.notes, row.tags)
                item is MirrorItem.Login && item.origin == origin && CredentialCapture.sameLogin(item.username, login)
            }

    private suspend fun writeLogin(
        origin: String,
        login: String,
        password: String,
        existing: SecretEntryWithSharing?,
    ): Boolean {
        val result =
            if (existing != null) {
                SecretService.updateSecret(
                    UpdateSecretRequest(
                        secretId = existing.id,
                        website = existing.website,
                        username = existing.username,
                        password = password,
                        notes = existing.notes,
                        expirationDate = existing.expirationDate,
                        tags = existing.tags,
                        twofaEnabled = existing.metadata?.twofaEnabled ?: false,
                        twofaType = existing.metadata?.twofaType,
                        recoveryCodes = existing.metadata?.recoveryCodes ?: emptyList(),
                    ),
                )
            } else {
                SecretService.createSecret(AutofillMapping.loginRequest(origin, login, password))
            }
        return result.isSuccess.also {
            if (!it) logger.warn(LogCategory.BROWSER, "Password save to Secret Manager failed", mapOf("origin" to origin))
        }
    }

    private fun onCardSave(
        browser: Browser,
        card: CreditCard,
        commit: () -> Unit,
        decline: () -> Unit,
    ) {
        val number = card.number().orEmpty()
        if (!canSave() || number.any { !it.isDigit() } || number.length !in 12..19) return safely(decline)
        val expiry = runCatching { YearMonth.of(card.expirationYear(), card.expirationMonth()) }.getOrNull() ?: return safely(decline)
        val key = cardKey(number.takeLast(4), expiry)
        val brand = AutofillMapping.brandOf(number)
        prompt(browser, "Save card?", "$brand ending ${number.takeLast(4)}", "Save", allowNever = false) { choice ->
            if (choice != SaveChoice.SAVE) return@prompt safely(decline)
            scope.launch {
                val known = AutofillMapping.map(lastRows).containsKey(key)
                val ok = known || SecretService.createSecret(AutofillMapping.cardRequest(number, expiry, card.cardholder().orEmpty())).isSuccess
                if (ok) {
                    index.add(currentProfilePath(), key)
                    runCatching { index.save() }
                    safely(commit)
                } else {
                    logger.warn(LogCategory.BROWSER, "Card save to Secret Manager failed")
                    safely(decline)
                }
                requestSync()
            }
        }
    }

    private fun onAddressSave(
        browser: Browser,
        profile: UserDataProfile,
        commit: () -> Unit,
        decline: () -> Unit,
    ) {
        if (!canSave()) return safely(decline)
        val fields = fieldsOf(profile)
        if (fields.isEmpty()) return safely(decline)
        val key = addressKey(fields)
        val detail = listOf(fields.street, fields.city).filter { it.isNotBlank() }.joinToString(", ")
        prompt(browser, "Save address?", detail, "Save", allowNever = false) { choice ->
            if (choice != SaveChoice.SAVE) return@prompt safely(decline)
            scope.launch {
                val known = AutofillMapping.map(lastRows).containsKey(key)
                val taken = lastRows.mapTo(mutableSetOf()) { it.website }
                val ok = known || SecretService.createSecret(AutofillMapping.addressRequest(fields, taken)).isSuccess
                if (ok) {
                    index.add(currentProfilePath(), key)
                    runCatching { index.save() }
                    safely(commit)
                } else {
                    logger.warn(LogCategory.BROWSER, "Address save to Secret Manager failed")
                    safely(decline)
                }
                requestSync()
            }
        }
    }

    private fun currentProfilePath(): String = mirrorProfile()?.path().orEmpty()

    private fun safely(f: () -> Unit) {
        runCatching(f)
    }

    private fun prompt(
        browser: Browser,
        title: String,
        detail: String,
        saveLabel: String,
        allowNever: Boolean,
        onChoice: (SaveChoice) -> Unit,
    ) = onEdt {
        val anchor = anchorFor(browser) ?: fallbackAnchor()
        if (anchor == null) {
            onChoice(SaveChoice.NOT_NOW)
            return@onEdt
        }
        SavePrompt(anchor, title, detail, saveLabel, allowNever) { choice ->
            logger.info(LogCategory.BROWSER, "Autofill save prompt answered", mapOf("choice" to choice.name))
            onChoice(choice)
        }.show()
        logger.info(LogCategory.BROWSER, "Autofill save prompt shown", mapOf("title" to title.substringBefore(" for ")))
    }

    private fun anchorFor(browser: Browser): AutofillAnchor? =
        BrowserServiceImpl.getActiveHandles().firstOrNull { it.getRawBrowser() === browser }?.autofillAnchor()

    private fun fallbackAnchor(): AutofillAnchor? {
        val w = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow ?: return null
        if (!w.isShowing) return null
        return AutofillAnchor(w, Rectangle(w.locationOnScreen, w.size))
    }

    // ------------------------------------------------------------------ dropdown

    private class OpenSuggestions(
        val window: SuggestionsWindow,
        @Volatile var suggestions: List<Suggestion>,
    )

    private fun installDropdown(browser: Browser) {
        // Internal JxBrowser API: if it is gone, JxBrowser's own popup stays in place.
        val installed =
            runCatching {
                browser.set(
                    ShowSuggestionsCallback::class.java,
                    ShowSuggestionsCallback { p, action ->
                        val type = p.popupType().name
                        val suggestions = p.suggestions().toList()
                        val loc = p.location()
                        onEdt { showDropdown(browser, type, suggestions, Point(loc.x, loc.y), action) }
                    },
                )
                browser.on(SuggestionsUpdated::class.java) { e ->
                    val list = e.suggestions().toList()
                    onEdt {
                        popups[browser]?.let {
                            it.suggestions = list
                            it.window.update(list.map(::rowOf))
                        }
                    }
                }
                browser.on(SuggestionsHidden::class.java) { onEdt { popups.remove(browser)?.window?.dispose() } }
                browser.navigation().on(NavigationStarted::class.java) { onEdt { popups.remove(browser)?.window?.cancel() } }
            }
        installed.onFailure {
            logger.warn(LogCategory.BROWSER, "Autofill dropdown unavailable - keeping JxBrowser default", mapOf("errorType" to it::class.simpleName))
        }
    }

    private fun showDropdown(
        browser: Browser,
        type: String,
        suggestions: List<Suggestion>,
        location: Point,
        action: ShowSuggestionsCallback.Action,
    ) {
        popups.remove(browser)?.window?.dispose()
        val anchor = anchorFor(browser)
        if (anchor == null || suggestions.isEmpty() || (type == "PASSWORDS" && !BrowserSettings.suggestPasswords)) {
            logger.debug(LogCategory.BROWSER, "Autofill dropdown declined", mapOf("type" to type, "anchored" to (anchor != null)))
            runCatching { action.cancel() }
            return
        }
        lateinit var state: OpenSuggestions
        val window =
            SuggestionsWindow(
                anchor,
                onAccept = { i ->
                    popups.remove(browser, state)
                    logger.info(LogCategory.BROWSER, "Autofill dropdown accepted", mapOf("type" to type))
                    state.suggestions.getOrNull(i)?.let { s -> runCatching { action.accept(s) } } ?: runCatching { action.cancel() }
                },
                onCancel = {
                    popups.remove(browser, state)
                    runCatching { action.cancel() }
                },
            )
        state = OpenSuggestions(window, suggestions)
        popups[browser] = state
        window.show(suggestions.map(::rowOf), location)
        logger.info(LogCategory.BROWSER, "Autofill dropdown shown", mapOf("type" to type, "count" to suggestions.size))
    }

    private fun rowOf(s: Suggestion): SuggestionRow {
        val secondary =
            when {
                s.hasHiddenPassword() -> s.hiddenPassword
                s.hasCreditCardInfo() -> s.creditCardInfo
                s.hasAddressOrFullname() -> s.addressOrFullname
                else -> ""
            }
        return SuggestionRow(s.value.orEmpty(), secondary.orEmpty())
    }

    /**
     * Called from the browser's PressKeyCallback. True = the key drove the open dropdown and must
     * not reach the page. Enter is only taken when a row is highlighted.
     */
    internal fun consumeSuggestionKey(
        browser: Browser,
        key: KeyCode,
    ): Boolean {
        val open = popups[browser] ?: return false
        return when (key) {
            KeyCode.KEY_CODE_DOWN -> true.also { onEdt { open.window.moveSelection(1) } }
            KeyCode.KEY_CODE_UP -> true.also { onEdt { open.window.moveSelection(-1) } }
            KeyCode.KEY_CODE_ESCAPE -> true.also { onEdt { open.window.cancel() } }
            KeyCode.KEY_CODE_RETURN ->
                if (open.window.hasSelection) {
                    onEdt { open.window.acceptSelected() }
                    true
                } else {
                    false
                }
            else -> false
        }
    }

    // ------------------------------------------------------------------ Chromium stores

    internal fun fieldsOf(p: UserDataProfile): AddressFields {
        val a = p.address()
        return AddressFields(
            name = p.fullName().orEmpty(),
            email = p.email().orEmpty(),
            company = p.companyName().orEmpty(),
            phone = p.phoneNumber().orEmpty(),
            street = a?.streetAddress().orEmpty(),
            city = a?.city().orEmpty(),
            state = a?.state().orEmpty(),
            zip = a?.zip().orEmpty(),
            country = a?.countryCode().orEmpty(),
        )
    }

    private class ChromiumStores(
        private val profile: Profile,
    ) {
        private fun loginKeyOf(r: PasswordRecord) = loginKey(AutofillMapping.normalizeOrigin(r.url()) ?: r.url(), r.login())

        private fun cardKeyOf(c: CreditCard) = cardKey(c.number().takeLast(4), YearMonth.of(c.expirationYear(), c.expirationMonth()))

        fun presentKeys(): Set<String> {
            val out = mutableSetOf<String>()
            runCatching { profile.passwordStore().allSaved().forEach { out += loginKeyOf(it) } }
            runCatching { profile.creditCards().all().forEach { c -> runCatching { out += cardKeyOf(c) } } }
            runCatching { profile.userDataProfiles().all().forEach { out += addressKey(fieldsOf(it)) } }
            return out
        }

        fun remove(key: String) {
            when {
                key.startsWith("pw|") -> {
                    val store = profile.passwordStore()
                    store.allSaved().filter { loginKeyOf(it) == key }.forEach(store::remove)
                }
                key.startsWith("card|") -> {
                    val cards = profile.creditCards()
                    cards.all().filter { runCatching { cardKeyOf(it) }.getOrNull() == key }.forEach(cards::remove)
                }
                key.startsWith("addr|") -> {
                    val profiles = profile.userDataProfiles()
                    profiles.all().filter { addressKey(fieldsOf(it)) == key }.forEach(profiles::remove)
                }
            }
        }

        fun write(
            item: MirrorItem,
            replaceOwned: Boolean,
        ) {
            if (replaceOwned) remove(item.key)
            when (item) {
                is MirrorItem.Login ->
                    profile.passwordStore().add(PasswordRecord.newBuilder(item.origin + "/", item.password).login(item.username).build())
                is MirrorItem.Card ->
                    profile.creditCards().add(
                        CreditCard
                            .newBuilder(item.number, item.expiry)
                            .apply { if (item.holder.isNotBlank()) cardholder(item.holder) }
                            .build(),
                    )
                is MirrorItem.Address -> {
                    val f = item.fields
                    val address =
                        Address
                            .newBuilder(f.country.ifBlank { "US" })
                            .streetAddress(f.street)
                            .city(f.city)
                            .state(f.state)
                            .zipCode(f.zip)
                            .build()
                    profile.userDataProfiles().add(
                        UserDataProfile
                            .newBuilder(address)
                            .fullName(f.name)
                            .email(f.email)
                            .phoneNumber(f.phone)
                            .company(f.company)
                            .build(),
                    )
                }
            }
        }
    }
}
