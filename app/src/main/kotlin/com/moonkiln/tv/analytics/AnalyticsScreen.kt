package com.moonkiln.tv.analytics

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.moonkiln.tv.data.Dashboard
import com.moonkiln.tv.data.DashboardRepo
import com.moonkiln.tv.data.MiniApi
import com.moonkiln.tv.data.Post
import com.moonkiln.tv.data.compact
import com.moonkiln.tv.data.groupIndian
import com.moonkiln.tv.data.rememberRemoteImage
import com.moonkiln.tv.home.Skeleton
import com.moonkiln.tv.home.StaleBadge
import com.moonkiln.tv.home.lerpColor
import com.moonkiln.tv.ui.FocusTile
import com.moonkiln.tv.ui.Kiln
import com.moonkiln.tv.ui.Motion
import com.moonkiln.tv.ui.Type
import com.moonkiln.tv.ui.cardSurface
import kotlin.math.roundToInt

const val NEUTRAL_W = 368
const val WIDE_W = 640
const val NARROW_W = 232
private const val CARD_PAD = 40

// Screen state the activity needs to see: Back collapses an expanded chart before it leaves the page.
class AnalyticsState {
    var period by mutableIntStateOf(30)
    var expanded by mutableIntStateOf(-1)
    var hover by mutableIntStateOf(-1)
    val firstChart = FocusRequester()
    val pills = List(4) { FocusRequester() }
    val charts = List(3) { FocusRequester() }

    fun collapse(): Boolean {
        if (expanded == -1) return false
        expanded = -1
        hover = -1
        return true
    }
}

@Composable
fun AnalyticsScreen(repo: DashboardRepo, state: AnalyticsState, onHome: () -> Unit) {
    val period = state.period
    val flow = remember(period) { repo.flow(period) }
    val data by flow.collectAsState()
    LaunchedEffect(period) { repo.refresh(period) }
    LaunchedEffect(Unit) { runCatching { state.charts[0].requestFocus() } }

    val dash = data.dashboard
    val stale = !data.reachable && dash != null
    val progress = remember { Animatable(0f) }
    // One 550 ms pass on every period change and every fresh load: tiles count up, lines draw, bars grow.
    LaunchedEffect(dash?.days, dash?.fetchedAtMs) {
        if (dash != null) {
            progress.snapTo(0f)
            progress.animateTo(1f, Motion.tween(Motion.SLOW))
        }
    }
    val shown = remember { mutableMapOf<String, Double>() }

    Column(Modifier.fillMaxSize().padding(start = 64.dp, top = 36.dp, end = 64.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BasicText(buildAnnotatedString {
                    append("What's going ")
                    withStyle(SpanStyle(color = Kiln.green)) { append("on.") }
                }, style = Type.pageTitle)
                if (stale) StaleBadge(dash!!.fetchedAtMs)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.background(Kiln.card, CircleShape).border(1.dp, Kiln.field, CircleShape).padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(7, 30, 90).forEachIndexed { i, d ->
                        PeriodPill("${d}d", active = period == d, requester = state.pills[i], down = state.charts[0]) {
                            if (state.period != d) { state.period = d; state.hover = -1 }
                        }
                    }
                }
                FocusTile(Modifier.height(38.dp), radius = 19f, focusRequester = state.pills[3], down = state.charts[2], onClick = onHome) { st ->
                    Box(Modifier.fillMaxHeight().background(if (st.focused) Kiln.cream else Kiln.card, CircleShape).border(1.dp, if (st.focused) Kiln.cream else Kiln.field, CircleShape).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                        BasicText("Home", style = Type.pillBold.copy(fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp), color = if (st.focused) Kiln.ink else Kiln.grey1))
                    }
                }
            }
        }

        // Seven slim tiles
        Row(Modifier.fillMaxWidth().height(84.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (dash == null) {
                repeat(7) {
                    Column(Modifier.weight(1f).fillMaxHeight().cardSurface(18f).padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 11.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Skeleton(70, 11); Skeleton(90, 22); Skeleton(50, 12)
                    }
                }
            } else {
                dash.kpis.forEach { k ->
                    val from = shown[k.key] ?: 0.0
                    val value = from + (k.value - from) * progress.value
                    if (progress.value >= 1f) shown[k.key] = k.value
                    val display = if (k.key == "revenue") "₹" + groupIndian(value) else groupIndian(value)
                    val colour = when (k.dir) { "up" -> Kiln.green; "down" -> Kiln.coral; else -> Kiln.grey3 }
                    Column(Modifier.weight(1f).fillMaxHeight().cardSurface(18f).padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 11.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        BasicText(k.label.uppercase(), style = Type.tileLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BasicText(display, style = Type.tileNumber.copy(color = if (stale) Kiln.grey2 else Kiln.cream), maxLines = 1)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            if (k.dir == "up" || k.dir == "down") Arrow(up = k.dir == "up", colour)
                            BasicText(k.delta, style = Type.delta.copy(color = colour), maxLines = 1)
                        }
                    }
                }
            }
        }

        // Three chart cards
        val widths = (0..2).map { i ->
            val target = when { state.expanded == -1 -> NEUTRAL_W; state.expanded == i -> WIDE_W; else -> NARROW_W }
            animateFloatAsState(target.toFloat(), Motion.tween(Motion.MEDIUM), label = "w$i").value.roundToInt()
        }
        Row(Modifier.fillMaxWidth().height(300.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ChartCard(0, "Sessions by channel", dash?.let { "${groupIndian(it.channelTotal.toDouble())} sessions · per ${if (it.weekly) "week" else "day"}" } ?: "", widths[0], state, dash,
                legendWide = { Legend(dash!!.channelNames.mapIndexed { i, n -> n to Kiln.channelColours[i] }, square = true) },
                narrowHint = "7 channels · OK to expand",
                tooltip = { i -> dash?.let { d -> d.channels.getOrNull(i)?.let { r ->
                    TooltipRows(
                        title = "${groupIndian(r.values.sum().toDouble())} sessions",
                        date = (if (d.weekly) "week of " else "") + dateLabel(r.date),
                        rows = r.values.indices.reversed().filter { r.values[it] > 0 }.map { k -> Triple(Kiln.channelColours[k], groupIndian(r.values[k].toDouble()), d.channelNames[k]) },
                    )
                } } },
                count = dash?.channels?.size ?: 0,
            ) { w, p, h -> if (dash == null) ChartSkeleton(w) else ChannelChart(dash.channels, w, p, h) }

            ChartCard(1, "Social trends", "each line on its own scale", widths[1], state, dash,
                legendWide = {
                    val d = dash!!
                    Legend(listOf(
                        "Followers · ${d.latestFollowers?.let { groupIndian(it.toDouble()) } ?: ""}" to Kiln.trendFollowers,
                        "Reach · ${groupIndian(d.latestReach7)} a day" to Kiln.trendReach,
                        "Engagement · ${groupIndian(d.latestEng7)} a day" to Kiln.trendEngagement,
                    ), square = false)
                },
                narrowHint = "3 lines · OK to expand",
                tooltip = { i -> dash?.social?.getOrNull(i)?.let { r ->
                    TooltipRows(title = null, date = dateLabel(r.date), rows = listOfNotNull(
                        r.followers?.let { Triple(Kiln.trendFollowers, groupIndian(it.toDouble()), "Followers") },
                        Triple(Kiln.trendReach, groupIndian(r.reach7), "Reach, 7-day average"),
                        Triple(Kiln.trendEngagement, groupIndian(r.eng7), "Engagement, 7-day average"),
                    ))
                } },
                count = dash?.social?.size ?: 0,
            ) { w, p, h -> if (dash == null) ChartSkeleton(w) else TrendChart(dash.social, w, p, h) }

            ChartCard(2, "Google search", dash?.let { "${groupIndian(it.searchImpressions.toDouble())} impressions · ${groupIndian(it.searchClicks.toDouble())} clicks · ${it.searchOrders} orders · per ${if (it.weekly) "week" else "day"}" } ?: "", widths[2], state, dash,
                legendWide = { Legend(listOf("Impressions" to Kiln.searchImpressions, "Clicks" to Kiln.searchClicks, "Orders from search" to Kiln.searchOrders), square = true) },
                narrowHint = "3 layers · OK to expand",
                tooltip = { i -> dash?.let { d -> d.search.getOrNull(i)?.let { r ->
                    TooltipRows(title = null, date = (if (d.weekly) "week of " else "") + dateLabel(r.date), rows = listOf(
                        Triple(Kiln.searchImpressions, groupIndian(r.impressions.toDouble()), "impressions"),
                        Triple(Kiln.searchClicks, groupIndian(r.clicks.toDouble()), "clicks"),
                        Triple(Kiln.searchOrders, groupIndian(r.orders), "orders from search"),
                    ))
                } } },
                count = dash?.search?.size ?: 0,
            ) { w, p, h -> if (dash == null) ChartSkeleton(w) else SearchChart(dash.search, w, p, h) }
        }

        // Top posts
        Column(Modifier.fillMaxWidth().height(140.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                BasicText("Top posts, last 90 days", style = Type.chartTitle)
                BasicText("by views and reach", style = Type.hint.copy(fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp)))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                if (dash == null) repeat(4) { PostCard(null) } else dash.posts.take(4).forEach { PostCard(it) }
            }
        }
    }
}

@Composable
private fun Arrow(up: Boolean, colour: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(9.dp)) {
        val p = androidx.compose.ui.graphics.Path()
        if (up) { p.moveTo(size.width / 2, 0f); p.lineTo(size.width, size.height); p.lineTo(0f, size.height) }
        else { p.moveTo(size.width / 2, size.height); p.lineTo(0f, 0f); p.lineTo(size.width, 0f) }
        p.close()
        drawPath(p, colour)
    }
}

@Composable
private fun PeriodPill(label: String, active: Boolean, requester: FocusRequester, down: FocusRequester, onClick: () -> Unit) {
    FocusTile(Modifier.height(30.dp), radius = 15f, focusRequester = requester, down = down, onClick = onClick) { st ->
        val bg = when { active -> Kiln.cream; st.focused -> Kiln.pillRest; else -> Color.Transparent }
        val fg = when { active -> Kiln.ink; st.focused -> Kiln.cream; else -> Kiln.grey1 }
        Box(
            Modifier.fillMaxHeight().background(bg, CircleShape).border(1.dp, if (st.focused && !active) Kiln.cream else Color.Transparent, CircleShape).padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(label, style = Type.pillBold.copy(fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp), color = fg))
        }
    }
}

data class TooltipRows(val title: String?, val date: String, val rows: List<Triple<Color, String, String>>)

@Composable
private fun ChartCard(
    index: Int,
    title: String,
    sub: String,
    cardWidth: Int,
    state: AnalyticsState,
    dash: Dashboard?,
    legendWide: @Composable () -> Unit,
    narrowHint: String,
    tooltip: (Int) -> TooltipRows?,
    count: Int,
    chart: @Composable (width: Int, progress: Float, hover: Int) -> Unit,
) {
    val expanded = state.expanded == index
    val hover = if (expanded) state.hover else -1
    val progress = remember { Animatable(0f) }
    LaunchedEffect(dash?.days, dash?.fetchedAtMs) {
        if (dash != null) { progress.snapTo(0f); progress.animateTo(1f, Motion.tween(Motion.SLOW)) }
    }
    FocusTile(
        Modifier.width(cardWidth.dp).fillMaxHeight(),
        radius = 20f,
        focusRequester = state.charts[index],
        up = state.pills[if (index == 2) 3 else listOf(7, 30, 90).indexOf(state.period).coerceAtLeast(0)],
        left = if (index > 0) state.charts[index - 1] else null,
        right = if (index < 2) state.charts[index + 1] else null,
        extraKeys = { ev ->
            if (!expanded || ev.type != KeyEventType.KeyDown || count == 0) false
            else when (ev.key) {
                Key.DirectionLeft -> { state.hover = if (state.hover == -1) count - 1 else (state.hover - 1).coerceAtLeast(0); true }
                Key.DirectionRight -> { state.hover = if (state.hover == -1) count - 1 else (state.hover + 1).coerceAtMost(count - 1); true }
                else -> false
            }
        },
        onClick = {
            if (state.expanded == index) state.collapse() else { state.expanded = index; state.hover = -1 }
        },
    ) { st ->
        val plotW = cardWidth - CARD_PAD
        Column(
            Modifier.fillMaxSize().cardSurface(20f, border = if (st.focused) Kiln.cream else Kiln.cardBorder).padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                BasicText(title, style = Type.chartTitle, maxLines = 1, overflow = TextOverflow.Clip)
                BasicText(sub, style = Type.chartSub, maxLines = 1, overflow = TextOverflow.Clip)
            }
            Box(Modifier.width(plotW.dp).height(CHART_H.dp)) {
                chart(plotW, progress.value, hover)
                if (hover in 0 until count) {
                    tooltip(hover)?.let { tip ->
                        val slot = (plotW - 6f - X0) / count
                        val cx = X0 + hover * slot + slot / 2
                        val left = cx.coerceIn(100f, plotW - 100f)
                        Tooltip(tip, Modifier.offset { IntOffset(left.roundToInt(), 0) })
                    }
                }
            }
            if (dash != null) {
                if (cardWidth >= 300) legendWide() else BasicText(narrowHint, style = Type.legend.copy(color = Kiln.grey4))
            }
        }
    }
}

@Composable
private fun Tooltip(tip: TooltipRows, modifier: Modifier) {
    Column(
        modifier.graphicsLayer { translationX = -size.width / 2f }
            .background(Kiln.pillRest, RoundedCornerShape(12.dp)).border(1.dp, Kiln.field, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        // Seven channel rows must fit inside the 170 px plot, so the rows sit tight.
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tip.title != null) BasicText(tip.title, style = Type.tipTotal)
            BasicText(tip.date, style = Type.axis)
        }
        tip.rows.forEach { (c, v, n) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(14.dp).height(3.dp).background(c, RoundedCornerShape(2.dp)))
                BasicText(v, style = Type.tipValue, modifier = Modifier.width(44.dp))
                BasicText(n, style = Type.tipName)
            }
        }
    }
}

@Composable
private fun Legend(items: List<Pair<String, Color>>, square: Boolean) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        items.forEach { (name, c) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (square) Box(Modifier.size(10.dp).background(c, RoundedCornerShape(3.dp)))
                else Box(Modifier.width(14.dp).height(3.dp).background(c, RoundedCornerShape(2.dp)))
                BasicText(name, style = Type.legend, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PostCard(post: Post?) {
    val context = LocalContext.current
    Row(
        Modifier.width(270.dp).height(106.dp).cardSurface(20f).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val img by rememberRemoteImage(context, post?.thumb?.let { MiniApi.url(it) }, 86)
        Box(Modifier.size(86.dp).background(Kiln.skeleton, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))) {
            img?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (post == null) {
                Skeleton(60, 10); Skeleton(140, 14); Skeleton(100, 12)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicText(post.kind.uppercase(), style = Type.postKind)
                    BasicText(post.date, style = Type.postDate)
                }
                BasicText(post.caption, style = Type.postCaption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                BasicText("${compact(post.views.toDouble())} views · ${compact(post.reach.toDouble())} reach · ${post.likes} likes", style = Type.postStats.copy(lineHeight = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp)), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
