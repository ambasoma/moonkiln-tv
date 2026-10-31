package com.moonkiln.tv.announce

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.moonkiln.tv.R
import com.moonkiln.tv.data.MiniApi
import com.moonkiln.tv.ui.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "MkAnnounce"

data class Announcement(val id: String, val text: String, val from: String, val time: String, val voiceUrl: String?, val durationS: Double?)

// The home announcement, after Announce.dc.html: an overlay window over anything on screen, the PS5
// picture included. Chime at 0, a flat 94% ink scrim over 350 ms, the message rising 12 px over 550 ms,
// the voice from 700 ms with the words lighting up as they are said, then Got it and Say it again.
// Plain views, built when a message arrives and dropped after, so it costs nothing while idle. Remote keys
// stop at the overlay: it takes focus and consumes every key, so nothing reaches the PS5 underneath.
class AnnounceOverlay(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val ease = PathInterpolator(0.2f, 0.7f, 0.3f, 1f)
    private val scope = CoroutineScope(Dispatchers.Main)

    private val queue = ArrayDeque<Announcement>()

    // Ids already put on screen, kept across restarts: after an update or a reboot the bridge replays its
    // recent events, and an announcement someone has already dismissed must not come back.
    private val shownPrefs = context.getSharedPreferences("announce", Context.MODE_PRIVATE)
    private fun alreadyShown(id: String) = id in (shownPrefs.getStringSet("shown", emptySet()) ?: emptySet())
    private fun markShown(id: String) {
        val ids = (shownPrefs.getString("order", "") ?: "").split(' ').filter { it.isNotEmpty() }.plus(id).takeLast(50)
        shownPrefs.edit().putString("order", ids.joinToString(" ")).putStringSet("shown", ids.toSet()).apply()
    }

    private fun refreshQueuedPill() {
        queuedPill?.text = "${queue.size} more after this"
        queuedPill?.visibility = if (queue.isEmpty()) View.GONE else View.VISIBLE
    }
    private var current: Announcement? = null
    private var root: FrameLayout? = null
    private var scrim: View? = null
    private var content: LinearLayout? = null
    private var message: TextView? = null
    private var foot: LinearLayout? = null
    private var speakingRow: View? = null
    private var pills: LinearLayout? = null
    private var gotIt: TextView? = null
    private var again: TextView? = null
    private var queuedPill: TextView? = null
    private var focusOnAgain = false
    private var phase = "idle"
    private var voice: MediaPlayer? = null
    private var chime: MediaPlayer? = null
    private var voiceFile: File? = null
    private var restoreVolume: Int? = null
    private var focusRequest: AudioFocusRequest? = null
    private var words: List<Pair<String, String>> = emptyList()
    private var spoken = 0
    private var cornerPill: View? = null

    private val cream = Color.parseColor("#F4EFE2")
    private val ink = Color.parseColor("#1C1A17")
    private val card = Color.parseColor("#26231F")
    private val field = Color.parseColor("#3A362F")
    private val grey1 = Color.parseColor("#C9C2AE")
    private val grey4 = Color.parseColor("#8A8270")
    private val dim = Color.parseColor("#5A554B")
    private val green = Color.parseColor("#8FB544")
    private val sun = Color.parseColor("#FFC92F")

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    fun enqueue(a: Announcement) {
        if (!canShow()) { Log.w(TAG, "overlay permission missing"); return }
        Log.i(TAG, "enqueue ${a.id} from ${a.from}")
        handler.post {
            if (alreadyShown(a.id) || current?.id == a.id || queue.any { it.id == a.id }) {
                Log.i(TAG, "skip ${a.id}, already shown or queued"); return@post
            }
            dropCornerPill()
            queue.addLast(a)
            if (current == null) next()
            else refreshQueuedPill()
        }
    }

    private fun next() {
        val a = queue.removeFirstOrNull() ?: run { current = null; return }
        current = a
        scope.launch {
            voiceFile = a.voiceUrl?.let { fetchVoice(a.id, it) }
            show(a)
        }
    }

    private suspend fun fetchVoice(id: String, path: String): File? = withContext(Dispatchers.IO) {
        try {
            val f = File(context.cacheDir, "announce-$id.wav")
            if (f.exists() && f.length() > 0) return@withContext f
            val conn = (URL(MiniApi.url(path)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000; readTimeout = 20000
                setRequestProperty("Authorization", "Bearer ${com.moonkiln.tv.BuildConfig.TODAY_TOKEN}")
            }
            try {
                if (conn.responseCode != 200) return@withContext null
                conn.inputStream.use { i -> f.outputStream().use { i.copyTo(it) } }
                f
            } finally { conn.disconnect() }
        } catch (e: Exception) { Log.w(TAG, "voice fetch: $e"); null }
    }

    private fun font(res: Int, weight: Int): Typeface = try {
        Typeface.create(context.resources.getFont(res), weight, false)
    } catch (_: Exception) { Typeface.SANS_SERIF }

    private fun show(a: Announcement) {
        Log.i(TAG, "show ${a.id}, voice=${voiceFile != null}")
        words = a.text.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { w ->
            val m = Regex("^(.*?)([.,!?]*)$").find(w)!!
            m.groupValues[1] to m.groupValues[2]
        }
        spoken = 0
        phase = "in"
        build(a)
        markShown(a.id)
        receipt(a.id, "shown")
        raiseVolumeIfNeeded()
        // t = 0: chime, scrim and the message start together.
        playChime()
        scrim?.animate()?.alpha(0.94f)?.setDuration(Motion.ms(350).toLong())?.setInterpolator(ease)?.start()
        content?.let { c ->
            c.alpha = 0f; c.translationY = 12f
            c.animate().alpha(1f).setDuration(Motion.ms(350).toLong()).setInterpolator(ease).start()
            c.animate().translationY(0f).setDuration(Motion.ms(550).toLong()).setInterpolator(ease).start()
        }
        handler.postDelayed({ speak() }, 700)
        // After ten minutes unseen, shrink to a corner pill so a game is never blocked for long.
        handler.postDelayed({ if (current?.id == a.id && phase != "idle") shrinkToCorner(a) }, 10 * 60_000L)
    }

    private fun speak() {
        val a = current ?: return
        val f = voiceFile
        phase = "speaking"
        speakingRow?.visibility = View.VISIBLE
        foot?.animate()?.alpha(1f)?.setDuration(Motion.ms(180).toLong())?.start()
        if (f != null) {
            try {
                val mp = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    setDataSource(f.path)
                    prepare()
                }
                voice = mp
                requestFocus()
                mp.setOnCompletionListener { handler.postDelayed({ waiting() }, 300) }
                mp.start()
                val durationMs = (a.durationS?.let { it * 1000 } ?: mp.duration.toDouble()).coerceAtLeast(400.0)
                lightWords(durationMs)
                return
            } catch (e: Exception) { Log.w(TAG, "voice play: $e") }
        }
        // No voice: light the words at reading pace, then wait.
        lightWords(words.size * 330.0)
        handler.postDelayed({ waiting() }, (words.size * 330 + 300).toLong())
    }

    private fun lightWords(totalMs: Double) {
        val n = words.size
        if (n == 0) return
        val step = totalMs / n
        for (i in 0 until n) {
            handler.postDelayed({ spoken = i + 1; renderWords() }, (i * step).toLong())
        }
    }

    private fun waiting() {
        if (phase == "idle") return
        Log.i(TAG, "waiting for OK")
        phase = "waiting"
        spoken = words.size
        renderWords()
        speakingRow?.visibility = View.GONE
        pills?.visibility = View.VISIBLE
        focusOnAgain = false
        renderPills()
        abandonFocus()
        restoreVolume()
    }

    private fun dismiss() {
        val a = current ?: return
        Log.i(TAG, "dismissed ${a.id}, seen")
        phase = "idle"
        handler.removeCallbacksAndMessages(null)
        stopAudio()
        restoreVolume()
        receipt(a.id, "seen")
        val r = root
        content?.animate()?.alpha(0f)?.setDuration(Motion.ms(180).toLong())?.setInterpolator(ease)?.start()
        scrim?.animate()?.alpha(0f)?.setDuration(Motion.ms(350).toLong())?.setInterpolator(ease)?.withEndAction {
            if (r != null) try { wm.removeView(r) } catch (_: Exception) {}
            root = null
            if (queue.isNotEmpty()) handler.postDelayed({ next() }, 100) else { current = null; seenPill(a) }
        }?.start()
    }

    private fun sayAgain() {
        if (phase != "waiting") return
        pills?.visibility = View.GONE
        spoken = 0
        renderWords()
        stopAudio()
        raiseVolumeIfNeeded()
        playChime()
        handler.postDelayed({ speak() }, 700)
    }

    private fun seenPill(a: Announcement) {
        val pill = pill("Seen. ${a.from} has been told.", cream, card, field, 40, 14f).apply {
            compoundDrawablePadding = 8
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.END; x = 40; y = 36 }
        try { wm.addView(pill, lp) } catch (_: Exception) { return }
        pill.alpha = 0f
        pill.animate().alpha(1f).setDuration(Motion.ms(180).toLong()).start()
        handler.postDelayed({ pill.animate().alpha(0f).setDuration(Motion.ms(180).toLong()).withEndAction { try { wm.removeView(pill) } catch (_: Exception) {} }.start() }, 2200)
    }

    private fun shrinkToCorner(a: Announcement) {
        phase = "idle"
        handler.removeCallbacksAndMessages(null)
        stopAudio()
        restoreVolume()
        val r = root
        if (r != null) try { wm.removeView(r) } catch (_: Exception) {}
        root = null
        current = null
        val pill = pill("From ${a.from}: ${a.text.take(60)}${if (a.text.length > 60) "…" else ""}", grey1, card, field, 40, 14f)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.END; x = 40; y = 36 }
        try { wm.addView(pill, lp); cornerPill = pill } catch (_: Exception) {}
        handler.postDelayed({ dropCornerPill() }, 30 * 60_000L)
        if (queue.isNotEmpty()) next()
    }

    private fun dropCornerPill() {
        cornerPill?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        cornerPill = null
    }

    // ---- audio ----------------------------------------------------------------------------------

    private fun playChime() {
        try {
            chime?.release()
            chime = MediaPlayer.create(context, R.raw.chime)?.apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setOnCompletionListener { it.release(); if (chime === it) chime = null }
                start()
            }
        } catch (e: Exception) { Log.w(TAG, "chime: $e") }
    }

    private fun requestFocus() {
        try {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build()
            focusRequest = req
            val r = am.requestAudioFocus(req)
            Log.i(TAG, "audio focus request: $r")
        } catch (e: Exception) { Log.w(TAG, "focus: $e") }
    }

    private fun abandonFocus() {
        focusRequest?.let { try { am.abandonAudioFocusRequest(it) } catch (_: Exception) {} }
        focusRequest = null
    }

    // The message must be heard: muted or under 20 goes to 20 for the message and back after.
    private fun raiseVolumeIfNeeded() {
        try {
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val muted = am.isStreamMute(AudioManager.STREAM_MUSIC)
            if (muted || cur < 20) {
                restoreVolume = if (muted) -1 else cur
                if (muted) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 20.coerceAtMost(am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0)
                Log.i(TAG, "volume raised from ${if (muted) "muted" else cur} to 20")
            }
        } catch (e: Exception) { Log.w(TAG, "volume: $e") }
    }

    private fun restoreVolume() {
        val prev = restoreVolume ?: return
        restoreVolume = null
        try {
            if (prev < 0) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            else am.setStreamVolume(AudioManager.STREAM_MUSIC, prev, 0)
            Log.i(TAG, "volume restored to ${if (prev < 0) "muted" else prev}")
        } catch (e: Exception) { Log.w(TAG, "restore: $e") }
    }

    private fun stopAudio() {
        abandonFocus()
        voice?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        voice = null
        chime?.let { try { it.release() } catch (_: Exception) {} }
        chime = null
    }

    private fun receipt(id: String, what: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val conn = (URL(MiniApi.url("/announce/$id/$what")).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 4000; readTimeout = 6000; requestMethod = "POST"; doOutput = true
                        setRequestProperty("Authorization", "Bearer ${com.moonkiln.tv.BuildConfig.TODAY_TOKEN}")
                        setRequestProperty("Content-Type", "application/json")
                    }
                    try { conn.outputStream.use { it.write("{}".toByteArray()) }; conn.responseCode } finally { conn.disconnect() }
                } catch (e: Exception) { Log.w(TAG, "receipt $what: $e") }
            }
        }
    }

    // ---- views ----------------------------------------------------------------------------------

    private fun build(a: Announcement) {
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        val r = FrameLayout(context)
        root = r
        scrim = View(context).apply { setBackgroundColor(ink); alpha = 0f }
        r.addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val c = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(96, 72, 96, 72)
        }
        content = c
        val hanken = font(R.font.hankengrotesk_wght, 700)
        val grand = font(R.font.grandstander_wght, 800)

        // From line
        val fromRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val icon = android.widget.ImageView(context).apply {
            setImageResource(R.drawable.ic_speaker)
            // The overlay is plain views in pixels, so pin the glyph to 20 px inside the 40 px circle.
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(10, 10, 10, 10)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(card); setStroke(1, field) }
            layoutParams = LinearLayout.LayoutParams(40, 40)
        }
        fromRow.addView(icon)
        fromRow.addView(TextView(context).apply {
            text = "FROM ${a.from.uppercase()}  ·  ${a.time}"
            setTextColor(grey1); typeface = hanken; setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f); letterSpacing = 0.12f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = 12 }
        })
        queuedPill = pill("${queue.size} more after this", grey1, card, field, 26, 12.5f).apply {
            visibility = if (queue.isEmpty()) View.GONE else View.VISIBLE
            (layoutParams as? LinearLayout.LayoutParams)?.marginStart = 8
        }
        fromRow.addView(queuedPill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 26).apply { marginStart = 8 })
        c.addView(fromRow)

        // Message
        val size = if (a.text.length > 70) 52f else 68f
        message = TextView(context).apply {
            typeface = grand
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            setLineSpacing(0f, 1.12f)
            letterSpacing = -0.015f
            maxWidth = 1040
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 28 }
        }
        c.addView(message)
        renderWords()

        // Footer: speaking bars, then the pills
        val f = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; alpha = 0f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 44).apply { topMargin = 28 }
        }
        foot = f
        val sp = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        val bars = Bars(context, green)
        sp.addView(bars, LinearLayout.LayoutParams(28, 22))
        sp.addView(TextView(context).apply {
            text = "Saying it out loud"; setTextColor(grey1); typeface = font(R.font.hankengrotesk_wght, 600); setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = 12 }
        })
        speakingRow = sp
        f.addView(sp)
        val p = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        gotIt = pill("Got it", ink, cream, cream, 44, 17f)
        again = pill("↺  Say it again", grey1, card, field, 44, 16f)
        p.addView(gotIt, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 44))
        p.addView(again, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 44).apply { marginStart = 12 })
        p.addView(TextView(context).apply {
            text = "OK to dismiss. ${a.from} sees that it was seen."; setTextColor(grey4); typeface = font(R.font.hankengrotesk_wght, 400); setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = 22 }
        })
        pills = p
        f.addView(p)
        c.addView(f)
        r.addView(c, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Every key stops here. OK dismisses (or replays when Say it again is picked); left and right pick.
        r.isFocusable = true
        r.isFocusableInTouchMode = true
        r.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) Log.i(TAG, "key $keyCode device=${event.deviceId} source=${event.source} phase=$phase")
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                        if (phase == "waiting") { if (focusOnAgain) sayAgain() else dismiss() }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (phase == "waiting") { focusOnAgain = !focusOnAgain; renderPills() }
                    KeyEvent.KEYCODE_BACK -> if (phase == "waiting") dismiss()
                }
            }
            true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        )
        try {
            wm.addView(r, lp)
            r.requestFocus()
        } catch (e: Exception) { Log.w(TAG, "addView: $e") }
    }

    private fun renderWords() {
        val tv = message ?: return
        val sb = SpannableStringBuilder()
        words.forEachIndexed { i, (body, punct) ->
            val start = sb.length
            sb.append(body)
            sb.setSpan(ForegroundColorSpan(if (i < spoken || phase == "waiting") cream else dim), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (punct.isNotEmpty()) {
                // Only the closing mark is green; a comma mid-sentence lights like its word.
                val ps = sb.length
                sb.append(punct)
                val wordColour = if (i < spoken || phase == "waiting") cream else dim
                sb.setSpan(ForegroundColorSpan(if (i == words.size - 1) green else wordColour), ps, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (i < words.size - 1) sb.append(' ')
        }
        tv.text = sb
    }

    private fun renderPills() {
        gotIt?.background = pillBg(if (focusOnAgain) card else cream, if (focusOnAgain) field else cream, ring = !focusOnAgain)
        gotIt?.setTextColor(if (focusOnAgain) grey1 else ink)
        again?.background = pillBg(if (focusOnAgain) cream else card, if (focusOnAgain) cream else field, ring = focusOnAgain)
        again?.setTextColor(if (focusOnAgain) ink else grey1)
    }

    private fun pillBg(fill: Int, stroke: Int, ring: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 24f
        setColor(fill)
        setStroke(if (ring) 3 else 1, if (ring) sun else stroke)
    }

    private fun pill(text: String, fg: Int, bg: Int, stroke: Int, height: Int, sizePx: Float): TextView = TextView(context).apply {
        this.text = text
        setTextColor(fg)
        typeface = font(R.font.hankengrotesk_wght, 700)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        gravity = Gravity.CENTER
        setPadding(if (height > 30) 22 else 10, 0, if (height > 30) 22 else 10, 0)
        background = pillBg(bg, stroke, ring = false)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, height)
    }

    // Four bars that breathe while the voice plays.
    private class Bars(context: Context, private val colour: Int) : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = colour }
        private val t0 = System.currentTimeMillis()
        override fun onDraw(canvas: android.graphics.Canvas) {
            val t = (System.currentTimeMillis() - t0) / 900.0
            val w = 4f; val gap = 4f
            for (i in 0 until 4) {
                val ph = t * 2 * Math.PI - listOf(0.0, 0.33, 0.66, 0.16)[i] * 2 * Math.PI
                val h = height * (0.35f + 0.65f * ((Math.sin(ph) + 1) / 2).toFloat())
                val x = i * (w + gap)
                canvas.drawRoundRect(x, height - h, x + w, height.toFloat(), 2f, 2f, paint)
            }
            if (Motion.scale > 0f) postInvalidateOnAnimation()
        }
    }
}
