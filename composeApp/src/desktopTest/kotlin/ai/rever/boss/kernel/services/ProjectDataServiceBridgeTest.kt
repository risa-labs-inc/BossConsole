package ai.rever.boss.kernel.services

import ai.rever.boss.components.plugin.panels.left_top.ProjectState
import ai.rever.boss.ipc.auth.IpcTlsIdentity
import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenClientInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.ProjectDataServiceGrpcKt
import ai.rever.boss.ipc.proto.services.ProjectPathRequest
import ai.rever.boss.ipc.proto.services.ProjectProto
import ai.rever.boss.plugin.api.ProjectData
import ai.rever.boss.plugin.api.ProjectDataProvider
import ai.rever.boss.window.Project
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.NettyServerBuilder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The project-data bridge, exercised over a real gRPC server (BossConsole#53).
 *
 * Before this bridge checked identity, any process able to open a connection to the kernel IPC
 * server - not only the plugins the host itself loaded - could watch every recently opened
 * project's path with no credential at all, and could force an arbitrary window to switch its
 * open project via `selectProject`, which also broadcasts a project-change event to every
 * installed plugin.
 *
 * Two routing guarantees, unchanged by the identity gating and still exercised (now over an
 * authenticated channel rather than a direct call):
 *
 * 1. `selectProject` confines provider mutation to the UI dispatcher, which lets
 *    project-change announcement stay lock-free.
 *
 * 2. BossConsole#520: `watchRecentProjects` must read [ProjectState] - the process-wide
 *    singleton - rather than a per-window [ProjectDataProvider]'s own mirror, because that
 *    mirror stops updating the moment its owning window disposes it
 *    (`ProjectDataProviderImpl.dispose`). Reading it instead would freeze a KERNEL client at
 *    whatever the window last saw.
 */
class ProjectDataServiceBridgeTest {
    private val tls = IpcTlsIdentity.create()
    private lateinit var tokenRegistry: ProcessTokenRegistry
    private lateinit var server: Server
    private lateinit var authenticatedChannel: ManagedChannel
    private lateinit var anonymousChannel: ManagedChannel
    private lateinit var authenticated: ProjectDataServiceGrpcKt.ProjectDataServiceCoroutineStub
    private lateinit var anonymous: ProjectDataServiceGrpcKt.ProjectDataServiceCoroutineStub

    private fun startServer(
        provider: ProjectDataProvider,
        uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
    ) {
        tokenRegistry = ProcessTokenRegistry()
        server =
            NettyServerBuilder
                .forPort(0)
                .sslContext(tls.serverContext())
                .intercept(ProcessIdentityInterceptor(tokenRegistry))
                .addService(ProjectDataServiceBridge(provider, uiDispatcher))
                .build()
                .start()
        authenticatedChannel =
            NettyChannelBuilder
                .forAddress("localhost", server.port)
                .sslContext(IpcTlsIdentity.clientContext(tls.certificateBase64))
                .overrideAuthority(IpcTlsIdentity.AUTHORITY)
                .intercept(ProcessTokenClientInterceptor(tokenRegistry.issue(CALLER)))
                .build()
        anonymousChannel =
            NettyChannelBuilder
                .forAddress(
                    "localhost",
                    server.port,
                ).sslContext(IpcTlsIdentity.clientContext(tls.certificateBase64))
                .overrideAuthority(IpcTlsIdentity.AUTHORITY)
                .build()
        authenticated = ProjectDataServiceGrpcKt.ProjectDataServiceCoroutineStub(authenticatedChannel)
        anonymous = ProjectDataServiceGrpcKt.ProjectDataServiceCoroutineStub(anonymousChannel)
    }

    @AfterTest
    fun tearDown() {
        if (::authenticatedChannel.isInitialized) {
            authenticatedChannel.shutdownNow()
            authenticatedChannel.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        if (::anonymousChannel.isInitialized) {
            anonymousChannel.shutdownNow()
            anonymousChannel.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        if (::server.isInitialized) {
            server.shutdownNow()
            server.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `every RPC is refused with no credential, and never reaches the provider`() =
        runBlocking {
            val provider = RecordingProjectDataProvider()
            startServer(provider)

            assertRefused { anonymous.watchRecentProjects(Empty.getDefaultInstance()).take(1).toList() }
            assertRefused {
                anonymous.updateRecentProjects(
                    ProjectProto
                        .newBuilder()
                        .setName("n")
                        .setPath("/tmp/x")
                        .setLastOpened(1L)
                        .build(),
                )
            }
            assertRefused {
                anonymous.removeRecentProject(ProjectPathRequest.newBuilder().setPath("/tmp/x").build())
            }
            assertRefused {
                anonymous.selectProject(
                    ProjectProto
                        .newBuilder()
                        .setName("n")
                        .setPath("/tmp/x")
                        .setLastOpened(1L)
                        .build(),
                )
            }

            assertTrue(provider.calls.isEmpty(), "a refused call must never reach the provider")
        }

    @Test
    fun `an authenticated caller can update, remove and select`() =
        runBlocking {
            val provider = RecordingProjectDataProvider()
            startServer(provider)

            authenticated.updateRecentProjects(
                ProjectProto
                    .newBuilder()
                    .setName("n")
                    .setPath("/tmp/x")
                    .setLastOpened(1L)
                    .build(),
            )
            authenticated.removeRecentProject(ProjectPathRequest.newBuilder().setPath("/tmp/x").build())
            authenticated.selectProject(
                ProjectProto
                    .newBuilder()
                    .setName("Selected")
                    .setPath("/tmp/boss-bridge-selected")
                    .setLastOpened(42L)
                    .build(),
            )

            assertEquals(listOf("updateRecentProjects", "removeRecentProject", "selectProject"), provider.calls)
            assertEquals(
                ProjectData(name = "Selected", path = "/tmp/boss-bridge-selected", lastOpened = 42L),
                provider.selectedProject,
            )
        }

    @Test
    fun `revoked caller cannot invoke a unary RPC`() =
        runBlocking {
            val provider = RecordingProjectDataProvider()
            startServer(provider)

            tokenRegistry.revoke(CALLER)
            assertRefused {
                authenticated.removeRecentProject(ProjectPathRequest.newBuilder().setPath("/tmp/x").build())
            }
            assertTrue(provider.calls.isEmpty())
        }

    @Test
    fun `selectProject switches from the grpc caller to the UI dispatcher`() =
        runBlocking {
            val provider = RecordingProjectDataProvider()
            val executor =
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, UI_THREAD_NAME)
                }

            executor.asCoroutineDispatcher().use { uiDispatcher ->
                startServer(provider, uiDispatcher)

                val response =
                    authenticated.selectProject(
                        ProjectProto
                            .newBuilder()
                            .setName("Selected")
                            .setPath("/tmp/boss-bridge-selected")
                            .setLastOpened(42L)
                            .build(),
                    )

                assertEquals(Empty.getDefaultInstance(), response)
            }

            assertTrue(
                provider.selectionThread?.startsWith(UI_THREAD_NAME) == true,
                "selection ran on ${provider.selectionThread}",
            )
            assertEquals(
                ProjectData(name = "Selected", path = "/tmp/boss-bridge-selected", lastOpened = 42L),
                provider.selectedProject,
            )
        }

    @Test
    fun `watchRecentProjects reflects the process-wide ProjectState, not the per-window provider`() =
        runBlocking {
            // Seeded, not read-only: under composeApp's test-home isolation (user.home
            // redirected to a fresh build/test-home per task) the singleton's list is
            // always empty in the test JVM, so asserting against whatever it happened to
            // hold was a no-op - an implementation that merely emitted [] passed it.
            // Seeding makes the assertion check a real value. The write is still safe:
            // persistence lands in the per-run test home, not the developer's real
            // recent-projects.json, and the finally below restores the list. The seed's
            // path is a real directory because ProjectState's one-shot disk load, if it
            // still runs when the test writes the file, filters out projects whose
            // directories do not exist.
            val seedDir = Files.createTempDirectory("boss-bridge-watch-seed").toFile()
            val secondDir = Files.createTempDirectory("boss-bridge-watch-second").toFile()
            val seed = Project(name = "boss-bridge-seed", path = seedDir.absolutePath, lastOpened = 0L)
            val second = Project(name = "boss-bridge-second", path = secondDir.absolutePath, lastOpened = 0L)
            try {
                val decoy = ProjectData(name = "decoy-window-only-project", path = "/nowhere/decoy", lastOpened = 0L)
                startServer(FakeProjectDataProvider(MutableStateFlow(listOf(decoy))))

                awaitInRecentProjects(seed)
                // Observe a seeded snapshot and then a later mutation on one stream. Ignore
                // the singleton's possible late startup-load emission and reseed while waiting;
                // neither an empty stream nor the per-window decoy can satisfy these assertions.
                val emissions = mutableListOf<List<String>>()
                val collector =
                    launch {
                        authenticated
                            .watchRecentProjects(Empty.getDefaultInstance())
                            .filter { response ->
                                val awaitedPath = if (emissions.isEmpty()) seed.path else second.path
                                response.projectsList.any { it.path == awaitedPath }
                            }.take(2)
                            .collect { emissions += it.projectsList.map { p -> p.path } }
                    }
                withTimeout(5_000) {
                    while (emissions.size < 1) {
                        ProjectState.updateRecentProjects(seed)
                        delay(10)
                    }
                }
                assertTrue(seed.path in emissions[0], "first observed snapshot must contain the global seed")
                assertNotEquals(listOf(decoy.path), emissions.getOrNull(0), "emission came from the per-window mirror")

                ProjectState.updateRecentProjects(second)
                withTimeout(5_000) {
                    while (emissions.size < 2) {
                        ProjectState.updateRecentProjects(second)
                        delay(10)
                    }
                }
                collector.join()
                assertTrue(
                    second.path in emissions.getOrNull(1).orEmpty(),
                    "second emission $emissions did not carry the post-mutation value; the stream is not live",
                )
            } finally {
                ProjectState.removeRecentProject(seed.path)
                ProjectState.removeRecentProject(second.path)
                seedDir.deleteRecursively()
                secondDir.deleteRecursively()
            }
        }

    @Test
    fun `revoked watcher cannot receive a later snapshot`() =
        runBlocking {
            withTimeout(10_000) {
                val seedDir = Files.createTempDirectory("boss-bridge-revoke-watch-seed").toFile()
                val seed = Project(name = "boss-bridge-revoke-seed", path = seedDir.absolutePath, lastOpened = 0L)
                try {
                    startServer(FakeProjectDataProvider(MutableStateFlow(emptyList())))
                    awaitInRecentProjects(seed)

                    supervisorScope {
                        val received = Channel<Unit>(Channel.UNLIMITED)
                        val emissions = mutableListOf<List<String>>()
                        val watching =
                            async {
                                authenticated.watchRecentProjects(Empty.getDefaultInstance()).collect {
                                    emissions += it.projectsList.map { p -> p.path }
                                    received.send(Unit)
                                }
                            }
                        try {
                            received.receive()
                            tokenRegistry.revoke(CALLER)
                            val second =
                                Project(
                                    name = "boss-bridge-revoke-second",
                                    path = "/nowhere/after-revocation",
                                    lastOpened = 0L,
                                )
                            ProjectState.updateRecentProjects(second)

                            val failure = assertFailsWith<StatusException> { watching.await() }
                            assertEquals(Status.Code.UNAUTHENTICATED, failure.status.code)
                            assertTrue(
                                emissions.none { second.path in it },
                                "revocation must prevent a later snapshot from reaching the caller",
                            )
                        } finally {
                            watching.cancel()
                            received.close()
                        }
                    }
                } finally {
                    ProjectState.removeRecentProject(seed.path)
                    ProjectState.removeRecentProject("/nowhere/after-revocation")
                    seedDir.deleteRecursively()
                }
            }
        }

    private suspend fun assertRefused(call: suspend () -> Unit) {
        val failure = assertFailsWith<StatusException> { call() }
        assertEquals(Status.Code.UNAUTHENTICATED, failure.status.code)
    }

    /**
     * Seeds [project] into the real [ProjectState] and returns once it is present. Reseeds on
     * every poll, because the singleton's one-shot disk load can run once, late, and replace
     * the whole list with the file's contents; the load happens at most once, so this loop
     * terminates.
     */
    private suspend fun awaitInRecentProjects(project: Project) {
        withTimeout(5_000) {
            while (project.path !in ProjectState.recentProjects.value.map { it.path }) {
                ProjectState.updateRecentProjects(project)
                delay(10)
            }
        }
    }

    /** Records every method it was actually asked to perform, so a refusal can be proven silent. */
    private class RecordingProjectDataProvider : ProjectDataProvider {
        val calls = CopyOnWriteArrayList<String>()
        override val recentProjects: StateFlow<List<ProjectData>> = MutableStateFlow(emptyList())
        var selectedProject: ProjectData? = null
        var selectionThread: String? = null

        override fun updateRecentProjects(project: ProjectData) {
            calls += "updateRecentProjects"
        }

        override fun removeRecentProject(projectPath: String) {
            calls += "removeRecentProject"
        }

        override fun selectProject(project: ProjectData) {
            calls += "selectProject"
            selectedProject = project
            selectionThread = Thread.currentThread().name
        }
    }

    private companion object {
        const val CALLER = "project-panel-plugin"
        const val UI_THREAD_NAME = "project-data-ui-test"
        const val SHUTDOWN_TIMEOUT_MS = 5_000L
    }
}

/** A provider whose [recentProjects] is fixed and deliberately unlike [ProjectState]'s value. */
private class FakeProjectDataProvider(
    override val recentProjects: StateFlow<List<ProjectData>>,
) : ProjectDataProvider {
    override fun updateRecentProjects(project: ProjectData) = error("not used by this test")

    override fun removeRecentProject(projectPath: String) = error("not used by this test")

    override fun selectProject(project: ProjectData) = error("not used by this test")
}
