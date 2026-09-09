package ai.rever.boss.plugin.api

import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiDivergenceGuardTest {
    private val guard = ApiPackageDivergenceTest()

    @Test
    fun `default argument bridges remain part of the contract`() {
        val members = guard.publicMemberSignatures(WithDefaults::class.java)
        assertTrue(members.any { "call\$default(" in it })
    }

    @Test
    fun `changed constructor is detected`() {
        val required = guard.publicMemberSignatures(WithDefaults::class.java)
        val available = guard.publicMemberSignatures(WithoutDefaults::class.java)
        assertTrue("init(java.lang.String)" in required - available)
    }

    @Test
    fun `static method cannot be replaced by an instance method`() {
        val required = guard.publicMemberSignatures(StaticMethod::class.java)
        val available = guard.publicMemberSignatures(InstanceMethod::class.java)
        assertTrue("static fun call():void" in required - available)
    }

    @Test
    fun `inherited public methods satisfy the host contract`() {
        val required = guard.publicMemberSignatures(InstanceMethod::class.java)
        val available = guard.publicMemberSignatures(InheritedMethod::class.java)
        assertTrue((required - available).isEmpty())
    }

    @Test
    fun `nested types and DefaultImpls are not discarded by jar enumeration`() {
        val jar = Files.createTempFile("api-guard", ".jar").toFile()
        try {
            JarOutputStream(jar.outputStream()).use { output ->
                for (name in listOf("Contract\$Nested", "Contract\$DefaultImpls")) {
                    output.putNextEntry(JarEntry("example/$name.class"))
                    output.closeEntry()
                }
                output.putNextEntry(JarEntry("exampleOther/Unrelated.class"))
                output.closeEntry()
            }
            val names = guard.comparableClassNames(jar, listOf("example"))
            assertTrue("example.Contract\$Nested" in names)
            assertTrue("example.Contract\$DefaultImpls" in names)
            assertFalse(names.any { "Unrelated" in it })
        } finally {
            Files.deleteIfExists(jar.toPath())
        }
    }

    class WithDefaults(
        val value: String,
    ) {
        fun call(value: String = "default") = value
    }

    class WithoutDefaults {
        fun call(value: String) = value
    }

    // A class fixture is intentional: changing a static entry point to an instance method breaks callers.
    @Suppress("UtilityClassWithPublicConstructor")
    class StaticMethod {
        companion object {
            @JvmStatic
            fun call() = Unit
        }
    }

    open class InstanceMethod {
        fun call() = Unit
    }

    class InheritedMethod : InstanceMethod()
}
