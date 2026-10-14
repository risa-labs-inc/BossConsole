package ai.rever.boss.services.auth

import ai.rever.boss.services.supabase.SupabaseConfig
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Instant

private val sessionImportJob = AtomicReference<Job?>(null)

/** Signed out, or stuck with an expired session (a container restarted after its session lapsed). */
internal fun wantsSessionImport(status: SessionStatus): Boolean =
    status is SessionStatus.NotAuthenticated || status is SessionStatus.RefreshFailure

internal fun hasLiveSession(
    session: UserSession?,
    now: Instant = Clock.System.now(),
): Boolean = session != null && session.expiresAt > now

internal actual fun startSessionFileImport(scope: CoroutineScope) {
    val path = SessionFileImporter.pathFromEnvironment() ?: return
    // isSignedIn and adopt resolve the client per call; the status flow is read once when the job starts.
    val importer =
        SessionFileImporter(
            path = path,
            isSignedIn = { hasLiveSession(SupabaseConfig.client.auth.currentSessionOrNull()) },
            adopt = { refreshToken ->
                val auth = SupabaseConfig.client.auth
                val session = auth.refreshSession(refreshToken)
                auth.importSession(session, source = SessionSource.External)
            },
            failureStatus = { (it as? RestException)?.statusCode },
        )
    val job =
        // Off the caller's (main) dispatcher: the loop does file I/O and a network round trip.
        scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val status = SupabaseConfig.client.auth.sessionStatus
            importer.run(status.map(::wantsSessionImport))
        }
    while (true) {
        val current = sessionImportJob.get()
        if (current != null && !current.isCompleted) {
            job.cancel()
            return
        }
        if (sessionImportJob.compareAndSet(current, job)) break
    }
    BossLogger.forComponent("SessionFileImporter").info(LogCategory.AUTH, "Session import hook enabled")
    job.start()
}
