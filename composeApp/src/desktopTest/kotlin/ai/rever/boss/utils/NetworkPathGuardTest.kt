package ai.rever.boss.utils

import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A `boss://file?path=\\host\share\x` link must not make BOSS touch `host`.
 *
 * Windows opens an SMB connection to a UNC path's host on the first filesystem call against it,
 * and sends the signed-in user's NTLM response with it. The scheme is registered with the OS, so a
 * web page can put any path in the link. Everything here is decided from the text of the path,
 * which is the point: the check itself must not be what touches the network.
 */
class NetworkPathGuardTest {
    private val unc = """\\attacker.example\share\notes.md"""

    private fun link(
        host: String,
        path: String,
    ) = "boss://$host?path=" + URLEncoder.encode(path, "UTF-8")

    private fun refusal(
        uri: String,
        origin: DeepLinkOrigin = DeepLinkOrigin.EXTERNAL,
        trusted: Set<String> = emptySet(),
    ) = NetworkPathGuard.refusalFor(uri, origin, windows = true, trustedHosts = trusted)

    @Test
    fun `every spelling of a UNC path is a network path`() {
        val spellings =
            listOf(
                unc,
                "//attacker.example/share/notes.md",
                """\/attacker.example/share""",
                """/\attacker.example\share""",
                """\\attacker.example""",
                """\\?\UNC\attacker.example\share\x""",
                """\\.\UNC\attacker.example\share\x""",
                """\??\UNC\attacker.example\share\x""",
                """//?/UNC/attacker.example/share/x""",
                """\\?\unc\attacker.example\share\x""",
                """\\?\GLOBALROOT\Device\Mup\attacker.example\share\x""",
                """\\.\GLOBALROOT\Device\Mup\attacker.example\share\x""",
                """\\.\pipe\anything""",
                """\\attacker.example@SSL\share\x""",
                """\\attacker.example@8080\share\x""",
                """   \\attacker.example\share""",
                "\t//attacker.example/share",
                """\\192.0.2.7\c$\windows""",
                """\\127.0.0.1.attacker.example\share""",
            )
        for (path in spellings) {
            assertTrue(NetworkPathGuard.isNetworkPath(path, windows = true), "should be a network path: $path")
        }
    }

    @Test
    fun `an ordinary local path is not a network path`() {
        val local =
            listOf(
                """C:\Users\me\notes.md""",
                "C:/Users/me/notes.md",
                """\Users\me\notes.md""",
                "/Users/me/notes.md",
                """relative\notes.md""",
                """\\?\C:\Users\me\notes.md""",
                """\\.\C:\Users\me\notes.md""",
                """\\?\Volume{01234567-89ab-cdef-0123-456789abcdef}\notes.md""",
                "",
                "notes.md",
                "C:",
            )
        for (path in local) {
            assertFalse(NetworkPathGuard.isNetworkPath(path, windows = true), "should be local: $path")
        }
    }

    @Test
    fun `off Windows a double slash is just a path`() {
        assertFalse(NetworkPathGuard.isNetworkPath("//tmp/notes.md", windows = false))
        assertFalse(NetworkPathGuard.isNetworkPath(unc, windows = false))
        val outside = DeepLinkOrigin.EXTERNAL
        assertNull(NetworkPathGuard.refusalFor(link("file", unc), outside, windows = false, trustedHosts = emptySet()))
    }

    @Test
    fun `the host is read out of every spelling`() {
        assertEquals("attacker.example", NetworkPathGuard.hostOf(unc, windows = true))
        assertEquals("attacker.example", NetworkPathGuard.hostOf("//Attacker.Example/share", windows = true))
        assertEquals("attacker.example", NetworkPathGuard.hostOf("""\\?\UNC\attacker.example\share""", windows = true))
        val webDav = NetworkPathGuard.hostOf("""\\attacker.example@SSL\share""", windows = true)
        assertEquals("attacker.example@ssl", webDav)
        assertNull(NetworkPathGuard.hostOf("""C:\x""", windows = true))
    }

    @Test
    fun `a link from outside that names a network path is refused for file folder and workspace`() {
        for (host in listOf("file", "folder", "workspace")) {
            assertNotNull(refusal(link(host, unc)), "boss://$host must refuse a UNC path")
            assertNotNull(refusal(link(host, "//attacker.example/share/x")), "boss://$host must refuse //host/share")
        }
    }

    @Test
    fun `the refusal does not depend on how the path was encoded`() {
        assertNotNull(refusal("boss://file?path=%5C%5Cattacker.example%5Cshare%5Cx.md"))
        assertNotNull(refusal("boss://file?path=%2F%2Fattacker.example%2Fshare"))
        assertNotNull(refusal("boss://file?path=\\\\attacker.example\\share"))
        assertNotNull(refusal("BOSS://FILE?path=%5C%5Cattacker.example%5Cshare"))
    }

    @Test
    fun `a second path parameter cannot smuggle the network path past a check of the first`() {
        // The handler keeps the LAST value of a repeated key. Checking only the first would
        // approve this link and then open the other one.
        val smuggled = "boss://file?path=%2Ftmp%2Fok.md&path=" + URLEncoder.encode(unc, "UTF-8")
        assertNotNull(refusal(smuggled))
        val reversed = "boss://file?path=" + URLEncoder.encode(unc, "UTF-8") + "&path=%2Ftmp%2Fok.md"
        assertNotNull(refusal(reversed))
    }

    @Test
    fun `a local path from outside is untouched`() {
        assertNull(refusal(link("file", """C:\Users\me\notes.md""")))
        assertNull(refusal(link("folder", "C:/Users/me/project")))
        assertNull(refusal(link("workspace", """C:\Users\me\space.json""")))
        assertNull(refusal(link("file", """\\?\C:\Users\me\notes.md""")))
    }

    @Test
    fun `other hosts are not this guard's business`() {
        assertNull(refusal("boss://terminal?command=ls"))
        assertNull(refusal("boss://plugin?id=bookmarks"))
        assertNull(refusal("boss://url?url=https%3A%2F%2Fexample.com"))
        assertNull(refusal("boss://file"))
        assertNull(refusal("boss://file?other=" + URLEncoder.encode(unc, "UTF-8")))
    }

    @Test
    fun `the operator's own CLI and a file they opened through the OS are their own choice`() {
        assertNull(refusal(link("file", unc), DeepLinkOrigin.OPERATOR_CLI))
        assertNull(refusal(link("file", unc), DeepLinkOrigin.OS_FILE_OPEN))
        assertNull(refusal(link("folder", unc), DeepLinkOrigin.OS_FILE_OPEN))
    }

    @Test
    fun `a host the operator trusts, and this machine, are allowed`() {
        assertNull(refusal(link("file", unc), trusted = setOf("attacker.example")))
        assertNull(refusal(link("file", """\\localhost\c$\notes.md""")))
        assertNull(refusal(link("file", """\\127.0.0.1\share\notes.md""")))
        assertNotNull(refusal(link("file", unc), trusted = setOf("someone-else.example")))
    }

    @Test
    fun `trusting a host does not trust a name that merely contains it or a WebDAV variant`() {
        val trusted = setOf("files.corp.example")
        assertNull(refusal(link("file", """\\files.corp.example\share\x"""), trusted = trusted))
        assertNull(refusal(link("file", """\\FILES.corp.example\share\x"""), trusted = trusted))
        assertNotNull(refusal(link("file", """\\files.corp.example.attacker.example\share\x"""), trusted = trusted))
        assertNotNull(refusal(link("file", """\\attacker-files.corp.example\share\x"""), trusted = trusted))
        assertNotNull(refusal(link("file", """\\files.corp.example@8080\share\x"""), trusted = trusted))
        assertNotNull(refusal(link("file", """\\localhost@8080\share\x""")))
    }

    @Test
    fun `the real handler refuses the link before it can touch the share`() {
        if (!System.getProperty("os.name").lowercase().contains("windows")) return
        // 192.0.2.0/24 is reserved for documentation (RFC 5737): never routable, so even a build
        // without the guard could only time out against it rather than reach anybody.
        val target = link("file", """\\192.0.2.1\share\notes.md""")
        StatusMessageManager.clearMessage()

        assertNull(DeepLinkHandler.processDeepLink(target, DeepLinkOrigin.EXTERNAL))

        val shown =
            runBlocking {
                withTimeoutOrNull(SHOWN_WITHIN_MS) { StatusMessageManager.currentMessage.filterNotNull().first() }
            }
        assertNotNull(shown, "the operator is told the link was blocked")
        assertTrue(shown.contains("Blocked"), shown)
        assertTrue(shown.contains("192.0.2.1"), shown)
    }

    @Test
    fun `an unstated origin is treated as outside`() {
        assertEquals(DeepLinkOrigin.EXTERNAL, DeepLinkOrigin.fromWireLabel(null))
        assertEquals(DeepLinkOrigin.EXTERNAL, DeepLinkOrigin.fromWireLabel("nonsense"))
        assertEquals(DeepLinkOrigin.OS_FILE_OPEN, DeepLinkOrigin.fromWireLabel("os_file_open"))
    }

    @Test
    fun `opening a file through the OS is not the operator running a command`() {
        assertFalse(DeepLinkOrigin.OS_FILE_OPEN.isOperatorInitiated)
        assertTrue(DeepLinkOrigin.OPERATOR_CLI.isOperatorInitiated)
    }

    private companion object {
        const val SHOWN_WITHIN_MS = 3_000L
    }
}
