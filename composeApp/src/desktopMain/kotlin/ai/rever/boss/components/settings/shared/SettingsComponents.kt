package ai.rever.boss.components.settings.shared

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.components.settings.shared.SettingsTheme.AccentColor
import ai.rever.boss.components.settings.shared.SettingsTheme.BackgroundColor
import ai.rever.boss.components.settings.shared.SettingsTheme.BorderColor
import ai.rever.boss.components.settings.shared.SettingsTheme.SurfaceColor
import ai.rever.boss.components.settings.shared.SettingsTheme.TextMuted
import ai.rever.boss.components.settings.shared.SettingsTheme.TextPrimary
import ai.rever.boss.components.settings.shared.SettingsTheme.TextSecondary
import kotlin.math.max
import kotlin.math.min

/**
 * A section container with a title header and optional description.
 */
@Composable
fun SettingsSection(
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
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (description != null) {
            Text(
                text = description,
                color = TextSecondary,
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
fun SettingsToggle(
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
            .background(SurfaceColor)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (enabled) TextPrimary else TextMuted,
                fontSize = 13.sp
            )
            if (description != null) {
                Text(
                    text = description,
                    color = TextMuted,
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
                checkedThumbColor = AccentColor,
                checkedTrackColor = AccentColor.copy(alpha = 0.5f),
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = BorderColor
            )
        )
    }
}

/**
 * A slider with label and value display.
 *
 * @param onValueChange Called on every drag event for immediate UI feedback
 * @param onValueChangeFinished Called when user releases the slider (use for persistence)
 */
@Composable
fun SettingsSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    valueDisplay: (Float) -> String = { "%.1f".format(it) },
    description: String? = null,
    enabled: Boolean = true
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceColor)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = if (enabled) TextPrimary else TextMuted,
                    fontSize = 13.sp
                )
                if (description != null) {
                    Text(
                        text = description,
                        color = TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Text(
                text = valueDisplay(value),
                color = AccentColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.padding(top = 4.dp),
            colors = SliderDefaults.colors(
                thumbColor = AccentColor,
                activeTrackColor = AccentColor,
                inactiveTrackColor = BorderColor,
                disabledThumbColor = TextMuted,
                disabledActiveTrackColor = TextMuted,
                disabledInactiveTrackColor = BorderColor
            )
        )
    }
}

/**
 * An integer number input field.
 */
@Composable
fun SettingsNumberInput(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    range: IntRange = Int.MIN_VALUE..Int.MAX_VALUE,
    description: String? = null,
    enabled: Boolean = true
) {
    var textValue by remember(value) { mutableStateOf(value.toString()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceColor)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = if (enabled) TextPrimary else TextMuted,
                    fontSize = 13.sp
                )
                if (description != null) {
                    Text(
                        text = description,
                        color = TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            BasicTextField(
                value = textValue,
                onValueChange = { newText ->
                    textValue = newText
                    newText.toIntOrNull()?.let { parsed ->
                        if (parsed in range) {
                            onValueChange(parsed)
                        }
                    }
                },
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(
                    color = TextPrimary,
                    fontSize = 13.sp
                ),
                cursorBrush = SolidColor(AccentColor),
                modifier = Modifier
                    .width(80.dp)
                    .background(BackgroundColor, RoundedCornerShape(4.dp))
                    .border(1.dp, BorderColor, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * A long number input field (for nanosecond values, etc.).
 */
@Composable
fun SettingsLongInput(
    label: String,
    value: Long,
    onValueChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    range: LongRange = Long.MIN_VALUE..Long.MAX_VALUE,
    description: String? = null,
    enabled: Boolean = true
) {
    var textValue by remember(value) { mutableStateOf(value.toString()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceColor)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = if (enabled) TextPrimary else TextMuted,
                    fontSize = 13.sp
                )
                if (description != null) {
                    Text(
                        text = description,
                        color = TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            BasicTextField(
                value = textValue,
                onValueChange = { newText ->
                    textValue = newText
                    newText.toLongOrNull()?.let { parsed ->
                        if (parsed in range) {
                            onValueChange(parsed)
                        }
                    }
                },
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(
                    color = TextPrimary,
                    fontSize = 13.sp
                ),
                cursorBrush = SolidColor(AccentColor),
                modifier = Modifier
                    .width(120.dp)
                    .background(BackgroundColor, RoundedCornerShape(4.dp))
                    .border(1.dp, BorderColor, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * A text input field.
 */
@Composable
fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    description: String? = null,
    enabled: Boolean = true
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceColor)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            color = if (enabled) TextPrimary else TextMuted,
            fontSize = 13.sp
        )
        if (description != null) {
            Text(
                text = description,
                color = TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(
                color = TextPrimary,
                fontSize = 13.sp
            ),
            cursorBrush = SolidColor(AccentColor),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BackgroundColor, RoundedCornerShape(4.dp))
                        .border(1.dp, BorderColor, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    }
                    innerTextField()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
    }
}

/**
 * A dropdown selector.
 */
@Composable
fun SettingsDropdown(
    label: String,
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceColor)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = if (enabled) TextPrimary else TextMuted,
                    fontSize = 13.sp
                )
                if (description != null) {
                    Text(
                        text = description,
                        color = TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(BackgroundColor)
                        .border(1.dp, BorderColor, RoundedCornerShape(4.dp))
                        .clickable(enabled = enabled) { expanded = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedOption,
                        color = TextPrimary,
                        fontSize = 13.sp
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Expand",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(SurfaceColor)
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            onClick = {
                                onOptionSelected(option)
                                expanded = false
                            }
                        ) {
                            Text(
                                text = option,
                                color = if (option == selectedOption) AccentColor else TextPrimary,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A sectioned dropdown selector with grouped options.
 * Each section has a non-selectable header.
 */
@Composable
fun SettingsSectionedDropdown(
    label: String,
    sections: Map<String, List<String>>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceColor)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = if (enabled) TextPrimary else TextMuted,
                    fontSize = 13.sp
                )
                if (description != null) {
                    Text(
                        text = description,
                        color = TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(BackgroundColor)
                        .border(1.dp, BorderColor, RoundedCornerShape(4.dp))
                        .clickable(enabled = enabled) { expanded = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedOption,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        maxLines = 1
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Expand",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier
                        .background(SurfaceColor)
                        .heightIn(max = 400.dp)
                ) {
                    sections.forEach { (sectionName, options) ->
                        // Section header (non-selectable)
                        DropdownMenuItem(
                            onClick = { /* Non-selectable */ },
                            enabled = false
                        ) {
                            Text(
                                text = sectionName,
                                color = AccentColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        // Section items
                        options.forEach { option ->
                            DropdownMenuItem(
                                onClick = {
                                    onOptionSelected(option)
                                    expanded = false
                                },
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Text(
                                    text = option,
                                    color = if (option == selectedOption) AccentColor else TextPrimary,
                                    fontSize = 13.sp
                                )
                            }
                        }
                        // Divider between sections (except after last)
                        if (sectionName != sections.keys.last()) {
                            Divider(color = BorderColor, modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * A color setting with preview swatch that opens a color picker.
 */
@Composable
fun ColorSetting(
    label: String,
    color: Color,
    onColorChange: (Color) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true
) {
    var showColorPicker by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceColor)
            .clickable(enabled = enabled) { showColorPicker = true }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (enabled) TextPrimary else TextMuted,
                fontSize = 13.sp
            )
            if (description != null) {
                Text(
                    text = description,
                    color = TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Hex value display
            Text(
                text = color.toHexString(),
                color = TextSecondary,
                fontSize = 11.sp
            )
            // Color swatch
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
                    .border(1.dp, BorderColor, RoundedCornerShape(4.dp))
            )
        }
    }

    if (showColorPicker) {
        ColorPickerDialog(
            initialColor = color,
            onColorSelected = { newColor ->
                onColorChange(newColor)
                showColorPicker = false
            },
            onDismiss = { showColorPicker = false }
        )
    }
}

/**
 * Convert Color to hex string format.
 */
fun Color.toHexString(): String {
    val argb = (this.alpha * 255).toInt().shl(24) or
            (this.red * 255).toInt().shl(16) or
            (this.green * 255).toInt().shl(8) or
            (this.blue * 255).toInt()
    return "#${argb.toUInt().toString(16).uppercase().padStart(8, '0')}"
}

/**
 * A file picker with text field and browse button.
 */
@Composable
fun SettingsFilePicker(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    fileExtensions: List<String> = emptyList(),
    enabled: Boolean = true
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceColor)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            color = if (enabled) TextPrimary else TextMuted,
            fontSize = 13.sp
        )
        if (description != null) {
            Text(
                text = description,
                color = TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(
                    color = TextPrimary,
                    fontSize = 13.sp
                ),
                cursorBrush = SolidColor(AccentColor),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(BackgroundColor, RoundedCornerShape(4.dp))
                            .border(1.dp, BorderColor, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        if (value.isEmpty()) {
                            Text(
                                text = "No file selected",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                        }
                        innerTextField()
                    }
                },
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    val fileDialog = java.awt.FileDialog(null as java.awt.Frame?, "Select File", java.awt.FileDialog.LOAD)
                    if (fileExtensions.isNotEmpty()) {
                        fileDialog.setFilenameFilter { _, name ->
                            fileExtensions.any { ext -> name.lowercase().endsWith(".$ext") }
                        }
                    }
                    fileDialog.isVisible = true
                    val selectedFile = fileDialog.file
                    val selectedDir = fileDialog.directory
                    if (selectedFile != null && selectedDir != null) {
                        onValueChange(selectedDir + selectedFile)
                    }
                },
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = AccentColor,
                    contentColor = BossTheme.colors.onSignal
                ),
                modifier = Modifier.height(36.dp)
            ) {
                Text("Browse", fontSize = 12.sp)
            }
            if (value.isNotEmpty()) {
                TextButton(
                    onClick = { onValueChange("") },
                    enabled = enabled
                ) {
                    Text("Clear", color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * A clickable info/action row with optional icon.
 */
@Composable
fun SettingsInfoRow(
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
            .background(SurfaceColor)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = TextPrimary,
                fontSize = 13.sp
            )
            if (description != null) {
                Text(
                    text = description,
                    color = TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Text(
            text = value,
            color = if (onClick != null) AccentColor else TextSecondary,
            fontSize = 13.sp
        )
    }
}

/**
 * A button row for actions like "Reset" or "Clear".
 */
@Composable
fun SettingsButtonRow(
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
            .background(SurfaceColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (enabled) TextPrimary else TextMuted,
                fontSize = 13.sp
            )
            if (description != null) {
                Text(
                    text = description,
                    color = TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        TextButton(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.textButtonColors(
                contentColor = if (isDestructive) BossTheme.colors.alert else AccentColor,
                disabledContentColor = TextMuted
            )
        ) {
            Text(buttonText, fontSize = 13.sp)
        }
    }
}

// ============== Color Picker Dialog ==============

/**
 * HSV color picker dialog.
 */
@Composable
fun ColorPickerDialog(
    initialColor: Color,
    onColorSelected: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    // Convert initial color to HSV
    val initialHsv = remember(initialColor) { colorToHsv(initialColor) }
    var hue by remember { mutableStateOf(initialHsv[0]) }
    var saturation by remember { mutableStateOf(initialHsv[1]) }
    var value by remember { mutableStateOf(initialHsv[2]) }
    var alpha by remember { mutableStateOf(initialColor.alpha) }

    // Current color from HSV
    val currentColor = remember(hue, saturation, value, alpha) {
        hsvToColor(hue, saturation, value, alpha)
    }

    // Hex input
    var hexInput by remember(currentColor) {
        mutableStateOf(colorToHex(currentColor))
    }

    DialogWindow(
        onCloseRequest = onDismiss,
        title = "Select Color",
        resizable = false,
        state = rememberDialogState(size = DpSize(340.dp, 520.dp))
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = BackgroundColor
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Saturation/Value canvas
                Text(
                    text = "Saturation / Brightness",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                SaturationValuePicker(
                    hue = hue,
                    saturation = saturation,
                    value = value,
                    onSaturationValueChange = { s, v ->
                        saturation = s
                        value = v
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                )

                // Hue slider
                Text(
                    text = "Hue",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                HueSlider(
                    hue = hue,
                    onHueChange = { hue = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                )

                // Alpha slider
                Text(
                    text = "Alpha",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                AlphaSlider(
                    alpha = alpha,
                    color = hsvToColor(hue, saturation, value, 1f),
                    onAlphaChange = { alpha = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                )

                // Preview and hex input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Color preview
                    Column {
                        Text(
                            text = "Preview",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // New color
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(currentColor)
                                    .border(1.dp, BorderColor, RoundedCornerShape(6.dp))
                            )
                            // Original color
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(initialColor)
                                    .border(1.dp, BorderColor, RoundedCornerShape(6.dp))
                            )
                        }
                    }

                    // Hex input
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Hex (ARGB)",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        BasicTextField(
                            value = hexInput,
                            onValueChange = { newHex ->
                                hexInput = newHex
                                parseHexColor(newHex)?.let { parsed ->
                                    val hsv = colorToHsv(parsed)
                                    hue = hsv[0]
                                    saturation = hsv[1]
                                    value = hsv[2]
                                    alpha = parsed.alpha
                                }
                            },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = TextPrimary,
                                fontSize = 13.sp
                            ),
                            cursorBrush = SolidColor(AccentColor),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SurfaceColor, RoundedCornerShape(4.dp))
                                .border(1.dp, BorderColor, RoundedCornerShape(4.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = SurfaceColor
                        ),
                        modifier = Modifier.width(100.dp)
                    ) {
                        Text("Cancel", color = TextPrimary, fontSize = 13.sp)
                    }
                    Button(
                        onClick = { onColorSelected(currentColor) },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = AccentColor
                        ),
                        modifier = Modifier.width(100.dp)
                    ) {
                        Text("OK", color = BossTheme.colors.onSignal, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/**
 * Saturation/Value 2D picker canvas.
 */
@Composable
private fun SaturationValuePicker(
    hue: Float,
    saturation: Float,
    value: Float,
    onSaturationValueChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val baseColor = hsvToColor(hue, 1f, 1f, 1f)

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(6.dp))
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val s = (offset.x / size.width).coerceIn(0f, 1f)
                        val v = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                        onSaturationValueChange(s, v)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        val s = (change.position.x / size.width).coerceIn(0f, 1f)
                        val v = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                        onSaturationValueChange(s, v)
                    }
                }
        ) {
            // White to hue gradient (horizontal - saturation)
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.White, baseColor)
                )
            )
            // Transparent to black gradient (vertical - value)
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black)
                )
            )
            // Border
            drawRect(
                color = BorderColor,
                style = Stroke(width = 1.dp.toPx())
            )

            // Selection indicator
            val indicatorX = saturation * size.width
            val indicatorY = (1f - value) * size.height
            drawCircle(
                color = Color.White,
                radius = 8.dp.toPx(),
                center = Offset(indicatorX, indicatorY),
                style = Stroke(width = 2.dp.toPx())
            )
            drawCircle(
                color = Color.Black,
                radius = 6.dp.toPx(),
                center = Offset(indicatorX, indicatorY),
                style = Stroke(width = 1.dp.toPx())
            )
        }
    }
}

/**
 * Horizontal hue slider (0-360).
 */
@Composable
private fun HueSlider(
    hue: Float,
    onHueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val hueColors = remember {
        listOf(
            Color.Red,
            Color.Yellow,
            Color.Green,
            Color.Cyan,
            Color.Blue,
            Color.Magenta,
            Color.Red
        )
    }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(4.dp))
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        onHueChange((offset.x / size.width * 360f).coerceIn(0f, 360f))
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        onHueChange((change.position.x / size.width * 360f).coerceIn(0f, 360f))
                    }
                }
        ) {
            // Hue gradient
            drawRect(
                brush = Brush.horizontalGradient(colors = hueColors)
            )
            // Border
            drawRect(
                color = BorderColor,
                style = Stroke(width = 1.dp.toPx())
            )

            // Selection indicator
            val indicatorX = (hue / 360f) * size.width
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(indicatorX - 4.dp.toPx(), 0f),
                size = Size(8.dp.toPx(), size.height),
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

/**
 * Alpha slider (0-1).
 */
@Composable
private fun AlphaSlider(
    alpha: Float,
    color: Color,
    onAlphaChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(4.dp))
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        onAlphaChange((offset.x / size.width).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        onAlphaChange((change.position.x / size.width).coerceIn(0f, 1f))
                    }
                }
        ) {
            // Checkerboard pattern for transparency
            val checkerSize = 8.dp.toPx()
            for (row in 0 until (size.height / checkerSize).toInt() + 1) {
                for (col in 0 until (size.width / checkerSize).toInt() + 1) {
                    val isLight = (row + col) % 2 == 0
                    drawRect(
                        color = if (isLight) Color(0xFFCCCCCC) else Color(0xFF999999),
                        topLeft = Offset(col * checkerSize, row * checkerSize),
                        size = Size(checkerSize, checkerSize)
                    )
                }
            }

            // Alpha gradient
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(color.copy(alpha = 0f), color.copy(alpha = 1f))
                )
            )
            // Border
            drawRect(
                color = BorderColor,
                style = Stroke(width = 1.dp.toPx())
            )

            // Selection indicator
            val indicatorX = alpha * size.width
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(indicatorX - 4.dp.toPx(), 0f),
                size = Size(8.dp.toPx(), size.height),
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

// Color conversion utilities

/**
 * Convert Color to HSV array [hue, saturation, value].
 */
private fun colorToHsv(color: Color): FloatArray {
    val r = color.red
    val g = color.green
    val b = color.blue

    val maxC = max(max(r, g), b)
    val minC = min(min(r, g), b)
    val delta = maxC - minC

    val h = when {
        delta == 0f -> 0f
        maxC == r -> 60f * (((g - b) / delta) % 6)
        maxC == g -> 60f * (((b - r) / delta) + 2)
        else -> 60f * (((r - g) / delta) + 4)
    }.let { if (it < 0) it + 360 else it }

    val s = if (maxC == 0f) 0f else delta / maxC
    val v = maxC

    return floatArrayOf(h, s, v)
}

/**
 * Convert HSV to Color.
 */
private fun hsvToColor(hue: Float, saturation: Float, value: Float, alpha: Float = 1f): Color {
    val c = value * saturation
    val x = c * (1 - kotlin.math.abs((hue / 60f) % 2 - 1))
    val m = value - c

    val (r, g, b) = when {
        hue < 60 -> Triple(c, x, 0f)
        hue < 120 -> Triple(x, c, 0f)
        hue < 180 -> Triple(0f, c, x)
        hue < 240 -> Triple(0f, x, c)
        hue < 300 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }

    return Color(
        red = (r + m).coerceIn(0f, 1f),
        green = (g + m).coerceIn(0f, 1f),
        blue = (b + m).coerceIn(0f, 1f),
        alpha = alpha
    )
}

/**
 * Convert Color to hex string (ARGB format).
 */
private fun colorToHex(color: Color): String {
    val a = (color.alpha * 255).toInt()
    val r = (color.red * 255).toInt()
    val g = (color.green * 255).toInt()
    val b = (color.blue * 255).toInt()
    return "#%02X%02X%02X%02X".format(a, r, g, b)
}

/**
 * Parse hex string to Color. Supports:
 * - #AARRGGBB (8 chars)
 * - #RRGGBB (6 chars)
 * - 0xAARRGGBB
 * - 0xRRGGBB
 */
private fun parseHexColor(hex: String): Color? {
    val cleanHex = hex.trim()
        .removePrefix("#")
        .removePrefix("0x")
        .removePrefix("0X")
        .uppercase()

    return try {
        when (cleanHex.length) {
            6 -> {
                val rgb = cleanHex.toLong(16)
                Color(
                    red = ((rgb shr 16) and 0xFF) / 255f,
                    green = ((rgb shr 8) and 0xFF) / 255f,
                    blue = (rgb and 0xFF) / 255f,
                    alpha = 1f
                )
            }
            8 -> {
                val argb = cleanHex.toLong(16)
                Color(
                    alpha = ((argb shr 24) and 0xFF) / 255f,
                    red = ((argb shr 16) and 0xFF) / 255f,
                    green = ((argb shr 8) and 0xFF) / 255f,
                    blue = (argb and 0xFF) / 255f
                )
            }
            else -> null
        }
    } catch (e: NumberFormatException) {
        null
    }
}
