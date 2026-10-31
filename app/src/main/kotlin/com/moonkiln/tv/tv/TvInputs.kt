package com.moonkiln.tv.tv

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "MkInputs"

// HDMI 1 on this set: the MediaTek HDMI input service, hardware port 5. The PS5 lives there.
const val PS5_INPUT_ID = "com.mediatek.tvinput/.hdmi.HDMIInputService/HW5"
const val LIVE_TV_PACKAGE = "com.mediatek.wwtv.tvcenter"

enum class Power { ON, STANDBY, DISCONNECTED, UNKNOWN }

data class TvInput(val id: String, val label: String, val power: Power)

// Reads and watches the inputs through TvInputManager. The CEC manager itself needs a signature permission
// a sideloaded app cannot hold, so the HDMI signal state is the honest proxy for the PS5 being awake.
class TvInputs(private val context: Context) {
    private val tim = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager

    private val _ps5 = MutableStateFlow(readPower(PS5_INPUT_ID))
    val ps5: StateFlow<Power> = _ps5

    private val callback = object : TvInputManager.TvInputCallback() {
        override fun onInputStateChanged(inputId: String, state: Int) {
            if (inputId == PS5_INPUT_ID) _ps5.value = toPower(state)
        }
        override fun onInputAdded(inputId: String) { if (inputId == PS5_INPUT_ID) _ps5.value = readPower(inputId) }
        override fun onInputRemoved(inputId: String) { if (inputId == PS5_INPUT_ID) _ps5.value = Power.DISCONNECTED }
    }

    fun start() {
        try {
            tim?.registerCallback(callback, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (e: Exception) { Log.w(TAG, "register: $e") }
        _ps5.value = readPower(PS5_INPUT_ID)
    }

    fun stop() {
        try { tim?.unregisterCallback(callback) } catch (_: Exception) {}
    }

    fun refresh() { _ps5.value = readPower(PS5_INPUT_ID) }

    private fun readPower(id: String): Power = try {
        toPower(tim?.getInputState(id) ?: -1)
    } catch (_: Exception) { Power.UNKNOWN }

    private fun toPower(state: Int): Power = when (state) {
        TvInputManager.INPUT_STATE_CONNECTED -> Power.ON
        TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> Power.STANDBY
        TvInputManager.INPUT_STATE_DISCONNECTED -> Power.DISCONNECTED
        else -> Power.UNKNOWN
    }

    // Every passthrough input the set has (HDMI ports, AV), with a readable label.
    fun passthroughInputs(): List<TvInput> {
        val list = try { tim?.tvInputList ?: emptyList() } catch (_: Exception) { emptyList<TvInputInfo>() }
        // Hardware ports only (HW5, HW6, AV): the CEC device entries (HDMI100004) double them up.
        return list.filter { it.isPassthroughInput && it.id.substringAfterLast('/').startsWith("HW") }
            .map { info ->
                val raw = try { info.loadLabel(context)?.toString() } catch (_: Exception) { null }
                val label = when {
                    info.id == PS5_INPUT_ID -> "HDMI 1 · PS5"
                    !raw.isNullOrBlank() -> raw
                    else -> info.id.substringAfterLast('/')
                }
                TvInput(info.id, label, readPower(info.id))
            }
            .sortedWith(compareBy({ !it.label.startsWith("HDMI") }, { it.label }))
    }

    // Switching is one VIEW intent on the passthrough URI; Live TV renders the picture and the set sends
    // Set Stream Path on CEC, which is what wakes the PS5 from rest.
    fun switchTo(inputId: String): Boolean {
        val uri = TvContract.buildChannelUriForPassthroughInput(inputId)
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "switch failed: $e")
            false
        }
    }
}

data class NetworkState(val label: String, val connected: Boolean)

fun readNetwork(context: Context): NetworkState {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return NetworkState("offline", false)
    val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return NetworkState("offline", false)
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkState("Ethernet", true)
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
            // The SSID needs a location grant on Android 10 and later; without it the set answers <unknown ssid>.
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val ssid = try { wm?.connectionInfo?.ssid?.trim('"') } catch (_: Exception) { null }
            val ok = !ssid.isNullOrBlank() && !ssid.contains("unknown", ignoreCase = true) && ssid != "0x"
            NetworkState(if (ok) ssid!! else "Wi-Fi", true)
        }
        else -> NetworkState("online", true)
    }
}
