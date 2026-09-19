package ai.rever.boss.components.dashboard.cards

import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.window.Project
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * Card displaying a recent project.
 */
@Composable
fun ProjectCard(
    project: Project,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.02f else 1f,
        animationSpec = spring(dampingRatio = 0.6f),
    )

    val backgroundColor = if (isHovered) BossTheme.colors.signalWash else BossTheme.colors.raised
    val cardShape = RoundedCornerShape(12.dp)

    Box(
        modifier =
            modifier
                .width(140.dp)
                .scale(scale)
                .clip(cardShape)
                .background(color = backgroundColor)
                .clickable { onClick() }
                .hoverable(interactionSource),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Project logo - same as top bar selection
            Surface(
                modifier =
                    Modifier
                        .padding(2.dp)
                        .size(32.dp),
                shape = RoundedCornerShape(4.dp),
                color = BossTheme.colors.signal,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    val initials =
                        when {
                            project.name.length >= 2 -> project.name.substring(0, 2)
                            project.name.isNotEmpty() -> project.name[0].toString()
                            else -> "?"
                        }
                    Text(
                        text = initials.uppercase(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = BossTheme.colors.onSignal,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }

            Text(
                text = project.name,
                color = BossTheme.colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = formatRelativeTime(project.lastOpened),
                color = BossTheme.colors.textSecondary,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }

        // Remove button (visible on hover)
        if (onRemove != null) {
            AnimatedVisibility(
                visible = isHovered,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Box(
                    modifier =
                        Modifier
                            .padding(4.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(BossTheme.colors.lineStrong)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onRemove() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Remove",
                        tint = BossTheme.colors.textSecondary,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

/**
 * Format timestamp as relative time (e.g., "2h ago", "Yesterday").
 *
 * Sub-day elapsed buckets are pure clock math: "Just now", "Nm ago" and the
 * "Nh ago" rendered below 24h depend only on the elapsed clock, so a 23:50
 * mtime still reads "15m ago" at 00:05 tonight. The calendar gates only the
 * day-named labels: "Yesterday" is the previous calendar date of [timestamp]
 * in [zone] - never a 24-48h elapsed window - so a 23:00 mtime from two
 * nights back never reads Yesterday. The same-date hour bucket is left
 * uncapped, so a DST-stretched calendar date stays "Nh ago" past 24 real
 * hours; anything older than yesterday falls back to the absolute "MMM d"
 * date.
 *
 * [now] and [zone] default to the system clock and zone; tests inject fixed
 * values to pin day boundaries deterministically.
 */
internal fun formatRelativeTime(
    timestamp: Long,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    if (timestamp == 0L) return "Never"

    val diff = now - timestamp
    val dayDiff = calendarDayDiff(now, timestamp, zone)
    return when {
        // Future mtimes keep rendering Just now; their handling is tracked
        // in a separate issue and deliberately untouched here.
        diff < 0 -> "Just now"

        // Sub-day elapsed buckets are pure clock math - midnight does not
        // promote a 2-minute-old mtime to Yesterday.
        diff < 60_000 -> "Just now"

        diff < 3600_000 -> "${diff / 60_000}m ago"

        // Same calendar date keeps the hour bucket even past 24h of real
        // time, so a DST-stretched date never falls out of "Nh ago".
        diff < 86_400_000 || dayDiff == 0L -> "${diff / 3600_000}h ago"

        // Only the day-named label resolves against calendar days.
        dayDiff == 1L -> "Yesterday"

        else -> SimpleDateFormat("MMM d").format(Date(timestamp))
    }
}

/**
 * Calendar days from the timestamp date to the now date in [zone]. Future
 * mtimes get a sentinel gap instead of date math, so they keep their
 * existing rendering no matter how far out they are.
 */
private fun calendarDayDiff(
    now: Long,
    timestamp: Long,
    zone: ZoneId,
): Long {
    if (now - timestamp < 0) return -1L
    val today = LocalDate.ofInstant(Instant.ofEpochMilli(now), zone).toEpochDay()
    val day = LocalDate.ofInstant(Instant.ofEpochMilli(timestamp), zone).toEpochDay()
    return today - day
}
