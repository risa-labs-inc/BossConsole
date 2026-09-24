package ai.rever.boss.utils

import ai.rever.boss.utils.OsOpenArguments.OpenTargetKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the OS handed BOSS decides how far its path is trusted: a `boss://` argument is text any web
 * page can write, a bare path or `file://` URL is what a file association produces.
 */
class OsOpenRequestOriginTest {
    private val fileOnDisk: (String) -> OpenTargetKind = { OpenTargetKind.FILE }
    private val unc = """\\attacker.example\share\notes.md"""

    @Test
    fun `a boss link from the OS is external`() {
        val requests = OsOpenArguments.requestsFrom(arrayOf("boss://file?path=%5C%5Chost%5Cs%5Cx"), fileOnDisk)
        assertEquals(listOf(DeepLinkOrigin.EXTERNAL), requests.map { it.origin })
    }

    @Test
    fun `an http link from the OS is external`() {
        val requests = OsOpenArguments.requestsFrom(arrayOf("https://example.com/"), fileOnDisk)
        assertEquals(listOf(DeepLinkOrigin.EXTERNAL), requests.map { it.origin })
    }

    @Test
    fun `a bare path from the OS is a file the operator opened`() {
        val requests = OsOpenArguments.requestsFrom(arrayOf(unc), fileOnDisk)
        assertEquals(listOf(DeepLinkOrigin.OS_FILE_OPEN), requests.map { it.origin })
    }

    @Test
    fun `a file URL from the OS is a file the operator opened`() {
        val requests = OsOpenArguments.requestsFrom(arrayOf("file:///tmp/notes.md"), fileOnDisk)
        assertEquals(listOf(DeepLinkOrigin.OS_FILE_OPEN), requests.map { it.origin })
    }

    @Test
    fun `a mixed launch keeps each argument's own origin`() {
        val requests =
            OsOpenArguments.requestsFrom(
                arrayOf("boss://file?path=%2Ftmp%2Fa.md", "/tmp/b.md"),
                fileOnDisk,
            )
        assertEquals(listOf(DeepLinkOrigin.EXTERNAL, DeepLinkOrigin.OS_FILE_OPEN), requests.map { it.origin })
    }

    @Test
    fun `the link-only view is unchanged`() {
        val args = arrayOf("boss://file?path=%2Ftmp%2Fa.md", "/tmp/b.md")
        assertEquals(
            OsOpenArguments.requestsFrom(args, fileOnDisk).map { it.link },
            OsOpenArguments.deepLinksFrom(args, fileOnDisk),
        )
    }
}
