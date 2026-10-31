package com.moonkiln.tv.toast

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.moonkiln.tv.R
import com.moonkiln.tv.data.Images
import com.moonkiln.tv.data.LatestSale
import com.moonkiln.tv.data.rupees
import com.moonkiln.tv.ui.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "MkToast"

// The new order toast. Plain views in an overlay window so it shows over the PS5 picture too: 550 ms in
// from the right, five seconds on screen, 350 ms fade out. Needs SYSTEM_ALERT_WINDOW, granted through adb.
class OrderToast(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val ease = PathInterpolator(0.2f, 0.7f, 0.3f, 1f)
    private var current: View? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    fun show(sale: LatestSale) {
        if (!canShow()) { Log.w(TAG, "overlay permission missing, toast skipped"); return }
        dismiss(immediate = true)
        val view = build(sale)
        val lp = WindowManager.LayoutParams(
            px(420), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = px(64)
            y = px(36)
        }
        try {
            wm.addView(view, lp)
        } catch (e: Exception) {
            Log.w(TAG, "addView: $e"); return
        }
        current = view
        view.alpha = 0f
        view.translationX = px(28).toFloat()
        view.animate().alpha(1f).translationX(0f).setDuration(Motion.ms(Motion.SLOW).toLong()).setInterpolator(ease).start()
        handler.postDelayed({ dismiss(immediate = false) }, 5000L + Motion.ms(Motion.SLOW))
        sale.imageUrl?.let { url ->
            scope.launch {
                val bmp: Bitmap? = Images.load(context, url, px(64))
                if (bmp != null && current === view) view.findViewById<ImageView>(R.id.mk_toast_image).setImageBitmap(bmp)
            }
        }
    }

    fun dismiss(immediate: Boolean) {
        val v = current ?: return
        current = null
        handler.removeCallbacksAndMessages(null)
        if (immediate || Motion.ms(1) == 0) {
            remove(v)
        } else {
            v.animate().alpha(0f).setDuration(Motion.ms(Motion.MEDIUM).toLong()).setInterpolator(ease).withEndAction { remove(v) }.start()
        }
    }

    private fun remove(v: View) {
        try { wm.removeView(v) } catch (_: Exception) {}
    }

    // Pixel sizes straight from the design; the TV is 1280x720 and the overlay is sized in real pixels.
    private fun px(v: Int): Int = v

    private fun build(sale: LatestSale): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(18), px(16), px(18), px(16))
            background = GradientDrawable().apply {
                cornerRadius = px(20).toFloat()
                setColor(Color.parseColor("#26231F"))
                setStroke(px(1), Color.parseColor("#3A362F"))
            }
            elevation = px(10).toFloat()
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, px(20).toFloat()) }
            }
            clipToOutline = true
        }
        val image = ImageView(context).apply {
            id = R.id.mk_toast_image
            layoutParams = LinearLayout.LayoutParams(px(64), px(64))
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = px(16).toFloat(); setColor(Color.parseColor("#2E2B25")) }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, px(16).toFloat()) }
            }
        }
        root.addView(image)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(16) }
        }
        val hanken = try { context.resources.getFont(R.font.hankengrotesk_wght) } catch (_: Exception) { Typeface.SANS_SERIF }
        val grand = try { context.resources.getFont(R.font.grandstander_wght) } catch (_: Exception) { Typeface.SANS_SERIF }
        column.addView(text("NEW ORDER", 12.5f, Color.parseColor("#8FB544"), Typeface.create(hanken, 700, false), letter = 0.12f))
        column.addView(text("${sale.title} · ${rupees(sale.amount)}", 21f, Color.parseColor("#F4EFE2"), Typeface.create(grand, 700, false), topGap = px(4)))
        val where = sale.city?.let { "to $it · just now" } ?: "just now"
        column.addView(text(where, 15f, Color.parseColor("#9C9482"), Typeface.create(hanken, 400, false), topGap = px(4)))
        root.addView(column)
        return root
    }

    private fun text(s: String, sizePx: Float, color: Int, face: Typeface, letter: Float = 0f, topGap: Int = 0): TextView =
        TextView(context).apply {
            text = s
            setTextColor(color)
            typeface = face
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
            letterSpacing = letter
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = topGap }
        }
}
