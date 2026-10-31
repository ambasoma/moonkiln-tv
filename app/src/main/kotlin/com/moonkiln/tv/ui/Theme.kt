@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.moonkiln.tv.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.moonkiln.tv.R

// The kiln palette from the canvas. Warm greys only, never cool.
object Kiln {
    val screen = Color(0xFF1C1A17)
    val card = Color(0xFF26231F)
    val cardLift = Color(0xFF2B2721)
    val cardBorder = Color(0xFF302C25)
    val skeleton = Color(0xFF2E2B25)
    val hairline = Color(0xFF343028)
    val field = Color(0xFF3A362F)
    val pillRest = Color(0xFF302C25)
    val cream = Color(0xFFF4EFE2)
    val grey1 = Color(0xFFC9C2AE)
    val grey2 = Color(0xFFABA28D)
    val grey3 = Color(0xFF9C9482)
    val grey4 = Color(0xFF8A8270)
    val grey5 = Color(0xFF5A5746)
    val green = Color(0xFF8FB544)
    val greenDeep = Color(0xFF72962B)
    val greenDark = Color(0xFF46601E)
    val greenLight = Color(0xFFA8C95E)
    val coral = Color(0xFFD6694F)
    val sun = Color(0xFFFFC92F)
    val ink = Color(0xFF1C1A17)
    val polaroidText = Color(0xFF7A7665)
    val black = Color(0xFF000000)

    // Chart series, validated for colour blindness on the card colour. Keep exactly.
    val channelColours = listOf(
        Color(0xFFD6694F), Color(0xFF3987E5), Color(0xFF72962B), Color(0xFFD55181),
        Color(0xFF9085E9), Color(0xFFC98500), Color(0xFF8A8270),
    )
    val trendFollowers = Color(0xFF72962B)
    val trendReach = Color(0xFF3987E5)
    val trendEngagement = Color(0xFFD55181)
    val searchImpressions = Color(0xFF46601E)
    val searchClicks = Color(0xFF72962B)
    val searchOrders = Color(0xFFA8C95E)
}

// Both families are the Google Fonts variable files (OFL), one file each, with the weights the
// design uses pinned through font variation settings.
val Grandstander = FontFamily(
    Font(R.font.grandstander_wght, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.grandstander_wght, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
)
val Hanken = FontFamily(
    Font(R.font.hankengrotesk_wght, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.hankengrotesk_wght, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.hankengrotesk_wght, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

// Sizes are the design's pixel sizes: the root locks density to one pixel per dp (see KilnRoot).
object Type {
    val greeting = TextStyle(fontFamily = Grandstander, fontWeight = FontWeight.ExtraBold, fontSize = 42.sp, lineHeight = 42.sp, letterSpacing = (-0.02).em, color = Kiln.cream)
    val pageTitle = TextStyle(fontFamily = Grandstander, fontWeight = FontWeight.ExtraBold, fontSize = 36.sp, lineHeight = 36.sp, letterSpacing = (-0.02).em, color = Kiln.cream)
    val wordmark = TextStyle(fontFamily = Grandstander, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 30.sp, letterSpacing = (-0.02).em, color = Kiln.cream)
    val ps5 = TextStyle(fontFamily = Grandstander, fontWeight = FontWeight.ExtraBold, fontSize = 64.sp, lineHeight = 63.sp, letterSpacing = (-0.02).em, color = Kiln.cream)
    val ambientClock = TextStyle(fontFamily = Grandstander, fontWeight = FontWeight.ExtraBold, fontSize = 136.sp, lineHeight = 130.sp, letterSpacing = (-0.02).em, color = Kiln.cream)
    val ambientMark = TextStyle(fontFamily = Grandstander, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, lineHeight = 24.sp, letterSpacing = (-0.02).em, color = Kiln.grey5)
    val cardTitle = TextStyle(fontFamily = Grandstander, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 22.sp, color = Kiln.cream)
    val chartTitle = TextStyle(fontFamily = Grandstander, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 21.sp, color = Kiln.cream)
    val polaroidName = TextStyle(fontFamily = Grandstander, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 26.sp, color = Kiln.ink)
    val toastTitle = TextStyle(fontFamily = Grandstander, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 24.sp, color = Kiln.cream)

    val body = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Normal, fontSize = 19.sp, lineHeight = 26.sp, color = Kiln.grey2)
    val label = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 20.sp, color = Kiln.grey3)
    val hint = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 18.sp, color = Kiln.grey4)
    val pill = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 18.sp, color = Kiln.grey1)
    val pillBold = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 18.sp, color = Kiln.grey1)
    val eyebrow = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, lineHeight = 14.sp, letterSpacing = 0.1.em, color = Kiln.grey3)
    val eyebrowCard = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 15.sp, letterSpacing = 0.12.em, color = Kiln.grey3)
    val clock = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.01).em, color = Kiln.cream)
    val hero = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = 46.sp, lineHeight = 46.sp, letterSpacing = (-0.02).em, color = Kiln.green)
    val tileNumber = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 24.sp, letterSpacing = (-0.02).em, color = Kiln.cream)
    val tileLabel = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 0.1.em, color = Kiln.grey3)
    val delta = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 15.sp)
    val chartSub = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 15.sp, color = Kiln.grey3)
    val legend = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Normal, fontSize = 11.5.sp, lineHeight = 14.sp, color = Kiln.grey3)
    val axis = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 14.sp, color = Kiln.grey3)
    val ambientDate = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Normal, fontSize = 26.sp, lineHeight = 30.sp, color = Kiln.grey2)
    val ambientPill = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 20.sp, color = Kiln.grey1)
    val polaroidPhrase = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 18.sp, color = Kiln.polaroidText)
    val postCaption = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 18.sp, color = Kiln.cream)
    val postKind = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = 10.5.sp, lineHeight = 12.sp, letterSpacing = 0.1.em, color = Kiln.green)
    val postDate = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Normal, fontSize = 11.5.sp, lineHeight = 13.sp, color = Kiln.grey4)
    val postStats = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 15.sp, color = Kiln.grey3)
    val staleBadge = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 13.sp, color = Color.White)
    val tipValue = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, lineHeight = 15.sp, color = Kiln.cream)
    val tipName = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 15.sp, color = Kiln.grey3)
    val tipTotal = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 18.sp, color = Kiln.cream)
}

// One easing everywhere, no bounces. Durations honour the TV's animation scale: zero means reduce motion.
object Motion {
    val ease = CubicBezierEasing(0.2f, 0.7f, 0.3f, 1f)
    const val FOCUS = 180
    const val PRESS = 120
    const val MEDIUM = 350
    const val SLOW = 550
    const val AMBIENT = 1200

    @Volatile var scale: Float = 1f

    // Compose multiplies every tween by the system animator scale, and this set ships with 0.5, which would
    // halve the canvas timings. Dividing by the scale puts the on-screen duration back at the design's number.
    // Zero still means no motion at all.
    fun ms(design: Int): Int = if (scale <= 0f) 0 else (design / scale).toInt()

    fun <T> tween(design: Int, delay: Int = 0): TweenSpec<T> = tween(ms(design), if (scale <= 0f) 0 else delay, ease)

    fun readScale(context: Context) {
        scale = try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        } catch (_: Exception) { 1f }
    }
}

val LocalReduceMotion = staticCompositionLocalOf { false }

// Locks the composition to one pixel per dp so the design's 1280x720 pixel numbers apply as written.
// The TV reports 213 dpi; without this every size would come out a third larger.
@Composable
fun KilnRoot(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalDensity provides Density(1f, 1f),
        LocalReduceMotion provides (Motion.scale <= 0f),
        content = content,
    )
}
