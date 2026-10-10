package com.txdost.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Apple / iOS Human Interface Guidelines inspired color tokens.
 * Semantic naming (systemBlue, label, secondaryFill ...) so screens stay theme-driven.
 */
data class AppleColors(
    val blue: Color,
    val green: Color,
    val red: Color,
    val orange: Color,
    val yellow: Color,
    val teal: Color,
    val indigo: Color,
    val pink: Color,
    val purple: Color,
    val gray: Color,

    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,

    val bg: Color,
    val secondaryBg: Color,
    val tertiaryBg: Color,
    val groupedBg: Color,
    val secondaryGroupedBg: Color,

    val separator: Color,
    val fill: Color,
    val secondaryFill: Color,
    val tertiaryFill: Color,

    val userBubble: Color,
    val aiBubble: Color
)

private val LightApple = AppleColors(
    blue = Color(0xFF007AFF),
    green = Color(0xFF34C759),
    red = Color(0xFFFF3B30),
    orange = Color(0xFFFF9500),
    yellow = Color(0xFFFFCC00),
    teal = Color(0xFF5AC8FA),
    indigo = Color(0xFF5856D6),
    pink = Color(0xFFFF2D55),
    purple = Color(0xFFAF52DE),
    gray = Color(0xFF8E8E93),

    label = Color(0xFF000000),
    secondaryLabel = Color(0x993C3C43),
    tertiaryLabel = Color(0x4D3C3C43),

    bg = Color(0xFFFFFFFF),
    secondaryBg = Color(0xFFF2F2F7),
    tertiaryBg = Color(0xFFFFFFFF),
    groupedBg = Color(0xFFF2F2F7),
    secondaryGroupedBg = Color(0xFFFFFFFF),

    separator = Color(0x33C6C6C8),
    fill = Color(0x33787880),
    secondaryFill = Color(0xFFE9E9EB),
    tertiaryFill = Color(0xFFF2F2F7),

    userBubble = Color(0xFF007AFF),
    aiBubble = Color(0xFFE9E9EB)
)

private val DarkApple = AppleColors(
    blue = Color(0xFF0A84FF),
    green = Color(0xFF30D158),
    red = Color(0xFFFF453A),
    orange = Color(0xFFFF9F0A),
    yellow = Color(0xFFFFD60A),
    teal = Color(0xFF64D2FF),
    indigo = Color(0xFF5E5CE6),
    pink = Color(0xFFFF375F),
    purple = Color(0xFFBF5AF2),
    gray = Color(0xFF8E8E93),

    label = Color(0xFFFFFFFF),
    secondaryLabel = Color(0x99EBEBF5),
    tertiaryLabel = Color(0x4DEBEBF5),

    bg = Color(0xFF000000),
    secondaryBg = Color(0xFF1C1C1E),
    tertiaryBg = Color(0xFF2C2C2E),
    groupedBg = Color(0xFF000000),
    secondaryGroupedBg = Color(0xFF1C1C1E),

    separator = Color(0x33545458),
    fill = Color(0x5C787880),
    secondaryFill = Color(0xFF2C2C2E),
    tertiaryFill = Color(0xFF26262A),

    userBubble = Color(0xFF0A84FF),
    aiBubble = Color(0xFF26262A)
)

val LocalAppleColors = staticCompositionLocalOf { LightApple }

/** Access Apple semantic tokens: `Apple.colors.blue`, `Apple.colors.secondaryLabel` ... */
object Apple {
    val colors: AppleColors
        @Composable @ReadOnlyComposable get() = LocalAppleColors.current
}

@Composable
fun FeaturesticTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val apple = if (darkTheme) DarkApple else LightApple

    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = apple.blue,
            onPrimary = Color.White,
            primaryContainer = apple.blue.copy(alpha = 0.20f),
            onPrimaryContainer = apple.blue,
            secondary = apple.green,
            onSecondary = Color.White,
            tertiary = apple.orange,
            onTertiary = Color.White,
            background = apple.bg,
            onBackground = apple.label,
            surface = apple.secondaryBg,
            onSurface = apple.label,
            surfaceVariant = apple.tertiaryBg,
            onSurfaceVariant = apple.secondaryLabel,
            surfaceContainer = apple.secondaryBg,
            surfaceContainerHigh = apple.tertiaryBg,
            surfaceContainerHighest = apple.tertiaryBg,
            outline = apple.separator,
            outlineVariant = apple.separator,
            error = apple.red,
            onError = Color.White,
            inverseSurface = apple.label,
            inverseOnSurface = apple.bg
        )
    } else {
        lightColorScheme(
            primary = apple.blue,
            onPrimary = Color.White,
            primaryContainer = apple.blue.copy(alpha = 0.14f),
            onPrimaryContainer = apple.blue,
            secondary = apple.green,
            onSecondary = Color.White,
            tertiary = apple.orange,
            onTertiary = Color.White,
            background = apple.bg,
            onBackground = apple.label,
            surface = apple.secondaryGroupedBg,
            onSurface = apple.label,
            surfaceVariant = apple.tertiaryFill,
            onSurfaceVariant = apple.secondaryLabel,
            surfaceContainer = apple.secondaryBg,
            surfaceContainerHigh = apple.secondaryGroupedBg,
            surfaceContainerHighest = apple.secondaryGroupedBg,
            outline = apple.separator,
            outlineVariant = apple.separator,
            error = apple.red,
            onError = Color.White,
            inverseSurface = apple.label,
            inverseOnSurface = apple.bg
        )
    }

    CompositionLocalProvider(LocalAppleColors provides apple) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppleTypography,
            shapes = AppleShapes,
            content = content
        )
    }
}
