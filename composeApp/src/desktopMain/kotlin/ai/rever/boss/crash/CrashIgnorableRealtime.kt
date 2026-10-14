package ai.rever.boss.crash

/**
 * The supabase-kt realtime containments for [CrashHandler.isIgnorable]: narrow,
 * frame-exact matches for IllegalStateException("Websocket not yet initialized")
 * thrown inside the library's own coroutines, where the host cannot catch it.
 * Kept out of [CrashHandler] so each rule carries its evidence comment beside
 * the match, and so the crash handler stays under detekt's size gate.
 */

/**
 * supabase-kt 3.8.0 can resume scheduleRejoin during the socket reconnect delay.
 * Its unsubscribe then reads the cleared socket before subscribe can wait for
 * a connection. The separate reconnect job rejoins registered channels; this
 * stale attempt must not turn that recovery into a fatal host crash
 * (BossConsole-Releases#28).
 * Match the throwing getter and retry path, not arbitrary initialization errors
 * or direct application calls to unsubscribe before connecting.
 * The installed-SDK regression test exercises the actual delayed retry, so an
 * SDK fix or stack change requires re-evaluating this narrow containment rule.
 */
internal fun isStaleRealtimeRejoin(throwable: Throwable): Boolean {
    if (throwable !is IllegalStateException || throwable.message != "Websocket not yet initialized") return false
    val frames = throwable.stackTrace
    val realtimePackage = "io.github.jan.supabase.realtime."
    val expected =
        listOf(
            "RealtimeImpl" to "getWebsocket",
            "RealtimeChannelImpl" to "unsubscribe",
            "RealtimeChannelImpl" to "resubscribe",
            "RealtimeChannelImpl" to "scheduleRejoin",
        )
    return expected.withIndex().all { (index, frame) ->
        frames.getOrNull(index)?.let {
            it.className == realtimePackage + frame.first && it.methodName == frame.second
        } == true
    }
}

/**
 * supabase-kt 3.8.0 races its per-tick heartbeat child against disconnect():
 * disconnect() nulls the socket before cancelling the heartbeat job, so a
 * tick already in flight reads the throwing getter after the null store
 * (BossConsole#1739; upstream supabase-kt#1394, fixed but unreleased by
 * supabase-kt#1395). The exception kills only that tick's child - the
 * heartbeat loop is a SupervisorJob sibling and keeps ticking, and the
 * reconnect job rebuilds the socket on its own - so one missed heartbeat
 * must not turn that self-healing recovery into a fatal host crash.
 * sendHeartbeat is private and reachable only from the tick child, so the
 * two-frame prefix is unique to this race; match it rather than the
 * loop's lambda frames, which vary with coroutine stack recovery.
 */
internal fun isRealtimeHeartbeatRace(throwable: Throwable): Boolean {
    if (throwable !is IllegalStateException || throwable.message != "Websocket not yet initialized") return false
    val frames = throwable.stackTrace
    val realtimePackage = "io.github.jan.supabase.realtime."
    val expected =
        listOf(
            "RealtimeImpl" to "getWebsocket",
            "RealtimeImpl" to "sendHeartbeat",
        )
    return expected.withIndex().all { (index, frame) ->
        frames.getOrNull(index)?.let {
            it.className == realtimePackage + frame.first && it.methodName == frame.second
        } == true
    }
}
