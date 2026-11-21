package com.ashavaani.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val OutdoorDaylightColorScheme = lightColorScheme(
    primary = AshaColors.ClinicalTeal,
    onPrimary = AshaColors.PureWhite,
    primaryContainer = AshaColors.ClinicalTealTint,
    onPrimaryContainer = AshaColors.ClinicalTeal,
    secondary = AshaColors.VoiceDiffAmber,
    onSecondary = AshaColors.PureWhite,
    secondaryContainer = AshaColors.VoiceDiffSurface,
    onSecondaryContainer = AshaColors.VoiceDiffAmberDark,
    tertiary = AshaColors.VerifiedEmerald,
    onTertiary = AshaColors.PureWhite,
    tertiaryContainer = AshaColors.VerifiedSurface,
    onTertiaryContainer = AshaColors.VerifiedEmerald,
    error = AshaColors.DangerCrimson,
    onError = AshaColors.PureWhite,
    errorContainer = AshaColors.DangerBadgeSurface,
    onErrorContainer = AshaColors.DangerCrimsonDark,
    background = AshaColors.SunlitCanvas,
    onBackground = AshaColors.InkPrimary,
    surface = AshaColors.PureWhite,
    onSurface = AshaColors.InkPrimary,
    surfaceVariant = AshaColors.SurfaceMuted,
    onSurfaceVariant = AshaColors.InkSecondary,
    outline = AshaColors.CardBorder,
    outlineVariant = AshaColors.DividerSubtle,
)

private val AshaShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

@Composable
fun AshaVaaniTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = OutdoorDaylightColorScheme,
        typography = AshaTypography,
        shapes = AshaShapes,
        content = content,
    )
}
