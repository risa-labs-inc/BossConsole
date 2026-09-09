package ai.rever.boss.components.plugin.language

import ai.rever.boss.plugin.language.LanguageIds

/**
 * The host's single answer to "what language is this file?".
 *
 * There were three copies of this map in the host with different membership, and
 * they disagreed: one knew 27 extensions, one knew 12 and never lowercased, and a
 * third carried `pyw`/`mjs` the others lacked. None could identify `Dockerfile` or
 * `Makefile`, because every one of them keyed on an extension and those files have
 * none.
 *
 * Both host-side consumers of the plugin-facing language id route here — this
 * provider and the built-in editor tab — so `editor_detect_language` cannot report
 * one language while that tab highlights another.
 *
 * The table itself now lives in `plugin-platform/plugin-language-types`'s
 * [LanguageIds] (BossConsole#75): `modules/boss-app-editor`'s `EditorServiceImpl`
 * could not depend on anything in `composeApp`, so it kept its own smaller,
 * independently hand-maintained copy that disagreed on `.sh`/`.bash`/`.zsh` (`bash`
 * here, `shell` there). Both now read the same table. This object stays as a thin
 * alias so every existing call site (`EditorContentProviderImpl`,
 * `FileTypeCategoriesTest`) keeps working unchanged.
 *
 * Two further copies are known and are **not** consolidated here - see [LanguageIds]'s
 * own doc for why: the editor-tab plugin's `LanguageDetection` and BossEditor's lexer
 * registry both live in separate repositories this build cannot reach, and
 * `FileIcons.forSpecialFileName`/`forFile` keys icon selection off the raw filename
 * for a finer granularity than a language id carries, so there is nothing there to
 * route through this table without a separate redesign.
 *
 * Measured against the editor-tab plugin's copy: the 15 ids added here are identical
 * (`properties` not `ini`, `batch` not `bat`); `r` has no lexer on either side, so
 * `.r` files are named but not highlighted. Nothing enforces this at build time.
 */
object EditorLanguages {
    /**
     * Language id for [filePath], or `"text"` when nothing matches.
     *
     * See [LanguageIds.detect] for the matching rules.
     */
    fun detect(filePath: String): String = LanguageIds.detect(filePath)

    /**
     * The extension-to-language table, for whoever has to stay in step with it.
     *
     * Exposed for exactly one reason: `boss-file-types.json` declares which file
     * types BOSS asks the OS to make it the default for, and it must claim
     * neither more nor less than what this table can highlight. Claiming more
     * means BOSS agreeing to open a file it renders as plain text; claiming less
     * means a language it *can* highlight that the OS never sends it.
     * `FileTypeCategoriesTest` compares the two, which is the enforcement this
     * class's own KDoc says nothing has.
     */
    fun extensions(): Map<String, String> = LanguageIds.extensions()
}
