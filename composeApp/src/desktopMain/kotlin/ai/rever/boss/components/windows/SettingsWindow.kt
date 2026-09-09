package ai.rever.boss.components.windows

import BossTheme
import ai.rever.boss.components.home.LocalPanelRegistry
import ai.rever.boss.components.plugin.registries.SettingsPageRegistryImpl
import ai.rever.boss.components.settings.keymap.EditableKeymapSettings
import ai.rever.boss.components.settings.search.LocalSettingsHighlight
import ai.rever.boss.components.settings.search.SettingsHighlight
import ai.rever.boss.components.settings.search.SettingsSearchHit
import ai.rever.boss.components.settings.search.SettingsSearchIndex
import ai.rever.boss.components.settings.search.SettingsSearchMatcher
import ai.rever.boss.components.settings.search.SettingsSearchState
import ai.rever.boss.components.settings.search.handleSettingsSearchKey
import ai.rever.boss.components.settings.search.highlightFor
import ai.rever.boss.components.settings.search.pluginPageEntry
import ai.rever.boss.components.settings.search.revealPanel
import ai.rever.boss.components.settings.search.withReachableSignposts
import ai.rever.boss.components.settings.sections.*
import ai.rever.boss.components.settings.shared.SettingsTheme.AccentColor
import ai.rever.boss.components.settings.shared.SettingsTheme.BackgroundColor
import ai.rever.boss.components.settings.shared.SettingsTheme.BorderColor
import ai.rever.boss.components.settings.shared.SettingsTheme.SurfaceColor
import ai.rever.boss.components.settings.shared.SettingsTheme.TextMuted
import ai.rever.boss.components.settings.shared.SettingsTheme.TextPrimary
import ai.rever.boss.components.settings.shared.SettingsTheme.TextSecondary
import ai.rever.boss.components.settings.sidebar.SettingsSection
import ai.rever.boss.components.settings.sidebar.SettingsSidebar
import ai.rever.boss.config.BrowserEngineSettingsManager
import ai.rever.boss.focusmode.FocusModeSettingsManager
import ai.rever.boss.performance.PerformanceSettingsManager
import ai.rever.boss.plugin.ui.BossAlertDialog
import ai.rever.boss.plugin.ui.BossThemeController
import ai.rever.boss.plugin.ui.LocalHeavyweightOverlays
import ai.rever.boss.run.RunnerSettingsManager
import ai.rever.boss.scrollbar.ScrollbarSettingsManager
import ai.rever.boss.startup.StartupSettingsManager
import ai.rever.boss.terminal.TerminalLinkSettingsManager
import ai.rever.boss.updater.UpdateSettingsSection
import ai.rever.boss.utils.DisplayUtils
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.ApplyBossWindowIcon
import ai.rever.boss.window.BossWindowIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.launch
import java.awt.Frame

private val logger = BossLogger.forComponent("SettingsWindow")

@Composable
actual fun SettingsWindow(
    onClose: () -> Unit,
    initialSection: String?,
    focusRequest: Int,
    sectionRequest: Int,
    requestedHighlight: SettingsHighlight?,
    highlightRequest: Int,
) {
    // No local `isOpen` flag. Composition is already gated by `SettingsWindowState.visible`, and a
    // second source of truth for "is this window up" is the bug this whole change fixes, waiting to
    // happen: if the two ever disagreed with `visible` true and nothing composed, every later
    // open() would take the focusRequest branch and Settings would be a dead button again - this
    // time permanently, because nothing would be left to reset it. onCloseRequest reports upward
    // and lets the one owner decide.
    val windowState =
        rememberWindowState(
            size = DisplayUtils.calculateSettingsWindowSize(),
            position = WindowPosition.Aligned(Alignment.Center),
        )
    // Owned here rather than in SettingsContent because the key handler below is a Window
    // parameter: it is the only place Cmd+F fires whatever holds focus inside.
    val searchState = remember { SettingsSearchState() }

    Window(
        onCloseRequest = onClose,
        title = "BOSS Settings",
        state = windowState,
        icon = BossWindowIcon.painter,
        onPreviewKeyEvent = { event -> handleSettingsSearchKey(event, searchState) },
    ) {
        ApplyBossWindowIcon(window)

        // Raise this window whenever Settings is asked for again. Keyed on the counter, so it
        // runs once per request and once on the first composition - which is harmless, the
        // window is brand new and coming to the front is what it should be doing anyway.
        //
        // Deiconify FIRST, and through the AWT frame rather than only through WindowState.
        // `toFront` on a minimised window is a no-op on every platform, so without a restore
        // that has actually landed, clicking Settings leaves the user exactly where the
        // original bug left them. Writing WindowState alone does not land in time: it mutates
        // snapshot state, which Compose applies to the frame in a later pass, so the `toFront`
        // below would still run against an iconified frame. The frame write takes effect now;
        // the WindowState write keeps Compose's own model in step with it.
        LaunchedEffect(focusRequest) {
            // Unguarded, because clearing the bit is idempotent on a window that is not
            // minimised - and any guard would have to read the FRAME, never WindowState. The
            // argument above is precisely that WindowState lags the frame, so gating the
            // restore on it reintroduces the bug in the window where the two disagree.
            window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
            if (windowState.isMinimized) {
                windowState.isMinimized = false
            }
            window.toFront()
            window.requestFocus()
        }

        // Opt this window's dialogs back OUT of heavyweight overlays.
        //
        // SettingsWindow is composed from inside the main window's subtree (BossAppDialogs), so
        // it inherits LocalHeavyweightOverlays = true from BossWindow. There is no browser
        // surface here to escape, and routing anyway would be actively wrong: the heavyweight
        // window measures LocalAwtWindow, which is still the MAIN window, so a settings dialog
        // would open centered over the main window and - being always-on-top, and deliberately
        // not dismissed by focus moving within the same application - keep floating above it.
        CompositionLocalProvider(LocalHeavyweightOverlays provides false) {
            BossTheme {
                SettingsContent(
                    initialSection = initialSection,
                    sectionRequest = sectionRequest,
                    requestedHighlight = requestedHighlight,
                    highlightRequest = highlightRequest,
                    searchState = searchState,
                )
            }
        }
    }
}

@Composable
private fun SettingsContent(
    initialSection: String? = null,
    sectionRequest: Int = 0,
    requestedHighlight: SettingsHighlight? = null,
    highlightRequest: Int = 0,
    searchState: SettingsSearchState = remember { SettingsSearchState() },
) {
    var selectedSection by remember { mutableStateOf(initialSectionFor(initialSection, visiblePageIds())) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Inherited from BossAppCompositionLocals: SettingsWindow composes inside BossAppDialogs, which
    // is inside that provider, so this is the MAIN window's registry - which is the one whose
    // sidebar a signpost opens.
    val panelRegistry = LocalPanelRegistry.current

    // Plugin-contributed settings pages: reactive to plugin lifecycle + RBAC.
    val registryPages by SettingsPageRegistryImpl.pages.collectAsState()
    val registryAccess by SettingsPageRegistryImpl.access.collectAsState()
    val pluginPages =
        remember(registryPages, registryAccess) {
            SettingsPageRegistryImpl.visiblePages()
        }
    var selectedPluginPageId by remember { mutableStateOf(initialPluginPageFor(initialSection, visiblePageIds())) }

    // --- Search ---------------------------------------------------------------------------------

    // What the search can find. Plugin pages are merged in here rather than declared in the index,
    // which is what keeps results honest about RBAC and plugin lifecycle for free: a page the user
    // cannot see is not in `pluginPages`, so it is not searchable either.
    // Signposts are filtered here rather than declared conditionally, for the same reason plugin
    // pages are merged here rather than indexed: reachability is a live fact about this window, and
    // the index is a compile-time list. See withReachableSignposts.
    val reachableBuiltIns = SettingsSearchIndex.builtIn.withReachableSignposts()
    val searchEntries =
        remember(pluginPages, reachableBuiltIns) {
            reachableBuiltIns +
                pluginPages.map { pluginPageEntry(it.pageId, it.displayName, it.description) }
        }
    val hits =
        remember(searchState.query, searchEntries) {
            SettingsSearchMatcher.search(searchState.query, searchEntries)
        }
    searchState.hits = hits

    var highlight by remember { mutableStateOf<SettingsHighlight?>(null) }
    var highlightNonce by remember { mutableStateOf(0) }
    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(searchState.focusTick) {
        // Guarded: the field is only in the tree once, but requestFocus throws if it is not
        // attached yet, and this effect also runs once on first composition.
        runCatching { searchFocusRequester.requestFocus() }
    }

    val applyHit: (SettingsSearchHit) -> Unit = { hit ->
        val entry = hit.entry
        when {
            // Asked FIRST, because a signpost is the one hit that does not navigate this window at
            // all - and leaving `selectedSection` where it was is deliberate. The user is being
            // sent to another window; changing this one behind them would be a second navigation
            // they did not ask for, waiting for them when they come back.
            entry.panel != null -> {
                revealPanel(entry.panel, entry.label, panelRegistry, coroutineScope)
            }

            entry.pluginPageId != null -> {
                selectedPluginPageId = entry.pluginPageId
                highlight = null
            }

            entry.section != null -> {
                selectedPluginPageId = null
                selectedSection = entry.section
                // Bumped whether or not a highlight comes of it: the counter is only ever read
                // back off a non-null highlight, and one unconditional bump is cheaper to reason
                // about than a second rule about when it moves.
                highlightNonce += 1
                highlight = highlightFor(entry, highlightNonce)
            }
        }
    }
    searchState.onPick = applyHit

    // Apply a deep link that arrives while this window is ALREADY open.
    //
    // The two `remember`s above only run once, so without this the window raised itself and stayed
    // on whatever page the user last picked - worse than the old behaviour, which at least did
    // nothing visible. Keyed on the request counter rather than on `initialSection`, because asking
    // twice for the same section leaves that string unchanged and a value key would navigate the
    // first time and silently ignore the second.
    //
    // Unresolved does NOTHING, deliberately: an open window is left where the user had it rather
    // than defaulted to FLUCK. It also runs once on the first composition, where it is a no-op -
    // the two remembers have already applied exactly what it computes.
    LaunchedEffect(sectionRequest) {
        when (val link = resolveSettingsDeepLink(initialSection, visiblePageIds())) {
            is SettingsDeepLink.Page -> {
                selectedPluginPageId = link.pageId
            }

            is SettingsDeepLink.Section -> {
                selectedPluginPageId = null
                selectedSection = link.section
            }

            SettingsDeepLink.Unresolved -> {
                // Nothing to show, and nothing visible happens - so this is the only record that
                // the request arrived at all. Worth having: the population that reaches it is a
                // plugin deep-linking to a section or page this build does not have, and from the
                // outside that is indistinguishable from the window ignoring the plugin.
                //
                // Gated on a section having been ASKED for. A plain open() passes null, which
                // resolves to Unresolved as well and runs through here on first composition - so
                // logging unconditionally would file a "deep link named nothing" line every time
                // anyone opened Settings from the menu, drowning the one case this is for.
                if (initialSection != null) {
                    logger.debug(
                        LogCategory.UI,
                        "Settings deep link named nothing this build can show; leaving the window where it was",
                        mapOf("requested" to initialSection),
                    )
                }
            }
        }
    }

    // A highlight asked for from OUTSIDE the window - the global search finding a row by name.
    //
    // Keyed on the holder's request COUNTER, not on the value and not on the nonce. A value key
    // would light the same row once and then do nothing, which is what the nonce exists to fix -
    // but a nonce key has its own blind spot, because "point at nothing" is null and null carries
    // no nonce, so `null -> null` looked like nothing happening while a local highlight stayed
    // armed on a page the window had navigated away from. The counter moves on every request,
    // including one that clears. Runs after the navigation effect above, which puts the row on
    // screen.
    LaunchedEffect(highlightRequest) {
        // Re-stamped with THIS window's counter rather than adopted as-is. The requester has a
        // counter of its own, and both start at zero: reveal row A from the global search
        // (external nonce 1), then pick the same row A in this window's search box (local nonce 1),
        // and the value equals what is already in `highlight` - no state change, the keyed effect
        // in searchTarget never re-runs, and the window visibly does nothing. Which is precisely
        // what the nonce exists to prevent.
        // Assigned unconditionally, null included. `reveal(highlightable = false)` nulls the
        // holder's highlight on purpose - its KDoc argues that pointing at nothing beats leaving
        // the last pick armed on a page it does not belong to - and acting only on a non-null
        // request threw that away: the effect re-ran with the key gone from 1 to null, did
        // nothing, and the local highlight kept pointing at the previous row. `sectionLevel` and
        // `delegated` entries are highlightable = false WITH a real section, so this was reachable
        // from ordinary rows: pick a highlightable Appearance row, then a section-level catch-all
        // elsewhere, then Appearance's own catch-all, and the first row lit up unasked.
        highlightNonce += 1
        highlight = requestedHighlight?.copy(nonce = highlightNonce)
    }

    // If the selected page's plugin is disabled/unloaded, fall back to sections.
    LaunchedEffect(pluginPages) {
        if (selectedPluginPageId != null && pluginPages.none { it.pageId == selectedPluginPageId }) {
            selectedPluginPageId = null
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = BackgroundColor,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Left navigation rail
            SettingsSidebar(
                selectedSection = selectedSection,
                onSectionChange = {
                    selectedPluginPageId = null
                    selectedSection = it
                    // Picking a section by hand is a different intent from following a search hit;
                    // leaving the wash armed would flash a control the user did not ask for.
                    highlight = null
                },
                pluginPages = pluginPages,
                selectedPluginPageId = selectedPluginPageId,
                onPluginPageChange = {
                    selectedPluginPageId = it
                    highlight = null
                },
                query = searchState.query,
                onQueryChange = searchState::updateQuery,
                hits = hits,
                selectedHitIndex = searchState.selectedIndex,
                onHitPicked = applyHit,
                searchFocusRequester = searchFocusRequester,
            )

            // Divider
            Box(
                modifier =
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(BorderColor),
            )

            // Right content area
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(BackgroundColor),
            ) {
                // Content with scrolling
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                ) {
                    val pluginPage = selectedPluginPageId?.let { id -> pluginPages.firstOrNull { it.pageId == id } }
                    CompositionLocalProvider(LocalSettingsHighlight provides highlight) {
                        if (pluginPage != null) {
                            PluginSettingsPageArea(
                                page = pluginPage,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            SettingsContentArea(
                                section = selectedSection,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                // Footer with auto-save message and reset button
                SettingsFooter(
                    onResetClick = { showResetConfirmation = true },
                )
            }
        }
    }

    // Reset confirmation dialog
    if (showResetConfirmation) {
        BossAlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = {
                Text(
                    text = "Reset Settings?",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Text(
                    text = "This will reset all settings to their default values. This action cannot be undone.",
                    color = TextSecondary,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            // Reset all settings managers to defaults
                            BrowserEngineSettingsManager.resetToDefault()
                            PerformanceSettingsManager.resetToDefault()
                            FocusModeSettingsManager.resetToDefault()
                            RunnerSettingsManager.resetToDefault()
                            ScrollbarSettingsManager.resetToDefault()
                            StartupSettingsManager.resetToDefault()
                            TerminalLinkSettingsManager.resetToDefault()
                        }
                        showResetConfirmation = false
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            backgroundColor = BossThemeController.current.colors.alert,
                        ),
                ) {
                    // On an alert fill, use the on-fill ink token, not TextPrimary.
                    Text("Reset", color = BossThemeController.current.colors.onSignal)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResetConfirmation = false },
                ) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            backgroundColor = SurfaceColor,
            contentColor = TextPrimary,
        )
    }
}

/** What a deep-link string resolves to. See [resolveSettingsDeepLink]. */
internal sealed interface SettingsDeepLink {
    /** A plugin-contributed page that is registered and visible to this user. */
    data class Page(
        val pageId: String,
    ) : SettingsDeepLink

    /** A built-in section. */
    data class Section(
        val section: SettingsSection,
    ) : SettingsDeepLink

    /** Nothing this build can show right now. */
    data object Unresolved : SettingsDeepLink
}

/**
 * Resolve a deep-link string against the built-in sections and [visiblePageIds].
 *
 * Pure, and takes the visible page ids rather than reading `SettingsPageRegistryImpl`, so the two
 * callers below cannot answer differently and both are testable without a `Window` - which is where
 * the previous round's bug lived, in a layer no test could see.
 *
 * **[SettingsDeepLink.Unresolved] is a real answer, not a failure to be defaulted.** Falling back to
 * FLUCK is right for a window being created and wrong for one already open: there it is a
 * navigation nobody asked for, and it clears the plugin page the user was reading. That path is
 * reachable from any plugin - `SettingsProviderImpl.openSettings` forwards an arbitrary string - so
 * a plugin deep-linking to its own page while that page is disabled, RBAC-hidden or not yet
 * registered would send the user to FLUCK. Only the initial value applies the default; see
 * [initialSectionFor].
 */
internal fun resolveSettingsDeepLink(
    requested: String?,
    visiblePageIds: Set<String>,
): SettingsDeepLink {
    val candidate = requested ?: return SettingsDeepLink.Unresolved
    val section = SettingsSection.entries.find { it.name.equals(candidate, ignoreCase = true) }
    return when {
        // Sections first, so a plugin cannot shadow a built-in page by claiming its id.
        section != null -> SettingsDeepLink.Section(section)

        candidate in visiblePageIds -> SettingsDeepLink.Page(candidate)

        else -> SettingsDeepLink.Unresolved
    }
}

/** The section a *new* window starts on: what [requested] names, or FLUCK when it names nothing. */
private fun initialSectionFor(
    requested: String?,
    visiblePageIds: Set<String>,
): SettingsSection =
    (resolveSettingsDeepLink(requested, visiblePageIds) as? SettingsDeepLink.Section)
        ?.section
        ?: SettingsSection.FLUCK

/** The plugin page a *new* window starts on, or null when [requested] does not name one. */
private fun initialPluginPageFor(
    requested: String?,
    visiblePageIds: Set<String>,
): String? = (resolveSettingsDeepLink(requested, visiblePageIds) as? SettingsDeepLink.Page)?.pageId

/** Page ids the current user can actually see, as [resolveSettingsDeepLink] wants them. */
private fun visiblePageIds(): Set<String> = SettingsPageRegistryImpl.visiblePages().map { it.pageId }.toSet()

/**
 * Content area for a plugin-contributed settings page: same header treatment
 * as built-in sections. Page content renders inside a
 * [ai.rever.boss.plugin.sandbox.ui.PluginExtensionBoundary] — a crash
 * attributed to the owning plugin replaces the page with an error notice
 * instead of killing the settings window. Pages with [SettingsPageProvider
 * .selfScrolling] own their scrolling (LazyColumn-friendly, mirroring the
 * embedded built-in sections); others get the host's vertical scroll.
 */
@Composable
private fun PluginSettingsPageArea(
    page: ai.rever.boss.plugin.api.SettingsPageProvider,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    val columnModifier =
        if (page.selfScrolling) {
            modifier.padding(20.dp)
        } else {
            modifier
                .verticalScroll(scrollState)
                .padding(20.dp)
        }
    Column(modifier = columnModifier) {
        Text(
            text = page.displayName,
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            text = page.description,
            color = TextMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 20.dp),
        )

        val pageBoundary: @Composable () -> Unit = {
            ai.rever.boss.plugin.sandbox.ui.PluginExtensionBoundary(
                pluginId =
                    ai.rever.boss.components.plugin.registries
                        .owningPluginId(page),
                surface = "settings page ${page.pageId}",
                fallback = { error ->
                    Text(
                        text =
                            "This settings page crashed: ${error.message ?: error::class.simpleName}. " +
                                "Reload the plugin to try again.",
                        color = BossThemeController.current.colors.alert,
                        fontSize = 13.sp,
                    )
                },
            ) {
                page.Content()
            }
        }
        if (page.selfScrolling) {
            // Bounded height so the page's own LazyColumn/scroll can fill it.
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                pageBoundary()
            }
        } else {
            pageBoundary()
        }
    }
}

/**
 * Content area displaying the selected category's settings with header.
 */
@Composable
private fun SettingsContentArea(
    section: SettingsSection,
    modifier: Modifier = Modifier,
) {
    // Keyed on the section. Unkeyed, one scroll position was shared by every page, so leaving
    // Security scrolled to the bottom and clicking Sidebar landed you at the bottom of a short
    // page. Search makes that worse rather than merely odd: a hit near the top of a section would
    // be scrolled past before its own bring-into-view ran, so the jump looked like it had failed.
    val scrollState = key(section) { rememberScrollState() }

    // Sections that embed external panels or use LazyColumn (can't nest in verticalScroll)
    val embeddedPanelSections =
        setOf(
            SettingsSection.TERMINAL,
            SettingsSection.BOSS_EDITOR,
            SettingsSection.KEYMAP, // Uses LazyColumn for shortcuts list
        )
    val isEmbeddedPanel = section in embeddedPanelSections

    if (isEmbeddedPanel) {
        // Embedded panels handle their own scrolling but get consistent header styling
        Column(
            modifier = modifier.padding(20.dp),
        ) {
            // Category header (same as regular sections)
            Text(
                text = section.displayName,
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = section.description,
                color = TextMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 20.dp),
            )

            // Embedded panel content (handles its own scrolling)
            Box(modifier = Modifier.weight(1f)) {
                when (section) {
                    SettingsSection.TERMINAL -> {
                        TerminalSettings()
                    }

                    SettingsSection.BOSS_EDITOR -> {
                        BossEditorSettings()
                    }

                    SettingsSection.KEYMAP -> {
                        EditableKeymapSettings()
                    }

                    else -> {}
                }
            }
        }
    } else {
        Column(
            modifier =
                modifier
                    .verticalScroll(scrollState)
                    .padding(20.dp),
        ) {
            // Category header
            Text(
                text = section.displayName,
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = section.description,
                color = TextMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 20.dp),
            )

            // Category-specific content
            when (section) {
                SettingsSection.FLUCK -> {
                    FluckBrowserSettings()
                }

                SettingsSection.BROWSER_ENGINE -> {
                    BrowserEngineSettings()
                }

                SettingsSection.DEFAULT_APPS -> {
                    DefaultAppsSettings()
                }

                SettingsSection.RUNNER -> {
                    RunnerSettings()
                }

                SettingsSection.WORKSPACE -> {
                    WorkspaceSettings()
                }

                SettingsSection.UPDATES -> {
                    UpdatesSettings()
                }

                SettingsSection.SECURITY -> {
                    SecuritySettings()
                }

                SettingsSection.LANGUAGE_SERVERS -> {
                    LspSettings()
                }

                SettingsSection.FOCUS_MODE -> {
                    FocusModeSettings()
                }

                SettingsSection.WINDOW_APPEARANCE -> {
                    WindowAppearanceSettings()
                }

                SettingsSection.PERFORMANCE -> {
                    PerformanceSettings()
                }

                SettingsSection.STARTUP -> {
                    StartupSettingsSection()
                }

                SettingsSection.SCROLLBAR -> {
                    ScrollbarSettings()
                }

                SettingsSection.SIDEBAR -> {
                    SidebarSettings()
                }

                SettingsSection.ADVANCED -> {
                    AdvancedSettings()
                }

                SettingsSection.THEME -> {
                    ThemeSettings()
                }

                else -> {}
            }
        }
    }
}

/**
 * Footer with auto-save message and reset button.
 */
@Composable
private fun SettingsFooter(onResetClick: () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SurfaceColor)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Changes are saved automatically",
                color = TextMuted,
                fontSize = 12.sp,
            )
            TextButton(
                onClick = onResetClick,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = TextSecondary,
                    ),
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Reset to Defaults", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun UpdatesSettings() {
    UpdateSettingsSection()
}
