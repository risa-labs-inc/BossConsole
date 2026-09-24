package ai.rever.boss.plugin.loader

import org.junit.jupiter.api.io.TempDir
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
 * The bytecode-diagnostics round added the JVMS §5.4.3.2 two-pass field
 * resolution, candidate capping, and end-to-end `validate` coverage: a real
 * jar round-trip (success AND failure), the same-JAR and third-party skips,
 * and the LinkageError gating of class-load diagnostics.
 *
 * Package note (load-bearing): the fixture classes clear the
 * `ai.rever.boss.plugin.*` contract gate in [BinaryCompatibilityValidator.verifyReference]
 * ONLY because this test sits in `ai.rever.boss.plugin.loader`. Moving the test to another
 * package turns the "missing member" assertions into silent zero-error passes.
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

    @Suppress("FunctionOnlyReturningConstant")
    class ValidFixturePlugin {
        fun doSomething(): String = "ok"
    }

    @Suppress("EmptyFunctionBlock", "UnusedParameter")
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

    @Suppress("EmptyFunctionBlock", "UnusedParameter")
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
        val expectedPrefix =
            "com.example.caller.MyPlugin -> ${DummyTarget::class.java.name}.execute(I)Z: " +
                "method not found"
        assertTrue(
            error.startsWith(expectedPrefix),
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
            javaClass.classLoader
                .getResourceAsStream(fixtureResource)
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

    // ─── validate seam: failure path, skips, and linkage gating ──────────────

    /** A jar with [entries] under their internal names. */
    private fun jarWith(
        tempDir: Path,
        vararg entries: Pair<String, ByteArray>,
    ): String {
        val jarFile = tempDir.resolve("seam-test-plugin.jar").toFile()
        ZipOutputStream(FileOutputStream(jarFile)).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return jarFile.absolutePath
    }

    private fun classBytes(className: String): ByteArray =
        javaClass.classLoader
            .getResourceAsStream(className.replace('.', '/') + ".class")
            ?.readBytes()
            ?: error("Fixture class not on classpath: $className")

    /**
     * A minimal class whose constant pool holds exactly one MethodRef:
     * `SyntheticCaller` -> [ownerInternal].[memberName]() — enough for
     * `ConstantPoolParser.extractReferences` to parse, so the test pins the
     * `validate` -> `verifyReference` -> `ValidationResult` aggregation end to end.
     * The parser only reads the constant pool, so the header past it is padding.
     */
    private fun syntheticClassWithMethodRef(
        ownerInternal: String,
        memberName: String,
    ): ByteArray {
        val pool = mutableListOf<ByteArray>()

        fun shortBytes(value: Int): ByteArray {
            val high = ((value shr 8) and 0xff).toByte()
            val low = (value and 0xff).toByte()
            return byteArrayOf(high, low)
        }

        fun utf8(text: String): Int {
            val bytes = text.toByteArray()
            pool.add(byteArrayOf(0x01) + shortBytes(bytes.size) + bytes)
            return pool.size
        }

        fun classEntry(nameIndex: Int): Int {
            pool.add(byteArrayOf(0x07) + shortBytes(nameIndex))
            return pool.size
        }

        fun nameAndType(
            nameIndex: Int,
            descIndex: Int,
        ): Int {
            pool.add(byteArrayOf(0x0C) + shortBytes(nameIndex) + shortBytes(descIndex))
            return pool.size
        }

        val callerClass =
            "ai/rever/boss/plugin/loader/BinaryCompatibilityValidatorTest\$ValidFixturePlugin"
        val callerIndex = classEntry(utf8(callerClass))
        val ownerIndex = classEntry(utf8(ownerInternal))
        val natIndex = nameAndType(utf8(memberName), utf8("()V"))
        pool.add(byteArrayOf(0x0A) + shortBytes(ownerIndex) + shortBytes(natIndex))

        val out = ByteBuffer.allocate(64 + pool.sumOf { it.size }).order(ByteOrder.BIG_ENDIAN)
        out.putInt(0xCAFEBABE.toInt())
        out.putShort(0)
        out.putShort(61)
        out.putShort((pool.size + 1).toShort())
        pool.forEach { out.put(it) }
        out.putShort(0x0001)
        out.putShort(callerIndex.toShort())
        out.putShort(ownerIndex.toShort())
        out.putShort(0)
        out.putShort(0)
        out.putShort(0)
        out.putShort(0)
        return out.array().copyOf(out.position())
    }

    @Test
    fun `validate returns isCompatible false for a contract owner with a missing member`(
        @TempDir tempDir: Path,
    ) {
        val fixtureClass = ValidFixturePlugin::class.java
        val targetClass = DummyTarget::class.java
        val jarPath =
            jarWith(
                tempDir,
                "${fixtureClass.name.replace('.', '/')}.class" to
                    syntheticClassWithMethodRef(
                        targetClass.name.replace('.', '/'),
                        "noSuchMethod",
                    ),
            )
        val result = BinaryCompatibilityValidator.validate(javaClass.classLoader, jarPath)
        assertFalse(result.isCompatible, "a missing contract member must fail validation")
        assertTrue(
            result.errors.any { it.contains("noSuchMethod") },
            "error must name the member: ${result.errors}",
        )
        assertTrue(
            result.errors.any { it.contains("method not found") },
            "error must be the method diagnostic: ${result.errors}",
        )
    }

    @Test
    fun `a plugin class the host cannot load is reported as a class-load failure`(
        @TempDir tempDir: Path,
    ) {
        val holderName = ContractSubWithBlockedSuper::class.java.name
        val blockedName = "com.thirdparty.loaderfixture.SuperThirdParty"
        val loader = FilteringClassLoader(javaClass.classLoader, blockedName, holderName)
        val jarPath =
            jarWith(
                tempDir,
                "${holderName.replace('.', '/')}.class" to classBytes(holderName),
            )
        val result = BinaryCompatibilityValidator.validate(loader, jarPath)
        assertFalse(result.isCompatible, "an unloadable contract class must fail validation")
        assertTrue(result.errors.any { it.contains(holderName) }, "error must name the class: ${result.errors}")
        assertTrue(
            result.errors.any { it.contains("NoClassDefFoundError") },
            "error must report linkage failure: ${result.errors}",
        )
    }

    @Test
    fun `a LinkageError on a third-party owner produces no error`() {
        val errors = mutableListOf<String>()
        val holderName = "com.thirdparty.loaderfixture.SubThirdParty"
        val ref =
            ConstantPoolParser.MemberRef(
                type = ConstantPoolParser.RefType.METHOD,
                ownerClassName = holderName,
                name = "shared",
                descriptor = "()I",
            )
        val loader =
            FilteringClassLoader(
                javaClass.classLoader,
                "com.thirdparty.loaderfixture.SuperThirdParty",
                holderName,
            )
        BinaryCompatibilityValidator.verifyReference(
            ref = ref,
            classLoader = loader,
            sourceClass = "com.example.caller.MyPlugin",
            errors = errors,
        )
        assertTrue(errors.isEmpty(), "a bundled third-party owner must not disable the plugin: $errors")
    }

    @Test
    fun `a LinkageError on a contract owner is still reported`() {
        val errors = mutableListOf<String>()
        val holderName = ContractSubWithBlockedSuper::class.java.name
        val ref =
            ConstantPoolParser.MemberRef(
                type = ConstantPoolParser.RefType.METHOD,
                ownerClassName = holderName,
                name = "own",
                descriptor = "()I",
            )
        val loader =
            FilteringClassLoader(
                javaClass.classLoader,
                "com.thirdparty.loaderfixture.SuperThirdParty",
                holderName,
            )
        BinaryCompatibilityValidator.verifyReference(
            ref = ref,
            classLoader = loader,
            sourceClass = "com.example.caller.MyPlugin",
            errors = errors,
        )
        assertEquals(1, errors.size, "a contract owner's linkage failure must be reported: $errors")
        assertTrue(errors.first().contains("ContractSubWithBlockedSuper"), "error must name the owner: $errors")
        assertTrue(errors.first().contains("NoClassDefFoundError"), "error must be linkage diagnostic: $errors")
    }

    @Test
    fun `field resolution on a type with an unresolvable field type is a miss, not a crash`() {
        val errors = mutableListOf<String>()
        val holderName = HolderWithBrokenField::class.java.name
        val loader = FilteringClassLoader(javaClass.classLoader, BrokenFieldType::class.java.name, holderName)
        val ref =
            ConstantPoolParser.MemberRef(
                type = ConstantPoolParser.RefType.FIELD,
                ownerClassName = holderName,
                name = "broken",
                descriptor = "Lai/rever/boss/plugin/loader/BrokenFieldType;",
            )
        BinaryCompatibilityValidator.verifyReference(
            ref = ref,
            classLoader = loader,
            sourceClass = "com.example.caller.MyPlugin",
            errors = errors,
        )
        assertEquals(1, errors.size, "the miss must be reported as a diagnostic, not an Error: $errors")
        assertTrue(errors.first().contains("field not found"), "expected the miss diagnostic: $errors")
    }
}
