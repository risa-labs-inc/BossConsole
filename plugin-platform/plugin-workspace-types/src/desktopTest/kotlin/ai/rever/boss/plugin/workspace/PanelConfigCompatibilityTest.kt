package ai.rever.boss.plugin.workspace

import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.json.Json
import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class PanelConfigCompatibilityTest {
    @Test
    fun `old copy descriptor preserves host pinned count`() {
        val panel = PanelConfig("old", emptyList(), pinnedCount = 2)
        val copy = PanelConfig::class.java.getMethod("copy", String::class.java, List::class.java)
        val result = copy.invoke(panel, "new", emptyList<TabConfig>()) as PanelConfig
        assertEquals("new", result.id)
        assertEquals(2, result.pinnedCount)
        assertEquals(1, panel.copy(pinnedCount = 1).pinnedCount)
        assertEquals(2, panel.copy(id = "source-copy").pinnedCount)
    }

    @Test
    fun `old default copy bridge retains unspecified properties`() {
        val panel = PanelConfig("old", emptyList(), pinnedCount = 2)
        val bridge =
            PanelConfig::class.java.getMethod(
                "copy\$default",
                PanelConfig::class.java,
                String::class.java,
                List::class.java,
                Int::class.javaPrimitiveType,
                Any::class.java,
            )
        val result = bridge.invoke(null, panel, null, null, 3, null) as PanelConfig
        assertEquals(panel, result)
    }

    @Test
    fun `old serialization constructor defaults pinned count and enforces required fields`() {
        val constructor =
            PanelConfig::class.java.constructors.single {
                it.parameterCount == 4 && it.parameterTypes.first() == Int::class.javaPrimitiveType
            }
        val panel = constructor.newInstance(3, "restored", emptyList<TabConfig>(), null) as PanelConfig
        assertEquals(PanelConfig("restored", emptyList(), 0), panel)
        val failure =
            assertFailsWith<InvocationTargetException> {
                constructor.newInstance(2, null, emptyList<TabConfig>(), null)
            }
        assertIs<MissingFieldException>(failure.cause)
    }

    @Test
    fun `host serialization still round trips pinned count and reads old json`() {
        val panel = PanelConfig("panel", emptyList(), 2)
        assertEquals(panel, Json.decodeFromString<PanelConfig>(Json.encodeToString(panel)))
        assertEquals(0, Json.decodeFromString<PanelConfig>("""{"id":"old","tabs":[]}""").pinnedCount)
    }
}
