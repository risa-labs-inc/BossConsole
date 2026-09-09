package ai.rever.boss.components.dashboard.cards

import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.updater.VersionInfo
import ai.rever.boss.updater.buildInlineMarkdown
import ai.rever.boss.updater.parseReleaseNotes
import ai.rever.boss.updater.summarizeReleaseNotes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
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

/**
 * A recent release displayed in the Dashboard's "What's New" feed.
 */
@Composable
fun ReleaseCard(
    release: VersionInfo,
    isNew: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val scale by
        animateFloatAsState(
            targetValue = if (isHovered) 1.02f else 1f,
            animationSpec = spring(dampingRatio = 0.6f),
        )
    val backgroundColor =
        if (isHovered) {
            BossTheme.colors.signalWash
        } else {
            BossTheme.colors.raised
        }
    val summary =
        remember(release.releaseNotes) {
            runCatching {
                summarizeReleaseNotes(parseReleaseNotes(release.releaseNotes))
            }.getOrNull()
        }

    Box(
        modifier =
            modifier
                .width(240.dp)
                .height(128.dp)
                .scale(scale)
                .clip(RoundedCornerShape(12.dp))
                .background(backgroundColor)
                .clickable(onClick = onClick)
                .hoverable(interactionSource),
    ) {
        ReleaseCardContent(
            release = release,
            isNew = isNew,
            summary = summary,
        )
    }
}

@Composable
private fun ReleaseCardContent(
    release: VersionInfo,
    isNew: Boolean,
    summary: String?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ReleaseCardHeader(
            release = release,
            isNew = isNew,
        )

        Text(
            text = formatReleaseDate(release.releaseDate),
            color = BossTheme.colors.textSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        ReleaseCardSummary(summary)
    }
}

@Composable
private fun ReleaseCardHeader(
    release: VersionInfo,
    isNew: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "v${release.version}",
            color = BossTheme.colors.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (isNew) {
            NewReleaseBadge()
        }
    }
}

@Composable
private fun ReleaseCardSummary(summary: String?) {
    if (summary != null) {
        Text(
            text = buildInlineMarkdown(summary),
            color = BossTheme.colors.textSecondary,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        Text(
            text = "View release notes",
            color = BossTheme.colors.textSecondary,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun NewReleaseBadge() {
    Text(
        text = "NEW",
        color = BossTheme.colors.onSignal,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        modifier =
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(BossTheme.colors.signal)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun formatReleaseDate(releaseDate: String): String = releaseDate.substringBefore("T").ifBlank { releaseDate }
