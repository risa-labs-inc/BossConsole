package ai.rever.boss

import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {
    @Test
    fun `placeholder server is loopback only`() {
        assertEquals("127.0.0.1", SERVER_HOST)
    }
}
