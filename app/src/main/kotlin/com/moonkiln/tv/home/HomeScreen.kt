package com.moonkiln.tv.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.moonkiln.tv.R
import com.moonkiln.tv.data.Today
import com.moonkiln.tv.data.TodayRepo
import com.moonkiln.tv.data.rememberRemoteImage
import com.moonkiln.tv.data.rupees
import com.moonkiln.tv.tv.NetworkState
import com.moonkiln.tv.tv.Power
import com.moonkiln.tv.ui.FocusTile
import com.moonkiln.tv.ui.Kiln
import com.moonkiln.tv.ui.Motion
import com.moonkiln.tv.ui.Pill
import com.moonkiln.tv.ui.Type
import com.moonkiln.tv.ui.cardSurface
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// What the home screen can ask the activity to do.
interface HomeActions {
    fun switchToPs5()
    fun openAnalytics()
    fun openAmbient()
    fun openInputs()
    fun openSettings()
    fun openClaude()
    fun openApp(app: LaunchableApp)
}

class HomeFocus {
    val ps5 = FocusRequester()
    val card = FocusRequester()
    val apps = List(24) { FocusRequester() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    todayState: TodayRepo.State,
    ps5Power: Power,
    network: NetworkState,
    apps: List<LaunchableApp>,
    switching: Boolean,
    focus: HomeFocus,
    actions: HomeActions,
) {
    val now by rememberClock()
    val builtIns = 4
    val tileCount = builtIns + apps.size
    val tileRequesters = focus.apps.take(tileCount.coerceAtMost(focus.apps.size))
    val cardDown = tileRequesters.getOrNull(4) ?: tileRequesters.lastOrNull()

    Column(
        Modifier.fillMaxSize().padding(start = 54.dp, top = 40.dp, end = 54.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Box(Modifier.padding(horizontal = 10.dp)) { TopBar(now, ps5Power, network) }
        Box(Modifier.padding(horizontal = 10.dp)) { Greeting(now, todayState.today) }
        Row(Modifier.height(268.dp).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Ps5Tile(ps5Power, switching, focus.ps5, down = tileRequesters.firstOrNull(), right = focus.card, onClick = actions::switchToPs5)
            TodayCard(todayState, focus.card, down = cardDown, left = focus.ps5, onClick = actions::openAnalytics)
        }
        val scroll = rememberScrollState()
        val spec = remember {
            object : BringIntoViewSpec {
                override val scrollAnimationSpec = Motion.tween<Float>(Motion.MEDIUM)
            }
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
            Row(
                // 10 px of air at both ends so the scaled, ringed tile is not clipped by the scroll edge.
                Modifier.fillMaxWidth().horizontalScroll(scroll).padding(start = 10.dp, end = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                val upFor: (Int) -> FocusRequester = { i -> if (i <= 2) focus.ps5 else focus.card }
                AppTile("Ambient", painterResource(R.drawable.ic_ambient), null, tileRequesters.getOrNull(0), upFor(0), left = null, right = tileRequesters.getOrNull(1)) { actions.openAmbient() }
                AppTile("Inputs", painterResource(R.drawable.ic_inputs), null, tileRequesters.getOrNull(1), upFor(1), left = tileRequesters.getOrNull(0), right = tileRequesters.getOrNull(2)) { actions.openInputs() }
                AppTile("Settings", painterResource(R.drawable.ic_settings), null, tileRequesters.getOrNull(2), upFor(2), left = tileRequesters.getOrNull(1), right = tileRequesters.getOrNull(3)) { actions.openSettings() }
                AppTile("Claude", painterResource(R.drawable.ic_claude), null, tileRequesters.getOrNull(3), upFor(3), left = tileRequesters.getOrNull(2), right = tileRequesters.getOrNull(4), tint = false) { actions.openClaude() }
                apps.forEachIndexed { i, app ->
                    val idx = builtIns + i
                    AppTile(app.label, null, app.icon, tileRequesters.getOrNull(idx), upFor(idx), left = tileRequesters.getOrNull(idx - 1), right = tileRequesters.getOrNull(idx + 1)) { actions.openApp(app) }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            // The canvas says "hold Home for ambient", but on this build a long press on Home sends ALL_APPS pinned
            // to the system Settings package, so it can never reach the launcher. The Ambient tile is the way in.
            BasicText("OK selects · Back returns · Ambient for the screensaver", style = Type.hint)
            BasicText("handmade in Bengaluru, one at a time", style = Type.hint)
        }
    }
}

@Composable
fun rememberClock(): androidx.compose.runtime.State<LocalDateTime> {
    val state = remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            state.value = LocalDateTime.now()
            delay(1000L)
        }
    }
    return state
}

fun clockText(t: LocalDateTime): String {
    val h12 = (t.hour % 12).let { if (it == 0) 12 else it }
    return String.format(Locale.US, "%d:%02d %s", h12, t.minute, if (t.hour >= 12) "pm" else "am")
}

fun dateText(t: LocalDateTime): String = t.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK))

fun greetingText(t: LocalDateTime): String = when {
    t.hour < 5 -> "Still up,"
    t.hour < 12 -> "Morning,"
    t.hour < 17 -> "Afternoon,"
    t.hour < 22 -> "Evening,"
    else -> "Night,"
}

fun fulfilText(today: Today?): String {
    if (today == null) return "checking what needs shipping"
    val shop = today.toFulfilShop
    val custom = today.toFulfilCustom
    fun n(c: Int, what: String) = "$c $what ${if (c == 1) "order" else "orders"}"
    return when {
        shop == 0 && custom == 0 -> "nothing left to fulfil"
        custom == 0 -> n(shop, "shop") + " to fulfil"
        shop == 0 -> n(custom, "custom") + " to fulfil"
        else -> "$shop shop and $custom custom orders to fulfil"
    }
}

@Composable
private fun TopBar(now: LocalDateTime, ps5: Power, network: NetworkState) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Image(painterResource(R.drawable.mk_logo_cream), contentDescription = "Moon Kiln", modifier = Modifier.height(48.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Pill(network.label, leading = {
                Image(painterResource(R.drawable.ic_wifi), contentDescription = null, modifier = Modifier.size(16.dp),
                    alpha = if (network.connected) 1f else 0.4f)
            })
            val on = ps5 == Power.ON
            Pill(if (on) "PS5 on" else "PS5 off", dot = if (on) Kiln.coral else Kiln.grey5)
            Box(Modifier.width(128.dp), contentAlignment = Alignment.CenterEnd) {
                BasicText(clockText(now), style = Type.clock.copy(textAlign = TextAlign.End), maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun Greeting(now: LocalDateTime, today: Today?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(buildAnnotatedString {
            append(greetingText(now))
            append(" ")
            withStyle(SpanStyle(color = Kiln.green)) { append("Amba.") }
        }, style = Type.greeting)
        BasicText("${dateText(now)} · ${fulfilText(today)}", style = Type.body)
    }
}

@Composable
private fun Ps5Tile(power: Power, switching: Boolean, requester: FocusRequester, down: FocusRequester?, right: FocusRequester?, onClick: () -> Unit) {
    FocusTile(
        Modifier.width(636.dp).fillMaxHeight(),
        radius = 28f,
        focusScale = 1.02f,
        focusRequester = requester,
        down = down,
        right = right,
        onClick = onClick,
    ) { st ->
        val on = power == Power.ON
        if (switching) {
            Box(Modifier.fillMaxSize().cardSurface(28f, fill = Kiln.black).padding(30.dp, 30.dp)) {
                Pill("switching to HDMI 1", dot = Kiln.coral, fill = Color(0xE626231F), border = Color.Transparent, height = 40, style = Type.pillBold)
            }
            return@FocusTile
        }
        Box(
            Modifier.fillMaxSize()
                .background(Brush.linearGradient(listOf(Kiln.cardLift, Kiln.card)), RoundedCornerShape(28.dp))
                .cardSurface(28f, fill = Color.Transparent, border = if (st.focused) Kiln.cream else Kiln.cardBorder)
                .clip(RoundedCornerShape(28.dp)),
        ) {
            Column(Modifier.fillMaxHeight().padding(start = 34.dp, top = 30.dp, bottom = 30.dp), verticalArrangement = Arrangement.SpaceBetween) {
                BasicText("HDMI 1", style = Type.eyebrowCard)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BasicText("PS5", style = Type.ps5)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(10.dp).background(if (on) Kiln.coral else Kiln.grey5, CircleShape))
                        BasicText(if (on) "PS5 is on and waiting" else "PS5 is asleep, OK wakes it", style = Type.body.copy(color = Kiln.grey1))
                    }
                }
                val ctaBg by animateFloatAsState(if (st.focused) 1f else 0f, Motion.tween(Motion.FOCUS), label = "cta")
                Box(
                    Modifier.height(40.dp)
                        .background(lerpColor(Kiln.pillRest, Kiln.cream, ctaBg), CircleShape)
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText("OK to switch", style = Type.pillBold.copy(color = lerpColor(Kiln.grey1, Kiln.ink, ctaBg)))
                }
            }
            Image(
                painterResource(R.drawable.dualsense), contentDescription = "PS5 DualSense controller",
                modifier = Modifier.align(Alignment.CenterEnd).height(236.dp).offset(x = 10.dp)
                    .graphicsLayer { shadowElevation = 0f }
                    .drawBehind { },
                contentScale = ContentScale.Fit,
            )
        }
    }
}

fun lerpColor(a: Color, b: Color, t: Float): Color = androidx.compose.ui.graphics.lerp(a, b, t.coerceIn(0f, 1f))

@Composable
private fun TodayCard(state: TodayRepo.State, requester: FocusRequester, down: FocusRequester?, left: FocusRequester?, onClick: () -> Unit) {
    val context = LocalContext.current
    FocusTile(
        Modifier.width(484.dp).fillMaxHeight(),
        radius = 28f,
        focusScale = 1.02f,
        focusRequester = requester,
        down = down,
        left = left,
        onClick = onClick,
    ) { st ->
        val today = state.today
        val stale = !state.reachable && today != null
        Column(
            Modifier.fillMaxSize().cardSurface(28f, border = if (st.focused) Kiln.cream else Kiln.cardBorder).padding(horizontal = 28.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    BasicText("Moon Kiln today", style = Type.eyebrowCard.copy(color = Kiln.green))
                    if (stale) StaleBadge(today!!.fetchedAtMs)
                }
                if (today == null) {
                    Skeleton(120, 30); Skeleton(170, 14); Skeleton(90, 14)
                } else {
                    val numberColour = if (stale) Kiln.grey2 else Kiln.green
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        BasicText(rupees(today.revenueToday), style = Type.hero.copy(color = numberColour))
                        BasicText("${today.ordersToday} ${if (today.ordersToday == 1) "order" else "orders"}", style = Type.body.copy(color = if (stale) Kiln.grey3 else Kiln.grey1), modifier = Modifier.padding(bottom = 4.dp))
                    }
                    BasicText(
                        if (stale) "mini not reachable, showing the last numbers" else "${today.awaitingPickupPieces} awaiting Delhivery pickup",
                        style = Type.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal, color = if (stale) Kiln.grey3 else Kiln.grey2),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Kiln.hairline))
            val sale = today?.latestSale
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val img by rememberRemoteImage(context, sale?.imageUrl, 56)
                Box(Modifier.size(56.dp).background(Kiln.skeleton, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))) {
                    img?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (sale == null) {
                        Skeleton(150, 16); Skeleton(110, 12)
                    } else {
                        BasicText("${sale.title} just sold", style = Type.cardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val parts = listOfNotNull(rupees(sale.amount), sale.city, agoText(sale.createdAtMs))
                        BasicText(parts.joinToString(" · "), style = Type.hint.copy(color = Kiln.grey3), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

fun agoText(createdAtMs: Long): String {
    if (createdAtMs <= 0L) return "earlier"
    val mins = (System.currentTimeMillis() - createdAtMs) / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 60 * 24 -> "${mins / 60} h ago"
        mins < 60 * 24 * 14 -> "${mins / (60 * 24)} d ago"
        else -> "a while ago"
    }
}

@Composable
fun StaleBadge(fetchedAtMs: Long) {
    val hours = ((System.currentTimeMillis() - fetchedAtMs) / 3_600_000).coerceAtLeast(0)
    val mins = ((System.currentTimeMillis() - fetchedAtMs) / 60_000).coerceAtLeast(0)
    val label = if (hours >= 1) "$hours h old" else "$mins min old"
    Box(Modifier.height(20.dp).background(Kiln.coral, CircleShape).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        BasicText(label, style = Type.staleBadge)
    }
}

@Composable
fun Skeleton(w: Int, h: Int) {
    Box(Modifier.width(w.dp).height(h.dp).background(Kiln.skeleton, RoundedCornerShape((h / 2).dp)))
}

@Composable
private fun AppTile(
    label: String,
    icon: Painter?,
    bitmap: androidx.compose.ui.graphics.ImageBitmap?,
    requester: FocusRequester?,
    up: FocusRequester,
    left: FocusRequester?,
    right: FocusRequester?,
    tint: Boolean = true,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(172.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FocusTile(
            Modifier.width(172.dp).height(92.dp),
            radius = 20f,
            focusRequester = requester,
            up = up,
            left = left,
            right = right,
            onFocus = { focused = it },
            onClick = onClick,
        ) { st ->
            val fill by animateFloatAsState(if (st.pressed) 1f else 0f, Motion.tween(Motion.PRESS), label = "press")
            Box(
                Modifier.fillMaxSize().cardSurface(20f, fill = lerpColor(Kiln.card, Kiln.cream, fill), border = if (st.focused) Kiln.cream else Kiln.cardBorder),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Image(icon, contentDescription = null, modifier = Modifier.size(if (tint) 34.dp else 30.dp),
                        colorFilter = if (fill > 0.5f && tint) androidx.compose.ui.graphics.ColorFilter.tint(Kiln.ink) else null)
                } else if (bitmap != null) {
                    Image(bitmap, contentDescription = null, modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)))
                } else {
                    Image(painterResource(R.drawable.ic_app), contentDescription = null, modifier = Modifier.size(34.dp))
                }
            }
        }
        val labelColour by animateFloatAsState(if (focused) 1f else 0f, Motion.tween(Motion.FOCUS), label = "label")
        BasicText(label, style = Type.label.copy(color = lerpColor(Kiln.grey3, Kiln.cream, labelColour)), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
