package com.moonkiln.tv.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.moonkiln.tv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "MkMini"

// The mini endpoint, LAN only, bearer token baked in at build time from local.properties.
object MiniApi {
    private val base = BuildConfig.MINI_BASE.trimEnd('/')

    fun url(path: String): String = base + path

    @Throws(Exception::class)
    fun getJson(path: String): JSONObject {
        val conn = (URL(url(path)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 15000
            setRequestProperty("Authorization", "Bearer ${BuildConfig.TODAY_TOKEN}")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code for $path")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}

data class LatestSale(
    val orderId: String,
    val orderName: String,
    val title: String,
    val amount: Double,
    val city: String?,
    val createdAtMs: Long,
    val imageUrl: String?,
)

data class Today(
    val ordersToday: Int,
    val revenueToday: Double,
    val awaitingPickupPieces: Int,
    val awaitingPickupShipments: Int,
    val toFulfilShop: Int,
    val toFulfilCustom: Int,
    val latestSale: LatestSale?,
    val staleOnMini: Boolean,
    val fetchedAtMs: Long,
) {
    companion object {
        fun parse(o: JSONObject, fetchedAtMs: Long): Today {
            val ls = o.optJSONObject("latest_sale")
            return Today(
                ordersToday = o.optInt("orders_today"),
                revenueToday = o.optDouble("revenue_today", 0.0),
                awaitingPickupPieces = o.optInt("awaiting_pickup_pieces"),
                awaitingPickupShipments = o.optInt("awaiting_pickup_shipments"),
                toFulfilShop = o.optInt("to_fulfil_shop"),
                toFulfilCustom = o.optInt("to_fulfil_custom"),
                latestSale = ls?.let {
                    LatestSale(
                        orderId = it.optString("order_id"),
                        orderName = it.optString("order_name"),
                        title = it.optString("title", "an order"),
                        amount = it.optDouble("amount", 0.0),
                        city = it.optString("city").takeIf { c -> c.isNotBlank() && c != "null" },
                        createdAtMs = parseIso(it.optString("created_at")),
                        imageUrl = it.optString("image_url").takeIf { u -> u.startsWith("http") },
                    )
                },
                staleOnMini = o.optBoolean("stale", false),
                fetchedAtMs = fetchedAtMs,
            )
        }
    }
}

fun parseIso(s: String?): Long {
    if (s.isNullOrBlank()) return 0L
    return try {
        java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (_: Exception) { 0L }
}

// Keeps the last good /today answer, polls it every minute while someone may be looking, and tells the
// screen how old the numbers are when the mini cannot be reached.
class TodayRepo(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("mk_today", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    data class State(val today: Today?, val reachable: Boolean, val loading: Boolean)

    private val _state = MutableStateFlow(State(loadCached(), reachable = true, loading = true))
    val state: StateFlow<State> = _state

    // Fires with the latest sale when a new one appears after the first successful poll.
    var onNewSale: ((LatestSale) -> Unit)? = null

    private var polling = false

    fun start() {
        if (polling) return
        polling = true
        scope.launch {
            while (isActive) {
                refresh()
                delay(60_000)
            }
        }
    }

    suspend fun refresh() {
        val now = System.currentTimeMillis()
        val fresh = withContext(Dispatchers.IO) {
            try {
                Today.parse(MiniApi.getJson("/today"), now)
            } catch (e: Exception) {
                Log.w(TAG, "today failed: ${e.message}")
                null
            }
        }
        if (fresh == null) {
            _state.value = _state.value.copy(reachable = false, loading = false)
            return
        }
        prefs.edit().putString("today_json", toJson(fresh)).putLong("today_at", now).apply()
        val previous = _state.value.today
        _state.value = State(fresh, reachable = true, loading = false)
        val sale = fresh.latestSale
        val lastSeen = prefs.getString("last_sale_id", null)
        if (sale != null && sale.orderId.isNotBlank() && sale.orderId != lastSeen) {
            prefs.edit().putString("last_sale_id", sale.orderId).apply()
            // Only announce sales that arrived while the launcher was running, never the one it woke up to.
            val recent = now - sale.createdAtMs < 30 * 60_000
            if (lastSeen != null && previous != null && recent) onNewSale?.invoke(sale)
        }
    }

    private fun loadCached(): Today? {
        val s = prefs.getString("today_json", null) ?: return null
        return try { Today.parse(JSONObject(s), prefs.getLong("today_at", 0L)) } catch (_: Exception) { null }
    }

    private fun toJson(t: Today): String {
        val o = JSONObject()
        o.put("orders_today", t.ordersToday)
        o.put("revenue_today", t.revenueToday)
        o.put("awaiting_pickup_pieces", t.awaitingPickupPieces)
        o.put("awaiting_pickup_shipments", t.awaitingPickupShipments)
        o.put("to_fulfil_shop", t.toFulfilShop)
        o.put("to_fulfil_custom", t.toFulfilCustom)
        t.latestSale?.let { ls ->
            val l = JSONObject()
            l.put("order_id", ls.orderId); l.put("order_name", ls.orderName); l.put("title", ls.title)
            l.put("amount", ls.amount); l.put("city", ls.city ?: JSONObject.NULL)
            l.put("created_at", java.time.Instant.ofEpochMilli(ls.createdAtMs).atOffset(java.time.ZoneOffset.ofHoursMinutes(5, 30)).toString())
            l.put("image_url", ls.imageUrl ?: JSONObject.NULL)
            o.put("latest_sale", l)
        }
        return o.toString()
    }
}

// ---------------------------------------------------------------------------------------------------
// Dashboard
// ---------------------------------------------------------------------------------------------------

data class Kpi(val key: String, val label: String, val value: Double, val display: String, val delta: String, val dir: String)
data class ChannelRow(val date: String, val values: IntArray)
data class SocialRow(val date: String, val followers: Int?, val reach7: Double, val eng7: Double)
data class SearchRow(val date: String, val impressions: Int, val clicks: Int, val orders: Double)
data class Post(val id: String, val kind: String, val date: String, val caption: String, val views: Int, val reach: Int, val likes: Int, val thumb: String?)

data class Dashboard(
    val days: Int,
    val weekly: Boolean,
    val kpis: List<Kpi>,
    val channelNames: List<String>,
    val channels: List<ChannelRow>,
    val channelTotal: Int,
    val social: List<SocialRow>,
    val latestFollowers: Int?,
    val latestReach7: Double,
    val latestEng7: Double,
    val search: List<SearchRow>,
    val searchImpressions: Int,
    val searchClicks: Int,
    val searchOrders: Int,
    val posts: List<Post>,
    val fetchedAtMs: Long,
) {
    companion object {
        fun parse(o: JSONObject, fetchedAtMs: Long): Dashboard {
            val kpis = o.getJSONArray("kpis").map { k ->
                Kpi(k.getString("key"), k.getString("label"), k.optDouble("value", 0.0), k.getString("display"), k.getString("delta"), k.getString("dir"))
            }
            val ch = o.getJSONObject("channels")
            val names = ch.getJSONArray("series").let { a -> List(a.length()) { a.getString(it) } }
            val chRows = ch.getJSONArray("rows").map { r ->
                val v = r.getJSONArray("v")
                ChannelRow(r.getString("d"), IntArray(v.length()) { v.getInt(it) })
            }
            val soc = o.getJSONObject("social")
            val socRows = soc.getJSONArray("rows").map { r ->
                SocialRow(r.getString("d"), if (r.isNull("followers")) null else r.getInt("followers"), r.optDouble("reach7", 0.0), r.optDouble("eng7", 0.0))
            }
            val latest = soc.getJSONObject("latest")
            val se = o.getJSONObject("search")
            val seRows = se.getJSONArray("rows").map { r ->
                SearchRow(r.getString("d"), r.optInt("impressions"), r.optInt("clicks"), r.optDouble("orders", 0.0))
            }
            val tot = se.getJSONObject("totals")
            val posts = o.getJSONArray("posts").map { p ->
                Post(p.getString("id"), p.getString("kind"), p.optString("date"), p.optString("caption"), p.optInt("views"), p.optInt("reach"), p.optInt("likes"),
                    p.optString("thumb").takeIf { it.startsWith("/") })
            }
            return Dashboard(
                days = o.getInt("days"), weekly = o.optBoolean("weekly"), kpis = kpis,
                channelNames = names, channels = chRows, channelTotal = ch.optInt("total"),
                social = socRows,
                latestFollowers = if (latest.isNull("followers")) null else latest.getInt("followers"),
                latestReach7 = latest.optDouble("reach7", 0.0), latestEng7 = latest.optDouble("eng7", 0.0),
                search = seRows, searchImpressions = tot.optInt("impressions"), searchClicks = tot.optInt("clicks"), searchOrders = tot.optInt("orders"),
                posts = posts, fetchedAtMs = fetchedAtMs,
            )
        }
    }
}

private inline fun <T> JSONArray.map(f: (JSONObject) -> T): List<T> = List(length()) { f(getJSONObject(it)) }

class DashboardRepo(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("mk_dashboard", Context.MODE_PRIVATE)

    data class State(val dashboard: Dashboard?, val reachable: Boolean, val loading: Boolean)

    private val flows = mutableMapOf<Int, MutableStateFlow<State>>()

    fun flow(days: Int): StateFlow<State> = flows.getOrPut(days) {
        MutableStateFlow(State(loadCached(days), reachable = true, loading = true))
    }

    suspend fun refresh(days: Int) {
        val f = flows.getOrPut(days) { MutableStateFlow(State(loadCached(days), reachable = true, loading = true)) }
        val now = System.currentTimeMillis()
        val cached = f.value.dashboard
        // Ten minutes between pulls is plenty: the mini itself caches for ten.
        if (cached != null && now - cached.fetchedAtMs < 10 * 60_000 && f.value.reachable) {
            f.value = f.value.copy(loading = false)
            return
        }
        f.value = f.value.copy(loading = cached == null)
        val text = withContext(Dispatchers.IO) {
            try {
                MiniApi.getJson("/dashboard?days=$days").toString()
            } catch (e: Exception) {
                Log.w(TAG, "dashboard $days failed: ${e.message}")
                null
            }
        }
        if (text == null) {
            f.value = f.value.copy(reachable = false, loading = false)
            return
        }
        val parsed = try { Dashboard.parse(JSONObject(text), now) } catch (e: Exception) { Log.w(TAG, "dashboard parse: $e"); null }
        if (parsed == null) {
            f.value = f.value.copy(reachable = false, loading = false)
            return
        }
        prefs.edit().putString("d$days", text).putLong("d${days}_at", now).apply()
        f.value = State(parsed, reachable = true, loading = false)
    }

    private fun loadCached(days: Int): Dashboard? {
        val s = prefs.getString("d$days", null) ?: return null
        return try { Dashboard.parse(JSONObject(s), prefs.getLong("d${days}_at", 0L)) } catch (_: Exception) { null }
    }
}

// Rs. 1,23,456: Indian grouping, no paise on a TV.
fun groupIndian(amount: Double): String {
    val n = Math.round(amount)
    val neg = n < 0
    val s = Math.abs(n).toString()
    if (s.length <= 3) return (if (neg) "-" else "") + s
    val tail = s.takeLast(3)
    var head = s.dropLast(3)
    val parts = ArrayList<String>()
    while (head.length > 2) {
        parts.add(0, head.takeLast(2))
        head = head.dropLast(2)
    }
    if (head.isNotEmpty()) parts.add(0, head)
    return (if (neg) "-" else "") + parts.joinToString(",") + "," + tail
}

fun rupees(amount: Double): String = "₹" + groupIndian(amount)

fun compact(n: Double): String = when {
    n >= 100_000 -> trimZero(n / 100_000) + "L"
    n >= 1_000 -> trimZero(n / 1_000) + "K"
    else -> Math.round(n).toString()
}

private fun trimZero(v: Double): String {
    val s = String.format(java.util.Locale.US, "%.1f", v)
    return if (s.endsWith(".0")) s.dropLast(2) else s
}
