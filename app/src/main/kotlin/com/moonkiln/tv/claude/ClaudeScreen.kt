package com.moonkiln.tv.claude

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.moonkiln.tv.data.BridgeRepo
import com.moonkiln.tv.data.Msg
import com.moonkiln.tv.data.MsgKind
import com.moonkiln.tv.ui.FocusTile
import com.moonkiln.tv.ui.Kiln
import com.moonkiln.tv.ui.LocalReduceMotion
import com.moonkiln.tv.ui.Motion
import com.moonkiln.tv.ui.Type
import kotlinx.coroutines.delay

// Claude on the TV, after Claude.dc.html: prompts as cream pills on the right, answers as plain cream
// text with a green spark, tool calls as grey one-line markers whose dot pulses sun while running, a
// status pill, a quick-prompt row and a pill input. OK sends, Back closes. The TV never runs Claude.
class ClaudeFocus {
    val quick = List(5) { FocusRequester() }
    val field = FocusRequester()
}

val QUICK_PROMPTS = listOf("What's shipping today?", "Switch to PS5", "How are we doing?")

@Composable
fun ClaudeScreen(bridge: BridgeRepo, focus: ClaudeFocus, onHome: () -> Unit) {
    val state by bridge.state.collectAsState()
    LaunchedEffect(Unit) { runCatching { focus.quick[0].requestFocus() } }
    var draft by remember { mutableStateOf("") }
    var fieldFocused by remember { mutableStateOf(false) }

    val statusText = when {
        !state.reachable -> "mini not reachable"
        state.status == "listening" -> "listening"
        state.status == "working" -> "working on the mini"
        state.status == "offline" || !state.alive -> "session not running"
        else -> "session on the mini · live"
    }
    val statusDot = when {
        !state.reachable || state.status == "offline" || !state.alive -> Kiln.coral
        state.status == "listening" || state.status == "working" -> Kiln.sun
        else -> Kiln.green
    }
    val pulsing = state.status == "listening" || state.status == "working"

    Column(
        Modifier.fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF2A2620), Kiln.screen, Kiln.screen), center = Offset(230f, 0f), radius = 900f))
            .padding(start = 64.dp, top = 36.dp, end = 64.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BasicText(buildAnnotatedString {
                    append("Claude")
                    withStyle(SpanStyle(color = Kiln.green)) { append(".") }
                }, style = Type.pageTitle.copy(fontSize = androidx.compose.ui.unit.TextUnit(32f, androidx.compose.ui.unit.TextUnitType.Sp)))
                Row(
                    Modifier.height(32.dp).background(Kiln.card, CircleShape).border(1.dp, Kiln.field, CircleShape).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PulseDot(statusDot, pulsing, 8)
                    BasicText(statusText, style = Type.pill.copy(fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp)))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText("same session on your phone", style = Type.hint.copy(fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp)), modifier = Modifier.padding(end = 6.dp))
                PillButton("Home", requester = focus.quick[4], down = focus.quick[0], onClick = onHome)
            }
        }

        // Transcript: the last seven lines, bottom aligned, each one rising in over 350 ms.
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom)) {
            val visible = state.messages.takeLast(7)
            if (visible.isEmpty()) {
                BasicText(
                    if (!state.alive) "Start the session on the mini (claude-on-tv/start.sh) and this screen follows it. Your phone sees the same session." else "Ask anything. The session on the mini answers here and on your phone.",
                    style = Type.body.copy(color = Kiln.grey4),
                )
            }
            visible.forEach { m -> key(m.id) { MessageRow(m) } }
        }

        Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText("TRY", style = Type.eyebrow, modifier = Modifier.padding(end = 4.dp))
            QUICK_PROMPTS.forEachIndexed { i, q ->
                PillButton(q, requester = focus.quick[i], up = focus.quick[4], down = focus.field, left = focus.quick.getOrNull(i - 1), right = focus.quick[i + 1]) { bridge.send(q) }
            }
            PillButton("Hold mic", requester = focus.quick[3], up = focus.quick[4], down = focus.field, left = focus.quick[2], mic = true) { bridge.listeningStub() }
            Spacer(Modifier.weight(1f))
            PillButton("Clear", onClick = { bridge.clear() }, up = focus.quick[4], down = focus.field, left = focus.quick[3])
        }

        // The pill input. Focus opens the on-screen keyboard; Enter or OK sends.
        Row(
            Modifier.fillMaxWidth().height(56.dp)
                .background(Kiln.card, CircleShape)
                .border(1.dp, if (state.status == "listening") Kiln.sun else if (fieldFocused) Kiln.cream else Kiln.field, CircleShape)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            MicIcon(Kiln.green, 20)
            BasicTextField(
                value = draft,
                onValueChange = { draft = it.take(500) },
                modifier = Modifier.weight(1f).focusRequester(focus.field).onFocusChanged { fieldFocused = it.isFocused },
                textStyle = Type.body.copy(fontSize = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp), color = Kiln.cream),
                cursorBrush = SolidColor(Kiln.cream),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (draft.isNotBlank()) { bridge.send(draft); draft = "" } }),
                decorationBox = { inner ->
                    Box {
                        if (draft.isEmpty()) BasicText("Ask Claude. Type here or from your phone; the remote's mic comes with Bluetooth pairing.", style = Type.body.copy(fontSize = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp), color = Kiln.grey4))
                        inner()
                    }
                },
            )
            BasicText("OK sends · Back closes", style = Type.delta.copy(color = Kiln.grey4))
        }
    }
}

@Composable
private fun MessageRow(m: Msg) {
    val reduce = LocalReduceMotion.current
    val rise by animateFloatAsState(1f, Motion.tween(Motion.MEDIUM), label = "rise")
    val entered = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered.value = true }
    val a by animateFloatAsState(if (entered.value || reduce) 1f else 0f, Motion.tween(Motion.MEDIUM), label = "in")
    Box(Modifier.fillMaxWidth().graphicsLayer { translationY = (1f - a) * 10f }) {
        when (m.kind) {
            MsgKind.USER -> Box(
                Modifier.align(Alignment.CenterEnd).widthIn(max = 720.dp)
                    .background(Kiln.cream.copy(alpha = a), CircleShape).padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                BasicText(m.text, style = Type.body.copy(fontSize = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = Kiln.ink.copy(alpha = a)))
            }
            MsgKind.TOOL -> Row(Modifier.align(Alignment.CenterStart).padding(start = 26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PulseDot(if (m.running) Kiln.sun else Kiln.green, m.running, 6)
                BasicText(m.text, style = Type.eyebrow.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal, color = Kiln.grey4.copy(alpha = a)))
            }
            MsgKind.CLAUDE -> Row(Modifier.align(Alignment.CenterStart).widthIn(max = 860.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Spark(Kiln.green.copy(alpha = a), Modifier.padding(top = 7.dp))
                RevealedText(m.text, a)
            }
            MsgKind.ANNOUNCE -> Row(Modifier.align(Alignment.CenterStart).widthIn(max = 860.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Spark(Kiln.sun.copy(alpha = a), Modifier.padding(top = 7.dp))
                BasicText(m.text, style = Type.body.copy(color = Kiln.cream.copy(alpha = a)))
            }
        }
    }
}

// Claude's answer appears a word at a time, 55 ms a word, with a blinking cursor until it is done.
@Composable
private fun RevealedText(text: String, alpha: Float) {
    val reduce = LocalReduceMotion.current
    val words = remember(text) { text.split(Regex("\\s+")).filter { it.isNotEmpty() } }
    var shown by remember(text) { mutableIntStateOf(if (reduce) words.size else 0) }
    LaunchedEffect(text) {
        while (shown < words.size) {
            delay(55)
            shown++
        }
    }
    val streaming = shown < words.size
    val blink = rememberInfiniteTransition(label = "cursor")
    val cursor by blink.animateFloat(1f, 0f, infiniteRepeatable(tween(500, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Reverse), label = "blink")
    Row(verticalAlignment = Alignment.Bottom) {
        BasicText(words.take(shown).joinToString(" "), style = Type.body.copy(color = Kiln.cream.copy(alpha = alpha), lineHeight = androidx.compose.ui.unit.TextUnit(27f, androidx.compose.ui.unit.TextUnitType.Sp)))
        if (streaming) Box(Modifier.padding(start = 3.dp, bottom = 4.dp).width(2.dp).height(20.dp).background(Kiln.cream.copy(alpha = if (cursor > 0.5f) 1f else 0f)))
    }
}

@Composable
private fun PulseDot(colour: Color, pulsing: Boolean, size: Int) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(600, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(size.dp).background(colour.copy(alpha = if (pulsing) a else 1f), CircleShape))
}

@Composable
private fun Spark(colour: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(14.dp)) {
        val w = size.width; val h = size.height
        val p = Path().apply {
            moveTo(w / 2, 0f); lineTo(w * 0.61f, h * 0.39f); lineTo(w, h / 2); lineTo(w * 0.61f, h * 0.61f)
            lineTo(w / 2, h); lineTo(w * 0.39f, h * 0.61f); lineTo(0f, h / 2); lineTo(w * 0.39f, h * 0.39f); close()
        }
        drawPath(p, colour)
    }
}

@Composable
private fun MicIcon(colour: Color, size: Int) {
    Canvas(Modifier.size(size.dp)) {
        val s = this.size.width / 24f
        drawRoundRect(colour, topLeft = Offset(9 * s, 3 * s), size = androidx.compose.ui.geometry.Size(6 * s, 11 * s), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3 * s), style = androidx.compose.ui.graphics.drawscope.Stroke(2 * s))
        drawArc(colour, 0f, 180f, false, topLeft = Offset(5 * s, 4 * s), size = androidx.compose.ui.geometry.Size(14 * s, 14 * s), style = androidx.compose.ui.graphics.drawscope.Stroke(2 * s))
        drawLine(colour, Offset(12 * s, 18 * s), Offset(12 * s, 21 * s), strokeWidth = 2 * s)
    }
}

@Composable
private fun PillButton(
    label: String,
    requester: FocusRequester? = null,
    up: FocusRequester? = null,
    down: FocusRequester? = null,
    left: FocusRequester? = null,
    right: FocusRequester? = null,
    mic: Boolean = false,
    onClick: () -> Unit,
) {
    FocusTile(Modifier.height(38.dp), radius = 19f, focusRequester = requester, up = up, down = down, left = left, right = right, onClick = onClick) { st ->
        Row(
            Modifier.fillMaxHeight()
                .background(if (st.pressed) Kiln.cream else if (st.focused) Kiln.pillRest else Kiln.card, CircleShape)
                .border(1.dp, if (st.focused) Kiln.cream else Kiln.field, CircleShape)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (mic) MicIcon(if (st.pressed) Kiln.ink else Kiln.grey1, 14)
            BasicText(label, style = Type.pillBold.copy(fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp), color = if (st.pressed) Kiln.ink else if (st.focused) Kiln.cream else Kiln.grey1))
        }
    }
}

@Composable
private inline fun key(key: Any, content: @Composable () -> Unit) = androidx.compose.runtime.key(key) { content() }
