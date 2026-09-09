package ai.rever.boss.plugin.api

import java.io.File
import java.net.URLClassLoader
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
 * test reads that resolved jar unfiltered, because the seven packages below are exactly the
 * ones that filtering throws away. An exact-version sibling build may supply the jar locally;
 * a release-equivalent result requires checking its digest against the published release.
 * `ai.rever.boss.plugin.api` itself is not in [duplicatedPackages]:
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
        val apiClassNames = ApiSurface.comparableClassNames(apiJar, duplicatedPackages)
        assertTrue(apiClassNames.isNotEmpty(), "Found no classes to compare - package list or jar is wrong")

        val mismatches = mutableListOf<String>()
        val internalSuffixes = ApiSurface.internalSuffixes(apiJar)

        IsolatedPackageClassLoader(apiJar, duplicatedPackages, javaClass.classLoader).use { apiLoader ->
            for (className in apiClassNames) {
                mismatches += ApiSurface.inspectClass(className, apiLoader, internalSuffixes)
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
