package ai.rever.boss.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CLIVersionParsingTest {
    @Test
    fun `stable version is read from each generated script style`() {
        listOf(
            "# Version: 9.5.21",
            "REM Version: 9.5.21",
            "    Version: 9.5.21",
        ).forEach { header ->
            assertEquals("9.5.21", installedCLIVersionFrom(sequenceOf(header)))
        }
    }

    @Test
    fun `prerelease suffix and build metadata are preserved`() {
        assertEquals(
            "9.5.21-alpha.1+local.4",
            installedCLIVersionFrom(sequenceOf("# Version: 9.5.21-alpha.1+local.4")),
        )
    }

    @Test
    fun `only the generated header budget is searched`() {
        val lines = List(20) { "header $it" } + "# Version: 9.5.21"
        assertNull(installedCLIVersionFrom(lines.asSequence()))
    }
}
