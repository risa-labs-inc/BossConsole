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
 * ## Scope: only the public, non-synthetic surface
 *
 * A class is compared only if its simple name has no `$`, or ends in `$Companion` - a real
 * top-level type or a Kotlin `companion object`, either of which a plugin can reference by name.
 * Everything else the Kotlin compiler emits under a dollar (lambda classes, `WhenMappings`,
 * `DefaultImpls`, coroutine continuations, ...) is compiler-internal wiring that shifts with
 * unrelated source changes on either side and that plugin bytecode never names directly - diffing
 * it would turn this test into churn instead of a signal.
 *
 * Within a compared class, only public methods and fields count, and two more differences are
 * allow-listed as benign, matching what a member-by-member `javap` diff against the api jar found
 * for the one field this already covers:
 *
 * - **`internal` Kotlin declarations**, module-name-mangled onto a public JVM member
 *   (`log$boss_plugin_api` here vs. this module's own mangled suffix) - unreachable from plugin
 *   bytecode either way, so a mismatch is not a real divergence. Detected as "contains `$`
 *   anywhere but is not exactly `$stable`", which also absorbs `$default` overload bridges and
 *   other compiler-synthesized member names for the same reason: none of them are names a
 *   plugin's own compiled bytecode invokes directly.
 * - **Extra private members on the host side** - not a divergence a plugin can observe, and
 *   already outside this test's scope since only public members are enumerated.
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

        val apiLoader = IsolatedPackageClassLoader(apiJar, duplicatedPackages, javaClass.classLoader)
        val mismatches = mutableListOf<String>()

        for (className in apiClassNames) {
            val apiClass = apiLoader.loadClass(className)
            val hostClass =
                try {
                    Class.forName(className, false, javaClass.classLoader)
                } catch (e: ClassNotFoundException) {
                    mismatches += "$className: exists in boss-plugin-api but not in the host at all"
                    continue
                }

            val onlyInApi = publicMemberSignatures(apiClass) - publicMemberSignatures(hostClass)
            if (onlyInApi.isNotEmpty()) {
                mismatches += "$className: host is missing ${onlyInApi.sorted()}"
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

    /** Public methods and fields, as name+descriptor strings so overloads compare distinctly. */
    private fun publicMemberSignatures(klass: Class<*>): Set<String> {
        val methods =
            klass.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) && isComparableName(it.name) }
                .map { m -> "fun ${m.name}(${m.parameterTypes.joinToString(",") { it.name }}):${m.returnType.name}" }

        val fields =
            klass.declaredFields
                .filter { Modifier.isPublic(it.modifiers) && isComparableName(it.name) }
                .map { f -> "val ${f.name}:${f.type.name}" }

        return (methods + fields).toSet()
    }

    /** `$stable` is a real, required part of the contract; anything else with a `$` is not. */
    private fun isComparableName(name: String) = name == "\$stable" || "$" !in name

    private fun comparableClassNames(
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
                .filter { fqcn ->
                    val simpleName = fqcn.substringAfterLast('.')
                    "$" !in simpleName || simpleName.endsWith("\$Companion")
                }.toList()
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
            findLoadedClass(name)?.let { return it }
            val loaded = findClass(name)
            if (resolve) resolveClass(loaded)
            return loaded
        }
    }
}
