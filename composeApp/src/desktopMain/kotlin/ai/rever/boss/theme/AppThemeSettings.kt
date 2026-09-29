package ai.rever.boss.theme

import ai.rever.boss.plugin.ui.BossThemes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persisted host theme preference. `appThemeId` matches a [BossThemes] id
 * ("blueprint", "blueprint-light", "operator", "daylight", "clean", "nvidia").
 * Stored at ~/.boss/app-theme-settings.json.
 *
 * Note that `BossConsoleRust` reads and writes this same file, so its
 * `ThemeId::DEFAULT` must stay in lockstep with [BossThemes.DEFAULT_ID] - a
 * settings file that omits `appThemeId` means "the default", and the two apps
 * would otherwise disagree about which theme that is. That lockstep is on
 * [BossThemes.DEFAULT_ID] alone; the *platform* first-run defaults
 * ([BossThemes.UNIX_DEFAULT_ID] on macOS and Linux,
 * [BossThemes.WINDOWS_DEFAULT_ID] on Windows) apply only when there is no file
 * at all, and see [decodeOrDefaults] for why those two absences are not the
 * same thing. Rust has no `nvidia` variant yet, so a file this host writes
 * naming it resolves there through `ThemeId::from_id(..).unwrap_or(DEFAULT)`.
 */
@Serializable
data class AppThemeSettings(
    val appThemeId: String = BossThemes.DEFAULT_ID,
    val glassCoverage: String = "window",
    val glassStyle: String = "regular",
    val glassTint: Float = DEFAULT_GLASS_TINT,
    val glassOpacity: Float = DEFAULT_GLASS_OPACITY,
) {
    companion object {
        /**
         * One Json for reading and writing, with `encodeDefaults = true`.
         *
         * The previous writer omitted `appThemeId` whenever it equalled the
         * class default, which is survivable only while every platform shares
         * one default. With a Windows default it is not: a Windows user who
         * picks Blueprint writes a file that says nothing, and absence is the
         * one thing that has to keep meaning Blueprint. Writing the id always
         * ends the ambiguity for every file written from here on.
         */
        internal val storageJson =
            Json {
                prettyPrint = true
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        /** First-run settings for a platform, before anything is on disk. */
        fun defaultsFor(isWindows: Boolean): AppThemeSettings = AppThemeSettings(BossThemes.defaultIdFor(isWindows))

        /**
         * Resolve the stored preference, where `content` is the settings file's
         * text or null when there is no file.
         *
         * The distinction is the whole feature. This file is written only by
         * `AppThemeSettingsManager.select()`, so its existence *is* the record
         * that someone chose a theme:
         *
         * - **no file** - nobody has ever chosen, so the platform default applies
         *   and Windows opens light.
         * - **a file** - someone chose. A missing `appThemeId` is then a choice
         *   of the class default, written by the old `encodeDefaults = false`
         *   writer, and resolving it from the platform default would silently
         *   flip a Windows user who deliberately picked Blueprint.
         *
         * A file that will not parse falls back to the platform default: the
         * choice it recorded is unreadable, so there is nothing to preserve.
         */
        fun decodeOrDefaults(
            content: String?,
            isWindows: Boolean,
        ): AppThemeSettings =
            if (content == null) {
                defaultsFor(isWindows)
            } else {
                storageJson.decodeFromString(serializer(), content)
            }
    }
}
