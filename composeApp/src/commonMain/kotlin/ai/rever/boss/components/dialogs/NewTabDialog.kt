package ai.rever.boss.components.dialogs

import ContextMenuBackground
import ContextMenuBorder
import ai.rever.boss.components.overlays.ContextMenu
import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.components.plugin.panels.left_top.ProjectState
import ai.rever.boss.components.plugin.panels.left_top.directoryHasChildren
import ai.rever.boss.components.plugin.panels.left_top.scanDirectory
import ai.rever.boss.components.plugin.panels.left_top.scanDirectoryWithDepth
import ai.rever.boss.components.window_panel.SplitDirection
import ai.rever.boss.icons.FileIcons
import ai.rever.boss.platform.rememberDirectoryPicker
import ai.rever.boss.platform.rememberFilePicker
import ai.rever.boss.plugin.api.FileNodeData
import ai.rever.boss.plugin.api.FileTreeUtils
import ai.rever.boss.plugin.api.NewTabContext
import ai.rever.boss.plugin.api.NewTabSpec
import ai.rever.boss.plugin.api.NodeLoadingStateData
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import ai.rever.boss.plugin.tab.jupyter.JupyterTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.utils.extractFileName
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.Project
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.CoroutineContext

private val newTabDialogLogger = BossLogger.forComponent("NewTabDialog")

/**
 * Validates and sanitizes a file path to prevent path traversal attacks.
 *
 * @param path The file path to validate
 * @param basePath Optional base path that the file must be within (null allows any path)
 * @return The canonical path if valid, null if the path is invalid or attempts traversal
 */
private fun validateFilePath(
    path: String,
    basePath: String? = null,
): String? {
    if (path.isBlank()) return null

    return try {
        val file = File(path)
        val canonicalPath = file.canonicalPath

        // If a base path is provided, ensure the file is within it
        if (basePath != null) {
            val baseFile = File(basePath)
            val canonicalBase = baseFile.canonicalPath

            // The file must be within the base directory
            if (!canonicalPath.startsWith(canonicalBase)) {
                newTabDialogLogger.warn(LogCategory.FILE, "Path traversal attempt blocked", mapOf("path" to path))
                return null
            }
        }

        // Validate the file exists
        if (!file.exists()) {
            newTabDialogLogger.debug(LogCategory.FILE, "File does not exist", mapOf("path" to path))
            return null
        }

        canonicalPath
    } catch (e: Exception) {
        newTabDialogLogger.debug(LogCategory.FILE, "Invalid path", mapOf("path" to path, "error" to e.toString()))
        null
    }
}

/** Breadcrumb for a failed directory-children probe; hoisted out of the deeply nested tree loader. */
private fun logDirProbeFailure(
    path: String,
    e: Exception,
) {
    newTabDialogLogger.debug(
        LogCategory.FILE,
        "Cannot probe directory for children",
        mapOf("path" to path, "error" to e.toString()),
    )
}

enum class TabType(
    val tabTypeId: TabTypeId,
) {
    URL(FluckTabType.typeId),
    FILE(CodeEditorTabType.typeId),
    TERMINAL(TerminalTabType.typeId),
    JUPYTER(JupyterTabInfo.TYPE_ID),
}

/**
 * Encoding for a search query going into a `?q=` parameter.
 *
 * Internal so the suggestion provider builds its search row with the same encoder the
 * confirm path uses - they produced different URLs for the same text, so clicking the row
 * and pressing Enter searched for different things.
 *
 * An ALLOWLIST, deliberately. This was a chain of `replace` calls, and each review round
 * found another character missing from it: `&` and `#` in one, then `%` and `+` in the next
 * (`100%` went out as a truncated escape, `a%26b` reached Google as `a&b`, `a + b` as
 * `a+++b`), with quotes, angle brackets, braces, backslash and every non-ASCII character
 * still passing through raw. Naming what is SAFE ends that sequence: RFC 3986 unreserved
 * characters survive, a space becomes `+` because that is the `?q=` convention, and
 * everything else goes out as the percent-encoded UTF-8 bytes it is made of - which is also
 * what makes a CJK or emoji query correct rather than merely tolerated.
 */
internal fun encodeUrlParameter(input: String): String =
    buildString {
        for (byte in input.encodeToByteArray()) {
            // Masked once, up front. A Byte is signed, so every byte of a multi-byte UTF-8
            // sequence is negative; relying on the sign bit landing outside [UNRESERVED]
            // happens to work and says nothing about why.
            val code = byte.toInt() and 0xFF
            val char = Char(code)
            when {
                char in UNRESERVED -> append(char)
                char == ' ' -> append('+')
                else -> append('%').append(HEX[code shr 4]).append(HEX[code and 0x0F])
            }
        }
    }

/**
 * RFC 3986's unreserved set: the characters a `?q=` parameter carries verbatim.
 *
 * A Set rather than the concatenated ranges it is built from, because `in` on a List is a
 * linear scan and this is consulted once per byte of the query.
 */
private val UNRESERVED = (('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('-', '.', '_', '~')).toSet()

private const val HEX = "0123456789ABCDEF"

/**
 * A spec that declares no input at all (blank label and placeholder, input
 * optional): clicking the tab type opens it instantly, no input step.
 *
 * **Not reachable by omission**, which is what makes a heuristic acceptable here
 * instead of an explicit flag. Checked against the pinned boss-plugin-api 1.0.76
 * jar: `inputLabel` defaults to "Input" and `inputOptional` to false, so a plugin
 * writing `NewTabSpec(order = 5, confirmLabel = "Open")` fails two of the three
 * conditions. Opting in means setting all three deliberately - blank label, blank
 * placeholder, optional - which no author does by accident.
 *
 * That property is load-bearing and lives in a different repo, so it is worth
 * re-checking if the api ever changes those defaults: flipping `inputOptional` to
 * true would silently take the input field away from every spec that omits a label.
 * An explicit `opensImmediately` on NewTabSpec would retire the heuristic, but it
 * ships in the external artifact and so needs an api release.
 */
internal fun NewTabSpec.needsNoInput(): Boolean = inputOptional && inputLabel.isBlank() && inputPlaceholder.isBlank()

/** Whether any modifier is held - a modified key is a different gesture, never an accept. */
private fun KeyEvent.hasModifiers(): Boolean = isShiftPressed || isMetaPressed || isCtrlPressed || isAltPressed

/**
 * How long the URL field waits before asking history for suggestions.
 *
 * Named so the tests that have to outlast it advance the clock by a multiple of it rather
 * than by a literal that silently stops being enough.
 */
internal const val URL_SUGGESTION_DEBOUNCE_MS = 100L

/**
 * Where the suggestion lookup runs.
 *
 * [Dispatchers.Default] in the app, because the lookup canonicalises and word-scans every
 * stored entry and that has no business between a keystroke and its frame. Overridable
 * because a real thread pool is not driven by the test clock: `advanceTimeBy` releases the
 * debounce, but nothing then guarantees the pool has finished and posted its result back
 * before the assertion runs. The lookup is sub-millisecond so it almost always wins, and
 * "almost always", across two dozen tests on a contended CI runner, is how a flake is born.
 * Tests set this to [EmptyCoroutineContext] so `withContext` stays on the composition's own
 * dispatcher and the whole effect is deterministic under the test clock.
 */
internal var urlSuggestionContext: CoroutineContext = Dispatchers.Default

// Platform-specific URL history provider
expect object UrlHistoryProvider {
    fun getSuggestions(
        query: String,
        limit: Int = 10,
    ): List<UrlSuggestion>

    fun deleteUrl(url: String)
}

data class UrlSuggestion(
    val url: String,
    val title: String,
    val isSearchSuggestion: Boolean = false,
)

@Composable
fun NewTabDialog(
    onDismiss: () -> Unit,
    onCreateTab: (type: TabType, path: String) -> Unit,
    tabRegistry: TabRegistry,
    initialTabType: TabType? = null,
    /**
     * Opens a [TabInfo] built by a plugin tab type's [TabTypeInfo.createTabInfo].
     * When null, plugin-registered tab types are not offered (legacy callers).
     */
    onCreateTabInfo: ((TabInfo) -> Unit)? = null,
    projectPath: String? = null,
    windowId: String? = null,
    /**
     * Where the new tab goes: null for the pane it would land in anyway, or the side a new pane
     * should be created on.
     *
     * Hoisted rather than owned here because the split map opens this dialog with a direction
     * already chosen - clicking a trapezoid IS asking for a tab in that direction - and a picker
     * that reset itself to "this pane" would throw that away in front of the user.
     */
    splitDirection: SplitDirection? = null,
    /** Null when this caller cannot split (no split view to split), which hides the picker. */
    onSplitDirectionChange: ((SplitDirection?) -> Unit)? = null,
) {
    val availableTypes = TabType.entries.filter { tabRegistry.isRegistered(it.tabTypeId) }
    // Plugin-registered tab types that opted into the dialog (newTabSpec).
    // TabRegistry is state-backed, so this recomposes on (un)registration.
    val builtinTypeIds = TabType.entries.map { it.tabTypeId }.toSet()
    val pluginTypes =
        if (onCreateTabInfo != null) {
            tabRegistry
                .getAllTabTypes()
                .filter { it.newTabSpec != null && it.typeId !in builtinTypeIds }
                .sortedWith(compareBy({ it.newTabSpec!!.order }, { it.displayName }))
        } else {
            emptyList()
        }
    val defaultType =
        if (initialTabType != null && initialTabType in availableTypes) {
            initialTabType
        } else {
            availableTypes.firstOrNull() ?: TabType.URL
        }
    var selectedType by remember { mutableStateOf(defaultType) }
    // Non-null when a plugin tab type is selected; built-in selection then
    // idles. Defaults to the first plugin type when no built-ins are
    // available. Keyed on availableTypes/pluginTypes so the default is
    // (re)applied if the registry populates after the dialog first composes
    // (built-ins are async-loaded plugins — an unkeyed remember would leave
    // nothing selected). Once the user picks a type the key is stable, so
    // their choice sticks.
    var selectedPluginType by remember(availableTypes.isEmpty(), pluginTypes.firstOrNull()?.typeId) {
        mutableStateOf(if (availableTypes.isEmpty()) pluginTypes.firstOrNull()?.typeId else null)
    }
    val selectedPluginTypeInfo = selectedPluginType?.let { id -> pluginTypes.firstOrNull { it.typeId == id } }
    var pluginInput by remember(selectedPluginType) { mutableStateOf("") }

    // Guards the create action against a second click landing before the dialog
    // leaves composition. onCreateTabInfo + onDismiss are state-driven, so two
    // clicks in that gap open two tabs. The confirm button always had this, but
    // a TILE is a far more natural thing to double-click than a confirm button,
    // so the instant-open path is where it actually becomes reachable.
    //
    // Latched, never reset: it is only ever set on a path that also calls
    // onDismiss, so this composable is on its way out. That makes it depend on
    // the caller honouring onDismiss - one that keeps the dialog composed would
    // leave it permanently unable to create a plugin tab.
    var opening by remember { mutableStateOf(false) }

    // Open a plugin tab type with the given input: the plugin builds the
    // TabInfo (null = input rejected, dialog stays open). Crash-isolated —
    // plugin code. Returns whether a tab was actually opened, which the
    // instant-open path needs: with no input on screen there is nothing for the
    // user to correct, so a refusal has to surface as something.
    val openPluginTab: (TabTypeInfo, String) -> Boolean = { typeInfo, input ->
        if (opening) {
            false
        } else {
            val tabInfo =
                try {
                    typeInfo.createTabInfo(input.trim(), NewTabContext(projectPath = projectPath, windowId = windowId))
                } catch (e: Exception) {
                    newTabDialogLogger.warn(
                        LogCategory.UI,
                        "Plugin createTabInfo failed",
                        mapOf(
                            "typeId" to typeInfo.typeId.typeId,
                        ),
                        e,
                    )
                    null
                }
            if (tabInfo != null) {
                opening = true
                onCreateTabInfo?.invoke(tabInfo)
                onDismiss()
            }
            tabInfo != null
        }
    }

    // Confirm the selected plugin tab type with the typed input.
    val confirmPluginTab: () -> Unit = confirm@{
        val typeInfo = selectedPluginTypeInfo ?: return@confirm
        val spec = typeInfo.newTabSpec ?: return@confirm
        if (!spec.inputOptional && pluginInput.isBlank()) return@confirm
        openPluginTab(typeInfo, pluginInput)
    }
    var urlText by remember { mutableStateOf("") }
    var fileText by remember { mutableStateOf("") }
    var terminalCommand by remember { mutableStateOf("") }
    var jupyterName by remember { mutableStateOf("") }
    var inputText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val terminalFocusRequester = remember { FocusRequester() }

    // URL autocomplete state
    var urlSuggestions by remember { mutableStateOf<List<UrlSuggestion>>(emptyList()) }
    var showUrlDropdown by remember { mutableStateOf(false) }
    var selectedSuggestionIndex by remember { mutableStateOf(-1) }
    val listState = rememberLazyListState()

    // The URL field holds a TextFieldValue rather than a plain String for the CURSOR: Right
    // accepts the completion only at the very end of the input, and anywhere else it has to
    // stay an ordinary cursor move. The value itself is only ever what the user typed.
    var urlField by remember { mutableStateOf(TextFieldValue("")) }
    // The completion the ghost text is offering, or null. Held apart from the field so it
    // can never feed itself a longer query and walk down the URL one character at a time.
    var urlCompletion by remember { mutableStateOf<UrlCompletion?>(null) }
    // A deletion must NOT re-complete: backspacing towards a shorter address should not be
    // fought by a completion that fills it straight back in. Chrome suppresses inline
    // completion after a delete for the same reason. Only an edit that ADDS characters
    // re-arms it.
    var completionAllowed by remember { mutableStateOf(true) }
    // The text a dismissal applies to, or null. Escape and an accepted completion both put
    // the list away, but neither of them can do that by writing `showUrlDropdown` alone:
    // the suggestion lookup is debounced, so a lookup already in flight - or the one an
    // accept starts by rewriting the field - lands afterwards and re-opens the list from
    // under them. Held as the TEXT rather than a flag so it expires the moment the user
    // types something else, which is exactly when the list should come back.
    var suggestionsDismissedFor by remember { mutableStateOf<String?>(null) }
    // Two suppressions, both DERIVED rather than written into state:
    //  - over a selection the ghost reads as field content that escaped the highlight, and
    //    Enter would commit the very text the user selected in order to replace it.
    //  - while a dropdown row is highlighted, that row is the proposal; two on screen at
    //    once is one too many.
    // Deriving them is what keeps the ghost steady. As state writes they fought the write
    // in `onValueChange`: every keystroke typed with a row highlighted set the completion
    // and an effect immediately cleared it, so the tail flickered off for a whole debounce
    // and the accept keys did nothing in that window.
    // `derivedStateOf`, not a plain `val`: the key handler and the Done action are lambdas
    // that outlive the composition that built them, and they read `selectedSuggestionIndex`
    // and `urlSuggestions` LIVE through their delegates. A captured value would disagree
    // with those live guards inside a single frame - Down then Enter before a recomposition
    // passed the `index >= 0` guard while committing a target computed before the Down.
    val ghostCompletion by remember {
        derivedStateOf {
            urlCompletion?.takeIf {
                // At the END of the input, not merely collapsed. The ghost is drawn after
                // the text, so with the caret anywhere else it describes an insertion point
                // it does not belong to - and Tab accepted it there while Right, which
                // computed its own `atEnd`, correctly did not. One rule now, so the two
                // gestures agree and the ghost goes away on a caret move, as Chrome's does.
                urlField.selection.collapsed &&
                    urlField.selection.start == urlField.text.length &&
                    selectedSuggestionIndex < 0
            }
        }
    }
    // Read once and passed as a key: `CoreTextField` memoises on the VisualTransformation
    // instance, so a new one per recomposition re-runs the filter and re-lays out the text
    // on every hover and every arrow key.
    val ghostColor = BossTheme.colors.textSecondary
    // Where a commit goes. ONE derived value read by Enter, by the confirm button and by
    // the dialog's Done action, in the order the user's own signals rank:
    //  1. a row they arrowed onto - the most explicit choice on screen.
    //  2. the ghost completion, guarded exactly as it is drawn, so the address the field
    //     shows and the address that opens cannot come apart.
    //  3. what they typed.
    // One value rather than one per commit path, so Enter and the confirm button cannot
    // disagree about which of the three signals wins.
    val urlToOpen by remember {
        derivedStateOf {
            urlSuggestions.getOrNull(selectedSuggestionIndex)?.url
                ?: urlCompletionTarget(ghostCompletion, urlField.text)?.target
                ?: inputText
        }
    }

    // File picker for browsing files
    val filePicker =
        rememberFilePicker(
            onFileSelected = { path, _, _ ->
                path?.let {
                    fileText = it
                    inputText = it
                }
            },
            fileExtensions = emptyList(), // Allow all files
        )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Update suggestions when URL text changes
    LaunchedEffect(urlText, selectedType) {
        if (selectedType == TabType.URL && urlText.isNotEmpty()) {
            // Captured before the delay. Read inside `withContext` it would resolve against
            // the worker thread's snapshot rather than the value that keyed this effect;
            // cancellation makes that benign today, and capturing makes the dismissal check
            // below provably about the string the lookup actually ran for.
            val query = urlText
            delay(URL_SUGGESTION_DEBOUNCE_MS)
            // Off the composition thread: the lookup canonicalises and word-scans every
            // stored entry, which is milliseconds at the 1000-entry cap but is still work
            // that has no business between a keystroke and its frame.
            urlSuggestions = withContext(urlSuggestionContext) { UrlHistoryProvider.getSuggestions(query) }
            // Read after the delay, so a dismissal made DURING the debounce is honoured.
            showUrlDropdown = urlSuggestions.isNotEmpty() && query != suggestionsDismissedFor
            selectedSuggestionIndex = -1
        } else {
            urlSuggestions = emptyList()
            showUrlDropdown = false
            // Or a stale index keeps gating the completion off after the field is cleared.
            selectedSuggestionIndex = -1
        }
    }

    // Re-offer the completion when a NEW suggestion list lands.
    //
    // The keystroke path in `onValueChange` is the primary writer - it has to be, or the
    // ghost trails the debounce - and this only catches up the case where the list arrived
    // after the character that asked for it. Keyed on the list alone: adding the other
    // pieces of state made this a second, differently-gated writer racing the first.
    LaunchedEffect(urlSuggestions, selectedType) {
        urlCompletion =
            if (selectedType == TabType.URL && completionAllowed) {
                inlineUrlCompletion(urlField.text, urlSuggestions)
            } else {
                null
            }
    }

    // Auto-scroll to selected suggestion when using arrow keys
    LaunchedEffect(selectedSuggestionIndex) {
        if (selectedSuggestionIndex >= 0 && urlSuggestions.isNotEmpty()) {
            listState.animateScrollToItem(selectedSuggestionIndex)
        }
    }

    // BossDialog renders this in a separate always-on-top window when the browser is a heavyweight
    // GPU surface (HARDWARE_ACCELERATED), because a plain Popup would be drawn UNDER the page; it
    // falls back to an ordinary Compose Dialog on OFF_SCREEN. Routed here rather than at the three
    // call sites so every one of them gets it.
    //
    // The scrim and centering that used to be hand-rolled here now belong to BossDialog, so every
    // dialog in the app gets the same ones rather than this one being a special case.
    BossDialog(onDismissRequest = onDismiss) {
        // Dialog content with ContextMenu styling
        Box(
            modifier =
                Modifier
                    .width(500.dp)
                    .background(
                        color = ContextMenuBackground,
                        shape = RoundedCornerShape(8.dp),
                    ).border(
                        width = 1.dp,
                        color = ContextMenuBorder,
                        shape = RoundedCornerShape(8.dp),
                    ).onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onDismiss()
                            true
                        } else {
                            false
                        }
                    },
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
            ) {
                // Title
                Text(
                    text = "New Tab",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = BossTheme.colors.textPrimary,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                if (availableTypes.isEmpty() && pluginTypes.isEmpty()) {
                    // Empty state when no tab plugins are enabled
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No tab types available. Enable a tab plugin or install one from the Plugin Store.",
                            color = BossTheme.colors.textSecondary,
                            fontSize = 13.sp,
                        )
                    }
                } else {
                    // Type selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (TabType.URL in availableTypes) {
                            TabTypeOption(
                                icon = Icons.Default.Language,
                                label = "URL",
                                isSelected = selectedPluginType == null && selectedType == TabType.URL,
                                onClick = {
                                    // Save current text before switching
                                    when (selectedType) {
                                        TabType.FILE -> {
                                            fileText = inputText
                                        }

                                        TabType.JUPYTER -> {
                                            jupyterName = inputText
                                        }

                                        else -> {}
                                    }
                                    selectedPluginType = null
                                    selectedType = TabType.URL
                                    inputText = urlText
                                    // `urlField` is deliberately NOT rewritten from `urlText`
                                    // here, and the reason is the display/target split rather
                                    // than an oversight.
                                    //
                                    // After an accepted completion the field holds the DISPLAY
                                    // (`192.168.4.20:8123`) while `urlText`/`inputText` hold
                                    // the TARGET (`http://192.168.4.20:8123`) - two strings on
                                    // purpose, because re-deriving the target from the display
                                    // sends it through `processUrlInput` as `https://`. The
                                    // field is remembered across a type switch, so leaving it
                                    // alone is what keeps BOTH: the user sees what they
                                    // accepted, and the commit still opens what history stored.
                                    // Syncing it here rendered the target in the field, and
                                    // syncing `urlText` from the field instead would lose the
                                    // target on the way back.
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        if (TabType.FILE in availableTypes) {
                            TabTypeOption(
                                icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                                label = "File",
                                isSelected = selectedPluginType == null && selectedType == TabType.FILE,
                                onClick = {
                                    // Save current text before switching
                                    when (selectedType) {
                                        TabType.URL -> {
                                            urlText = inputText
                                        }

                                        TabType.JUPYTER -> {
                                            jupyterName = inputText
                                        }

                                        else -> {}
                                    }
                                    selectedPluginType = null
                                    selectedType = TabType.FILE
                                    inputText = fileText
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        if (TabType.TERMINAL in availableTypes) {
                            TabTypeOption(
                                icon = Icons.Outlined.Terminal,
                                label = "Terminal",
                                isSelected = selectedPluginType == null && selectedType == TabType.TERMINAL,
                                onClick = {
                                    // Save current text before switching
                                    when (selectedType) {
                                        TabType.URL -> {
                                            urlText = inputText
                                        }

                                        TabType.FILE -> {
                                            fileText = inputText
                                        }

                                        TabType.JUPYTER -> {
                                            jupyterName = inputText
                                        }

                                        else -> {}
                                    }
                                    selectedPluginType = null
                                    selectedType = TabType.TERMINAL
                                    inputText = terminalCommand
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        // Plugin-registered tab types that opted into the dialog
                        // via TabTypeInfo.newTabSpec — fully dynamic, no host
                        // change needed for a new tab type to appear here.
                        for (pluginType in pluginTypes) {
                            TabTypeOption(
                                icon = pluginType.icon,
                                label = pluginType.displayName,
                                isSelected = selectedPluginType == pluginType.typeId,
                                onClick = {
                                    // Types that declare no input (e.g. Arcade)
                                    // open on the click itself — no input step.
                                    //
                                    // If the plugin refuses, fall back to selecting
                                    // the type. Without that the tile is inert: it
                                    // does not even become selected, so a broken
                                    // no-input type is a button that visibly does
                                    // nothing, forever, with only a log line to say
                                    // why. Selecting shows the confirm button, so the
                                    // refusal is at least visible and retryable.
                                    //
                                    // `!opening` distinguishes the two falses
                                    // openPluginTab returns. A refusal means select;
                                    // "a create is already in flight" - the second half
                                    // of a double-click - must NOT, or the dialog
                                    // rearranges itself on its way out.
                                    if (pluginType.newTabSpec?.needsNoInput() == true) {
                                        if (!openPluginTab(pluginType, "") && !opening) {
                                            selectedPluginType = pluginType.typeId
                                        }
                                        return@TabTypeOption
                                    }
                                    when (selectedType) {
                                        TabType.URL -> {
                                            urlText = inputText
                                        }

                                        TabType.FILE -> {
                                            fileText = inputText
                                        }

                                        TabType.JUPYTER -> {
                                            jupyterName = inputText
                                        }

                                        else -> {}
                                    }
                                    selectedPluginType = pluginType.typeId
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        if (TabType.JUPYTER in availableTypes) {
                            TabTypeOption(
                                // Matches JupyterTabInfo's default tab icon for a consistent identity.
                                icon = Icons.Outlined.Code,
                                label = "Jupyter",
                                isSelected = selectedPluginType == null && selectedType == TabType.JUPYTER,
                                onClick = {
                                    when (selectedType) {
                                        TabType.URL -> {
                                            urlText = inputText
                                        }

                                        TabType.FILE -> {
                                            fileText = inputText
                                        }

                                        TabType.TERMINAL -> {
                                            terminalCommand = inputText
                                        }

                                        else -> {}
                                    }
                                    selectedType = TabType.JUPYTER
                                    inputText = jupyterName
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Input field
                    Column {
                        // Plugin tab type input — one generic field driven by the
                        // type's NewTabSpec; the plugin validates via createTabInfo.
                        //
                        // Gated on the same predicate as the tile click, so the two
                        // paths agree. A no-input type can arrive here without ever
                        // being clicked: when no built-in types are available the
                        // dialog auto-selects the first plugin type, which would
                        // otherwise open showing the empty "(optional)" field, the
                        // focus grab and the confirm button that this change exists
                        // to remove.
                        //
                        // The no-input case is a branch INSIDE this one, never a
                        // narrower condition on it. This `if` heads a chain that ends in
                        // an unconditional `else` drawing the URL field, so excluding a
                        // type here does not render nothing - it falls through to
                        // whichever built-in matches `selectedType`, which starts as URL.
                        // That put a focused "Enter URL or search term" next to a "Play"
                        // button that discards whatever is typed, and on the refusal path
                        // a whole file tree beside a button that silently fails again.
                        if (selectedPluginTypeInfo != null) {
                            val spec = selectedPluginTypeInfo.newTabSpec!!
                            // Nothing to ask for. The confirm button below is already
                            // plugin-scoped on this same condition, so it keeps carrying
                            // spec.confirmLabel and confirmPluginTab.
                            if (!spec.needsNoInput()) {
                                val pluginFocusRequester = remember { FocusRequester() }
                                LaunchedEffect(selectedPluginType) {
                                    pluginFocusRequester.requestFocus()
                                }
                                OutlinedTextField(
                                    value = pluginInput,
                                    onValueChange = { pluginInput = it },
                                    label = {
                                        Text(
                                            spec.inputLabel + if (spec.inputOptional) " (optional)" else "",
                                            color = BossTheme.colors.textSecondary,
                                        )
                                    },
                                    placeholder = {
                                        Text(
                                            spec.inputPlaceholder,
                                            color = BossTheme.colors.textMuted,
                                        )
                                    },
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .focusRequester(pluginFocusRequester)
                                            .onPreviewKeyEvent { event ->
                                                if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                                    confirmPluginTab()
                                                    true
                                                } else {
                                                    false
                                                }
                                            },
                                    colors =
                                        TextFieldDefaults.outlinedTextFieldColors(
                                            textColor = BossTheme.colors.textPrimary,
                                            cursorColor = BossTheme.colors.textPrimary,
                                            focusedBorderColor = BossTheme.colors.signal,
                                            unfocusedBorderColor = BossTheme.colors.line,
                                            backgroundColor = BossTheme.colors.panel,
                                        ),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { confirmPluginTab() }),
                                )
                            }
                        } else if (selectedType == TabType.TERMINAL) {
                            // Terminal command input
                            LaunchedEffect(selectedType) {
                                if (selectedType == TabType.TERMINAL) {
                                    terminalFocusRequester.requestFocus()
                                }
                            }
                            OutlinedTextField(
                                value = terminalCommand,
                                onValueChange = { newValue ->
                                    terminalCommand = newValue
                                    inputText = newValue
                                },
                                label = {
                                    Text(
                                        "Initial command (optional)",
                                        color = BossTheme.colors.textSecondary,
                                    )
                                },
                                placeholder = {
                                    Text(
                                        "e.g., npm run dev",
                                        color = BossTheme.colors.textMuted,
                                    )
                                },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .focusRequester(terminalFocusRequester)
                                        .onPreviewKeyEvent { event ->
                                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                                handleCreateTab(selectedType, terminalCommand, onCreateTab, onDismiss)
                                                true
                                            } else {
                                                false
                                            }
                                        },
                                colors =
                                    TextFieldDefaults.outlinedTextFieldColors(
                                        textColor = BossTheme.colors.textPrimary,
                                        cursorColor = BossTheme.colors.textPrimary,
                                        focusedBorderColor = BossTheme.colors.signal,
                                        unfocusedBorderColor = BossTheme.colors.line,
                                        backgroundColor = BossTheme.colors.panel,
                                    ),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions =
                                    KeyboardActions(
                                        onDone = {
                                            handleCreateTab(selectedType, terminalCommand, onCreateTab, onDismiss)
                                        },
                                    ),
                            )
                        } else if (selectedType == TabType.JUPYTER) {
                            // Optional notebook name (blank = a new untitled notebook)
                            val jupyterFocusRequester = remember { FocusRequester() }
                            LaunchedEffect(selectedType) {
                                if (selectedType == TabType.JUPYTER) jupyterFocusRequester.requestFocus()
                            }
                            OutlinedTextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                label = { Text("Notebook name (optional)", color = BossTheme.colors.textSecondary) },
                                placeholder = { Text("e.g., analysis", color = BossTheme.colors.textMuted) },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .focusRequester(jupyterFocusRequester)
                                        .onPreviewKeyEvent { event ->
                                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                                handleCreateTab(selectedType, inputText, onCreateTab, onDismiss)
                                                true
                                            } else {
                                                false
                                            }
                                        },
                                colors =
                                    TextFieldDefaults.outlinedTextFieldColors(
                                        textColor = BossTheme.colors.textPrimary,
                                        cursorColor = BossTheme.colors.textPrimary,
                                        focusedBorderColor = BossTheme.colors.signal,
                                        unfocusedBorderColor = BossTheme.colors.line,
                                        backgroundColor = BossTheme.colors.panel,
                                    ),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions =
                                    KeyboardActions(
                                        onDone = { handleCreateTab(selectedType, inputText, onCreateTab, onDismiss) },
                                    ),
                            )
                        } else if (selectedType == TabType.FILE) {
                            // Current project/folder selector (uses global recent projects)
                            // Note: In NewTabDialog we don't have window context, so we use the most recent project
                            val recentProjects by ProjectState.recentProjects.collectAsState()
                            var selectedProject by remember {
                                mutableStateOf(
                                    recentProjects.firstOrNull()
                                        ?: Project("No Project", "", 0L),
                                )
                            }
                            // Update selectedProject when recentProjects changes
                            LaunchedEffect(recentProjects) {
                                if (selectedProject.path.isEmpty() && recentProjects.isNotEmpty()) {
                                    selectedProject = recentProjects.first()
                                }
                            }
                            var showFolderDropdown by remember { mutableStateOf(false) }
                            var buttonHeight by remember { mutableStateOf(0) }

                            // File tree state
                            var fileTree by remember { mutableStateOf<FileNodeData?>(null) }
                            var expandedPaths by remember { mutableStateOf(setOf<String>()) }
                            var isLoadingTree by remember { mutableStateOf(false) }
                            val coroutineScope = rememberCoroutineScope()

                            // Load file tree when project changes
                            LaunchedEffect(selectedProject.path) {
                                if (selectedProject.path.isNotEmpty()) {
                                    isLoadingTree = true
                                    fileTree =
                                        try {
                                            withContext(Dispatchers.IO) {
                                                scanDirectory(selectedProject.path)
                                            }
                                        } catch (e: Exception) {
                                            newTabDialogLogger.warn(LogCategory.FILE, "Error scanning directory", error = e)
                                            null
                                        }
                                    isLoadingTree = false
                                } else {
                                    fileTree = null
                                }
                            }

                            // Directory picker for selecting new folder
                            val directoryPicker =
                                rememberDirectoryPicker { path ->
                                    path?.let {
                                        val projectName = it.extractFileName().ifEmpty { "Unknown" }
                                        val newProject =
                                            Project(
                                                name = projectName,
                                                path = it,
                                            )
                                        // Update local state and recent projects list
                                        selectedProject = newProject
                                        ProjectState.updateRecentProjects(newProject)
                                        // Clear expanded paths for new folder
                                        expandedPaths = emptySet()
                                    }
                                }

                            // Show "Open Project" button when no project is selected
                            if (selectedProject.path.isEmpty()) {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .height(200.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Button(
                                        onClick = { directoryPicker.pickDirectory() },
                                        colors =
                                            ButtonDefaults.buttonColors(
                                                backgroundColor = BossTheme.colors.signal,
                                                contentColor = BossTheme.colors.onSignal,
                                            ),
                                        shape = RoundedCornerShape(4.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.FolderOpen,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Open Project")
                                    }
                                }
                            } else {
                                // Folder selector dropdown
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(
                                        onClick = { showFolderDropdown = true },
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .onGloballyPositioned { coordinates ->
                                                    buttonHeight = coordinates.size.height
                                                },
                                        colors =
                                            ButtonDefaults.outlinedButtonColors(
                                                backgroundColor = BossTheme.colors.panel,
                                                contentColor = BossTheme.colors.textPrimary,
                                            ),
                                        border =
                                            ButtonDefaults.outlinedBorder.copy(
                                                brush =
                                                    androidx.compose.ui.graphics
                                                        .SolidColor(BossTheme.colors.line),
                                            ),
                                        shape = RoundedCornerShape(4.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Folder,
                                            contentDescription = "Folder",
                                            tint = BossTheme.colors.signalText,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = selectedProject.name,
                                            color = BossTheme.colors.textPrimary,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = "Expand",
                                            tint = BossTheme.colors.textSecondary,
                                        )
                                    }

                                    if (showFolderDropdown) {
                                        ContextMenu(
                                            items =
                                                buildList {
                                                    // Recent projects
                                                    recentProjects.forEach { project ->
                                                        add(
                                                            ContextMenuItem(
                                                                text = project.name,
                                                                icon = Icons.Outlined.Folder,
                                                                onClick = {
                                                                    selectedProject = project
                                                                    expandedPaths = emptySet()
                                                                },
                                                            ),
                                                        )
                                                    }
                                                    // Divider if there are recent projects
                                                    if (recentProjects.isNotEmpty()) {
                                                        add(ContextMenuItem(isDivider = true))
                                                    }
                                                    // Browse option
                                                    add(
                                                        ContextMenuItem(
                                                            text = "Browse...",
                                                            icon = Icons.Default.FolderOpen,
                                                            onClick = { directoryPicker.pickDirectory() },
                                                        ),
                                                    )
                                                },
                                            offset = IntOffset(0, buttonHeight),
                                            modifier = Modifier.widthIn(min = 200.dp),
                                            onDismissRequest = { showFolderDropdown = false },
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // File tree browser
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .height(200.dp)
                                            .background(
                                                color = BossTheme.colors.panel,
                                                shape = RoundedCornerShape(4.dp),
                                            ).border(
                                                width = 1.dp,
                                                color = ContextMenuBorder,
                                                shape = RoundedCornerShape(4.dp),
                                            ),
                                ) {
                                    if (isLoadingTree) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(24.dp),
                                                color = BossTheme.colors.signal,
                                                strokeWidth = 2.dp,
                                            )
                                        }
                                    } else if (fileTree != null && fileTree?.children?.isNotEmpty() == true) {
                                        Column(
                                            modifier =
                                                Modifier
                                                    .fillMaxSize()
                                                    .verticalScroll(rememberScrollState())
                                                    .padding(4.dp),
                                        ) {
                                            fileTree?.children?.forEach { node ->
                                                DialogFileTreeItem(
                                                    node = node,
                                                    level = 0,
                                                    expandedPaths = expandedPaths,
                                                    onToggleExpanded = { path ->
                                                        if (expandedPaths.contains(path)) {
                                                            // Collapse - just remove from expanded set
                                                            expandedPaths = expandedPaths - path
                                                        } else {
                                                            // Expand - add to expanded set and load children
                                                            expandedPaths = expandedPaths + path

                                                            // Load children if needed
                                                            val currentTree = fileTree
                                                            if (currentTree != null) {
                                                                val targetNode = FileTreeUtils.findNodeByPath(currentTree, path)
                                                                if (targetNode?.isDirectory == true && targetNode.children.isEmpty()) {
                                                                    // Need to load children
                                                                    coroutineScope.launch {
                                                                        try {
                                                                            val scannedNode =
                                                                                withContext(Dispatchers.IO) {
                                                                                    scanDirectoryWithDepth(
                                                                                        path,
                                                                                        maxDepth = 1,
                                                                                        startDepth = 0,
                                                                                    )
                                                                                }
                                                                            if (scannedNode != null) {
                                                                                val loadedChildren =
                                                                                    scannedNode.children.map { child ->
                                                                                        if (child.isDirectory) {
                                                                                            val hasKids =
                                                                                                try {
                                                                                                    directoryHasChildren(child.path)
                                                                                                } catch (e: Exception) {
                                                                                                    logDirProbeFailure(child.path, e)
                                                                                                    false
                                                                                                }
                                                                                            child.copy(hasChildren = hasKids)
                                                                                        } else {
                                                                                            child
                                                                                        }
                                                                                    }
                                                                                fileTree =
                                                                                    FileTreeUtils.updateNodeAtPath(
                                                                                        currentTree,
                                                                                        path,
                                                                                    ) { existingNode ->
                                                                                        existingNode.copy(
                                                                                            children = loadedChildren,
                                                                                            hasChildren = loadedChildren.isNotEmpty(),
                                                                                        )
                                                                                    }
                                                                            }
                                                                        } catch (e: Exception) {
                                                                            newTabDialogLogger.warn(
                                                                                LogCategory.FILE,
                                                                                "Error loading folder children",
                                                                                error = e,
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    },
                                                    onFileClick = { file ->
                                                        inputText = file.path
                                                        fileText = file.path
                                                    },
                                                )
                                            }
                                        }
                                    } else if (fileTree != null && fileTree?.children?.isEmpty() == true) {
                                        // Empty folder (hidden files like .git are excluded)
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                            ) {
                                                Text(
                                                    text = "No visible files",
                                                    color = BossTheme.colors.textSecondary,
                                                    fontSize = 13.sp,
                                                )
                                                Text(
                                                    text = "(hidden files and build folders are excluded)",
                                                    color = BossTheme.colors.textMuted,
                                                    fontSize = 11.sp,
                                                )
                                            }
                                        }
                                    } else {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = "Unable to load files",
                                                color = BossTheme.colors.textSecondary,
                                                fontSize = 13.sp,
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // File input with browse button
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedTextField(
                                        value = inputText,
                                        onValueChange = { newValue ->
                                            inputText = newValue
                                            fileText = newValue
                                        },
                                        label = {
                                            Text(
                                                "File path",
                                                color = BossTheme.colors.textSecondary,
                                            )
                                        },
                                        placeholder = {
                                            Text(
                                                "Select a file above or enter path",
                                                color = BossTheme.colors.textMuted,
                                            )
                                        },
                                        modifier =
                                            Modifier
                                                .weight(1f)
                                                .focusRequester(focusRequester)
                                                .onPreviewKeyEvent { event ->
                                                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                                        handleCreateTab(selectedType, inputText, onCreateTab, onDismiss)
                                                        true
                                                    } else {
                                                        false
                                                    }
                                                },
                                        colors =
                                            TextFieldDefaults.outlinedTextFieldColors(
                                                textColor = BossTheme.colors.textPrimary,
                                                cursorColor = BossTheme.colors.textPrimary,
                                                focusedBorderColor = BossTheme.colors.signal,
                                                unfocusedBorderColor = BossTheme.colors.line,
                                                backgroundColor = BossTheme.colors.panel,
                                            ),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                        keyboardActions =
                                            KeyboardActions(
                                                onDone = {
                                                    handleCreateTab(selectedType, inputText, onCreateTab, onDismiss)
                                                },
                                            ),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    IconButton(
                                        onClick = { filePicker.pickFile() },
                                        modifier = Modifier.size(48.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.FolderOpen,
                                            contentDescription = "Browse files",
                                            tint = BossTheme.colors.textSecondary,
                                        )
                                    }
                                }
                            }
                        } else {
                            // URL input
                            OutlinedTextField(
                                value = urlField,
                                onValueChange = { newValue ->
                                    // Compose fires this for SELECTION-only changes too - a
                                    // click, a drag, an arrow key, even Cmd+C collapsing a
                                    // selection. Treating those as edits disarmed the
                                    // completion on a bare cursor move, which killed the
                                    // accept gesture the ghost had just invited.
                                    val textChanged = newValue.text != urlField.text
                                    // Only an edit that ADDS characters may complete.
                                    if (textChanged) {
                                        completionAllowed = newValue.text.length > urlField.text.length
                                    }
                                    urlField = newValue
                                    if (!textChanged) return@OutlinedTextField
                                    // Typing is what un-dismisses the list: a dismissal is
                                    // about the text it was made against, and this is no
                                    // longer that text.
                                    suggestionsDismissedFor = null
                                    // And it drops the highlighted row, which belongs to a
                                    // list built for text that no longer exists. The effect
                                    // below resets this too, but only after the debounce,
                                    // and `urlToOpen` reads the row FIRST - so Enter inside
                                    // that window committed a row from the previous list.
                                    selectedSuggestionIndex = -1
                                    inputText = newValue.text
                                    urlText = newValue.text
                                    // Recomputed HERE, against the suggestions already in
                                    // hand, rather than left to the effect below. The
                                    // lookup is debounced, so waiting for it made the ghost
                                    // trail the keystroke by 100ms and blink out whenever a
                                    // character diverged from the candidate - and left a
                                    // window where a commit took a completion the field was
                                    // no longer showing.
                                    urlCompletion =
                                        if (completionAllowed) {
                                            inlineUrlCompletion(newValue.text, urlSuggestions)
                                        } else {
                                            null
                                        }
                                },
                                label = {
                                    Text(
                                        "Enter URL or search term",
                                        color = BossTheme.colors.textSecondary,
                                    )
                                },
                                placeholder = {
                                    Text(
                                        "https://example.com or search...",
                                        color = BossTheme.colors.textMuted,
                                    )
                                },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .focusRequester(focusRequester)
                                        .onPreviewKeyEvent { event ->
                                            if (event.type == KeyEventType.KeyDown) {
                                                when (event.key) {
                                                    Key.DirectionDown -> {
                                                        // Always consume arrow keys to prevent cursor movement in text field
                                                        if (showUrlDropdown && urlSuggestions.isNotEmpty()) {
                                                            selectedSuggestionIndex =
                                                                (selectedSuggestionIndex + 1).coerceAtMost(urlSuggestions.size - 1)
                                                        }
                                                        true
                                                    }

                                                    Key.DirectionUp -> {
                                                        // Always consume arrow keys to prevent cursor movement in text field
                                                        if (showUrlDropdown && urlSuggestions.isNotEmpty()) {
                                                            selectedSuggestionIndex = (selectedSuggestionIndex - 1).coerceAtLeast(-1)
                                                        }
                                                        true
                                                    }

                                                    // Tab, and Right at the very end of the
                                                    // input, accept the ghost text - the same two
                                                    // gestures the browser's address bar accepts
                                                    // it with. Anywhere else Right is an ordinary
                                                    // cursor move and must stay one.
                                                    Key.Tab, Key.DirectionRight -> {
                                                        val completion =
                                                            urlCompletionTarget(
                                                                ghostCompletion,
                                                                urlField.text,
                                                            )
                                                        // A modified key is a different
                                                        // gesture: Shift+Right extends a
                                                        // selection, Shift+Tab moves focus
                                                        // backwards, Cmd/Alt+Right jumps a
                                                        // word. None of them mean "accept".
                                                        val plain = !event.hasModifiers()
                                                        // No separate `atEnd`: `ghostCompletion`
                                                        // is null unless the caret is at the end,
                                                        // so a non-null completion already means
                                                        // Right is where it may accept.
                                                        if (plain && completion != null) {
                                                            urlField =
                                                                TextFieldValue(
                                                                    completion.display,
                                                                    TextRange(completion.display.length),
                                                                )
                                                            inputText = completion.target
                                                            urlText = completion.display
                                                            urlCompletion = null
                                                            // An accepted completion is where the
                                                            // user stopped, so nothing may extend
                                                            // it until they type again. Without
                                                            // this, accepting "github.com" ghosts
                                                            // the most-visited page under it and
                                                            // Enter goes somewhere else entirely.
                                                            completionAllowed = false
                                                            // Accepting moves past the list, so it
                                                            // closes with the proposal - the same
                                                            // as the address bar's Right path.
                                                            // Recorded against the accepted text
                                                            // too: the write above re-keys the
                                                            // suggestion effect, which would
                                                            // otherwise re-open the list one
                                                            // debounce later.
                                                            showUrlDropdown = false
                                                            suggestionsDismissedFor = completion.display
                                                            true
                                                        } else {
                                                            // Nothing to accept: Tab has to keep
                                                            // moving focus, or the dialog's own
                                                            // buttons become unreachable from the
                                                            // keyboard. The address bar returns
                                                            // false here for the same reason.
                                                            false
                                                        }
                                                    }

                                                    Key.Enter -> {
                                                        if (selectedSuggestionIndex >= 0 &&
                                                            selectedSuggestionIndex < urlSuggestions.size
                                                        ) {
                                                            showUrlDropdown = false
                                                            handleCreateTab(
                                                                selectedType,
                                                                urlToOpen,
                                                                onCreateTab,
                                                                onDismiss,
                                                            )
                                                            true
                                                        } else {
                                                            false
                                                        }
                                                    }

                                                    Key.Escape -> {
                                                        // `ghostCompletion`, not `urlCompletion`:
                                                        // a completion suppressed by a selection
                                                        // or a highlighted row is not on screen,
                                                        // and Escape consuming the key with
                                                        // nothing visibly changing is how a key
                                                        // that should have closed the dialog did
                                                        // nothing at all. Every other read of the
                                                        // completion already goes through this
                                                        // one; this was the last that did not.
                                                        if (showUrlDropdown || ghostCompletion != null) {
                                                            showUrlDropdown = false
                                                            // A lookup still inside the debounce
                                                            // would otherwise land and re-open
                                                            // the list Escape just closed.
                                                            suggestionsDismissedFor = urlField.text
                                                            // The highlighted row goes with the
                                                            // list. It outranks the ghost in
                                                            // `urlToOpen`, so leaving it behind
                                                            // meant Escape then Enter opened a
                                                            // row that was no longer on screen.
                                                            selectedSuggestionIndex = -1
                                                            // The ghost is a proposal, so the key
                                                            // that rejects the list rejects it
                                                            // too. Leaving it behind meant Escape
                                                            // then Enter opened the completion the
                                                            // user had just dismissed.
                                                            urlCompletion = null
                                                            completionAllowed = false
                                                            true
                                                        } else {
                                                            false
                                                        }
                                                    }

                                                    else -> {
                                                        false
                                                    }
                                                }
                                            } else {
                                                false
                                            }
                                        },
                                visualTransformation =
                                    remember(ghostCompletion, ghostColor) {
                                        ghostTextTransformation(ghostCompletion, ghostColor)
                                    },
                                colors =
                                    TextFieldDefaults.outlinedTextFieldColors(
                                        textColor = BossTheme.colors.textPrimary,
                                        cursorColor = BossTheme.colors.textPrimary,
                                        focusedBorderColor = BossTheme.colors.signal,
                                        unfocusedBorderColor = BossTheme.colors.line,
                                        backgroundColor = BossTheme.colors.panel,
                                    ),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions =
                                    KeyboardActions(
                                        onDone = { handleCreateTab(selectedType, urlToOpen, onCreateTab, onDismiss) },
                                    ),
                            )

                            // URL suggestions dropdown
                            if (showUrlDropdown) {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 200.dp)
                                            .background(
                                                color = ContextMenuBackground,
                                                shape = RoundedCornerShape(0.dp, 0.dp, 4.dp, 4.dp),
                                            ).border(
                                                width = 1.dp,
                                                color = ContextMenuBorder,
                                                shape = RoundedCornerShape(0.dp, 0.dp, 4.dp, 4.dp),
                                            ),
                                ) {
                                    LazyColumn(state = listState) {
                                        itemsIndexed(urlSuggestions) { index, suggestion ->
                                            Row(
                                                modifier =
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .background(
                                                            if (index == selectedSuggestionIndex) {
                                                                BossTheme.colors.signal.copy(alpha = 0.2f)
                                                            } else {
                                                                Color.Transparent
                                                            },
                                                        ).clickable {
                                                            // A click names its own row, so this
                                                            // is the one commit path that does
                                                            // not read `urlToOpen`.
                                                            showUrlDropdown = false
                                                            handleCreateTab(TabType.URL, suggestion.url, onCreateTab, onDismiss)
                                                        }.padding(horizontal = 16.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(
                                                    imageVector =
                                                        if (suggestion.isSearchSuggestion) {
                                                            Icons.Default.Search
                                                        } else {
                                                            Icons.Default.History
                                                        },
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                    tint = BossTheme.colors.textSecondary,
                                                )
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = suggestion.title.ifEmpty { suggestion.url },
                                                        fontSize = 14.sp,
                                                        color = BossTheme.colors.textPrimary,
                                                        maxLines = 1,
                                                    )
                                                    if (suggestion.title.isNotEmpty()) {
                                                        Text(
                                                            text = suggestion.url,
                                                            fontSize = 12.sp,
                                                            color = BossTheme.colors.textSecondary,
                                                            maxLines = 1,
                                                        )
                                                    }
                                                }
                                                IconButton(
                                                    onClick = {
                                                        UrlHistoryProvider.deleteUrl(suggestion.url)
                                                        // Update suggestions
                                                        urlSuggestions = urlSuggestions.filterNot { it.url == suggestion.url }
                                                        // The index addressed a row in the OLD
                                                        // list; every row after the deleted one
                                                        // has shifted under it.
                                                        selectedSuggestionIndex = -1
                                                        if (urlSuggestions.isEmpty()) {
                                                            showUrlDropdown = false
                                                        }
                                                    },
                                                    modifier = Modifier.size(24.dp),
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Close,
                                                        contentDescription = "Delete",
                                                        modifier = Modifier.size(16.dp),
                                                        tint = BossTheme.colors.textSecondary,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } // end availableTypes.isNotEmpty() else

                Spacer(modifier = Modifier.height(16.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // On the same line as Cancel and Create, and to their left, because it
                    // qualifies the button next to it rather than the tab type above: "create
                    // this, over there". A row of its own would have read as another property of
                    // the tab, alongside its URL or its command.
                    if (onSplitDirectionChange != null) {
                        SplitDirectionPicker(
                            selected = splitDirection,
                            onSelect = onSplitDirectionChange,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    TextButton(
                        onClick = onDismiss,
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = BossTheme.colors.textSecondary,
                            ),
                    ) {
                        Text("Cancel")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            if (selectedPluginTypeInfo != null) {
                                confirmPluginTab()
                            } else {
                                // Same rule as Enter: a ghost completion on screen is what the
                                // field reads as, so confirming takes it.
                                val input =
                                    when (selectedType) {
                                        TabType.TERMINAL -> terminalCommand
                                        TabType.URL -> urlToOpen
                                        else -> inputText
                                    }
                                handleCreateTab(selectedType, input, onCreateTab, onDismiss)
                            }
                        },
                        enabled =
                            if (selectedPluginTypeInfo != null) {
                                selectedPluginTypeInfo.newTabSpec!!.inputOptional || pluginInput.isNotBlank()
                            } else {
                                availableTypes.isNotEmpty() &&
                                    (selectedType == TabType.TERMINAL || selectedType == TabType.JUPYTER || inputText.isNotBlank())
                            },
                        colors =
                            ButtonDefaults.buttonColors(
                                backgroundColor = BossTheme.colors.signal,
                                contentColor = BossTheme.colors.onSignal,
                                disabledBackgroundColor = BossTheme.colors.raised,
                                disabledContentColor = BossTheme.colors.textMuted,
                            ),
                    ) {
                        Text(
                            if (selectedPluginTypeInfo != null) {
                                selectedPluginTypeInfo.newTabSpec!!.confirmLabel
                            } else {
                                when (selectedType) {
                                    TabType.URL -> "Fluck it"
                                    TabType.FILE -> "Open"
                                    TabType.TERMINAL -> "Open Terminal"
                                    TabType.JUPYTER -> "New Notebook"
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabTypeOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .height(80.dp)
                .background(
                    color = if (isSelected) BossTheme.colors.signal.copy(alpha = 0.2f) else ContextMenuBorder,
                    shape = RoundedCornerShape(4.dp),
                ).border(
                    width = 1.dp,
                    color = if (isSelected) BossTheme.colors.signal else ContextMenuBorder,
                    shape = RoundedCornerShape(4.dp),
                ).clickable { onClick() },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) BossTheme.colors.signal else BossTheme.colors.textSecondary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                fontSize = 13.sp,
                color = if (isSelected) BossTheme.colors.textPrimary else BossTheme.colors.textSecondary,
            )
        }
    }
}

private fun handleCreateTab(
    type: TabType,
    input: String,
    onCreateTab: (TabType, String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (type != TabType.TERMINAL && type != TabType.JUPYTER && input.isBlank()) return

    val processedInput =
        when (type) {
            TabType.URL -> {
                processUrlInput(input)
            }

            TabType.FILE -> {
                // Validate file path to prevent path traversal attacks
                val validatedPath = validateFilePath(input.trim())
                if (validatedPath == null) {
                    // Path validation failed - don't create the tab
                    newTabDialogLogger.warn(LogCategory.FILE, "File path validation failed", mapOf("path" to input.trim()))
                    return
                }
                validatedPath
            }

            TabType.TERMINAL -> {
                // Pass the command (or empty string if none)
                input.trim()
            }

            TabType.JUPYTER -> {
                input.trim()
            } // empty = new untitled notebook
        }

    onCreateTab(type, processedInput)
    onDismiss()
}

// Helper function to process URL input - either as URL or search query
private fun processUrlInput(input: String): String {
    val trimmed = input.trim()
    val lowerTrimmed = trimmed.lowercase()

    // If it's already a full URL or special scheme, return as-is
    if (lowerTrimmed.startsWith("http://") || lowerTrimmed.startsWith("https://") ||
        lowerTrimmed.startsWith("file://") || lowerTrimmed.startsWith("javascript:") ||
        lowerTrimmed.startsWith("chrome://")
    ) {
        return trimmed
    }

    // Check if it looks like a URL (contains dots and no spaces)
    val looksLikeUrl = trimmed.contains(".") && !trimmed.contains(" ")

    // Check for common URL patterns
    val urlPattern = Regex("""^([a-zA-Z0-9-]+\.)+[a-zA-Z]{2,}(/.*)?$""")
    val isLikelyUrl = looksLikeUrl || urlPattern.matches(trimmed)

    // Check for localhost patterns
    val isLocalhost =
        trimmed.startsWith("localhost") ||
            trimmed.matches(Regex("""^127\.0\.0\.1(:\d+)?(/.*)?$""")) ||
            trimmed.matches(Regex("""^localhost(:\d+)?(/.*)?$"""))

    return when {
        isLocalhost -> "http://$trimmed"
        isLikelyUrl -> "https://$trimmed"
        else -> "https://www.google.com/search?q=${encodeUrlParameter(trimmed)}"
    }
}

/**
 * Simplified file tree item for the NewTabDialog file browser.
 */
@Composable
private fun DialogFileTreeItem(
    node: FileNodeData,
    level: Int,
    expandedPaths: Set<String>,
    onToggleExpanded: (String) -> Unit,
    onFileClick: (FileNodeData) -> Unit,
) {
    val isExpanded = expandedPaths.contains(node.path)
    val hasChildren = node.isDirectory && (node.hasChildren != false || node.children.isNotEmpty())

    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .clickable {
                        if (node.isDirectory) {
                            onToggleExpanded(node.path)
                        } else {
                            onFileClick(node)
                        }
                    }.padding(start = (8 + level * 12).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Expand/collapse icon for directories
            if (node.isDirectory && hasChildren) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = BossTheme.colors.textSecondary,
                    modifier = Modifier.size(14.dp),
                )
            } else {
                Spacer(modifier = Modifier.width(14.dp))
            }

            Spacer(modifier = Modifier.width(2.dp))

            // File/folder icon - use centralized FileIcons
            val iconInfo =
                if (node.isDirectory) {
                    FileIcons.forFolder(isExpanded)
                } else {
                    FileIcons.forFile(node.name)
                }
            Icon(
                imageVector = iconInfo.icon,
                contentDescription = if (node.isDirectory) "Folder" else "File",
                tint = iconInfo.color,
                modifier = Modifier.size(14.dp),
            )

            Spacer(modifier = Modifier.width(4.dp))

            // File/folder name
            Text(
                text = node.name,
                fontSize = 12.sp,
                color = BossTheme.colors.textPrimary,
            )
        }

        // Show children if expanded
        if (node.isDirectory && isExpanded && node.children.isNotEmpty()) {
            node.children.forEach { child ->
                DialogFileTreeItem(
                    node = child,
                    level = level + 1,
                    expandedPaths = expandedPaths,
                    onToggleExpanded = onToggleExpanded,
                    onFileClick = onFileClick,
                )
            }
        }
    }
}
