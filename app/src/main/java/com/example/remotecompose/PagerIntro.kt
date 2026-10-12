package com.example.remotecompose

import android.content.Context
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.operations.NamedVariable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.LottieCompositionFactory
import com.example.remotecompose.overlay.OverlayKey
import com.example.remotecompose.overlay.OverlayKind
import com.example.remotecompose.overlay.OverlayPlayback
import com.example.remotecompose.overlay.OverlaySlots
import com.example.remotecompose.overlay.USER_PREFIX
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive

private const val TAG = "RC_INTRO"

/**
 * The paging host's intro — jar `WithdrawalOptionScreenV2`'s `TabIntroState` plus its page reveal,
 * for a screen whose manifest has a `pin = overlay` section (the Withdraw guiding Lottie).
 *
 * The overlay section's (first) Lottie hole plays once over the tab pages. Its playback is pushed into
 * every binary as `overlay.lottie.<id>.time` / `.done` — the header reveals its tabs on that
 * timeline, in-binary. The sequence is strict: the Lottie is prefetched while the screen loads
 * and held until the header is on screen and the pages have painted behind it ([awaitReady]), so
 * it plays → the tabs animate on its timeline → the page loads over it. What crosses binaries
 * stays here, as in jar:
 *  - the page reveal: fade in + [PAGE_REVEAL_SLIDE] slide up over [PAGE_REVEAL_MILLIS], starting
 *    [PAGE_REVEAL_LEAD_FRAMES] frames before the Lottie ends (or as soon as it fails to load);
 *  - the overlay fades out with it and leaves the composition once the page is fully in;
 *  - header taps are ignored while it plays ([isRunning]).
 *
 * No overlay section, or a Lottie hole without a URL (jar: no `guidingLottie`): no intro at all.
 * Whether the URL is there is the backend's call (it gets the opener's `args.visitCount`); a
 * screen whose intro was already shown passes `section = null` (jar `isGuidingLottieShown`).
 */
@Stable
class PagerIntro(val section: LoadedSection?) {
    /** The intro's Lottie hole; null = no intro at all. */
    val key: OverlayKey? = section?.let { OverlaySlots.keys(it.document, OverlayKind.Lottie).firstOrNull() }

    var playback by mutableStateOf(if (key == null) OverlayPlayback.Missing else OverlayPlayback.Loading)
        private set

    /** 0 → 1: the pages fade / slide in over the Lottie. 1 from the start when there is no intro. */
    val pageReveal = Animatable(if (key == null) 1f else 0f)

    /** The Lottie may start: the header is visible and the pages are ready behind it. */
    var ready by mutableStateOf(false)
        private set

    /** Loading or playing: the header waits on it and its taps are ignored. */
    val isRunning: Boolean get() = !playback.done

    /** jar `hasReachedLeadOrIdle(PAGE_REVEAL_LEAD_FRAMES)`: the page loads slightly before the Lottie's end. */
    val isPageRevealing: Boolean
        get() = playback.done ||
            (playback.durationSec > 0f && playback.timeSec >= playback.durationSec - PAGE_REVEAL_LEAD_FRAMES / playback.frameRate)

    /** jar `introLottieVisible`: the overlay stays until the page has fully revealed over it. */
    val isOverlayVisible: Boolean get() = key != null && pageReveal.value < 1f

    private var shownReported = false

    /**
     * [onShown] fires once, when playback passes the hole's `.shownAt` — jar `OnGuidingLottieShown`
     * at 60 %. The host turns it into the `media_shown{id}` host result; counting the visit is the
     * opener's job (it owns the count it passes back as `args.visitCount`).
     */
    fun onPlayback(key: OverlayKey, playback: OverlayPlayback, onShown: (OverlayKey) -> Unit) {
        if (key != this.key) return
        if (this.playback.done) return // finished is final (a late frame must not re-open the intro)
        if (this.playback.durationSec <= 0f && playback.durationSec > 0f) {
            Log.d(TAG, "guiding lottie played (${"%.2f".format(playback.durationSec)} s)") // jar OnGuidingLottiePlayed
        }
        this.playback = playback
        if (playback.done) Log.d(TAG, "intro done at ${"%.2f".format(playback.timeSec)} s (missing=${playback.missing})")
        val shownAt = playback.shownAt ?: return
        if (!shownReported && playback.durationSec > 0f && playback.timeSec >= playback.durationSec * shownAt) {
            shownReported = true
            Log.d(TAG, "guiding lottie shown: ${"%.0f".format(shownAt * 100)} % played")
            onShown(key)
        }
    }

    /** Host values every binary may read: the hole's `.time` / `.done` (jar: what `TabIntroState` exposes). */
    fun hostFloats(): Map<String, Float> {
        val k = key ?: return emptyMap()
        return mapOf(OverlaySlots.time(k) to playback.timeSec, OverlaySlots.done(k) to if (playback.done) 1f else 0f)
    }

    /**
     * Fetches the intro Lottie while the screen loads, into Lottie's cache under the same key
     * `rememberLottieComposition(Url)` reads — so it can start the moment the screen is ready.
     */
    fun prefetch(context: Context, values: Map<String, JsonPrimitive>) {
        val doc = section?.document ?: return
        val k = key ?: return
        val url = OverlaySlots.resolveUrl(namedTextDefault(doc, USER_PREFIX + OverlaySlots.url(k)), values) ?: return
        Log.d(TAG, "prefetching guiding lottie $url")
        LottieCompositionFactory.fromUrl(context, url)
    }

    /**
     * Holds the Lottie until the header is on screen ([headerPainted]) and the tab pages have
     * painted behind the overlay ([pagesPainted], capped at [PAGES_READY_TIMEOUT_MS]): nothing is
     * inflated while it plays, and every tab animation it drives is visible.
     */
    suspend fun awaitReady(headerPainted: () -> Boolean, pagesPainted: () -> Boolean) {
        if (key == null) return
        snapshotFlow { headerPainted() }.first { it }
        val pages = withTimeoutOrNull(PAGES_READY_TIMEOUT_MS) { snapshotFlow { pagesPainted() }.first { it } }
        ready = true
        if (playback.missing) return // nothing to play: the page is already shown
        Log.d(TAG, if (pages != null) "screen ready — intro starts" else "pages slow — intro starts anyway")
    }

    /** Runs the page reveal once the Lottie is near its end — jar `pageReveal.animateTo(1f, …)`. */
    suspend fun runPageReveal() {
        if (key == null) return
        snapshotFlow { isPageRevealing }.first { it }
        if (playback.missing) {
            // No URL: jar has no intro state at all, so the page is simply there.
            Log.d(TAG, "no guiding lottie — page shown")
            pageReveal.snapTo(1f)
            return
        }
        Log.d(TAG, "page reveal at ${"%.2f".format(playback.timeSec)} s of ${"%.2f".format(playback.durationSec)} s (done=${playback.done})")
        pageReveal.animateTo(1f, tween(PAGE_REVEAL_MILLIS, easing = LinearOutSlowInEasing))
        // The overlay (and its Lottie) leaves now. If its last frame never reported, finish here so
        // the header settles and the tabs unlock — jar's chips depend on `isCompleted` the same way.
        complete()
    }

    private fun complete() {
        if (playback.done) return
        Log.d(TAG, "intro done with the page reveal at ${"%.2f".format(playback.timeSec)} s")
        playback = playback.copy(timeSec = maxOf(playback.timeSec, playback.durationSec), done = true)
    }

    companion object {
        /** jar `TabIntroTimeline.PAGE_REVEAL_LEAD_FRAMES`. */
        const val PAGE_REVEAL_LEAD_FRAMES = 6

        /** jar `TabIntroTimeline.PAGE_REVEAL_DURATION_MILLIS`. */
        const val PAGE_REVEAL_MILLIS = 500

        /** jar `PAGE_REVEAL_SLIDE_DISTANCE`. */
        val PAGE_REVEAL_SLIDE = 48.dp

        /** Most the intro waits for the hidden pages to paint before it starts anyway. */
        const val PAGES_READY_TIMEOUT_MS = 3_000L
    }
}

/** A named `USER:` text slot's baked default, read off the parsed document (no player needed). */
@Suppress("RestrictedApi")
private fun namedTextDefault(document: CoreDocument, name: String): String? =
    document.operations.asSequence()
        .filterIsInstance<NamedVariable>()
        .firstOrNull { it.mVarName == name && it.mVarType == NamedVariable.STRING_TYPE }
        ?.let { document.getText(it.mVarId) }
