package ai.rever.boss.startup

import java.io.ByteArrayInputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformSetupTest {
    @Test
    fun extractPty4jNativesCreatesTargetPlatformDirectory() {
        val tempDir = createTempDirectory("pty4j_test").toFile()
        try {
            PlatformSetup.extractPty4jNatives(
                targetDir = tempDir,
                osName = "linux",
                osArch = "x86_64",
                classLoader = object : ClassLoader(null) {},
            )
            val expectedDir = File(tempDir, "linux/x86-64")
            assertTrue(expectedDir.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun extractPty4jNativesHandlesMacPlatform() {
        val tempDir = createTempDirectory("pty4j_test_mac").toFile()
        try {
            PlatformSetup.extractPty4jNatives(
                targetDir = tempDir,
                osName = "mac os x",
                osArch = "aarch64",
                classLoader = object : ClassLoader(null) {},
            )
            val expectedDir = File(tempDir, "darwin")
            assertTrue(expectedDir.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun extractionUsesFallbackResourceAndPreservesExistingNative() {
        val tempDir = createTempDirectory("pty4j_fixture").toFile()
        val requested = mutableListOf<String>()
        val loader =
            object : ClassLoader(null) {
                override fun getResourceAsStream(name: String): java.io.InputStream? {
                    requested.add(name)
                    return if (name == "native/linux/x86-64/libpty.so") {
                        ByteArrayInputStream("fixture-native".toByteArray())
                    } else {
                        null
                    }
                }
            }
        try {
            PlatformSetup.extractPty4jNatives(tempDir, "linux", "amd64", loader)
            val native = File(tempDir, "linux/x86-64/libpty.so")
            assertEquals("fixture-native", native.readText())
            assertEquals(4, requested.size)
            requested.clear()
            PlatformSetup.extractPty4jNatives(tempDir, "linux", "amd64", loader)
            assertTrue(requested.isEmpty())
            assertEquals("fixture-native", native.readText())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun failedCopyDoesNotPoisonNextLaunch() {
        val tempDir = createTempDirectory("pty4j_failed_copy").toFile()
        var fail = true
        val loader =
            object : ClassLoader(null) {
                override fun getResourceAsStream(name: String): java.io.InputStream? {
                    if (name != "native/linux/x86-64/libpty.so") return null
                    return if (!fail) {
                        ByteArrayInputStream("complete-native".toByteArray())
                    } else {
                        object : java.io.InputStream() {
                            var remaining = 9000

                            override fun read(): Int {
                                kotlin.test.assertFalse(File(tempDir, "linux/x86-64/libpty.so").exists())
                                if (--remaining < 0) throw java.io.IOException("interrupted copy")
                                return 65
                            }
                        }
                    }
                }
            }
        try {
            PlatformSetup.extractPty4jNatives(tempDir, "linux", "amd64", loader)
            val native = File(tempDir, "linux/x86-64/libpty.so")
            kotlin.test.assertFalse(native.exists())
            File(tempDir, "linux/x86-64/libpty.so.part").writeText("orphaned partial")
            fail = false
            PlatformSetup.extractPty4jNatives(tempDir, "linux", "amd64", loader)
            assertEquals("complete-native", native.readText())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
