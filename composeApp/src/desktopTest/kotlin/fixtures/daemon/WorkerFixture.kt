package fixtures.daemon

import ai.rever.boss.plugin.api.DaemonService
import ai.rever.boss.plugin.api.DaemonServiceContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import java.io.File

/** Loaded only from the synthetic plugin JAR, including its coroutine implementation classes. */
class WorkerFixture : DaemonService {
    private lateinit var data: File

    override suspend fun start(
        context: DaemonServiceContext,
        configuration: Map<String, String>,
    ): Map<String, String> {
        data = File(context.dataDirectory)
        File(data, "started").writeText("started")
        val entered = CompletableDeferred<Unit>()
        context.scope.launch {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                File(data, "scope-drained").writeText("done")
            }
        }
        entered.await()
        check(configuration["fail"] != "true") { "fixture startup failure" }
        return mapOf("data" to data.absolutePath)
    }

    override suspend fun request(
        method: String,
        payload: String,
    ): String =
        when (method) {
            "write" -> {
                payload.also { File(data, "value").writeText(it) }
            }

            "read" -> {
                File(data, "value").readText()
            }

            "late" -> {
                WorkerLateFixture.value + ":" +
                    checkNotNull(
                        Thread.currentThread().contextClassLoader.getResource("worker-resource.txt"),
                    ).readText()
            }

            else -> {
                error("Unknown fixture method")
            }
        }

    override suspend fun stop() {
        File(data, "stopped").writeText("done")
    }
}

object WorkerLateFixture {
    val value: String = listOf("late").single()
}
