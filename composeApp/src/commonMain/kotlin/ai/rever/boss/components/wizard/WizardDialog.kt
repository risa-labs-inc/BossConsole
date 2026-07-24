package ai.rever.boss.components.wizard

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * A dialog container for multi-step wizards.
 *
 * Provides a consistent layout with:
 * - Title and optional subtitle
 * - Optional close button
 * - Step indicator
 * - Content area
 * - Navigation buttons (Back/Next/Finish)
 *
 * @param title The wizard title
 * @param subtitle Optional subtitle text
 * @param onDismiss Callback when the dialog should be dismissed
 * @param currentStep Current step number (1-based for display)
 * @param totalSteps Total number of steps
 * @param onBack Callback for the Back button, null to hide it
 * @param onNext Callback for the Next/Finish button
 * @param isLastStep Whether this is the last step
 * @param nextButtonText Custom text for the next button (default: "Next" or "Finish")
 * @param nextButtonEnabled Whether the next button is enabled
 * @param showCloseButton Whether to show the close button
 * @param dismissOnClickOutside Whether clicking outside dismisses the dialog
 * @param width Dialog width
 * @param minHeight Minimum dialog height
 * @param maxHeight Maximum dialog height
 * @param content The step content composable
 */
@Composable
fun WizardDialog(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    currentStep: Int,
    totalSteps: Int,
    onBack: (() -> Unit)?,
    onNext: () -> Unit,
    isLastStep: Boolean,
    nextButtonText: String = if (isLastStep) "Finish" else "Next",
    nextButtonEnabled: Boolean = true,
    showCloseButton: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    width: Dp = 650.dp,
    minHeight: Dp = 500.dp,
    maxHeight: Dp = 600.dp,
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = {
            if (dismissOnClickOutside) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnClickOutside = dismissOnClickOutside,
            dismissOnBackPress = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .width(width)
                .heightIn(min = minHeight, max = maxHeight)
                .clip(RoundedCornerShape(16.dp))
                .background(BossTheme.colors.panel)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp)
            ) {
                // Header
                WizardDialogHeader(
                    title = title,
                    subtitle = subtitle,
                    onBack = onBack,
                    onDismiss = if (showCloseButton) onDismiss else null
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Step indicator
                WizardStepIndicator(
                    currentStep = currentStep,
                    totalSteps = totalSteps,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Content
                Box(modifier = Modifier.weight(1f)) {
                    content()
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Navigation buttons
                WizardNavigationButtons(
                    onBack = onBack,
                    onNext = onNext,
                    isLastStep = isLastStep,
                    nextButtonText = nextButtonText,
                    nextButtonEnabled = nextButtonEnabled
                )
            }
        }
    }
}

@Composable
private fun WizardDialogHeader(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    onDismiss: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.padding(end = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = BossTheme.colors.textSecondary
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = BossTheme.colors.textPrimary
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = BossTheme.colors.textSecondary
                )
            }
        }

        if (onDismiss != null) {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Close",
                    tint = BossTheme.colors.textSecondary
                )
            }
        }
    }
}

@Composable
private fun WizardNavigationButtons(
    onBack: (() -> Unit)?,
    onNext: () -> Unit,
    isLastStep: Boolean,
    nextButtonText: String,
    nextButtonEnabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier.weight(1f))

        if (onBack != null) {
            TextButton(
                onClick = onBack,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = BossTheme.colors.textSecondary
                )
            ) {
                Text("Back")
            }

            Spacer(modifier = Modifier.width(12.dp))
        }

        Button(
            onClick = onNext,
            enabled = nextButtonEnabled,
            colors = ButtonDefaults.buttonColors(
                backgroundColor = BossTheme.colors.signal,
                contentColor = Color.White,
                disabledBackgroundColor = BossTheme.colors.line,
                disabledContentColor = BossTheme.colors.textSecondary
            ),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.height(40.dp)
        ) {
            Text(nextButtonText, fontWeight = FontWeight.Medium)
        }
    }
}
