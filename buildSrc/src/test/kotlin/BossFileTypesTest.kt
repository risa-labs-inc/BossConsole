import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the generated macOS file-type declarations.
 *
 * The plist is only assembled during `packageDmg` on a mac runner and only read
 * by Launch Services, which reports nothing when a declaration is malformed - it
 * just silently does not register the type. So the generator is verified here
 * instead, including against the real resource the app ships.
 *
 * The drift test at the bottom is the important one: it is what stops what BOSS
 * offers to open from diverging from what its editor can highlight.
 */
class BossFileTypesTest {
    private val repoRoot = File("..").canonicalFile

    private val resourceFile = File(repoRoot, "composeApp/src/desktopMain/resources/boss-file-types.json")

    private val languageIdsFile =
        File(
            repoRoot,
            "plugin-platform/plugin-language-types/src/commonMain/kotlin/ai/rever/boss/plugin/language/LanguageIds.kt",
        )

    private val table by lazy { BossFileTypes.parse(resourceFile) }

    private fun minimalTable(extensions: String) =
        """
        {
          "exportedTypePrefix": "ai.rever.boss",
          "exportedTypeSuffix": "-source",
          "categories": [
            {"id": "source-code", "displayName": "Source", "description": "d",
             "schemes": [], "extraContentTypes": [], "mimeTypes": [], "systemTypeRank": "Default"}
          ],
          "languageNames": {"kotlin": "Kotlin", "python": "Python"},
          "extensions": [$extensions]
        }
        """.trimIndent()

    // ---- the shipped resource ----

    @Test
    fun `the shipped resource parses`() {
        assertTrue(resourceFile.isFile, "boss-file-types.json not found at ${resourceFile.absolutePath}")
        assertTrue(table.extensions.isNotEmpty())
        assertTrue(table.categories.isNotEmpty())
    }

    @Test
    fun `every category is reachable and every extension names a real one`() {
        val ids = table.categories.map { it.id }.toSet()
        table.extensions.forEach { row ->
            assertTrue(row.category in ids, ".${row.ext} names category '${row.category}'")
        }
        // A category with no extensions and no schemes claims nothing at all and
        // would show in Settings as a row whose Set button does nothing.
        table.categories.forEach { category ->
            val claimsSomething =
                category.schemes.isNotEmpty() ||
                    category.extraContentTypes.isNotEmpty() ||
                    table.extensions.any { it.category == category.id }
            assertTrue(claimsSomething, "category '${category.id}' claims nothing")
        }
    }

    @Test
    fun `the three vetted system types are never claimed`() {
        // These are the measurements that make the table worth having: .ts
        // resolves to a video container, .as to a binary archive and .edn to an
        // Adobe project format. Claiming any would make BOSS the default
        // application for a format it cannot open.
        val rejected = setOf("public.mpeg-2-transport-stream", "com.apple.applesingle-archive", "com.adobe.edn")
        val claimed = table.categories.flatMap { table.systemTypesFor(it.id) }.toSet()
        rejected.forEach { assertFalse(it in claimed, "$it must not be claimed") }

        // And each of them still has to be openable, through an exported type.
        val exportedExtensions = table.exportedTypes().flatMap { it.extensions }.toSet()
        listOf("ts", "as", "edn").forEach {
            assertTrue(it in exportedExtensions, ".$it must be covered by an exported type")
        }
    }

    @Test
    fun `no extension is claimed by two exported types`() {
        val claimed = table.exportedTypes().flatMap { it.extensions }
        assertEquals(claimed.distinct().size, claimed.size)
    }

    @Test
    fun `no system type is repeated inside one document type entry`() {
        table.categories.forEach { category ->
            val types = table.systemTypesFor(category.id)
            assertEquals(types.distinct().size, types.size, "duplicate content type in '${category.id}'")
        }
    }

    // ---- generated XML ----

    /**
     * Wraps a fragment in a plist so a real XML parser can judge it.
     *
     * Concatenated, not `trimIndent()`ed with the fragment interpolated: the
     * fragment's own lines start at column 0, which drags the common indent to
     * zero and leaves the template's leading spaces in front of the `<?xml`
     * prolog - an XML error, and the same trap the build file avoids the same
     * way. Caught by these two tests failing when it was written that way.
     */
    private fun parseFragment(fragment: String) {
        val document =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<plist version=\"1.0\"><dict>\n" +
                fragment + "\n" +
                "</dict></plist>"
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(document.byteInputStream())
    }

    @Test
    fun `generated blocks are well-formed XML`() {
        parseFragment(BossFileTypes.documentTypesXml(table))
        parseFragment(BossFileTypes.exportedTypesXml(table))
        parseFragment(BossFileTypes.urlTypesXml(table, ownScheme = "boss", urlName = "ai.rever.boss"))
    }

    @Test
    fun `every declared type appears in the document types block`() {
        val xml = BossFileTypes.documentTypesXml(table)
        table.allContentTypes().forEach { type ->
            assertTrue("<string>$type</string>" in xml, "$type missing from CFBundleDocumentTypes")
        }
    }

    @Test
    fun `every exported type declares its extensions and conforms to source code`() {
        val xml = BossFileTypes.exportedTypesXml(table)
        table.exportedTypes().forEach { type ->
            assertTrue("<string>${type.identifier}</string>" in xml)
            type.extensions.forEach { ext ->
                assertTrue("<string>$ext</string>" in xml, ".$ext missing from ${type.identifier}")
            }
        }
        assertEquals(
            table.exportedTypes().size,
            Regex("public\\.source-code").findAll(xml).count(),
            "each exported type needs exactly one conformance entry",
        )
    }

    @Test
    fun `document types are editors, never viewers`() {
        // Viewer is what the hand-written entry said, and it told Finder BOSS
        // could look at an .html file but not save it.
        val xml = BossFileTypes.documentTypesXml(table)
        assertFalse("<string>Viewer</string>" in xml)
        assertTrue("<string>Editor</string>" in xml)
    }

    @Test
    fun `exported types are owned and borrowed system types are not`() {
        val xml = BossFileTypes.documentTypesXml(table)
        // public.html belongs to a browser. Claiming Owner for it would be untrue.
        val htmlEntry = xml.substringAfter("<string>Web pages</string>").substringBefore("</dict>")
        assertTrue("<string>Alternate</string>" in htmlEntry, "web-pages should be an Alternate handler")
        assertTrue("<string>Owner</string>" in xml, "BOSS's own types should be Owner")
    }

    @Test
    fun `url types carry the boss scheme first and the table's schemes`() {
        val xml = BossFileTypes.urlTypesXml(table, ownScheme = "boss", urlName = "ai.rever.boss")
        assertTrue(xml.indexOf("<string>boss</string>") < xml.indexOf("<string>http</string>"))
        assertTrue("<string>https</string>" in xml)
    }

    @Test
    fun `an empty exported set produces no block rather than an empty array`() {
        val onlySystemTypes =
            BossFileTypes.parse(minimalTable("""{"ext": "py", "language": "python", "category": "source-code", "systemType": "public.python-script"}"""))
        assertEquals("", BossFileTypes.exportedTypesXml(onlySystemTypes))
    }

    @Test
    fun `special characters in a name cannot break the plist`() {
        val withAmpersand =
            BossFileTypes.parse(
                """
                {
                  "exportedTypePrefix": "ai.rever.boss",
                  "exportedTypeSuffix": "-source",
                  "categories": [
                    {"id": "source-code", "displayName": "A & B", "description": "d",
                     "schemes": [], "extraContentTypes": ["public.text"], "mimeTypes": [], "systemTypeRank": "Default"}
                  ],
                  "languageNames": {"kotlin": "C++ <CLI> & friends"},
                  "extensions": [{"ext": "kt", "language": "kotlin", "category": "source-code"}]
                }
                """.trimIndent(),
            )
        parseFragment(BossFileTypes.documentTypesXml(withAmpersand))
        parseFragment(BossFileTypes.exportedTypesXml(withAmpersand))
    }

    // ---- validation ----

    @Test
    fun `an unknown category is refused`() {
        assertFailsWith<IllegalArgumentException> {
            BossFileTypes.parse(minimalTable("""{"ext": "kt", "language": "kotlin", "category": "nope"}"""))
        }
    }

    @Test
    fun `a language with no display name is refused`() {
        assertFailsWith<IllegalArgumentException> {
            BossFileTypes.parse(minimalTable("""{"ext": "kt", "language": "brainfuck", "category": "source-code"}"""))
        }
    }

    @Test
    fun `a duplicate extension is refused`() {
        assertFailsWith<IllegalArgumentException> {
            BossFileTypes.parse(
                minimalTable(
                    """{"ext": "kt", "language": "kotlin", "category": "source-code"},""" +
                        """{"ext": "kt", "language": "python", "category": "source-code"}""",
                ),
            )
        }
    }

    @Test
    fun `an exported language spanning two categories is refused`() {
        // The generator groups exported types by language across the WHOLE table
        // while the app groups by language WITHIN a category, so a language in two
        // categories would produce one UTI listed under both - and claiming one
        // category would silently make BOSS the default for the other's
        // extensions. Unreachable with today's data; this is what keeps it so.
        val spanning =
            """
            {
              "exportedTypePrefix": "ai.rever.boss",
              "exportedTypeSuffix": "-source",
              "categories": [
                {"id": "source-code", "displayName": "Source", "description": "d",
                 "schemes": [], "extraContentTypes": [], "mimeTypes": [], "systemTypeRank": "Default"},
                {"id": "markdown", "displayName": "Markdown", "description": "d",
                 "schemes": [], "extraContentTypes": [], "mimeTypes": [], "systemTypeRank": "Default"}
              ],
              "languageNames": {"kotlin": "Kotlin"},
              "extensions": [
                {"ext": "kt", "language": "kotlin", "category": "source-code"},
                {"ext": "kts", "language": "kotlin", "category": "markdown"}
              ]
            }
            """.trimIndent()
        val failure = assertFailsWith<IllegalArgumentException> { BossFileTypes.parse(spanning) }
        assertTrue(
            failure.message.orEmpty().contains("span more than one category"),
            "unexpected message: ${failure.message}",
        )
    }

    @Test
    fun `the shipped table keeps every exported language in one category`() {
        val perLanguage =
            table.extensions
                .filter { it.systemType == null }
                .groupBy({ it.language }, { it.category })
                .mapValues { (_, categories) -> categories.distinct() }
        assertTrue(
            perLanguage.all { (_, categories) -> categories.size == 1 },
            "exported languages span categories: ${perLanguage.filterValues { it.size > 1 }}",
        )
    }

    @Test
    fun `claiming and rejecting the same extension's type is refused`() {
        assertFailsWith<IllegalArgumentException> {
            BossFileTypes.parse(
                minimalTable(
                    """{"ext": "kt", "language": "kotlin", "category": "source-code",""" +
                        """ "systemType": "public.x", "rejectedSystemType": "public.y"}""",
                ),
            )
        }
    }

    // ---- the drift gate ----

    @Test
    fun `the table covers exactly the extensions LanguageIds knows`() {
        val languageIds = languageIdExtensions()
        assertEquals(
            languageIds.keys.sorted(),
            table.extensions.map { it.ext }.sorted(),
            "boss-file-types.json and LanguageIds.EXTENSIONS disagree about which extensions exist. " +
                "Adding a lexer means adding a row here, or BOSS will not offer to open the files it can now read; " +
                "removing one and leaving the row means BOSS claims a file type it cannot highlight.",
        )
    }

    @Test
    fun `the table agrees with LanguageIds about which language each extension is`() {
        val languageIds = languageIdExtensions()
        val disagreements =
            table.extensions.mapNotNull { row ->
                val expected = languageIds[row.ext]
                if (expected != null && expected != row.language) {
                    ".${row.ext}: LanguageIds says '$expected', boss-file-types.json says '${row.language}'"
                } else {
                    null
                }
            }
        assertTrue(disagreements.isEmpty(), disagreements.joinToString("\n"))
    }

    /**
     * `LanguageIds.EXTENSIONS`, read out of the source.
     *
     * A regex over Kotlin source is a poor way to read a map and it is the only
     * way available here: `buildSrc` compiles before `plugin-language-types` and cannot
     * depend on it. The alternative was no gate at all, which is the state that
     * let three copies of this table drift apart in the first place. The pattern
     * is anchored to the `EXTENSIONS` block so `EXACT_NAMES` and `PREFIXED_NAMES`
     * above it cannot leak in.
     */
    private fun languageIdExtensions(): Map<String, String> {
        assertTrue(languageIdsFile.isFile, "LanguageIds.kt not found at ${languageIdsFile.absolutePath}")
        val source = languageIdsFile.readText()
        val block =
            source
                .substringAfter("private val EXTENSIONS =", "")
                .also { assertTrue(it.isNotEmpty(), "EXTENSIONS block not found; did LanguageIds get restructured?") }
        val pairs = Regex("\"([a-z0-9]+)\" to \"([a-z]+)\"").findAll(block)
        val map = pairs.associate { it.groupValues[1] to it.groupValues[2] }
        assertTrue(map.size > 50, "only ${map.size} extensions parsed out of LanguageIds; the regex has gone stale")
        return map
    }
}
