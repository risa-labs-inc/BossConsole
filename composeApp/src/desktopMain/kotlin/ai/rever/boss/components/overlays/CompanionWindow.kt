@file:Suppress("TooManyFunctions")

package ai.rever.boss.components.overlays

import ai.rever.boss.companion.CompanionCoordinator
import ai.rever.boss.companion.CompanionTask
import ai.rever.boss.companion.CompanionTaskStatus
import ai.rever.boss.components.events.CompanionNavigationBus
import ai.rever.boss.window.BossWindowIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import java.util.prefs.Preferences
import kotlin.math.roundToInt

private data class CompanionSavedPosition(
    val deviceId: String,
    val xRatio: Double,
    val yRatio: Double,
)

private object CompanionPositionStore {
    private val preferences =
        Preferences.userRoot().node("ai.rever.boss.companion")

    private const val DEVICE_ID_KEY = "positionDeviceId"
    private const val X_RATIO_KEY = "positionXRatio"
    private const val Y_RATIO_KEY = "positionYRatio"

    fun load(): CompanionSavedPosition? {
        val deviceId = preferences.get(DEVICE_ID_KEY, null) ?: return null

        return CompanionSavedPosition(
            deviceId = deviceId,
            xRatio = preferences.getDouble(X_RATIO_KEY, 0.05),
            yRatio = preferences.getDouble(Y_RATIO_KEY, 0.05),
        )
    }

    fun save(
        deviceId: String,
        xRatio: Double,
        yRatio: Double,
    ) {
        preferences.put(DEVICE_ID_KEY, deviceId)
        preferences.putDouble(
            X_RATIO_KEY,
            xRatio.coerceIn(0.0, 1.0),
        )
        preferences.putDouble(
            Y_RATIO_KEY,
            yRatio.coerceIn(0.0, 1.0),
        )
    }
}

private fun java.awt.Window.restoreCompanionPosition() {
    val environment =
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
    val devices = environment.screenDevices

    if (devices.isEmpty()) {
        return
    }

    val saved = CompanionPositionStore.load()
    val device =
        saved?.let { position ->
            devices.firstOrNull {
                it.getIDstring() == position.deviceId
            }
        } ?: environment.defaultScreenDevice

    val configuration = device.defaultConfiguration
    val bounds = configuration.bounds
    val insets =
        java.awt.Toolkit
            .getDefaultToolkit()
            .getScreenInsets(configuration)

    val usableX = bounds.x + insets.left
    val usableY = bounds.y + insets.top
    val usableWidth =
        (bounds.width - insets.left - insets.right)
            .coerceAtLeast(1)
    val usableHeight =
        (bounds.height - insets.top - insets.bottom)
            .coerceAtLeast(1)

    val maxX =
        (usableX + usableWidth - width)
            .coerceAtLeast(usableX)
    val maxY =
        (usableY + usableHeight - height)
            .coerceAtLeast(usableY)

    val xRatio = saved?.xRatio ?: 0.05
    val yRatio = saved?.yRatio ?: 0.05

    val x =
        (usableX + (maxX - usableX) * xRatio)
            .roundToInt()
            .coerceIn(usableX, maxX)
    val y =
        (usableY + (maxY - usableY) * yRatio)
            .roundToInt()
            .coerceIn(usableY, maxY)

    setLocation(x, y)
}

private fun java.awt.Window.saveCompanionPosition() {
    val environment =
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()

    val device =
        environment.screenDevices.firstOrNull { screen ->
            screen.defaultConfiguration.bounds.contains(
                java.awt.Point(
                    x + width / 2,
                    y + height / 2,
                ),
            )
        } ?: environment.defaultScreenDevice

    val configuration = device.defaultConfiguration
    val bounds = configuration.bounds

    val insets =
        java.awt.Toolkit
            .getDefaultToolkit()
            .getScreenInsets(configuration)

    val usableX = bounds.x + insets.left
    val usableY = bounds.y + insets.top

    val usableWidth =
        (bounds.width - insets.left - insets.right - width)
            .coerceAtLeast(1)

    val usableHeight =
        (bounds.height - insets.top - insets.bottom - height)
            .coerceAtLeast(1)

    val xRatio =
        ((x - usableX).toDouble() / usableWidth)
            .coerceIn(0.0, 1.0)

    val yRatio =
        ((y - usableY).toDouble() / usableHeight)
            .coerceIn(0.0, 1.0)

    CompanionPositionStore.save(
        deviceId = device.getIDstring(),
        xRatio = xRatio,
        yRatio = yRatio,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CompanionWindow() {
    val tasks by CompanionCoordinator.instance.tasks().collectAsState()
    val enabled by CompanionCoordinator.instance.enabled.collectAsState()
    val snoozed by CompanionCoordinator.instance.snoozed.collectAsState()

    if (!enabled || snoozed || tasks.isEmpty()) {
        return
    }

    val taskList = tasks.values.toList()

    var selectedTaskId by remember(taskList.map { it.id }) {
        mutableStateOf(taskList.last().id)
    }

    val selectedTask =
        taskList.firstOrNull { it.id == selectedTaskId }
            ?: taskList.last()

    CompanionWindowFrame(
        taskList = taskList,
        selectedTask = selectedTask,
        onTaskSelected = { selectedTaskId = it },
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CompanionWindowFrame(
    taskList: List<CompanionTask>,
    selectedTask: CompanionTask,
    onTaskSelected: (String) -> Unit,
) {
    val state =
        rememberWindowState(
            width = 360.dp,
            height = 240.dp,
        )

    Window(
        onCloseRequest = {},
        state = state,
        title = "BOSS Companion",
        icon = BossWindowIcon.painter,
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        focusable = false,
        resizable = false,
    ) {
        val window = LocalAwtWindow.current

        androidx.compose.runtime.LaunchedEffect(window) {
            window?.restoreCompanionPosition()
        }

        CompanionContent(
            window = window,
            taskList = taskList,
            selectedTask = selectedTask,
            onTaskSelected = onTaskSelected,
        )
    }
}

@Composable
private fun CompanionContent(
    window: java.awt.Window?,
    taskList: List<CompanionTask>,
    selectedTask: CompanionTask,
    onTaskSelected: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .background(
                    Color(0xFF202124),
                    RoundedCornerShape(18.dp),
                ).pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        window?.moveCompanionBy(dragAmount)
                    }
                }.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompanionHeader()
        CompanionTaskList(
            tasks = taskList,
            selectedTaskId = selectedTask.id,
            onTaskSelected = onTaskSelected,
        )
        CompanionTaskResult(selectedTask)
    }
}

@Composable
private fun CompanionHeader() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "🤖 BOSS Companion",
            color = Color.White,
        )

        Button(
            onClick = {
                CompanionCoordinator.instance.snooze()
            },
        ) {
            Text("Snooze")
        }

        Button(
            onClick = {
                CompanionCoordinator.instance.setEnabled(false)
            },
        ) {
            Text("Disable")
        }
    }
}

@Composable
private fun CompanionTaskList(
    tasks: List<CompanionTask>,
    selectedTaskId: String,
    onTaskSelected: (String) -> Unit,
) {
    tasks.forEach { task ->
        CompanionTaskRow(
            task = task,
            selected = task.id == selectedTaskId,
            onClick = { onTaskSelected(task.id) },
        )
    }
}

@Composable
private fun CompanionTaskRow(
    task: CompanionTask,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    if (selected) Color(0xFF303134) else Color.Transparent,
                    RoundedCornerShape(10.dp),
                ).clickable(onClick = onClick)
                .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            statusIcon(task.status),
            color = Color.White,
        )

        Column {
            Text(
                task.name,
                color = Color.White,
            )

            Text(
                statusText(task.status),
                color = Color.LightGray,
            )
        }
    }
}

@Composable
private fun CompanionTaskResult(task: CompanionTask) {
    Text(
        completionMessage(task),
        color = Color.White,
    )

    if (task.status == CompanionTaskStatus.COMPLETED ||
        task.status == CompanionTaskStatus.FAILED
    ) {
        Button(
            enabled = canNavigate(task),
            onClick = {
                navigateToTask(task)
            },
        ) {
            Text(
                if (task.status == CompanionTaskStatus.COMPLETED) {
                    "View result"
                } else {
                    "Return to session"
                },
            )
        }
    }
}

private fun java.awt.Window.moveCompanionBy(dragAmount: androidx.compose.ui.geometry.Offset) {
    val newX = x + dragAmount.x.roundToInt()
    val newY = y + dragAmount.y.roundToInt()

    location =
        java.awt.Point(
            newX,
            newY,
        )

    saveCompanionPosition()
}

private fun canNavigate(task: CompanionTask): Boolean =
    task.context?.windowId != null &&
        task.context?.tabId != null

private fun navigateToTask(task: CompanionTask) {
    val windowId = task.context?.windowId ?: return
    val tabId = task.context?.tabId ?: return

    CompanionNavigationBus.tryNavigate(
        windowId = windowId,
        tabId = tabId,
    )
}

private fun statusIcon(status: CompanionTaskStatus): String =
    when (status) {
        CompanionTaskStatus.WORKING -> "🔄"
        CompanionTaskStatus.WAITING_FOR_INPUT -> "❓"
        CompanionTaskStatus.COMPLETED -> "✅"
        CompanionTaskStatus.FAILED -> "⚠️"
        CompanionTaskStatus.STOPPED -> "⏹️"
    }

private fun statusText(status: CompanionTaskStatus): String =
    when (status) {
        CompanionTaskStatus.WORKING -> "Working..."
        CompanionTaskStatus.WAITING_FOR_INPUT -> "Needs input"
        CompanionTaskStatus.COMPLETED -> "Completed"
        CompanionTaskStatus.FAILED -> "Failed"
        CompanionTaskStatus.STOPPED -> "Stopped"
    }

private fun completionMessage(task: CompanionTask): String =
    when (task.status) {
        CompanionTaskStatus.WORKING -> {
            "${task.name} is still running."
        }

        CompanionTaskStatus.WAITING_FOR_INPUT -> {
            "${task.name} is waiting for your input."
        }

        CompanionTaskStatus.COMPLETED -> {
            "${task.name} finished successfully."
        }

        CompanionTaskStatus.FAILED -> {
            "${task.name} finished with an error."
        }

        CompanionTaskStatus.STOPPED -> {
            "${task.name} was stopped."
        }
    }
