package com.girish.expensetracker.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.girish.expensetracker.R

/*
 * Flat dark after the references the user chose (Firnhold, Coinly, Dovetail): near-black canvas, flat cards
 * stepped by tone with hairline edges and no shadows, big bold numerals, mint as the one data accent,
 * rose only for "spending faster", faded grey for last period, soft accent gradients under charts.
 */

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(
    R.font.inter_variable, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

@OptIn(ExperimentalTextApi::class)
private fun mono(weight: Int) = Font(
    R.font.jetbrains_mono_variable, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))
val Mono = FontFamily(mono(400), mono(500))

/** Tone steps and accents that Material's scheme has no slot for. */
@Immutable
data class Tones(
    val ground: Color,      // page
    val section: Color,     // alternate band / nested
    val panel: Color,       // cards, button fills
    val raised: Color,      // pressed / selected panel
    val hairline: Color,    // dividers, frames
    val edge: Color,        // outline buttons, inputs
    val ink: Color,         // primary text
    val ink2: Color,        // secondary text
    val ink3: Color,        // muted / disabled
    val accent: Color,      // mint: this period, active state
    val accentSoft: Color,
    val good: Color,        // money coming back (paybacks, refunds)
    val warn: Color,        // rose: spending faster than usual
)

private val DarkTones = Tones(
    ground = Color(0xFF05070A),
    section = Color(0xFF0E1113),
    panel = Color(0xFF171A1B),
    raised = Color(0xFF20262A),
    hairline = Color(0x1AFFFFFF),
    edge = Color(0xFF3A4044),
    ink = Color(0xFFF3F6F5),
    ink2 = Color(0xFF8D9694),
    ink3 = Color(0xFF6B7472),
    accent = Color(0xFF45E3BE),
    accentSoft = Color(0x2945E3BE),
    good = Color(0xFF45E3BE),
    warn = Color(0xFFF0567F),
)

private val LightTones = DarkTones.copy(
    ground = Color(0xFFFAFAFA),
    section = Color(0xFFF2F2F2),
    panel = Color(0xFFFFFFFF),
    raised = Color(0xFFEDEDED),
    hairline = Color(0xFFE4E4E4),
    edge = Color(0xFFC9C9C9),
    ink = Color(0xFF0A0A0A),
    ink2 = Color(0xFF52514E),
    ink3 = Color(0xFF7C7C7C),
    accent = Color(0xFF0E9C7E),
    accentSoft = Color(0x1F0E9C7E),
    good = Color(0xFF0E9C7E),
    warn = Color(0xFFC92E59),
)

val LocalTones = staticCompositionLocalOf { DarkTones }

private fun scheme(t: Tones, dark: Boolean) = (if (dark) darkColorScheme() else lightColorScheme()).copy(
    primary = t.ink,
    onPrimary = t.ground,
    primaryContainer = t.raised,
    onPrimaryContainer = t.ink,
    secondary = t.accent,
    onSecondary = Color.White,
    secondaryContainer = t.accentSoft,
    onSecondaryContainer = t.ink,
    tertiary = t.good,
    background = t.ground,
    onBackground = t.ink,
    surface = t.ground,
    onSurface = t.ink,
    surfaceVariant = t.panel,
    onSurfaceVariant = t.ink2,
    surfaceContainerLowest = t.ground,
    surfaceContainerLow = t.section,
    surfaceContainer = t.panel,
    surfaceContainerHigh = t.raised,
    surfaceContainerHighest = t.raised,
    outline = t.edge,
    outlineVariant = t.hairline,
    inverseSurface = t.ink,
    inverseOnSurface = t.ground,
    error = Color(0xFFFF6B6B),
)

/** Type scale: Inter, tracking tightening with size (display reads compressed). */
private fun t(size: Int, weight: Int, line: Float, track: Double) =
    TextStyle(fontFamily = Inter, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = (size * line).sp, letterSpacing = track.em)

private val SpendType = Typography(
    displayLarge = t(56, 600, 1.05f, -0.045),
    displayMedium = t(44, 600, 1.08f, -0.04),
    displaySmall = t(36, 600, 1.1f, -0.035),
    headlineLarge = t(30, 600, 1.15f, -0.03),
    headlineMedium = t(26, 600, 1.18f, -0.025),
    headlineSmall = t(21, 600, 1.25f, -0.02),
    titleLarge = t(18, 600, 1.3f, -0.015),
    titleMedium = t(16, 500, 1.35f, -0.01),
    titleSmall = t(15, 500, 1.35f, -0.008),
    bodyLarge = t(16, 400, 1.5f, -0.006),
    bodyMedium = t(14, 400, 1.5f, -0.004),
    bodySmall = t(13, 400, 1.45f, 0.0),
    labelLarge = t(14, 500, 1.3f, -0.004),
    labelMedium = t(12, 500, 1.3f, 0.0),
    labelSmall = t(11, 500, 1.3f, 0.01),
)

/** Amounts: Inter with tabular figures so rupee columns align. */
val AmountStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight(600), fontSize = 16.sp, letterSpacing = (-0.01).em, fontFeatureSettings = "tnum")

/** Data labels (dates, codes, axis ticks): mono, small, open tracking. Never used as a kicker above headings. */
val DataLabel = TextStyle(fontFamily = Mono, fontWeight = FontWeight(400), fontSize = 11.sp, letterSpacing = 0.06.em)

/**
 * Category colours: eight hues validated (dataviz validator) for colour-blind and normal-vision separation and
 * 3:1 contrast on the dark ground. Related categories share a hue; charts always label their rows, so colour
 * never carries identity alone. Colour follows the category, never its rank.
 */
private val Blue = Color(0xFF3987E5)
private val Orange = Color(0xFFD95926)
private val Aqua = Color(0xFF199E70)
private val Yellow = Color(0xFFC98500)
private val Magenta = Color(0xFFD55181)
private val Green = Color(0xFF008300)
private val Violet = Color(0xFF9085E9)
private val Red = Color(0xFFE66767)
/** "Other" and anything unsorted: a neutral that never competes with a real category. */
val OtherPaint = Color(0xFF5E5E5E)

private val categoryPaint = mapOf(
    "Food" to Orange,
    "Groceries" to Aqua, "Household" to Aqua,
    "Transport" to Blue, "Travel" to Blue,
    "Fuel" to Yellow, "Vehicle" to Yellow, "Cash" to Yellow,
    "People" to Magenta, "Gifts" to Magenta,
    "Investment" to Green,
    "Shopping" to Violet, "Personal care" to Violet, "Medical" to Violet, "Health" to Violet,
    "Bills" to Red, "Bills & EMI" to Red, "Rent" to Red, "Subscriptions" to Red, "Entertainment" to Red, "Education" to Red,
)

fun paintFor(category: String): Color = categoryPaint[category] ?: OtherPaint

/** Dark by default (the user's preference); light stays available for previews and a future setting. */
@Composable
fun SpendTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    val tones = if (dark) DarkTones else LightTones
    CompositionLocalProvider(LocalTones provides tones) {
        MaterialTheme(colorScheme = scheme(tones, dark), typography = SpendType, content = content)
    }
}
