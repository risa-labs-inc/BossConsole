package ai.rever.boss.updater

import BossDarkBackground
import BossDarkBorder
import BossDarkError
import BossDarkTextPrimary
import BossDarkTextSecondary
import BossDarkWarning
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import ai.rever.boss.utils.Version

/**
 * Warning dialog shown before downgrading to an older version
 */
@Composable
fun DowngradeWarningDialog(
    currentVersion: Version,
    targetVersion: Version,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.width(500.dp),
            backgroundColor = BossDarkBackground,
            shape = RoundedCornerShape(12.dp),
            elevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                // Header with warning icon
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = BossDarkWarning,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        "Downgrade Warning",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BossDarkTextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Version info
                Text(
                    "You are about to downgrade from v$currentVersion to v$targetVersion.",
                    fontSize = 14.sp,
                    color = BossDarkTextPrimary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Warning message
                Text(
                    "Downgrading may cause:",
                    fontWeight = FontWeight.Medium,
                    color = BossDarkTextPrimary,
                    fontSize = 14.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier.padding(start = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    WarningItem("Data loss or corruption")
                    WarningItem("Incompatible configuration files")
                    WarningItem("Database schema mismatches")
                    WarningItem("Unexpected behavior or crashes")
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Backup reminder
                Card(
                    backgroundColor = BossDarkWarning.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, BossDarkWarning.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = BossDarkWarning,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            "Make sure you have backups before proceeding.",
                            fontWeight = FontWeight.Medium,
                            color = BossDarkWarning,
                            fontSize = 13.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Cancel button
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = BossDarkTextSecondary
                        ),
                        border = BorderStroke(1.dp, BossDarkBorder)
                    ) {
                        Text("Cancel")
                    }

                    // Confirm downgrade button
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = BossDarkError,
                            contentColor = BossDarkTextPrimary
                        )
                    ) {
                        Text(
                            "Downgrade Anyway",
                            color = BossDarkTextPrimary
                        )
                    }
                }
            }
        }
    }
}

/**
 * Individual warning item
 */
@Composable
private fun WarningItem(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            "•",
            color = BossDarkError,
            fontSize = 14.sp
        )
        Text(
            text,
            color = BossDarkTextSecondary,
            fontSize = 13.sp
        )
    }
}
