package com.example.remotecompose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.Log
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.operations.NamedVariable
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware

/**
 * Demo data for the `USER:` image slots the profile binary declares (profile picture, KYC
 * status-tag icon).
 *
 * Slot names are DISCOVERED from the document — registered as `USER:<path>#rc-size=WxH#rc-scale=…`
 * — never hardcoded, so the `#rc-*` hints the server appends stay opaque here. Each bitmap is
 * conformed to the slot's DECLARED size, because the player paints a slot through a source rect
 * of exactly those dims (a smaller bitmap would land in the top-left corner of its box).
 */
object ProfileSlotBitmaps {
    private const val TAG = "RC_SLOTS"
    private const val USER_PREFIX = "USER:"
    private const val DEFAULT_SLOT_PX = 512
    const val PROFILE_PIC_PATH = "user.data.profilePicUrl"
    const val KYC_ICON_PATH = "kyc.data.statusTag.textData.textIcon"
    private const val PROFILE_PIC_URL = "https://i.pravatar.cc/512?img=47"

    /** Push key (registered name minus `USER:`) -> declared WxH, for every image slot. */
    fun imageSlots(document: CoreDocument): Map<String, Pair<Int, Int>> =
        (runCatching { document.getNamedVariables(NamedVariable.IMAGE_TYPE) }.getOrNull() ?: emptyArray())
            .filter { it.startsWith(USER_PREFIX) }
            .associate { full ->
                val key = full.removePrefix(USER_PREFIX)
                key to (parseRcSize(key) ?: (DEFAULT_SLOT_PX to DEFAULT_SLOT_PX))
            }

    /** `#rc-size=WxH` off a registered slot name; null when absent or malformed. */
    fun parseRcSize(name: String): Pair<Int, Int>? {
        val parts = name.substringAfter("#rc-size=", "").substringBefore("#").split("x")
        if (parts.size != 2) return null
        val width = parts[0].toIntOrNull() ?: return null
        val height = parts[1].toIntOrNull() ?: return null
        return if (width > 0 && height > 0) width to height else null
    }

    /** A real photo from the network, or a drawn avatar if that fails; always exactly [w]x[h]. */
    suspend fun profilePic(context: Context, w: Int, h: Int): Bitmap {
        val fetched = runCatching {
            val result = SingletonImageLoader.get(context).execute(
                ImageRequest.Builder(context).data(PROFILE_PIC_URL).allowHardware(false).build(),
            )
            ((result as? SuccessResult)?.image as? BitmapImage)?.bitmap
        }.onFailure { Log.w(TAG, "profile pic fetch failed, drawing a fallback", it) }.getOrNull()
        return fetched?.let { conform(it, w, h) } ?: drawAvatar(w, h)
    }

    /** Green "verified" tick — the shape a KYC status-tag icon has. */
    fun kycTick(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
        val canvas = Canvas(bmp)
        val radius = minOf(w, h) / 2f
        canvas.drawCircle(w / 2f, h / 2f, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1DB954.toInt() })
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = radius * 0.22f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val tick = Path().apply {
            moveTo(w * 0.28f, h * 0.52f)
            lineTo(w * 0.44f, h * 0.68f)
            lineTo(w * 0.73f, h * 0.36f)
        }
        canvas.drawPath(tick, stroke)
    }

    private fun drawAvatar(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF6C5CE7.toInt())
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = h * 0.5f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val baseline = h / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText("M", w / 2f, baseline, text)
    }

    /** Center-crop cover, then scale to exactly [w]x[h]. */
    private fun conform(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        val scale = maxOf(w / src.width.toFloat(), h / src.height.toFloat())
        val cropW = (w / scale).toInt().coerceIn(1, src.width)
        val cropH = (h / scale).toInt().coerceIn(1, src.height)
        val cropped = Bitmap.createBitmap(src, (src.width - cropW) / 2, (src.height - cropH) / 2, cropW, cropH)
        return Bitmap.createScaledBitmap(cropped, w, h, true)
    }
}
