package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.components.settings.shared.SettingsTheme.AccentColor
import ai.rever.boss.components.settings.shared.SettingsTheme.TextMuted
import ai.rever.boss.components.settings.shared.SettingsTheme.TextPrimary
import ai.rever.boss.components.settings.shared.SettingsToggle
import ai.rever.boss.sharing.AppSharingService
import ai.rever.boss.sharing.AppSharingState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.Button
import androidx.compose.material.ButtonColors
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Checkbox
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun AppSharingSettings() {
    val state by AppSharingService.state.collectAsState()
    LaunchedEffect(Unit) { AppSharingService.refresh() }
    val colors =
        ButtonDefaults.buttonColors(
            backgroundColor = AccentColor.copy(alpha = 0.15f),
            contentColor = TextPrimary,
        )
    AppSharingLocalSettings(state, colors)
    Spacer(Modifier.height(20.dp))
    AppSharingAccountSettings(state)
    Spacer(Modifier.height(20.dp))
    AppSharingConnections(state, colors)
}

@Composable
private fun AppSharingLocalSettings(
    state: AppSharingState,
    colors: ButtonColors,
) {
    SettingsSection(title = "BossConsole Sharing") {
        SettingsToggle(
            label = "Share automatically after sign-in",
            checked = state.automaticSharingEnabled,
            onCheckedChange = { AppSharingService.setAutomaticSharingEnabled(it) },
            description =
                "Enabled by default. Your account can open this BossConsole from Live Sessions. " +
                    "Stop sharing pauses it until the next sign-in.",
        )
        SettingsToggle(
            label = "Use media relay",
            checked = state.relayEnabled,
            onCheckedChange = { AppSharingService.setRelayEnabled(it) },
            description = "One encrypted publication serves all viewers. Turning this off stops sharing.",
        )
        Text(
            "Sharing starts automatically after sign-in. You can also choose windows from the sharing menu.",
            color = TextMuted,
            fontSize = 12.sp,
        )
        Text(
            "Preview: requires macOS 14+ and Screen Recording permission. " +
                "Includes owned Compose dialogs. Hidden windows, OS lock or sleep stop sharing.",
            color = TextMuted,
            fontSize = 12.sp,
        )
        if (ai.rever.boss.utils.SystemUtils.isMacOS) {
            Button(onClick = {
                ai.rever.boss.platform.MacOSScreenCapture
                    .requestPermission()
            }, colors = colors) { Text("Grant Screen Recording permission") }
        }
        Text(state.status, color = TextPrimary, fontSize = 13.sp)
        AppSharingWindowSelection(state)
        if (state.activeWindowId != null) {
            Text("${state.viewers} viewing connections", color = TextMuted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { AppSharingService.takeBackControl() },
                    enabled = state.controller,
                    colors = colors,
                ) { Text("Take back control") }
                Button(onClick = { AppSharingService.stop() }, colors = colors) { Text("Stop sharing") }
            }
        }
    }
}

@Composable
private fun AppSharingWindowSelection(state: AppSharingState) {
    state.windows.forEach { window ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = window.id in state.selectedWindowIds,
                onCheckedChange = { AppSharingService.selectWindow(window.id, it) },
                enabled = !state.busy,
            )
            Text(window.title, color = TextPrimary, fontSize = 13.sp)
        }
    }
    Text(
        "Start from Window → Share Selected BossConsole Windows on this device. " +
            "Selection changes take effect only after starting from that menu. " +
            "Capture pauses when nobody is viewing a window.",
        color = TextMuted,
        fontSize = 12.sp,
    )
}

@Composable
private fun AppSharingAccountSettings(state: AppSharingState) {
    SettingsSection(title = "Account Access") {
        SettingsToggle(
            label = "Allow my devices without approval",
            checked =
                state.preferences
                    ?.get("auto_admit")
                    ?.jsonPrimitive
                    ?.booleanOrNull ?: true,
            enabled = state.preferences != null,
            onCheckedChange = { AppSharingService.setAccountPreference("auto_admit", it) },
            description = "Your signed-in devices can connect once sharing starts. Turn off to block automatic access.",
        )
        SettingsToggle(
            label = "Allow my devices to take control",
            checked =
                state.preferences
                    ?.get("auto_control")
                    ?.jsonPrimitive
                    ?.booleanOrNull ?: true,
            enabled = state.preferences != null,
            onCheckedChange = { AppSharingService.setAccountPreference("auto_control", it) },
            description = "Enabled by default. One remote device can take control without a host approval prompt.",
        )
        Text(
            "Clipboard, file transfer, audio and guest access are not enabled for application sharing.",
            color = TextMuted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun AppSharingConnections(
    state: AppSharingState,
    colors: ButtonColors,
) {
    SettingsSection(title = "Remote Connections") {
        Button(onClick = { AppSharingService.refresh() }, colors = colors) { Text("Refresh connections") }
        if (state.sessions.isEmpty()) {
            Text("No shared BossConsole instances found for your account.", color = TextMuted, fontSize = 12.sp)
        }
        state.sessions.forEach { session ->
            Text(session["name"]?.jsonPrimitive?.contentOrNull ?: "BossConsole", color = TextPrimary, fontSize = 13.sp)
            session["windows"]?.jsonArray?.forEach { item ->
                val window = item.jsonObject
                val id = window["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                Text(
                    window["title"]?.jsonPrimitive?.contentOrNull ?: "Shared window",
                    color = TextMuted,
                    fontSize = 12.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { AppSharingService.openViewer(session, false, id) }, colors = colors) {
                        Text("Open in BossConsole")
                    }
                    Button(onClick = { AppSharingService.openViewer(session, true, id) }, colors = colors) {
                        Text("Open in browser")
                    }
                }
            }
        }
    }
}
