package ai.rever.boss.plugin.logging

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class StateRootDirectoryTest {
    @Test
    fun `production logging uses the portable state root`() {
        assertEquals(
            File("/home/boss", ".boss"),
            BossLogger.stateRootDirectory("/home/boss", propertyDevMode = null, environmentDevMode = null),
        )
    }

    @Test
    fun `developer logging uses the isolated debug root for every supported truthy value`() {
        for (truthy in listOf("true", "TRUE", "1", "yes", " YES ")) {
            assertEquals(
                File("/home/boss", ".boss_debug"),
                BossLogger.stateRootDirectory("/home/boss", propertyDevMode = truthy, environmentDevMode = null),
            )
            assertEquals(
                File("/home/boss", ".boss_debug"),
                BossLogger.stateRootDirectory("/home/boss", propertyDevMode = null, environmentDevMode = truthy),
            )
        }
    }
}
