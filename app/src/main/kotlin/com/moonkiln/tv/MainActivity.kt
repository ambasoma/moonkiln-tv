package com.moonkiln.tv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.moonkiln.tv.ambient.AmbientScreen
import com.moonkiln.tv.analytics.AnalyticsScreen
import com.moonkiln.tv.analytics.AnalyticsState
import com.moonkiln.tv.home.HomeActions
import com.moonkiln.tv.home.HomeFocus
import com.moonkiln.tv.home.HomeScreen
import com.moonkiln.tv.home.InputsScreen
import com.moonkiln.tv.home.LaunchableApp
import com.moonkiln.tv.home.installedApps
import com.moonkiln.tv.home.launch
import com.moonkiln.tv.home.openSettings
import com.moonkiln.tv.tv.NetworkState
import com.moonkiln.tv.tv.PS5_INPUT_ID
import com.moonkiln.tv.tv.TvInput
import com.moonkiln.tv.tv.readNetwork
import com.moonkiln.tv.ui.Kiln
import com.moonkiln.tv.ui.KilnRoot
import com.moonkiln.tv.ui.Motion
import com.moonkiln.tv.ui.OpeningPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class Screen { Home, Analytics, Ambient, Inputs }

class MainActivity : ComponentActivity() {
    private val app get() = application as App

    private var screen by mutableStateOf(Screen.Home)
    private var switching by mutableStateOf(false)
    private var opening by mutableStateOf<String?>(null)
    private var apps by mutableStateOf<List<LaunchableApp>>(emptyList())
    private var network by mutableStateOf(NetworkState("Wi-Fi", true))
    private var inputs by mutableStateOf<List<TvInput>>(emptyList())
    private val analytics = AnalyticsState()
    private val homeFocus = HomeFocus()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_ALL_APPS) screen = Screen.Ambient
        setContent {
            KilnRoot {
                val today by app.today.state.collectAsState()
                val ps5 by app.inputs.ps5.collectAsState()
                LaunchedEffect(Unit) {
                    apps = withContext(Dispatchers.Default) { installedApps(this@MainActivity, 34) }
                    while (true) {
                        network = readNetwork(this@MainActivity)
                        delay(30_000)
                    }
                }
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.radialGradient(listOf(Kiln.cardLift, Kiln.screen, Kiln.screen), center = androidx.compose.ui.geometry.Offset(230f, 0f), radius = 900f),
                    ),
                ) {
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = {
                            (fadeIn(Motion.tween(Motion.MEDIUM, delay = Motion.MEDIUM)) + slideInVertically(Motion.tween(Motion.MEDIUM, delay = Motion.MEDIUM)) { 12 })
                                .togetherWith(fadeOut(Motion.tween(Motion.MEDIUM)) + slideOutVertically(Motion.tween(Motion.MEDIUM)) { -12 })
                        },
                        label = "screen",
                    ) { s ->
                        when (s) {
                            Screen.Home -> {
                                LaunchedEffect(Unit) { runCatching { homeFocus.ps5.requestFocus() } }
                                HomeScreen(today, ps5, network, apps, switching, homeFocus, actions)
                            }
                            Screen.Analytics -> AnalyticsScreen(app.dashboard, analytics) { go(Screen.Home) }
                            Screen.Ambient -> AmbientScreen(today.today)
                            Screen.Inputs -> {
                                LaunchedEffect(Unit) { inputs = app.inputs.passthroughInputs() }
                                InputsScreen(inputs) { input -> switchInput(input.id) }
                            }
                        }
                    }
                    OpeningPill(opening, Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp))
                }
            }
        }
    }

    private val actions = object : HomeActions {
        override fun switchToPs5() = switchInput(PS5_INPUT_ID)
        override fun openAnalytics() = go(Screen.Analytics)
        override fun openAmbient() = go(Screen.Ambient)
        override fun openInputs() = go(Screen.Inputs)
        override fun openSettings() { opening = "Settings"; openSettings(this@MainActivity) }
        override fun openApp(app: LaunchableApp) { opening = app.label; launch(this@MainActivity, app.component) }
    }

    private fun go(target: Screen) {
        if (target == Screen.Home) analytics.collapse()
        screen = target
    }

    // The PS5 tile goes black with the switching pill for at most 350 ms, then Live TV takes the screen.
    private fun switchInput(id: String) {
        if (switching) return
        switching = true
        window.decorView.postDelayed({
            app.inputs.switchTo(id)
            window.decorView.postDelayed({ switching = false }, 1500)
        }, 200)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Home key: back to the home screen. Long press Home arrives as ALL_APPS: ambient.
        screen = if (intent.action == Intent.ACTION_ALL_APPS) Screen.Ambient else Screen.Home
        switching = false
    }

    override fun onResume() {
        super.onResume()
        switching = false
        opening = null
        app.inputs.refresh()
        network = readNetwork(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (screen == Screen.Ambient) {
            // Any button wakes ambient. Home itself arrives as an intent, not a key.
            go(Screen.Home)
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_TV_INPUT -> { switchInput(PS5_INPUT_ID); return true }
            KeyEvent.KEYCODE_BACK -> {
                if (screen == Screen.Analytics && analytics.collapse()) return true
                if (screen != Screen.Home) { go(Screen.Home); return true }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
