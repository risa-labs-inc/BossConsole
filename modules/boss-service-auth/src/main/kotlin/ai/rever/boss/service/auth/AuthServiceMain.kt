package ai.rever.boss.service.auth

import ai.rever.boss.ipc.ChildProcessBootstrap
import ai.rever.boss.ipc.proto.*
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Auth Service — the first service extracted to its own process (Phase 2).
 *
 * Manages:
 * - Authentication state (sign in, sign out, session)
 * - User info and permissions
 * - Admin status
 *
 * Connects to the kernel on startup, registers its manifest, and serves
 * the AuthService gRPC API.
 */
fun main() {
    val logger = LoggerFactory.getLogger("AuthServiceMain")
    logger.info("Auth Service starting...")

    val bootstrap = ChildProcessBootstrap()

    val manifest = ProcessManifest.newBuilder()
        .setProcessId(bootstrap.processId)
        .setProcessType(ProcessType.PROCESS_TYPE_SERVICE)
        .setDisplayName("Auth Service")
        .setVersion("1.0.0")
        .setMainClass("ai.rever.boss.service.auth.AuthServiceMainKt")
        .setBehaviorSpec(
            "Manages user authentication, session lifecycle, and authorization. " +
            "Handles sign-in/sign-out flows, maintains auth state, and provides " +
            "permission checking for role-based access control."
        )
        .addAllSourceFiles(listOf(
            "boss-service-auth/src/main/kotlin/ai/rever/boss/service/auth/AuthServiceMain.kt",
            "boss-service-auth/src/main/kotlin/ai/rever/boss/service/auth/AuthServiceGrpcImpl.kt",
        ))
        .addAllExposedServices(listOf("boss.ipc.v1.services.AuthService"))
        .addAllCapabilities(listOf(
            PluginCapability.newBuilder()
                .setAction("check_permission")
                .setInputSchemaJson("""{"type":"object","properties":{"permission":{"type":"string"}}}""")
                .setOutputSchemaJson("""{"type":"object","properties":{"granted":{"type":"boolean"}}}""")
                .setDescription("Check if the current user has a specific permission")
                .build(),
            PluginCapability.newBuilder()
                .setAction("get_current_user")
                .setInputSchemaJson("""{"type":"object"}""")
                .setOutputSchemaJson("""{"type":"object","properties":{"userId":{"type":"string"},"email":{"type":"string"},"isAdmin":{"type":"boolean"}}}""")
                .setDescription("Get current authenticated user info")
                .build(),
        ))
        .setHealthContract(
            HealthContract.newBuilder()
                .setHeartbeatIntervalMs(5000)
                .setStartupTimeoutMs(15000)
                .build()
        )
        .addAllRepairHints(listOf(
            RepairHint.newBuilder()
                .setFailurePattern(".*SupabaseException.*network.*")
                .setSeverity(FailureSeverity.FAILURE_SEVERITY_TRANSIENT)
                .setRepairStrategy(RepairStrategy.REPAIR_STRATEGY_RESTART)
                .setDescription("Network connectivity issue with Supabase")
                .setSuggestedFix("Check network connection and Supabase service status")
                .build(),
            RepairHint.newBuilder()
                .setFailurePattern(".*JWT.*expired.*")
                .setSeverity(FailureSeverity.FAILURE_SEVERITY_TRANSIENT)
                .setRepairStrategy(RepairStrategy.REPAIR_STRATEGY_RESET_STATE)
                .setDescription("JWT token expired, needs fresh session")
                .setSuggestedFix("Clear session cache and re-authenticate")
                .build(),
        ))
        .setStateSnapshotEnabled(true)
        .build()

    runBlocking {
        val connection = bootstrap.connect(manifest)

        // Create Supabase client — reads SUPABASE_URL and SUPABASE_ANON_KEY from env
        val supabaseClient = SupabaseAuthClient()

        // Add the auth service implementation
        val authService = AuthServiceGrpcImpl(supabaseClient)
        connection.processServer.addService(authService)

        // Start serving
        connection.startServer()
        logger.info("Auth Service running on: {}", bootstrap.processAddress)

        // Restore any previous session from Supabase token storage
        authService.restoreSession()

        // Wait for termination
        connection.awaitTermination()
    }
}
