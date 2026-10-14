package ai.rever.boss.ipc

import ai.rever.boss.ipc.auth.IpcTlsIdentityTest

/** Run real encrypted RPCs and revocation after native compilation, without test-runner reflection. */
fun main() {
    IpcTlsIdentityTest().apply {
        `restored endpoint key accepts its pinned client and credential`()
        `rogue listener receives no credential or RPC despite same endpoint hostname`()
    }
    IpcAuthorizationTest().apply {
        `missing malformed unknown revoked and replaced credentials never reach registration`()
        `valid caller cannot register another process redirect its endpoint or forge its heartbeat`()
        `revocation cancels an idle stream and replacement cannot read the previous instances private state`()
        `broadcast preserves analytics subscriber labels while deriving the event source from its caller`()
    }
    println("IPC TLS, endpoint identity, authorization, and revocation smoke checks passed")
}
