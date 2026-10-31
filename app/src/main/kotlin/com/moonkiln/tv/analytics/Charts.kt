package com.moonkiln.tv.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.moonkiln.tv.data.ChannelRow
import com.moonkiln.tv.data.SearchRow
import com.moonkiln.tv.data.SocialRow
import com.moonkiln.tv.data.compact
import com.moonkiln.tv.ui.Kiln
import com.moonkiln.tv.ui.Type
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// Chart geometry, straight from the canvas: 30 px for the axis labels on the left, 6 px air on the right,
// the plot between y 10 and y 146 of a 170 px tall area, date labels on the 164 line.
const val X0 = 30f
const val Y0 = 10f
const val Y1 = 146f
const val CHART_H = 170
const val LABEL_Y = 164f

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

fun dateLabel(iso: String): String = try {
    val d = LocalDate.parse(iso)
    "${d.dayOfMonth} ${MONTHS[d.monthValue - 1]}"
} catch (_: Exception) { iso }

fun niceStep(range: Double, target: Int): Double {
    val raw = range / target
    val p = 10.0.pow(floor(log10(if (raw <= 0) 1.0 else raw)))
    val cands = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * p }
    return cands.firstOrNull { it >= raw } ?: cands.last()
}

fun labelEvery(w: Float, n: Int): Int {
    if (w < 260) return max(1, n - 1)
    val per = if (w > 500) 40f else 60f
    return max(1, ((n / ((w - X0) / per))).roundToInt())
}

private fun DrawScope.roundedTopBar(x: Float, top: Float, w: Float, base: Float, color: Color, alpha: Float) {
    val h = base - top
    if (h <= 0.5f) return
    val rad = min(4f, min(w / 2f, h / 2f))
    val path = Path().apply {
        moveTo(x, base)
        lineTo(x, top + rad)
        arcTo(androidx.compose.ui.geometry.Rect(x, top, x + 2 * rad, top + 2 * rad), 180f, 90f, false)
        lineTo(x + w - rad, top)
        arcTo(androidx.compose.ui.geometry.Rect(x + w - 2 * rad, top, x + w, top + 2 * rad), 270f, 90f, false)
        lineTo(x + w, base)
        close()
    }
    drawPath(path, color, alpha = alpha)
}

private fun DrawScope.gridAndAxis(tm: TextMeasurer, x1: Float, hi: Double, step: Double) {
    var v = 0.0
    while (v <= hi + 0.001) {
        val y = (Y1 - (v / hi) * (Y1 - Y0)).toFloat()
        drawLine(Kiln.hairline, Offset(X0, y), Offset(x1, y), strokeWidth = 1f)
        val t = tm.measure(compact(v), Type.axis)
        drawText(t, topLeft = Offset(25f - t.size.width, y + 4f - t.size.height * 0.78f))
        v += step
    }
    drawLine(Kiln.field, Offset(X0, Y1), Offset(x1, Y1), strokeWidth = 1f)
}

private fun DrawScope.xLabels(tm: TextMeasurer, dates: List<String>, slot: Float, w: Float) {
    val n = dates.size
    val every = labelEvery(w, n)
    dates.forEachIndexed { i, d ->
        val show = i % every == 0 || (w < 260 && i == n - 1)
        if (!show) return@forEachIndexed
        val t = tm.measure(dateLabel(d), Type.axis)
        val cx = X0 + i * slot + slot / 2
        drawText(t, topLeft = Offset(cx - t.size.width / 2, LABEL_Y - t.size.height * 0.78f))
    }
}

// Sessions by channel, stacked bars with 4 px rounded tops and 2 px gaps between segments.
@Composable
fun ChannelChart(rows: List<ChannelRow>, width: Int, progress: Float, hover: Int) {
    val tm = rememberTextMeasurer(cacheSize = 64)
    Canvas(Modifier.width(width.dp).height(CHART_H.dp)) {
        val w = width.toFloat()
        val x1 = w - 6f
        val n = rows.size
        if (n == 0) return@Canvas
        val totals = rows.map { it.values.sum().toDouble() }
        val cMax = max(1.0, totals.maxOrNull() ?: 1.0)
        val step = niceStep(cMax, 3)
        val hi = ceil(cMax / step) * step
        val slot = (x1 - X0) / n
        val cw = min(24f, max(3f, slot - 2f))
        gridAndAxis(tm, x1, hi, step)
        rows.forEachIndexed { i, r ->
            val x = X0 + i * slot + (slot - cw) / 2
            var acc = 0.0
            var topIdx = -1
            for (k in r.values.indices) if (r.values[k] > 0) topIdx = k
            val alpha = if (hover == -1 || hover == i) 1f else 0.45f
            for (k in r.values.indices) {
                val v = r.values[k].toDouble()
                if (v <= 0) continue
                val yTop = (Y1 - ((acc + v) / hi) * (Y1 - Y0) * progress).toFloat()
                val yBot = (Y1 - (acc / hi) * (Y1 - Y0) * progress).toFloat()
                val colour = Kiln.channelColours[k]
                if (k == topIdx) {
                    val base = if (acc > 0) yBot - 2f else yBot
                    roundedTopBar(x, yTop, cw, max(base, yTop + 0.6f), colour, alpha)
                } else {
                    val h = max(0f, (yBot - yTop) - (if (acc > 0) 2f else 0f))
                    drawRect(colour, topLeft = Offset(x, yTop), size = Size(cw, h), alpha = alpha)
                }
                acc += v
            }
        }
        xLabels(tm, rows.map { it.date }, slot, w)
    }
}

// Social trends: three lines, each on its own scale, drawn left to right as progress runs.
@Composable
fun TrendChart(rows: List<SocialRow>, width: Int, progress: Float, hover: Int) {
    val tm = rememberTextMeasurer(cacheSize = 64)
    Canvas(Modifier.width(width.dp).height(CHART_H.dp)) {
        val w = width.toFloat()
        val x1 = w - 6f
        val n = rows.size
        if (n == 0) return@Canvas
        listOf(0.25f, 0.5f, 0.75f).forEach { f ->
            val y = Y1 - f * (Y1 - Y0)
            drawLine(Kiln.hairline, Offset(X0, y), Offset(x1, y), strokeWidth = 1f)
        }
        val tx = { i: Int -> X0 + (if (n > 1) i / (n - 1f) else 0f) * (x1 - X0) }
        val series = listOf(
            Triple(rows.map { it.followers?.toDouble() }, Kiln.trendFollowers, "followers"),
            Triple(rows.map { it.reach7 }, Kiln.trendReach, "reach"),
            Triple(rows.map { it.eng7 }, Kiln.trendEngagement, "eng"),
        )
        val measure = PathMeasure()
        series.forEach { (vals, colour, _) ->
            val known = vals.filterNotNull()
            if (known.isEmpty()) return@forEach
            val mn = known.min()
            val mx = known.max()
            val pad = (Y1 - Y0) * 0.08f
            val scale = { v: Double -> ((Y1 - pad) - ((v - mn) / ((mx - mn).takeIf { it > 0 } ?: 1.0)) * ((Y1 - Y0) - 2 * pad)).toFloat() }
            val path = Path()
            var pen = false
            vals.forEachIndexed { i, v ->
                if (v == null) { pen = false; return@forEachIndexed }
                val y = scale(v)
                if (pen) path.lineTo(tx(i), y) else path.moveTo(tx(i), y)
                pen = true
            }
            if (progress >= 1f) {
                drawPath(path, colour, style = Stroke(width = 2f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            } else {
                measure.setPath(path, false)
                val seg = Path()
                measure.getSegment(0f, measure.length * progress, seg, true)
                drawPath(seg, colour, style = Stroke(width = 2f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
            val lastIdx = vals.indexOfLast { it != null }
            if (progress >= 0.98f && lastIdx >= 0) {
                val cx = tx(lastIdx)
                val cy = scale(vals[lastIdx]!!)
                drawCircle(Kiln.card, 5f, Offset(cx, cy))
                drawCircle(colour, 4f, Offset(cx, cy))
            }
            if (hover in 0 until n && vals[hover] != null) {
                val cx = tx(hover)
                val cy = scale(vals[hover]!!)
                drawCircle(Kiln.card, 6f, Offset(cx, cy))
                drawCircle(colour, 5f, Offset(cx, cy))
            }
        }
        if (hover in 0 until n) {
            val hx = tx(hover)
            drawLine(Kiln.grey5, Offset(hx, Y0), Offset(hx, Y1), strokeWidth = 1f)
        }
        val idxs = if (w < 260) listOf(0, n - 1) else listOf(0, (n - 1) / 2, n - 1)
        idxs.distinct().forEachIndexed { k, i ->
            val t = tm.measure(dateLabel(rows[i].date), Type.axis)
            val x = when (k) { 0 -> tx(i); idxs.distinct().size - 1 -> tx(i) - t.size.width; else -> tx(i) - t.size.width / 2 }
            drawText(t, topLeft = Offset(x, LABEL_Y - t.size.height * 0.78f))
        }
    }
}

// Google search: impressions, clicks and orders from search as overlaid bars on one axis.
@Composable
fun SearchChart(rows: List<SearchRow>, width: Int, progress: Float, hover: Int) {
    val tm = rememberTextMeasurer(cacheSize = 64)
    Canvas(Modifier.width(width.dp).height(CHART_H.dp)) {
        val w = width.toFloat()
        val x1 = w - 6f
        val n = rows.size
        if (n == 0) return@Canvas
        val slot = (x1 - X0) / n
        val sw = min(24f, max(3f, slot - 2f))
        val sMax = max(1.0, rows.maxOf { it.impressions.toDouble() })
        val step = niceStep(sMax, 3)
        val hi = ceil(sMax / step) * step
        gridAndAxis(tm, x1, hi, step)
        val layers = listOf(
            Triple({ r: SearchRow -> r.impressions.toDouble() }, Kiln.searchImpressions, 1f),
            Triple({ r: SearchRow -> r.clicks.toDouble() }, Kiln.searchClicks, 0.6f),
            Triple({ r: SearchRow -> r.orders }, Kiln.searchOrders, 0.34f),
        )
        rows.forEachIndexed { i, r ->
            val alpha = if (hover == -1 || hover == i) 1f else 0.5f
            layers.forEach { (get, colour, wf) ->
                val v = get(r)
                if (v <= 0) return@forEach
                val bw = max(2f, sw * wf)
                val x = X0 + i * slot + (slot - bw) / 2
                val top = min((Y1 - (v / hi) * (Y1 - Y0) * progress).toFloat(), Y1 - 2f * progress)
                roundedTopBar(x, top, bw, Y1, colour, alpha)
            }
        }
        xLabels(tm, rows.map { it.date }, slot, w)
    }
}

@Composable
fun ChartSkeleton(width: Int) {
    Box(Modifier.width(width.dp).height(CHART_H.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val x1 = width - 6f
            listOf(0.25f, 0.5f, 0.75f, 1f).forEach { f ->
                val y = Y1 - f * (Y1 - Y0)
                drawLine(Kiln.hairline, Offset(X0, y), Offset(x1, y), strokeWidth = 1f)
            }
            drawLine(Kiln.field, Offset(X0, Y1), Offset(x1, Y1), strokeWidth = 1f)
            val n = 12
            val slot = (x1 - X0) / n
            for (i in 0 until n) {
                val h = 20f + (i * 37 % 70)
                drawRoundRect(Kiln.skeleton, topLeft = Offset(X0 + i * slot + 2f, Y1 - h), size = Size(slot - 4f, h), cornerRadius = CornerRadius(4f))
            }
        }
    }
}
