package com.moonkiln.tv.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.moonkiln.tv.tv.LIVE_TV_PACKAGE

// An installed app that shows on the row: the Leanback launcher activity of anything that is not us,
// not the hidden Live TV app, and not the Google launcher we are replacing.
data class LaunchableApp(val label: String, val component: ComponentName, val icon: ImageBitmap?)

private val HIDDEN_PACKAGES = setOf(
    "com.moonkiln.tv",
    LIVE_TV_PACKAGE,
    "com.google.android.tvlauncher",
    // The remote's firmware updater stays installed but has no place on the row.
    "com.airoha.ble.ota",
)

fun installedApps(context: Context, iconPx: Int): List<LaunchableApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
    val infos = try { pm.queryIntentActivities(intent, 0) } catch (_: Exception) { emptyList() }
    return infos
        .filter { it.activityInfo.packageName !in HIDDEN_PACKAGES }
        .map { ri ->
            val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { ri.activityInfo.packageName }
            val icon = try { toBitmap(ri.activityInfo.loadIcon(pm), iconPx) } catch (_: Exception) { null }
            LaunchableApp(prettify(label), ComponentName(ri.activityInfo.packageName, ri.activityInfo.name), icon)
        }
        .sortedBy { it.label.lowercase() }
}

private fun prettify(label: String): String = when (label) {
    "Google Play Store" -> "Play Store"
    "YouTube Music" -> "YT Music"
    "Google Play Games" -> "Play Games"
    "Google Play Movies & TV", "Play Movies & TV" -> "Movies"
    else -> label
}

private fun toBitmap(drawable: Drawable, px: Int): ImageBitmap {
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    drawable.setBounds(0, 0, px, px)
    drawable.draw(c)
    return bmp.asImageBitmap()
}

fun launch(context: Context, component: ComponentName): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        .setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    true
} catch (_: Exception) {
    try {
        val pm: PackageManager = context.packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(component.packageName) ?: pm.getLaunchIntentForPackage(component.packageName)
        if (i != null) { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } else false
    } catch (_: Exception) { false }
}

fun openSettings(context: Context): Boolean = try {
    context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: Exception) { false }
