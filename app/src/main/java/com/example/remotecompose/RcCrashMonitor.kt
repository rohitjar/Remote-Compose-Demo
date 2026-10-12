package com.example.remotecompose

import android.util.Log
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.player.view.RemoteComposePlayer
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Crash detector for a RemoteCompose document. The player never lets a document exception
 * reach the app — it catches everything and paints its red error screen — so this is the
 * only way to know a binary died. Covers both failure modes the library has (alpha13):
 *
 *  - draw-phase: [arm] registers a reserved host-exception id on the document; the player's
 *    onDraw catch then fires the id-action channel, which [onAction] picks up.
 *  - load-phase (setDocument data ops, bitmap-memory guard): set private mDisable /
 *    mErrorMessage with no callback at all; [checkAfterFrame] reads them by reflection.
 *
 * One instance per document; each death is reported once, whichever path sees it first.
 * Usage: `arm(doc)` after loading, then pass [onAction] and call [checkAfterFrame] from
 * `update` on [androidx.compose.remote.player.compose.RemoteDocumentPlayer].
 */
class RcCrashMonitor(private val source: String) {
    private val reported = AtomicBoolean(false)

    /** Registers the crash channel on the document. Call once, right after init. */
    fun arm(document: CoreDocument) = document.setHostExceptionID(CRASH_ACTION_ID)

    /** Pass as the player's `onAction`; catches draw-phase exceptions. */
    val onAction: (Int, String?) -> Unit = { id, value ->
        if (id == CRASH_ACTION_ID) report("draw", value)
    }

    /**
     * Call from the player's `update` lambda: setDocument has already run by then, so a
     * load-phase failure is visible; checking on the next frame also covers the first draw.
     */
    fun checkAfterFrame(player: RemoteComposePlayer) {
        if (reported.get()) return
        player.post { if (!reported.get()) errorOrNull(player)?.let { report("load", it) } }
    }

    private fun report(phase: String, message: String?) {
        if (!reported.compareAndSet(false, true)) return
        Log.e(TAG, "RemoteCompose document crashed [$source, phase=$phase]: $message")
        // Crashlytics hookup goes here: recordException(RcDocumentException(source, phase, message))
    }

    private companion object {
        const val TAG = "RC_CRASH"

        /** Reserved id-action channel — baked docs use small sequential ids, so no collision. */
        const val CRASH_ACTION_ID = 0x00C0FFEE

        /**
         * mInner (RemoteComposePlayer) → mDisable/mErrorMessage (RemoteComposeView), resolved
         * once. Null if a library update renames them — monitoring then degrades to the
         * draw-phase channel instead of crashing the host.
         */
        val internals: Triple<Field, Field, Field>? by lazy {
            runCatching {
                val inner = RemoteComposePlayer::class.java.getDeclaredField("mInner")
                    .apply { isAccessible = true }
                val view = Class.forName("androidx.compose.remote.player.view.platform.RemoteComposeView")
                Triple(
                    inner,
                    view.getDeclaredField("mDisable").apply { isAccessible = true },
                    view.getDeclaredField("mErrorMessage").apply { isAccessible = true },
                )
            }.onFailure { Log.w(TAG, "player internals unavailable — load-phase crashes won't be detected", it) }
                .getOrNull()
        }

        /** The player's error message if its document is dead, null if healthy. */
        fun errorOrNull(player: RemoteComposePlayer): String? = runCatching {
            val (innerField, disableField, errorField) = internals ?: return null
            val inner = innerField.get(player) ?: return null
            if (!disableField.getBoolean(inner)) return null
            errorField.get(inner) as? String ?: "unknown error"
        }.getOrNull()
    }
}
