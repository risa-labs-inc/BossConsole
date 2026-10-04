package ai.rever.boss.startup

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
