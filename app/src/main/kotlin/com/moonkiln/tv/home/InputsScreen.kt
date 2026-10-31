package com.moonkiln.tv.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.moonkiln.tv.BuildConfig
import com.moonkiln.tv.tv.Power
import com.moonkiln.tv.tv.TvInput
import com.moonkiln.tv.ui.FocusTile
import com.moonkiln.tv.ui.Kiln
import com.moonkiln.tv.ui.Type
import com.moonkiln.tv.ui.cardSurface

// Every HDMI and AV input the set has, as a list of rows. OK switches, Back returns home. The footer is the
// launcher's about line, including the credit for the controller photo on the PS5 tile.
@Composable
fun InputsScreen(inputs: List<TvInput>, onSwitch: (TvInput) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(inputs.size) { if (inputs.isNotEmpty()) runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(start = 64.dp, top = 36.dp, end = 64.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText(buildAnnotatedString {
                append("Inputs")
                withStyle(SpanStyle(color = Kiln.green)) { append(".") }
            }, style = Type.pageTitle)
            BasicText("OK switches · Back returns", style = Type.hint)
        }
        if (inputs.isEmpty()) {
            BasicText("No inputs reported by the set yet.", style = Type.body)
        }
        inputs.forEachIndexed { i, input ->
            FocusTile(Modifier.fillMaxWidth().height(72.dp), radius = 20f, focusRequester = if (i == 0) first else null, onClick = { onSwitch(input) }) { st ->
                Row(
                    Modifier.fillMaxSize().cardSurface(20f, border = if (st.focused) Kiln.cream else Kiln.cardBorder).padding(horizontal = 26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val (dot, word) = when (input.power) {
                        Power.ON -> Kiln.coral to "signal"
                        Power.STANDBY -> Kiln.grey5 to "connected, no signal"
                        Power.DISCONNECTED -> Kiln.grey5 to "nothing plugged in"
                        Power.UNKNOWN -> Kiln.grey5 to ""
                    }
                    Box(Modifier.size(10.dp).background(dot, CircleShape))
                    BasicText(input.label, style = Type.body.copy(color = Kiln.cream))
                    Spacer(Modifier.weight(1f))
                    BasicText(word, style = Type.label)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        BasicText(
            "Moon Kiln launcher ${BuildConfig.VERSION_NAME} · controller photo: Alex Cochrane, CC BY-SA 4.0, via Wikimedia Commons · type: Grandstander and Hanken Grotesk, OFL",
            style = Type.eyebrow.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal, color = Kiln.grey4),
        )
    }
}
