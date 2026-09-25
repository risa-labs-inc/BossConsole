@file:Suppress("LongMethod", "MaxLineLength")

package ai.rever.boss.arcade.rushhour.ui

import ai.rever.boss.plugin.ui.BossDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Apple-style interactive instructions and rule guide modal for Rush Hour Gym.
 * Explains puzzle objective, 1D drag mechanics, keyboard shortcuts, and MCP CLI tools.
 */
@Composable
fun RushHourHelpSheet(onDismiss: () -> Unit) {
    val clipboardManager = LocalClipboardManager.current
    var copiedSnippet by remember { mutableStateOf(false) }

    BossDialog(onDismissRequest = onDismiss) {
        Box(
            modifier =
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth(0.92f)
                    .clip(RushHourTheme.ModalShape)
                    .background(RushHourTheme.BoardSurface)
                    .border(1.dp, RushHourTheme.BoardBorder, RushHourTheme.ModalShape)
                    .padding(24.dp),
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                // Header with Title & Apple-style Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier =
                                Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(RushHourTheme.PrimaryCarStart),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Rush Hour Gym // Guide",
                            color = RushHourTheme.TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Box(
                        modifier =
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable { onDismiss() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = RushHourTheme.TextSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Section 1: Objective
                HelpSection(
                    title = "OBJECTIVE",
                    description =
                        "Clear a direct exit path for Car X (Sunset Coral). Slide blocking vehicles " +
                            "out of the way until Car X reaches the glowing green Exit Gate at (row 2, col 5).",
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Section 2: 1D Drag Controls
                HelpSection(
                    title = "1D DRAG CONTROLS",
                    description =
                        "• Horizontal vehicles slide exclusively Left ↔ Right.\n" +
                            "• Vertical vehicles slide exclusively Up ↕ Down.\n" +
                            "• Drag > 40% of a grid cell to commit a move. Releasing below 40% will smoothly snap back.\n" +
                            "• Click ends or arrow buttons to step 1 cell incrementally.",
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Section 3: Keyboard Shortcuts
                HelpSection(
                    title = "KEYBOARD SHORTCUTS",
                    description =
                        "• [ 1 ] - [ 4 ] : Switch difficulty level (Beginner → Expert)\n" +
                            "• [ R ] : Reset current board\n" +
                            "• [ Esc ] : Close this modal guide",
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Section 4: AI Agent Gym (MCP)
                Column {
                    Text(
                        text = "AI AGENT EVALUATION (MCP)",
                        color = RushHourTheme.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "External AI agents can inspect state and execute moves over Boss Console MCP protocol:",
                        color = RushHourTheme.TextTertiary,
                        fontSize = 13.sp,
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    val cliSnippet = """boss mcp invoke mcp__boss__arcade_rushhour_move '{"vehicleId":"X","steps":1}'"""
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RushHourTheme.CardShape)
                                .background(RushHourTheme.CellSurface)
                                .border(1.dp, RushHourTheme.CellBorder, RushHourTheme.CardShape)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = cliSnippet,
                                color = RushHourTheme.ExitEmerald,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            Box(
                                modifier =
                                    Modifier
                                        .size(32.dp)
                                        .clip(RushHourTheme.CellShape)
                                        .background(if (copiedSnippet) RushHourTheme.ExitEmerald else Color.White.copy(alpha = 0.08f))
                                        .clickable {
                                            clipboardManager.setText(AnnotatedString(cliSnippet))
                                            copiedSnippet = true
                                        },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy command",
                                    tint = if (copiedSnippet) RushHourTheme.BoardBackdrop else RushHourTheme.TextPrimary,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HelpSection(
    title: String,
    description: String,
) {
    Column {
        Text(
            text = title,
            color = RushHourTheme.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = description,
            color = RushHourTheme.TextPrimary,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
    }
}
