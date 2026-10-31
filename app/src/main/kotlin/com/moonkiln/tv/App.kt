package com.moonkiln.tv

import android.app.Application
import android.os.PowerManager
import com.moonkiln.tv.data.BridgeRepo
import com.moonkiln.tv.data.DashboardRepo
import com.moonkiln.tv.data.TodayRepo
import com.moonkiln.tv.toast.OrderToast
import com.moonkiln.tv.tv.TvInputs
import com.moonkiln.tv.ui.Motion

// One process, one set of long lived helpers: the launcher is the home app so it stays resident, and the
// order watcher keeps polling the mini while the PS5 has the screen.
class App : Application() {
    lateinit var today: TodayRepo
    lateinit var dashboard: DashboardRepo
    lateinit var inputs: TvInputs
    lateinit var toast: OrderToast
    val bridge = BridgeRepo()

    override fun onCreate() {
        super.onCreate()
        instance = this
        Motion.readScale(this)
        today = TodayRepo(this)
        dashboard = DashboardRepo(this)
        inputs = TvInputs(this)
        toast = OrderToast(this)
        today.onNewSale = { sale ->
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isInteractive) toast.show(sale)
        }
        today.start()
        inputs.start()
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
