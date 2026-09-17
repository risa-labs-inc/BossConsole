package ai.rever.boss.tabs

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Predefined workspace tab categories with harmonious UI badge color tokens.
 */
enum class TabCategory(
    val label: String,
    val colorHex: String,
) {
    WORK("Work", "#3B82F6"), // Blue
    DEV("Dev", "#10B981"), // Emerald Green
    DOCS("Docs", "#F59E0B"), // Amber
    PERSONAL("Personal", "#EC4899"), // Pink
    IMPORTANT("Important", "#EF4444"), // Red
}

/**
 * Custom color tag metadata assigned to a workspace tab.
 */
data class TabColorTag(
    val category: TabCategory,
    val customLabel: String? = null,
) {
    val displayLabel: String
        get() = customLabel ?: category.label

    val color: Color
        get() = parseColorHex(category.colorHex)
}

/**
 * Parses a hex color string into a Compose Color object.
 */
fun parseColorHex(hex: String): Color {
    val cleanHex = hex.removePrefix("#")
    val colorInt = cleanHex.toLongOrNull(16) ?: 0xFF3B82F6
    return if (cleanHex.length == 6) {
        Color(0xFF000000 or colorInt)
    } else {
        Color(colorInt)
    }
}

/**
 * Thread-safe registry managing tab color tags per workspace.
 */
object TabColorRegistry {
    private val lock = Any()
    private val tabTags = mutableMapOf<String, TabColorTag>()

    private val _tagsFlow = MutableStateFlow<Map<String, TabColorTag>>(emptyMap())
    val tagsFlow: StateFlow<Map<String, TabColorTag>> = _tagsFlow.asStateFlow()

    /**
     * Assigns a color tag to a specific tab ID.
     */
    fun setTag(
        tabId: String,
        category: TabCategory,
        customLabel: String? = null,
    ) {
        if (tabId.isBlank()) return
        synchronized(lock) {
            tabTags[tabId] = TabColorTag(category = category, customLabel = customLabel)
            _tagsFlow.value = tabTags.toMap()
        }
    }

    /**
     * Retrieves the color tag assigned to a tab ID.
     */
    fun getTag(tabId: String): TabColorTag? =
        synchronized(lock) {
            tabTags[tabId]
        }

    /**
     * Removes a color tag assignment from a tab ID.
     */
    fun removeTag(tabId: String) {
        synchronized(lock) {
            tabTags.remove(tabId)
            _tagsFlow.value = tabTags.toMap()
        }
    }

    /**
     * Clears all tab color tag assignments.
     */
    fun clear() {
        synchronized(lock) {
            tabTags.clear()
            _tagsFlow.value = emptyMap()
        }
    }
}
