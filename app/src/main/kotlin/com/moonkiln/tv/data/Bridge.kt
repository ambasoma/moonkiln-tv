package com.moonkiln.tv.data

import android.util.Log
import com.moonkiln.tv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "MkBridge"

// One line of the transcript on the TV.
enum class MsgKind { USER, CLAUDE, TOOL, ANNOUNCE }

data class Msg(
    val id: Long,
    val kind: MsgKind,
    val text: String,
    val running: Boolean = false,   // a tool marker still in flight
    val toolId: String = "",
    val local: Boolean = false,     // typed on the TV, not yet echoed by the session
)

data class BridgeState(
    val messages: List<Msg> = emptyList(),
    val status: String = "offline",   // live, working, listening, offline
    val reachable: Boolean = true,
    val alive: Boolean = false,
)

// The TV's end of the bridge: a long-poll loop while the Claude screen is open, prompts posted back.
// Never runs Claude; the session lives in tmux on the mini.
class BridgeRepo {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(BridgeState())
    val state: StateFlow<BridgeState> = _state
    private var job: Job? = null
    private var since = 0L
    private var nextLocalId = -1L

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val body = withContext(Dispatchers.IO) { fetch(since) }
                if (body == null) {
                    _state.value = _state.value.copy(reachable = false)
                    delay(3000)
                    continue
                }
                apply(body)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun clear() {
        _state.value = _state.value.copy(messages = emptyList())
    }

    // Show the prompt at once; the session's own echo (the prompt event) replaces it when it arrives.
    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val m = Msg(nextLocalId--, MsgKind.USER, t, local = true)
        _state.value = _state.value.copy(messages = (_state.value.messages + m).takeLast(40), status = "working")
        scope.launch {
            val ok = withContext(Dispatchers.IO) { post("/bridge/prompt", JSONObject().put("text", t)) }
            if (!ok) {
                val note = Msg(nextLocalId--, MsgKind.TOOL, "mini · the session is not running, start it on the mini")
                _state.value = _state.value.copy(messages = (_state.value.messages + note).takeLast(40), status = if (_state.value.alive) _state.value.status else "offline")
            }
        }
    }

    // The mic stub: the remote's microphone needs Bluetooth pairing, so this only shows the state.
    fun listeningStub() {
        scope.launch {
            _state.value = _state.value.copy(status = "listening")
            delay(1400)
            val note = Msg(nextLocalId--, MsgKind.TOOL, "mic · the remote is not paired over Bluetooth yet, talk from your phone")
            _state.value = _state.value.copy(messages = (_state.value.messages + note).takeLast(40), status = if (_state.value.alive) "live" else "offline")
        }
    }

    private fun apply(body: JSONObject) {
        val events = body.optJSONArray("events")
        var msgs = _state.value.messages
        if (events != null) {
            for (i in 0 until events.length()) {
                val e = events.getJSONObject(i)
                since = maxOf(since, e.optLong("seq"))
                val text = e.optString("text")
                when (e.optString("type")) {
                    "prompt" -> {
                        // Replace the TV's optimistic pill if it is the same prompt.
                        val idx = msgs.indexOfLast { it.local && it.kind == MsgKind.USER && it.text == text }
                        msgs = if (idx >= 0) msgs.toMutableList().also { it[idx] = Msg(e.optLong("seq"), MsgKind.USER, text) }
                        else msgs + Msg(e.optLong("seq"), MsgKind.USER, text)
                    }
                    "text" -> if (text.isNotBlank()) msgs = msgs + Msg(e.optLong("seq"), MsgKind.CLAUDE, text)
                    "tool_start" -> msgs = msgs + Msg(e.optLong("seq"), MsgKind.TOOL, text, running = true, toolId = e.optString("tool_id"))
                    "tool_done" -> {
                        val id = e.optString("tool_id")
                        val idx = msgs.indexOfLast { it.kind == MsgKind.TOOL && it.running && (it.toolId == id || (id.isEmpty() && it.text == text)) }
                        msgs = if (idx >= 0) msgs.toMutableList().also { it[idx] = it[idx].copy(running = false) }
                        else msgs + Msg(e.optLong("seq"), MsgKind.TOOL, text)
                    }
                    "announce" -> msgs = msgs + Msg(e.optLong("seq"), MsgKind.ANNOUNCE, text)
                    else -> {}
                }
            }
        }
        _state.value = BridgeState(
            messages = msgs.takeLast(40),
            status = body.optString("status", "offline"),
            reachable = true,
            alive = body.optBoolean("alive", false),
        )
    }

    private fun fetch(since: Long): JSONObject? = try {
        val conn = (URL(MiniApi.url("/bridge/events?since=$since&wait=25")).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 32000
            setRequestProperty("Authorization", "Bearer ${BuildConfig.TODAY_TOKEN}")
        }
        try {
            if (conn.responseCode != 200) null else JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally { conn.disconnect() }
    } catch (e: Exception) {
        Log.w(TAG, "events: ${e.message}")
        null
    }

    private fun post(path: String, body: JSONObject): Boolean = try {
        val conn = (URL(MiniApi.url(path)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 8000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${BuildConfig.TODAY_TOKEN}")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            conn.responseCode == 200
        } finally { conn.disconnect() }
    } catch (e: Exception) {
        Log.w(TAG, "post: ${e.message}")
        false
    }
}
