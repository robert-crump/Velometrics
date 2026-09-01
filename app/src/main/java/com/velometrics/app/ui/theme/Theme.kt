package com.velometrics.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

// Fallback scheme for API < 31 (no dynamic color) — seeded from the launcher icon's green
// rather than the Compose template's placeholder purple. See Color.kt for how it was generated.
private val DarkColorScheme = darkColorScheme(
    primary = SeedGreenPrimaryDark,
    onPrimary = SeedGreenOnPrimaryDark,
    primaryContainer = SeedGreenPrimaryContainerDark,
    onPrimaryContainer = SeedGreenOnPrimaryContainerDark,
    secondary = SeedGreenSecondaryDark,
    onSecondary = SeedGreenOnSecondaryDark,
    secondaryContainer = SeedGreenSecondaryContainerDark,
    onSecondaryContainer = SeedGreenOnSecondaryContainerDark,
    tertiary = SeedGreenTertiaryDark,
    onTertiary = SeedGreenOnTertiaryDark,
    tertiaryContainer = SeedGreenTertiaryContainerDark,
    onTertiaryContainer = SeedGreenOnTertiaryContainerDark,
    background = SeedGreenBackgroundDark,
    onBackground = SeedGreenOnBackgroundDark,
    surface = SeedGreenSurfaceDark,
    onSurface = SeedGreenOnSurfaceDark,
    surfaceVariant = SeedGreenSurfaceVariantDark,
    onSurfaceVariant = SeedGreenOnSurfaceVariantDark,
    outline = SeedGreenOutlineDark,
    outlineVariant = SeedGreenOutlineVariantDark,
    inverseSurface = SeedGreenInverseSurfaceDark,
    inverseOnSurface = SeedGreenInverseOnSurfaceDark,
    inversePrimary = SeedGreenInversePrimaryDark
)

private val LightColorScheme = lightColorScheme(
    primary = SeedGreenPrimaryLight,
    onPrimary = SeedGreenOnPrimaryLight,
    primaryContainer = SeedGreenPrimaryContainerLight,
    onPrimaryContainer = SeedGreenOnPrimaryContainerLight,
    secondary = SeedGreenSecondaryLight,
    onSecondary = SeedGreenOnSecondaryLight,
    secondaryContainer = SeedGreenSecondaryContainerLight,
    onSecondaryContainer = SeedGreenOnSecondaryContainerLight,
    tertiary = SeedGreenTertiaryLight,
    onTertiary = SeedGreenOnTertiaryLight,
    tertiaryContainer = SeedGreenTertiaryContainerLight,
    onTertiaryContainer = SeedGreenOnTertiaryContainerLight,
    background = SeedGreenBackgroundLight,
    onBackground = SeedGreenOnBackgroundLight,
    surface = SeedGreenSurfaceLight,
    onSurface = SeedGreenOnSurfaceLight,
    surfaceVariant = SeedGreenSurfaceVariantLight,
    onSurfaceVariant = SeedGreenOnSurfaceVariantLight,
    outline = SeedGreenOutlineLight,
    outlineVariant = SeedGreenOutlineVariantLight,
    inverseSurface = SeedGreenInverseSurfaceLight,
    inverseOnSurface = SeedGreenInverseOnSurfaceLight,
    inversePrimary = SeedGreenInversePrimaryLight
)

@Composable
fun VelometricsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
