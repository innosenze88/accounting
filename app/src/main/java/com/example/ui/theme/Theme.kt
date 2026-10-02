package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DarkColorScheme =
  darkColorScheme(
    primary = Color(0xFF5DBB84),
    onPrimary = Color(0xFF0C2A1C),
    primaryContainer = Color(0xFF1D3327),
    onPrimaryContainer = Color(0xFFC8E7D4),
    secondary = Color(0xFF9BD3B1),
    onSecondary = Color(0xFF0C2A1C),
    secondaryContainer = Color(0xFF283F33),
    onSecondaryContainer = Color(0xFFC8E7D4),
    tertiary = Color(0xFFE8B866),
    onTertiary = Color(0xFF3A2600),
    tertiaryContainer = Color(0xFF332C20),
    onTertiaryContainer = Color(0xFFF1DFB8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = NightPage,
    onBackground = Color(0xFFE6EBF0),
    surface = NightCard,
    onSurface = Color(0xFFE6EBF0),
    surfaceVariant = NightMuted,
    onSurfaceVariant = Color(0xFFA3B0BE),
    outline = Color(0xFF6B7889),
    outlineVariant = NightCardBorder,
    surfaceContainerLowest = NightPage,
    surfaceContainerLow = NightCard,
    surfaceContainer = NightCard,
    surfaceContainerHigh = NightCard,
    surfaceContainerHighest = NightCard
  )

private val LightColorScheme =
  lightColorScheme(
    primary = SageGreen,
    onPrimary = Color.White,
    primaryContainer = SageGreenSoft,
    onPrimaryContainer = SageGreenDeep,
    secondary = SageGreenText,
    onSecondary = Color.White,
    secondaryContainer = SageGreenSoft,
    onSecondaryContainer = SageGreenDeep,
    tertiary = ToneAmber,
    onTertiary = Color.White,
    tertiaryContainer = NoticeBackground,
    onTertiaryContainer = NoticeText,
    error = Color(0xFFCB2A2A),
    onError = Color.White,
    errorContainer = BrandRedContainer,
    onErrorContainer = Color(0xFF5F1410),
    background = PageBackground,
    onBackground = InkText,
    surface = CardIvory,
    onSurface = InkText,
    surfaceVariant = MutedFill,
    onSurfaceVariant = MutedInk,
    outline = Color(0xFFA0ABB6),
    outlineVariant = CardBorder,
    surfaceContainerLowest = CardIvory,
    surfaceContainerLow = CardIvory,
    surfaceContainer = CardIvory,
    surfaceContainerHigh = CardIvory,
    surfaceContainerHighest = CardIvory
  )

/** Rounder corners everywhere, matching the web app's cards and buttons. */
private val AppShapes =
  Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, shapes = AppShapes, content = content)
}
