package ai.rever.boss.ipc.auth

/** A credential is usable only with the endpoint certificate the trusted host supplied with it. */
class IpcClientCredentials(
    val certificateBase64: String,
    internal val token: String,
) {
    init {
        require(certificateBase64.length in 1..16_384) { "Invalid IPC endpoint certificate size" }
        require(token.length == 64 && token.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Invalid IPC credential"
        }
    }
}
