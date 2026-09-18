package ai.rever.boss.plugin.loader

import org.junit.jupiter.api.io.TempDir
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [BinaryCompatibilityValidator]'s helpers.
 *
 * Two behaviours have been touched recently and warrant pinning:
 *
 *  - `isSoftFailReference` — soft-fails references into
 *    `ai.rever.boss.plugin.runtime.*` because those classes live only on
 *    OOP plugin child-JVM classpaths.
 *  - `hasMethod` — when the owner is an interface, falls back to
 *    `java.lang.Object`'s declared methods. Without this the JNA
 *    `CallbackProxy -> java.util.List.toString()` ref surfaces as a
 *    false-positive binary-compatibility error and blocks plugin load.
 *
 * Full-JAR integration coverage is intentionally deferred — these helpers
 * carry the only behavioural deltas of the recent pivot.
 */
class BinaryCompatibilityValidatorTest {
    // ─── hintFor ───────────────────────────────────────────────────────

    @Test
    fun `a missing stable field explains itself`() {
        // "$stable: field not found" was the ONLY clue when the host's plugin-logging copy
        // diverged from boss-plugin-api's, and it cost hours. The field is Compose-synthetic, so
        // its absence nearly always means two copies of the owning class exist — one compiled with
        // the Compose compiler and one without.
        val hint = BinaryCompatibilityValidator.hintFor("\$stable")

        assertTrue(hint.contains("Compose"), "no mention of Compose: $hint")
        assertTrue(hint.contains("diverged"), "does not name the actual cause: $hint")
    }

    @Test
    fun `an ordinary missing field gets no hint`() {
        // The hint must not dilute unrelated failures.
        assertEquals("", BinaryCompatibilityValidator.hintFor("someOrdinaryField"))
    }

    // ─── isSoftFailReference ───────────────────────────────────────────

    @Test
    fun `runtime package references soft-fail`() {
        assertTrue(
            BinaryCompatibilityValidator.isSoftFailReference(
                "ai.rever.boss.plugin.runtime.RemotePluginContext",
            ),
        )
        assertTrue(
            BinaryCompatibilityValidator.isSoftFailReference(
                "ai.rever.boss.plugin.runtime.stateholders.ConsoleStateHolder",
            ),
        )
    }

    @Test
    fun `api package references do not soft-fail`() {
        assertFalse(
            BinaryCompatibilityValidator.isSoftFailReference(
                "ai.rever.boss.plugin.api.DynamicPlugin",
            ),
        )
        assertFalse(
            BinaryCompatibilityValidator.isSoftFailReference(
                "ai.rever.boss.plugin.api.NonExistentClass",
            ),
        )
    }

    @Test
    fun `prefix is anchored to the package boundary`() {
        // `ai.rever.boss.plugin.runtimeFoo.X` is NOT the runtime package;
        // the trailing dot in the prefix prevents false positives.
        assertFalse(
            BinaryCompatibilityValidator.isSoftFailReference(
                "ai.rever.boss.plugin.runtimeFoo.X",
            ),
        )
    }

    @Test
    fun `jdk and other packages do not soft-fail`() {
        assertFalse(BinaryCompatibilityValidator.isSoftFailReference("java.util.List"))
        assertFalse(BinaryCompatibilityValidator.isSoftFailReference("kotlin.collections.MutableList"))
    }

    // ─── hasMethod ─────────────────────────────────────────────────────

    @Test
    fun `interface ref to Object's toString resolves`() {
        // The JNA CallbackProxy false-positive that blocked terminal-tab
        // loading: an `InterfaceMethodRef` on `java.util.List.toString()`.
        // `List.getMethod("toString")` doesn't see Object's inherited method,
        // but the call IS valid at runtime via dynamic dispatch.
        assertTrue(
            BinaryCompatibilityValidator.hasMethod(
                java.util.List::class.java,
                "toString",
                emptyArray(),
            ),
        )
    }

    @Test
    fun `interface ref to Object's equals resolves`() {
        assertTrue(
            BinaryCompatibilityValidator.hasMethod(
                java.util.List::class.java,
                "equals",
                arrayOf(Any::class.java),
            ),
        )
    }

    @Test
    fun `interface ref to Object's hashCode resolves`() {
        assertTrue(
            BinaryCompatibilityValidator.hasMethod(
                java.util.List::class.java,
                "hashCode",
                emptyArray(),
            ),
        )
    }

    @Test
    fun `interface ref to a declared interface method resolves`() {
        // Sanity: don't break the normal case while fixing the false-positive.
        assertTrue(
            BinaryCompatibilityValidator.hasMethod(
                java.util.List::class.java,
                "iterator",
                emptyArray(),
            ),
        )
    }

    @Test
    fun `interface ref to a genuinely missing method does not resolve`() {
        // Confirms the Object-method check is additive — we still report
        // missing methods that are neither on the interface nor on Object.
        assertFalse(
            BinaryCompatibilityValidator.hasMethod(
                java.util.List::class.java,
                "definitelyNotARealMethod",
                emptyArray(),
            ),
        )
    }

    @Test
    fun `concrete class still resolves Object methods via the superclass walk`() {
        // The Object-method shortcut is gated on `clazz.isInterface`; for
        // concrete classes the existing superclass-walk path covers it.
        assertTrue(
            BinaryCompatibilityValidator.hasMethod(
                java.util.ArrayList::class.java,
                "toString",
                emptyArray(),
            ),
        )
    }

    // ─── Bytecode Diagnostic Precision ─────────────────────────────────

    class DummyTarget {
        @JvmField
        var count: Int = 42

        fun execute(payload: String): Boolean = payload.isNotEmpty()
    }

    class ValidFixturePlugin {
        fun doSomething(): String = "ok"
    }

    class OverloadHolder {
        fun test() {}

        fun test(a: Int) {}

        fun test(
            a: Int,
            b: Int,
        ) {}

        fun test(a: String) {}

        fun test(a: Double) {}

        fun test(a: Long) {}

        fun test(a: Float) {}

        fun test(a: Boolean) {}

        fun test(a: Short) {}

        fun test(a: Byte) {}
    }

    class UnresolvableParamType

    class HolderWithBrokenMethod {
        fun broken(p: UnresolvableParamType) {}
    }

    class FilteringClassLoader(
        parent: ClassLoader,
        private val blockedClassName: String,
        private val holderClassName: String,
    ) : ClassLoader(parent) {
        override fun loadClass(
            name: String,
            resolve: Boolean,
        ): Class<*> {
            if (name == blockedClassName) {
                throw NoClassDefFoundError("Blocked for testing: $name")
            }
            if (name == holderClassName) {
                val resourcePath = name.replace('.', '/') + ".class"
                val bytes =
                    getResourceAsStream(resourcePath)?.readBytes()
                        ?: throw ClassNotFoundException(name)
                return defineClass(name, bytes, 0, bytes.size)
            }
            return super.loadClass(name, resolve)
        }
    }

    @Test
    fun `typeDescriptor computes standard JVM descriptors`() {
        assertEquals("I", BinaryCompatibilityValidator.typeDescriptor(Int::class.javaPrimitiveType!!))
        assertEquals("Z", BinaryCompatibilityValidator.typeDescriptor(Boolean::class.javaPrimitiveType!!))
        assertEquals("V", BinaryCompatibilityValidator.typeDescriptor(Void.TYPE))
        assertEquals("Ljava/lang/String;", BinaryCompatibilityValidator.typeDescriptor(String::class.java))
        assertEquals(
            "[Ljava/lang/String;",
            BinaryCompatibilityValidator.typeDescriptor(Array<String>::class.java),
        )
    }

    @Test
    fun `missing method error includes candidates without redundant tokens`() {
        val errors = mutableListOf<String>()
        val ref =
            ConstantPoolParser.MemberRef(
                type = ConstantPoolParser.RefType.METHOD,
                ownerClassName = DummyTarget::class.java.name,
                name = "execute",
                descriptor = "(I)Z",
            )

        BinaryCompatibilityValidator.verifyReference(
            ref = ref,
            classLoader = DummyTarget::class.java.classLoader,
            sourceClass = "com.example.caller.MyPlugin",
            errors = errors,
        )

        assertEquals(1, errors.size)
        val error = errors.first()
        assertTrue(
            error.startsWith("com.example.caller.MyPlugin -> ${DummyTarget::class.java.name}.execute(I)Z: method not found"),
            "Error must specify source and missing method: $error",
        )
        assertTrue(
            error.contains("available candidates: [execute(String): boolean]"),
            "Must list available candidates: $error",
        )
        assertFalse(error.contains("caller:"), "Must not contain redundant caller label: $error")
        assertFalse(error.contains("declaringClass:"), "Must not contain redundant declaringClass label: $error")
    }

    @Test
    fun `missing constructor error includes available constructors without redundant tokens`() {
        val errors = mutableListOf<String>()
        val ref =
            ConstantPoolParser.MemberRef(
                type = ConstantPoolParser.RefType.METHOD,
                ownerClassName = DummyTarget::class.java.name,
                name = "<init>",
                descriptor = "(Ljava/lang/String;)V",
            )

        BinaryCompatibilityValidator.verifyReference(
            ref = ref,
            classLoader = DummyTarget::class.java.classLoader,
            sourceClass = "com.example.caller.MyPlugin",
            errors = errors,
        )

        assertEquals(1, errors.size)
        val error = errors.first()
        val expectedPrefix =
            "com.example.caller.MyPlugin -> ${DummyTarget::class.java.name}.<init>(Ljava/lang/String;)V: " +
                "constructor not found"
        assertTrue(
            error.startsWith(expectedPrefix),
            "Error must specify source and missing constructor: $error",
        )
        assertTrue(
            error.contains("available constructors: [<init>()]"),
            "Must list default constructor: $error",
        )
        assertFalse(error.contains("caller:"), "Must not contain redundant caller label: $error")
        assertFalse(error.contains("declaringClass:"), "Must not contain redundant declaringClass label: $error")
    }

    @Test
    fun `missing field error indicates field not found without redundant tokens`() {
        val errors = mutableListOf<String>()
        val ref =
            ConstantPoolParser.MemberRef(
                type = ConstantPoolParser.RefType.FIELD,
                ownerClassName = DummyTarget::class.java.name,
                name = "nonExistentField",
                descriptor = "Ljava/lang/String;",
            )

        BinaryCompatibilityValidator.verifyReference(
            ref = ref,
            classLoader = DummyTarget::class.java.classLoader,
            sourceClass = "com.example.caller.MyPlugin",
            errors = errors,
        )

        assertEquals(1, errors.size)
        val error = errors.first()
        val expectedFieldPrefix =
            "com.example.caller.MyPlugin -> ${DummyTarget::class.java.name}.nonExistentField:Ljava/lang/String;: " +
                "field not found"
        assertTrue(
            error.startsWith(expectedFieldPrefix),
            "Must indicate field not found: $error",
        )
        assertFalse(error.contains("caller:"), "Must not contain redundant caller label: $error")
        assertFalse(error.contains("declaringClass:"), "Must not contain redundant declaringClass label: $error")
    }

    @Test
    fun `field type mismatch error reports expected and actual type without redundant tokens`() {
        val errors = mutableListOf<String>()
        val ref =
            ConstantPoolParser.MemberRef(
                type = ConstantPoolParser.RefType.FIELD,
                ownerClassName = DummyTarget::class.java.name,
                name = "count",
                descriptor = "Ljava/lang/String;", // Actual type is int (I)
            )

        BinaryCompatibilityValidator.verifyReference(
            ref = ref,
            classLoader = DummyTarget::class.java.classLoader,
            sourceClass = "com.example.caller.MyPlugin",
            errors = errors,
        )

        assertEquals(1, errors.size)
        val error = errors.first()
        assertTrue(error.contains("field type mismatch"), "Must report field type mismatch: $error")
        assertTrue(
            error.contains("expected Ljava/lang/String;, found I"),
            "Must report expected and actual descriptors: $error",
        )
        assertFalse(error.contains("caller:"), "Must not contain redundant caller label: $error")
        assertFalse(error.contains("declaringClass:"), "Must not contain redundant declaringClass label: $error")
    }

    @Test
    fun `shadowed field resolves superclass field when matching descriptor`() {
        val errors = mutableListOf<String>()
        val ref =
            ConstantPoolParser.MemberRef(
                type = ConstantPoolParser.RefType.FIELD,
                ownerClassName = SubWithShadowedField::class.java.name,
                name = "count",
                descriptor = "Ljava/lang/String;", // Matches SuperWithField.count
            )

        BinaryCompatibilityValidator.verifyReference(
            ref = ref,
            classLoader = SubWithShadowedField::class.java.classLoader,
            sourceClass = "ai.rever.boss.plugin.test.Caller",
            errors = errors,
        )

        assertTrue(errors.isEmpty(), "Must resolve superclass field without false rejection: $errors")
    }

    @Test
    fun `shadowed field resolves subclass field when matching subclass descriptor`() {
        val errors = mutableListOf<String>()
        val ref =
            ConstantPoolParser.MemberRef(
                type = ConstantPoolParser.RefType.FIELD,
                ownerClassName = SubWithShadowedField::class.java.name,
                name = "count",
                descriptor = "I", // Matches SubWithShadowedField.count
            )

        BinaryCompatibilityValidator.verifyReference(
            ref = ref,
            classLoader = SubWithShadowedField::class.java.classLoader,
            sourceClass = "ai.rever.boss.plugin.test.Caller",
            errors = errors,
        )

        assertTrue(errors.isEmpty(), "Must resolve subclass field without false rejection: $errors")
    }

    @Test
    fun `shadowed field reports type mismatch when neither matches`() {
        val errors = mutableListOf<String>()
        val ref =
            ConstantPoolParser.MemberRef(
                type = ConstantPoolParser.RefType.FIELD,
                ownerClassName = SubWithShadowedField::class.java.name,
                name = "count",
                descriptor = "Z", // Neither is boolean
            )

        BinaryCompatibilityValidator.verifyReference(
            ref = ref,
            classLoader = SubWithShadowedField::class.java.classLoader,
            sourceClass = "ai.rever.boss.plugin.test.Caller",
            errors = errors,
        )

        assertEquals(1, errors.size)
        val error = errors.first()
        assertTrue(error.contains("field type mismatch"), "Must report field type mismatch: $error")
        assertTrue(error.contains("expected Z, found I"), "Must report expected Z: $error")
    }

    @Test
    fun `exact field match produces zero errors`() {
        val errors = mutableListOf<String>()
        val ref =
            ConstantPoolParser.MemberRef(
                type = ConstantPoolParser.RefType.FIELD,
                ownerClassName = DummyTarget::class.java.name,
                name = "count",
                descriptor = "I",
            )

        BinaryCompatibilityValidator.verifyReference(
            ref = ref,
            classLoader = DummyTarget::class.java.classLoader,
            sourceClass = "ai.rever.boss.plugin.test.Caller",
            errors = errors,
        )

        assertTrue(errors.isEmpty(), "Matching field must produce zero errors: $errors")
    }

    @Test
    fun `non-contract external classes produce zero errors even if member missing`() {
        val errors = mutableListOf<String>()
        val ref =
            ConstantPoolParser.MemberRef(
                type = ConstantPoolParser.RefType.METHOD,
                ownerClassName = "java.util.List",
                name = "completelyNonExistentMethod",
                descriptor = "()V",
            )

        BinaryCompatibilityValidator.verifyReference(
            ref = ref,
            classLoader = javaClass.classLoader,
            sourceClass = "ai.rever.boss.plugin.test.Caller",
            errors = errors,
        )

        assertTrue(errors.isEmpty(), "References outside ai.rever.boss.plugin.* must not produce errors: $errors")
    }

    @Test
    fun `candidate extraction caps long candidate lists to 8 items`() {
        val candidates = BinaryCompatibilityValidator.extractCandidateMethods(OverloadHolder::class.java, "test")
        assertEquals(9, candidates.size)
        assertTrue(candidates.last().startsWith("... ("), "Last item must be overflow summary: ${candidates.last()}")
    }

    @Test
    fun `candidate extraction safely handles reflection failures`() {
        val holderName = HolderWithBrokenMethod::class.java.name
        val blockedName = UnresolvableParamType::class.java.name
        val loader = FilteringClassLoader(javaClass.classLoader, blockedName, holderName)
        val holderClass = loader.loadClass(holderName)

        val candidates = BinaryCompatibilityValidator.extractCandidateMethods(holderClass, "broken")
        assertEquals(emptyList(), candidates)
    }

    @Test
    fun `validate exercises full production seam with compiled jar entry`(
        @TempDir tempDir: Path,
    ) {
        val fixtureResource = "ai/rever/boss/plugin/loader/BinaryCompatibilityValidatorTest\$ValidFixturePlugin.class"
        val classBytes =
            javaClass.classLoader.getResourceAsStream(fixtureResource)
                ?: error("Fixture class not found on classpath: $fixtureResource")

        val jarFile = tempDir.resolve("valid-test-plugin.jar").toFile()
        ZipOutputStream(FileOutputStream(jarFile)).use { zip ->
            zip.putNextEntry(ZipEntry(fixtureResource))
            classBytes.copyTo(zip)
            zip.closeEntry()
        }

        val result = BinaryCompatibilityValidator.validate(javaClass.classLoader, jarFile.absolutePath)
        assertTrue(result.isCompatible, "Valid plugin jar must pass validation: ${result.errors}")
        assertTrue(result.errors.isEmpty(), "No errors should be produced: ${result.errors}")
    }
}
