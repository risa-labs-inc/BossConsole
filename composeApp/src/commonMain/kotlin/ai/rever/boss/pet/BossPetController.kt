package ai.rever.boss.pet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-global task bookkeeping. Unacknowledged results are ordered by arrival and carry their task id,
 * so another task starting or finishing cannot erase a failure or misattribute a completion.
 * Producers must use distinct ids for concurrent tasks. Successful runs retain separate notices;
 * identical pending failures coalesce across runs and record their occurrence count.
 */
class BossPetController {
    private val activeTasks = mutableSetOf<String>()
    private val announcements = linkedMapOf<Long, BossPetMood>()
    private var nextAnnouncement = 0L
    private var overflowCount = 0L
    private val _mood = MutableStateFlow<BossPetMood>(BossPetMood.Idle)
    val mood: StateFlow<BossPetMood> = _mood.asStateFlow()

    @Synchronized
    fun taskStarted(id: String) {
        activeTasks.add(id)
        publish()
    }

    @Synchronized
    fun taskFinished(
        id: String,
        label: String,
        requiresAcknowledgement: Boolean = false,
    ) {
        if (!activeTasks.remove(id) && hasAnnouncement(id, label, failure = false)) return
        val sequence = nextAnnouncement++
        enqueue(sequence, BossPetMood.Completed(label, id, sequence, requiresAcknowledgement))
        publish()
    }

    @Synchronized
    fun taskFailed(
        id: String,
        label: String,
    ) {
        activeTasks.remove(id)
        val existing =
            announcements.entries.firstOrNull {
                val notice = it.value
                notice is BossPetMood.Failed && notice.taskId == id && notice.label == label
            }
        if (existing != null) {
            val notice = existing.value as BossPetMood.Failed
            existing.setValue(notice.copy(occurrences = notice.occurrences + 1))
            publish()
            return
        }
        val sequence = nextAnnouncement++
        enqueue(sequence, BossPetMood.Failed(label, id, sequence))
        publish()
    }

    /** End activity without a result message, for cancelled work or an update check finding nothing. */
    @Synchronized
    fun taskStopped(id: String) {
        activeTasks.remove(id)
        publish()
    }

    /** Retract an obsolete success without acknowledging failures or other tasks' results. */
    @Synchronized
    fun retractCompletion(
        id: String,
        label: String,
    ) {
        announcements.entries.removeAll {
            val notice = it.value
            notice is BossPetMood.Completed && notice.taskId == id && notice.label == label
        }
        publish()
    }

    @Synchronized
    fun dismissAnnouncement() {
        announcements.keys.firstOrNull()?.let {
            announcements.remove(it)
            if (it == OVERFLOW_KEY) overflowCount = 0
        }
        publish()
    }

    /** Advance successes even while other tasks run. Failures require explicit acknowledgement. */
    @Synchronized
    fun onIdleTimeout(expected: BossPetMood = _mood.value) {
        if (expected is BossPetMood.Completed && !expected.requiresAcknowledgement && _mood.value == expected) {
            dismissAnnouncement()
        }
    }

    private fun enqueue(
        sequence: Long,
        notice: BossPetMood,
    ) {
        // Reserve one of 128 slots for an explicit overflow warning rather than growing forever
        // behind an unacknowledged failure. The warning counts results whose details were omitted.
        if (announcements.size >= 127 || overflowCount > 0) {
            overflowCount++
            announcements[OVERFLOW_KEY] =
                BossPetMood.Failed("$overflowCount additional results - check BOSS", "pet-overflow", OVERFLOW_KEY)
        } else {
            announcements[sequence] = notice
        }
    }

    private fun hasAnnouncement(
        id: String,
        label: String,
        failure: Boolean,
    ): Boolean =
        announcements.values.any {
            when (it) {
                is BossPetMood.Completed -> !failure && it.taskId == id && it.label == label
                is BossPetMood.Failed -> failure && it.taskId == id && it.label == label
                else -> false
            }
        }

    private fun publish() {
        _mood.value =
            announcements.values.firstOrNull()
                ?: if (activeTasks.isEmpty()) BossPetMood.Idle else BossPetMood.Working(activeTasks.size)
    }
}

private const val OVERFLOW_KEY = -1L
