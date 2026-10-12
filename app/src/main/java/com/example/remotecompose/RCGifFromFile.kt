package com.example.remotecompose

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.remote.player.core.platform.BitmapLoader
import androidx.compose.remote.player.view.RemoteComposePlayer
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.URL

// ─── GIF demo: app pushes frames into USER: slots ────────────────────────────
//
// The document (gif_slot.rc) declares one named bitmap slot per GIF and paints whatever
// is in each. We download every GIF, and on a timer draw each one's frame for the current
// time into a bitmap and push it with setUserLocalBitmap. The player just repaints slots
// whose contents keep changing — that IS the animation.

/**
 * Slot names to push under. The document registers `USER:gif.frame.N#rc-size=…#rc-scale=…`
 * (remoteNamedUrlImageSlot appends the fragments) and the player's name map is an
 * exact-string match — but setUserLocalBitmap prepends `USER:` ITSELF, so we pass the
 * name WITHOUT the prefix, same as the profile demo's `user.data.firstName` pushes.
 * Keep in sync with GifSlotScreen.kt.
 */
private const val GIF_PX = 200
private fun slotName(index: Int) = "gif.frame.$index#rc-size=${GIF_PX}x$GIF_PX#rc-scale=fit,extend"

/** Must match GIF_SLOT_COUNT in GifSlotScreen.kt — one URL per declared slot. */
private const val GIF_COUNT = 20

/**
 * Giphy GIFs (Giphy serves to a plain client — Wikimedia 403s). Mixed aspect ratios on
 * purpose: each frame is letterboxed into the GIF_PX square before pushing. Eight distinct
 * assets cycled to fill GIF_COUNT slots — each slot still gets its own Movie and buffers,
 * so the load is the same as 20 distinct GIFs.
 */
private val GIF_IDS = listOf(
    "3o7aCTfyhYawdOXcFW", // 200×200
    "26ufdipQqU2lhNA4g", // 200×200
    "l0MYt5jPR6QX5pnqM", // 200×112
    "xT9IgzoKnwFNmISR8I", // 200×179
    "13HgwGsXF0aiGY", // 200×112
    "3oEjI6SIIHBdRxXI40", // 200×200
    "26tn33aiTi1jkl6H6", // 200×112
    "l41lFw057lAJQMwg0", // 200×113
)
private val GIF_URLS = List(GIF_COUNT) { i ->
    "https://media.giphy.com/media/${GIF_IDS[i % GIF_IDS.size]}/200w.gif"
}

/** How often we push a frame. 40 ms ≈ 25 fps, plenty for a GIF. */
private const val FRAME_MS = 40L

/**
 * The document bakes each USER: slot's own sized name as a placeholder "url" (addBitmapUrl
 * dedupes ids by url string), so the player asks the loader for it. The default Android
 * loader tries `URI(name)` and chokes on the two `#` fragments. Production's loader ignores
 * `USER:` pulls — the slot is filled by setUserLocalBitmap, never fetched — so do the same.
 */
internal object UserSlotIgnoringLoader : BitmapLoader {
    private val EMPTY = ByteArray(0)
    override fun loadBitmap(name: String): InputStream {
        if (!name.startsWith("USER:")) Log.w("RC_GIF", "unexpected non-slot image pull: $name")
        return ByteArrayInputStream(EMPTY)
    }
}

/** One decoded GIF plus the two bitmaps we alternate drawing its frames into. */
private class GifTrack(val movie: Movie) {
    val duration = movie.duration().takeIf { it > 0 } ?: 1000
    // Two buffers so we never draw into the bitmap the player is currently painting.
    val buffers = Array(2) { Bitmap.createBitmap(GIF_PX, GIF_PX, Bitmap.Config.ARGB_8888) }
    // Letterbox the GIF's real frame into the square slot.
    val scale = minOf(GIF_PX.toFloat() / movie.width(), GIF_PX.toFloat() / movie.height())
    val dx = (GIF_PX - movie.width() * scale) / 2f
    val dy = (GIF_PX - movie.height() * scale) / 2f

    fun frameAt(elapsedMs: Long, buffer: Int): Bitmap {
        val bmp = buffers[buffer]
        bmp.eraseColor(0)
        movie.setTime((elapsedMs % duration).toInt())
        Canvas(bmp).apply {
            translate(dx, dy)
            scale(scale, scale)
            movie.draw(this, 0f, 0f)
        }
        return bmp
    }
}

@Composable
fun RCGifFromFile(assetName: String = "gif_slot.rc") {
    val context = LocalContext.current
    var document by remember { mutableStateOf<CoreDocument?>(null) }
    // One track per slot, filled in as downloads land. A plain array, not State: the
    // ticker reads it every tick and must not recompose the player (see playerRef).
    val tracks = remember { arrayOfNulls<GifTrack>(GIF_COUNT) }
    // The live player, captured once from `update`. NOT Compose state on purpose:
    // RemoteDocumentPlayer's own update lambda calls setDocument() every time it runs,
    // which resets the document — scroll position included. Driving frames through a
    // State would re-run it 25×/s and the list could never scroll. The ticker pushes
    // straight into this handle instead, then calls player.invalidate(): setLocalBitmap
    // only invalidates the document's MEASURE, it never schedules a view redraw — without
    // the explicit invalidate the slots only advance while something else (a scroll)
    // repaints.
    val playerRef = remember { arrayOfNulls<RemoteComposePlayer>(1) }

    // 1. Load the document from assets.
    LaunchedEffect(assetName) {
        document = withContext(Dispatchers.IO) {
            val bytes = context.assets.open(assetName).readBytes()
            CoreDocument().also {
                it.initFromBuffer(RemoteComposeBuffer.fromInputStream(bytes.inputStream()))
            }
        }
    }

    // 2. Download each DISTINCT GIF once (8 fetches, not 40 — the slots share assets), and
    //    give every slot using it its own Movie so the ticker can drive them independently.
    //    Slots fill in as their download lands; nothing waits for the slowest fetch.
    //    android.graphics.Movie is deprecated but is the simplest frame-accurate GIF decoder
    //    with no extra dependency: setTime() + draw().
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            GIF_URLS.withIndex().groupBy({ it.value }, { it.index }).map { (url, slots) ->
                async {
                    val bytes = runCatching { URL(url).readBytes() }
                        .getOrElse { Log.e("RC_GIF", "gif failed: $url", it); return@async }
                    slots.forEach { i ->
                        tracks[i] = Movie.decodeByteArray(bytes, 0, bytes.size)?.let(::GifTrack)
                    }
                    Log.d("RC_GIF", "$url (${bytes.size} B) → slots ${slots.map { it + 1 }}")
                }
            }.awaitAll()
        }
    }

    // 3. Ticker: every FRAME_MS, render each loaded GIF's frame for the current time.
    LaunchedEffect(document) {
        val list = tracks
        document ?: return@LaunchedEffect
        val start = System.currentTimeMillis()
        var tick = 0
        while (isActive) {
            val elapsed = System.currentTimeMillis() - start
            val buffer = tick++ % 2
            playerRef[0]?.let { player ->
                list.forEachIndexed { i, track ->
                    track?.let {
                        player.stateUpdater.setUserLocalBitmap(slotName(i + 1), it.frameAt(elapsed, buffer))
                    }
                }
                player.invalidate()
            }
            delay(FRAME_MS)
        }
    }

    document?.let { doc ->
        RemoteDocumentPlayer(
            // Binaries paint edge-to-edge and bake no insets; the host keeps the header
            // out from under the status bar.
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            document = doc,
            documentWidth = doc.width,
            documentHeight = doc.height,
            bitmapLoader = UserSlotIgnoringLoader,
            // Runs after setDocument, when the USER: slots exist. Only captures the handle;
            // the ticker above does the pushing.
            update = { player -> playerRef[0] = player },
            onNamedAction = ::handleNamedAction,
        )
    }
}
