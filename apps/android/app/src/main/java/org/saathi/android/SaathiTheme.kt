package org.saathi.android

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.unit.sp

val Manrope = FontFamily(
    Font(R.font.manrope, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.manrope, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.manrope, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.manrope, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700)))
)
val Lora = FontFamily(Font(R.font.lora))
private val light = lightColorScheme(primary = Color(0xff216352), onPrimary = Color(0xfffffefa), primaryContainer = Color(0xffeaf0e7), onPrimaryContainer = Color(0xff243d35), background = Color(0xfff8f7f2), onBackground = Color(0xff243d35), surface = Color(0xfffffefa), onSurface = Color(0xff243d35), surfaceVariant = Color(0xffeaf0e7), onSurfaceVariant = Color(0xff626e64), outline = Color(0xffdedfd5), error = Color(0xff923d31), errorContainer = Color(0xfffae9e4), onErrorContainer = Color(0xff923d31))
private val dark = darkColorScheme(primary = Color(0xffa0d7bd), onPrimary = Color(0xff15221e), primaryContainer = Color(0xff273b31), onPrimaryContainer = Color(0xffe6eee6), background = Color(0xff15221e), onBackground = Color(0xffe6eee6), surface = Color(0xff1d2e27), onSurface = Color(0xffe6eee6), surfaceVariant = Color(0xff273b31), onSurfaceVariant = Color(0xffb3bfb5), outline = Color(0xff3b4d41), error = Color(0xffffc0ad), errorContainer = Color(0xff482c27), onErrorContainer = Color(0xffffc0ad))
@Composable fun SaathiTheme(content: @Composable () -> Unit) {
    val defaults = Typography()
    val base = if (isSystemInDarkTheme()) dark else light
    MaterialTheme(colorScheme = base.copy(secondary = base.primary, onSecondary = base.onPrimary, secondaryContainer = base.primaryContainer, onSecondaryContainer = base.onPrimaryContainer, outlineVariant = base.outline, surfaceTint = base.primary, surfaceContainer = base.surface, surfaceContainerHigh = base.surfaceVariant, surfaceContainerHighest = base.surfaceVariant, surfaceContainerLow = base.background, surfaceContainerLowest = base.surface),
        typography = Typography(
            displaySmall = defaults.displaySmall.copy(fontFamily = Lora, fontSize = 32.sp, lineHeight = 40.sp),
            headlineMedium = defaults.headlineMedium.copy(fontFamily = Lora, fontSize = 28.sp, lineHeight = 36.sp),
            headlineSmall = defaults.headlineSmall.copy(fontFamily = Manrope, fontWeight = FontWeight.Bold),
            titleLarge = defaults.titleLarge.copy(fontFamily = Manrope, fontWeight = FontWeight.Bold),
            titleMedium = defaults.titleMedium.copy(fontFamily = Manrope, fontWeight = FontWeight.Bold),
            bodyLarge = defaults.bodyLarge.copy(fontFamily = Manrope, lineHeight = 25.sp),
            bodyMedium = defaults.bodyMedium.copy(fontFamily = Manrope, lineHeight = 22.sp),
            labelLarge = defaults.labelLarge.copy(fontFamily = Manrope, fontWeight = FontWeight.Bold),
            labelMedium = defaults.labelMedium.copy(fontFamily = Manrope),
            bodySmall = defaults.bodySmall.copy(fontFamily = Manrope, lineHeight = 18.sp)
        ), content = content)
}
