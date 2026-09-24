package ai.rever.boss.app

import ai.rever.boss.components.dialogs.ConfirmationDialog
import ai.rever.boss.components.events.PluginActionEventBus
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** Each distinct request gets a fresh arming interval, including an identical action. */
@Composable
internal fun PluginActionApprovalDialog(
    request: PendingPluginAction,
    pendingCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    key(request) {
        var armed by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(500)
            armed = true
        }
        ConfirmationDialog(
            title = "Run this plugin action? ($pendingCount pending)",
            message = pluginActionApprovalMessage(request),
            confirmText = "Run action",
            confirmEnabled = armed,
            onDismiss = onDismiss,
            onConfirm = { if (armed) onConfirm() },
        )
    }
}

/**
 * A window's plugin action prompt: the request [queue] is showing, titled with the backlog, and
 * consumed before [onConfirmed] is handed it.
 *
 * Its own composable rather than inline in `BossAppDialogs` for two reasons. The wiring - which
 * count the title shows, and that a confirm consumes before it dispatches - is then something a
 * test can drive, where `BossAppDialogs` needs a whole `BossAppState`; inlined, reverting the
 * count to the queue's size compiled and failed nothing. And [pluginActionBacklog] returns a value,
 * so the bus count it collects invalidates the nearest restartable scope: here that is this prompt,
 * not all of `BossAppDialogs`.
 */
@Composable
internal fun PluginActionApprovalPrompt(
    queue: PluginActionApprovalQueue,
    onConfirmed: (PendingPluginAction) -> Unit,
) {
    val pending = queue.current ?: return
    PluginActionApprovalDialog(
        request = pending,
        pendingCount = pluginActionBacklog(queue),
        onDismiss = { queue.consume(pending) },
        onConfirm = confirm@{
            // Consume before dispatch; the dialog also calls onDismiss after onConfirm.
            // A stale callback must never dispatch or dismiss the next request.
            if (!queue.consume(pending)) return@confirm
            onConfirmed(pending)
        },
    )
}

/**
 * The prompt title's count: the request [queue] is showing plus every request still retained on
 * [PluginActionEventBus] - "this one, and everything queued behind it".
 *
 * The window's own queue alone cannot answer this. A window claims one request at a time
 * (`PluginActionApprovalQueue.canClaim`), so its size is 1 whenever the prompt is on screen,
 * and under a flood of links the rest are on the bus - exactly when the operator most needs to
 * see that more are coming. Collected as state so the title follows the bus as links arrive.
 *
 * Not a global total: a request another window has claimed and is showing is off the bus and in
 * that window's queue, so with two windows prompting each title leaves out the other's.
 */
@Composable
internal fun pluginActionBacklog(queue: PluginActionApprovalQueue): Int {
    val retained by PluginActionEventBus.pendingCountFlow.collectAsState()
    return queue.size + retained
}

/**
 * The prompt's body. Names the plugin handler and the action, and lists the
 * parameter KEYS the handler would receive — never their values, which are
 * attacker-chosen text that a prompt is not a safe place to render.
 */
internal fun pluginActionApprovalMessage(request: PendingPluginAction): String {
    val parameters =
        if (request.paramKeys.isEmpty()) {
            "no parameters"
        } else {
            "parameters: ${request.paramKeys.joinToString(", ")}"
        }
    return "BOSS was asked through a link to run a plugin action. It has not run. " +
        "Confirm only if you expected it:\n\n" +
        "plugin: ${request.handlerId}\n" +
        "action: ${request.action}\n" +
        parameters
}
