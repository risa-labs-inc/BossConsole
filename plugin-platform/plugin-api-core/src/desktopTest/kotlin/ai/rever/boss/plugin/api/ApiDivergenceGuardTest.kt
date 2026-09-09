package ai.rever.boss.plugin.api

import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiDivergenceGuardTest {
    @Test
    fun `default argument bridges remain part of the contract`() {
        val members = ApiSurface.publicMemberSignatures(WithDefaults::class.java)
        assertTrue(members.any { "call\$default(" in it })
    }

    @Test
    fun `private lambda accessors are not plugin contract members`() {
        val dialog = Class.forName("ai.rever.boss.plugin.ui.BossDialogKt")
        val accessors = dialog.methods.filter { it.isSynthetic && "\$lambda\$" in it.name }
        assertTrue(accessors.isNotEmpty(), "Fixture must contain compiler-generated lambda accessors")
        val members = ApiSurface.publicMemberSignatures(dialog)
        assertFalse(members.any { "access\$ScrimmedModalContent\$lambda\$" in it })
    }

    @Test
    fun `changed constructor is detected`() {
        val required = ApiSurface.publicMemberSignatures(WithDefaults::class.java)
        val available = ApiSurface.publicMemberSignatures(WithoutDefaults::class.java)
        assertTrue("init(java.lang.String)" in required - available)
    }

    @Test
    fun `static method cannot be replaced by an instance method`() {
        val required = ApiSurface.publicMemberSignatures(StaticMethod::class.java)
        val available = ApiSurface.publicMemberSignatures(InstanceMethod::class.java)
        assertTrue("static fun call():void" in required - available)
    }

    @Test
    fun `inherited public methods satisfy the host contract`() {
        val required = ApiSurface.publicMemberSignatures(InstanceMethod::class.java)
        val available = ApiSurface.publicMemberSignatures(InheritedMethod::class.java)
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
                output.putNextEntry(JarEntry("META-INF/test-module.kotlin_module"))
                output.closeEntry()
                output.putNextEntry(JarEntry("exampleOther/Unrelated.class"))
                output.closeEntry()
            }
            val names = ApiSurface.comparableClassNames(jar, listOf("example"))
            assertTrue("example.Contract\$Nested" in names)
            assertTrue("example.Contract\$DefaultImpls" in names)
            assertFalse(names.any { "Unrelated" in it })
            assertTrue(ApiSurface.internalSuffixes(jar) == setOf("\$test_module"))
        } finally {
            Files.deleteIfExists(jar.toPath())
        }
    }

    @Test
    fun `missing public static int fields are detected`() {
        val required = ApiSurface.publicMemberSignatures(Int::class.javaObjectType)
        val available = ApiSurface.publicMemberSignatures(WithoutDefaults::class.java)
        assertTrue("static val MAX_VALUE:int" in required - available)
    }

    @Test
    fun `signature linkage failures name the class and unresolved type`() {
        val brokenLoader =
            object : ClassLoader() {
                override fun loadClass(name: String): Class<*> = throw NoClassDefFoundError("example.MissingType")
            }
        val failures = ApiSurface.inspectClass("example.Broken", brokenLoader)
        assertTrue(failures.single().contains("example.Broken"))
        assertTrue(failures.single().contains("example.MissingType"))
    }

    @Test
    fun `parent delegation cannot silently compare the host to itself`() {
        val delegatingLoader = object : ClassLoader(javaClass.classLoader) {}
        assertFailsWith<AssertionError> {
            ApiSurface.inspectClass(WithoutDefaults::class.java.name, delegatingLoader)
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
