package ai.rever.boss.components.onboarding

import ai.rever.boss.plugin.ui.BossDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.awt.Desktop
import java.net.URI

@OptIn(ExperimentalMaterialApi::class)
@Composable
internal fun FirstSessionBanner(
    onOpenWorkspace: () -> Unit,
    onOpenToolbox: () -> Unit,
    onOpenTerminal: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    var agentWizardStep by remember { mutableStateOf(0) }
    var selectedAgent by remember { mutableStateOf("") }
    
    BossDialog(
        onDismissRequest = {},
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colors.surface,
            elevation = 24.dp
        ) {
            Column(
                modifier = Modifier
                    .width(780.dp)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            if (agentWizardStep >= 1) {
                LinearProgressIndicator(
                    progress = agentWizardStep / 4f,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    color = MaterialTheme.colors.primary
                )
            }

            if (agentWizardStep == 0) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Welcome to BOSS",
                        style = MaterialTheme.typography.h5,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "You are all set. Here is where to start.",
                        style = MaterialTheme.typography.body1,
                        color = LocalContentColor.current.copy(alpha = 0.7f)
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        // Card 1
                        Card(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onOpenWorkspace()
                                onDismiss()
                            }
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(20.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = "Open a Workspace",
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Open a Workspace",
                                    style = MaterialTheme.typography.body1,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Launch a workspace layout to get started",
                                    style = MaterialTheme.typography.body2,
                                    color = LocalContentColor.current.copy(alpha = 0.7f)
                                )
                            }
                        }
                        
                        // Card 2
                        Card(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                agentWizardStep = 1
                            }
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(20.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Link,
                                    contentDescription = "Connect an Agent",
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Connect an Agent",
                                    style = MaterialTheme.typography.body1,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Point your agent at 127.0.0.1:7677",
                                    style = MaterialTheme.typography.body2,
                                    color = LocalContentColor.current.copy(alpha = 0.7f)
                                )
                            }
                        }
                        
                        // Card 3
                        Card(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onOpenToolbox()
                                onDismiss()
                            }
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(20.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Extension,
                                    contentDescription = "Open Toolbox",
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Open Toolbox",
                                    style = MaterialTheme.typography.body1,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Install more plugins anytime",
                                    style = MaterialTheme.typography.body2,
                                    color = LocalContentColor.current.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Get Started")
                    }
                }
            } else if (agentWizardStep == 1) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⚡ Connect Your Agent", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.h6)
                        Text("Step 1 of 4", style = MaterialTheme.typography.body2, color = LocalContentColor.current.copy(alpha = 0.5f))
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Which agent CLI do you have installed?", style = MaterialTheme.typography.body1)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { selectedAgent = "claude"; agentWizardStep = 2 },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Claude Code")
                        }
                        OutlinedButton(
                            onClick = { selectedAgent = "gemini"; agentWizardStep = 2 },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Gemini CLI")
                        }
                        OutlinedButton(
                            onClick = { selectedAgent = "codex"; agentWizardStep = 2 },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Codex / OpenCode")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(
                        onClick = {
                            runCatching { Desktop.getDesktop().browse(URI("https://claude.ai/download")) }
                        }
                    ) {
                        Text("Don't have one? Install Claude Code free →")
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = { agentWizardStep = 0 }) {
                            Text("← Back")
                        }
                    }
                }
            } else if (agentWizardStep == 2) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⚡ Connect Your Agent", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.h6)
                        Text("Step 2 of 4", style = MaterialTheme.typography.body2, color = LocalContentColor.current.copy(alpha = 0.5f))
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Run this in your terminal:", style = MaterialTheme.typography.body1, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val addCmd = when (selectedAgent) {
                        "claude" -> "claude mcp add --scope user --transport sse boss http://127.0.0.1:7677"
                        "gemini" -> "gemini mcp add boss http://127.0.0.1:7677 --transport sse --scope user"
                        "codex" -> "codex mcp add boss --url http://127.0.0.1:7677/mcp"
                        else -> ""
                    }
                    
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(addCmd, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onSurface)
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(onClick = { clipboardManager.setText(AnnotatedString(addCmd)) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Then launch your agent:", style = MaterialTheme.typography.body1, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val launchCmd = when (selectedAgent) {
                        "claude" -> "claude"
                        "gemini" -> "gemini"
                        "codex" -> "codex"
                        else -> ""
                    }
                    
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(launchCmd, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onSurface)
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(onClick = { clipboardManager.setText(AnnotatedString(launchCmd)) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colors.primary.copy(alpha = 0.1f)
                    ) {
                        Row(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "💡 Tip: Copy the command, click below to open the Toolbox, find Terminal Tab there, and run it. Then come back and click 'I ran it'.",
                                style = MaterialTheme.typography.body2,
                                color = MaterialTheme.colors.onSurface
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    TextButton(
                        onClick = { onOpenTerminal() },
                        modifier = Modifier.align(Alignment.Start)
                    ) {
                        Text("Open Toolbox (find Terminal there) →")
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = { agentWizardStep = 1 }) {
                            Text("← Back")
                        }
                        Button(onClick = { agentWizardStep = 3 }) {
                            Text("I ran it → Next")
                        }
                    }
                }
            } else if (agentWizardStep == 3) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⚡ Connect Your Agent", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.h6)
                        Text("Step 3 of 4", style = MaterialTheme.typography.body2, color = LocalContentColor.current.copy(alpha = 0.5f))
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Ask your agent this:", style = MaterialTheme.typography.body1, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val askCmd = "What BOSS tools do you have available?"
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(askCmd, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onSurface)
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(onClick = { clipboardManager.setText(AnnotatedString(askCmd)) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colors.secondary.copy(alpha = 0.15f)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("✅ Connected", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.body2)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Your agent will list tools like mcp__boss__open_url, mcp__boss__run_command", style = MaterialTheme.typography.body2)
                            }
                        }
                        
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colors.error.copy(alpha = 0.15f)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("❌ Not connected", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.body2)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Go back and re-run the connection command, then restart your agent.", style = MaterialTheme.typography.body2)
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = { agentWizardStep = 2 }) {
                            Text("← Back")
                        }
                        Button(onClick = { agentWizardStep = 4 }) {
                            Text("It worked! Next →")
                        }
                    }
                }
            } else if (agentWizardStep == 4) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🎉 Your agent is connected!", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.h6)
                        Text("Step 4 of 4", style = MaterialTheme.typography.body2, color = LocalContentColor.current.copy(alpha = 0.5f))
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Try your first BOSS command:", style = MaterialTheme.typography.body1, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val tryCmd = "Open google.com in the BOSS browser tab"
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(tryCmd, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onSurface)
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(onClick = { clipboardManager.setText(AnnotatedString(tryCmd)) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        "Watch the Fluck Browser open Google automatically. Your agent now controls BOSS — its browser, terminal, editor, and 100+ tools.",
                        style = MaterialTheme.typography.body2,
                        color = LocalContentColor.current.copy(alpha = 0.5f)
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = { agentWizardStep = 3 }) {
                            Text("← Back")
                        }
                        Button(onClick = onDismiss) {
                            Text("Finish ✓")
                        }
                    }
                }
            }
        }
    }
}
}
