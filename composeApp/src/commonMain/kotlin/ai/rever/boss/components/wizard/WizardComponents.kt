package ai.rever.boss.components.wizard

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Visual step indicator showing progress through the wizard.
 *
 * @param currentStep Current step number (1-based)
 * @param totalSteps Total number of steps
 * @param modifier Modifier for the component
 */
@Composable
fun WizardStepIndicator(
    currentStep: Int,
    totalSteps: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (step in 1..totalSteps) {
            val isCompleted = step < currentStep
            val isCurrent = step == currentStep

            val backgroundColor by animateColorAsState(
                targetValue = when {
                    isCompleted -> BossTheme.colors.ok
                    isCurrent -> BossTheme.colors.signal
                    else -> BossTheme.colors.raised
                },
                label = "step_bg_$step"
            )

            val borderColor by animateColorAsState(
                targetValue = when {
                    isCompleted || isCurrent -> Color.Transparent
                    else -> BossTheme.colors.line
                },
                label = "step_border_$step"
            )

            // Step circle
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(backgroundColor)
                    .border(1.dp, borderColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (isCompleted) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Completed",
                        modifier = Modifier.size(16.dp),
                        tint = BossTheme.colors.onSignal
                    )
                } else {
                    Text(
                        text = step.toString(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isCurrent) BossTheme.colors.onSignal else BossTheme.colors.textSecondary
                    )
                }
            }

            // Connector line (except after last step)
            if (step < totalSteps) {
                val lineColor by animateColorAsState(
                    targetValue = if (isCompleted) BossTheme.colors.ok else BossTheme.colors.line,
                    label = "line_$step"
                )
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(2.dp)
                        .background(lineColor)
                )
            }
        }
    }
}

/**
 * A selectable card component for single selection scenarios.
 *
 * @param title Card title
 * @param description Card description
 * @param icon Optional icon
 * @param isSelected Whether this card is selected
 * @param onClick Callback when the card is clicked
 * @param modifier Modifier for the component
 */
@Composable
fun SelectionCard(
    title: String,
    description: String,
    icon: ImageVector? = null,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.02f else 1f,
        animationSpec = spring(dampingRatio = 0.6f),
        label = "selection_card_scale"
    )

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isSelected -> BossTheme.colors.signalWash
            isHovered -> BossTheme.colors.signalWash
            else -> BossTheme.colors.raised
        },
        label = "selection_card_bg"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isSelected -> BossTheme.colors.signal
            isHovered -> BossTheme.colors.line.copy(alpha = 0.8f)
            else -> Color.Transparent
        },
        label = "selection_card_border"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .hoverable(interactionSource)
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (isSelected) BossTheme.colors.signal else BossTheme.colors.textSecondary
                )
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isSelected || isHovered) BossTheme.colors.textPrimary else BossTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (description.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = description,
                        fontSize = 12.sp,
                        color = BossTheme.colors.textSecondary.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 16.sp
                    )
                }
            }

            if (isSelected) {
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(BossTheme.colors.signal),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        modifier = Modifier.size(14.dp),
                        tint = BossTheme.colors.onSignal
                    )
                }
            }
        }
    }
}

/**
 * A checkbox card component for multi-selection scenarios.
 *
 * @param title Card title
 * @param description Card description
 * @param icon Optional icon
 * @param isChecked Whether this card is checked
 * @param onCheckedChange Callback when the checkbox state changes
 * @param enabled Whether the card is enabled
 * @param modifier Modifier for the component
 */
@Composable
fun CheckboxCard(
    title: String,
    description: String,
    icon: ImageVector? = null,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val backgroundColor by animateColorAsState(
        targetValue = when {
            !enabled -> BossTheme.colors.raised.copy(alpha = 0.5f)
            isChecked -> BossTheme.colors.signalWash
            isHovered -> BossTheme.colors.signalWash
            else -> BossTheme.colors.raised
        },
        label = "checkbox_card_bg"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            !enabled -> Color.Transparent
            isChecked -> BossTheme.colors.signal.copy(alpha = 0.5f)
            isHovered -> BossTheme.colors.line.copy(alpha = 0.8f)
            else -> Color.Transparent
        },
        label = "checkbox_card_border"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onCheckedChange(!isChecked) }
            .hoverable(interactionSource)
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isChecked,
                onCheckedChange = if (enabled) onCheckedChange else null,
                enabled = enabled,
                colors = CheckboxDefaults.colors(
                    checkedColor = BossTheme.colors.signal,
                    uncheckedColor = BossTheme.colors.line,
                    disabledColor = BossTheme.colors.line.copy(alpha = 0.5f),
                    checkmarkColor = BossTheme.colors.onSignal
                )
            )

            Spacer(modifier = Modifier.width(8.dp))

            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (enabled) {
                        if (isChecked) BossTheme.colors.signal else BossTheme.colors.textSecondary
                    } else {
                        BossTheme.colors.textSecondary.copy(alpha = 0.5f)
                    }
                )
                Spacer(modifier = Modifier.width(10.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) {
                        if (isChecked || isHovered) BossTheme.colors.textPrimary else BossTheme.colors.textSecondary
                    } else {
                        BossTheme.colors.textSecondary.copy(alpha = 0.5f)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (description.isNotEmpty()) {
                    Text(
                        text = description,
                        fontSize = 11.sp,
                        color = BossTheme.colors.textSecondary.copy(alpha = if (enabled) 0.7f else 0.4f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}

/**
 * Note text component for wizard hints and information.
 *
 * @param text The note text to display
 * @param modifier Modifier for the component
 */
@Composable
fun WizardNote(
    text: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(BossTheme.colors.raised.copy(alpha = 0.5f))
            .padding(12.dp)
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = BossTheme.colors.textSecondary,
            lineHeight = 16.sp
        )
    }
}
