package ai.rever.boss.plugin.api

import java.io.File
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Diffs the host's copies of the packages boss-plugin-api also ships against the pinned release
 * jar itself, and fails on any public member the api has that the host's copy lacks.
 *
 * ## Why this exists
 *
 * `ai.rever.boss.plugin.{logging,bookmark,workspace,browser,scrollbar,ui,tab.terminal}` each
 * exist twice: once in the modules this test can see, and once inside boss-plugin-api, what
 * plugins actually compile against. The host's copy shadows the api's parent-first inside plugin
 * classloaders, so a plugin whose bytecode references a member the api has and the host does not
 * fails to link at load time - not at compile time, and not with a message that names this cause.
 * [LoggingStableFieldTest][ai.rever.boss.plugin.logging.LoggingStableFieldTest],
 * [BookmarkStableFieldTest][ai.rever.boss.plugin.bookmark.BookmarkStableFieldTest] and
 * [WorkspaceStableFieldTest][ai.rever.boss.plugin.workspace.WorkspaceStableFieldTest] each pin
 * the one field that has actually bitten us this way (secret-manager 1.2.6/1.2.7, unloadable on
 * every host). Nothing pinned the general case: an added method, a removed one, or a changed
 * signature in either copy breaks a plugin the same way, and would be diagnosed from scratch
 * exactly as `$stable` was, one incident at a time.
 *
 * ## What "the api" means here
 *
 * `plugin-api-core`'s own build already downloads the pinned release
 * (`fetchApiPluginJar`, `boss.api.contract.jar` system property below) to build
 * `apiContractCoreJar` - filtered down to `ai.rever.boss.plugin.api` for host compilation. This
 * test reads the SAME downloaded jar unfiltered, because the seven packages below are exactly the
 * ones that filtering throws away. `ai.rever.boss.plugin.api` itself is not in [duplicatedPackages]:
 * the host compiles against that filtered jar directly, so it cannot diverge from itself.
 * `ai.rever.boss.plugin.bundled` (the api module's own plugin-registration singleton) is api-only
 * and was never duplicated, so it is excluded too.
 *
 * Public JVM classes, constructors, methods and fields are compared, including nested types,
 * default-argument bridges and Compose stability fields referenced by compiled Kotlin callers.
 * Only module-mangled internal methods are excluded. Generic signatures and Kotlin source-level
 * compatibility are outside this erased JVM linkage check.
 */
class ApiPackageDivergenceTest {
    private val apiJar: File by lazy {
        val path =
            System.getProperty("boss.api.contract.jar")
                ?: error(
                    "boss.api.contract.jar is not set - this test reads the jar plugin-api-core's " +
                        "own build downloads, and only runs meaningfully through Gradle's desktopTest " +
                        "task (see plugin-api-core/build.gradle.kts), not a bare IDE run.",
                )
        File(path).also {
            check(it.isFile) { "api contract jar not found at $it - did fetchApiPluginJar run?" }
        }
    }

    private val duplicatedPackages =
        listOf(
            "ai.rever.boss.plugin.logging",
            "ai.rever.boss.plugin.bookmark",
            "ai.rever.boss.plugin.workspace",
            "ai.rever.boss.plugin.browser",
            "ai.rever.boss.plugin.scrollbar",
            "ai.rever.boss.plugin.ui",
            "ai.rever.boss.plugin.tab.terminal",
        )

    @Test
    fun `host copies of the duplicated api packages have not fallen behind`() {
        val apiClassNames = comparableClassNames(apiJar, duplicatedPackages)
        assertTrue(apiClassNames.isNotEmpty(), "Found no classes to compare - package list or jar is wrong")

        val mismatches = mutableListOf<String>()

        IsolatedPackageClassLoader(apiJar, duplicatedPackages, javaClass.classLoader).use { apiLoader ->
            for (className in apiClassNames) {
                val apiClass = apiLoader.loadClass(className)
                if (Modifier.isPublic(apiClass.modifiers) && !apiClass.isSynthetic) {
                    mismatches += classMismatches(apiClass)
                }
            }
        }

        assertTrue(
            mismatches.isEmpty(),
            "The host's copy of a duplicated api package is missing members boss-plugin-api has. " +
                "A plugin compiled against the api and calling one of these fails to link at " +
                "runtime, not at compile time:\n" +
                mismatches.joinToString("\n"),
        )
    }

    private fun classMismatches(apiClass: Class<*>): List<String> {
        val hostClass =
            try {
                Class.forName(apiClass.name, false, javaClass.classLoader)
            } catch (e: ClassNotFoundException) {
                return listOf("${apiClass.name}: exists in boss-plugin-api but not in the host: ${e.message}")
            }
        return buildList {
            if (!Modifier.isPublic(hostClass.modifiers)) add("${apiClass.name}: host class is not public")
            val missing = publicMemberSignatures(apiClass) - publicMemberSignatures(hostClass)
            if (missing.isNotEmpty()) add("${apiClass.name}: host is missing ${missing.sorted()}")
        }
    }

    /** Public methods and fields, as name+descriptor strings so overloads compare distinctly. */
    internal fun publicMemberSignatures(klass: Class<*>): Set<String> {
        val methods =
            klass.methods
                .filter { Modifier.isPublic(it.modifiers) && isComparableName(it.name) }
                .map { m ->
                    val parameters = m.parameterTypes.joinToString(",") { it.name }
                    "${staticKind(m.modifiers)} fun ${m.name}($parameters):${m.returnType.name}"
                }

        val fields =
            klass.fields
                .filter { Modifier.isPublic(it.modifiers) && isComparableName(it.name) }
                .map { f -> "${staticKind(f.modifiers)} val ${f.name}:${f.type.name}" }

        val constructors = klass.constructors.map { c -> "init(${c.parameterTypes.joinToString(",") { it.name }})" }
        return (methods + fields + constructors).toSet()
    }

    private fun staticKind(modifiers: Int) = if (Modifier.isStatic(modifiers)) "static" else "instance"

    // Do not discard $default, value-class mangling, or serializer bridges: callers link to them.
    private fun isComparableName(name: String) =
        !name.endsWith("\$boss_plugin_api") && !name.contains("\$com_risaboss_")

    internal fun comparableClassNames(
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

/**
 * Loads [packages] straight from [jar], bypassing parent delegation for exactly those packages so
 * comparison sees boss-plugin-api's actual classes rather than the host's - which the parent
 * classloader (the test's own) already has loaded for every one of them. Everything else (Kotlin
 * stdlib, coroutines, slf4j, ...) still resolves through [parent] normally, since those are not
 * being compared and the jar's classes need them to link.
 */
private class IsolatedPackageClassLoader(
    jar: File,
    private val packages: List<String>,
    parent: ClassLoader,
) : URLClassLoader(arrayOf(jar.toURI().toURL()), parent) {
    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> {
        if (packages.none { name.startsWith("$it.") }) return super.loadClass(name, resolve)

        synchronized(getClassLoadingLock(name)) {
            val loaded = findLoadedClass(name) ?: findClass(name)
            if (resolve) resolveClass(loaded)
            return loaded
        }
    }
}
