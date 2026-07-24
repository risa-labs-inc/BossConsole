import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.kotlinSerialization)
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

group = "ai.rever.boss.plugin"
version = "1.0.0"

// ============================================================================
// Store-only API contract resolution (no Maven registry).
//
// boss-plugin-api is distributed ONLY through its GitHub releases — the same
// assets the BOSS Plugin Store serves and the runtime ApiClassLoader loads.
// This module fetches the PINNED release's plugin jar (libs.versions.toml
// `boss-plugin-api`) and filters it to the `ai.rever.boss.plugin.api` package
// (+ META-INF/*.kotlin_module, without which top-level declarations like the
// LocalXxx composition locals don't resolve). Compiling against the fat
// plugin jar instead would put duplicate ui/logging/... FQCNs on the
// classpath that the host owns as its own modules.
//
// Resolution order: sibling boss_plugins/boss-plugin-api checkout jar with
// the EXACT pinned version (local dev) → previously fetched copy → GitHub
// release asset download (public repo, no token needed).
// ============================================================================
val bossPluginApiVersion: String =
    libs.versions.boss.plugin.api
        .get()
val apiContractDir = layout.buildDirectory.dir("api-contract")
val apiPluginJar = apiContractDir.map { it.file("boss-plugin-api-$bossPluginApiVersion.jar") }

val fetchApiPluginJar =
    tasks.register("fetchApiPluginJar") {
        description = "Fetches the pinned boss-plugin-api release jar (store distribution channel)"
        val version = bossPluginApiVersion
        val targetPath = apiPluginJar.get().asFile.absolutePath
        val siblingPaths =
            listOf(
                // Main checkout layout: Boss/BossConsole + Boss/boss_plugins
                rootDir.resolve("../boss_plugins/boss-plugin-api/build/libs/boss-plugin-api-$version.jar").absolutePath,
                // Worktree layout: Boss/.worktrees/<name> + Boss/boss_plugins
                rootDir.resolve("../../boss_plugins/boss-plugin-api/build/libs/boss-plugin-api-$version.jar").absolutePath,
            )
        outputs.file(targetPath)
        doLast {
            val target = File(targetPath)
            // Fetched once per pinned version. NOTE for SDK devs: rebuilding the
            // sibling checkout at the SAME pin does not refresh this copy —
            // delete build/api-contract/ (or bump the pin) to pick it up.
            if (target.isFile && target.length() > 0) return@doLast
            target.parentFile.mkdirs()

            val sibling = siblingPaths.map(::File).firstOrNull { it.isFile }
            if (sibling != null) {
                sibling.copyTo(target, overwrite = true)
                logger.lifecycle("boss-plugin-api $version copied from sibling checkout: ${sibling.path}")
                return@doLast
            }

            val url = "https://github.com/risa-labs-inc/boss-plugin-api/releases/download/v$version/boss-plugin-api-$version.jar"
            logger.lifecycle("Downloading boss-plugin-api $version from GitHub release…")
            val process =
                ProcessBuilder("curl", "-fsSL", "-o", targetPath, url)
                    .redirectErrorStream(true)
                    .start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            check(exit == 0 && target.isFile && target.length() > 0) {
                "Failed to download $url (curl exit $exit): $output\n" +
                    "Is v$version released? Distribution is store/GitHub-releases only — no Maven fallback. " +
                    "For local dev, build the jar in a sibling boss_plugins/boss-plugin-api checkout."
            }
        }
    }

val apiContractCoreJar =
    tasks.register<org.gradle.jvm.tasks.Jar>("apiContractCoreJar") {
        description = "Filters the api package out of the pinned boss-plugin-api jar for host compilation"
        dependsOn(fetchApiPluginJar)
        archiveBaseName.set("boss-plugin-api-core")
        archiveVersion.set(bossPluginApiVersion)
        destinationDirectory.set(apiContractDir.map { it.dir("filtered") })
        from(zipTree(apiPluginJar)) {
            include("ai/rever/boss/plugin/api/**")
            include("META-INF/*.kotlin_module")
        }
    }

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvmToolchain(17)

    jvm("desktop") {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }
        }
    }

    sourceSets {
        // The `ai.rever.boss.plugin.api` contract is filtered locally from the
        // pinned boss-plugin-api RELEASE jar (see fetchApiPluginJar above —
        // store-only distribution, no Maven registry), ending the hand-synced
        // source mirror. This module retains a FEW host-only sources under
        // src/ (the terminal/codeeditor/fluck tab types), which import the
        // contract classes from that jar. The ~20 host modules keep depending
        // on :plugin-platform:plugin-api-core unchanged. Bump the pin in
        // libs.versions.toml (boss-plugin-api) to adopt a new API level.
        commonMain {
            dependencies {
                // The pinned API contract classes (filtered release jar).
                api(files(apiContractCoreJar))

                // Compose dependencies
                api(libs.compose.mp.runtime)
                api(libs.compose.mp.ui)
                api(libs.compose.mp.foundation)
                api(libs.compose.mp.material)
                api(compose.materialIconsExtended)

                // Decompose for ComponentContext
                api(libs.decompose)
                api(libs.essenty.lifecycle)

                // Coroutines
                api(libs.kotlinx.coroutines.core)

                // Logging (SLF4J for BossLogger)
                api(libs.slf4j.api)

                // Serialization for PluginManifest
                api(libs.kotlinx.serialization.json)

                // Type modules for provider interfaces
                api(projects.pluginPlatform.pluginBookmarkTypes)
                api(projects.pluginPlatform.pluginWorkspaceTypes)

                // Browser service API for plugins needing browser capabilities
                api(projects.pluginPlatform.pluginApiBrowser)

                // UI core for context menu data types
                api(projects.pluginPlatform.pluginUiCore)

                // BossLogger/LogCategory — referenced by the artifact's classes
                api(projects.pluginPlatform.pluginLogging)
            }
        }

        named("desktopMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}
