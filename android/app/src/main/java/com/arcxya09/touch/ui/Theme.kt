package com.arcxya09.touch.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Quiet, warm surfaces and a single forest accent. Components use semantic roles.
private val Light = lightColorScheme(
    primary = Color(0xFF38634A), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEADD), onPrimaryContainer = Color(0xFF173C25),
    secondary = Color(0xFF616A5D), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E9DD), onSecondaryContainer = Color(0xFF30392C),
    tertiary = Color(0xFF8A6542), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF3E4CE), onTertiaryContainer = Color(0xFF513819),
    background = Color(0xFFF8F7F2), onBackground = Color(0xFF232D26),
    surface = Color(0xFFF8F7F2), onSurface = Color(0xFF232D26),
    surfaceVariant = Color(0xFFE8ECE3), onSurfaceVariant = Color(0xFF586158),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF3F3ED),
    surfaceContainer = Color(0xFFEFF0E9), surfaceContainerHigh = Color(0xFFE7EAE1),
    surfaceContainerHighest = Color(0xFFE0E4DB),
    outline = Color(0xFF7C867C), outlineVariant = Color(0xFFD6DDD2),
    error = Color(0xFFAD3A35), onError = Color.White,
    errorContainer = Color(0xFFFFDAD5), onErrorContainer = Color(0xFF6D1917),
    inverseSurface = Color(0xFF2D3830), inverseOnSurface = Color(0xFFF0F3EA),
    inversePrimary = Color(0xFFA6D2AE), scrim = Color.Black,
)
private val Dark = darkColorScheme(
    primary = Color(0xFFA6D2AE), onPrimary = Color(0xFF153923),
    primaryContainer = Color(0xFF2C4E36), onPrimaryContainer = Color(0xFFDCEADD),
    secondary = Color(0xFFC2CCB9), onSecondary = Color(0xFF2C3527),
    secondaryContainer = Color(0xFF424C3D), onSecondaryContainer = Color(0xFFE4E9DD),
    tertiary = Color(0xFFE0BC93), onTertiary = Color(0xFF452B10),
    tertiaryContainer = Color(0xFF614327), onTertiaryContainer = Color(0xFFF3E4CE),
    background = Color(0xFF151C17), onBackground = Color(0xFFE4E9DF),
    surface = Color(0xFF151C17), onSurface = Color(0xFFE4E9DF),
    surfaceVariant = Color(0xFF313D33), onSurfaceVariant = Color(0xFFB8C3B6),
    surfaceContainerLowest = Color(0xFF101612), surfaceContainerLow = Color(0xFF1C241E),
    surfaceContainer = Color(0xFF222C24), surfaceContainerHigh = Color(0xFF2B352D),
    surfaceContainerHighest = Color(0xFF354037),
    outline = Color(0xFF889587), outlineVariant = Color(0xFF414D42),
    error = Color(0xFFFFB4AA), onError = Color(0xFF660D0D),
    errorContainer = Color(0xFF882922), onErrorContainer = Color(0xFFFFDAD5),
    inverseSurface = Color(0xFFE4E9DF), inverseOnSurface = Color(0xFF2B352D),
    inversePrimary = Color(0xFF38634A), scrim = Color.Black,
)
private fun type(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontSize = size.sp, lineHeight = height.sp,
    fontWeight = weight, letterSpacing = 0.sp,
)
private val TouchTypography = Typography(
    displayLarge = type(56, 64, FontWeight.Light), displayMedium = type(44, 52, FontWeight.Light),
    displaySmall = type(36, 44, FontWeight.Light),
    headlineLarge = type(30, 40, FontWeight.SemiBold), headlineMedium = type(26, 36, FontWeight.SemiBold),
    headlineSmall = type(23, 32, FontWeight.SemiBold),
    titleLarge = type(21, 30, FontWeight.SemiBold), titleMedium = type(17, 26, FontWeight.Medium),
    titleSmall = type(15, 22, FontWeight.Medium),
    bodyLarge = type(16, 26), bodyMedium = type(14, 22), bodySmall = type(12, 20),
    labelLarge = type(14, 20, FontWeight.Medium), labelMedium = type(12, 18, FontWeight.Medium),
    labelSmall = type(11, 16, FontWeight.Medium),
)
@Composable fun TouchTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, typography = TouchTypography,
        shapes = Shapes(extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)),
        content = content)
}
