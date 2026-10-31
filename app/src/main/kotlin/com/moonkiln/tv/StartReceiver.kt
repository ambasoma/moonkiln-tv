package com.moonkiln.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Starting the process is the whole job: App.onCreate starts the bridge listener, so announcements and the
// order toast work after a reboot or an update even when the TV comes up on HDMI and nobody presses Home.
class StartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
