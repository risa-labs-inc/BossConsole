package ai.rever.boss.plugin.icons

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pins the behaviour of routing unmatched extensions straight to
 * [LanguageIcons.forExtensionOrNull], which replaced a `when` in [FileIcons] whose
 * 52 branches were byte-identical calls to `LanguageIcons.forExtension`.
 *
 * That `when` was a second copy of the extension list living in the adjacent file,
 * one of the duplicated language tables BossConsole#75 is about. It had already
 * drifted: eleven extensions LanguageIcons has icons for were missing from it and
 * fell through to the generic file icon, and nothing reported that.
 *
 * The two properties worth holding onto are that no extension LOST an icon (the
 * old list was a strict subset) and that the explicit branches still win, because
 * one of them (xml) deliberately disagrees with the language mapping and the rest
 * carry icons the language table does not have.
 */
class FileIconsLanguageDelegationTest {
    private val genericFile = FileIcons.forFile("a.does-not-exist-anywhere")

    @Test
    fun `an unknown extension still falls back to the generic file icon`() {
        // The delegation must not turn "no icon for this" into something arbitrary.
        assertEquals(FileIcons.forFile("notes.zzzzz").icon, genericFile.icon)
        assertEquals(FileIcons.forFile("notes.zzzzz").color, genericFile.color)
    }

    @Test
    fun `extensions the old gate listed still resolve to their language icon`() {
        // A spread across the branches that were removed: JVM, scripting, systems,
        // web, and the ones whose extension differs from the language name.
        for (name in listOf("A.kt", "a.py", "a.ts", "a.rs", "a.go", "a.rb", "a.swift", "a.dart", "a.vue", "a.hs")) {
            val icon = FileIcons.forFile(name)
            assertNotEquals(genericFile.icon, icon.icon, "$name lost its icon")
        }
    }

    @Test
    fun `the eleven extensions the old gate had drifted past now resolve too`() {
        // These are the drift. LanguageIcons has had icons for them all along; the
        // hand-maintained gate in FileIcons never listed them, so they rendered as
        // a blank document while the icon existed one file away.
        val drifted =
            listOf(
                "a.astro",
                "a.eslintignore",
                "a.eslintrc",
                "a.gql",
                "a.graphql",
                "a.pom",
                "a.prettierignore",
                "a.prettierrc",
                "a.prisma",
                "a.proto",
                "a.styl",
            )
        for (name in drifted) {
            assertNotEquals(genericFile.icon, FileIcons.forFile(name).icon, "$name still falls through")
        }
    }

    @Test
    fun `explicit branches still override the language mapping`() {
        // The reason the remaining `when` has to stay ahead of the delegation. xml is the one
        // branch that deliberately disagrees with the language mapping (a .xml is a config
        // document, not a Maven project); bat/cmd and plist carry icons the language table does
        // not have, and they would fall to the generic file icon if their branches were dropped.
        assertEquals(LanguageIcons.powershell, FileIcons.forFile("run.bat").icon)
        assertEquals(LanguageIcons.powershell, FileIcons.forFile("run.cmd").icon)
        assertEquals(LanguageIcons.ios, FileIcons.forFile("Info.plist").icon)

        // xml, properties, ini and cfg are config documents here, not markup/source.
        // Deliberately not pom.xml: forSpecialFileName claims that one by name
        // before any of this runs, which is the ordering the last case pins.
        val xml = FileIcons.forFile("data.xml")
        assertEquals(xml.icon, FileIcons.forFile("app.properties").icon)
        assertEquals(xml.icon, FileIcons.forFile("settings.ini").icon)
        // And the disagreement is real: the same contents under a .pom extension get the
        // Maven icon through the language table, so the xml branch is load-bearing.
        assertNotEquals(xml.icon, FileIcons.forFile("data.pom").icon)
    }

    @Test
    fun `gradle files keep their icon through the language table`() {
        // The old gate's explicit "gradle" branch was value-identical to LanguageIcons' own
        // mapping, so it was the duplication this PR removes rather than an override.
        // build.gradle is still claimed by forSpecialFileName by name; libs.gradle reaches the
        // icon through the forExtensionOrNull delegation, which is what pins the hand-off.
        assertEquals(LanguageIcons.gradle, FileIcons.forFile("build.gradle").icon)
        assertEquals(LanguageIcons.gradle, FileIcons.forFile("libs.gradle").icon)
    }

    @Test
    fun `a jar is an archive rather than Java source`() {
        // Same override reasoning, and the one most likely to be "simplified" away
        // because the colour is Java's while the icon is not.
        val jar = FileIcons.forFile("app.jar")
        assertEquals(FileIcons.forFile("app.zip").icon, jar.icon)
        assertEquals(LanguageIcons.Colors.java, jar.color)
    }

    @Test
    fun `special file names still win over both`() {
        // forSpecialFileName runs before the extension logic and must stay there.
        assertEquals(LanguageIcons.docker, FileIcons.forFile("Dockerfile").icon)
        assertEquals(LanguageIcons.npm, FileIcons.forFile("package.json").icon)
    }

    @Test
    fun `forExtension stays total for callers that want a fallback`() {
        // The nullable variant is additive; the original contract is unchanged.
        assertEquals(LanguageIcons.unknown, LanguageIcons.forExtension("zzzzz").first)
        assertEquals(null, LanguageIcons.forExtensionOrNull("zzzzz"))
        assertEquals(LanguageIcons.forExtensionOrNull("kt"), LanguageIcons.forExtension("kt"))
    }
}
