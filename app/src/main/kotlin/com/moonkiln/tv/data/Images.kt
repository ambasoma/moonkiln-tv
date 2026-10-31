package com.moonkiln.tv.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.moonkiln.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

// A deliberately small image loader: no third party library, every bitmap decoded at the size it is
// drawn, a 2 MB memory cache and the files kept in the app cache folder for the next time.
object Images {
    private val memory = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun load(context: Context, url: String, sizePx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$url@$sizePx"
        memory.get(key)?.let { return@withContext it }
        val file = cacheFile(context, url)
        if (!file.exists() || file.length() == 0L) {
            if (!download(url, file)) return@withContext null
        }
        val bmp = decode(file, sizePx) ?: run { file.delete(); null }
        if (bmp != null) memory.put(key, bmp)
        bmp
    }

    suspend fun loadAsset(context: Context, name: String, sizePx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "asset:$name@$sizePx"
        memory.get(key)?.let { return@withContext it }
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(name).use { BitmapFactory.decodeStream(it, null, bounds) }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, sizePx)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bmp = context.assets.open(name).use { BitmapFactory.decodeStream(it, null, opts) }
            if (bmp != null) memory.put(key, bmp)
            bmp
        } catch (_: Exception) { null }
    }

    fun evictAssets() {
        memory.snapshot().keys.filter { it.startsWith("asset:") }.forEach { memory.remove(it) }
    }

    private fun download(url: String, into: File): Boolean {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 15000
                if (url.startsWith(BuildConfig.MINI_BASE)) setRequestProperty("Authorization", "Bearer ${BuildConfig.TODAY_TOKEN}")
            }
            try {
                if (conn.responseCode != 200) return false
                val tmp = File(into.path + ".part")
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(into)
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) { false }
    }

    private fun decode(file: File, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, sizePx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.path, opts)
    }

    private fun sampleSize(w: Int, h: Int, target: Int): Int {
        var s = 1
        while (w / (s * 2) >= target && h / (s * 2) >= target) s *= 2
        return s
    }

    private fun cacheFile(context: Context, url: String): File {
        val dir = File(context.cacheDir, "img").apply { mkdirs() }
        val md = MessageDigest.getInstance("MD5").digest(url.toByteArray())
        return File(dir, md.joinToString("") { "%02x".format(it) })
    }
}

@Composable
fun rememberRemoteImage(context: Context, url: String?, sizePx: Int): State<ImageBitmap?> =
    produceState<ImageBitmap?>(initialValue = null, url, sizePx) {
        value = if (url == null) null else Images.load(context, url, sizePx)?.asImageBitmap()
    }

@Composable
fun rememberAssetImage(context: Context, name: String?, sizePx: Int): State<ImageBitmap?> =
    produceState<ImageBitmap?>(initialValue = null, name, sizePx) {
        value = if (name == null) null else Images.loadAsset(context, name, sizePx)?.asImageBitmap()
    }
