package ai.rever.boss.files

import com.sun.jna.Memory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class WindowsSidBoundsTest {
    @Test
    fun `SID must fit completely before native conversion`() {
        Memory(32).use { descriptor ->
            descriptor.clear()
            descriptor.setByte(20, 1)
            descriptor.setByte(21, 1)
            assertNotNull(WindowsPermissions.boundedSid(descriptor, 20))
            descriptor.setByte(21, 2)
            assertFailsWith<IllegalArgumentException> { WindowsPermissions.boundedSid(descriptor, 20) }
            assertFailsWith<IllegalArgumentException> { WindowsPermissions.boundedSid(descriptor, 32) }
            descriptor.setByte(21, 16)
            assertFailsWith<IllegalArgumentException> { WindowsPermissions.boundedSid(descriptor, 20) }
        }
    }
}
