@file:Suppress("TooManyFunctions")

package ai.rever.boss.components.settings.search

import ai.rever.boss.components.plugin.PanelIds
import ai.rever.boss.components.settings.sidebar.SettingsSection
import ai.rever.boss.plugin.api.PanelId

/*
 * Every settings entry, declared one section per function.
 *
 * Split out of SettingsSearchIndex.kt so neither file mixes the model with 150 lines of data,
 * and suppressed for TooManyFunctions at file level rather than baselined: one function per
 * section is the point of the layout, and the count is meant to grow with the settings window.
 */

/** Collects the entries for one section. See the per-section functions below. */
private fun section(
    section: SettingsSection,
    block: EntryScope.() -> Unit,
): List<SettingsSearchEntry> = EntryScope(section).apply(block).entries

private class EntryScope(
    private val section: SettingsSection,
) {
    val entries = mutableListOf<SettingsSearchEntry>()

    /** A `SettingsSection(title = ...)` group header. Jumpable in its own right. */
    fun group(
        title: String,
        vararg keywords: String,
    ) {
        entries += SettingsSearchEntry(label = title, section = section, keywords = keywords.toList())
    }

    /** A single control. [group] is the enclosing group's title, which disambiguates duplicates. */
    fun setting(
        label: String,
        group: String,
        vararg keywords: String,
    ) {
        entries +=
            SettingsSearchEntry(
                label = label,
                section = section,
                group = group,
                keywords = keywords.toList(),
            )
    }

    /**
     * A control that sits directly on the page with no `SettingsSection` around it.
     *
     * Group is null, which is what the drift scanner computes for it too. [highlightable] is false
     * where the row is drawn by a local composable that carries no search target.
     */
    fun ungrouped(
        label: String,
        highlightable: Boolean,
        context: String? = null,
        vararg keywords: String,
    ) {
        entries +=
            SettingsSearchEntry(
                label = label,
                section = section,
                keywords = keywords.toList(),
                highlightable = highlightable,
                context = context,
            )
    }

    /** A hand-written catch-all so the section is reachable by words no label contains. */
    fun sectionLevel(vararg keywords: String) {
        entries +=
            SettingsSearchEntry(
                label = section.displayName,
                section = section,
                keywords = keywords.toList(),
                highlightable = false,
                curated = true,
            )
    }
}

private fun delegated(
    section: SettingsSection,
    vararg keywords: String,
) = SettingsSearchEntry(
    label = section.displayName,
    section = section,
    keywords = keywords.toList(),
    highlightable = false,
    curated = true,
)

/**
 * The three sections whose bodies belong to BossTerm, BossEditor and editor-tab.
 *
 * Roughly 300 labels live behind those panels and the host cannot enumerate a single one: the
 * API surface is one opaque `@Composable fun ...SettingsPanel(modifier)`. Curated keywords are
 * the honest stopgap - searching "cursor" lands the user on Terminal rather than on the control,
 * and the result row says so.
 *
 * Shortcuts is deliberately NOT here, though it was at first, and the mistake is worth recording:
 * having its own search box is not the same as belonging to another module. `EditableKeymapSettings`
 * is host code in this repo, so its controls can and should be indexed - see [keymapEntries]. Filing
 * it as delegated both left its content unfindable and exempted it from the drift guard, so nothing
 * reported the gap.
 */
private fun delegatedEntries() =
    listOf(
        delegated(
            SettingsSection.TERMINAL,
            "cursor",
            "font",
            "bell",
            "scrollback",
            "shell",
            "colors",
            "profile",
        ),
        delegated(
            SettingsSection.BOSS_EDITOR,
            "folding",
            "brackets",
            "indent",
            "auto save",
            "markdown",
            "preview",
        ),
        delegated(
            SettingsSection.LANGUAGE_SERVERS,
            "lsp",
            "completion",
            "diagnostics",
            "hover",
            "format",
        ),
    )

/**
 * A curated pointer to something the Settings window does not contain at all.
 *
 * [delegated] covers a page the host still frames; this covers one that has left. Picking it opens
 * [panel] in the main window and raises that window - see `applyHit` in `SettingsWindow`.
 *
 * Keywords are hand-written for the same reason as [delegated]'s, and here it is the only way: a
 * panel is not a settings page, so nothing merges it into the index at query time, and even the
 * page path would not help - `pluginPageEntry` keeps only words longer than three characters, so a
 * description reading "API key" would index neither half of it.
 *
 * @param owner what the breadcrumb says after "Plugins", naming the panel rather than the plugin -
 *   the reader is being sent somewhere, and the panel is the thing they will see.
 */
private fun panelSignpost(
    label: String,
    panel: PanelId,
    owner: String,
    vararg keywords: String,
) = SettingsSearchEntry(
    label = label,
    panel = panel,
    context = owner,
    keywords = keywords.toList(),
    highlightable = false,
    curated = true,
)

/**
 * Settings that used to be in this window and are now somewhere else.
 *
 * AI providers were `Settings > AI Providers` until the section was removed: the credentials live
 * in the Secret Manager panel's vault and the page that manages them moved beside them. Deleting
 * the section without this left the words a user actually types - "api key", "anthropic", "claude"
 * - matching nothing, so Settings search answered "No matching settings" for a feature that exists.
 *
 * The first six keywords are the ones the deleted `delegated(LLM_PROVIDERS, ...)` entry carried.
 * The rest name the panel itself, because [SettingsSearchMatcher] scores label, group, section
 * display name and keywords - **not** `context`. Without them the breadcrumb reads "Secret Manager
 * panel" and yet "secret manager" matched nothing, which is the likeliest thing to be typed by
 * someone who half-remembers that the keys live in a vault.
 */
private fun signpostEntries() =
    listOf(
        panelSignpost(
            label = "AI Providers",
            panel = PanelIds.SECRET_MANAGER,
            owner = "Secret Manager panel",
            "api key",
            "anthropic",
            "openai",
            "model",
            "claude",
            "gateway",
            "llm",
            "secret manager",
            "vault",
            "credentials",
        ),
    )

/**
 * UPDATES is host-owned but uses none of the shared controls - `UpdateUI.kt` is raw `Text` in
 * commonMain - so the drift scanner, which reads `settings/sections/`, cannot see it. Declared
 * by hand, and left out of both guards on purpose.
 */
private fun updatesEntries() =
    section(SettingsSection.UPDATES) {
        group("Version Information", "build", "current version")
        group("Update Settings")
        setting("Automatic Update Checks", "Update Settings", "auto update")
        setting("Include Pre-release Versions", "Update Settings", "beta", "alpha", "rc")
    }

private fun browserEntries() =
    section(SettingsSection.FLUCK) {
        group("Default Browser")
        group("Trackpad")
        setting(
            "Two-finger swipe navigation",
            "Trackpad",
            "swipe",
            "gesture",
            "trackpad",
            "back",
            "forward",
            "two finger",
        )
        group("Video calls")
        setting(
            "Keep calls on screen when switching tabs",
            "Video calls",
            "picture in picture",
            "pip",
            "meet",
            "google meet",
            "call",
            "video call",
            "pop out",
            "floating window",
        )
        group("User Agent")
        setting("Browser Identity", "User Agent", "user agent", "ua", "spoof")
        setting("Custom User Agent String", "User Agent", "ua string")
        group("Terminal Links")
        setting("Open links with", "Terminal Links", "terminal link", "click", "url handler")
        setting("Target panel", "Terminal Links")
        setting("Reset Link Behavior", "Terminal Links")
        group("Secret Manager")
        setting(
            "Suggest Strong Passwords",
            "Secret Manager",
            "generate",
            "generator",
            "signup",
            "password manager",
        )
        setting(
            "Offer to Save Passwords",
            "Secret Manager",
            "save password",
            "remember",
            "autofill",
            "password manager",
        )
        setting("Discrete Password Fill", "Secret Manager", "blur", "privacy", "autofill")
        group("Tab Sharing")
        setting("Show share (QR) button", "Tab Sharing", "co-browse", "cobrowse", "qr code")
        group("Advanced")
        setting("Max Initialization Retries", "Advanced")
        setting("Max Recovery Attempts", "Advanced")
        setting("Apply Browser Settings", "Advanced")
        group("Browser Profiles")
    }

/**
 * Default Apps has exactly one indexable label: its own section header.
 *
 * Everything inside it - the per-category rows and their Set buttons - is
 * generated at runtime from `boss-file-types.json`, so no label appears as a
 * literal in the source. `SettingsSearchIndexDriftTest` scans the sources, so
 * indexing those rows made it (correctly) report seven entries naming settings
 * that "no longer exist": a search result for them would navigate and highlight
 * nothing, which reads as the search being broken.
 *
 * The keywords therefore carry the weight. They are the words somebody actually
 * types when their links open in the wrong app - including the file extensions,
 * since "how do I open .md in BOSS" is the question this page answers.
 */
private fun defaultAppsEntries() =
    section(SettingsSection.DEFAULT_APPS) {
        group(
            "Default Apps",
            "default browser",
            "file association",
            "open with",
            "handler",
            "markdown",
            "md",
            "shell script",
            "sh",
            "source code",
            "http",
            "https",
            "html",
        )
    }

private fun browserEngineEntries() =
    section(SettingsSection.BROWSER_ENGINE) {
        group("Current Engine")
        setting("Installed version", "Current Engine", "chromium", "jxbrowser")
        setting("App default version", "Current Engine")
        group("Engine Version")
        setting("Engine version", "Engine Version", "chromium", "jxbrowser", "update")
        setting("Download and stage the selected version", "Engine Version")
        setting("Staged - restart to apply", "Engine Version")
        setting("Status", "Engine Version")
        group("Effective Chromium command line")
        setting("Active this session", "Effective Chromium command line")
        setting("After the next restart", "Effective Chromium command line")
        setting("Rendering mode", "Effective Chromium command line", "hardware", "software", "gpu", "accelerated")
        setting("HTTP disk cache", "Effective Chromium command line")
        setting("Container detected", "Effective Chromium command line")
        group("Apply")
        setting("Changes are waiting for a restart", "Apply")
        setting("Reset every flag to its default", "Apply")
        group("Rendering")
        setting("Rendering mode", "Rendering", "hardware", "software", "gpu", "accelerated")
        setting("App rendering backend (Skiko)", "Rendering", "metal", "opengl", "gpu", "skia")
        setting("Browser surface top offset (dp)", "Rendering")
        group("Performance")
        setting("HTTP disk cache (MB)", "Performance", "cache size")
        setting("Renderer process limit", "Performance")
        setting("Pre-warm the engine at startup", "Performance", "prewarm", "boot", "startup")
        setting("Skia Graphite (Metal) raster backend", "Performance", "gpu", "macos")
        setting("VA-API hardware video decode", "Performance", "linux", "gpu", "video")
        setting("Disable native-window occlusion tracking", "Performance")
        group("Privacy & Network")
        setting("Drop hyperlink auditing pings", "Privacy & Network")
        setting("Disable Domain Reliability reporting", "Privacy & Network")
        group("Advanced")
        setting("Extra Chromium switches", "Advanced")
        setting("Will be ignored", "Advanced")
        setting("Has its own setting above", "Advanced")
        group("Danger zone")
        setting("Disable the Chromium sandbox", "Danger zone", "security", "unsafe")
        setting("DevTools remote debugging", "Danger zone", "inspector", "debug", "cdp")
        setting("DevTools port", "Danger zone", "inspector", "debug", "cdp")
    }

private fun runnerEntries() =
    section(SettingsSection.RUNNER) {
        group("Terminal Target")
        group("Behavior")
        setting("Focus on Run", "Behavior", "runner")
        setting("Notify on Exit", "Behavior", "runner", "notification")
        setting("Re-run Delay", "Behavior")
        group("Run Controls")
        group("Notes")
    }

private fun workspaceEntries() =
    section(SettingsSection.WORKSPACE) {
        group("Default Workspace")
        group("When Switching Workspaces")
        group("About Workspaces")
    }

private fun securityEntries() =
    section(SettingsSection.SECURITY) {
        group("WebAuthn Authentication")
        setting("WebAuthn Support", "WebAuthn Authentication", "passkey", "touch id", "biometric")
        setting("Platform Authenticator", "WebAuthn Authentication", "passkey", "touch id", "biometric")
        setting("Security Key Support", "WebAuthn Authentication", "yubikey", "fido")
        setting("Cross-Device Authentication", "WebAuthn Authentication", "passkey", "hybrid", "qr")
        setting("NFC Support", "WebAuthn Authentication", "fido")
        group("Security Best Practices")
    }

private fun focusModeEntries() =
    section(SettingsSection.FOCUS_MODE) {
        group("Focus Mode")
        setting("Enable Focus Mode", "Focus Mode", "distraction free", "zen", "hide chrome")
        group("What Stays Visible")
        group("What Gets Hidden")
        setting("Top action bar", "What Gets Hidden")
        setting("Left sidebar", "What Gets Hidden")
        setting("Right sidebar", "What Gets Hidden")
        setting("Bottom status bar", "What Gets Hidden")
        group("Auto-Reveal")
        setting("Auto-Reveal on Hover", "Auto-Reveal")
        setting("Reveal Sensitivity", "Auto-Reveal")
        setting("Reveal Delay", "Auto-Reveal")
        group("Keyboard Shortcut")
        setting("Toggle Focus Mode", "Keyboard Shortcut", "shortcut", "hotkey")
    }

private fun themeEntries() =
    section(SettingsSection.THEME) {
        group("App Theme")
    }

private fun windowAppearanceEntries() =
    section(SettingsSection.WINDOW_APPEARANCE) {
        group("Title Bar")
        setting("Show Title Bar", "Title Bar", "window", "chrome", "decoration")
        setting("Platform Default", "Title Bar")
        group("Tab Bar")
        setting("Position", "Tab Bar", "vertical tabs", "left", "top", "sidebar", "tabs")
        setting("Tab Sizing", "Tab Bar", "shrink to fit", "fixed width", "tabs")
        setting("Vertical Bar Width", "Tab Bar", "vertical tabs", "left", "sidebar", "width")
        setting("Expand on Hover", "Tab Bar", "vertical tabs", "rail", "collapse", "drawer")
        setting("Pane Tab Strip", "Tab Bar", "vertical tabs", "split", "favicon", "pane", "tabs")
        setting("Only in Split Windows", "Tab Bar", "vertical tabs", "split", "favicon", "pane", "strip")
        group("Bars")
        setting("Show Top Bar", "Bars", "chrome", "window")
        setting("Show Bottom Bar", "Bars", "chrome", "window", "status")
        setting("Show Left Strip", "Bars", "chrome", "sidebar")
        setting("Show Right Strip", "Bars", "chrome", "sidebar")
        setting("Applies to", "Bars")
        group("Captured Full Screen")
        setting(
            "Allow Captured Full Screen",
            "Captured Full Screen",
            // The words someone actually arrives with. "blue button" and "blue circle" matter most:
            // the feature is discovered by seeing it beside the traffic lights, and its own name is
            // the one thing that person does not know yet.
            "blue button",
            "blue circle",
            "traffic light",
            "fullscreen",
            "kiosk",
            "capture",
            "pointer",
            "mouse",
            "cmd tab",
            "shortcuts",
            "parallels",
        )
        group("Menus")
        setting("Native Context Menus", "Menus", "right click", "macos", "nsmenu")
    }

private fun sidebarEntries() =
    section(SettingsSection.SIDEBAR) {
        group("Plugin Icons")
        setting("Icons per slot", "Plugin Icons", "sidebar", "overflow")
        setting("Fixed icon limit", "Plugin Icons", "sidebar", "overflow")
    }

private fun performanceEntries() =
    section(SettingsSection.PERFORMANCE) {
        group("General")
        setting("Enable Performance Monitoring", "General", "cpu", "memory", "profiler")
        setting("Show Status Bar Indicator", "General", "cpu", "memory", "status bar")
        group("Memory Thresholds")
        setting("Warning Threshold", "Memory Thresholds", "alert", "limit")
        setting("Critical Threshold", "Memory Thresholds", "alert", "limit")
        group("CPU Thresholds")
        setting("Warning Threshold", "CPU Thresholds", "alert", "limit")
        setting("Critical Threshold", "CPU Thresholds", "alert", "limit")
        group("History")
        setting("History Retention", "History")
        group("Reset")
        setting("Reset to Defaults", "Reset")
        group("Resource Mode")
        setting("Mode", "Resource Mode", "lite", "ultra lite", "resource")
        setting("Running as", "Resource Mode")
        setting("Detected memory", "Resource Mode")
        group("Automatic Selection")
        setting("Use Lite below", "Automatic Selection", "resource mode", "memory")
        setting("Use Ultra Lite below", "Automatic Selection", "resource mode", "memory")
        setting("React to low memory while running", "Automatic Selection")
    }

private fun startupEntries() =
    section(SettingsSection.STARTUP) {
        group("Workspace Loading")
        setting("Workspace Load Timeout", "Workspace Loading", "startup", "boot")
        setting("Reset Timeout", "Workspace Loading")
        group("About")
    }

private fun scrollbarEntries() =
    section(SettingsSection.SCROLLBAR) {
        group("Scrollbar Thickness")
        setting("Panel Scrollbar Thickness", "Scrollbar Thickness")
        setting("Bar Scrollbar Thickness", "Scrollbar Thickness")
        setting("Default Panel Thickness", "Scrollbar Thickness")
        setting("Default Bar Thickness", "Scrollbar Thickness")
        group("Visibility")
        setting("Always Show Scrollbars", "Visibility", "auto hide", "overlay")
        group("Animation")
        setting("Fade Delay", "Animation")
        setting("Fade Duration", "Animation")
        setting("Auto-hide Behavior", "Animation")
        setting("Fade Animation", "Animation")
    }

private fun advancedEntries() =
    section(SettingsSection.ADVANCED) {
        group("Process Mode")
        setting("Microkernel Mode", "Process Mode", "out of process", "oop", "isolation", "grpc")
        group("Plugin JVM Resources")
        setting("Max Heap per Plugin", "Plugin JVM Resources", "xmx", "memory", "jvm")
        setting("Initial Heap per Plugin", "Plugin JVM Resources", "xms", "memory", "jvm")
        group("About")
        group("Self-Healing")
        setting("AI-Assisted Repair", "Self-Healing", "self healing", "orchestrator", "llm")
        setting("Provider", "Self-Healing")
        setting("Model", "Self-Healing")
        setting("Endpoint", "Self-Healing")
        setting("Source root", "Self-Healing")
        setting("System prompt", "Self-Healing")
        setting("Custom system prompt in use", "Self-Healing")
    }

/**
 * Shortcuts, whose page is `EditableKeymapSettings` in commonMain.
 *
 * Only the two tab-switching chips are static; the shortcut rows themselves are built at runtime
 * from the keymap plus plugin-contributed defaults, and the page has its own search box for those.
 *
 * Both chips highlight. That took two things: moving the highlight machinery into commonMain, where
 * the settings pages themselves live, and having this page scroll its `LazyColumn` to the
 * tab-switching item - a control that is not composed cannot be brought into view, so a hit did
 * nothing while the page sat scrolled down its shortcut list.
 */
private fun keymapEntries() =
    section(SettingsSection.KEYMAP) {
        sectionLevel("shortcut", "hotkey", "binding", "keyboard", "preset", "conflict", "chord")
        ungrouped(
            "Positional",
            highlightable = true,
            context = "Tab switching",
            "tab switching",
            "ctrl+tab",
            "tab order",
            "next tab",
            "previous tab",
        )
        ungrouped(
            "Most recently used",
            highlightable = true,
            context = "Tab switching",
            "tab switching",
            "ctrl+tab",
            "alt+tab",
            "recent tabs",
        )
    }

/** Every built-in entry, in the order the sections appear in the nav rail. */
internal val builtInEntries: List<SettingsSearchEntry> by lazy {
    browserEntries() +
        browserEngineEntries() +
        defaultAppsEntries() +
        runnerEntries() +
        workspaceEntries() +
        securityEntries() +
        keymapEntries() +
        focusModeEntries() +
        themeEntries() +
        windowAppearanceEntries() +
        sidebarEntries() +
        performanceEntries() +
        startupEntries() +
        scrollbarEntries() +
        advancedEntries() +
        updatesEntries() +
        delegatedEntries() +
        signpostEntries()
}
