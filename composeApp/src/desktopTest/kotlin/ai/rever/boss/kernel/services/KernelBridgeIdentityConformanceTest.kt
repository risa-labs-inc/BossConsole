package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenClientInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.kernel.ui.RemoteUiSurfaceRegistry
import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.DirectoryPickerProvider
import ai.rever.boss.plugin.api.DownloadDataProvider
import ai.rever.boss.plugin.api.GitDataProvider
import ai.rever.boss.plugin.api.LogDataProvider
import ai.rever.boss.plugin.api.NotificationProvider
import ai.rever.boss.plugin.api.PanelEventProvider
import ai.rever.boss.plugin.api.PerformanceDataProvider
import ai.rever.boss.plugin.api.ProjectDataProvider
import ai.rever.boss.plugin.api.RoleManagementProvider
import ai.rever.boss.plugin.api.RunConfigurationDataProvider
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.api.SupabaseDataProvider
import com.google.protobuf.Message
import io.grpc.BindableService
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.MethodDescriptor
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.ClientCalls
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Conformance harness for the caller-identity gate on every kernel gRPC service bridge
 * (BossConsole#53).
 *
 * ## What the per-bridge suites could not catch
 *
 * Each bridge has its own test class, and each of those lists the RPCs it refuses an anonymous
 * caller for, one `@Test` at a time. That shape has two blind spots, and this file exists for
 * exactly those two:
 *
 * - **A copy-paste slip that also refuses AUTHENTICATED callers.** A bridge whose gate is applied
 *   to the wrong value, or applied twice, still refuses every anonymous call, so every
 *   "anonymous is refused" assertion keeps passing while the RPC is unusable in production.
 * - **A new RPC that is never gated.** Nothing enumerates a bridge's RPCs, so an RPC added later
 *   is covered only if somebody remembers to write a test for it.
 *
 * Both are closed by deriving the work from the service descriptor instead of from a hand-written
 * list: [allRpcs] reads every method out of `bindService()`, so an RPC added to any bridge below is
 * exercised here the moment it exists, with no test to update.
 *
 * ## How a refusal is produced without a credential
 *
 * The transport interceptor rejects a missing or revoked token with `UNAUTHENTICATED` before a
 * bridge ever runs, so an anonymous caller cannot reach the bridge-level gate over the normal
 * wiring. That gate is therefore defence in depth, and this harness tests it as such: the
 * "anonymous" server below is deliberately built **without** [ProcessIdentityInterceptor], so the
 * gRPC context carries neither `AUTHENTICATED_PROCESS_ID` nor `CURRENT_IDENTITY` and the bridge's
 * own `authenticatedCallerOrRefuse` is what answers. `PERMISSION_DENIED` here is the bridge's
 * refusal, not the transport's.
 *
 * ## Hermetic, and bounded
 *
 * Providers are JDK dynamic proxies that record the call and throw if they are ever reached, so a
 * refusal that still reached a provider fails rather than passing quietly - the same assertion the
 * per-bridge suites make, obtained without writing sixteen fakes. The only sockets are loopback
 * channels to servers inside this JVM. No network, no other process, no `~/.boss`, no sleep, and no
 * virtual time needed: every call here is request/response.
 *
 * Every call carries a [CALL_DEADLINE_MS] deadline. That is not a sleep and not a race - it is what
 * makes the suite terminate. An RPC that gets past the gate is expected to fail against the
 * unreachable provider, but not all of them do: `ProjectDataServiceBridge.watchRecentProjects`
 * deliberately reads the process-wide `ProjectState.recentProjects` rather than its provider (see
 * its KDoc), so past the gate it collects a source that never completes. Without a bound that one
 * call parks the whole suite forever. With it, the call resolves as `DEADLINE_EXCEEDED`, which the
 * "admits a verified caller" assertion treats exactly as it should: a status that is not
 * `PERMISSION_DENIED`, i.e. the gate let the caller through.
 *
 * ## Why "admits a verified caller" asserts NOT PERMISSION_DENIED rather than OK
 *
 * Because the throwing proxy is not invisible to the code under test. A refusal path that reads the
 * provider - to enrich its own log line, say - throws while building that line, so the caller sees
 * `UNKNOWN` instead of the `PERMISSION_DENIED` the bridge actually decided on.
 * `RunConfigServiceBridge.execute` does exactly this: `knownConfigurationOrRefuse` correctly refuses
 * a default request, then evaluates `provider.isScanning.value` for its warning map, and the
 * AssertionError from the proxy wins the race to the wire. Asserting `OK` would therefore be
 * asserting something about this file's stand-in rather than about the bridge. Asserting
 * `not PERMISSION_DENIED` still catches the failure mode this test exists for, which is a gate that
 * refuses a caller it just verified.
 *
 * ## What this harness deliberately does not assert, and why
 *
 * Five things, each recorded in a named map with a reason rather than skipped quietly, so that none
 * of them can rot into a silent hole. Four of them are enforced by a test that fails when the
 * recorded set drifts:
 *
 * - **Mid-stream revocation.** That property belongs to [ProcessIdentityInterceptor], which
 *   re-checks the principal on every `sendMessage`, and it is already asserted per streaming bridge
 *   (`ActiveTabsServiceBridgeTest`, `DownloadServiceBridgeTest`, `GitServiceBridgeTest` and
 *   `LogServiceBridgeTest` each carry a "revoked watcher cannot receive a later snapshot" test). It
 *   is not duplicated here because reaching a mid-stream revocation needs a provider that actually
 *   emits, which is a per-bridge fake rather than the shared unreachable proxy this harness is
 *   built on.
 * - **RPCs whose shape the driver cannot exercise** - [UNDRIVABLE].
 * - **RPCs an anonymous caller is not refused for** - [ADMITS_ANONYMOUS].
 * - **RPCs a verified caller is refused for anyway** - [REFUSES_VERIFIED].
 * - **Bridges that are ungated** - [AWAITING_GATE] and [INTENTIONALLY_UNGATED].
 */
class KernelBridgeIdentityConformanceTest {
    private lateinit var tokenRegistry: ProcessTokenRegistry
    private lateinit var anonymousServer: Server
    private lateinit var anonymousChannel: ManagedChannel
    private lateinit var identityServer: Server
    private lateinit var identityChannel: ManagedChannel

    /** Provider methods that were reached. A refusal must leave this empty. */
    private val reachedProviders = mutableListOf<String>()

    @BeforeTest
    fun startServers() {
        tokenRegistry = ProcessTokenRegistry()
        // No interceptor: the bridge's own gate is what answers, with no identity in context.
        anonymousServer =
            ServerBuilder
                .forPort(0)
                .addEveryBridge()
                .build()
                .start()
        anonymousChannel =
            ManagedChannelBuilder.forAddress("localhost", anonymousServer.port).usePlaintext().build()

        // The production wiring, for the half that must NOT be refused.
        identityServer =
            ServerBuilder
                .forPort(0)
                .intercept(ProcessIdentityInterceptor(tokenRegistry))
                .addEveryBridge()
                .build()
                .start()
        identityChannel =
            ManagedChannelBuilder
                .forAddress("localhost", identityServer.port)
                .usePlaintext()
                .intercept(ProcessTokenClientInterceptor(tokenRegistry.issue(CALLER)))
                .build()
    }

    @AfterTest
    fun stopServers() {
        listOf(anonymousChannel, identityChannel).forEach {
            it.shutdownNow()
            it.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        listOf(anonymousServer, identityServer).forEach {
            it.shutdownNow()
            it.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    // ------------------------------------------------------------------
    // The two failure modes this harness exists to catch
    // ------------------------------------------------------------------

    @Test
    fun `every bridge RPC refuses an anonymous caller with PERMISSION_DENIED`() {
        val admitted = mutableListOf<String>()
        val wrongStatus = mutableListOf<String>()

        gatedBridges().forEach { bridge ->
            // Exempt RPCs are recorded in ADMITS_ANONYMOUS, each with a reason.
            drivableRpcs(bridge).filterNot { it.fullName in ADMITS_ANONYMOUS }.forEach { rpc ->
                val code = invoke(anonymousChannel, bridge, rpc).code
                when (code) {
                    Status.Code.PERMISSION_DENIED -> Unit
                    Status.Code.OK -> admitted += rpc.fullName
                    else -> wrongStatus += "${rpc.fullName} -> $code"
                }
            }
        }

        assertEquals(
            emptyList(),
            admitted,
            "these RPCs answered an anonymous caller outright, so they have no identity gate at all",
        )
        assertEquals(
            emptyList(),
            wrongStatus,
            "these RPCs refused an anonymous caller, but with the wrong status; only PERMISSION_DENIED " +
                "distinguishes the bridge's identity gate from a transport or handler failure",
        )
        assertEquals(
            emptyList(),
            reachedProviders,
            "a refused call must not reach the provider behind the bridge",
        )
    }

    @Test
    fun `every bridge RPC admits a caller with a verified identity`() {
        val refused = mutableListOf<String>()

        gatedBridges().forEach { bridge ->
            // Exempt RPCs are recorded in REFUSES_VERIFIED, each with a reason.
            drivableRpcs(bridge).filterNot { it.fullName in REFUSES_VERIFIED }.forEach { rpc ->
                if (invoke(identityChannel, bridge, rpc).code == Status.Code.PERMISSION_DENIED) {
                    refused += rpc.fullName
                }
            }
        }

        assertEquals(
            emptyList(),
            refused,
            "these RPCs refused a caller that presented a valid process credential",
        )
    }

    // ------------------------------------------------------------------
    // The ungated bridges, and the ways that record can rot
    // ------------------------------------------------------------------

    @Test
    fun `no ungated bridge is unaccounted for`() {
        val ungated = bridges().filterNot { refusesAnonymously(it) }.map { it.simpleName }.toSet()

        assertEquals(
            emptySet(),
            ungated - recordedUngatedBridges(),
            "these bridges refused nothing for an anonymous caller and are not recorded anywhere, so " +
                "a new bridge has been added without a gate or a decision written down about it",
        )
    }

    @Test
    fun `no bridge recorded as ungated has since been gated`() {
        val gated = bridges().filter { refusesAnonymously(it) }.map { it.simpleName }.toSet()

        assertEquals(
            emptySet(),
            recordedUngatedBridges().intersect(gated),
            "these bridges now refuse anonymous callers, so they are converted and must come out of " +
                "the record - a list that only grows stops describing anything",
        )
    }

    @Test
    fun `every recorded bridge says where its conversion is tracked or why it needs none`() {
        assertEquals(
            emptySet(),
            (AWAITING_GATE + INTENTIONALLY_UNGATED).filterValues { it.isBlank() }.keys,
            "every entry must either name a tracking PR or issue, or say why no gate is needed",
        )
    }

    @Test
    fun `no bridge is recorded in two places`() {
        assertEquals(
            emptySet(),
            AWAITING_GATE.keys.intersect(INTENTIONALLY_UNGATED.keys),
            "a bridge cannot be both waiting for a gate and one that must never have one",
        )
    }

    // ------------------------------------------------------------------
    // The coverage guards, and the boundary of what "covered" means
    // ------------------------------------------------------------------

    @Test
    fun `the harness covers the whole bridge package`() {
        // Guards the direction the tests above cannot: those only prove something about the bridges
        // that ARE registered, so a bridge quietly dropped from the registry would make every one of
        // them pass while covering less. Pinned against the count of *ServiceBridge.kt files in this
        // package, so adding a bridge fails here until it is registered - which is the intended
        // prompt.
        assertEquals(
            EXPECTED_BRIDGE_COUNT,
            bridges().size,
            "the registry and the package have diverged; register the new bridge (and give it a " +
                "gate or a record entry) rather than adjusting this number alone",
        )
        assertEquals(bridges().size, bridges().map { it.simpleName }.toSet().size, "duplicate bridge entry")
    }

    @Test
    fun `every RPC this harness cannot drive is pinned with a reason`() {
        // The other direction of the same idea: the refusal tests only make a claim about the RPCs
        // they actually drive, so an RPC of a shape the driver skips would be covered by nothing.
        // Pinning the exact set turns "silently skipped" into "explicitly decided".
        val found = bridges().flatMap { undrivableRpcs(it) }.map { it.fullName }.toSet()

        assertEquals(
            UNDRIVABLE.keys,
            found,
            "the set of RPC shapes this harness cannot drive has changed; drive the new RPC, or " +
                "record why it cannot be driven and where its identity gate is asserted instead",
        )
        assertEquals(
            emptySet(),
            UNDRIVABLE.filterValues { it.isBlank() }.keys,
            "every pinned RPC must say why the harness cannot drive it",
        )
    }

    @Test
    fun `every RPC exempt from a refusal assertion is real and says why`() {
        // An exemption is the one thing a reader cannot re-derive from the code, so it is held to
        // the same standard as a record entry: it must name a live RPC, and it must say why.
        val known = bridges().flatMap { allRpcs(it) }.map { it.fullName }.toSet()
        val exemptions = ADMITS_ANONYMOUS + REFUSES_VERIFIED

        assertEquals(
            emptySet(),
            exemptions.keys - known,
            "these exemptions name an RPC that no longer exists, so they are stale and must go",
        )
        assertEquals(
            emptySet(),
            exemptions.filterValues { it.isBlank() }.keys,
            "every exemption must say why the assertion cannot be made",
        )
        assertEquals(
            emptySet(),
            ADMITS_ANONYMOUS.keys.intersect(REFUSES_VERIFIED.keys),
            "an RPC cannot be exempt from both assertions; that would exempt it from coverage entirely",
        )
        assertEquals(
            emptySet(),
            exemptions.keys.intersect(UNDRIVABLE.keys),
            "an RPC cannot be both undrivable and exempt",
        )
    }

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    /** Every RPC of [bridge], read from the generated service descriptor rather than a list. */
    private fun allRpcs(bridge: Bridge): List<Rpc> =
        bridge.service.bindService().methods.map { method ->
            val descriptor = method.methodDescriptor
            Rpc(
                name = descriptor.fullMethodName.substringAfterLast('/'),
                fullName = descriptor.fullMethodName,
                descriptor = descriptor,
            )
        }

    /** The RPCs [invoke] can drive. */
    private fun drivableRpcs(bridge: Bridge): List<Rpc> = allRpcs(bridge).filter { drivable(it.descriptor) }

    /** The RPCs it cannot, i.e. everything [UNDRIVABLE] has to account for. */
    private fun undrivableRpcs(bridge: Bridge): List<Rpc> = allRpcs(bridge).filterNot { drivable(it.descriptor) }

    private fun drivable(descriptor: MethodDescriptor<*, *>): Boolean =
        descriptor.type == MethodDescriptor.MethodType.UNARY ||
            descriptor.type == MethodDescriptor.MethodType.SERVER_STREAMING

    /**
     * A default-constructed request for [rpcName], taken from the bridge's own override.
     *
     * The descriptor carries marshallers but not the message class, so the type comes from the
     * generated method the bridge overrides. The name is matched ignoring case, because the proto
     * RPC is `GetUserSecrets` where the Kotlin method is `getUserSecrets`.
     */
    private fun defaultRequest(
        bridge: Bridge,
        rpcName: String,
    ): Message {
        val kotlinName = rpcName.replaceFirstChar { it.lowercase() }
        val method =
            bridge.service.javaClass.methods.firstOrNull { candidate ->
                candidate.name.equals(kotlinName, ignoreCase = true) &&
                    candidate.parameterTypes.isNotEmpty() &&
                    Message::class.java.isAssignableFrom(candidate.parameterTypes[0])
            } ?: error("no request type for ${bridge.simpleName}.$rpcName")
        return method.parameterTypes[0].getMethod("getDefaultInstance").invoke(null) as Message
    }

    /**
     * Drives one RPC over [channel] and reports the status it settled on.
     *
     * `ClientCalls` rather than the generated stub because it takes the descriptor directly, so one
     * call site covers every bridge without a `when` over sixteen stub types.
     */
    private fun invoke(
        channel: Channel,
        bridge: Bridge,
        rpc: Rpc,
    ): Status {
        @Suppress("UNCHECKED_CAST")
        val descriptor = rpc.descriptor as MethodDescriptor<Message, Message>
        val request = defaultRequest(bridge, rpc.name)
        val options = CallOptions.DEFAULT.withDeadlineAfter(CALL_DEADLINE_MS, TimeUnit.MILLISECONDS)
        return try {
            when (descriptor.type) {
                MethodDescriptor.MethodType.UNARY -> {
                    ClientCalls.blockingUnaryCall(channel, descriptor, options, request)
                }

                MethodDescriptor.MethodType.SERVER_STREAMING -> {
                    // Drained rather than only opened: the gate on a streaming RPC is evaluated when
                    // the flow is collected, which does not happen until the response side is read.
                    val stream = ClientCalls.blockingServerStreamingCall(channel, descriptor, options, request)
                    while (stream.hasNext()) {
                        stream.next()
                    }
                }

                else -> {
                    error("${rpc.fullName} is not drivable by this harness")
                }
            }
            Status.OK
        } catch (t: StatusRuntimeException) {
            Status.fromThrowable(t)
        }
    }

    /** A bridge that refuses at least one RPC to an anonymous caller is treated as gated. */
    private fun refusesAnonymously(bridge: Bridge): Boolean =
        drivableRpcs(bridge).any { invoke(anonymousChannel, bridge, it).code == Status.Code.PERMISSION_DENIED }

    // ------------------------------------------------------------------
    // The bridges, and the stand-ins behind them
    // ------------------------------------------------------------------

    /** `ServerBuilder.addService` registers one service per call, so the registry is walked here. */
    private fun ServerBuilder<*>.addEveryBridge(): ServerBuilder<*> {
        bridges().forEach { addService(it.service) }
        return this
    }

    /**
     * Every kernel service bridge, with a provider that fails if it is ever reached.
     *
     * [PluginUIServiceBridge] and [ContextMenuServiceBridge] take no provider; the former is handed
     * a fresh [RemoteUiSurfaceRegistry] rather than the process-wide `shared` one, so a test run
     * cannot reserve surface names in the host's own registry.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher below
    private fun bridges(): List<Bridge> =
        listOf(
            Bridge("ActiveTabsServiceBridge", ActiveTabsServiceBridge(provider(ActiveTabsProvider::class.java))),
            Bridge("ContextMenuServiceBridge", ContextMenuServiceBridge()),
            Bridge(
                "DirectoryPickerServiceBridge",
                DirectoryPickerServiceBridge(provider(DirectoryPickerProvider::class.java)),
            ),
            Bridge("DownloadServiceBridge", DownloadServiceBridge(provider(DownloadDataProvider::class.java))),
            Bridge("GitServiceBridge", GitServiceBridge(provider(GitDataProvider::class.java))),
            Bridge("LogServiceBridge", LogServiceBridge(provider(LogDataProvider::class.java))),
            Bridge("NotificationServiceBridge", NotificationServiceBridge(provider(NotificationProvider::class.java))),
            Bridge("PanelEventServiceBridge", PanelEventServiceBridge(provider(PanelEventProvider::class.java))),
            Bridge("PerformanceServiceBridge", PerformanceServiceBridge(provider(PerformanceDataProvider::class.java))),
            Bridge("PluginUIServiceBridge", PluginUIServiceBridge(RemoteUiSurfaceRegistry())),
            Bridge(
                "ProjectDataServiceBridge",
                ProjectDataServiceBridge(provider(ProjectDataProvider::class.java), UnconfinedTestDispatcher()),
            ),
            Bridge(
                "RoleManagementServiceBridge",
                RoleManagementServiceBridge(provider(RoleManagementProvider::class.java)),
            ),
            Bridge(
                "RunConfigServiceBridge",
                RunConfigServiceBridge(provider(RunConfigurationDataProvider::class.java)),
            ),
            Bridge("SecretServiceBridge", SecretServiceBridge(provider(SecretDataProvider::class.java))),
            Bridge("SplitViewServiceBridge", SplitViewServiceBridge(provider(SplitViewOperations::class.java))),
            Bridge("SupabaseServiceBridge", SupabaseServiceBridge(provider(SupabaseDataProvider::class.java))),
        )

    /** The bridges expected to refuse, i.e. everything not recorded as ungated. */
    private fun gatedBridges(): List<Bridge> = bridges().filterNot { it.simpleName in recordedUngatedBridges() }

    private fun recordedUngatedBridges(): Set<String> = AWAITING_GATE.keys + INTENTIONALLY_UNGATED.keys

    /**
     * A stand-in for a bridge's provider that records the call and throws.
     *
     * Throwing rather than returning a benign value is the point: it makes "the gate let this
     * through" indistinguishable from "the provider was reached", which is the assertion every
     * refusal test in this package already makes. The one cost is documented on the class: a
     * refusal path that reads the provider for its log line turns into `UNKNOWN` on the wire.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> provider(type: Class<T>): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            reachedProviders += method.name
            throw AssertionError("provider ${type.simpleName}.${method.name} was reached")
        } as T

    private data class Bridge(
        val simpleName: String,
        val service: BindableService,
    )

    private data class Rpc(
        val name: String,
        val fullName: String,
        val descriptor: MethodDescriptor<*, *>,
    )

    companion object {
        private const val CALLER = "ai.rever.boss.plugin.dynamic.conformance-caller"
        private const val SHUTDOWN_TIMEOUT_MS = 5_000L

        /**
         * Bound on every call. Generous against a refusal, which is produced synchronously before
         * the handler suspends, and only ever consumed by a call that got past the gate and is
         * waiting on a source that does not complete.
         */
        private const val CALL_DEADLINE_MS = 3_000L

        /** `*ServiceBridge.kt` files in this package. */
        private const val EXPECTED_BRIDGE_COUNT = 16

        /**
         * Bridges with no identity gate yet, so the harness stays green while it is honest about
         * them.
         *
         * Each is tracked by BossConsole#53, the umbrella for the whole class of gap, whose own text
         * records that "the same is true of the other ~17 kernel service bridges". None of these
         * four has a dedicated conversion PR: searching open and closed PRs and issues for each
         * bridge name returns no such PR, which is why the umbrella is the tracking reference rather
         * than a per-bridge one.
         *
         * These are reported, not fixed, in the PR that added this harness. Removing an entry once
         * its bridge is converted is enforced by `no bridge recorded as ungated has since been
         * gated`.
         */
        private val AWAITING_GATE: Map<String, String> =
            mapOf(
                "DirectoryPickerServiceBridge" to "BossConsole#53",
                "NotificationServiceBridge" to "BossConsole#53",
                "PanelEventServiceBridge" to "BossConsole#53",
                "PerformanceServiceBridge" to "BossConsole#53",
            )

        /**
         * Bridges that must NOT be gated, as distinct from bridges that have not been gated yet.
         *
         * `ContextMenuServiceBridge` acknowledges and drops every request: it holds no state and
         * takes no provider, because the host does not render out-of-process context menus at all
         * (issue #30, explained in its KDoc). There is nothing for an identity check to protect, and
         * answering `OK` is the contract a plugin depends on - `ContextMenuServiceBridgeTest` pins
         * it, and asserts that same `OK` for a direct call carrying no identity at all. Adding a gate
         * here would be a behaviour change to a documented contract, not a hardening, which is why
         * this is a separate record from [AWAITING_GATE]: that list is expected to shrink to empty,
         * this one is not.
         */
        private val INTENTIONALLY_UNGATED: Map<String, String> =
            mapOf(
                "ContextMenuServiceBridge" to
                    "acknowledge-and-drop stub with no state and no provider; its contract is OK, " +
                    "pinned by ContextMenuServiceBridgeTest (issue #30)",
            )

        /**
         * RPCs this harness cannot drive, keyed by full method name, so that the gap is a recorded
         * decision rather than an omission.
         *
         * `PluginUIService/StreamUI` is bidirectional. Its identity refusal is not a first-statement
         * check that a descriptor-driven driver can reach: the bridge opens the surface from the
         * `surface_id` of the first `WidgetUpdate` via `RemoteUiSurfaceRegistry.openStream`, and that
         * answers `NotOwner` (the `PERMISSION_DENIED` path) only for a surface that **is**
         * registered. A default request against an empty registry reaches `Unregistered`, which maps
         * to `NOT_FOUND`, so this harness cannot tell a missing identity gate from an unregistered
         * surface - and asserting `NOT_FOUND` would be asserting nothing. Driving it to
         * `PERMISSION_DENIED` would need a pre-registered surface owned by a different process, which
         * is per-bridge arrangement rather than the shared stand-in this file is built on.
         *
         * It is covered instead by `PluginUIServiceBridgeTest`, which registers a real surface and
         * asserts `PERMISSION_DENIED` when a different process streams it
         * (`process A cannot impersonate process B through StreamUI`).
         */
        private val UNDRIVABLE: Map<String, String> =
            mapOf(
                "boss.ipc.v1.PluginUIService/StreamUI" to
                    "bidirectional streaming whose refusal depends on registry state; see the KDoc " +
                    "on UNDRIVABLE and PluginUIServiceBridgeTest",
            )

        /**
         * Drivable RPCs where an anonymous caller is not refused, because the gate reads the request
         * body rather than the call.
         *
         * `PluginUIService/UnregisterUI` refuses only when the named surface exists **and** belongs
         * to another process: `PluginUIServiceBridge.unregisterUI` resolves
         * `RemoteUiSurfaceRegistry.surfaceOf(surfaceId)` first and never consults the identity when
         * there is no owner to protect ("An unregistered surface has no owner to protect, so it still
         * no-ops exactly as before"). A default request names no surface, so an anonymous caller
         * correctly gets `OK` from a no-op - there is nothing to tear down. Asserting
         * `PERMISSION_DENIED` here would assert something false; accepting `OK` would assert nothing.
         *
         * Covered instead by `PluginUIServiceBridgeTest`, which registers a real surface and asserts
         * `PERMISSION_DENIED` for a caller that does not own it
         * (`process A cannot impersonate process B through UnregisterUI`).
         */
        private val ADMITS_ANONYMOUS: Map<String, String> =
            mapOf(
                "boss.ipc.v1.PluginUIService/UnregisterUI" to
                    "refuses only a surface registered to another process; a default request names " +
                    "none, so the call is a no-op rather than a refusal",
            )

        /**
         * Drivable RPCs where a verified caller is refused anyway, because a second guard on the
         * same RPC answers with the same status for a default request.
         *
         * `openFile` and `revealInFolder` run `trackedDownloadPathOrRefuse` after the identity
         * check, confining them to a path the provider is currently tracking and refusing with
         * `PERMISSION_DENIED` otherwise. A default request names no path, so the confinement guard
         * always fires and the identity gate's own verdict cannot be observed on this channel. The
         * gate is still asserted for both by the anonymous test, which fires first - see
         * `DownloadServiceBridgeTest`'s "openFile with no credential is refused before the
         * path-confinement check even runs".
         *
         * Covered by `DownloadServiceBridgeTest`, which drives real tracked paths and asserts both
         * halves: "openFile for a path this provider is not tracking is refused even for an
         * authenticated caller", "revealInFolder for a path this provider is not tracking is refused
         * even for an authenticated caller", and "openFile for a path this provider IS tracking as a
         * download reaches the provider".
         */
        private val REFUSES_VERIFIED: Map<String, String> =
            mapOf(
                "boss.ipc.v1.services.DownloadService/OpenFile" to
                    "the path-confinement guard also answers PERMISSION_DENIED, and a default " +
                    "request names no tracked path; covered by DownloadServiceBridgeTest",
                "boss.ipc.v1.services.DownloadService/RevealInFolder" to
                    "the path-confinement guard also answers PERMISSION_DENIED, and a default " +
                    "request names no tracked path; covered by DownloadServiceBridgeTest",
            )
    }
}
