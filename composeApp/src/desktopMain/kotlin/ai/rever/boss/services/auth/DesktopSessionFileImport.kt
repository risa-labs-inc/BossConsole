package ai.rever.boss.services.auth

import ai.rever.boss.services.supabase.SupabaseConfig
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Volatile
private var sessionImportJob: Job? = null

internal actual fun startSessionFileImport(scope: CoroutineScope) {
    val path = SessionFileImporter.pathFromEnvironment() ?: return
    if (sessionImportJob?.isActive == true) return
    val auth = SupabaseConfig.client.auth
    val importer =
        SessionFileImporter(
            path = path,
            isSignedIn = { auth.currentSessionOrNull() != null },
            adopt = { refreshToken ->
                val session = auth.refreshSession(refreshToken)
                auth.importSession(session, source = SessionSource.External)
            },
            failureStatus = { (it as? RestException)?.statusCode },
        )
    BossLogger.forComponent("SessionFileImporter").info(LogCategory.AUTH, "Session import hook enabled")
    sessionImportJob =
        scope.launch {
            importer.run(auth.sessionStatus.map { it is SessionStatus.NotAuthenticated })
        }
}
