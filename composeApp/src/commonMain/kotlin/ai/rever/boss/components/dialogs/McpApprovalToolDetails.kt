package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpApprovalRequest
import ai.rever.boss.mcp.McpArgumentSanitizer
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The tool card of [McpApprovalDialog]: name, provider, description and arguments. Kept in its own
// file so the dialog file stays under detekt's TooManyFunctions threshold.

/** Lines of the tool's description shown before "Show more". */
internal const val APPROVAL_DESCRIPTION_COLLAPSED_LINES = 3

/** Test tag of the scrolling arguments block. */
internal const val APPROVAL_ARGUMENTS_TAG = "mcp-approval-arguments"

/** Test tag of the dialog's scrolling body, between the pinned header and the pinned actions. */
internal const val APPROVAL_BODY_TAG = "mcp-approval-body"

private val ARGUMENTS_MAX_HEIGHT = 140.dp

/** Space kept between the card and the window edge, split top and bottom by the centring. */
private val DIALOG_WINDOW_MARGIN = 32.dp

/** Height cap while the window has not reported a size yet. */
private val DIALOG_FALLBACK_MAX_HEIGHT = 640.dp

/**
 * The largest (width, height) the approval card may take in the current window: the window less a
 * margin, or a fixed fallback while the window has not measured yet.
 */
@Composable
internal fun approvalDialogBounds(): Pair<Dp, Dp> {
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    return with(density) {
        val width =
            if (windowSize.width > 0) {
                (windowSize.width.toDp() - DIALOG_WINDOW_MARGIN).coerceAtLeast(1.dp)
            } else {
                APPROVAL_DIALOG_WIDTH
            }
        val height =
            if (windowSize.height > 0) {
                (windowSize.height.toDp() - DIALOG_WINDOW_MARGIN).coerceAtLeast(1.dp)
            } else {
                DIALOG_FALLBACK_MAX_HEIGHT
            }
        width to height
    }
}

@Composable
internal fun ToolDetails(request: McpApprovalRequest) {
    val colors = BossTheme.colors
    val radii = BossTheme.radius
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.raised, RoundedCornerShape(radii.card))
                .border(1.dp, colors.line, RoundedCornerShape(radii.card))
                .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = request.toolName,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = colors.signalText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = request.providerId,
                fontSize = 11.sp,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .padding(start = 8.dp)
                        .border(1.dp, colors.line, RoundedCornerShape(radii.input))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }

        // The tool's own description is the operator's only sight of what it
        // claims to do - without it an approval is a guess on a bare name.
        request.toolDescription?.takeIf { it.isNotBlank() }?.let { description ->
            Spacer(modifier = Modifier.height(6.dp))
            ToolDescription(description = description, requestId = request.id)
        }

        val sanitizedArguments =
            remember(request.arguments) {
                McpArgumentSanitizer.sanitize(request.arguments)
            }
        if (sanitizedArguments.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            ArgumentsBlock(sanitizedArguments, requestId = request.id)
        }

        // Why the prompt exists. The deadline itself is the live countdown in the pinned header:
        // a second, snapshotted "auto-denies in ~45s" here used to disagree with it.
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Policy: ${request.policy?.name ?: "ASK"} · denied automatically when the timer runs out",
            fontSize = 10.sp,
            color = colors.textSecondary,
        )
    }
}

/**
 * A plugin's description can run to many lines of prose, and shown in full it pushed the decision
 * UI out of reach. Collapsed to [APPROVAL_DESCRIPTION_COLLAPSED_LINES] by default; the toggle only
 * appears when there is something hidden.
 */
@Composable
private fun ToolDescription(
    description: String,
    requestId: String,
) {
    val colors = BossTheme.colors
    var expanded by remember(requestId) { mutableStateOf(false) }
    var overflows by remember(requestId, description) { mutableStateOf(false) }
    Text(
        text = description,
        fontSize = 12.sp,
        color = colors.textPrimary,
        maxLines = if (expanded) Int.MAX_VALUE else APPROVAL_DESCRIPTION_COLLAPSED_LINES,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
    )
    if (expanded || overflows) {
        TextButton(
            onClick = { expanded = !expanded },
            colors = ButtonDefaults.textButtonColors(contentColor = colors.signalText),
            contentPadding = PaddingValues(horizontal = 0.dp),
            modifier = Modifier.height(24.dp),
        ) {
            Text(if (expanded) "Show less" else "Show more", fontSize = 11.sp)
        }
    }
}

/**
 * The sanitized arguments, in a bounded box that scrolls on its own and always opens at its top.
 * Long lines wrap rather than widen the card. The scroll state is keyed on the request, so the next
 * prompt never inherits the previous one's offset.
 */
@Composable
private fun ArgumentsBlock(
    sanitizedArguments: Map<String, String>,
    requestId: String,
) {
    val colors = BossTheme.colors
    val radii = BossTheme.radius
    val scroll = remember(requestId) { ScrollState(0) }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = ARGUMENTS_MAX_HEIGHT)
                .background(colors.ink, RoundedCornerShape(radii.input))
                .testTag(APPROVAL_ARGUMENTS_TAG),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        ) {
            sanitizedArguments.forEach { (k, v) ->
                Text(
                    text = "$k = $v",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.textPrimary,
                )
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scroll),
            modifier = Modifier.matchParentSize().wrapContentWidth(Alignment.End),
        )
    }
}
