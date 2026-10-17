@file:Suppress("unused", "UNUSED_VARIABLE")

package ai.rever.boss.plugin.launchpad.scan

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.PluginContext
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/*
 * Real compiled classes for the scanner to read. Each references exactly the API its name says and is never
 * run: the scanner reads constant pools, and these exist to put the right entries in them.
 */
class ExecFixture {
    fun run() {
        Runtime.getRuntime().exec("true")
        ProcessBuilder("true").start()
    }
}

class NetFixture {
    fun run() {
        URL("http://exfil.example.test/upload").openStream()
    }
}

class ListenFixture {
    fun run() {
        ServerSocket(0)
    }
}

class FileFixture {
    fun run(f: File) {
        f.delete()
        Files.write(f.toPath(), ByteArray(1))
        Files.readAllBytes(f.toPath())
    }
}

class ReflectFixture {
    fun run() {
        val c = Class.forName("java.lang.String")
        c.declaredFields.forEach { it.isAccessible = true }
    }
}

class NativeFixture {
    fun run() {
        System.loadLibrary("something")
    }
}

class EnvFixture {
    fun run(): String? = System.getenv("API_KEY")
}

class ExitFixture {
    fun run() {
        System.exit(3)
    }
}

class CredentialPathFixture {
    val where = "/home/user/.ssh/id_rsa"
}

class LoaderFixture : ClassLoader()

/** A long and a double take two pool slots each; the reference after them must still be found. */
class WideConstantsFixture {
    fun run(): Long {
        val a = 1234567890123L
        val b = 2.718281828
        val c = 9876543210987L
        Runtime.getRuntime().exec("true")
        return a + b.toLong() + c
    }
}

class HostApiFixture {
    fun run(ctx: PluginContext) {
        ctx.secretDataProvider
        ctx.applicationEventBus
        ctx.projectSearchProvider?.let { p ->
            runBlocking { p.replaceInProject("a", "b", emptyList(), false, false, false, false) }
        }
        ctx.registerMcpToolProvider(
            object : McpToolProvider {
                override val providerId = "x"

                override fun tools() = emptyList<McpToolDefinition>()
            },
        )
    }
}

/** Touches nothing the catalogue cares about. */
class BenignFixture {
    fun run(): String = listOf("a", "b").map { it.uppercase() }.joinToString("-")
}

/** Names a host in a string constant, with no capability-triggering API call at all. */
class HostOnlyFixture {
    val endpoint = "http://exfil.example.test/beacon"
}

internal object ScanFixtures {
    fun bytesOf(internalName: String): ByteArray =
        ScanFixtures::class.java.classLoader
            .getResourceAsStream("$internalName.class")!!
            .use { it.readBytes() }

    fun bytes(cls: Class<*>): ByteArray = bytesOf(cls.name.replace('.', '/'))

    fun info(cls: Class<*>): ClassInfo = ConstantPoolReader.read(bytes(cls))

    /** The class and its compiler-generated inner classes (lambdas, coroutine state machines), as a JAR holds them. */
    fun withInner(cls: Class<*>): List<ClassInfo> {
        val dir =
            File(
                cls.protectionDomain.codeSource.location
                    .toURI(),
            ).resolve(cls.packageName.replace('.', '/'))
        val prefix = cls.simpleName + "$"
        val inner =
            dir
                .listFiles { f -> f.name.startsWith(prefix) && f.extension == "class" }
                .orEmpty()
                .sortedBy { it.name }
        return listOf(info(cls)) + inner.map { ConstantPoolReader.read(it.readBytes()) }
    }

    fun ids(cls: Class<*>): Set<String> =
        withInner(cls)
            .flatMap { CapabilityCatalog.detect(it) }
            .map { it.capability.id }
            .toSet()

    /** A plugin JAR holding [classes] (and their inner classes), a manifest, and any [extra] entries. */
    fun jar(
        dir: File,
        name: String,
        classes: List<Class<*>>,
        id: String = "ai.rever.boss.plugin.dynamic.sample",
        version: String = "1.0.0",
        permissions: String = "[]",
        main: String = "ai.rever.boss.plugin.launchpad.scan.BenignFixture",
        manifest: String? =
            """{"pluginId":"$id","displayName":"Sample","version":"$version","apiVersion":"1.0.20",""" +
                """"mainClass":"$main","requiredPermissions":$permissions}""",
        extra: Map<String, ByteArray> = emptyMap(),
    ): File {
        val file = File(dir, name)
        JarOutputStream(file.outputStream()).use { out ->
            fun put(
                entry: String,
                data: ByteArray,
            ) {
                out.putNextEntry(JarEntry(entry))
                out.write(data)
                out.closeEntry()
            }
            if (manifest != null) put("META-INF/boss-plugin/plugin.json", manifest.toByteArray())
            for (cls in classes) for (info in withInner(cls)) put(info.name + ".class", bytesOf(info.name))
            extra.forEach { (n, b) -> put(n, b) }
        }
        return file
    }
}
