package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = Purple80,
    onPrimary = Color(0xFF0B2E68),
    primaryContainer = Color(0xFF173C74),
    onPrimaryContainer = Color(0xFFD7E5FF),
    secondary = Pink80,
    onSecondary = Color(0xFF0A3B1B),
    secondaryContainer = Color(0xFF174D2C),
    onSecondaryContainer = Color(0xFFC4F4CE),
    tertiary = Color(0xFFFFBE55),
    onTertiary = Color(0xFF472900),
    tertiaryContainer = Color(0xFF664000),
    onTertiaryContainer = Color(0xFFFFDEA6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = NightBackground,
    onBackground = Color(0xFFE1E2E7),
    surface = NightSurface,
    onSurface = Color(0xFFE1E2E7),
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = Color(0xFFC2C6D0),
    outline = Color(0xFF8C919C),
    outlineVariant = Color(0xFF414751)
  )

private val LightColorScheme =
  lightColorScheme(
    primary = Purple40,
    onPrimary = Color.White,
    primaryContainer = BrandBlueContainer,
    onPrimaryContainer = NavyDark,
    secondary = PurpleGrey40,
    onSecondary = Color.White,
    secondaryContainer = BrandGreenContainer,
    onSecondaryContainer = Color(0xFF12391B),
    tertiary = Pink40,
    onTertiary = Color.White,
    tertiaryContainer = BrandAmberContainer,
    onTertiaryContainer = Color(0xFF5B3A00),
    error = Color(0xFFB42318),
    onError = Color.White,
    errorContainer = BrandRedContainer,
    onErrorContainer = Color(0xFF5F1410),
    background = SlateSurface,
    onBackground = SlateTextDark,
    surface = Color.White,
    onSurface = SlateTextDark,
    surfaceVariant = Color(0xFFEDF1F7),
    onSurfaceVariant = Color(0xFF465266),
    outline = Color(0xFF778399),
    outlineVariant = SlateBorder
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

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
