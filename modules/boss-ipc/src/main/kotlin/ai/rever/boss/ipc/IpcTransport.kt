package ai.rever.boss.ipc

import java.nio.file.Path
import java.util.jar.JarFile

/** The transport contract is separate from protobuf compatibility and in-process plugin versions. */
object IpcTransport {
    const val MARKER_PATH = "META-INF/boss-runtime/security-contract"
    private const val MARKER = "pinned-tls-v1;subprocess-env-v1\n"

    /**
     * The companion runtime must explicitly declare this contract after adopting pinned TLS and
     * stripping [ai.rever.boss.ipc.auth.IpcEnvironment] credentials from every unrelated subprocess.
     * This resource is deliberately NOT shipped by boss-ipc: upgrading the dependency alone cannot
     * attest to the runtime's own launch paths. Artifact signatures remain a separate trust boundary.
     */
    fun requireCompatibleRuntime(path: Path) {
        JarFile(path.toFile()).use { jar ->
            val marker =
                jar.getJarEntry(MARKER_PATH)
                    ?: error("Update the microkernel runtime: this host requires authenticated IPC transport")
            val value = jar.getInputStream(marker).use { it.readNBytes(64) }.toString(Charsets.UTF_8)
            check(value == MARKER) { "The microkernel runtime uses an unsupported IPC transport; update the runtime" }
        }
    }
}
