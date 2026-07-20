@file:Suppress("RestrictedApi")

package com.example.remotecompose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import androidx.compose.remote.player.core.platform.BitmapLoader
import coil3.BitmapImage
import coil3.Image
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Non-blocking [BitmapLoader] backed by Coil — verbatim from the production HomeAssistant
 * RemoteCompose app (yschimke/homeassistant-remotecompose, rc-image-coil), with an added
 * [onImageReady] hook so the Compose host can repaint when an async image arrives.
 *
 * **Never blocks.** [loadBitmap] runs on the player's decode thread (sometimes the main thread),
 * so it must be a hash-map lookup, not a coroutine dispatch. We bypass [ImageLoader.execute]
 * (which spins up an async job even on a memory hit) and read directly from
 * [ImageLoader.memoryCache] with a key we control.
 *
 * On a miss we enqueue a full async fetch ([ImageLoader.enqueue]) with the same explicit
 * memory-cache key and return an EMPTY stream for this pass; the player keeps the placeholder.
 * When the warm-up completes we call [onImageReady] so the next render reads the now-cached image.
 *
 * **Decode-bound clamp ([maxImageDimensionPx]).** The player rejects a fetched bitmap whose decoded
 * size is larger than the slot the document declared via `addBitmapUrl(url, w, h)`
 * (`RemoteBitmapDecoder.checkBounds` throws "dimensions don't match …"). The loader only gets the
 * URL, not the slot size, so it downscales every fetched bitmap (aspect-preserving) to fit within
 * [maxImageDimensionPx] × [maxImageDimensionPx]. Keep this in sync with the slot size the server
 * declares.
 */
open class CoilBitmapLoader(
    private val context: Context,
    private val imageLoader: ImageLoader = SingletonImageLoader.get(context),
    private val baseUrl: String? = null,
    private val compressFormat: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
    private val compressQuality: Int = 100,
    private val maxImageDimensionPx: Int = DEFAULT_MAX_IMAGE_DIMENSION_PX,
    private val onImageReady: () -> Unit = {},
    private val configure: ImageRequest.Builder.() -> Unit = {},
) : BitmapLoader {

    private val baseUrlPrefix: String? = baseUrl?.trimEnd('/')
    private val logState = ConcurrentHashMap<String, LogState>()

    final override fun loadBitmap(name: String): InputStream {
        val resolved = resolveName(name)
        val slot = parseSlotSize(resolved)
        val key = memoryCacheKeyFor(resolved)
        val image = imageLoader.memoryCache?.get(key)?.image
        val bytes = image?.let { encodeImage(it, slot) }
        if (bytes != null) {
            logTransition(resolved, LogState.Hit, bytes.size, name)
            return ByteArrayInputStream(bytes)
        }
        logTransition(resolved, LogState.Miss, 0, name)
        // Fetch without the fragment (it's a slot-size annotation, not part of the resource),
        // but cache under the full name so distinct slot sizes don't collide.
        warmUpAsync(resolved.substringBefore('#'), key)
        return ByteArrayInputStream(EMPTY)
    }

    /**
     * Slot dims the document declared for this URL, carried as a `#rc-size=WxH` fragment by the
     * server's UrlBitmapSupport. The player requires the delivered bitmap to match the declared
     * slot exactly (larger throws in RemoteBitmapDecoder.checkBounds, smaller renders shrunken),
     * so when the fragment is present the encoded bitmap is letterboxed to exactly these dims.
     */
    private fun parseSlotSize(name: String): Pair<Int, Int>? {
        val match = SLOT_FRAGMENT.find(name) ?: return null
        val (w, h) = match.destructured
        return w.toInt() to h.toInt()
    }

    private fun logTransition(resolved: String, state: LogState, bytes: Int, originalName: String) {
        val prev = logState.put(resolved, state)
        if (prev == state) return
        when (state) {
            LogState.Hit ->
                Log.d(TAG, "loadBitmap HIT name=$originalName resolved=$resolved bytes=$bytes")
            LogState.Miss ->
                Log.d(TAG, "loadBitmap MISS name=$originalName resolved=$resolved (enqueuing warm-up)")
        }
    }

    private fun resolveName(name: String): String {
        val prefix = baseUrlPrefix ?: return name
        if (!name.startsWith('/')) return name
        return prefix + name
    }

    protected open fun memoryCacheKeyFor(name: String): MemoryCache.Key = MemoryCache.Key(name)

    protected open fun encodeImage(image: Image, slot: Pair<Int, Int>? = null): ByteArray? {
        if (image !is BitmapImage) return null
        val source = image.bitmap
        val software =
            if (source.config == Bitmap.Config.HARDWARE) {
                source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            } else {
                source
            }
        val encodable = if (slot != null) letterboxToExact(software, slot.first, slot.second)
                        else downscaleToFit(software)
        val out = ByteArrayOutputStream(encodable.allocationByteCount.coerceAtLeast(1024))
        encodable.compress(compressFormat, compressQuality, out)
        if (encodable !== software) encodable.recycle()
        if (software !== source) software.recycle()
        return out.toByteArray()
    }

    /**
     * Scales (aspect-preserving) and centers the bitmap onto an exactly [w]×[h] transparent
     * canvas, so the delivered size always equals the slot the document declared.
     */
    private fun letterboxToExact(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val scale = minOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val dw = src.width * scale
        val dh = src.height * scale
        val left = (w - dw) / 2f
        val top = (h - dh) / 2f
        Canvas(out).drawBitmap(
            src,
            null,
            RectF(left, top, left + dw, top + dh),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        return out
    }

    private fun downscaleToFit(bitmap: Bitmap): Bitmap {
        val cap = maxImageDimensionPx
        if (cap <= 0) return bitmap
        val w = bitmap.width
        val h = bitmap.height
        if (w <= cap && h <= cap) return bitmap
        val scale = cap.toFloat() / maxOf(w, h)
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, /* filter= */ true)
    }

    protected open fun warmUpAsync(name: String, key: MemoryCache.Key) {
        val request =
            ImageRequest.Builder(context)
                .data(name)
                .memoryCacheKey(key)
                .allowHardware(false)
                .listener(
                    onSuccess = { _, result: SuccessResult ->
                        val img = result.image
                        val dims =
                            if (img is BitmapImage) "${img.bitmap.width}x${img.bitmap.height}"
                            else img::class.java.simpleName
                        Log.d(TAG, "warmUp SUCCESS url=$name dataSource=${result.dataSource} image=$dims")
                        onImageReady()
                    },
                    onError = { _, result: ErrorResult ->
                        Log.w(
                            TAG,
                            "warmUp ERROR url=$name " +
                                "throwable=${result.throwable::class.java.simpleName}: " +
                                "${result.throwable.message}",
                        )
                    },
                )
                .apply(configure)
                .build()
        Log.d(TAG, "warmUp enqueue url=$name")
        imageLoader.enqueue(request)
    }

    private enum class LogState { Hit, Miss }

    companion object {
        private const val TAG = "CoilBitmapLoader"
        private val EMPTY = ByteArray(0)
        const val DEFAULT_MAX_IMAGE_DIMENSION_PX = 512
        private val SLOT_FRAGMENT = Regex("#rc-size=(\\d+)x(\\d+)")
    }
}
