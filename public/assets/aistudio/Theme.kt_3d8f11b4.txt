package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val BetManagerColorScheme = lightColorScheme(
    primary = EmeraldPrimary,
    onPrimary = OnAccent,
    primaryContainer = EmeraldContainer,
    onPrimaryContainer = EmeraldDeep,
    secondary = GoldAccent,
    onSecondary = OnAccent,
    secondaryContainer = GoldContainer,
    onSecondaryContainer = InkPrimary,
    error = CoralDanger,
    onError = OnAccent,
    errorContainer = CoralContainer,
    onErrorContainer = InkPrimary,
    background = GreyBg,
    onBackground = InkPrimary,
    surface = GreySurface,
    onSurface = InkPrimary,
    surfaceVariant = GreySurfaceVariant,
    onSurfaceVariant = InkSecondary,
    outline = GreyOutline,
    outlineVariant = GreyOutline,
    surfaceTint = GreySurface,
    surfaceContainerLowest = GreySurface,
    surfaceContainerLow = GreySurface,
    surfaceContainer = GreySurface,
    surfaceContainerHigh = GreySurfaceVariant,
    surfaceContainerHighest = GreySurfaceVariant
)

val BetManagerShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp)
)

/** BetManager is light grey only. There is deliberately no dark theme. */
@Composable
fun BetManagerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BetManagerColorScheme,
        typography = Typography,
        shapes = BetManagerShapes,
        content = content
    )
}
