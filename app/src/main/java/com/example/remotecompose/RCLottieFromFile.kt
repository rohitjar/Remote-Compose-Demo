package com.example.remotecompose

import android.graphics.Bitmap
import android.graphics.Canvas
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
import androidx.compose.remote.player.view.RemoteComposePlayer
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.URL

// ─── Lottie demo: app pushes rasterised frames into USER: slots ──────────────
//
// Same picture-frame model as RCGifFromFile — the document (lottie_slot.rc) declares one
// named bitmap slot per cell and paints whatever is in it. The only difference is the
// flipbook: instead of a GIF's pre-drawn pages (Movie), we hold a LottieDrawable and ask it
// to DRAW the vector animation at a given progress onto our bitmap. Vector in, so the
// frame is crisp at any slot size — no letterbox blur.

/**
 * Slot names to push under. The document registers `USER:lottie.frame.N#rc-size=…#rc-scale=…`
 * and the player's name map is an exact-string match — but setUserLocalBitmap prepends
 * `USER:` ITSELF, so we pass the name WITHOUT the prefix. Keep in sync with GifSlotScreen.kt.
 */
private const val SLOT_PX = 400 // full-width cells; vector source, so we can afford the pixels
private fun slotName(index: Int) = "lottie.frame.$index#rc-size=${SLOT_PX}x$SLOT_PX#rc-scale=fit,extend"

/** Must match LOTTIE_SLOT_COUNT in GifSlotScreen.kt — one animation per declared slot. */
private const val LOTTIE_COUNT = 5

/**
 * Public Lottie JSONs from the lottie-web / lottie-android repos, chosen because they have
 * NO image assets (a Lottie that references PNGs needs an image loader we don't set up)
 * and are visibly in motion the whole loop — a subtle icon morph reads as a still picture.
 * Mixed aspect ratios on purpose — each frame is letterboxed into the SLOT_PX square.
 */
private val LOTTIE_URLS_DISTINCT = listOf(
    "https://raw.githubusercontent.com/airbnb/lottie-web/master/demo/gatin/data.json", // 800×800, 3.2s
    "https://raw.githubusercontent.com/airbnb/lottie-android/master/sample/src/main/assets/AndroidWave.json", // 400×400, 2s wave
    "https://raw.githubusercontent.com/airbnb/lottie-android/master/sample/src/main/assets/Lottie%20Logo%201.json", // 375×667, 6s, 48 layers
    "https://raw.githubusercontent.com/airbnb/lottie-android/master/sample/src/main/assets/Lottie%20Logo%202.json", // 281×500, 12.6s, 74 layers
    "https://raw.githubusercontent.com/airbnb/lottie-web/master/demo/adrock/data.json", // 690×913, 13s character
)
private val LOTTIE_URLS = List(LOTTIE_COUNT) { i -> LOTTIE_URLS_DISTINCT[i % LOTTIE_URLS_DISTINCT.size] }

/** How often we push a frame. 40 ms ≈ 25 fps. */
private const val FRAME_MS = 66L

// The BitmapLoader is shared with RCGifFromFile (UserSlotIgnoringLoader): USER: slots are
// filled by push, never fetched.

/** One Lottie animation plus the two bitmaps we alternate drawing its frames into. */
private class LottieTrack(composition: LottieComposition) {
    private val drawable = LottieDrawable().apply {
        this.composition = composition
        // Draw at the animation's own size; we scale the canvas to letterbox into the slot.
        setBounds(0, 0, composition.bounds.width(), composition.bounds.height())
    }
    val durationMs = composition.duration.toLong().coerceAtLeast(1L)
    // Two buffers so we never draw into the bitmap the player is currently painting.
    val buffers = Array(2) { Bitmap.createBitmap(SLOT_PX, SLOT_PX, Bitmap.Config.ARGB_8888) }
    private val w = composition.bounds.width().toFloat()
    private val h = composition.bounds.height().toFloat()
    private val scale = minOf(SLOT_PX / w, SLOT_PX / h)
    private val dx = (SLOT_PX - w * scale) / 2f
    private val dy = (SLOT_PX - h * scale) / 2f

    fun frameAt(elapsedMs: Long, buffer: Int): Bitmap {
        val bmp = buffers[buffer]
        bmp.eraseColor(0)
        drawable.progress = (elapsedMs % durationMs).toFloat() / durationMs
        Canvas(bmp).apply {
            translate(dx, dy)
            scale(scale, scale)
            drawable.draw(this)
        }
        return bmp
    }
}

@Composable
fun RCLottieFromFile(assetName: String = "lottie_slot.rc") {
    val context = LocalContext.current
    var document by remember { mutableStateOf<CoreDocument?>(null) }
    // One track per slot, filled in as downloads land. Plain array, not State — see playerRef.
    val tracks = remember { arrayOfNulls<LottieTrack>(LOTTIE_COUNT) }
    // The live player, captured once from `update`. NOT Compose state on purpose:
    // RemoteDocumentPlayer's own update lambda calls setDocument() every time it runs,
    // which resets the document — scroll position included. The ticker pushes straight
    // into this handle, then calls player.invalidate(): setLocalBitmap only dirties the
    // document's measure and never schedules a view redraw on its own.
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

    // 2. Download each DISTINCT JSON once and parse it into a LottieComposition. A
    //    composition is immutable and shareable, so every slot using the same animation
    //    gets its own drawable over the same parsed data. Slots fill in as downloads land.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            LOTTIE_URLS.withIndex().groupBy({ it.value }, { it.index }).map { (url, slots) ->
                async {
                    val json = runCatching { URL(url).readText() }
                        .getOrElse { Log.e("RC_LOTTIE", "download failed: $url", it); return@async }
                    val result = LottieCompositionFactory.fromJsonStringSync(json, url)
                    val composition = result.value ?: run {
                        Log.e("RC_LOTTIE", "parse failed: $url", result.exception); return@async
                    }
                    slots.forEach { i -> tracks[i] = LottieTrack(composition) }
                    Log.d(
                        "RC_LOTTIE",
                        "$url (${json.length} chars) ${composition.bounds.width()}x${composition.bounds.height()} " +
                            "${composition.duration.toInt()}ms → slots ${slots.map { it + 1 }}",
                    )
                }
            }.awaitAll()
        }
    }

    // 3. Ticker: every FRAME_MS, rasterise each loaded animation at the current time.
    LaunchedEffect(document) {
        document ?: return@LaunchedEffect
        val start = System.currentTimeMillis()
        var tick = 0
        while (isActive) {
            val elapsed = System.currentTimeMillis() - start
            val buffer = tick++ % 2
            playerRef[0]?.let { player ->
                tracks.forEachIndexed { i, track ->
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
