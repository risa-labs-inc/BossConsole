package ai.rever.boss.ipc.auth

/** Environment handoff owned by the spawning host. The child cannot choose these credentials. */
object IpcEnvironment {
    const val PROCESS_TOKEN = "BOSS_PROCESS_TOKEN"
    const val KERNEL_CERTIFICATE = "BOSS_KERNEL_TLS_CERT"
    const val SERVER_CERTIFICATE = "BOSS_IPC_TLS_CERT"
    const val SERVER_PRIVATE_KEY = "BOSS_IPC_TLS_KEY"
    const val HOST_TOKEN = "BOSS_HOST_TOKEN"

    private val credentialNames =
        setOf(PROCESS_TOKEN, KERNEL_CERTIFICATE, SERVER_CERTIFICATE, SERVER_PRIVATE_KEY, HOST_TOKEN)

    fun removeCredentials(environment: MutableMap<String, String>) {
        environment.keys
            .filter { key -> credentialNames.any { it.equals(key, ignoreCase = true) } }
            .forEach { environment.remove(it) }
    }

    fun required(name: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: error("$name is required; start this service through the current host")
}
