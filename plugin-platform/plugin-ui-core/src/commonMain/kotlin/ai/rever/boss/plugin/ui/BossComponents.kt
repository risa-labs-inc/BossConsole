package ai.rever.boss.plugin.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A card container with Boss theme styling.
 *
 * @param modifier Modifier for the card
 * @param onClick Optional click handler
 * @param content Content to display inside the card
 */
@Composable
fun BossCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        color = BossThemeColors.SurfaceColor,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, BossThemeColors.BorderColor)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            content = content
        )
    }
}

/**
 * A section container with a title header and optional description.
 */
@Composable
fun BossSection(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            color = BossThemeColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (description != null) {
            Text(
                text = description,
                color = BossThemeColors.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

/**
 * A toggle switch with label and optional description.
 */
@Composable
fun BossToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(BossThemeColors.SurfaceColor)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (enabled) BossThemeColors.TextPrimary else BossThemeColors.TextMuted,
                fontSize = 13.sp
            )
            if (description != null) {
                Text(
                    text = description,
                    color = BossThemeColors.TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BossThemeColors.AccentColor,
                checkedTrackColor = BossThemeColors.AccentColor.copy(alpha = 0.5f),
                uncheckedThumbColor = BossThemeColors.TextMuted,
                uncheckedTrackColor = BossThemeColors.BorderColor
            )
        )
    }
}

/**
 * A clickable info/action row with optional icon.
 */
@Composable
fun BossInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    description: String? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(BossThemeColors.SurfaceColor)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = BossThemeColors.TextPrimary,
                fontSize = 13.sp
            )
            if (description != null) {
                Text(
                    text = description,
                    color = BossThemeColors.TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Text(
            text = value,
            color = if (onClick != null) BossThemeColors.AccentColor else BossThemeColors.TextSecondary,
            fontSize = 13.sp
        )
    }
}

/**
 * A button row for actions.
 */
@Composable
fun BossButtonRow(
    label: String,
    buttonText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    isDestructive: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(BossThemeColors.SurfaceColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (enabled) BossThemeColors.TextPrimary else BossThemeColors.TextMuted,
                fontSize = 13.sp
            )
            if (description != null) {
                Text(
                    text = description,
                    color = BossThemeColors.TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        TextButton(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.textButtonColors(
                contentColor = if (isDestructive) BossThemeColors.ErrorColor else BossThemeColors.AccentColor,
                disabledContentColor = BossThemeColors.TextMuted
            )
        ) {
            Text(buttonText, fontSize = 13.sp)
        }
    }
}

/**
 * A search bar with Boss theme styling.
 * Matches the URL bar design in Fluck browser.
 */
@Composable
fun BossSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search..."
) {
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = TextStyle(
            color = BossThemeColors.TextPrimary,
            fontSize = 13.sp
        ),
        cursorBrush = SolidColor(BossThemeColors.AccentColor),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BossThemeColors.SurfaceColor)
                    .border(1.dp, BossThemeColors.BorderColor.copy(alpha = 0.5f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = BossThemeColors.TextMuted,
                    modifier = Modifier.size(14.dp)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = 6.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = BossThemeColors.TextMuted.copy(alpha = 0.6f),
                            fontSize = 13.sp
                        )
                    }
                    innerTextField()
                }
            }
        },
        modifier = modifier.height(28.dp)
    )
}


/**
 * A text field with Boss theme styling.
 *
 * @param value Current text value
 * @param onValueChange Callback when text changes
 * @param label Label displayed above the field
 * @param modifier Modifier for the entire component
 * @param placeholder Placeholder text when empty
 * @param enabled Whether the field is enabled
 * @param isError Whether to show error styling
 * @param errorMessage Optional error message to display
 * @param singleLine Whether to limit to a single line
 * @param required Whether to show a required indicator
 */
@Composable
fun BossTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    isError: Boolean = false,
    errorMessage: String? = null,
    singleLine: Boolean = true,
    required: Boolean = false
) {
    Column(modifier = modifier) {
        // Label row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 4.dp)
        ) {
            Text(
                text = label,
                color = if (enabled) BossThemeColors.TextPrimary else BossThemeColors.TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            if (required) {
                Text(
                    text = " *",
                    color = BossThemeColors.ErrorColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Text field
        val borderColor = when {
            isError -> BossThemeColors.ErrorColor
            !enabled -> BossThemeColors.BorderColor.copy(alpha = 0.5f)
            else -> BossThemeColors.BorderColor
        }

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = TextStyle(
                color = if (enabled) BossThemeColors.TextPrimary else BossThemeColors.TextMuted,
                fontSize = 13.sp
            ),
            cursorBrush = SolidColor(BossThemeColors.AccentColor),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (enabled) BossThemeColors.SurfaceColor else BossThemeColors.SurfaceColor.copy(alpha = 0.5f),
                            RoundedCornerShape(6.dp)
                        )
                        .border(1.dp, borderColor, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            color = BossThemeColors.TextMuted.copy(alpha = 0.6f),
                            fontSize = 13.sp
                        )
                    }
                    innerTextField()
                }
            }
        )

        // Error message
        if (isError && errorMessage != null) {
            Text(
                text = errorMessage,
                color = BossThemeColors.ErrorColor,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * A multi-line text area with Boss theme styling.
 *
 * @param value Current text value
 * @param onValueChange Callback when text changes
 * @param label Label displayed above the field
 * @param modifier Modifier for the entire component
 * @param placeholder Placeholder text when empty
 * @param enabled Whether the field is enabled
 * @param isError Whether to show error styling
 * @param errorMessage Optional error message to display
 * @param minLines Minimum number of visible lines
 * @param maxLines Maximum number of visible lines
 */
@Composable
fun BossTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    isError: Boolean = false,
    errorMessage: String? = null,
    minLines: Int = 3,
    maxLines: Int = 5
) {
    Column(modifier = modifier) {
        // Label
        Text(
            text = label,
            color = if (enabled) BossThemeColors.TextPrimary else BossThemeColors.TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // Text area
        val borderColor = when {
            isError -> BossThemeColors.ErrorColor
            !enabled -> BossThemeColors.BorderColor.copy(alpha = 0.5f)
            else -> BossThemeColors.BorderColor
        }

        // Calculate minimum height based on line count
        val minHeight = (minLines * 20 + 16).dp

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = false,
            maxLines = maxLines,
            textStyle = TextStyle(
                color = if (enabled) BossThemeColors.TextPrimary else BossThemeColors.TextMuted,
                fontSize = 13.sp
            ),
            cursorBrush = SolidColor(BossThemeColors.AccentColor),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(minHeight)
                        .background(
                            if (enabled) BossThemeColors.SurfaceColor else BossThemeColors.SurfaceColor.copy(alpha = 0.5f),
                            RoundedCornerShape(6.dp)
                        )
                        .border(1.dp, borderColor, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            color = BossThemeColors.TextMuted.copy(alpha = 0.6f),
                            fontSize = 13.sp
                        )
                    }
                    innerTextField()
                }
            }
        )

        // Error message
        if (isError && errorMessage != null) {
            Text(
                text = errorMessage,
                color = BossThemeColors.ErrorColor,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * A primary button with Boss theme styling.
 */
@Composable
fun BossPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            backgroundColor = BossThemeColors.AccentColor,
            contentColor = Color.White,
            disabledBackgroundColor = BossThemeColors.BorderColor,
            disabledContentColor = BossThemeColors.TextMuted
        ),
        shape = RoundedCornerShape(6.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(text, fontSize = 13.sp)
    }
}

/**
 * A secondary/outline button with Boss theme styling.
 */
@Composable
fun BossSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isDestructive: Boolean = false,
    icon: ImageVector? = null
) {
    val contentColor = when {
        !enabled -> BossThemeColors.TextMuted
        isDestructive -> BossThemeColors.ErrorColor
        else -> BossThemeColors.AccentColor
    }

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            backgroundColor = Color.Transparent,
            contentColor = contentColor,
            disabledBackgroundColor = Color.Transparent,
            disabledContentColor = BossThemeColors.TextMuted
        ),
        border = BorderStroke(1.dp, if (enabled) contentColor else BossThemeColors.BorderColor),
        shape = RoundedCornerShape(6.dp),
        elevation = null
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(text, fontSize = 13.sp)
    }
}

/**
 * A tab indicator for Boss-styled tabs.
 */
@Composable
fun BossTabIndicator(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(3.dp)
            .background(BossThemeColors.AccentColor, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
    )
}

/**
 * A badge for showing counts (like update count).
 */
@Composable
fun BossBadge(
    count: Int,
    modifier: Modifier = Modifier
) {
    if (count > 0) {
        Box(
            modifier = modifier
                .size(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(BossThemeColors.AccentColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * An empty state placeholder with icon and message.
 */
@Composable
fun BossEmptyState(
    icon: ImageVector,
    message: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = BossThemeColors.TextMuted.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            color = BossThemeColors.TextSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = description,
            color = BossThemeColors.TextMuted,
            fontSize = 12.sp
        )
    }
}
