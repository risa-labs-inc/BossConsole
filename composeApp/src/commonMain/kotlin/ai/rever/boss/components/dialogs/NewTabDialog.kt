package ai.rever.boss.components.dialogs

import ContextMenuBackground
import ContextMenuBorder
import ai.rever.boss.plugin.api.NewTabContext
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.tab.jupyter.JupyterTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.icons.FileIcons
import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.utils.extractFileName
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import ai.rever.boss.platform.rememberFilePicker
import ai.rever.boss.platform.rememberDirectoryPicker
import ai.rever.boss.components.overlays.ContextMenu
import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.components.plugin.panels.left_top.ProjectState
import ai.rever.boss.window.Project
import ai.rever.boss.plugin.api.FileNodeData
import ai.rever.boss.plugin.api.NodeLoadingStateData
import ai.rever.boss.plugin.api.FileTreeUtils
import ai.rever.boss.components.plugin.panels.left_top.scanDirectory
import ai.rever.boss.components.plugin.panels.left_top.directoryHasChildren
import ai.rever.boss.components.plugin.panels.left_top.scanDirectoryWithDepth
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val newTabDialogLogger = BossLogger.forComponent("NewTabDialog")

/**
 * Validates and sanitizes a file path to prevent path traversal attacks.
 *
 * @param path The file path to validate
 * @param basePath Optional base path that the file must be within (null allows any path)
 * @return The canonical path if valid, null if the path is invalid or attempts traversal
 */
private fun validateFilePath(path: String, basePath: String? = null): String? {
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

enum class TabType(val tabTypeId: TabTypeId) {
    URL(FluckTabType.typeId),
    FILE(CodeEditorTabType.typeId),
    TERMINAL(TerminalTabType.typeId),
    JUPYTER(JupyterTabInfo.TYPE_ID)
}

// Simple URL parameter encoding
private fun encodeUrlParameter(input: String): String {
    return input
        .replace(" ", "+")
        .replace("&", "%26")
        .replace("#", "%23")
        .replace("?", "%3F")
        .replace("=", "%3D")
        .replace("/", "%2F")
}

// Platform-specific URL history provider
expect object UrlHistoryProvider {
    fun getSuggestions(query: String, limit: Int = 10): List<UrlSuggestion>
    fun deleteUrl(url: String)
}

data class UrlSuggestion(
    val url: String,
    val title: String,
    val isSearchSuggestion: Boolean = false
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
    windowId: String? = null
) {
    val availableTypes = TabType.entries.filter { tabRegistry.isRegistered(it.tabTypeId) }
    // Plugin-registered tab types that opted into the dialog (newTabSpec).
    // TabRegistry is state-backed, so this recomposes on (un)registration.
    val builtinTypeIds = TabType.entries.map { it.tabTypeId }.toSet()
    val pluginTypes = if (onCreateTabInfo != null) {
        tabRegistry.getAllTabTypes()
            .filter { it.newTabSpec != null && it.typeId !in builtinTypeIds }
            .sortedWith(compareBy({ it.newTabSpec!!.order }, { it.displayName }))
    } else {
        emptyList()
    }
    val defaultType = if (initialTabType != null && initialTabType in availableTypes) initialTabType
        else availableTypes.firstOrNull() ?: TabType.URL
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

    // Confirm a plugin tab type: the plugin builds the TabInfo (null = input
    // rejected, dialog stays open). Crash-isolated — plugin code.
    val confirmPluginTab: () -> Unit = confirm@{
        val typeInfo = selectedPluginTypeInfo ?: return@confirm
        val spec = typeInfo.newTabSpec ?: return@confirm
        if (!spec.inputOptional && pluginInput.isBlank()) return@confirm
        val tabInfo = try {
            typeInfo.createTabInfo(pluginInput.trim(), NewTabContext(projectPath = projectPath, windowId = windowId))
        } catch (e: Exception) {
            newTabDialogLogger.warn(LogCategory.UI, "Plugin createTabInfo failed", mapOf(
                "typeId" to typeInfo.typeId.typeId
            ), e)
            null
        }
        if (tabInfo != null) {
            onCreateTabInfo?.invoke(tabInfo)
            onDismiss()
        }
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

    // File picker for browsing files
    val filePicker = rememberFilePicker(
        onFileSelected = { path, _ ->
            path?.let {
                fileText = it
                inputText = it
            }
        },
        fileExtensions = emptyList() // Allow all files
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    
    // Update suggestions when URL text changes
    LaunchedEffect(urlText, selectedType) {
        if (selectedType == TabType.URL && urlText.isNotEmpty()) {
            delay(100) // Small debounce
            urlSuggestions = UrlHistoryProvider.getSuggestions(urlText)
            showUrlDropdown = urlSuggestions.isNotEmpty()
            selectedSuggestionIndex = -1
        } else {
            urlSuggestions = emptyList()
            showUrlDropdown = false
        }
    }

    // Auto-scroll to selected suggestion when using arrow keys
    LaunchedEffect(selectedSuggestionIndex) {
        if (selectedSuggestionIndex >= 0 && urlSuggestions.isNotEmpty()) {
            listState.animateScrollToItem(selectedSuggestionIndex)
        }
    }

    // Full-screen overlay with scrim
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            // Dialog content with ContextMenu styling
            Box(
                modifier = Modifier
                    .width(500.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* Consume click to prevent dismissing */ }
                    .background(
                        color = ContextMenuBackground,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = ContextMenuBorder,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onDismiss()
                            true
                        } else {
                            false
                        }
                    }
            ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                // Title
                Text(
                    text = "New Tab",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = BossTheme.colors.textPrimary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                if (availableTypes.isEmpty() && pluginTypes.isEmpty()) {
                    // Empty state when no tab plugins are enabled
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No tab types available. Enable a tab plugin or install one from the Plugin Store.",
                            color = BossTheme.colors.textSecondary,
                            fontSize = 13.sp
                        )
                    }
                } else {
                // Type selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (TabType.URL in availableTypes) {
                    TabTypeOption(
                        icon = Icons.Default.Language,
                        label = "URL",
                        isSelected = selectedPluginType == null && selectedType == TabType.URL,
                        onClick = {
                            // Save current text before switching
                            when (selectedType) {
                                TabType.FILE -> fileText = inputText
                                TabType.JUPYTER -> jupyterName = inputText
                                else -> {}
                            }
                            selectedPluginType = null
                            selectedType = TabType.URL
                            inputText = urlText
                        },
                        modifier = Modifier.weight(1f)
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
                                TabType.URL -> urlText = inputText
                                TabType.JUPYTER -> jupyterName = inputText
                                else -> {}
                            }
                            selectedPluginType = null
                            selectedType = TabType.FILE
                            inputText = fileText
                        },
                        modifier = Modifier.weight(1f)
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
                                TabType.URL -> urlText = inputText
                                TabType.FILE -> fileText = inputText
                                TabType.JUPYTER -> jupyterName = inputText
                                else -> {}
                            }
                            selectedPluginType = null
                            selectedType = TabType.TERMINAL
                            inputText = terminalCommand
                        },
                        modifier = Modifier.weight(1f)
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
                                when (selectedType) {
                                    TabType.URL -> urlText = inputText
                                    TabType.FILE -> fileText = inputText
                                    TabType.JUPYTER -> jupyterName = inputText
                                    else -> {}
                                }
                                selectedPluginType = pluginType.typeId
                            },
                            modifier = Modifier.weight(1f)
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
                                    TabType.URL -> urlText = inputText
                                    TabType.FILE -> fileText = inputText
                                    TabType.TERMINAL -> terminalCommand = inputText
                                    else -> {}
                                }
                                selectedType = TabType.JUPYTER
                                inputText = jupyterName
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Input field
                Column {
                    // Plugin tab type input — one generic field driven by the
                    // type's NewTabSpec; the plugin validates via createTabInfo.
                    if (selectedPluginTypeInfo != null) {
                        val spec = selectedPluginTypeInfo.newTabSpec!!
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
                                    color = BossTheme.colors.textSecondary
                                )
                            },
                            placeholder = {
                                Text(
                                    spec.inputPlaceholder,
                                    color = BossTheme.colors.textMuted
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(pluginFocusRequester)
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                        confirmPluginTab()
                                        true
                                    } else false
                                },
                            colors = TextFieldDefaults.outlinedTextFieldColors(
                                textColor = BossTheme.colors.textPrimary,
                                cursorColor = BossTheme.colors.textPrimary,
                                focusedBorderColor = BossTheme.colors.signal,
                                unfocusedBorderColor = BossTheme.colors.line,
                                backgroundColor = BossTheme.colors.panel
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { confirmPluginTab() })
                        )
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
                                    color = BossTheme.colors.textSecondary
                                )
                            },
                            placeholder = {
                                Text(
                                    "e.g., npm run dev",
                                    color = BossTheme.colors.textMuted
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(terminalFocusRequester)
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                        handleCreateTab(selectedType, terminalCommand, onCreateTab, onDismiss)
                                        true
                                    } else false
                                },
                            colors = TextFieldDefaults.outlinedTextFieldColors(
                                textColor = BossTheme.colors.textPrimary,
                                cursorColor = BossTheme.colors.textPrimary,
                                focusedBorderColor = BossTheme.colors.signal,
                                unfocusedBorderColor = BossTheme.colors.line,
                                backgroundColor = BossTheme.colors.panel
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    handleCreateTab(selectedType, terminalCommand, onCreateTab, onDismiss)
                                }
                            )
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(jupyterFocusRequester)
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                        handleCreateTab(selectedType, inputText, onCreateTab, onDismiss)
                                        true
                                    } else false
                                },
                            colors = TextFieldDefaults.outlinedTextFieldColors(
                                textColor = BossTheme.colors.textPrimary,
                                cursorColor = BossTheme.colors.textPrimary,
                                focusedBorderColor = BossTheme.colors.signal,
                                unfocusedBorderColor = BossTheme.colors.line,
                                backgroundColor = BossTheme.colors.panel
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = { handleCreateTab(selectedType, inputText, onCreateTab, onDismiss) }
                            )
                        )
                    } else if (selectedType == TabType.FILE) {
                        // Current project/folder selector (uses global recent projects)
                        // Note: In NewTabDialog we don't have window context, so we use the most recent project
                        val recentProjects by ProjectState.recentProjects.collectAsState()
                        var selectedProject by remember {
                            mutableStateOf(recentProjects.firstOrNull()
                                ?: Project("No Project", "", 0L))
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
                                fileTree = try {
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
                        val directoryPicker = rememberDirectoryPicker { path ->
                            path?.let {
                                val projectName = it.extractFileName().ifEmpty { "Unknown" }
                                val newProject = Project(
                                    name = projectName,
                                    path = it
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
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Button(
                                    onClick = { directoryPicker.pickDirectory() },
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = BossTheme.colors.signal,
                                        contentColor = BossTheme.colors.onSignal
                                    ),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FolderOpen,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
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
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onGloballyPositioned { coordinates ->
                                            buttonHeight = coordinates.size.height
                                        },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        backgroundColor = BossTheme.colors.panel,
                                        contentColor = BossTheme.colors.textPrimary
                                    ),
                                    border = ButtonDefaults.outlinedBorder.copy(
                                        brush = androidx.compose.ui.graphics.SolidColor(BossTheme.colors.line)
                                    ),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Folder,
                                        contentDescription = "Folder",
                                        tint = BossTheme.colors.signal,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = selectedProject.name,
                                        color = BossTheme.colors.textPrimary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = "Expand",
                                        tint = BossTheme.colors.textSecondary
                                    )
                                }

                                if (showFolderDropdown) {
                                    ContextMenu(
                                        items = buildList {
                                            // Recent projects
                                            recentProjects.forEach { project ->
                                                add(ContextMenuItem(
                                                    text = project.name,
                                                    icon = Icons.Outlined.Folder,
                                                    onClick = {
                                                        selectedProject = project
                                                        expandedPaths = emptySet()
                                                    }
                                                ))
                                            }
                                            // Divider if there are recent projects
                                            if (recentProjects.isNotEmpty()) {
                                                add(ContextMenuItem(isDivider = true))
                                            }
                                            // Browse option
                                            add(ContextMenuItem(
                                                text = "Browse...",
                                                icon = Icons.Default.FolderOpen,
                                                onClick = { directoryPicker.pickDirectory() }
                                            ))
                                        },
                                        offset = IntOffset(0, buttonHeight),
                                        modifier = Modifier.widthIn(min = 200.dp),
                                        onDismissRequest = { showFolderDropdown = false }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                        // File tree browser
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .background(
                                    color = BossTheme.colors.panel,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = ContextMenuBorder,
                                    shape = RoundedCornerShape(4.dp)
                                )
                        ) {
                            if (isLoadingTree) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = BossTheme.colors.signal,
                                        strokeWidth = 2.dp
                                    )
                                }
                            } else if (fileTree != null && fileTree?.children?.isNotEmpty() == true) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(4.dp)
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
                                                                    val scannedNode = withContext(Dispatchers.IO) {
                                                                        scanDirectoryWithDepth(path, maxDepth = 1, startDepth = 0)
                                                                    }
                                                                    if (scannedNode != null) {
                                                                        val loadedChildren = scannedNode.children.map { child ->
                                                                            if (child.isDirectory) {
                                                                                val hasKids = try {
                                                                                    directoryHasChildren(child.path)
                                                                                } catch (e: Exception) {
                                                                                    newTabDialogLogger.debug(LogCategory.FILE, "Cannot probe directory for children", mapOf("path" to child.path, "error" to e.toString()))
                                                                                    false
                                                                                }
                                                                                child.copy(hasChildren = hasKids)
                                                                            } else {
                                                                                child
                                                                            }
                                                                        }
                                                                        fileTree = FileTreeUtils.updateNodeAtPath(currentTree, path) { existingNode ->
                                                                            existingNode.copy(
                                                                                children = loadedChildren,
                                                                                hasChildren = loadedChildren.isNotEmpty()
                                                                            )
                                                                        }
                                                                    }
                                                                } catch (e: Exception) {
                                                                    newTabDialogLogger.warn(LogCategory.FILE, "Error loading folder children", error = e)
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            },
                                            onFileClick = { file ->
                                                inputText = file.path
                                                fileText = file.path
                                            }
                                        )
                                    }
                                }
                            } else if (fileTree != null && fileTree?.children?.isEmpty() == true) {
                                // Empty folder (hidden files like .git are excluded)
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "No visible files",
                                            color = BossTheme.colors.textSecondary,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = "(hidden files and build folders are excluded)",
                                            color = BossTheme.colors.textMuted,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Unable to load files",
                                        color = BossTheme.colors.textSecondary,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // File input with browse button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
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
                                        color = BossTheme.colors.textSecondary
                                    )
                                },
                                placeholder = {
                                    Text(
                                        "Select a file above or enter path",
                                        color = BossTheme.colors.textMuted
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(focusRequester)
                                    .onPreviewKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                            handleCreateTab(selectedType, inputText, onCreateTab, onDismiss)
                                            true
                                        } else false
                                    },
                                colors = TextFieldDefaults.outlinedTextFieldColors(
                                    textColor = BossTheme.colors.textPrimary,
                                    cursorColor = BossTheme.colors.textPrimary,
                                    focusedBorderColor = BossTheme.colors.signal,
                                    unfocusedBorderColor = BossTheme.colors.line,
                                    backgroundColor = BossTheme.colors.panel
                                ),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        handleCreateTab(selectedType, inputText, onCreateTab, onDismiss)
                                    }
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = { filePicker.pickFile() },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = "Browse files",
                                    tint = BossTheme.colors.textSecondary
                                )
                            }
                        }
                        }
                    } else {
                        // URL input
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { newValue ->
                                inputText = newValue
                                urlText = newValue
                            },
                            label = {
                                Text(
                                    "Enter URL or search term",
                                    color = BossTheme.colors.textSecondary
                                )
                            },
                            placeholder = {
                                Text(
                                    "https://example.com or search...",
                                    color = BossTheme.colors.textMuted
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown) {
                                        when (event.key) {
                                            Key.DirectionDown -> {
                                                // Always consume arrow keys to prevent cursor movement in text field
                                                if (showUrlDropdown && urlSuggestions.isNotEmpty()) {
                                                    selectedSuggestionIndex = (selectedSuggestionIndex + 1).coerceAtMost(urlSuggestions.size - 1)
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
                                            Key.Enter -> {
                                                if (selectedSuggestionIndex >= 0 && selectedSuggestionIndex < urlSuggestions.size) {
                                                    val suggestion = urlSuggestions[selectedSuggestionIndex]
                                                    inputText = suggestion.url
                                                    urlText = suggestion.url
                                                    showUrlDropdown = false
                                                    handleCreateTab(selectedType, inputText, onCreateTab, onDismiss)
                                                    true
                                                } else false
                                            }
                                            Key.Escape -> {
                                                if (showUrlDropdown) {
                                                    showUrlDropdown = false
                                                    true
                                                } else false
                                            }
                                            else -> false
                                        }
                                    } else false
                                },
                            colors = TextFieldDefaults.outlinedTextFieldColors(
                                textColor = BossTheme.colors.textPrimary,
                                cursorColor = BossTheme.colors.textPrimary,
                                focusedBorderColor = BossTheme.colors.signal,
                                unfocusedBorderColor = BossTheme.colors.line,
                                backgroundColor = BossTheme.colors.panel
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (selectedSuggestionIndex >= 0 && selectedSuggestionIndex < urlSuggestions.size) {
                                        val suggestion = urlSuggestions[selectedSuggestionIndex]
                                        handleCreateTab(selectedType, suggestion.url, onCreateTab, onDismiss)
                                    } else {
                                        handleCreateTab(selectedType, inputText, onCreateTab, onDismiss)
                                    }
                                }
                            )
                        )
                        
                        // URL suggestions dropdown
                        if (showUrlDropdown) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .background(
                                        color = ContextMenuBackground,
                                        shape = RoundedCornerShape(0.dp, 0.dp, 4.dp, 4.dp)
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = ContextMenuBorder,
                                        shape = RoundedCornerShape(0.dp, 0.dp, 4.dp, 4.dp)
                                    )
                            ) {
                                LazyColumn(state = listState) {
                                    itemsIndexed(urlSuggestions) { index, suggestion ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    if (index == selectedSuggestionIndex)
                                                        BossTheme.colors.signal.copy(alpha = 0.2f)
                                                    else
                                                        Color.Transparent
                                                )
                                                .clickable {
                                                    inputText = suggestion.url
                                                    urlText = suggestion.url
                                                    showUrlDropdown = false
                                                    handleCreateTab(TabType.URL, suggestion.url, onCreateTab, onDismiss)
                                                }
                                                .padding(horizontal = 16.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (suggestion.isSearchSuggestion) Icons.Default.Search else Icons.Default.History,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                                tint = BossTheme.colors.textSecondary
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = suggestion.title.ifEmpty { suggestion.url },
                                                    fontSize = 14.sp,
                                                    color = BossTheme.colors.textPrimary,
                                                    maxLines = 1
                                                )
                                                if (suggestion.title.isNotEmpty()) {
                                                    Text(
                                                        text = suggestion.url,
                                                        fontSize = 12.sp,
                                                        color = BossTheme.colors.textSecondary,
                                                        maxLines = 1
                                                    )
                                                }
                                            }
                                            IconButton(
                                                onClick = {
                                                    UrlHistoryProvider.deleteUrl(suggestion.url)
                                                    // Update suggestions
                                                    urlSuggestions = urlSuggestions.filterNot { it.url == suggestion.url }
                                                    if (urlSuggestions.isEmpty()) {
                                                        showUrlDropdown = false
                                                    }
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Delete",
                                                    modifier = Modifier.size(16.dp),
                                                    tint = BossTheme.colors.textSecondary
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
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = BossTheme.colors.textSecondary
                        )
                    ) {
                        Text("Cancel")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            if (selectedPluginTypeInfo != null) {
                                confirmPluginTab()
                            } else {
                                val input = if (selectedType == TabType.TERMINAL) terminalCommand else inputText
                                handleCreateTab(selectedType, input, onCreateTab, onDismiss)
                            }
                        },
                        enabled = if (selectedPluginTypeInfo != null) {
                            selectedPluginTypeInfo.newTabSpec!!.inputOptional || pluginInput.isNotBlank()
                        } else {
                            availableTypes.isNotEmpty() && (selectedType == TabType.TERMINAL || selectedType == TabType.JUPYTER || inputText.isNotBlank())
                        },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = BossTheme.colors.signal,
                            contentColor = BossTheme.colors.onSignal,
                            disabledBackgroundColor = BossTheme.colors.raised,
                            disabledContentColor = BossTheme.colors.textMuted
                        )
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
                            }
                        )
                    }
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
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(80.dp)
            .background(
                color = if (isSelected) BossTheme.colors.signal.copy(alpha = 0.2f) else ContextMenuBorder,
                shape = RoundedCornerShape(4.dp)
            )
            .border(
                width = 1.dp,
                color = if (isSelected) BossTheme.colors.signal else ContextMenuBorder,
                shape = RoundedCornerShape(4.dp)
            )
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) BossTheme.colors.signal else BossTheme.colors.textSecondary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                fontSize = 13.sp,
                color = if (isSelected) BossTheme.colors.textPrimary else BossTheme.colors.textSecondary
            )
        }
    }
}

private fun handleCreateTab(
    type: TabType,
    input: String,
    onCreateTab: (TabType, String) -> Unit,
    onDismiss: () -> Unit
) {
    if (type != TabType.TERMINAL && type != TabType.JUPYTER && input.isBlank()) return

    val processedInput = when (type) {
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
        TabType.JUPYTER -> input.trim() // empty = new untitled notebook
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
        lowerTrimmed.startsWith("chrome://")) {
        return trimmed
    }
    
    // Check if it looks like a URL (contains dots and no spaces)
    val looksLikeUrl = trimmed.contains(".") && !trimmed.contains(" ")
    
    // Check for common URL patterns
    val urlPattern = Regex("""^([a-zA-Z0-9-]+\.)+[a-zA-Z]{2,}(/.*)?$""")
    val isLikelyUrl = looksLikeUrl || urlPattern.matches(trimmed)
    
    // Check for localhost patterns
    val isLocalhost = trimmed.startsWith("localhost") || 
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
    onFileClick: (FileNodeData) -> Unit
) {
    val isExpanded = expandedPaths.contains(node.path)
    val hasChildren = node.isDirectory && (node.hasChildren != false || node.children.isNotEmpty())

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clickable {
                    if (node.isDirectory) {
                        onToggleExpanded(node.path)
                    } else {
                        onFileClick(node)
                    }
                }
                .padding(start = (8 + level * 12).dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Expand/collapse icon for directories
            if (node.isDirectory && hasChildren) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = BossTheme.colors.textSecondary,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Spacer(modifier = Modifier.width(14.dp))
            }

            Spacer(modifier = Modifier.width(2.dp))

            // File/folder icon - use centralized FileIcons
            val iconInfo = if (node.isDirectory) {
                FileIcons.forFolder(isExpanded)
            } else {
                FileIcons.forFile(node.name)
            }
            Icon(
                imageVector = iconInfo.icon,
                contentDescription = if (node.isDirectory) "Folder" else "File",
                tint = iconInfo.color,
                modifier = Modifier.size(14.dp)
            )

            Spacer(modifier = Modifier.width(4.dp))

            // File/folder name
            Text(
                text = node.name,
                fontSize = 12.sp,
                color = BossTheme.colors.textPrimary
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
                    onFileClick = onFileClick
                )
            }
        }
    }
}
