package ai.rever.boss.plugin.launchpad.scan

import ai.rever.boss.plugin.launchpad.PluginManifest
import java.io.File

/** The JAR-level findings: what is wrong or notable about the archive, its manifest and its signature file. */
internal object ScanFindings {
    private const val SIGNATURE_STALE_MS = 2_000L
    private val storeId = Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+$")

    /** Sensitive host surfaces: using one with no `requiredPermissions` leaves it open to every signed-in user. */
    private val gateable =
        setOf("host.secrets", "host.brokered-credential", "host.store-key", "host.supabase", "host.admin")
    private val secretSources =
        setOf("host.secrets", "host.brokered-credential", "host.store-key", "host.supabase", "cred.path", "env.read")
    private val dataSources =
        setOf(
            "fs.read",
            "host.filesystem",
            "host.project-search",
            "host.editor",
            "host.clipboard",
            "clipboard.system",
            "host.browser",
            "host.event-bus",
            "host.active-tabs",
            "host.screen-capture",
        )

    fun of(
        file: File,
        tally: PluginJarScanner.Tally,
        manifest: PluginManifest?,
        ids: Set<String>,
    ): List<ScanFinding> =
        buildList {
            addAll(manifestFindings(tally, manifest))
            addAll(archiveFindings(tally))
            addAll(comboFindings(ids))
            if (manifest != null) addAll(permissionFindings(manifest, ids))
            signatureFinding(file)?.let { add(it) }
            incompleteFinding(tally)?.let { add(it) }
        }

    /**
     * A class that was skipped (too large, corrupt, an unreadable constant pool) or a truncation limit that
     * fired (too many entries, too many total bytes) never otherwise reaches [ScanResult.topRisk]: those are
     * recorded in `tally.unreadable`/`tally.limits`, which the verdict, `--fail-on` and the JSON exit code do
     * not look at. Without this, whoever built the JAR can pad the one dangerous class past the size cap, or
     * put thousands of junk entries before the real classes, and the scan reports a clean bill and exits 0.
     */
    private fun incompleteFinding(tally: PluginJarScanner.Tally): ScanFinding? {
        if (tally.unreadable == 0 && tally.limits.isEmpty()) return null
        return finding(
            "scan.incomplete",
            ScanRisk.MEDIUM,
            "The scan did not read the whole JAR",
            "${tally.unreadable} class(es) could not be read, or a size/entry limit was hit while reading. " +
                "The rest of this report is not a complete picture of this JAR.",
        )
    }

    private fun finding(
        id: String,
        risk: ScanRisk,
        title: String,
        detail: String,
    ) = ScanFinding(id, risk, title, detail)

    private fun manifestFindings(
        tally: PluginJarScanner.Tally,
        manifest: PluginManifest?,
    ): List<ScanFinding> {
        if (manifest == null) {
            return listOf(
                finding(
                    "manifest.unreadable",
                    ScanRisk.MEDIUM,
                    "No readable plugin.json in the JAR",
                    "The host cannot load a JAR without a valid META-INF/boss-plugin/plugin.json.",
                ),
            )
        }
        val out = mutableListOf<ScanFinding>()
        val main = manifest.entrypointClass
        val incomplete = tally.unreadable > 0 || tally.limits.isNotEmpty()
        if (main.isBlank()) {
            out += finding("manifest.no-main-class", ScanRisk.MEDIUM, "No mainClass in plugin.json", "Nothing to run")
        } else if (main.replace('.', '/') !in tally.classNames && !incomplete) {
            // Skipped when the scan is incomplete: a class the scanner could not read (too large, a
            // truncated pool) is absent from tally.classNames for the same reason it might be the main
            // class itself, so "not among the readable classes" would be a false positive here. The
            // scan.incomplete finding already flags the report as not a complete picture.
            out +=
                finding(
                    "manifest.main-class-missing",
                    ScanRisk.MEDIUM,
                    "mainClass is not in the JAR",
                    "${ScanText.safe(main)} was not among the ${tally.scanned} readable classes.",
                )
        }
        if (!storeId.matches(manifest.id)) {
            out +=
                finding(
                    "manifest.plugin-id",
                    ScanRisk.LOW,
                    "pluginId is not in store form",
                    "The store accepts lowercase reverse-domain ids only.",
                )
        }
        return out
    }

    private fun archiveFindings(tally: PluginJarScanner.Tally): List<ScanFinding> =
        buildList {
            if (tally.nativeLibs.isNotEmpty()) {
                val example = ScanText.safe(tally.nativeLibs.first())
                add(
                    finding(
                        "jar.native-library",
                        ScanRisk.HIGH,
                        "Bundles native libraries",
                        "${tally.nativeLibs.size} file(s), for example $example. Native code is not analysed here.",
                    ),
                )
            }
            if (tally.nestedJars.isNotEmpty()) {
                val example = ScanText.safe(tally.nestedJars.first())
                add(
                    finding(
                        "jar.nested-jar",
                        ScanRisk.MEDIUM,
                        "Bundles other JARs",
                        "${tally.nestedJars.size} nested JAR(s), for example $example. Contents not scanned.",
                    ),
                )
            }
            if (tally.bundlesApi > 0) {
                add(
                    finding(
                        "jar.bundles-host-api",
                        ScanRisk.MEDIUM,
                        "Bundles copies of the host's plugin API classes",
                        "${tally.bundlesApi} class(es) under ai/rever/boss/plugin/api; the host loads its own first.",
                    ),
                )
            }
            if (tally.traversal > 0) {
                add(
                    finding(
                        "jar.path-traversal",
                        ScanRisk.MEDIUM,
                        "Entry names that climb out of the archive",
                        "${tally.traversal} name(s) contain .. or an absolute path; unpacking this JAR would " +
                            "write outside its target.",
                    ),
                )
            }
            if (tally.services.isNotEmpty()) {
                add(
                    finding(
                        "jar.service-providers",
                        ScanRisk.LOW,
                        "Declares ServiceLoader providers",
                        "${tally.services.size} META-INF/services file(s).",
                    ),
                )
            }
        }

    private fun comboFindings(ids: Set<String>): List<ScanFinding> {
        if ("net.client" !in ids && "net.listen" !in ids) return emptyList()
        return when {
            ids.any { it in secretSources } -> {
                listOf(
                    finding(
                        "combo.secret-and-network",
                        ScanRisk.HIGH,
                        "Can read credentials and reach the network",
                        "It mentions a way to obtain secrets and a way to send data off the machine. " +
                            "That is the shape of an exfiltration path, not proof of one.",
                    ),
                )
            }

            ids.any { it in dataSources } -> {
                listOf(
                    finding(
                        "combo.data-and-network",
                        ScanRisk.MEDIUM,
                        "Can read local data and reach the network",
                        "It mentions a way to read local data and a way to send data off the machine.",
                    ),
                )
            }

            else -> {
                emptyList()
            }
        }
    }

    private fun permissionFindings(
        manifest: PluginManifest,
        ids: Set<String>,
    ): List<ScanFinding> {
        val gated = ids.filter { it in gateable }
        if (gated.isEmpty() || manifest.permissions.isNotEmpty()) return emptyList()
        return listOf(
            finding(
                "manifest.ungated-sensitive-api",
                ScanRisk.MEDIUM,
                "Uses a sensitive host API but declares no requiredPermissions",
                "${gated.sorted().joinToString()}. Empty requiredPermissions leaves it open to every signed-in user.",
            ),
        )
    }

    private fun signatureFinding(file: File): ScanFinding? {
        val sig = File(file.path + ".sig")
        return when {
            !sig.isFile -> {
                finding(
                    "sig.none",
                    ScanRisk.INFO,
                    "No .jar.sig next to the JAR",
                    "Installations that enforce signatures reject an unsigned local JAR.",
                )
            }

            sig.lastModified() + SIGNATURE_STALE_MS < file.lastModified() -> {
                finding(
                    "sig.stale",
                    ScanRisk.MEDIUM,
                    "The .jar.sig is older than the JAR",
                    "It was probably made for different bytes, and the host will refuse to load this JAR against it. " +
                        "Move it away before replacing a plugin.",
                )
            }

            else -> {
                null
            }
        }
    }
}
