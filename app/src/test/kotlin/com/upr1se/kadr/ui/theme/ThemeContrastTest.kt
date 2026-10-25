package com.upr1se.kadr.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
  @Test
  fun textAndControlsMeetWcagContrastInEveryTheme() {
    assertTheme(
      background = LightBackground,
      onBackground = LightOnBackground,
      secondary = LightSecondary,
      accent = LightAccent,
      onAccent = LightOnAccent,
      error = LightError,
      containers =
        listOf(
          LightSurfaceContainerLowest,
          LightSurfaceContainerLow,
          LightSurfaceContainer,
          LightSurfaceContainerHigh,
          LightSurfaceContainerHighest,
        ),
    )
    assertTheme(
      background = DarkBackground,
      onBackground = DarkOnBackground,
      secondary = DarkSecondary,
      accent = DarkAccent,
      onAccent = DarkOnAccent,
      error = DarkError,
      containers =
        listOf(
          DarkSurfaceContainerLowest,
          DarkSurfaceContainerLow,
          DarkSurfaceContainer,
          DarkSurfaceContainerHigh,
          DarkSurfaceContainerHighest,
        ),
    )
    assertTheme(
      background = SepiaBackground,
      onBackground = SepiaOnBackground,
      secondary = SepiaSecondary,
      accent = SepiaAccent,
      onAccent = SepiaOnAccent,
      error = SepiaError,
      containers =
        listOf(
          SepiaSurfaceContainerLowest,
          SepiaSurfaceContainerLow,
          SepiaSurfaceContainer,
          SepiaSurfaceContainerHigh,
          SepiaSurfaceContainerHighest,
        ),
    )
  }

  private fun assertTheme(
    background: Color,
    onBackground: Color,
    secondary: Color,
    accent: Color,
    onAccent: Color,
    error: Color,
    containers: List<Color>,
  ) {
    assertTrue(contrast(background, onBackground) >= 4.5)
    assertTrue(contrast(background, secondary) >= 4.5)
    assertTrue(contrast(background, accent) >= 3.0)
    assertTrue(contrast(accent, onAccent) >= 4.5)
    assertTrue(contrast(background, error) >= 3.0)
    containers.forEach { container ->
      assertTrue(contrast(container, onBackground) >= 4.5)
    }
  }

  private fun contrast(first: Color, second: Color): Double {
    val a = luminance(first)
    val b = luminance(second)
    return (max(a, b) + 0.05) / (min(a, b) + 0.05)
  }

  private fun luminance(color: Color): Double {
    fun channel(value: Float): Double {
      val channel = value.toDouble()
      return if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
  }
}
