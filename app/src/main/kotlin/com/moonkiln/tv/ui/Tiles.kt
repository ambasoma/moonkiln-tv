package com.moonkiln.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Focus and press, the way the canvas specifies them: 180 ms to scale 1.05 with a 3 px cream ring outside
// the tile, 120 ms down to 0.97 on OK, then the focus pop brings it back. Only transform and opacity animate.
class TileState(val focused: Boolean, val pressed: Boolean)

@Composable
fun FocusTile(
    modifier: Modifier = Modifier,
    radius: Float,
    focusRequester: FocusRequester? = null,
    up: FocusRequester? = null,
    down: FocusRequester? = null,
    left: FocusRequester? = null,
    right: FocusRequester? = null,
    onFocus: (Boolean) -> Unit = {},
    extraKeys: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean)? = null,
    onClick: () -> Unit,
    content: @Composable BoxScope.(TileState) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val press = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, Motion.tween(Motion.FOCUS), label = "focus")
    val ring by animateFloatAsState(if (focused) 1f else 0f, Motion.tween(Motion.FOCUS), label = "ring")
    var pressed by remember { mutableStateOf(false) }

    Box(
        modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties {
                if (up != null) this.up = up
                if (down != null) this.down = down
                if (left != null) this.left = left
                if (right != null) this.right = right
            }
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .onKeyEvent { ev ->
                if (extraKeys != null && extraKeys(ev)) return@onKeyEvent true
                val isOk = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
                if (!isOk) return@onKeyEvent false
                when (ev.type) {
                    KeyEventType.KeyDown -> {
                        if (!pressed) {
                            pressed = true
                            scope.launch { press.animateTo(0.97f / 1.05f, Motion.tween(Motion.PRESS)) }
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        pressed = false
                        scope.launch {
                            press.animateTo(1f, Motion.tween(Motion.FOCUS))
                        }
                        onClick()
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            .graphicsLayer {
                val s = scale * press.value
                scaleX = s
                scaleY = s
            }
            .drawBehind {
                // A 3 px cream ring flush against the tile's own 1 px border, as on the canvas.
                if (ring > 0f) {
                    drawRoundRect(
                        color = Kiln.cream.copy(alpha = ring),
                        topLeft = Offset(-1.5f, -1.5f),
                        size = Size(size.width + 3f, size.height + 3f),
                        cornerRadius = CornerRadius(radius + 1.5f),
                        style = Stroke(width = 3f),
                    )
                }
            },
    ) {
        content(TileState(focused, pressed))
    }
}

// A card surface: card fill, 1 px border, rounded corners.
fun Modifier.cardSurface(radius: Float, fill: Color = Kiln.card, border: Color = Kiln.cardBorder): Modifier =
    this
        .background(fill, RoundedCornerShape(radius.dp))
        .border(1.dp, border, RoundedCornerShape(radius.dp))

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = Type.pill,
    fill: Color = Kiln.card,
    border: Color = Kiln.field,
    height: Int = 36,
    dot: Color? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier
            .height(height.dp)
            .background(fill, CircleShape)
            .border(1.dp, border, CircleShape)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            }
            if (dot != null) {
                Box(Modifier.size(9.dp).background(dot, CircleShape))
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            }
            BasicText(text, style = style)
        }
    }
}

// The "Opening Settings" pill that sits bottom centre for 1.2 s when an app launches.
@Composable
fun OpeningPill(label: String?, modifier: Modifier = Modifier) {
    var shown by remember(label) { mutableStateOf(label != null) }
    LaunchedEffect(label) {
        if (label != null) { shown = true; delay(1200); shown = false }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, Motion.tween(Motion.FOCUS), label = "opening")
    if (label != null && alpha > 0f) {
        Box(
            modifier
                .graphicsLayer { this.alpha = alpha }
                .background(Kiln.cream, CircleShape)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            BasicText("Opening $label", style = Type.pillBold.copy(color = Kiln.ink))
        }
    }
}
