package ai.rever.boss.sharing

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppSharingAutoStartTest {
    private val defaults = AppSharingLocalPreferences()

    @Test
    fun `signed-in hosts start by default once windows appear and duplicate signals do not restart viewers`() {
        val auto = AppSharingAutoStart()
        assertNull(auto.claim(null, listOf("first"), defaults, false))
        auto.accountChanged("alice")
        assertNull(auto.claim("alice", emptyList(), defaults, false))
        assertNull(auto.claim("alice", listOf("first"), defaults, true))
        val plan = assertNotNull(auto.claim("alice", listOf("first", "second"), defaults, false))
        assertEquals(listOf("first", "second"), plan.windows)
        assertTrue(auto.isCurrent(plan))
        assertNull(auto.claim("alice", listOf("first", "second", "third"), defaults, false))
    }

    @Test
    fun `Stop fences delayed starts and new windows until an explicit enable or new sign-in`() {
        val auto = AppSharingAutoStart()
        auto.accountChanged("alice")
        val before = assertNotNull(auto.claim("alice", listOf("first"), defaults, false))
        auto.pause()
        assertFalse(auto.isCurrent(before))
        auto.windowClosed()
        assertNull(auto.claim("alice", listOf("second"), defaults, false))
        auto.resume()
        assertNotNull(auto.claim("alice", listOf("second"), defaults, false))
        auto.pause()
        auto.accountChanged("alice")
        assertNotNull(auto.claim("alice", listOf("second"), defaults, false))
    }

    @Test
    fun `account changes and opt-outs refuse capture while automatic close allows a surviving window`() {
        val auto = AppSharingAutoStart()
        auto.accountChanged("alice")
        assertNull(auto.claim("alice", listOf("first"), defaults.copy(automaticSharingEnabled = false), false))
        assertNull(auto.claim("alice", listOf("first"), defaults.copy(relayEnabled = false), false))
        val before = assertNotNull(auto.claim("alice", listOf("first"), defaults, false))
        auto.accountChanged("bob")
        assertFalse(auto.isCurrent(before))
        assertNull(auto.claim("alice", listOf("first"), defaults, false))
        assertNotNull(auto.claim("bob", listOf("first"), defaults, false))
        auto.windowClosed()
        assertNotNull(auto.claim("bob", listOf("second"), defaults, false))
        auto.accountChanged(null)
        assertNull(auto.claim("bob", listOf("second"), defaults, false))
    }

    @Test
    fun `new and legacy settings default on while a saved disable or unreadable settings stay off`() {
        val directory = Files.createTempDirectory("boss-auto-sharing-").toFile()
        try {
            val file = directory.resolve("app-sharing.json")
            val store = AppSharingPreferenceStore(file)
            assertTrue(store.load().automaticSharingEnabled)
            file.writeText("""{"relayEnabled":true,"deviceId":"${defaults.deviceId}"}""")
            assertTrue(store.load().automaticSharingEnabled)
            store.save(defaults.copy(automaticSharingEnabled = false))
            assertFalse(store.load().automaticSharingEnabled)
            file.writeText("invalid")
            assertFalse(store.load().automaticSharingEnabled)
        } finally {
            directory.deleteRecursively()
        }
    }
}
