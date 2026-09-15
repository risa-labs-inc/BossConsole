package ai.rever.boss.files

import com.sun.jna.Platform
import org.junit.Assume.assumeTrue
import java.nio.file.DirectoryNotEmptyException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PosixErrorMappingTest {
    @Test
    fun `only the current platform ENOTEMPTY maps to a nonempty directory`() {
        assumeTrue(Platform.isMac() || Platform.isLinux())
        val ownCode = if (Platform.isMac()) 66 else 39
        val otherCode = if (Platform.isMac()) 39 else 66
        assertTrue(PosixApi.error("Delete", ownCode) is DirectoryNotEmptyException)
        assertFalse(PosixApi.error("Delete", otherCode) is DirectoryNotEmptyException)
    }
}
