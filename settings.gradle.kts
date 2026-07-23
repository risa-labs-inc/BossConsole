rootProject.name = "BOSS-Kotlin"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
        mavenCentral()
        // NOTE: the boss-plugin-api contract is deliberately NOT resolved from
        // any Maven registry — its distribution is store/GitHub-releases only.
        // plugin-platform/plugin-api-core downloads the pinned release jar (the same
        // asset the Plugin Store serves) and filters the api package locally.
    }
}

include(":composeApp")
include(":server")
// Microkernel architecture modules
// protoc and protoc-gen-grpc-java do not publish Windows ARM64 binaries,
// so all microkernel modules (which depend on boss-ipc proto generation)
// are excluded on that platform. The microkernel is server-grade infrastructure
// not needed for the Windows ARM64 desktop distribution.
val settingsOsArch: String = System.getProperty("os.arch").lowercase()
val settingsOsName: String = System.getProperty("os.name").lowercase()
val isWindowsArm64 = settingsOsName.contains("win") && (settingsOsArch == "aarch64" || settingsOsArch == "arm")

if (!isWindowsArm64) {
    // The microkernel modules live under modules/ to keep the repo root tidy,
    // but keep their flat Gradle paths (:boss-ipc, …) so consumers and the
    // type-safe `projects.*` accessors are unchanged — only the projectDir moves.
    listOf(
        "boss-ipc",
        "boss-process-manager",
        "boss-service-auth",
        "boss-ui-sdk",
        "boss-orchestrator",
        "boss-mastery-sdk",
        "boss-mastery-orchestrator",
        "boss-service-workspace",
        "boss-service-settings",
        "boss-service-filesystem",
        "boss-app-terminal",
        "boss-app-editor",
        "boss-app-browser",
    ).forEach { name ->
        include(":$name")
        project(":$name").projectDir = file("modules/$name")
    }
    include(":plugin-platform:plugin-api-ipc")
}
// Plugin modules
// plugin-api-core: Ultra-minimal core (PluginContext, DynamicPlugin, PluginManifest)
// Everything else comes from boss-plugin-api bundled plugin
include(":plugin-platform:plugin-api-core")
include(":plugin-platform:plugin-ui-core")
include(":plugin-platform:plugin-logging")
include(":plugin-platform:plugin-scrollbar")
include(":plugin-platform:plugin-events")
include(":plugin-platform:plugin-search")
include(":plugin-platform:plugin-window")
include(":plugin-platform:plugin-git-types")
include(":plugin-platform:plugin-run-types")
include(":plugin-platform:plugin-workspace-types")
include(":plugin-platform:plugin-bookmark-types")
include(":plugin-platform:plugin-icons")
include(":plugin-platform:plugin-path-utils")
include(":plugin-platform:plugin-sandbox")
include(":plugin-platform:plugin-api-browser")
include(":plugin-platform:plugin-loader")
include(":plugin-platform:plugin-repository")
include(":plugin-platform:plugin-dependency")
include(":plugin-platform:plugin-updater")
// Plugin panel manager is now dynamic (loaded from boss_plugin as plugin-manager)

// Tab type plugins are now dynamic (loaded from boss_plugin):
// - editor-tab, terminal-tab, fluck-browser