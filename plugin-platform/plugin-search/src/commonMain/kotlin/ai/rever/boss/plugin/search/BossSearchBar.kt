package ai.rever.boss.plugin.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Standardized search bar component used across BOSS application
 *
 * Design specifications:
 * - Height: 28dp
 * - Corner radius: 4dp
 * - Background: Dark surface (0xFF1E1F22)
 * - Border: Gray (0xFF555555)
 * - Cursor: Gold (0xFFFBBF24)
 *
 * Based on Bookmarks search bar design.
 *
 * @param query Current search query text
 * @param onQueryChange Callback when query text changes
 * @param placeholder Placeholder text when query is empty
 * @param modifier Modifier for the search bar container
 * @param leadingIcon Icon to display at the start of the search bar (default: Search icon)
 * @param showClearButton Whether to show the clear button when text is present (default: true)
 * @param onFocusChanged Optional callback for focus state changes
 */
@Composable
fun BossSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = "Search...",
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector = Icons.Outlined.Search,
    showClearButton: Boolean = true,
    onFocusChanged: ((Boolean) -> Unit)? = null
) {
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.body2.copy(
            color = Color.White
        ),
        cursorBrush = SolidColor(Color(0xFFFBBF24)), // Gold cursor
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Color(0xFF1E1F22), // Dark surface
                        RoundedCornerShape(4.dp)
                    )
                    .border(
                        1.dp,
                        Color(0xFF555555), // Gray border
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Leading icon (search or custom)
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = "Search",
                    modifier = Modifier.size(16.dp),
                    tint = Color(0xFF888888)
                )

                Spacer(modifier = Modifier.width(6.dp))

                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.body2,
                            color = Color(0xFF888888),
                            fontSize = 12.sp
                        )
                    }
                    innerTextField()
                }

                // Clear button (only show when there's text and enabled)
                if (showClearButton && query.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.size(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = "Clear search",
                            modifier = Modifier.size(14.dp),
                            tint = Color(0xFF888888)
                        )
                    }
                }
            }
        }
    )
}
