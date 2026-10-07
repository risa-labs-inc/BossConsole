package ai.rever.boss.startup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StartupSourceTest {
    @Test
    fun `comment and string mentions do not count as executed startup calls`() {
        val call = "DefaultWindowIcon.install()"
        val source =
            "/* $call /* nested */ still comment */\n" +
                "$call // $call\n" +
                "val text = \"$call // literal\"\n" +
                "val raw = \"\"\"$call /* raw */\"\"\"\n"
        val code = startupCodeOnly(source)
        assertEquals(source.length, code.length)
        assertEquals(source.count { it == '\n' }, code.count { it == '\n' })
        val index = code.indexOf(call)
        assertTrue(index >= 0)
        assertEquals(-1, code.indexOf(call, index + 1))
    }

    @Test
    fun `nested template quotes and imports cannot create extra startup call matches`() {
        val call = "DefaultWindowIcon.install()"
        val source =
            "import ai.rever.boss.plugin.browser.MacOSScrollGesturePhases\n" +
                "val text = \"\${describe(\"$call\")} suffix\"\n" +
                "val raw = \"\"\"\${describe(\"$call\")} raw\"\"\"\n" +
                "$call\nMacOSScrollGesturePhases.ensureStarted()\n"
        val code = startupCodeOnly(source)
        assertEquals(source.length, code.length)
        assertEquals(source.indexOf("$call\nMacOS"), code.indexOf(call))
        assertEquals(-1, code.indexOf(call, code.indexOf(call) + 1))
        assertEquals(1, Regex("MacOSScrollGesturePhases").findAll(code).count())
    }

    @Test
    fun `executed calls inside templates remain visible including nested strings and braces`() {
        val call = "ChromiumBootstrap.preflight()"
        val source = "val text = \"\${run { describe(\"\${$call}\") }} suffix\"\nDefaultWindowIcon.install()"
        val code = startupCodeOnly(source)
        assertEquals(source.length, code.length)
        assertEquals(source.indexOf(call), code.indexOf(call))
        assertEquals(source.indexOf("DefaultWindowIcon.install()"), code.indexOf("DefaultWindowIcon.install()"))
    }

    @Test
    fun `malformed source reports its lexical error instead of silently hiding remaining calls`() {
        for (source in listOf("/* open", "\"open", "\"\${call(")) {
            assertFailsWith<IllegalStateException> { startupCodeOnly(source) }
        }
    }
}
