package com.example.remotecompose.overlay

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import kotlinx.serialization.json.JsonPrimitive

private const val TAG = "RcMediaOverlay"

/**
 * Where a play-once hole is — jar `TabIntroState.onFrame` / `complete` in one value. Reported to
 * the host, which pushes `.time` / `.done` into the screen's binaries.
 */
@Immutable
data class OverlayPlayback(
    val timeSec: Float,
    /** Full length; 0 until the composition has loaded. */
    val durationSec: Float,
    val frameRate: Float,
    /** Finished, failed to load, or nothing to play (no URL). */
    val done: Boolean,
    /** Nothing to play: the hole's URL is blank or unresolvable (jar: no guiding Lottie at all). */
    val missing: Boolean = false,
    /** The hole's `.shownAt`: playback fraction at which the view counts (null = never reported). */
    val shownAt: Float? = null,
) {
    companion object {
        val Loading = OverlayPlayback(timeSec = 0f, durationSec = 0f, frameRate = 30f, done = false)
        val Failed = OverlayPlayback(timeSec = 0f, durationSec = 0f, frameRate = 30f, done = true)
        val Missing = OverlayPlayback(timeSec = 0f, durationSec = 0f, frameRate = 30f, done = true, missing = true)
    }
}

/**
 * Lottie floated over a document's `overlay.lottie.<id>` holes — port of jar's
 * `RcMediaOverlayLayer` (Lottie branch; the demo has no native video player). URL from `.url`
 * (literal or data path), rect from the laid-out hole, hidden while the document collapses it.
 * Compose it inside the player's box, above the player: hole rects are in its coordinates.
 *
 * `.loop` = 0 plays the Lottie once and reports its playback through [onPlayback] every frame,
 * like jar's `WithdrawalIntroLottie`; a hole whose URL does not resolve reports
 * [OverlayPlayback.Missing] at once, so a document waiting on it settles. While [hold] is true a
 * play-once Lottie loads but stays hidden at frame 0 — the host starts it when the screen is ready.
 */
@Composable
fun RcMediaOverlayLayer(
    handle: RcPlayerHandle,
    values: Map<String, JsonPrimitive>?,
    hold: Boolean = false,
    onPlayback: (OverlayKey, OverlayPlayback) -> Unit = { _, _ -> },
) {
    val keys = remember(handle.document) { OverlaySlots.keys(handle.document, OverlayKind.Lottie) }
    if (keys.isEmpty()) return
    val reader = remember(handle) { RcNamedValueReader(handle) }
    val rects by rememberOverlayRects(handle, keys)
    val report by rememberUpdatedState(onPlayback)

    Box(modifier = Modifier.fillMaxSize()) {
        for (k in keys) {
            key(k) { LottieHole(k, reader, rects[k], values, hold) { report(k, it) } }
        }
    }
}

/** One hole: the Lottie at its laid-out [rect], or a `Missing` report when its URL does not resolve. */
@Composable
private fun LottieHole(
    k: OverlayKey,
    reader: RcNamedValueReader,
    rect: OverlayRect?,
    values: Map<String, JsonPrimitive>?,
    hold: Boolean,
    onPlayback: (OverlayPlayback) -> Unit,
) {
    val url = OverlaySlots.resolveUrl(reader.readText(OverlaySlots.url(k)), values)
    val loop = (reader.readFloat(OverlaySlots.loop(k)) ?: 1f) >= 0.5f
    if (url == null) {
        if (!loop) LaunchedEffect(Unit) { onPlayback(OverlayPlayback.Missing) }
        return
    }
    if (rect == null || rect.collapsed) return
    val radius = reader.readFloat(OverlaySlots.radius(k)) ?: 0f
    val scale = OverlaySlots.contentScaleOf(reader.readText(OverlaySlots.contentScale(k)))
    val modifier = Modifier
        .offset { IntOffset(rect.xDp.dp.roundToPx(), rect.yDp.dp.roundToPx()) }
        .size(rect.wDp.dp, rect.hDp.dp)
        .clip(RoundedCornerShape(radius.dp))
    key(url) {
        if (loop) {
            LoopingLottie(url, scale, modifier)
        } else {
            val shownAt = reader.readFloat(OverlaySlots.shownAt(k))?.takeIf { it in 0f..1f }
            PlayOnceLottie(url, scale, hold, modifier) { onPlayback(it.copy(shownAt = shownAt)) }
        }
    }
}

@Composable
private fun LoopingLottie(url: String, scale: ContentScale, modifier: Modifier) {
    val composition by rememberLottieComposition(LottieCompositionSpec.Url(url))
    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations = LottieConstants.IterateForever,
    )
    Box(modifier = modifier) {
        LottieAnimation(
            composition = composition,
            progress = { progress },
            contentScale = scale,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * jar `WithdrawalIntroLottie`: plays once, reports playback every frame and completion (or failure).
 * [hold]: loaded but not shown or started yet (reports "loading").
 */
@Composable
private fun PlayOnceLottie(
    url: String,
    scale: ContentScale,
    hold: Boolean,
    modifier: Modifier,
    onPlayback: (OverlayPlayback) -> Unit,
) {
    val result = rememberLottieComposition(LottieCompositionSpec.Url(url))
    val composition by result
    val state = animateLottieCompositionAsState(composition = composition, isPlaying = !hold, iterations = 1)

    LaunchedEffect(Unit) { onPlayback(OverlayPlayback.Loading) }
    LaunchedEffect(result.isFailure) {
        if (result.isFailure) {
            Log.e(TAG, "lottie failed to load: $url", result.error)
            onPlayback(OverlayPlayback.Failed)
        }
    }
    LaunchedEffect(composition, hold) {
        val loaded = composition ?: return@LaunchedEffect
        if (hold) return@LaunchedEffect
        val durationSec = loaded.duration / 1000f
        snapshotFlow { state.progress }.collect { progress ->
            onPlayback(
                OverlayPlayback(
                    timeSec = progress * durationSec,
                    durationSec = durationSec,
                    frameRate = loaded.frameRate,
                    done = progress >= 1f,
                ),
            )
        }
    }

    if (hold) return
    Box(modifier = modifier) {
        LottieAnimation(
            composition = composition,
            progress = { state.progress },
            contentScale = scale,
            safeMode = true,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
