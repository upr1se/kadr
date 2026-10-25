package com.upr1se.kadr.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class KadrThemeMode {
  SYSTEM,
  LIGHT,
  DARK,
  SEPIA,
}

private val LightColorScheme =
  lightColorScheme(
    primary = LightAccent,
    onPrimary = LightOnAccent,
    primaryContainer = LightAccentContainer,
    onPrimaryContainer = LightOnAccentContainer,
    secondary = LightSecondary,
    onSecondary = Color.White,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnBackground,
    tertiary = LightAccent,
    onTertiary = LightOnAccent,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightBackground,
    onSurface = LightOnBackground,
    surfaceVariant = Color(0xFFEDE5D8),
    onSurfaceVariant = LightSecondary,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    outline = LightSecondary,
    outlineVariant = LightOutlineVariant,
    error = LightError,
    onError = Color.White,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
  )

private val DarkColorScheme =
  darkColorScheme(
    primary = DarkAccent,
    onPrimary = DarkOnAccent,
    primaryContainer = DarkAccentContainer,
    onPrimaryContainer = DarkOnAccentContainer,
    secondary = DarkSecondary,
    onSecondary = DarkBackground,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnBackground,
    tertiary = DarkAccent,
    onTertiary = DarkOnAccent,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkBackground,
    onSurface = DarkOnBackground,
    surfaceVariant = Color(0xFF241F1A),
    onSurfaceVariant = DarkSecondary,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    outline = DarkSecondary,
    outlineVariant = DarkOutlineVariant,
    error = DarkError,
    onError = Color(0xFF690005),
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
  )

private val SepiaColorScheme =
  lightColorScheme(
    primary = SepiaAccent,
    onPrimary = SepiaOnAccent,
    primaryContainer = SepiaAccentContainer,
    onPrimaryContainer = SepiaOnAccentContainer,
    secondary = SepiaSecondary,
    onSecondary = Color.White,
    secondaryContainer = SepiaSecondaryContainer,
    onSecondaryContainer = SepiaOnBackground,
    tertiary = SepiaAccent,
    onTertiary = SepiaOnAccent,
    background = SepiaBackground,
    onBackground = SepiaOnBackground,
    surface = SepiaBackground,
    onSurface = SepiaOnBackground,
    surfaceVariant = Color(0xFFDED0B5),
    onSurfaceVariant = SepiaSecondary,
    surfaceContainerLowest = SepiaSurfaceContainerLowest,
    surfaceContainerLow = SepiaSurfaceContainerLow,
    surfaceContainer = SepiaSurfaceContainer,
    surfaceContainerHigh = SepiaSurfaceContainerHigh,
    surfaceContainerHighest = SepiaSurfaceContainerHighest,
    outline = SepiaSecondary,
    outlineVariant = SepiaOutlineVariant,
    error = SepiaError,
    onError = Color.White,
    errorContainer = SepiaErrorContainer,
    onErrorContainer = SepiaOnErrorContainer,
  )

@Composable
@Suppress("DEPRECATION")
fun KadrTheme(
  themeMode: KadrThemeMode = KadrThemeMode.SYSTEM,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when (themeMode) {
      KadrThemeMode.SYSTEM ->
        if (isSystemInDarkTheme()) DarkColorScheme else LightColorScheme
      KadrThemeMode.LIGHT -> LightColorScheme
      KadrThemeMode.DARK -> DarkColorScheme
      KadrThemeMode.SEPIA -> SepiaColorScheme
    }
  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val window = view.context.findActivity()?.window ?: return@SideEffect
      WindowCompat.getInsetsController(window, view).apply {
        isAppearanceLightStatusBars = colorScheme.background.luminance() > 0.5f
        isAppearanceLightNavigationBars = colorScheme.background.luminance() > 0.5f
      }
      window.statusBarColor = Color.Transparent.toArgb()
      window.navigationBarColor = colorScheme.background.toArgb()
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        window.navigationBarDividerColor = Color.Transparent.toArgb()
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = false
      }
    }
  }

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    content = content,
  )
}

private fun Context.findActivity(): Activity? {
  var current = this
  while (current is ContextWrapper) {
    if (current is Activity) return current
    current = current.baseContext
  }
  return current as? Activity
}
