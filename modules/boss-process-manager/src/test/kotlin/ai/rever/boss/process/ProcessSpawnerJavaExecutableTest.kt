package ai.rever.boss.process

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProcessSpawnerJavaExecutableTest {
    @Test
    fun `findJavaExecutable returns a valid executable path`() {
        val executable = ProcessSpawner.findJavaExecutable()
        assertNotNull(executable)
        assertTrue(executable.isNotBlank(), "Java executable path must not be blank")

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        if (isWindows) {
            assertTrue(
                executable.endsWith(".exe") || executable == "java" || executable == "java.exe",
                "Executable on Windows should end with .exe or be standard binary name: $executable",
            )
        }
    }
}
