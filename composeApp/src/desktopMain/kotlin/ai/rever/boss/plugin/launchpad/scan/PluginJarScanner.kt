package ai.rever.boss.plugin.launchpad.scan

import ai.rever.boss.plugin.launchpad.PluginManifest
import ai.rever.boss.plugin.launchpad.PluginValidator
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** A problem or observation about the JAR as a whole, as opposed to one capability. */
internal data class ScanFinding(
    val id: String,
    val risk: ScanRisk,
    val title: String,
    val detail: String,
)

/** A capability, everywhere it was seen. */
internal data class CapabilityUse(
    val capability: Capability,
    val evidence: List<Evidence>,
    val classCount: Int,
)

@Suppress("LongParameterList")
internal class ScanResult(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String?,
    val manifest: PluginManifest?,
    val classesScanned: Int,
    val classesUnreadable: Int,
    val capabilities: List<CapabilityUse>,
    val findings: List<ScanFinding>,
    val hosts: List<String>,
    /** Why the picture may be incomplete. Always shown: a scan that hid its limits would read as a clean bill. */
    val limits: List<String>,
    /** Set when the file could not be scanned at all. */
    val unreadableReason: String? = null,
) {
    val topRisk: ScanRisk
        get() = (capabilities.map { it.capability.risk } + findings.map { it.risk }).maxOrNull() ?: ScanRisk.INFO

    val pluginId: String? get() = manifest?.id?.takeIf { it.isNotBlank() }
}

/**
 * Reads a plugin JAR and reports what its classes reach for.
 *
 * Static and read-only: no class is loaded or run, and the file is only opened for reading. Bounded in every
 * direction, because the JAR is a stranger's: a cap on the file, the entry count, the bytes read for one class
 * and the bytes read overall, none of which trusts the sizes the archive declares for itself.
 */
internal object PluginJarScanner {
    const val MAX_JAR_BYTES = 256L * 1024 * 1024
    const val MAX_ENTRIES = 20_000
    const val MAX_CLASS_BYTES = 8 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
    private const val MAX_HOSTS = 25
    private const val MAX_EVIDENCE = 5
    private const val SERVICES = "META-INF/services/"
    private val nativeSuffixes = listOf(".dll", ".so", ".dylib", ".jnilib")
    private val benignHosts =
        setOf("www.w3.org", "www.apache.org", "xmlpull.org", "java.sun.com", "xml.org", "www.jcp.org")
    private val urlHost = Regex("https?://([A-Za-z0-9][A-Za-z0-9.-]{0,252})")

    /** What the walk collects before it is turned into a [ScanResult]. */
    internal class Tally {
        val evidence = LinkedHashMap<String, MutableList<Evidence>>()
        val classesPer = LinkedHashMap<String, MutableSet<String>>()
        val capabilities = LinkedHashMap<String, Capability>()
        val hosts = LinkedHashSet<String>()
        val classNames = HashSet<String>()
        val nativeLibs = mutableListOf<String>()
        val nestedJars = mutableListOf<String>()
        val services = mutableListOf<String>()
        val limits = mutableListOf<String>()
        var scanned = 0
        var unreadable = 0
        var totalBytes = 0L
        var bundlesApi = 0
        var traversal = 0
        private var hostsLimited = false

        fun record(hit: Hit) {
            val id = hit.capability.id
            capabilities.putIfAbsent(id, hit.capability)
            evidence.getOrPut(id) { mutableListOf() }.add(hit.evidence)
            classesPer.getOrPut(id) { LinkedHashSet() }.add(hit.evidence.className)
        }

        fun collectHosts(info: ClassInfo) {
            for (s in info.strings) {
                if (hosts.size >= MAX_HOSTS) {
                    if (!hostsLimited) {
                        hostsLimited = true
                        limits += "Stopped after $MAX_HOSTS hosts named in the code; more may be there."
                    }
                    break
                }
                val host =
                    urlHost
                        .find(s)
                        ?.groupValues
                        ?.get(1)
                        ?.lowercase()
                if (host != null && host !in benignHosts) hosts += host
            }
        }
    }

    fun scan(file: File): ScanResult {
        val length = file.length()
        val tally = Tally()
        val failure =
            if (length > MAX_JAR_BYTES) "larger than the $MAX_JAR_BYTES-byte limit, not opened" else walk(file, tally)
        return if (failure != null) unreadable(file, length, failure) else assemble(file, length, tally)
    }

    /** Reads every entry into [tally]. Returns why the archive could not be read, or null when it could. */
    private fun walk(
        file: File,
        tally: Tally,
    ): String? =
        try {
            ZipFile(file).use { zip -> readEntries(zip, tally) }
            null
        } catch (_: ZipException) {
            "not a readable ZIP/JAR archive"
        } catch (_: IllegalArgumentException) {
            // ZipFile/ZipCoder throws this, not ZipException, for a malformed entry name (invalid UTF-8 in
            // the central directory). The file is a stranger's, so this is a hostile input, not a bug here.
            "not a readable ZIP/JAR archive"
        } catch (e: IOException) {
            "could not be read (${e.javaClass.simpleName})"
        }

    private fun assemble(
        file: File,
        length: Long,
        tally: Tally,
    ): ScanResult {
        val manifest = PluginValidator.readManifestFromJar(file)
        val capabilities =
            tally.capabilities.values
                .map { cap ->
                    val evidence = tally.evidence.getValue(cap.id).take(MAX_EVIDENCE)
                    CapabilityUse(cap, evidence, tally.classesPer.getValue(cap.id).size)
                }.sortedWith(compareByDescending<CapabilityUse> { it.capability.risk }.thenBy { it.capability.id })
        return ScanResult(
            fileName = file.name,
            sizeBytes = length,
            sha256 = ArchiveIo.sha256(file),
            manifest = manifest,
            classesScanned = tally.scanned,
            classesUnreadable = tally.unreadable,
            capabilities = capabilities,
            findings = ScanFindings.of(file, tally, manifest, capabilities.map { it.capability.id }.toSet()),
            hosts = tally.hosts.toList(),
            limits = limits(tally),
        )
    }

    private fun unreadable(
        file: File,
        length: Long,
        reason: String,
    ) = ScanResult(
        fileName = file.name,
        sizeBytes = length,
        sha256 = null,
        manifest = null,
        classesScanned = 0,
        classesUnreadable = 0,
        capabilities = emptyList(),
        findings = emptyList(),
        hosts = emptyList(),
        limits = listOf("The file was not scanned."),
        unreadableReason = reason,
    )

    private fun readEntries(
        zip: ZipFile,
        tally: Tally,
    ) {
        var entries = 0
        val enumeration = zip.entries()
        while (enumeration.hasMoreElements()) {
            if (++entries > MAX_ENTRIES) {
                tally.limits += "Stopped after $MAX_ENTRIES entries; the rest of the archive was not read."
                return
            }
            val entry = enumeration.nextElement()
            if (!entry.isDirectory && !readEntry(zip, entry, tally)) return
        }
    }

    /** Files one entry into [tally]. Returns false when the overall byte budget is spent and reading must stop. */
    private fun readEntry(
        zip: ZipFile,
        entry: ZipEntry,
        tally: Tally,
    ): Boolean {
        val name = entry.name
        if (name.startsWith("/") || name.contains('\\') || name.split('/').any { it == ".." }) tally.traversal++
        return when {
            name.endsWith(".class") -> readClass(zip, entry, tally)
            nativeSuffixes.any { name.endsWith(it, ignoreCase = true) } -> tally.nativeLibs.add(name)
            name.endsWith(".jar", ignoreCase = true) -> tally.nestedJars.add(name)
            name.startsWith(SERVICES) && name.length > SERVICES.length -> tally.services.add(name)
            else -> true
        }
    }

    /** Returns false when the overall byte budget is spent and reading must stop. */
    private fun readClass(
        zip: ZipFile,
        entry: ZipEntry,
        tally: Tally,
    ): Boolean {
        if (entry.name.startsWith("ai/rever/boss/plugin/api/")) tally.bundlesApi++
        val bytes = ArchiveIo.boundedEntry(zip, entry)
        return when {
            bytes == null -> {
                tally.unreadable++
                tally.limits += "A class over $MAX_CLASS_BYTES bytes, or with corrupt compressed data, was skipped."
                true
            }

            tally.totalBytes + bytes.size > MAX_TOTAL_BYTES -> {
                tally.limits += "Stopped after reading $MAX_TOTAL_BYTES bytes of classes."
                false
            }

            else -> {
                tally.totalBytes += bytes.size
                accept(tally, bytes)
                true
            }
        }
    }

    private fun accept(
        tally: Tally,
        bytes: ByteArray,
    ) {
        val info =
            try {
                ConstantPoolReader.read(bytes)
            } catch (_: MalformedClassException) {
                tally.unreadable++
                return
            }
        tally.scanned++
        tally.classNames += info.name
        for (hit in CapabilityCatalog.detect(info)) tally.record(hit)
        tally.collectHosts(info)
    }

    private fun limits(tally: Tally): List<String> =
        buildList {
            add("Static scan of ${tally.scanned} class constant pool(s); code built at run time is not visible.")
            if (tally.unreadable > 0) add("${tally.unreadable} class file(s) could not be read.")
            if (tally.nestedJars.isNotEmpty()) add("Nested JARs were not opened.")
            addAll(tally.limits.distinct())
        }
}

/** The byte-level reads: one bounded entry, and a streaming digest of the whole file. */
private object ArchiveIo {
    private const val BUFFER = 16 * 1024

    /** One entry's bytes, or null when it is over the class limit or its compressed data is corrupt. */
    fun boundedEntry(
        zip: ZipFile,
        entry: ZipEntry,
    ): ByteArray? =
        try {
            zip.getInputStream(entry).use { readBounded(it) }
        } catch (_: IOException) {
            null
        }

    /** Reads at most the class limit and returns null when the stream holds more, trusting no declared size. */
    private fun readBounded(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var total = 0
        var overLimit = false
        while (!overLimit) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            overLimit = total > PluginJarScanner.MAX_CLASS_BYTES
            if (!overLimit) out.write(buffer, 0, n)
        }
        return if (overLimit) null else out.toByteArray()
    }

    fun sha256(file: File): String? =
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(file.inputStream(), digest).use { it.copyTo(OutputStream.nullOutputStream()) }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: IOException) {
            null
        }
}
