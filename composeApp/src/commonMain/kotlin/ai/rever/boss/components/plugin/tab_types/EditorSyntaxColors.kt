package ai.rever.boss.components.plugin.tab_types

import androidx.compose.ui.graphics.Color

/**
 * Shared syntax-highlighting palette for the built-in fallback code editor.
 *
 * These are deliberate VS Code "Dark+"-style syntax colors, NOT BOSS theme
 * tokens: syntax highlighting keeps a fixed palette so code reads the same
 * regardless of the active theme. Do not map these to BossTheme colors.
 */
internal object EditorSyntaxColors {
    /** Keywords and boolean literals. */
    val keyword = Color(0xFF569CD6)

    /** Type names and TOML section headers. */
    val type = Color(0xFF4EC9B0)

    /** String literals. */
    val string = Color(0xFFCE9178)

    /** Numeric literals. */
    val number = Color(0xFFB5CEA8)

    /** Property/key names (e.g. TOML keys). */
    val property = Color(0xFF9CDCFE)

    /** Comments. */
    val comment = Color(0xFF6A9955)
}
