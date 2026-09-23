package ai.rever.boss.utils

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URL
import kotlin.test.assertEquals

class CodeSourceFileTest {
    @Test
    fun `ordinary file URLs keep spaces in the resolved path`() {
        val expected = File(System.getProperty("java.io.tmpdir"), "BOSS install/app/BOSS.jar").absoluteFile

        assertEquals(expected, codeSourceFile(expected.toURI().toURL()).absoluteFile)
    }

    @Test
    fun `Windows network-share file URLs retain the server`() {
        assumeTrue(File.separatorChar == '\\', "UNC paths require the Windows filesystem provider")

        val file = codeSourceFile(URL("file://nas01/software/BOSS/app/BOSS.jar"))

        assertEquals("\\\\nas01\\software\\BOSS\\app\\BOSS.jar", file.absolutePath)
    }
}
