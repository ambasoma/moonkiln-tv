package com.moonkiln.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.tv.TvInputManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.moonkiln.tv.tv.PS5_INPUT_ID

// Starting the process is the main job: App.onCreate starts the bridge listener, so announcements and the
// order toast work after a reboot or an update even when the TV comes up on HDMI and nobody presses Home.
//
// After a cold boot it also wins the screen back. Timeline measured on 1 Nov 2026: the MediaTek TV service
// (LiveTVLauncher, switched on by the read-only property ro.vendor.mtk.auto.enter.livetv) answers
// BOOT_COMPLETED by forcing Live TV on top 0.2 s later, often before our home activity is even launched.
// About 4 s after that Live TV tunes HDMI 1, and that tune sends a CEC power-on to the PS5, which then
// holds the screen (the PS5 bounce). So this receiver runs first in the ordered boot broadcast (priority
// 999 in the manifest), returns at once so it does not hold the queue, and brings the home screen back
// about 2 s later, in the gap after Live TV appears and before it tunes. A second pass a few seconds on
// covers a slow first one. Each pass leaves the PS5 alone when it is already on. SYSTEM_ALERT_WINDOW is
// what lets a background start through.
class StartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        Log.i(TAG, "boot completed received at ${SystemClock.elapsedRealtime()} ms after boot")
        for (delay in RECLAIM_AT_MS) handler.postDelayed({ reclaimHome(app, delay) }, delay)
    }

    private fun reclaimHome(context: Context, pass: Long) {
        val tim = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        val ps5 = try { tim?.getInputState(PS5_INPUT_ID) } catch (_: Exception) { null }
        if (ps5 == TvInputManager.INPUT_STATE_CONNECTED) {
            Log.i(TAG, "boot +$pass ms: PS5 is on, leaving its picture")
            return
        }
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
            Log.i(TAG, "boot +$pass ms: home screen brought back (PS5 state $ps5)")
        } catch (e: Exception) {
            Log.w(TAG, "boot +$pass ms: could not bring the home screen back: $e")
        }
    }

    private companion object {
        const val TAG = "MkStart"
        val RECLAIM_AT_MS = longArrayOf(2_000L, 6_000L)
    }
}
