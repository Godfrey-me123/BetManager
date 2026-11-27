package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ColorClassifier
import com.example.data.model.CrashColorCategory

fun parseSafeCrashColor(hex: String): Color {
    val parsed = ColorClassifier.parseHexToArgb(hex)
    return if (parsed != null) Color(parsed) else Color.Gray
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VisualCrashColorPicker(
    initialHex: String = CrashColorCategory.Pink.defaultHex,
    onAddPickedColor: (category: CrashColorCategory, hex: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val initialArgb = remember(initialHex) {
        ColorClassifier.parseHexToArgb(initialHex) ?: android.graphics.Color.parseColor("#EC4899")
    }
    val initialHsv = remember(initialArgb) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initialArgb, it) }
    }

    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var sat by remember { mutableFloatStateOf(initialHsv[1].coerceAtLeast(0.65f)) }
    var value by remember { mutableFloatStateOf(initialHsv[2].coerceAtLeast(0.75f)) }

    val currentColorInt = remember(hue, sat, value) {
        android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
    }
    val currentHex = remember(currentColorInt) { ColorClassifier.formatArgbHex(currentColorInt) }
    var hexInput by remember(currentHex) { mutableStateOf(currentHex) }
    val displayHex = ColorClassifier.parseHexToArgb(hexInput)?.let { ColorClassifier.formatArgbHex(it) } ?: currentHex
    val classifiedCategory = remember(displayHex) { ColorClassifier.classifyHex(displayHex) }
    val previewColor = remember(displayHex) { parseSafeCrashColor(displayHex) }

    val presetSwatches = remember {
        listOf(
            "#2563EB", // Blue (<2x)
            "#7C3AED", // Purple (2x-10x)
            "#EC4899", // Pink (>=10x)
            "#DC2626", // Red
            "#16A34A", // Green
            "#CA8A04", // Yellow
            "#EA580C", // Orange
            "#06B6D4"  // Cyan
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .testTag("visual_color_picker")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Visual Color Picker",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(previewColor)
                            .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    )
                    Text(
                        text = "$displayHex (${classifiedCategory.displayName})",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // 2D Saturation / Brightness canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    .testTag("color_picker_sv_pad")
            ) {
                Canvas(
                    modifier = Modifier
                        .matchParentSize()
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                sat = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                value = 1f - (offset.y / size.height.toFloat()).coerceIn(0f, 1f)
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                sat = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                value = 1f - (change.position.y / size.height.toFloat()).coerceIn(0f, 1f)
                            }
                        }
                ) {
                    val pureHueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                    drawRect(
                        brush = Brush.horizontalGradient(listOf(Color.White, pureHueColor))
                    )
                    drawRect(
                        brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
                    )
                    val cx = sat * size.width
                    val cy = (1f - value) * size.height
                    drawCircle(
                        color = Color.White,
                        radius = 12f,
                        center = Offset(cx, cy),
                        style = Stroke(width = 3f)
                    )
                    drawCircle(
                        color = Color.Black,
                        radius = 15f,
                        center = Offset(cx, cy),
                        style = Stroke(width = 1.5f)
                    )
                }
            }

            // Hue rainbow bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .testTag("color_picker_hue_bar")
            ) {
                Canvas(
                    modifier = Modifier
                        .matchParentSize()
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                hue = ((offset.x / size.width.toFloat()).coerceIn(0f, 1f)) * 360f
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                hue = ((change.position.x / size.width.toFloat()).coerceIn(0f, 1f)) * 360f
                            }
                        }
                ) {
                    val rainbow = listOf(
                        Color.Red,
                        Color.Yellow,
                        Color.Green,
                        Color.Cyan,
                        Color.Blue,
                        Color.Magenta,
                        Color.Red
                    )
                    drawRect(brush = Brush.horizontalGradient(rainbow))
                    val hx = (hue / 360f) * size.width
                    drawCircle(
                        color = Color.White,
                        radius = size.height * 0.42f,
                        center = Offset(hx, size.height / 2f),
                        style = Stroke(width = 3f)
                    )
                }
            }

            // Quick HEX swatches
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presetSwatches.forEach { swatchHex ->
                    val selected = displayHex.equals(swatchHex, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(parseSafeCrashColor(swatchHex))
                            .border(
                                width = if (selected) 2.5.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape
                            )
                            .clickable {
                                val argb = ColorClassifier.parseHexToArgb(swatchHex)
                                if (argb != null) {
                                    val hsv = FloatArray(3)
                                    android.graphics.Color.colorToHSV(argb, hsv)
                                    hue = hsv[0]
                                    sat = hsv[1]
                                    value = hsv[2]
                                    hexInput = swatchHex
                                }
                            }
                            .testTag("color_picker_swatch_${swatchHex.removePrefix("#")}")
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { raw ->
                        hexInput = raw.uppercase()
                        ColorClassifier.parseHexToArgb(raw)?.let { argb ->
                            val hsv = FloatArray(3)
                            android.graphics.Color.colorToHSV(argb, hsv)
                            hue = hsv[0]
                            sat = hsv[1]
                            value = hsv[2]
                        }
                    },
                    label = { Text("HEX (#RRGGBB)", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("color_picker_hex_input")
                )
                Button(
                    onClick = {
                        onAddPickedColor(classifiedCategory, displayHex)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(previewColor.toArgb())
                    ),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("color_picker_add_btn")
                ) {
                    Text(
                        text = "+ Add Color",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (value > 0.65f && sat < 0.4f) Color.Black else Color.White
                    )
                }
            }
        }
    }
}
