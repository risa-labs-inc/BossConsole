package ai.rever.boss.plugin.loader

import kotlin.test.Test
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

    // ─── isSoftFailReference ───────────────────────────────────────────

    @Test
    fun `runtime package references soft-fail`() {
        assertTrue(
            BinaryCompatibilityValidator.isSoftFailReference(
                "ai.rever.boss.plugin.runtime.RemotePluginContext"
            )
        )
        assertTrue(
            BinaryCompatibilityValidator.isSoftFailReference(
                "ai.rever.boss.plugin.runtime.stateholders.ConsoleStateHolder"
            )
        )
    }

    @Test
    fun `api package references do not soft-fail`() {
        assertFalse(
            BinaryCompatibilityValidator.isSoftFailReference(
                "ai.rever.boss.plugin.api.DynamicPlugin"
            )
        )
        assertFalse(
            BinaryCompatibilityValidator.isSoftFailReference(
                "ai.rever.boss.plugin.api.NonExistentClass"
            )
        )
    }

    @Test
    fun `prefix is anchored to the package boundary`() {
        // `ai.rever.boss.plugin.runtimeFoo.X` is NOT the runtime package;
        // the trailing dot in the prefix prevents false positives.
        assertFalse(
            BinaryCompatibilityValidator.isSoftFailReference(
                "ai.rever.boss.plugin.runtimeFoo.X"
            )
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
                emptyArray()
            )
        )
    }

    @Test
    fun `interface ref to Object's equals resolves`() {
        assertTrue(
            BinaryCompatibilityValidator.hasMethod(
                java.util.List::class.java,
                "equals",
                arrayOf(Any::class.java)
            )
        )
    }

    @Test
    fun `interface ref to Object's hashCode resolves`() {
        assertTrue(
            BinaryCompatibilityValidator.hasMethod(
                java.util.List::class.java,
                "hashCode",
                emptyArray()
            )
        )
    }

    @Test
    fun `interface ref to a declared interface method resolves`() {
        // Sanity: don't break the normal case while fixing the false-positive.
        assertTrue(
            BinaryCompatibilityValidator.hasMethod(
                java.util.List::class.java,
                "iterator",
                emptyArray()
            )
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
                emptyArray()
            )
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
                emptyArray()
            )
        )
    }
}
