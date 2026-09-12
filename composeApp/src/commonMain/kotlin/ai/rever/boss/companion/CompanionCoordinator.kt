package ai.rever.boss.companion

import ai.rever.boss.components.events.RunProcessEventBus
import ai.rever.boss.plugin.api.CustomPluginEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class CompanionCoordinator internal constructor(
    private val applicationEvents: Flow<CustomPluginEvent>,
    private val scope: CoroutineScope,
) {
    private val stateStore = CompanionStateStore()

    private val _enabled =
        kotlinx.coroutines.flow.MutableStateFlow(true)

    val enabled: kotlinx.coroutines.flow.StateFlow<Boolean> =
        _enabled.asStateFlow()

    private val _snoozed =
        kotlinx.coroutines.flow.MutableStateFlow(false)

    val snoozed: kotlinx.coroutines.flow.StateFlow<Boolean> =
        _snoozed.asStateFlow()

    private val processAdapter =
        RunProcessCompanionAdapter(
            applicationEvents = applicationEvents,
            emitProcessEvent = { event ->
                RunProcessEventBus.emit(event)
            },
        )

    private val runnerAdapter =
        RunnerCompanionAdapter()

    private var started = false
    private var processJob: Job? = null
    private var runnerJob: Job? = null
    private var stateJob: Job? = null

    fun ensureStarted() {
        if (started) return
        started = true

        processJob = processAdapter.start(scope)
        runnerJob = runnerAdapter.start(scope)

        stateJob =
            ai.rever.boss.components.events.CompanionEventBus.events
                .onEach { event ->
                    stateStore.handle(event)
                }.launchIn(scope)
    }

    fun stop() {
        processJob?.cancel()
        runnerJob?.cancel()
        stateJob?.cancel()
        processJob = null
        runnerJob = null
        stateJob = null
        processAdapter.stop()
        runnerAdapter.stop()
        started = false
    }

    fun tasks() = stateStore.tasks

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        if (!enabled) {
            _snoozed.value = false
        }
    }

    fun snooze() {
        if (_enabled.value) {
            _snoozed.value = true
        }
    }

    fun unsnooze() {
        if (_enabled.value) {
            _snoozed.value = false
        }
    }

    companion object {
        lateinit var instance: CompanionCoordinator
            private set

        fun initialize(
            applicationEvents: Flow<CustomPluginEvent>,
            scope: CoroutineScope,
        ) {
            if (!::instance.isInitialized) {
                instance = CompanionCoordinator(applicationEvents, scope)
            }
        }
    }
}
