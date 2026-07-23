package ai.rever.boss.components.dashboard.cards

import ai.rever.boss.cache.loadHighQualityFavicon
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.dashboard.RecentBrowserPage
import ai.rever.boss.dashboard.RecentBrowserPagesManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Language
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Card displaying a recent browser page with favicon.
 */
@Composable
fun BrowserPageCard(
    page: RecentBrowserPage,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.02f else 1f,
        animationSpec = spring(dampingRatio = 0.6f)
    )

    val backgroundColor = if (isHovered) BossTheme.colors.signalWash else BossTheme.colors.raised
    val domain = RecentBrowserPagesManager.getDomain(page.url)
    val iconColor = getDomainColor(domain)
    val cardShape = RoundedCornerShape(12.dp)

    // Load high-quality favicon (Google's service provides up to 128px icons)
    var favicon by remember(page.url) { mutableStateOf<ai.rever.boss.plugin.api.TabIcon.Image?>(null) }
    LaunchedEffect(page.url, page.faviconCacheKey) {
        favicon = try {
            loadHighQualityFavicon(page.url, page.faviconCacheKey)
        } catch (e: Exception) {
            null
        }
    }

    Box(
        modifier = modifier.width(120.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .scale(scale)
                .clip(cardShape)
                .background(color = backgroundColor)
                .clickable { onClick() }
                .hoverable(interactionSource)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // High-quality favicon or fallback icon
            Box(
                modifier = Modifier.size(36.dp),
                contentAlignment = Alignment.Center
            ) {
                if (favicon != null) {
                    Image(
                        painter = favicon!!.painter,
                        contentDescription = page.title,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Language,
                        contentDescription = page.title,
                        tint = iconColor,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            // Title with fixed height for consistency
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = page.title.ifBlank { domain },
                    color = BossTheme.colors.textPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }

            Text(
                text = domain,
                color = BossTheme.colors.textSecondary,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Remove button (visible on hover)
        if (isHovered) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(16.dp)
                    .background(
                        color = BossTheme.colors.lineStrong,
                        shape = CircleShape
                    )
                    .clickable { onRemove() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Remove",
                    tint = BossTheme.colors.textSecondary,
                    modifier = Modifier.size(10.dp)
                )
            }
        }
    }
}

/**
 * Get a color for a domain based on common sites.
 *
 * These are deliberate external brand-identity colors (GitHub green, Google
 * blue, YouTube red, ...) and are intentionally exempt from BOSS theme tokens.
 */
private fun getDomainColor(domain: String): Color {
    return when {
        domain.contains("github.com") -> Color(0xFF2EA44F)
        domain.contains("gitlab.com") -> Color(0xFFFC6D26)
        domain.contains("stackoverflow.com") -> Color(0xFFF48024)
        domain.contains("google.com") -> Color(0xFF4285F4)
        domain.contains("youtube.com") -> Color(0xFFFF0000)
        domain.contains("twitter.com") || domain.contains("x.com") -> Color(0xFF1DA1F2)
        domain.contains("reddit.com") -> Color(0xFFFF4500)
        domain.contains("npm") -> Color(0xFFCB3837)
        domain.contains("docs.") -> Color(0xFF0288D1)
        domain.contains("developer.") -> Color(0xFF4CAF50)
        else -> Color(0xFF78909C)
    }
}
