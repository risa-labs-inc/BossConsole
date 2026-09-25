package ai.rever.boss.arcade.rushhour.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Apple Design System & HIG Tokens for Boss Arcade: Rush Hour.
 * Implements continuous squircle geometry, refined dark mode layering,
 * and high-contrast tactile elements.
 */
object RushHourTheme {
    // Surface & Shell Colors
    val BoardBackdrop = Color(0xFF0B0E14)
    val BoardSurface = Color(0xFF121620)
    val BoardBorder = Color.White.copy(alpha = 0.08f)
    val CellSurface = Color(0xFF171B24)
    val CellBorder = Color.White.copy(alpha = 0.04f)
    val CardSurface = Color(0xFF151A24)
    val CardBorder = Color.White.copy(alpha = 0.07f)

    // Primary Vehicle (Car X)
    val PrimaryCarStart = Color(0xFFFF5E3A) // Apple Sunset Coral
    val PrimaryCarEnd = Color(0xFFFF9500) // Apple Warm Amber
    val PrimaryCarBrush = Brush.horizontalGradient(listOf(PrimaryCarStart, PrimaryCarEnd))
    val PrimaryCarBorder = Color.White.copy(alpha = 0.35f)

    // Blocking Vehicles (Cars: length 2, Trucks: length 3)
    val CarGradientStart = Color(0xFF323B4E)
    val CarGradientEnd = Color(0xFF242A39)
    val CarBrush = Brush.verticalGradient(listOf(CarGradientStart, CarGradientEnd))

    val TruckGradientStart = Color(0xFF252D3C)
    val TruckGradientEnd = Color(0xFF1B212D)
    val TruckBrush = Brush.verticalGradient(listOf(TruckGradientStart, TruckGradientEnd))

    val VehicleSpecular = Color.White.copy(alpha = 0.14f)
    val VehicleBorder = Color.White.copy(alpha = 0.09f)
    val VehicleGroove = Color.Black.copy(alpha = 0.35f)
    val VehicleGrooveHighlight = Color.White.copy(alpha = 0.08f)

    // Selection & Accent
    val SelectedGlow = Color(0xFF0A84FF) // Apple System Blue
    val SelectedBorder = Color(0xFF64D2FF)
    val ExitEmerald = Color(0xFF30D158) // Apple System Green
    val ExitGlow = Color(0xFF30D158).copy(alpha = 0.25f)
    val WarningRed = Color(0xFFFF453A) // Apple System Red

    // Typography & Contrast
    val TextPrimary = Color(0xFFF5F5F7)
    val TextSecondary = Color(0xFF98989D)
    val TextTertiary = Color(0xFF636366)

    // Continuous Squircle & Pill Shapes
    val BoardShape = RoundedCornerShape(16.dp)
    val VehicleShape = RoundedCornerShape(10.dp)
    val CellShape = RoundedCornerShape(8.dp)
    val PillShape = RoundedCornerShape(100.dp)
    val CardShape = RoundedCornerShape(12.dp)
    val ModalShape = RoundedCornerShape(20.dp)
}
