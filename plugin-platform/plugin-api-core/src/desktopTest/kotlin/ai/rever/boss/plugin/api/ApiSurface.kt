package ai.rever.boss.plugin.api

import java.io.File
import java.lang.reflect.Modifier
import java.util.zip.ZipFile
import kotlin.test.assertTrue

internal object ApiSurface {
    internal fun inspectClass(
        name: String,
        apiLoader: ClassLoader,
        internalSuffixes: Set<String> = emptySet(),
    ): List<String> =
        try {
            val apiClass = apiLoader.loadClass(name)
            assertTrue(
                apiClass.classLoader === apiLoader,
                "$name: API loader delegated to the host; comparison is invalid",
            )
            if (Modifier.isPublic(apiClass.modifiers) && !apiClass.isSynthetic) {
                classMismatches(apiClass, internalSuffixes)
            } else {
                emptyList()
            }
        } catch (error: LinkageError) {
            listOf("$name: cannot inspect API/host linkage: $error")
        }

    private fun classMismatches(
        apiClass: Class<*>,
        internalSuffixes: Set<String>,
    ): List<String> {
        val hostClass =
            try {
                Class.forName(apiClass.name, false, javaClass.classLoader)
            } catch (e: ClassNotFoundException) {
                return listOf("${apiClass.name}: exists in boss-plugin-api but not in the host: ${e.message}")
            }
        return buildList {
            if (!Modifier.isPublic(hostClass.modifiers)) add("${apiClass.name}: host class is not public")
            val missing =
                publicMemberSignatures(apiClass, internalSuffixes) - publicMemberSignatures(hostClass)
            if (missing.isNotEmpty()) add("${apiClass.name}: host is missing ${missing.sorted()}")
        }
    }

    /** Public methods and fields, as name+descriptor strings so overloads compare distinctly. */
    fun publicMemberSignatures(
        klass: Class<*>,
        internalSuffixes: Set<String> = emptySet(),
    ): Set<String> {
        val methods =
            klass.methods
                // Private lambda accessors are compiler implementation details; their numeric
                // suffix changes when the enclosing implementation changes. Keep other synthetic
                // bridges (including public inline/default-argument entry points) in the contract.
                .filterNot { it.isSynthetic && it.name.startsWith("access\$") && "\$lambda\$" in it.name }
                .filter { member -> internalSuffixes.none { member.name.endsWith(it) } }
                .map { m ->
                    val parameters = m.parameterTypes.joinToString(",") { it.name }
                    "${staticKind(m.modifiers)} fun ${m.name}($parameters):${m.returnType.name}"
                }

        val fields =
            klass.fields
                .filter { member -> internalSuffixes.none { member.name.endsWith(it) } }
                .map { f -> "${staticKind(f.modifiers)} val ${f.name}:${f.type.name}" }

        val constructors = klass.constructors.map { c -> "init(${c.parameterTypes.joinToString(",") { it.name }})" }
        return (methods + fields + constructors).toSet()
    }

    private fun staticKind(modifiers: Int) = if (Modifier.isStatic(modifiers)) "static" else "instance"

    // The API artifact declares its Kotlin module names. Host-only members are harmless and
    // need no filtering. Keep $default, value-class and serializer bridges callable by plugins.
    fun internalSuffixes(jar: File): Set<String> =
        ZipFile(jar).use { zip ->
            zip
                .entries()
                .asSequence()
                .map { it.name }
                .filter { it.startsWith("META-INF/") && it.endsWith(".kotlin_module") }
                .map { "$" + it.substringAfterLast('/').removeSuffix(".kotlin_module").replace('-', '_') }
                .toSet()
        }

    fun comparableClassNames(
        jar: File,
        packages: List<String>,
    ): List<String> {
        val prefixes = packages.map { it.replace('.', '/') + "/" }
        ZipFile(jar).use { zip ->
            return zip
                .entries()
                .asSequence()
                .map { it.name }
                .filter { name -> name.endsWith(".class") && prefixes.any { name.startsWith(it) } }
                .map { it.removeSuffix(".class").replace('/', '.') }
                .toList()
        }
    }
}
