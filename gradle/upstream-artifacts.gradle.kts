/**
 * Aggregator task that collects the IPC-contract upstream JARs and lays
 * them out under `build/upstream-artifacts/` with stable filenames so
 * the release CI can append them to a GitHub release.
 *
 * Phase 1 of the runtime-extraction migration: the standalone microkernel
 * runtime repo will `compileOnly(files(...))` against these published
 * jars instead of `project(":boss-ipc")` etc.
 *
 * Naming: `<module>-<ipcVersion>.jar`. The version is sourced from the
 * project version of `:boss-ipc` (which is also asserted to match
 * `IpcVersion.CURRENT` — see check below).
 *
 * Outputs:
 *   build/upstream-artifacts/boss-ipc-1.0.0.jar
 *   build/upstream-artifacts/boss-ui-sdk-1.0.0.jar
 *   build/upstream-artifacts/plugin-api-ipc-1.0.0.jar
 *   build/upstream-artifacts/plugin-api-core-1.0.0.jar
 */

val outputDir = layout.buildDirectory.dir("upstream-artifacts")

// Snapshots captured at configuration time so the doLast closure stays
// configuration-cache safe (no project access from the task action). The
// boss-ipc subproject is in evaluation-pending state at root config time,
// so use afterEvaluate to grab its version once it's ready.
val ipcVersionSourceFile = file("modules/boss-ipc/src/main/kotlin/ai/rever/boss/ipc/IpcVersion.kt")
val ipcVersionProvider = providers.provider {
    project(":boss-ipc").version.toString()
}

tasks.register("verifyIpcVersionConsistency") {
    group = "verification"
    description = "Fails the build if IpcVersion.CURRENT and :boss-ipc.version drift apart."
    val src = ipcVersionSourceFile
    val moduleVersion = ipcVersionProvider
    inputs.file(src)
    doLast {
        if (!src.exists()) {
            throw GradleException(
                "Could not read IpcVersion.CURRENT from boss-ipc source. " +
                    "Did you rename or delete IpcVersion.kt?"
            )
        }
        val constant = Regex("""const\s+val\s+CURRENT\s*:\s*String\s*=\s*"([^"]+)"""")
            .find(src.readText())
            ?.groupValues
            ?.get(1)
            ?: throw GradleException(
                "Could not parse IpcVersion.CURRENT from ${src.name}. " +
                    "Expected `const val CURRENT: String = \"…\"`."
            )
        val mv = moduleVersion.get()
        if (constant != mv) {
            throw GradleException(
                "IPC version drift: IpcVersion.CURRENT='$constant' but " +
                    ":boss-ipc.version='$mv'. Update both to the same value " +
                    "(both should be the IPC wire-format version, not BossConsole's app version)."
            )
        }
        logger.lifecycle("✓ IPC version consistent: $constant")
    }
}

tasks.register<Copy>("assembleUpstreamJars") {
    group = "publishing"
    description = "Produces upstream IPC JARs under build/upstream-artifacts/ for runtime distribution."

    dependsOn("verifyIpcVersionConsistency")
    dependsOn(":boss-ipc:jar")
    dependsOn(":boss-ui-sdk:jar")
    dependsOn(":plugin-platform:plugin-api-ipc:desktopJar")
    dependsOn(":plugin-platform:plugin-api-core:desktopJar")

    val destDir = outputDir
    into(destDir)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // boss-ipc → boss-ipc-<ver>.jar
    from(project(":boss-ipc").layout.buildDirectory.dir("libs")) {
        include("boss-ipc-*.jar")
        exclude("*-sources.jar", "*-javadoc.jar")
    }
    // boss-ui-sdk → boss-ui-sdk-<ver>.jar
    from(project(":boss-ui-sdk").layout.buildDirectory.dir("libs")) {
        include("boss-ui-sdk-*.jar")
        exclude("*-sources.jar", "*-javadoc.jar")
    }
    // plugin-api-ipc desktopJar → plugin-api-ipc-<ver>-desktop.jar by default;
    // rename to drop the platform classifier so the runtime repo can fetch it
    // by a predictable name.
    from(project(":plugin-platform:plugin-api-ipc").layout.buildDirectory.dir("libs")) {
        include("plugin-api-ipc-desktop-*.jar")
        exclude("*-sources.jar", "*-javadoc.jar")
        rename("""plugin-api-ipc-desktop-(.+)\.jar""", "plugin-api-ipc-$1.jar")
    }
    // plugin-api-core desktopJar → same rename trick.
    from(project(":plugin-platform:plugin-api-core").layout.buildDirectory.dir("libs")) {
        include("plugin-api-core-desktop-*.jar")
        exclude("*-sources.jar", "*-javadoc.jar")
        rename("""plugin-api-core-desktop-(.+)\.jar""", "plugin-api-core-$1.jar")
    }

    doLast {
        val out = destDir.get().asFile
        val produced = out.listFiles()?.sorted().orEmpty()
        logger.lifecycle("✓ assembleUpstreamJars produced ${produced.size} jar(s):")
        produced.forEach { logger.lifecycle("  - ${it.name} (${it.length() / 1024} KB)") }
        if (produced.size < 4) {
            throw GradleException(
                "Expected 4 upstream jars in $out but got ${produced.size}: " +
                    produced.joinToString { it.name }
            )
        }
    }
}
