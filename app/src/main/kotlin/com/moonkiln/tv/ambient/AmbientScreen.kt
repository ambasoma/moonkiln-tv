package com.moonkiln.tv.ambient

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moonkiln.tv.data.Images
import com.moonkiln.tv.data.Today
import com.moonkiln.tv.home.clockText
import com.moonkiln.tv.home.dateText
import com.moonkiln.tv.home.rememberClock
import com.moonkiln.tv.ui.Kiln
import com.moonkiln.tv.ui.LocalReduceMotion
import com.moonkiln.tv.ui.Motion
import com.moonkiln.tv.ui.Pill
import com.moonkiln.tv.ui.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The curated set bundled in assets/photos (600 px hero shots), with a name and a line in the brand's voice.
data class AmbientPhoto(val asset: String, val name: String, val phrase: String)

val AMBIENT_PHOTOS = listOf(
    AmbientPhoto("photos/bearista.jpg", "Bearista", "pals with jobs and faces"),
    AmbientPhoto("photos/capybara.jpg", "Capy", "one of a kind always"),
    AmbientPhoto("photos/tuxedo-kitty.jpg", "Tuxedo Kitty", "only classy in looks"),
    AmbientPhoto("photos/happy-duck.jpg", "Happy Duck", "sculpted, painted and named by hand"),
    AmbientPhoto("photos/charlie.jpg", "Charlie", "handmade in Bangalore, one at a time"),
    AmbientPhoto("photos/jiji.jpg", "Jiji", "pals with jobs and faces"),
    AmbientPhoto("photos/mr-fox.jpg", "Mr Fox", "one of a kind always"),
    AmbientPhoto("photos/mr-ribbit.jpg", "Mr Ribbit", "only classy in looks"),
    AmbientPhoto("photos/shiba-inu.jpg", "Shiba Inu", "sculpted, painted and named by hand"),
    AmbientPhoto("photos/sunny-bunny.jpg", "Sunny Bunny", "handmade in Bangalore, one at a time"),
    AmbientPhoto("photos/totoro.jpg", "Totoro", "pals with jobs and faces"),
    AmbientPhoto("photos/mushroom-pal.jpg", "Mushroom Pal", "one of a kind always"),
)

private val DRIFTS = listOf(0f to 0f, 14f to -8f, 26f to 6f, 10f to 18f, -8f to 10f, -14f to -6f)

// Ambient mode: used both inside the launcher (Ambient tile, long press Home) and as the system screensaver.
@Composable
fun AmbientScreen(today: Today?, intervalMs: Long = 6000L) {
    val context = LocalContext.current
    val reduce = LocalReduceMotion.current
    val now by rememberClock()
    var index by remember { mutableIntStateOf((System.currentTimeMillis() / 60_000 % AMBIENT_PHOTOS.size).toInt()) }
    var step by remember { mutableIntStateOf(0) }
    var current by remember { mutableStateOf<ImageBitmap?>(null) }
    var previous by remember { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember { Animatable(1f) }

    // Two bitmaps at most: the one on screen and the one fading out.
    LaunchedEffect(index) {
        val next = Images.loadAsset(context, AMBIENT_PHOTOS[index].asset, 396)?.asImageBitmap()
        previous = current
        current = next
        fade.snapTo(0f)
        fade.animateTo(1f, Motion.tween(Motion.AMBIENT))
        previous = null
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(intervalMs)
            index = (index + 1) % AMBIENT_PHOTOS.size
            step = (step + 1) % DRIFTS.size
        }
    }
    val sway = rememberInfiniteTransition(label = "sway")
    val angle by sway.animateFloat(
        initialValue = -1.5f, targetValue = 1.5f,
        animationSpec = infiniteRepeatable(tween(5000, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse),
        label = "angle",
    )
    val driftTarget = DRIFTS[step]
    val dx = remember { Animatable(0f) }
    val dy = remember { Animatable(0f) }
    LaunchedEffect(step) {
        launch { dx.animateTo(driftTarget.first, tween(2000, easing = Motion.ease)) }
        launch { dy.animateTo(driftTarget.second, tween(2000, easing = Motion.ease)) }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(Kiln.card, Kiln.screen), center = androidx.compose.ui.geometry.Offset(1088f, 360f), radius = 700f),
        ),
    ) {
        Column(
            Modifier.offset(x = 96.dp, y = 150.dp).graphicsLayer { translationX = dx.value; translationY = dy.value },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText(clockText(now), style = Type.ambientClock)
            BasicText(dateText(now), style = Type.ambientDate)
            Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val shop = today?.toFulfilShop ?: 0
                val custom = today?.toFulfilCustom ?: 0
                Pill("$shop shop ${if (shop == 1) "order" else "orders"} to fulfil", dot = Kiln.green, height = 42, style = Type.ambientPill)
                Pill("$custom custom ${if (custom == 1) "pet" else "pets"} to fulfil", dot = Kiln.sun, height = 42, style = Type.ambientPill)
            }
        }

        Box(
            Modifier.align(Alignment.TopEnd).padding(end = 120.dp, top = 92.dp).width(420.dp).height(540.dp)
                .graphicsLayer { rotationZ = if (reduce) 0f else angle; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f) },
        ) {
            val tilt = if (index % 2 == 0) 2f else -2f
            previous?.let { Polaroid(it, AMBIENT_PHOTOS[(index + AMBIENT_PHOTOS.size - 1) % AMBIENT_PHOTOS.size], alpha = 1f - fade.value, scale = 1f, tilt = -tilt) }
            current?.let { Polaroid(it, AMBIENT_PHOTOS[index], alpha = fade.value, scale = 0.96f + 0.04f * fade.value, tilt = tilt) }
        }

        BasicText("moon kiln", style = Type.ambientMark, modifier = Modifier.align(Alignment.BottomStart).padding(start = 96.dp, bottom = 44.dp))
        BasicText("press any button to wake", style = Type.hint.copy(color = Kiln.grey5), modifier = Modifier.align(Alignment.BottomEnd).padding(end = 96.dp, bottom = 44.dp))
    }
}

@Composable
private fun Polaroid(image: ImageBitmap, photo: AmbientPhoto, alpha: Float, scale: Float, tilt: Float) {
    Column(
        Modifier.fillMaxSize()
            .graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale; rotationZ = tilt; shadowElevation = 16f; shape = RoundedCornerShape(28.dp); clip = false }
            .background(Color.White, RoundedCornerShape(28.dp))
            .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 40.dp),
    ) {
        Image(image, contentDescription = photo.name, modifier = Modifier.size(396.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFFF1EEE6)), contentScale = ContentScale.Crop)
        Spacer(Modifier.height(14.dp))
        BasicText(photo.name, style = Type.polaroidName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(photo.phrase, style = Type.polaroidPhrase, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
